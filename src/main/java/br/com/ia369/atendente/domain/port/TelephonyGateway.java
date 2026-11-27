package br.com.ia369.atendente.domain.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TelephonyGateway {

    StartedCall startAiCall(
            String from,
            String to,
            String assistantId,
            Map<String, String> dynamicVariables,
            CallbackUrls callbacks
    );

    Optional<CallDetails> fetchCall(String callSid);

    List<RecordingInfo> listRecordings(String callSid);

    byte[] downloadRecording(String mediaUrl);

    boolean isHealthy();

    record StartedCall(String callSid, String status) {}

    record CallbackUrls(
            String statusCallbackUrl,
            String recordingStatusCallbackUrl,
            String conversationCallbackUrl
    ) {}

    record CallDetails(
            String callSid,
            String status,
            String answeredBy,
            String direction,
            Instant startedAt,
            Instant endedAt,
            Integer durationSec,
            BigDecimal price,
            String priceUnit
    ) {}

    record RecordingInfo(
            String sid,
            String mediaUrl,
            Integer durationSec,
            String channels
    ) {}
}

