package br.com.ia369.atendente.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CallRecord(
        UUID id,
        String externalRef,
        String callSid,
        String callControlId,
        String callSessionId,
        String conversationId,
        String assistantId,
        String fromNumber,
        String toNumber,
        CallStatus status,
        String telnyxStatus,
        String answeredBy,
        Instant startedAt,
        Instant endedAt,
        Integer durationSec,
        BigDecimal price,
        String priceUnit,
        CallCost cost,
        RecordingRef recording,
        String callbackUrl,
        Map<String, String> dynamicVariables,
        int collectionAttempts,
        Instant nextCollectionAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        List<TranscriptLine> transcript
) {
    public CallRecord withStatus(CallStatus newStatus, String newTelnyxStatus, Instant now) {
        return new CallRecord(
                id, externalRef, callSid, callControlId, callSessionId, conversationId,
                assistantId, fromNumber, toNumber, newStatus, newTelnyxStatus, answeredBy,
                startedAt, endedAt, durationSec, price, priceUnit, cost, recording,
                callbackUrl, dynamicVariables, collectionAttempts, nextCollectionAt,
                lastError, createdAt, now, transcript
        );
    }

    public CallRecord withCallSid(String newCallSid, String newTelnyxStatus, Instant now) {
        return new CallRecord(
                id, externalRef, newCallSid, callControlId, callSessionId, conversationId,
                assistantId, fromNumber, toNumber, status, newTelnyxStatus, answeredBy,
                startedAt, endedAt, durationSec, price, priceUnit, cost, recording,
                callbackUrl, dynamicVariables, collectionAttempts, nextCollectionAt,
                lastError, createdAt, now, transcript
        );
    }

    public CallRecord withConversationId(String newConversationId, String newCallControlId, String newCallSessionId, Instant now) {
        return new CallRecord(
                id, externalRef, callSid,
                newCallControlId != null ? newCallControlId : callControlId,
                newCallSessionId != null ? newCallSessionId : callSessionId,
                newConversationId != null ? newConversationId : conversationId,
                assistantId, fromNumber, toNumber, status, telnyxStatus, answeredBy,
                startedAt, endedAt, durationSec, price, priceUnit, cost, recording,
                callbackUrl, dynamicVariables, collectionAttempts, nextCollectionAt,
                lastError, createdAt, now, transcript
        );
    }

    public CallRecord withRecording(RecordingRef newRecording, Instant now) {
        return new CallRecord(
                id, externalRef, callSid, callControlId, callSessionId, conversationId,
                assistantId, fromNumber, toNumber, status, telnyxStatus, answeredBy,
                startedAt, endedAt, durationSec, price, priceUnit, cost, newRecording,
                callbackUrl, dynamicVariables, collectionAttempts, nextCollectionAt,
                lastError, createdAt, now, transcript
        );
    }

    public CallRecord withCollectedData(
            CallStatus finalStatus,
            String newTelnyxStatus,
            String newAnsweredBy,
            Instant newStartedAt,
            Instant newEndedAt,
            Integer newDurationSec,
            BigDecimal newPrice,
            String newPriceUnit,
            CallCost newCost,
            List<TranscriptLine> newTranscript,
            int newAttempts,
            Instant newNextCollectionAt,
            String newLastError,
            Instant now
    ) {
        return new CallRecord(
                id, externalRef, callSid, callControlId, callSessionId, conversationId,
                assistantId, fromNumber, toNumber, finalStatus,
                newTelnyxStatus != null ? newTelnyxStatus : telnyxStatus,
                newAnsweredBy != null ? newAnsweredBy : answeredBy,
                newStartedAt != null ? newStartedAt : startedAt,
                newEndedAt != null ? newEndedAt : endedAt,
                newDurationSec != null ? newDurationSec : durationSec,
                newPrice != null ? newPrice : price,
                newPriceUnit != null ? newPriceUnit : priceUnit,
                newCost != null ? newCost : cost,
                recording, callbackUrl, dynamicVariables,
                newAttempts, newNextCollectionAt, newLastError,
                createdAt, now,
                newTranscript != null ? newTranscript : transcript
        );
    }
}

