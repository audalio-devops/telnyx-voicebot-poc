# Plano de Implementação — Atendente Virtual Telnyx (Java 21)

Nov 25, 2026 · @Audalio Dev

## Objetivo e escopo

Construir um microsserviço Java 21, empacotado em container Docker, que dispara chamadas com a assistente **Antonella** da Telnyx e, ao fim de cada chamada, entrega à ferramenta existente um pacote completo: transcrição, áudio MP3 e metadados (data, hora, duração, status e custo).

**Dentro do escopo**

- Chamada outbound para fixo ou celular, via `POST /v2/texml/ai_calls/{connection_id}` com `AIAssistantId = assistant-6585b0b1-550a-4d7d-948c-eb4e3538e674` (o mesmo fluxo já validado no curl).
- Coleta pós-chamada: transcrição turno a turno, gravação MP3 armazenada localmente, horários, duração, status final, resultado de AMD (humano/caixa postal) e custo.
- API REST própria para a ferramenta existente disparar chamadas e consultar resultados, mais notificação (webhook de saída) quando o pacote estiver pronto.
- Execução em container, configurada só por variáveis de ambiente.

**Fora do escopo nesta versão**

- Chamadas inbound (receber ligações).
- Edição do prompt/comportamento da Antonella — continua no portal Telnyx.
- Discador em massa/campanhas com fila e controle de concorrência (fica preparado, mas não implementado).

## Visão geral da arquitetura

Um único serviço novo (“Atendente Java”) fica entre a ferramenta existente e a Telnyx: dispara a chamada, recebe os callbacks, reúne transcrição, áudio e custo e entrega tudo de uma vez.

&#91;embedded content: sequência de uma chamada · 4 participantes, 10 passos\]

Os passos 4 a 6 chegam de forma assíncrona e em qualquer ordem; o passo 7 tem de ocorrer em até 10 minutos do fim da chamada, e os passos 8 e 9 são repetidos com backoff até os dados ficarem disponíveis.

## APIs Telnyx utilizadas

Oito endpoints e callbacks cobrem tudo o que foi pedido; a base é `https://api.telnyx.com/v2` com `Authorization: Bearer $TELNYX_API_KEY`.

