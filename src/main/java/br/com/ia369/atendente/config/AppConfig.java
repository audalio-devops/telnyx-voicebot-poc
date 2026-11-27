package br.com.ia369.atendente.config;

import com.telnyx.sdk.client.TelnyxClient;
import com.telnyx.sdk.client.okhttp.TelnyxOkHttpClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

@Configuration
@EnableAsync
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
@EnableConfigurationProperties({TelnyxProperties.class, AtendenteProperties.class})
public class AppConfig {

    @Bean(destroyMethod = "close")
    public TelnyxClient telnyxClient(TelnyxProperties properties) {
        TelnyxOkHttpClient.Builder builder = TelnyxOkHttpClient.builder()
                .apiKey(properties.apiKey() != null && !properties.apiKey().isBlank()
                        ? properties.apiKey()
                        : "KEY_UNCONFIGURED");
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            builder.baseUrl(properties.baseUrl());
        }
        if (properties.publicKey() != null && !properties.publicKey().isBlank()) {
            builder.publicKey(properties.publicKey());
        }
        return builder.build();
    }

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .usingDbTime()
                        .build()
        );
    }

    @Bean
    public Counter callsStartedCounter(MeterRegistry registry) {
        return Counter.builder("calls_started_total")
                .description("Total de chamadas iniciadas via Telnyx")
                .register(registry);
    }

    @Bean
    public Counter recordingDownloadFailuresCounter(MeterRegistry registry) {
        return Counter.builder("recording_download_failures_total")
                .description("Total de falhas no download imediato da gravação MP3")
                .register(registry);
    }

    @Bean
    public Timer collectionDurationTimer(MeterRegistry registry) {
        return Timer.builder("collection_duration_seconds")
                .description("Duração da coleta pós-chamada")
                .register(registry);
    }
}

