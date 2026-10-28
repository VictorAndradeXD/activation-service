# ADR 0003: Falha temporária permanece em PROCESSING com contador de tentativas

**Status:** aceito

## Contexto

Após uma falha temporária (5xx, timeout, 429), a solicitação será tentada de novo. O enunciado pergunta: ela volta para `PENDING` ou permanece em `PROCESSING` com contador de tentativas?

## Decisão

Permanece em `PROCESSING`, com `attempts` incrementado a cada tentativa, `next_attempt_at` com o horário da próxima (backoff com jitter) e `last_error` com o motivo. `PENDING` significa apenas "ainda não enviado".

## Consequências

- O status é honesto: depois de um timeout o provedor pode já ter ativado o produto. `PENDING` sugeriria que nada foi enviado.
- O worker trata da mesma forma um retry agendado e uma solicitação abandonada por uma instância que morreu: ambos são `PROCESSING` com `next_attempt_at` vencido. Não há um job de reconciliação separado.
- As transições continuam as do enunciado (`PENDING → PROCESSING → COMPLETED | FAILED`), mais `PROCESSING → PROCESSING` para nova tentativa. Nada volta para `PENDING`.
- Para o consumidor, `PROCESSING` com `attempts > 1` indica que a ativação está demorando por instabilidade.

## Alternativa considerada

Voltar para `PENDING`: exigiria distinguir "nunca enviado" de "enviado com resultado incerto" por outro campo, e permitiria uma transição para trás no ciclo de vida.