| Dado | Endpoint / evento | Campos que usamos |
| --- | --- | --- |
| Iniciar chamada | [`POST /texml/ai_calls/{connection_id}`](https://developers.telnyx.com/api-reference/texml-rest-commands/initiate-an-outbound-ai-call) | Entrada: `From`, `To`, `AIAssistantId`, `AIAssistantDynamicVariables`, `StatusCallback`, `StatusCallbackEvent`, `ConversationCallback`, `Record`, `RecordingChannels`, `RecordingStatusCallback`, `MachineDetection`, `Timeout`, `TimeLimit`. Saída: `call_sid`, `status` |
| Ciclo de vida | Status callback TeXML (eventos `initiated ringing answered completed no-answer busy canceled failed`) | `CallSid`, `CallStatus`, `AccountSid`; dispara a coleta no `completed` |
| Conversa | `ConversationCallback` (`conversation_created`, `conversation_ended`) | `conversation_id` da chamada |
| Transcrição | [`GET /ai/conversations`](https://developers.telnyx.com/api-reference/conversations/list-conversations) + [`GET /ai/conversations/{conversation_id}/messages`](https://developers.telnyx.com/api-reference/conversations/get-conversation-messages) | Filtro `metadata->call_control_id=eq.<id>`; metadados `call_session_id`, `call_leg_id`; mensagens com `role` e texto, incluindo chamadas de ferramenta |
| Áudio MP3 | `RecordingStatusCallback` + [`GET /texml/Accounts/{account_sid}/Calls/{call_sid}/Recordings.json`](https://developers.telnyx.com/api-reference/texml-rest-commands/fetch-recordings-for-a-call) | `media_url` (MP3), `duration`, `sid` |
| Data, hora, duração, status | [`GET /texml/Accounts/{account_sid}/Calls/{call_sid}`](https://developers.telnyx.com/api-reference/texml-rest-commands/fetch-a-call) | `start_time`, `end_time`, `duration`, `status`, `answered_by`, `direction`, `price`, `price_unit` |
| Custo total (telefonia + IA) | [`GET /session_analysis/call-session/{call_session_id}?max_depth=3`](https://developers.telnyx.com/docs/reporting/session-analysis) | `cost.total`, `cost.currency`, `cost.event_cost` por nó (call-control, ai-voice-assistant, inference) |
| Conciliação (opcional) | [`GET /detail_records?filter[record_type]=...`](https://developers.telnyx.com/api-reference/detail-records/search-detail-records) | CDR bruto, se o custo precisar ser auditado |

**Regras que moldam o desenho**

- A URL da gravação TeXML vale só **10 minutos após o fim da chamada**: o MP3 é baixado no instante do `RecordingStatusCallback` e guardado no nosso armazenamento.
- `GET Calls/{call_sid}` é *eventualmente consistente* e `price`/`price_unit` só vêm preenchidos com o recurso de custo de chamada habilitado na conta; por isso o custo oficial vem da Session Analysis, com retentativas.
- `account_sid` é o UUID da conta Telnyx dona da API key (não é um `AC...` do Twilio); chega também como `AccountSid` nos callbacks.
- Na Session Analysis, `max_depth` precisa ser 3 ou mais; o padrão (2) para no nó da assistente e esconde o custo de inferência.
- Callbacks vêm assinados com `telnyx-signature-ed25519` e `telnyx-timestamp`; callbacks TeXML podem chegar como `application/x-www-form-urlencoded`, não JSON.

**SDK Java:** a Telnyx publica o SDK oficial `com.telnyx.sdk:telnyx` (cliente `TelnyxOkHttpClient`, compatível com Java 8+), que expõe `client.texml().initiateAiCall(...)`, `client.texml().accounts().calls().retrieve(...)` e `client.ai().conversations().messages().list(...)`. Ele será encapsulado atrás de uma interface própria (ver Stack técnica).

## Stack técnica e estrutura do projeto

Spring Boot sobre Java 21 com arquitetura hexagonal: o domínio não conhece a Telnyx, então trocar SDK, banco ou storage não mexe nas regras.

| Camada | Escolha | Motivo |
| --- | --- | --- |
| Linguagem | Java 21 (Temurin) | Records, sealed interfaces, pattern matching e *virtual threads* |
| Framework | Spring Boot 3.5.x (ou a versão já usada na ferramenta existente) | Web, validação, Actuator, agendamento; `spring.threads.virtual.enabled=true` |
| Cliente Telnyx | SDK oficial `com.telnyx.sdk:telnyx` (fixar a versão mais recente do Maven Central) | Tipado, mantido pela Telnyx; fallback `java.net.http.HttpClient` se algum endpoint faltar |
| Banco | PostgreSQL 16 + Flyway | Histórico de chamadas e idempotência de webhooks; H2 nos testes |
| Áudio | Interface `RecordingStorage` com dois adaptadores: volume local e S3/MinIO | O container fica sem estado |
| Verificação de assinatura | BouncyCastle (Ed25519) ou o helper de webhook do SDK | Validar `telnyx-signature-ed25519` |
| Resiliência | Resilience4j (retry + circuit breaker) | Endpoints eventualmente consistentes |
| Build | Maven, imagem via Dockerfile multi-stage | Imagem final só com JRE |
| Testes | JUnit 5, WireMock (simula Telnyx), Testcontainers (Postgres) | Ciclo completo sem ligar para ninguém |

**Estrutura de pacotes**

```
br.com.qrgold.atendente
├── domain
│   ├── model        CallRecord, CallStatus (sealed), TranscriptLine, CallCost, RecordingRef
│   └── port         TelephonyGateway, ConversationGateway, CostGateway,
│                    RecordingStorage, CallRepository, ResultNotifier
├── application
│   ├── StartCallUseCase          valida o número, grava QUEUED, chama TelephonyGateway
│   ├── HandleCallStatusUseCase   atualiza status; no terminal agenda a coleta
│   ├── HandleRecordingUseCase    baixa o MP3 na hora e salva no storage
│   ├── CollectCallDataUseCase    transcrição + detalhes + custo, com retentativas
│   └── ReconcileStuckCallsJob    varredura agendada para webhooks perdidos
├── adapter
│   ├── in.rest       CallController (API pública), TelnyxWebhookController
│   ├── in.webhook    SignatureVerifier, FormOrJsonPayloadParser
│   ├── out.telnyx    TelnyxTelephonyAdapter, TelnyxConversationAdapter, TelnyxCostAdapter
│   ├── out.persistence  JPA entities + repositórios
│   ├── out.storage   LocalDiskStorage, S3Storage
│   └── out.notify    HttpResultNotifier (webhook para a ferramenta existente)
└── config            TelnyxProperties (@ConfigurationProperties), beans, segurança
```

**Porta principal (contrato do domínio)**

```java
public interface TelephonyGateway {
    StartedCall startAiCall(String from, String to, String assistantId,
                            Map<String, String> dynamicVariables, CallbackUrls callbacks);
    CallDetails fetchCall(String callSid);            // start/end, duration, status, price
    List<RecordingInfo> listRecordings(String callSid);
}

public record StartedCall(String callSid, String status) {}
```

O nome de pacote `br.com.qrgold` é uma sugestão; ajuste ao padrão da ferramenta existente.

## Modelo de dados e API REST

A ferramenta existente conversa com o serviço por quatro endpoints REST e recebe um webhook quando o pacote da chamada fica pronto; nunca chama a Telnyx diretamente.

**API pública (para a ferramenta existente)**

| Método e rota | Função | Resposta |
| --- | --- | --- |
| `POST /api/v1/calls` | Dispara chamada. Corpo: `to` (E.164), `from` opcional, `externalRef`, `variables` (variáveis dinâmicas da Antonella), `callbackUrl` opcional | `202` com `id`, `callSid`, `status=QUEUED` |
| `GET /api/v1/calls/{id}` | Pacote completo: status, datas, duração, custo, links de transcrição e áudio | `200` JSON |
| `GET /api/v1/calls/{id}/transcript` | Transcrição; `?format=json` (turnos com papel e horário) ou `?format=txt` | `200` |
| `GET /api/v1/calls/{id}/recording.mp3` | Stream do MP3 armazenado | `200 audio/mpeg` |
| `GET /api/v1/calls?status=&from=&to=&externalRef=` | Listagem paginada | `200` |

**Webhooks recebidos da Telnyx** (expostos publicamente, assinatura obrigatória): `POST /webhooks/telnyx/call-status`, `POST /webhooks/telnyx/recording`, `POST /webhooks/telnyx/conversation`.

**Webhook enviado à ferramenta existente:** `POST {callbackUrl}` com o mesmo JSON de `GET /api/v1/calls/{id}`, assinado com HMAC-SHA256 no header `X-Atendente-Signature`, quando o estado chega a `COMPLETED` ou `PARTIAL`.

**Exemplo do pacote final**

```json
{
  "id": "7c1e…",
  "externalRef": "lead-123",
  "callSid": "v3:…",
  "conversationId": "…",
  "from": "+551151189636",
  "to": "+5548999213535",
  "status": "COMPLETED",
  "telnyxStatus": "completed",
  "answeredBy": "human",
  "startedAt": "2026-11-25T15:02:11Z",
  "endedAt": "2026-11-25T15:05:47Z",
  "durationSec": 216,
  "cost": { "total": "0.1234", "currency": "USD", "telephony": "…", "ai": "…" },
  "recording": { "url": "/api/v1/calls/7c1e…/recording.mp3", "durationSec": 214, "channels": "dual" },
  "transcript": [
    { "seq": 1, "role": "assistant", "text": "Olá, aqui é a Antonella…", "at": "2026-11-25T15:02:14Z" },
    { "seq": 2, "role": "user", "text": "…", "at": "…" }
  ]
}
```

Os valores acima são ilustrativos.

**Tabelas (PostgreSQL)**

| Tabela | Colunas principais |
| --- | --- |
| `call_record` | `id` uuid PK, `external_ref`, `call_sid` unique, `call_control_id`, `call_session_id`, `conversation_id`, `assistant_id`, `from_number`, `to_number`, `status`, `telnyx_status`, `answered_by`, `started_at`, `ended_at`, `duration_sec`, `price`, `price_unit`, `cost_total`, `cost_currency`, `cost_breakdown` jsonb, `recording_key`, `recording_duration_sec`, `collection_attempts`, `last_error`, `created_at`, `updated_at` |
| `transcript_line` | `id`, `call_id` FK, `seq`, `role`, `text`, `spoken_at`, `raw` jsonb |
| `webhook_event` | `id`, `source`, `event_key` unique (idempotência), `payload` jsonb, `received_at`, `processed_at` |
| `outbound_notification` | `id`, `call_id`, `target_url`, `status`, `attempts`, `next_attempt_at` |

**Estados da chamada no serviço:** `QUEUED → RINGING → IN_PROGRESS → ENDED → COLLECTING → COMPLETED`. `PARTIAL` quando algum artefato não pôde ser obtido após todas as retentativas. `NO_ANSWER`, `BUSY`, `FAILED` e `CANCELED` também passam por `COLLECTING`, para guardar horário e custo.

## Containerização e integração

O serviço entra na ferramenta existente como mais um container na mesma rede Docker; o único requisito externo é uma URL HTTPS pública para os callbacks da Telnyx.

**Dockerfile (multi-stage)**

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/target/atendente-telnyx.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
```

**docker-compose (trecho para anexar ao compose existente)**

```yaml
services:
  atendente-telnyx:
    image: registry.local/atendente-telnyx:1.0.0
    env_file: .env.atendente
    depends_on: [atendente-db]
    volumes:
      - recordings:/data/recordings
    networks: [backend]
    labels:          # exemplo com Traefik; adapte ao proxy já usado
      - traefik.http.routers.atendente-wh.rule=Host(`hooks.seudominio.com.br`) && PathPrefix(`/webhooks/telnyx`)
  atendente-db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: atendente
      POSTGRES_USER: atendente
      POSTGRES_PASSWORD: ${ATENDENTE_DB_PASSWORD}
    volumes: [pgdata:/var/lib/postgresql/data]
    networks: [backend]
volumes: { recordings: {}, pgdata: {} }
```

Se a ferramenta já tem um PostgreSQL, basta um schema `atendente` nele e remover `atendente-db`.

**Variáveis de ambiente**

| Variável | Exemplo / uso |
| --- | --- |
| `TELNYX_API_KEY` | Chave da API (segredo) |
| `TELNYX_PUBLIC_KEY` | Chave pública Ed25519 do portal, para validar callbacks |
| `TELNYX_CONNECTION_ID` | `3099557255473792623` (o ID da aplicação TeXML usado no curl) |
| `TELNYX_ACCOUNT_SID` | UUID da conta Telnyx, exigido nos endpoints `/texml/Accounts/...` |
| `TELNYX_ASSISTANT_ID` | `assistant-6585b0b1-550a-4d7d-948c-eb4e3538e674` |
| `TELNYX_DEFAULT_FROM` | `+551151189636` |
| `PUBLIC_BASE_URL` | `https://hooks.seudominio.com.br`, base dos callbacks enviados à Telnyx |
| `RECORDING_STORAGE` | `local` ou `s3` (+ `S3_BUCKET`, `S3_ENDPOINT`, credenciais) |
| `VA_DB_URL` / `_USERNAME` / `_PASSWORD` | Conexão PostgreSQL (compatível com `QR369-GCP-PRO`) |
| `API_AUTH_TOKEN` | Token que a ferramenta existente envia em `Authorization` |
| `NOTIFY_HMAC_SECRET` | Segredo da assinatura do webhook de saída |
| `COLLECTION_MAX_WAIT_MIN` | Tempo máximo de retentativas da coleta (ex.: 30) |

**Formas de integração com a ferramenta existente**

1. **REST + webhook (recomendado):** a ferramenta chama `POST /api/v1/calls` e recebe o pacote em `callbackUrl`.
2. **Polling:** a ferramenta consulta `GET /api/v1/calls/{id}` até `status` final, se não puder receber webhooks.
3. **Mensageria (evolução):** se a ferramenta já usa RabbitMQ/Kafka, o `ResultNotifier` ganha um adaptador que publica `call.completed`, sem mudar o domínio.

**Desenvolvimento local:** expor o container com `cloudflared tunnel` ou `ngrok http 8080` e usar essa URL em `PUBLIC_BASE_URL`.

## Segurança, confiabilidade e observabilidade

Toda chamada precisa terminar com um pacote salvo, mesmo se um webhook se perder; o serviço combina callbacks com uma varredura agendada para garantir isso.

**Segurança**

- Validar `telnyx-signature-ed25519` + `telnyx-timestamp` sobre os bytes brutos do corpo, com janela anti-replay de 5 minutos; rejeitar com `401` o que falhar.
- Segredos só por variável de ambiente ou Docker secrets; nunca no código nem em log.
- API pública protegida por token (`Authorization: Bearer`) ou mTLS dentro da rede da ferramenta; só `/webhooks/telnyx/**` fica exposto à internet.
- Validar `to` em E.164 brasileiro (`+55` + DDD + 8 ou 9 dígitos) e manter lista de bloqueio opcional.
- **LGPD:** gravação e transcrição são dados pessoais. Informar a gravação no início da fala da Antonella, definir prazo de retenção (`RETENTION_DAYS`) com job de expurgo e criptografar o bucket/volume em repouso.

**Confiabilidade**

- Webhooks: responder `200` em milissegundos, gravar em `webhook_event` (deduplicado por `event_key`) e processar de forma assíncrona.
- Download do MP3 imediatamente no `RecordingStatusCallback` (janela de 10 minutos), com 3 tentativas rápidas; se perder a janela, tentar `Recordings.json` e, se ainda falhar, marcar `PARTIAL`.
- Coleta de transcrição e custo com backoff exponencial (ex.: 15 s, 30 s, 1 min, 2 min, 5 min…) até `COLLECTION_MAX_WAIT_MIN`, porque os dados são eventualmente consistentes.
- `ReconcileStuckCallsJob` a cada minuto: chamadas em `QUEUED`/`RINGING`/`IN_PROGRESS` há mais que `TimeLimit` + margem são consultadas em `GET Calls/{call_sid}`.
- Webhook de saída com fila em `outbound_notification` e retentativas; nada se perde se a ferramenta estiver fora do ar.
- Shutdown gracioso e jobs com lock (ShedLock) para rodar mais de uma réplica sem coleta duplicada.

**Observabilidade**

- Logs JSON com `callId`, `callSid` e `conversationId` no MDC.
- Métricas Micrometer/Prometheus: `calls_started_total`, `calls_completed_total{status}`, `collection_duration_seconds`, `recording_download_failures_total`, `telnyx_api_errors_total{endpoint,code}`, `call_cost_usd_sum`.
- `/actuator/health` com checagem de banco, storage e alcance da API Telnyx (`GET /v2/balance`).

## Fases, cronograma e critérios de aceite

A entrega leva cerca de 4 semanas para um desenvolvedor, em cinco fases; a Fase 0 vem primeiro porque confirma, com uma chamada real, o vínculo entre chamada e transcrição do qual o resto depende.

&#91;embedded content: roadmap · 5 fases com critério de aceite\]

**Critérios de aceite detalhados**

1. **Validação:** uma chamada real com `Record=true` e todos os callbacks gera payloads salvos; fica confirmado qual campo liga `call_sid` à `conversation_id` e de onde vem o `call_session_id`.
2. **Núcleo:** `POST /api/v1/calls` dispara a Antonella; callbacks com assinatura inválida são rejeitados; a chamada percorre `QUEUED → ENDED` no banco; testes com WireMock cobrem `completed`, `no-answer`, `busy` e `failed`.
3. **Coleta:** em 10 chamadas de teste (celular, fixo, não atendida, caixa postal), 100% têm status e duração, e as atendidas têm MP3 tocável e transcrição completa; o custo bate com o portal Telnyx.
4. **Integração:** a imagem sobe no compose da ferramenta; a ferramenta dispara uma chamada e recebe o pacote via webhook em homologação.
5. **Produção:** métricas e alertas ativos, job de reconciliação testado derrubando o webhook, política de retenção aplicada e runbook escrito.

## Riscos, pontos em aberto e próximos passos

O maior risco é o vínculo entre `call_sid` (TeXML) e a conversa da IA, que a documentação não descreve explicitamente; a Fase 0 existe para confirmá-lo com uma chamada real.

| Risco / dúvida | Impacto | Mitigação |
| --- | --- | --- |
| A doc lista o filtro `metadata->call_control_id` nas conversas, mas não diz se o `call_sid` da TeXML tem o mesmo valor (o exemplo de resposta usa o formato `v3:...`, igual ao de `call_control_id`) | Transcrição não associada à chamada | Usar o `conversation_id` do `ConversationCallback` como vínculo primário; filtro por `call_control_id` e por `telnyx_end_user_target` + janela de tempo como alternativas |
| `price` vazio em `GET Calls/{call_sid}` | Custo incompleto | Pedir ao suporte a ativação do recurso de custo da chamada; usar a Session Analysis como fonte oficial |
| Atraso na disponibilidade do custo na Session Analysis (não documentado) | Pacote demora a fechar | Entregar o pacote sem custo e atualizar depois (`cost.status = PENDING → FINAL`), com segundo webhook |
| `call_session_id` não vir no callback TeXML | Sem chave para o custo | Obtê-lo dos metadados da conversa (`call_session_id`) |
| URL do MP3 expira em 10 min | Áudio perdido | Download imediato + fallback em `Recordings.json` |
| Gravação `dual` x `mono` | Qualidade da análise | Usar `dual` (cliente e Antonella em canais separados), confirmar que o MP3 mantém dois canais |
| Caixa postal no celular | Custo sem conversa | `MachineDetection=Enable` + `AsyncAmd` e registro de `answered_by` |
| Requisitos da Anatel para chamadas automatizadas (ex.: prefixo 0303 para telemarketing ativo) | Bloqueio ou reclamação | Validar com o jurídico o enquadramento das ligações antes de escalar volume |

**Próximos passos**

- [ ] Confirmar o `account_sid` da conta e a chave pública Ed25519 no portal Telnyx
- [ ] Configurar na aplicação TeXML `3099557255473792623` as URLs de callback padrão (ou só enviá-las por chamada)
- [ ] Fazer uma chamada de teste com `Record=true`, `StatusCallback` e `ConversationCallback` apontando para um receptor temporário e guardar os payloads reais
- [ ] Definir com a equipe da ferramenta: formato do `callbackUrl`, autenticação e onde o MP3 deve morar (volume ou S3)
- [ ] Definir política de retenção LGPD para gravações e transcrições

## Fontes

Documentação Telnyx consultada em 25/11/2026:

- [Initiate an outbound AI call](https://developers.telnyx.com/api-reference/texml-rest-commands/initiate-an-outbound-ai-call)
- [Fetch a call (TeXML)](https://developers.telnyx.com/api-reference/texml-rest-commands/fetch-a-call)
- [Fetch recordings for a call (TeXML)](https://developers.telnyx.com/api-reference/texml-rest-commands/fetch-recordings-for-a-call)
- [List conversations](https://developers.telnyx.com/api-reference/conversations/list-conversations)
- [Get conversation messages](https://developers.telnyx.com/api-reference/conversations/get-conversation-messages)
- [Conversation keying](https://developers.telnyx.com/docs/inference/ai-assistants/conversation-keying)
- [Session Analysis](https://developers.telnyx.com/docs/reporting/session-analysis)
- [Search detail records](https://developers.telnyx.com/api-reference/detail-records/search-detail-records)
- [Receiving webhooks](https://developers.telnyx.com/docs/development/api-fundamentals/webhooks/receiving-webhooks)
- [SDK Java da Telnyx](https://developers.telnyx.com/docs/development/sdk/java) · [repositório](https://github.com/team-telnyx/telnyx-java)
