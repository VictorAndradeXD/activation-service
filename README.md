# Activation Service

Serviço de ativação de produtos de clientes: recebe solicitações de sistemas de origem, registra, envia a um provedor externo de ativação e permite consultar o status.

```
 Sistema de origem                 activation-service                         Provedor externo
 ─────────────────   POST /api/v1/requests   ┌──────────────┐
  (JWT client_      ───────────────────────► │  API (MVC)   │──► INSERT (PENDING) ──┐
   credentials)     ◄─── 201 / 200 / 409 ─── └──────────────┘                       │
                                                                          ┌─────────▼─────────┐
                     GET /api/v1/requests/{id}                            │ activation_request│
                    ───────────────────────►  status atual  ◄──────────── │   (PostgreSQL)    │
                                                                          └─────────▲─────────┘
                                             ┌──────────────┐  claim (SKIP LOCKED)   │
                                             │    Worker    │◄───────────────────────┘
                                             │  (agendado)  │── POST /provider/v1/activate ──►
                                             └──────────────┘   Idempotency-Key, X-Correlation-Id
```

**Stack:** Java 21 · Spring Boot 3.5 (Spring MVC, Spring Data JPA/Hibernate, Spring Security Resource Server, Actuator) · PostgreSQL 17 · Flyway · Maven · JUnit 5, Mockito, AssertJ, Testcontainers, WireMock, ArchUnit · Docker.

## Sumário

