package br.com.ia369.atendente.domain.port;

public interface WebhookEventRepository {

    /**
     * Registra evento de webhook caso ainda não tenha sido recebido.
     *
     * @return true se foi inserido agora (novo), false se já existia (duplicado)
     */
    boolean registerIfNew(String source, String eventKey, String payloadJson);

    void markProcessed(String eventKey);
}

