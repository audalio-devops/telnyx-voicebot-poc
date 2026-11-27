package br.com.ia369.atendente.domain.model;

import java.time.Instant;
import java.util.Map;

public record TranscriptLine(
        int seq,
        String role,
        String text,
        Instant spokenAt,
        Map<String, Object> raw
) {
}

