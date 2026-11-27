package br.com.ia369.atendente;

import br.com.ia369.atendente.adapter.out.notify.HttpResultNotifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TelnyxCallLifecycleIntegrationTest {

    private static final WireMockServer wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    private static final KeyPair keyPair;
    private static final String publicKeyBase64;

    static {
        wireMock.start();
        try {
            keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] x509 = keyPair.getPublic().getEncoded();
            byte[] raw32 = Arrays.copyOfRange(x509, x509.length - 32, x509.length);
            publicKeyBase64 = Base64.getEncoder().encodeToString(raw32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("telnyx.base-url", () -> wireMock.baseUrl() + "/v2");
        registry.add("telnyx.public-key", () -> publicKeyBase64);
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/v2/balance"))
                .willReturn(okJson("{\"data\":{\"balance\":\"100.00\",\"currency\":\"USD\"}}")));
    }

    @Test
    void shouldRejectUnauthenticatedCallsAndInvalidPhonesAndInvalidWebhookSignatures() throws Exception {
        mockMvc.perform(post("/api/v1/calls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"+5548999213535\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/calls")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-api-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"48999213535\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/calls")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-api-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"+5511900000000\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/webhooks/telnyx/call-status")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("telnyx-signature-ed25519", "bad-sig")
                        .header("telnyx-timestamp", String.valueOf(Instant.now().getEpochSecond()))
                        .content("CallSid=v3:x&CallStatus=completed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldCompleteFullOutboundAiCallFlowWithMp3TranscriptCostAndSignedOutboundWebhook() throws Exception {
        String callSid = "v3:call-happy-001";
        String conversationId = "conv-happy-001";
        String sessionId = "sess-happy-001";
        byte[] fakeMp3 = "ID3-MOCK-MP3-AUDIO-DATA".getBytes(StandardCharsets.UTF_8);

        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlPathEqualTo("/v2/texml/ai_calls/3099557255473792623"))
                .willReturn(okJson("""
                        {"call_sid":"%s","status":"queued","from":"+551151189636","to":"+5548999213535"}
                        """.formatted(callSid))));

        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        urlPathEqualTo("/v2/texml/Accounts/11111111-1111-1111-1111-111111111111/Calls/" + callSid))
                .willReturn(okJson("""
                        {
                          "sid": "%s",
                          "account_sid": "11111111-1111-1111-1111-111111111111",
                          "status": "completed",
                          "answered_by": "human",
                          "direction": "outbound-api",
                          "start_time": "2026-11-25T15:02:11Z",
                          "end_time": "2026-11-25T15:05:47Z",
                          "duration": "216",
                          "price": "0.0450",
                          "price_unit": "USD"
                        }
                        """.formatted(callSid))));

        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        urlPathEqualTo("/recordings/rec-001.mp3"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "audio/mpeg")
                        .withBody(fakeMp3)));

        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        urlPathEqualTo("/v2/ai/conversations/" + conversationId))
                .willReturn(okJson("""
                        {
                          "data": {
                            "id": "%s",
                            "created_at": "2026-11-25T15:02:12Z",
                            "last_message_at": "2026-11-25T15:05:45Z",
                            "metadata": {
                              "call_control_id": "%s",
                              "call_session_id": "%s",
                              "call_leg_id": "leg-001"
                            }
                          }
                        }
                        """.formatted(conversationId, callSid, sessionId))));

        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        urlPathEqualTo("/v2/ai/conversations/" + conversationId + "/messages"))
                .willReturn(okJson("""
                        {
                          "data": [
                            {"role": "assistant", "text": "Olá, aqui é a Antonella.", "sent_at": "2026-11-25T15:02:14Z"},
                            {"role": "user", "text": "Oi Antonella, pode falar.", "sent_at": "2026-11-25T15:02:19Z"}
                          ],
                          "meta": {"page_number": 1, "page_size": 20, "total_pages": 1, "total_results": 2}
                        }
                        """)));

        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        urlPathEqualTo("/v2/session_analysis/call-session/" + sessionId))
                .withQueryParam("max_depth", equalTo("3"))
                .willReturn(okJson("""
                        {
                          "session_id": "%s",
                          "cost": {"currency": "USD", "total": "0.1234"},
                          "meta": {"event_count": 3, "products": ["call-control", "ai-voice-assistant", "inference"]},
                          "root": {
                            "id": "%s",
                            "product": "call-control",
                            "event_name": "call.hangup",
                            "cost": {"currency": "USD", "event_cost": "0.0450", "cumulative_cost": "0.1234"},
                            "links": {"self": "", "records": ""},
                            "record": {},
                            "children": [
                              {
                                "id": "node-ai",
                                "product": "ai-voice-assistant",
                                "event_name": "assistant.session",
                                "cost": {"currency": "USD", "event_cost": "0.0600", "cumulative_cost": "0.0784"},
                                "links": {"self": "", "records": ""},
                                "record": {},
                                "children": [
                                  {
                                    "id": "node-inf",
                                    "product": "inference",
                                    "event_name": "llm.turn",
                                    "cost": {"currency": "USD", "event_cost": "0.0184", "cumulative_cost": "0.0184"},
                                    "links": {"self": "", "records": ""},
                                    "record": {},
                                    "children": []
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """.formatted(sessionId, sessionId))));

        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlPathEqualTo("/client-callback"))
                .willReturn(aResponse().withStatus(200)));

        // 1. Dispara chamada pela API pública
        String startPayload = """
                {
                  "to": "+5548999213535",
                  "externalRef": "lead-123",
                  "variables": {"empresa": "Acme Ltda", "contato": "Carlos"},
                  "callbackUrl": "%s/client-callback"
                }
                """.formatted(wireMock.baseUrl());

        MvcResult startResult = mockMvc.perform(post("/api/v1/calls")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-api-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startPayload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.callSid").value(callSid))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();

        UUID callId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        // 2. Callback de gravação (form-urlencoded, assinado)
        byte[] recBody = ("CallSid=" + callSid
                + "&RecordingSid=rec-001&RecordingStatus=completed&RecordingUrl="
                + wireMock.baseUrl() + "/recordings/rec-001.mp3&RecordingDuration=214&RecordingChannels=dual")
                .getBytes(StandardCharsets.UTF_8);
        postSignedWebhook("/webhooks/telnyx/recording", MediaType.APPLICATION_FORM_URLENCODED, recBody);

        // 3. Callback de conversa (JSON, assinado)
        byte[] convBody = """
                {
                  "data": {
                    "event_type": "conversation_ended",
                    "payload": {
                      "conversation_id": "%s",
                      "call_control_id": "%s",
                      "call_session_id": "%s"
                    }
                  }
                }
                """.formatted(conversationId, callSid, sessionId).getBytes(StandardCharsets.UTF_8);
        postSignedWebhook("/webhooks/telnyx/conversation", MediaType.APPLICATION_JSON, convBody);

        // 4. Callback de status completed (form-urlencoded, assinado + duplicado para validar idempotência)
        byte[] statusBody = ("CallSid=" + callSid + "&CallStatus=completed&AnsweredBy=human&CallDuration=216")
                .getBytes(StandardCharsets.UTF_8);
        postSignedWebhook("/webhooks/telnyx/call-status", MediaType.APPLICATION_FORM_URLENCODED, statusBody);
        postSignedWebhook("/webhooks/telnyx/call-status", MediaType.APPLICATION_FORM_URLENCODED, statusBody);

        // 5. Aguardar conclusão assíncrona e validar pacote completo
        JsonNode packageJson = awaitStatus(callId, "COMPLETED");
        assertThat(packageJson.get("externalRef").asText()).isEqualTo("lead-123");
        assertThat(packageJson.get("answeredBy").asText()).isEqualTo("human");
        assertThat(packageJson.get("durationSec").asInt()).isEqualTo(216);
        assertThat(packageJson.get("cost").get("total").decimalValue()).isEqualByComparingTo("0.1234");
        assertThat(packageJson.get("cost").get("telephony").decimalValue()).isEqualByComparingTo("0.0450");
        assertThat(packageJson.get("cost").get("ai").decimalValue()).isEqualByComparingTo("0.0784");
        assertThat(packageJson.get("transcript")).hasSize(2);
        assertThat(packageJson.get("recording").get("url").asText()).isEqualTo("/api/v1/calls/" + callId + "/recording.mp3");

        // 6. Validar endpoints de transcrição, MP3 e listagem paginada
        mockMvc.perform(get("/api/v1/calls/" + callId + "/transcript?format=txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-api-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Olá, aqui é a Antonella.")));

        mockMvc.perform(get("/api/v1/calls/" + callId + "/recording.mp3")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-api-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"))
                .andExpect(content().bytes(fakeMp3));

        mockMvc.perform(get("/api/v1/calls?status=COMPLETED&externalRef=lead-123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-api-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // 7. Validar webhook de saída assinado com HMAC-SHA256
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/client-callback"))
                .withHeader(HttpResultNotifier.SIGNATURE_HEADER, matching("^sha256=[0-9a-f]{64}$")));
    }

    @Test
    void shouldHandleNonAnsweredTerminalStatusesNoAnswerBusyAndFailed() throws Exception {
        for (String telnyxTerminal : new String[]{"no-answer", "busy", "failed"}) {
            String callSid = "v3:call-" + telnyxTerminal;
            wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                            urlPathEqualTo("/v2/texml/ai_calls/3099557255473792623"))
                    .willReturn(okJson("{\"call_sid\":\"" + callSid + "\",\"status\":\"queued\"}")));

            wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                            urlPathEqualTo("/v2/texml/Accounts/11111111-1111-1111-1111-111111111111/Calls/" + callSid))
                    .willReturn(okJson("{\"sid\":\"" + callSid + "\",\"status\":\"" + telnyxTerminal + "\",\"duration\":\"0\"}")));

            MvcResult res = mockMvc.perform(post("/api/v1/calls")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer test-api-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"to\":\"+5511988887777\",\"externalRef\":\"ref-" + telnyxTerminal + "\"}"))
                    .andExpect(status().isAccepted())
                    .andReturn();

            UUID callId = UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());

            byte[] statusBody = ("CallSid=" + callSid + "&CallStatus=" + telnyxTerminal).getBytes(StandardCharsets.UTF_8);
            postSignedWebhook("/webhooks/telnyx/call-status", MediaType.APPLICATION_FORM_URLENCODED, statusBody);

            JsonNode pkg = awaitStatus(callId, "COMPLETED");
            assertThat(pkg.get("telnyxStatus").asText()).isEqualTo(telnyxTerminal);
        }
    }

    private void postSignedWebhook(String path, MediaType contentType, byte[] body) throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        byte[] prefix = (ts + "|").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(signed);
        String sig = Base64.getEncoder().encodeToString(signer.sign());

        mockMvc.perform(post(path)
                        .contentType(contentType)
                        .header("telnyx-signature-ed25519", sig)
                        .header("telnyx-timestamp", ts)
                        .content(body))
                .andExpect(status().isOk());
    }

    private JsonNode awaitStatus(UUID callId, String expectedStatus) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            MvcResult res = mockMvc.perform(get("/api/v1/calls/" + callId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer test-api-token"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
            if (expectedStatus.equals(json.get("status").asText())) {
                return json;
            }
            Thread.sleep(100);
        }
        MvcResult last = mockMvc.perform(get("/api/v1/calls/" + callId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-api-token"))
                .andReturn();
        throw new AssertionError("Timeout aguardando status " + expectedStatus + ". Último JSON: " + last.getResponse().getContentAsString());
    }
}

