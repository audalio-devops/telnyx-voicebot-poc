package br.com.ia369.atendente.config;

import br.com.ia369.atendente.domain.port.RecordingStorage;
import br.com.ia369.atendente.domain.port.TelephonyGateway;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("atendenteDependencies")
public class AtendenteHealthIndicator implements HealthIndicator {

    private final RecordingStorage recordingStorage;
    private final TelephonyGateway telephonyGateway;

    public AtendenteHealthIndicator(
            RecordingStorage recordingStorage,
            TelephonyGateway telephonyGateway
    ) {
        this.recordingStorage = recordingStorage;
        this.telephonyGateway = telephonyGateway;
    }

    @Override
    public Health health() {
        boolean storageOk = recordingStorage.isHealthy();
        boolean telnyxOk = telephonyGateway.isHealthy();
        Health.Builder builder = (storageOk && telnyxOk) ? Health.up() : Health.down();
        return builder
                .withDetail("storage", storageOk ? "UP" : "DOWN")
                .withDetail("telnyxApi", telnyxOk ? "UP" : "DOWN")
                .build();
    }
}

