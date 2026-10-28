# ADR 0001: Processamento assíncrono com a própria tabela como outbox

**Status:** aceito

## Contexto

Depois de criar uma solicitação, o serviço precisa chamar o provedor externo de ativação. O provedor pode ficar lento (de 400 ms para 3 s) ou indisponível. Uma solicitação aceita não pode se perder, mesmo que a aplicação caia entre gravar no banco e chamar o provedor. E chamada HTTP dentro de transação aberta segura conexão e locks pelo tempo da chamada.

## Decisão

- O `POST` grava a solicitação como `PENDING` e responde imediatamente.
- Um worker agendado (`ActivationProcessor`) busca solicitações vencidas com `SELECT … FOR UPDATE SKIP LOCKED LIMIT 1`.
- Para cada solicitação, o worker:
  1. faz o claim em transação curta: `PROCESSING`, `attempts + 1` e lease em `next_attempt_at`;
  2. chama o provedor **fora de transação**;
  3. grava o resultado em outra transação curta, descartando-o se a versão mudou.
- Toda tentativa envia `Idempotency-Key` = id da solicitação.

Não há broker de mensagens: a tabela `activation_request` é a fila.

## Consequências

- A latência da API não depende do provedor. O cliente acompanha o status por consulta.
- Nada se perde: o que foi commitado é processado, e um lease expirado (instância morta) torna a solicitação elegível de novo.
- Escala horizontal sem coordenação: `SKIP LOCKED` distribui as linhas entre instâncias.
- A entrega ao provedor é *at-least-once*. A deduplicação depende da chave de idempotência no provedor.
- Há latência de até um intervalo de polling (1 s) até a primeira chamada, e o polling gera consultas periódicas, baratas graças ao índice parcial.

## Alternativas consideradas

- **Chamada síncrona no POST:** API lenta e frágil quando o provedor degrada, e sem como fazer backoff de minutos.
- **`@Async` / evento em memória após o commit:** perde a solicitação se a aplicação cair antes de processar.
- **Broker (Kafka/RabbitMQ/SQS):** exigiria outbox para publicar com consistência e mais infraestrutura. Desproporcional para o escopo. Seria o caminho se outros serviços precisassem consumir os eventos.
