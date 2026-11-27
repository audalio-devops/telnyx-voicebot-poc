package br.com.ia369.atendente.adapter.in.rest.dto;

import br.com.ia369.atendente.domain.model.CallRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CallPackageResponse(
        UUID id,
        String externalRef,
        String callSid,
        String callControlId,
        String callSessionId,
        String conversationId,
        String assistantId,
        String from,
        String to,
        String status,
        String telnyxStatus,
        String answeredBy,
        Instant startedAt,
        Instant endedAt,
        Integer durationSec,
        CostDto cost,
        RecordingDto recording,
        List<TranscriptLineDto> transcript,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public record CostDto(
            BigDecimal total,
            String currency,
            BigDecimal telephony,
            BigDecimal ai,
            String status,
            Map<String, Object> breakdown
    ) {}

    public record RecordingDto(
            String url,
            Integer durationSec,
            String channels
    ) {}

    public record TranscriptLineDto(
            int seq,
            String role,
            String text,
            Instant at
    ) {}

    public static CallPackageResponse fromDomain(CallRecord record, String baseUrlPrefix) {
        CostDto costDto = record.cost() != null
                ? new CostDto(
                record.cost().total(),
                record.cost().currency(),
                record.cost().telephony(),
                record.cost().ai(),
                record.cost().status(),
                record.cost().breakdown())
                : null;

        String prefix = baseUrlPrefix != null ? baseUrlPrefix : "";
        RecordingDto recordingDto = record.recording() != null
                ? new RecordingDto(
                prefix + "/api/v1/calls/" + record.id() + "/recording.mp3",
                record.recording().durationSec(),
                record.recording().channels())
                : null;

        List<TranscriptLineDto> transcriptDtos = record.transcript() != null
                ? record.transcript().stream()
                .map(line -> new TranscriptLineDto(line.seq(), line.role(), line.text(), line.spokenAt()))
                .toList()
                : List.of();

        return new CallPackageResponse(
                record.id(),
                record.externalRef(),
                record.callSid(),
                record.callControlId(),
                record.callSessionId(),
                record.conversationId(),
                record.assistantId(),
                record.fromNumber(),
                record.toNumber(),
                record.status().code(),
                record.telnyxStatus(),
                record.answeredBy(),
                record.startedAt(),
                record.endedAt(),
                record.durationSec(),
                costDto,
                recordingDto,
                transcriptDtos,
                record.lastError(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}

