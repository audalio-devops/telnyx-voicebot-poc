package br.com.ia369.atendente.domain.model;

import java.time.Instant;
import java.util.UUID;

public record OutboundNotification(
        UUID id,
        UUID callId,
        String targetUrl,
        String status,
        int attempts,
        Instant nextAttemptAt,
        String lastError
) {
}

