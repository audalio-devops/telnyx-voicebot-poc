package br.com.ia369.atendente.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telnyx")
public record TelnyxProperties(
        String apiKey,
        String publicKey,
        String baseUrl,
        String connectionId,
        String accountSid,
        String assistantId,
        String defaultFrom,
        String publicBaseUrl,
        boolean verifyWebhookSignature,
        long webhookReplayWindowSec,
        long callTimeoutSec,
        long callTimeLimitSec,
        String machineDetection
) {
}

