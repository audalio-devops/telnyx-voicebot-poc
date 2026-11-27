package br.com.ia369.atendente.domain.port;

import java.io.InputStream;
import java.util.UUID;

public interface RecordingStorage {

    String store(UUID callId, byte[] audioBytes, String contentType);

    InputStream openStream(String storageKey);

    void delete(String storageKey);

    boolean isHealthy();
}

