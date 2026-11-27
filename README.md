# Telnyx Voicebot PoC — Atendente Virtual Antonella (Java 21)

Microsserviço Spring Boot 3.5 / Java 21 com arquitetura hexagonal que dispara chamadas outbound para a assistente **Antonella** na Telnyx (`POST /v2/texml/ai_calls/{connection_id}`) e consolida automaticamente o pacote pós-chamada: transcrição turno a turno, gravação MP3, metadados (`startedAt`, `endedAt`, `durationSec`, `answeredBy`, `status`) e custo detalhado (`telephony` + `ai`).

> Preparado para futura incorporação ao monorepo `QR369-GCP-PROD` (`groupId: br.com.ia369`, pacote base `br.com.ia369.atendente`).

## Arquitetura

- **Domínio (`br.com.ia369.atendente.domain`)**: `CallRecord`, `CallStatus` (sealed interface), `TranscriptLine`, `CallCost`, `RecordingRef`, e portas (`TelephonyGateway`, `ConversationGateway`, `CostGateway`, `RecordingStorage`, `CallRepository`, `WebhookEventRepository`, `ResultNotifier`).
- **Aplicação (`br.com.ia369.atendente.application`)**: `StartCallUseCase`, `HandleCallStatusUseCase`, `HandleRecordingUseCase`, `HandleConversationEventUseCase`, `CollectCallDataUseCase` (com backoff exponencial) e `ReconcileStuckCallsJob` (com ShedLock + expurgo LGPD).
- **Adaptadores (`br.com.ia369.atendente.adapter`)**:
  - Entrada REST & Webhooks: `CallController`, `TelnyxWebhookController`, `SignatureVerifier` (Ed25519 + anti-replay), `FormOrJsonPayloadParser` (`x-www-form-urlencoded` e `json`).
  - Saída Telnyx: `TelnyxTelephonyAdapter`, `TelnyxConversationAdapter`, `TelnyxCostAdapter` encapsulando `com.telnyx.sdk:telnyx:6.98.0` + Resilience4j.
  - Persistência: PostgreSQL 16 + Flyway (`V1__init.sql`).
  - Storage: `LocalDiskStorage` (`/data/recordings`) ou `S3Storage` (S3 / MinIO).
  - Notificação: `HttpResultNotifier` com fila em banco (`outbound_notification`) e assinatura HMAC-SHA256 (`X-Atendente-Signature`).

## Endpoints Públicos (`/api/v1/calls`)

Todos os endpoints `/api/v1/**` exigem `Authorization: Bearer $API_AUTH_TOKEN`.

| Método e Rota | Descrição |
| --- | --- |
| `POST /api/v1/calls` | Dispara chamada outbound (`to` em E.164 brasileiro `+55...`, `from`, `externalRef`, `variables`, `callbackUrl`) → `202` |
| `GET /api/v1/calls/{id}` | Retorna o pacote completo da chamada |
| `GET /api/v1/calls/{id}/transcript?format=json\|txt` | Transcrição turno a turno |
| `GET /api/v1/calls/{id}/recording.mp3` | Stream do MP3 armazenado (`audio/mpeg`) |
| `GET /api/v1/calls?status=&from=&to=&externalRef=&page=0&size=20` | Listagem paginada |

## Webhooks Telnyx (`/webhooks/telnyx/**`)

Expostos publicamente com verificação obrigatória `telnyx-signature-ed25519` + `telnyx-timestamp` (janela de 5 min):

- `POST /webhooks/telnyx/call-status`
- `POST /webhooks/telnyx/recording`
- `POST /webhooks/telnyx/conversation`

Com `PUBLIC_BASE_URL=https://integra369.duckdns.org`, a aplicação envia à Telnyx as URLs `https://integra369.duckdns.org/webhooks/telnyx/call-status`, `/recording` e `/conversation` (basta o proxy reverso encaminhar `/webhooks/telnyx/` para a porta `8087`).

## Execução Local e Testes

```powershell
# Compilar e rodar testes (H2 em modo PostgreSQL + Flyway + WireMock)
mvn clean test

# Gerar JAR executável
mvn clean package
```

Para subir via Docker Compose:

```powershell
copy .env.example .env
# Preencha TELNYX_API_KEY, TELNYX_PUBLIC_KEY, TELNYX_ACCOUNT_SID, PUBLIC_BASE_URL, API_AUTH_TOKEN, NOTIFY_HMAC_SECRET
docker compose up --build
```

