package br.com.ia369.atendente.adapter.in.rest;

import br.com.ia369.atendente.adapter.in.webhook.FormOrJsonPayloadParser;
import br.com.ia369.atendente.adapter.in.webhook.RawBodyCachingFilter;
import br.com.ia369.atendente.adapter.in.webhook.SignatureVerifier;
import br.com.ia369.atendente.application.HandleCallStatusUseCase;
import br.com.ia369.atendente.application.HandleConversationEventUseCase;
import br.com.ia369.atendente.application.HandleRecordingUseCase;
import br.com.ia369.atendente.domain.port.WebhookEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@RestController
@RequestMapping("/webhooks/telnyx")
public class TelnyxWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelnyxWebhookController.class);

    private final SignatureVerifier signatureVerifier;
    private final FormOrJsonPayloadParser payloadParser;
    private final WebhookEventRepository webhookEventRepository;
    private final HandleCallStatusUseCase handleCallStatusUseCase;
    private final HandleRecordingUseCase handleRecordingUseCase;
    private final HandleConversationEventUseCase handleConversationEventUseCase;

    public TelnyxWebhookController(
            SignatureVerifier signatureVerifier,
            FormOrJsonPayloadParser payloadParser,
            WebhookEventRepository webhookEventRepository,
            HandleCallStatusUseCase handleCallStatusUseCase,
            HandleRecordingUseCase handleRecordingUseCase,
            HandleConversationEventUseCase handleConversationEventUseCase
    ) {
        this.signatureVerifier = signatureVerifier;
        this.payloadParser = payloadParser;
        this.webhookEventRepository = webhookEventRepository;
        this.handleCallStatusUseCase = handleCallStatusUseCase;
        this.handleRecordingUseCase = handleRecordingUseCase;
        this.handleConversationEventUseCase = handleConversationEventUseCase;
    }

    @PostMapping("/call-status")
    public ResponseEntity<Void> onCallStatus(
            HttpServletRequest request,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "telnyx-signature-ed25519", required = false) String signature,
            @RequestHeader(value = "telnyx-timestamp", required = false) String timestamp
    ) {
        byte[] rawBody = extractRawBody(request);
        if (!signatureVerifier.verify(rawBody, signature, timestamp)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        FormOrJsonPayloadParser.ParsedPayload parsed = payloadParser.parse(rawBody, contentType);
        Map<String, Object> fields = unwrapDataPayload(parsed.fields());

        String callSid = firstString(fields, "CallSid", "call_sid", "call_control_id");
        String callStatus = firstString(fields, "CallStatus", "call_status", "status");
        String eventKey = "call-status:" + firstString(fields, "event_id", "id") + ":"
                + callSid + ":" + callStatus + ":" + sha256Short(rawBody);

        if (!webhookEventRepository.registerIfNew("telnyx-call-status", eventKey, parsed.jsonRepresentation())) {
            log.debug("Webhook call-status duplicado ignorado: {}", eventKey);
            return ResponseEntity.ok().build();
        }

        handleCallStatusUseCase.handleAsync(new HandleCallStatusUseCase.CallStatusCommand(
                callSid,
                callStatus,
                firstString(fields, "AnsweredBy", "answered_by"),
                firstString(fields, "call_control_id", "CallControlId"),
                firstString(fields, "call_session_id", "CallSessionId"),
                parseInteger(firstString(fields, "CallDuration", "Duration", "duration"))
        ));
        webhookEventRepository.markProcessed(eventKey);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/recording")
    public ResponseEntity<Void> onRecording(
            HttpServletRequest request,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "telnyx-signature-ed25519", required = false) String signature,
            @RequestHeader(value = "telnyx-timestamp", required = false) String timestamp
    ) {
        byte[] rawBody = extractRawBody(request);
        if (!signatureVerifier.verify(rawBody, signature, timestamp)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        FormOrJsonPayloadParser.ParsedPayload parsed = payloadParser.parse(rawBody, contentType);
        Map<String, Object> fields = unwrapDataPayload(parsed.fields());

        String callSid = firstString(fields, "CallSid", "call_sid", "call_control_id");
        String recordingSid = firstString(fields, "RecordingSid", "recording_sid", "sid");
        String recordingStatus = firstString(fields, "RecordingStatus", "recording_status", "status");
        String eventKey = "recording:" + recordingSid + ":" + recordingStatus + ":" + sha256Short(rawBody);

        if (!webhookEventRepository.registerIfNew("telnyx-recording", eventKey, parsed.jsonRepresentation())) {
            return ResponseEntity.ok().build();
        }

        handleRecordingUseCase.handleAsync(new HandleRecordingUseCase.RecordingCallbackCommand(
                callSid,
                recordingSid,
                recordingStatus,
                firstString(fields, "RecordingUrl", "recording_url", "media_url"),
                parseInteger(firstString(fields, "RecordingDuration", "recording_duration", "duration")),
                firstString(fields, "RecordingChannels", "recording_channels", "channels")
        ));
        webhookEventRepository.markProcessed(eventKey);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/conversation")
    public ResponseEntity<Void> onConversation(
            HttpServletRequest request,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "telnyx-signature-ed25519", required = false) String signature,
            @RequestHeader(value = "telnyx-timestamp", required = false) String timestamp
    ) {
        byte[] rawBody = extractRawBody(request);
        if (!signatureVerifier.verify(rawBody, signature, timestamp)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        FormOrJsonPayloadParser.ParsedPayload parsed = payloadParser.parse(rawBody, contentType);
        Map<String, Object> root = parsed.fields();
        Map<String, Object> fields = unwrapDataPayload(root);

        String eventType = firstString(fields, "event_type", "type", "EventType");
        if (eventType == null) {
            eventType = firstString(root, "event_type", "type");
        }
        String conversationId = firstString(fields, "conversation_id", "ConversationId", "id");
        String callSid = firstString(fields, "call_sid", "CallSid", "call_control_id");
        String callControlId = firstString(fields, "call_control_id", "CallControlId");
        String callSessionId = firstString(fields, "call_session_id", "CallSessionId");

        String eventKey = "conversation:" + conversationId + ":" + eventType + ":" + sha256Short(rawBody);
        if (!webhookEventRepository.registerIfNew("telnyx-conversation", eventKey, parsed.jsonRepresentation())) {
            return ResponseEntity.ok().build();
        }

        handleConversationEventUseCase.handleAsync(new HandleConversationEventUseCase.ConversationEventCommand(
                eventType,
                conversationId,
                callSid,
                callControlId,
                callSessionId
        ));
        webhookEventRepository.markProcessed(eventKey);
        return ResponseEntity.ok().build();
    }

    private static byte[] extractRawBody(HttpServletRequest request) {
        Object attr = request.getAttribute(RawBodyCachingFilter.RAW_BODY_ATTRIBUTE);
        if (attr instanceof byte[] cached) {
            return cached;
        }
        try {
            return request.getInputStream().readAllBytes();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapDataPayload(Map<String, Object> root) {
        Object dataObj = root.get("data");
        if (dataObj instanceof Map<?, ?> dataMap) {
            Object payloadObj = dataMap.get("payload");
            if (payloadObj instanceof Map<?, ?> payloadMap) {
                return (Map<String, Object>) payloadMap;
            }
            return (Map<String, Object>) dataMap;
        }
        return root;
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val != null) {
                String s = val.toString().trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    private static Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String sha256Short(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(bytes.length);
        }
    }
}

