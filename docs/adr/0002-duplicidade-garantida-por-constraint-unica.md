# ADR 0002: Duplicidade garantida por constraint única no banco

**Status:** aceito

## Contexto

Não pode haver duas solicitações com o mesmo `externalId`, inclusive quando duas requisições iguais chegam quase ao mesmo tempo, possivelmente em instâncias diferentes. Sistemas de origem fazem retry.

## Decisão

- `CONSTRAINT uk_activation_request_external_id UNIQUE (external_id)`.
- O service faz uma consulta prévia por `externalId`, apenas como atalho para retries comuns, e insere em transação própria. Se o insert violar a constraint, busca o registro vencedor.
- Mesmo conteúdo, incluindo o consumidor: **200** com o registro existente. Conteúdo diferente: **409**.

## Consequências

- Correto com qualquer número de threads ou instâncias: o banco serializa os inserts conflitantes.
- Retries são idempotentes para o cliente, que recebe o mesmo recurso.
- Em corridas, o banco registra um erro de violação no log. É esperado e tratado.

## Alternativas consideradas

- **Só verificar antes de inserir:** há uma janela entre o `SELECT` e o `INSERT`; duas requisições concorrentes passam pela verificação.
- **Lock na aplicação (`synchronized`, mutex):** não funciona com mais de uma instância.
- **Lock distribuído (Redis):** mais infraestrutura e modos de falha, para algo que o banco já garante.
- **`INSERT … ON CONFLICT DO NOTHING`:** equivalente e evita a exceção, mas acopla o código a SQL nativo do PostgreSQL. Viável se o volume de corridas for alto.
