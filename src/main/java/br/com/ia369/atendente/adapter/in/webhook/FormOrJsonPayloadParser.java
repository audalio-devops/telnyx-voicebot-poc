package br.com.ia369.atendente.adapter.in.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FormOrJsonPayloadParser {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final ObjectMapper objectMapper;

    public FormOrJsonPayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedPayload parse(byte[] rawBody, String contentType) {
        String text = new String(rawBody, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return new ParsedPayload(Map.of(), "{}");
        }

        boolean looksLikeJson = (contentType != null && contentType.toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE))
                || text.startsWith("{")
                || text.startsWith("[");

        if (looksLikeJson) {
            try {
                Map<String, Object> map = objectMapper.readValue(text, MAP_TYPE);
                return new ParsedPayload(map, text);
            } catch (Exception e) {
                throw new IllegalArgumentException("Payload JSON inválido no webhook", e);
            }
        }

        Map<String, Object> formMap = parseFormUrlEncoded(text);
        try {
            String normalizedJson = objectMapper.writeValueAsString(formMap);
            return new ParsedPayload(formMap, normalizedJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Falha ao normalizar payload form-urlencoded", e);
        }
    }

    private Map<String, Object> parseFormUrlEncoded(String body) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int idx = pair.indexOf('=');
            if (idx >= 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(key, val);
            } else {
                map.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            }
        }
        return map;
    }

    public record ParsedPayload(Map<String, Object> fields, String jsonRepresentation) {}
}

