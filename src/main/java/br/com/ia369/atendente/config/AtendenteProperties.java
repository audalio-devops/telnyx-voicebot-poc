package br.com.ia369.atendente.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atendente")
public record AtendenteProperties(
        String apiAuthToken,
        String notifyHmacSecret,
        CollectionConfig collection,
        RetentionConfig retention,
        String blocklist,
        StorageConfig storage
) {
    public record CollectionConfig(
            long maxWaitMin,
            int maxAttempts,
            long reconcileIntervalMs,
            long notifyIntervalMs
    ) {}

    public record RetentionConfig(
            long days,
            String cron
    ) {}

    public record StorageConfig(
            String type,
            String localPath,
            S3Config s3
    ) {}

    public record S3Config(
            String bucket,
            String region,
            String endpoint,
            String accessKey,
            String secretKey
    ) {}
}

