package br.com.ia369.atendente.domain.model;

public record RecordingRef(
        String storageKey,
        Integer durationSec,
        String channels,
        String sourceSid
) {
}

