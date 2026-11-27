package br.com.ia369.atendente.adapter.in.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FormOrJsonPayloadParserTest {

    private final FormOrJsonPayloadParser parser = new FormOrJsonPayloadParser(new ObjectMapper());

    @Test
    void shouldParseFormUrlEncodedTexmlPayload() {
        byte[] body = "CallSid=v3%3Acall123&CallStatus=completed&AnsweredBy=human&CallDuration=124"
                .getBytes(StandardCharsets.UTF_8);

        FormOrJsonPayloadParser.ParsedPayload parsed = parser.parse(body, "application/x-www-form-urlencoded");

        assertThat(parsed.fields())
                .containsEntry("CallSid", "v3:call123")
                .containsEntry("CallStatus", "completed")
                .containsEntry("AnsweredBy", "human")
                .containsEntry("CallDuration", "124");
        assertThat(parsed.jsonRepresentation()).contains("\"CallSid\":\"v3:call123\"");
    }

    @Test
    void shouldParseJsonWebhookPayload() {
        byte[] body = """
                {"data":{"event_type":"conversation_ended","payload":{"conversation_id":"conv-1","call_control_id":"v3:call123"}}}
                """.getBytes(StandardCharsets.UTF_8);

        FormOrJsonPayloadParser.ParsedPayload parsed = parser.parse(body, "application/json");

        assertThat(parsed.fields()).containsKey("data");
        assertThat(parsed.jsonRepresentation()).contains("conversation_ended");
    }
}