- [Como executar](#como-executar)
- [API](#api)
- [Decisões técnicas](#decisões-técnicas)
- [Estratégia de testes](#estratégia-de-testes)
- [Cenário de análise](#cenário-de-análise)
- [Produção](#produção)
- [Pontos pendentes](#pontos-pendentes)
- [Melhorias futuras](#melhorias-futuras)
- [Uso de IA](#uso-de-ia)

Os registros de decisão estão em [`docs/adr/`](docs/adr).

---

## Como executar

**Pré-requisitos:** JDK 21 e Docker (os testes de integração usam Testcontainers).

### Testes

```bash
./mvnw verify
```

Um único comando roda os testes unitários (Surefire) e os de integração contra PostgreSQL real e provedor simulado (Failsafe, classes `*IT`).

### Ambiente completo (aplicação + banco + provedor simulado + servidor OAuth)

```bash
docker compose up --build
```

| Serviço         | Porta no host | Papel                                                                  |
|-----------------|---------------|------------------------------------------------------------------------|
| `app`           | 8080          | a aplicação                                                            |
| `postgres`      | 5433          | banco                                                                  |
| `provider-mock` | 8089          | WireMock simulando `POST /provider/v1/activate`                        |
| `oauth`         | 8090          | servidor OAuth 2.0 de teste ([mock-oauth2-server](https://github.com/navikt/mock-oauth2-server)) |

**1. Obter um token** (fluxo client credentials). Há dois clientes configurados em [`docker/oauth/oauth.json`](docker/oauth/oauth.json):
`erp-system` (escopos `activation:read activation:write`) e `reporting-system` (só `activation:read`).

```bash
TOKEN=$(curl -s -X POST localhost:8090/default/token \
  -d grant_type=client_credentials -d client_id=erp-system -d client_secret=qualquer \
  | jq -r .access_token)
```

**2. Criar e consultar:**

```bash
curl -i -X POST localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"
curl -s "localhost:8080/api/v1/requests?status=COMPLETED&customerId=CUST-10001&page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

**3. Simular os cenários do provedor:** o mock decide a resposta pelo `productCode`:

| `productCode` contém | Resposta do provedor             | Resultado esperado                                |
|----------------------|----------------------------------|---------------------------------------------------|
| (qualquer outro)     | 200 após 300 ms                  | `COMPLETED`                                       |
| `INVALID`            | 400                              | `FAILED` sem nova tentativa                       |
| `UNSTABLE`           | 500                              | retries com backoff até `FAILED` (5 tentativas)   |
| `SLOW`               | 200 após 10 s (> read timeout 5 s) | timeout, retries, por fim `FAILED`               |
| `THROTTLED`          | 429 com `Retry-After: 30`        | próxima tentativa só após 30 s                    |

### Rodar a aplicação fora do Docker

```bash
docker compose up -d postgres provider-mock oauth
./mvnw spring-boot:run
```

A configuração padrão em `application.yml` já aponta para essas portas.

---

## API

| Método e rota                     | Escopo             | Respostas                                                    |
|-----------------------------------|--------------------|--------------------------------------------------------------|
| `POST /api/v1/requests`           | `activation:write` | 201 (criada, header `Location`), 200 (retry), 400, 401, 403, 409 |
| `GET /api/v1/requests/{id}`       | `activation:read`  | 200, 400 (id malformado), 401, 403, 404                      |
| `GET /api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | 200 paginado, 400                     |
| `GET /actuator/health/{liveness,readiness}`, `/actuator/prometheus` | público | |

**Validação do corpo:** `externalId`, `customerId` e `productCode` são obrigatórios, têm no máximo 64 caracteres e aceitam só letras, números e `. _ : -`.
Paginação: `page` ≥ 0 e `size` entre 1 e 100 (padrão 20). A ordenação é fixa, `createdAt` decrescente, para não aceitar ordenação por campos arbitrários.

**Resposta:**

```json
{
  "id": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "FAILED",
  "attempts": 1,
  "failureReason": "Provedor rejeitou a solicitação (HTTP 400)",
  "createdAt": "2026-10-26T14:03:11.123Z",
  "updatedAt": "2026-10-26T14:03:12.456Z"
}
```

Datas sempre em UTC, ISO-8601. `failureReason` só aparece em `FAILED`. Os DTOs são `record`s separados da entidade JPA.

---

## Decisões técnicas

### Organização do código

Pacotes por responsabilidade, com poucas camadas:

```
domain/         entidade ActivationRequest (regras e transições), status, repositório
application/    casos de uso: criação/consulta, processamento, política de retry, porta do provedor
api/            controller, DTOs, tratamento de erros
integration/    cliente HTTP do provedor
security/       configuração do resource server
observability/  correlation id
```

As regras de dependência são verificadas com ArchUnit (`ArchitectureTest`): `domain` e `application` não conhecem HTTP, e ninguém depende de `api` ou `integration`.
A única interface "de arquitetura" é `ActivationProvider`. Ela existe porque o processamento precisa ser testável sem HTTP e porque o cliente HTTP é um detalhe trocável. Não há mappers, factories ou camadas adicionais.

### Modelo de domínio e estados

```
PENDING ──► PROCESSING ──► COMPLETED
               │  ▲   └──► FAILED
               └──┘  nova tentativa após falha temporária
```

- **Transições controladas pelo domínio.** A entidade não tem setters; o status muda por `startAttempt`, `complete`, `fail` e `scheduleRetry`, que validam a transição com `ActivationStatus.canTransitionTo`. `COMPLETED` e `FAILED` são finais. Os testes unitários cobrem isso sem Spring.
- **Falha temporária: permanece em `PROCESSING` com contador de tentativas** (`attempts`) e horário da próxima tentativa (`next_attempt_at`). Voltar para `PENDING` daria a entender que nada foi enviado. Depois de um timeout, porém, o provedor pode já ter ativado o produto; `PROCESSING` é o estado honesto: "enviado, aguardando resultado final". Detalhes no [ADR 0003](docs/adr/0003-falha-temporaria-permanece-em-processing.md).
- **Lock otimista** (`@Version`). Protege a atualização de status contra escrita concorrente. Exemplo: um worker lento termina a chamada depois que o lease expirou e outro worker já retomou a solicitação. A gravação do resultado antigo falha ou é descartada em vez de sobrescrever o estado.

### Duplicidade (item 3)

**Estratégia:** constraint `UNIQUE (external_id)` no PostgreSQL.

**Por que "verificar se existe e depois inserir" não basta:** entre o `SELECT` e o `INSERT` existe uma janela. Duas requisições simultâneas fazem o `SELECT`, nenhuma encontra registro, e as duas inserem. Nenhum código na aplicação fecha essa janela sozinho quando há mais de uma thread ou instância. Só o banco enxerga as duas transações. Com a constraint, o segundo `INSERT` falha.

**Fluxo em `ActivationRequestService.create`:**
1. Busca por `externalId`. É só um atalho para o caso comum (retry dias depois), evitando uma exceção.
2. Se não achou, insere em transação curta.
3. Se o insert violar a constraint (outra requisição venceu a corrida), busca o registro vencedor e decide a resposta como no passo 1.

O teste `concurrentIdenticalRequestsCreateExactlyOneRecord` dispara 16 requisições simultâneas contra PostgreSQL real. No log aparecem as violações de `uk_activation_request_external_id`, prova de que a corrida acontece e é resolvida: exatamente um registro, uma resposta 201 e as demais 200.

| Cenário | Resposta | Justificativa |
|---|---|---|
| Mesmo `externalId`, mesmo conteúdo (retry legítimo) | **200** com o registro existente e `Location` | A operação é idempotente: o sistema de origem recebe o mesmo recurso e pode seguir o fluxo normal. Um 409 obrigaria todo cliente a tratar o próprio retry como erro. O 200 (em vez de 201) informa que nada novo foi criado. |
| Mesmo `externalId`, conteúdo diferente | **409 Conflict**, `type: /problems/duplicate-request`, "O externalId informado já foi utilizado em uma solicitação com dados diferentes." | É erro do cliente: reutilizou um identificador para outra coisa. Retornar o registro existente esconderia o problema. |
| Duas requisições idênticas em paralelo | Uma recebe **201**; a outra, **200** com o mesmo registro | A perdedora cai na violação da constraint e é tratada como retry legítimo. |

"Mesmo conteúdo" inclui o consumidor (dono). Se outro sistema enviar um `externalId` já usado, recebe 409 sem nenhum dado do registro existente.

### Integração com o provedor (item 5)

**Assíncrono.** O `POST` grava a solicitação como `PENDING` e responde de imediato. Um worker agendado envia ao provedor. Motivos:
- O tempo de resposta da API não depende do provedor. Se ele passa de 400 ms para 3 s, a API continua rápida.
- Um provedor lento não consome threads do Tomcat nem conexões do banco da API.
- Retries com backoff de minutos são impossíveis dentro de uma requisição HTTP.

O custo é que o cliente consulta o status (ou, no futuro, recebe um evento; ver [Microsserviços](#microsserviços-e-cloud)).

**Transação × chamada remota.** A própria tabela funciona como *outbox* ([ADR 0001](docs/adr/0001-processamento-assincrono-com-outbox-na-tabela.md)): se a solicitação foi commitada, ela será enviada, mesmo que a aplicação caia logo após o commit. O worker (`ActivationProcessor`) trata cada solicitação em três passos, e **a chamada HTTP nunca acontece com transação aberta**:

1. **Claim** (transação curta): `SELECT … FOR UPDATE SKIP LOCKED LIMIT 1`, marca `PROCESSING`, incrementa `attempts` e define um *lease* (`next_attempt_at = agora + 30 s`).
2. **Chamada ao provedor**, sem transação.
3. **Registro do resultado** (transação curta), descartado se outro worker retomou a solicitação nesse meio-tempo (versão diferente).

Se a instância morrer durante a chamada, o lease expira e a solicitação volta a ser elegível. Esse é o processo de reconciliação para solicitações presas em `PROCESSING`, embutido no próprio worker e coberto por `requestAbandonedMidCallIsReclaimedWhenTheLeaseExpires`. O `SKIP LOCKED` permite várias instâncias processando em paralelo sem pegar a mesma linha e sem bloquear umas às outras.

**Timeout é ambíguo → chave de idempotência.** Toda tentativa da mesma solicitação envia `Idempotency-Key: <id da solicitação>`. Pressupõe-se que o provedor deduplica por essa chave (prática comum, como no Stripe), então repetir após timeout não ativa o produto duas vezes. O id também vai no corpo (`requestId`).

**Comportamento por retorno:**

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| Sucesso (2xx) | `COMPLETED` | — | Ativado. Uma repetição com a mesma chave também retorna 2xx. |
| HTTP 400 (e demais 4xx não listados) | `FAILED` | Não | A requisição em si é inválida para o provedor; repetir dá o mesmo resultado. O motivo fica em `failureReason`. |
| HTTP 500 (5xx) e 408 | `PROCESSING` | Sim, com backoff | Falha do lado do provedor, normalmente transitória. |
| Timeout / erro de conexão | `PROCESSING` | Sim, com backoff | Resultado desconhecido; a chave de idempotência torna a repetição segura. |
| HTTP 429 / 503 com `Retry-After` | `PROCESSING` | Sim, não antes do tempo pedido | O provedor pediu para esperar. Vale o maior entre o `Retry-After` (segundos ou data HTTP) e o backoff calculado. |
| HTTP 401 / 403 | `PROCESSING` | Sim, com backoff, e log de erro | O problema é a credencial deste serviço (expirada, rotacionada), não a solicitação do cliente. Marcar como `FAILED` puniria o cliente por um erro nosso. |
| Limite de tentativas atingido | `FAILED` | Não | `failureReason` = "Limite de N tentativas atingido. Último erro: …". |

**Backoff e limites** (configuráveis em `app.processing.*`):
- Backoff exponencial a partir de 2 s, com teto de 5 min.
- Jitter: metade do atraso é fixa e metade aleatória, para que solicitações que falharam juntas não voltem todas no mesmo instante.
- Máximo de 5 tentativas.

**Timeouts explícitos:** conexão de 2 s e leitura de 5 s (`app.provider.*`), no `RestClient` sobre o `HttpClient` do JDK. O lease (30 s) é maior que conexão + leitura, para que uma chamada lenta não seja duplicada por outro worker.

**Circuit breaker não implementado, por decisão.** Com o processamento assíncrono, o backoff por solicitação já reduz a pressão sobre um provedor fora do ar, e a API não sente a falha. O breaker evitaria que o worker continuasse disparando tentativas quando *todas* falham, mas acrescenta estado e configuração. Seria o próximo passo se o volume crescer; está em [Melhorias futuras](#melhorias-futuras).

### Tratamento de erros (item 6)

Todas as respostas de erro seguem **Problem Details (RFC 9457)**, `application/problem+json`, com `traceId`. O mesmo id está no log (MDC) e no header `X-Correlation-Id` da resposta.

| Situação | Status | `type` |
|---|---|---|
| Campos inválidos (com lista `errors[]` por campo) | 400 | `/problems/validation-error` |
| JSON malformado ou ausente | 400 | `/problems/malformed-request` |
| Sem token ou token inválido | 401 (+ `WWW-Authenticate`) | `/problems/unauthorized` |
| Escopo insuficiente | 403 | `/problems/forbidden` |
| Solicitação inexistente (ou de outro consumidor) | 404 | `/problems/not-found` |
| `externalId` reutilizado com dados diferentes | 409 | `/problems/duplicate-request` |
| Banco indisponível | 503 | `/problems/service-unavailable` |
| Erro inesperado | 500, mensagem genérica, stack trace só no log | `/problems/internal-error` |

**Erro de integração:** como a chamada ao provedor é assíncrona, uma falha do provedor nunca vira erro HTTP para o consumidor. Ela aparece no recurso como retry (`PROCESSING` com `attempts` > 1) ou como `FAILED` com `failureReason`.

### Segurança (item 7, implementada)

- **Fluxo OAuth 2.0: client credentials.** Os consumidores são sistemas, não pessoas. Não há usuário para consentir nem navegador para redirecionar. Cada sistema tem seu `client_id`/secret (ou, melhor, `private_key_jwt`/mTLS) no servidor de autorização e obtém um JWT de curta duração.
- **O serviço é um Resource Server** (Spring Security) e valida no JWT:
  - a assinatura, com chaves obtidas do endpoint **JWKS** do servidor de autorização (`jwk-set-uri`; o Spring faz cache e recarrega ao ver um `kid` novo, o que permite rotação de chaves sem deploy);
  - o emissor (`iss`), a audiência (`aud = activation-service`, impedindo reuso de token emitido para outra API) e a validade (`exp`/`nbf`).
- **Autorização por escopo:** `activation:write` para criar e `activation:read` para consultar ou listar. Rotas não mapeadas: `denyAll`.
- **Isolamento entre consumidores (BOLA, OWASP API1):** cada solicitação guarda o `sub` do token que a criou, e toda consulta é filtrada por ele. Pedir o id de outro consumidor retorna **404**, não 403, para não revelar que o registro existe.
- Testes de **401 e 403 sem servidor de autenticação real**, com `jwt()` do `spring-security-test`.
- **Credencial do provedor externo:** lida de variável de ambiente (`PROVIDER_API_KEY`) e nunca logada. Em produção viria de um gerenciador de segredos (AWS Secrets Manager, GCP Secret Manager, Vault), injetado no contêiner. Rotação sem indisponibilidade:
  1. O provedor aceita duas chaves simultâneas.
  2. A nova chave é publicada no gerenciador e os pods são reiniciados gradualmente.
  3. A antiga é revogada.

  Se o provedor suportar OAuth, o ideal é client credentials também na saída, com token curto renovado automaticamente.
- **Limite de corpo e de taxa:** não implementados na aplicação. Ver [Pontos pendentes](#pontos-pendentes).

### Índices (listagem paginada)

A listagem é **sempre** filtrada pelo consumidor (BOLA) e ordenada por `created_at DESC`. Por isso os índices começam por `client_id` e terminam em `created_at`, o que permite ler a página já ordenada, sem *sort*:

| Índice | Atende |
|---|---|
| `(client_id, created_at DESC)` | listagem sem filtros |
| `(client_id, customer_id, created_at DESC)` | filtro por cliente final, o mais seletivo |
| `(client_id, status, created_at DESC)` | filtro por status, por exemplo "minhas `FAILED`" |
| `UNIQUE (external_id)` | duplicidade e o atalho de retry |
| parcial `(next_attempt_at) WHERE status IN ('PENDING','PROCESSING')` | polling do worker; só contém o que não terminou, então continua pequeno mesmo com milhões de `COMPLETED` |

O trade-off é que cada índice encarece o `INSERT`. Com o volume esperado (escrita moderada, leitura frequente) compensa. Se as métricas mostrarem que um filtro não é usado, o índice correspondente sai.

### Observabilidade

- **Actuator:** `/actuator/health/liveness` e `/actuator/health/readiness` (readiness inclui o banco).
- **Correlation id:**
  - É aceito do header `X-Correlation-Id`, se tiver formato seguro, ou gerado.
  - Vai para o MDC (todas as linhas de log), para a resposta, para os erros e é **gravado com a solicitação**. Assim o worker, que roda depois e em outra thread, usa o mesmo id no log e o envia ao provedor.
- **Logs estruturados JSON** (formato ECS, recurso nativo do Spring Boot 3.4+), ativados no compose por `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`. Tokens e a API key nunca são logados.
- **Métricas Prometheus** (`/actuator/prometheus`):
  - `http_server_requests_seconds`: latência da API, com histograma para p95/p99.
  - `http_client_requests_seconds{client_name="…",status,outcome}`: latência e taxa de erro do provedor.
  - `activation_provider_responses_total{outcome}`: activated, rejected ou temporary_failure.
  - `activation_requests_finished_total{status}`: solicitações concluídas e falhas.
  - `activation_requests_backlog{status}`: quantas estão em `PENDING`/`PROCESSING`. É um *gauge* consultado no banco via índice parcial; os estados finais são contadores, porque contar uma tabela que só cresce a cada scrape seria caro.

---

## Estratégia de testes

`./mvnw verify` roda tudo. A divisão segue o que cada nível consegue provar:

| Nível | Classes | O que garante | Por que nesse nível |
|---|---|---|---|
| **Unitário, sem Spring** | `ActivationRequestTest`, `RetryPolicyTest`, `RetryAfterTest` | transições válidas e inválidas; backoff exponencial, teto e jitter; parsing do `Retry-After` | regras puras: rápidas, determinísticas, sem infraestrutura |
| **Fatia web** (`@WebMvcTest` + Mockito) | `ActivationRequestControllerTest` | contrato HTTP: 201 + `Location`, 200 no retry, 400 com erros por campo, 404, 409, 500 sem vazar detalhes, **401 sem token, 403 por escopo**, dono vindo do `sub` | testa serialização, validação, segurança e Problem Details sem banco; o service é mockado |
| **Integração** (Testcontainers PostgreSQL + WireMock) | `ActivationRequestApiIT`, `ActivationProcessingIT` | **concorrência real contra a constraint** (16 requisições simultâneas → 1 registro); BOLA; listagem; sucesso, 400, 500 com retry, limite de tentativas, **timeout**, `Retry-After`, recuperação de lease expirado; mesma `Idempotency-Key` em todas as tentativas; traceId propagado ao provedor | duplicidade, `SKIP LOCKED`, lock otimista e timeouts só se provam com banco e HTTP de verdade; H2 não reproduz o comportamento de concorrência do PostgreSQL |
| **Arquitetura** (ArchUnit) | `ArchitectureTest` | regras de dependência entre camadas | evita erosão da estrutura com o tempo |

Duas escolhas tornam os testes de integração determinísticos:
- O worker agendado fica desligado no perfil de teste, e os testes chamam `processDueRequests()` diretamente.
- O tempo é controlado por um `Clock` injetável (`MutableClock`). Avançar o relógio substitui `sleep` para testar backoff e lease.

PostgreSQL e WireMock sobem uma única vez e são compartilhados pelas classes, o que mantém o contexto Spring em cache.

---

## Cenário de análise

> API 300 ms → 2,5 s · CPU 45% · memória 60% · algumas consultas 50 ms → 1,2 s · provedor 400 ms → 3 s

**1. O que investigaria primeiro?**
Primeiro, **o que mudou e quando**: deploy, migração, pico de tráfego, crescimento de dados, mudança no provedor. Em seguida, **onde o tempo da API está sendo gasto**. CPU (45%) e memória (60%) estão confortáveis, então a aplicação não está calculando mais: as threads estão **esperando**. Os candidatos são espera por conexão do pool, por lock ou por I/O (banco e provedor). Começaria pelos endpoints mais lentos (p99 de `http_server_requests` por `uri`) e pelas métricas do pool de conexões.

**2. Como identificar se é aplicação, banco ou integração?**
Decompondo a latência de uma requisição lenta:
- **Tracing distribuído** (ou o traceId nos logs): mostra quanto tempo cada span passou no banco, no provedor ou dentro da aplicação.
- **Pool Hikari:** se `hikaricp_connections_pending` > 0 e `hikaricp_connections_acquire_seconds` alto, o tempo vai para *esperar* conexão, não para executar a query. O banco pode estar saudável e a aplicação segurando conexões.
- **Banco:** `pg_stat_statements` (quais queries pioraram, quantas chamadas, tempo médio), `pg_stat_activity` (sessões `idle in transaction`, esperas por lock em `wait_event`), `EXPLAIN (ANALYZE, BUFFERS)` nas consultas lentas, I/O e conexões do banco.
- **Integração:** `http_client_requests` do provedor por status e outcome, e se o atraso também aparece do lado deles (status page, outros clientes).
- **JVM:** um *thread dump* mostra onde as threads estão paradas (`getConnection`, leitura de socket, `synchronized`). Pausas de GC descartam ou confirmam a aplicação como causa.

**3. Logs e métricas úteis.**
- p50/p95/p99 da API por endpoint.
- Latência e taxa de erro/timeout do provedor.
- Pool Hikari: ativas, ociosas, pendentes e tempo de aquisição.
- Threads do Tomcat ocupadas.
- `pg_stat_statements`, slow query log (`log_min_duration_statement`), locks.
- GC e heap.
- Backlog do worker (`activation_requests_backlog`).
- Throughput (requisições por segundo), para saber se o problema é carga.
- Logs com traceId e duração por etapa.

**4. Melhorias possíveis.**
- **Banco:** índices adequados aos filtros reais (validados com `EXPLAIN`), paginação com limite, revisão de N+1, `VACUUM`/estatísticas, pool dimensionado ao banco e não ao número de threads.
- **Integração:** timeouts menores e explícitos, nunca chamar o provedor com transação aberta, processamento assíncrono (como aqui), circuit breaker e *bulkhead* (pool de threads separado) para que um provedor lento não contamine o resto.
- **Carga:** escalar horizontalmente, réplica de leitura para as consultas, cache para dados de leitura frequente.

**5. Hipótese de causa comum.**
**Conexões do banco presas durante a chamada ao provedor.** Se algum fluxo faz a chamada HTTP *dentro* de uma transação, por exemplo um `@Transactional` envolvendo o "salvar e chamar o provedor":
1. O provedor passa de 400 ms para 3 s.
2. Cada requisição passa a segurar uma conexão por 3 s em vez de 0,4 s, e o pool (por exemplo, 10 conexões) esgota.
3. As demais requisições, inclusive as que só fazem consultas, ficam esperando conexão. Essa espera aparece como "consulta lenta" (50 ms → 1,2 s), embora a query em si continue rápida.
4. A API sobe para 2,5 s com CPU baixa, porque as threads só esperam.

Linhas `FOR UPDATE` presas durante a chamada, bloqueando outras transações, produzem o mesmo efeito.

**Como confirmar:**
- `hikaricp_connections_pending` alto e `active` no máximo, junto com a latência do provedor.
- `pg_stat_activity` mostrando muitas sessões `idle in transaction` com `query_start` antigo.
- Thread dump com threads em `HikariPool.getConnection` e outras em leitura de socket do cliente HTTP.
- Correlação temporal: a piora do banco começa junto com a do provedor.
- Reprodução em homologação: atrasar o mock do provedor e observar o pool esgotar.

Este serviço foi desenhado para evitar exatamente isso: claim, chamada e resultado são passos separados, sem transação durante o HTTP.

Hipótese alternativa: um pico de tráfego explicaria as três coisas, com o provedor sob mais carga e mais consultas concorrentes. O throughput descarta ou confirma.

**6. Como validar a correção depois de publicada.**
- Definir antes o critério de sucesso. Exemplo: p95 da API < 500 ms, `connections_pending` = 0, consultas de volta a ~50 ms.
- Publicar em **canário** ou para uma fração das instâncias e comparar as duas versões nos mesmos painéis, sob o mesmo tráfego.
- Acompanhar o mesmo dashboard da investigação (latência da API, pool, `pg_stat_statements`, latência do provedor) por um período que inclua o horário de pico.
- Garantir que o backlog do worker não está crescendo, para não trocar lentidão síncrona por fila acumulada.
- Manter alertas de SLO e um teste de carga que reproduza o cenário (provedor a 3 s), para virar teste de regressão.

---

## Produção

**Como executaria:**
- A imagem do `Dockerfile` vai para um registry (ECR/GCR/ACR) e roda em um orquestrador de contêineres (Kubernetes/EKS/GKE, ECS ou Cloud Run), com **2+ réplicas** atrás de um load balancer ou API gateway.
- **Configuração por variáveis de ambiente** (relaxed binding do Spring: `SPRING_DATASOURCE_URL`, `APP_PROVIDER_BASEURL`, …).
- **Segredos** (senha do banco, `PROVIDER_API_KEY`) vêm do gerenciador de segredos do provedor cloud, nunca da imagem ou do repositório.
- **Probes:** `livenessProbe` → `/actuator/health/liveness`; `readinessProbe` → `/actuator/health/readiness`, que inclui o banco. Uma instância sem banco sai do balanceamento mas não é reiniciada em loop.
- **Desligamento gracioso:**
  - `server.shutdown=graceful` (20 s): termina requisições em andamento.
  - O agendador aguarda a rodada do worker terminar.
  - O `stop_grace_period`/`terminationGracePeriodSeconds` deve ser maior que isso.
  - Se o processo morrer mesmo assim, o lease garante a retomada.
- **JVM:** `-XX:MaxRAMPercentage=75` ajusta o heap ao limite de memória do contêiner. `ExitOnOutOfMemoryError` faz o orquestrador reiniciar em vez de deixar um processo degradado. Requests e limits de CPU e memória definidos.
- **Banco gerenciado** (RDS/Cloud SQL) com backups, réplicas e `max_connections` dimensionado para réplicas × pool.
- **Migrações Flyway:**
  - Hoje rodam na subida da aplicação, o que é simples, e o Flyway usa lock para que só uma instância migre.
  - Em produção, prefiro um *job* separado no pipeline, antes do rollout.
  - As migrações são sempre compatíveis com a versão anterior (*expand/contract*), para permitir rollback e rolling update.
- **Actuator/Prometheus** em porta de gerenciamento separada (`management.server.port`), acessível só da rede interna. No compose ficam públicos para facilitar.

### Microsserviços e cloud

- **Várias instâncias:**
  - A **duplicidade** continua garantida porque a regra está na constraint do banco, não na memória de uma instância.
  - O **processamento** escala horizontalmente com `FOR UPDATE SKIP LOCKED`: cada instância pega linhas diferentes, sem coordenação e sem líder.
  - O **lease** cobre instâncias que morrem e o **`@Version`** cobre corridas no registro do resultado.
  - Não há estado em memória, nem sessão nem cache, então qualquer instância atende qualquer requisição.
- **Configurações e segredos:** configuração não sensível em variáveis de ambiente ou ConfigMap; segredos no Secrets Manager/Vault, montados como variável ou arquivo e rotacionáveis sem rebuild.
- **Avisar outros serviços quando uma ativação termina:** publicar um evento `ActivationCompleted`/`ActivationFailed` em um tópico (Kafka, SNS/SQS, Pub/Sub).
  - Para não perder eventos, nem publicar evento de algo que não foi gravado, usar o mesmo padrão *outbox*: na transação que muda o status, inserir também uma linha em `outbox_event`.
  - Um publicador (o mesmo esquema de polling deste worker, ou CDC com Debezium) envia ao broker.
  - A entrega é *at-least-once*, então os consumidores deduplicam pelo id do evento.
- **CI/CD:**
  1. Pull request: `./mvnw verify` (unitários + integração) e análise estática/dependências (Sonar, Dependabot, Trivy na imagem).
  2. Merge na `main`: build da imagem com tag do commit, scan e push para o registry.
  3. Deploy automático em homologação, migração (job) e *smoke tests*.
  4. Promoção da **mesma imagem** para produção (aprovação manual ou automática) com rolling update ou canário, monitorando as métricas de erro e latência, com rollback automático se o SLO degradar.

  O pipeline implementado (`.github/workflows/ci.yml`) cobre build, testes e build da imagem.

---

## Pontos pendentes

O que não foi implementado e como faria:

- **Limite de tamanho do corpo e rate limiting:** no API gateway ou ingress (por exemplo, `client_max_body_size 16k` e limite por `client_id` do token), que é onde já se conhece o consumidor e a proteção chega antes da aplicação. Se precisasse ser na aplicação: um filtro rejeitando `Content-Length` acima de alguns KB com 413, e Bucket4j por `sub` do JWT com 429 e `Retry-After`.
- **Circuit breaker no provedor:** Resilience4j no `HttpActivationProvider`. Com o circuito aberto, a resposta vira `TemporaryFailure` sem chamar o provedor, então o domínio não muda.
- **Reconciliação de `FAILED` após timeouts:** se o limite de tentativas for atingido só com timeouts, o produto pode ter sido ativado. Com um endpoint de consulta do provedor (`GET /provider/v1/activations/{idempotencyKey}`), um job confirmaria o estado real antes de marcar `FAILED`, ou corrigiria depois.
- **Reprocessamento manual** de uma solicitação `FAILED` (endpoint administrativo com escopo próprio), para quando a causa for corrigida, por exemplo uma credencial.
- **Tracing distribuído com OpenTelemetry:** `micrometer-tracing-bridge-otel` e exportador OTLP. O correlation id próprio passaria a ser o `traceId` W3C (`traceparent`), propagado automaticamente pelo `RestClient`. Como a chamada ao provedor é assíncrona, o contexto precisaria ser persistido com a solicitação, como já é feito com o correlation id.
- **Unicidade de `externalId` por consumidor:** hoje é global, como pede o enunciado. Com muitos sistemas de origem, dois sistemas podem gerar o mesmo id legitimamente. Nesse caso a constraint passaria a ser `UNIQUE (client_id, external_id)`.

## Melhorias futuras

- **Notificação de conclusão** por evento (outbox → tópico) ou webhook, para o sistema de origem não precisar fazer polling.
- **Disparo imediato após o commit**, além do polling: um `@TransactionalEventListener(AFTER_COMMIT)` aciona o worker e a latência até o provedor cai de até 1 s para quase zero. O polling continua como garantia.
- **Paralelismo no worker:** hoje cada instância processa uma solicitação por vez. Com volume alto, processar o lote com *virtual threads* (Java 21), limitado por um semáforo para não sobrecarregar o provedor.
- Porta de gerenciamento separada, dashboards e alertas (SLO da API, backlog crescente, taxa de `FAILED`, erros 401/403 do provedor indicando credencial expirada).
- Testes de contrato com o provedor (Pact ou Spring Cloud Contract) e teste de carga (Gatling/k6) no pipeline.
- Expurgo ou arquivamento de solicitações finalizadas antigas, ou particionamento por data, se a tabela crescer muito.
- Documentação OpenAPI (springdoc) gerada a partir do código.

---

## Uso de IA

Este projeto foi desenvolvido com o **Claude Code** (Anthropic) como assistente de programação, em sessões de pair programming:

- As decisões de design (processamento assíncrono com outbox na tabela, constraint única para duplicidade, falha temporária mantida em `PROCESSING`, semântica 200/409, tabela de comportamento do provedor) foram discutidas e revisadas antes da implementação.
- O assistente gerou código, testes, configuração Docker/CI e o rascunho desta documentação.
- Tudo foi verificado executando `./mvnw verify` e testando o ambiente do `docker compose` ponta a ponta: token OAuth real, cada cenário do provedor, propagação do correlation id e métricas.
- Os commits trazem a linha `Co-Authored-By` correspondente.
