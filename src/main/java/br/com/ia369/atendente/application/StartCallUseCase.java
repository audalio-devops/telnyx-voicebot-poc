package br.com.ia369.atendente.application;

import br.com.ia369.atendente.config.AtendenteProperties;
import br.com.ia369.atendente.config.TelnyxProperties;
import br.com.ia369.atendente.domain.model.CallCost;
import br.com.ia369.atendente.domain.model.CallRecord;
import br.com.ia369.atendente.domain.model.CallStatus;
import br.com.ia369.atendente.domain.port.CallRepository;
import br.com.ia369.atendente.domain.port.TelephonyGateway;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class StartCallUseCase {

    private static final Logger log = LoggerFactory.getLogger(StartCallUseCase.class);
    private static final Pattern BR_E164_PATTERN = Pattern.compile("^\\+55\\d{10,11}$");
    private static final Pattern GENERIC_E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final CallRepository callRepository;
    private final TelephonyGateway telephonyGateway;
    private final TelnyxProperties telnyxProperties;
    private final AtendenteProperties atendenteProperties;
    private final Counter callsStartedCounter;

    public StartCallUseCase(
            CallRepository callRepository,
            TelephonyGateway telephonyGateway,
            TelnyxProperties telnyxProperties,
            AtendenteProperties atendenteProperties,
            Counter callsStartedCounter
    ) {
        this.callRepository = callRepository;
        this.telephonyGateway = telephonyGateway;
        this.telnyxProperties = telnyxProperties;
        this.atendenteProperties = atendenteProperties;
        this.callsStartedCounter = callsStartedCounter;
    }

    public CallRecord execute(StartCallCommand command) {
        String to = normalizePhone(command.to());
        validateBrazilianDestination(to);
        checkBlocklist(to);

        String from = command.from() != null && !command.from().isBlank()
                ? normalizePhone(command.from())
                : normalizePhone(telnyxProperties.defaultFrom());
        if (from == null || !GENERIC_E164_PATTERN.matcher(from).matches()) {
            throw new InvalidCallRequestException("Número de origem (from) inválido no formato E.164: " + from);
        }

        String assistantId = command.assistantId() != null && !command.assistantId().isBlank()
                ? command.assistantId().trim()
                : telnyxProperties.assistantId();

        UUID callId = UUID.randomUUID();
        Instant now = Instant.now();

        CallRecord initial = new CallRecord(
                callId,
                command.externalRef(),
                null,
                null,
                null,
                null,
                assistantId,
                from,
                to,
                CallStatus.Queued.INSTANCE,
                "queued",
                null,
                null,
                null,
                null,
                null,
                null,
                CallCost.pending(),
                null,
                command.callbackUrl(),
                command.variables() != null ? Map.copyOf(command.variables()) : Map.of(),
                0,
                null,
                null,
                now,
                now,
                List.of()
        );
        callRepository.save(initial);

        String baseUrl = stripTrailingSlash(telnyxProperties.publicBaseUrl());
        TelephonyGateway.CallbackUrls callbacks = new TelephonyGateway.CallbackUrls(
                baseUrl + "/webhooks/telnyx/call-status",
                baseUrl + "/webhooks/telnyx/recording",
                baseUrl + "/webhooks/telnyx/conversation"
        );

        try (var ignored = MDC.putCloseable("callId", callId.toString())) {
            TelephonyGateway.StartedCall started = telephonyGateway.startAiCall(
                    from,
                    to,
                    assistantId,
                    command.variables() != null ? command.variables() : Map.of(),
                    callbacks
            );
            CallRecord updated = initial.withCallSid(started.callSid(), started.status(), Instant.now());
            CallRecord saved = callRepository.save(updated);
            callsStartedCounter.increment();
            try (var ignoredSid = MDC.putCloseable("callSid", started.callSid())) {
                log.info("Chamada iniciada na Telnyx para {} (externalRef={})", to, command.externalRef());
            }
            return saved;
        } catch (RuntimeException ex) {
            CallRecord failed = new CallRecord(
                    initial.id(), initial.externalRef(), initial.callSid(), initial.callControlId(),
                    initial.callSessionId(), initial.conversationId(), initial.assistantId(),
                    initial.fromNumber(), initial.toNumber(), CallStatus.Failed.INSTANCE, "failed",
                    null, null, Instant.now(), 0, null, null, null, null,
                    initial.callbackUrl(), initial.dynamicVariables(), 0, null,
                    ex.getMessage(), initial.createdAt(), Instant.now(), List.of()
            );
            callRepository.save(failed);
            throw ex;
        }
    }

    private void validateBrazilianDestination(String to) {
        if (to == null || !BR_E164_PATTERN.matcher(to).matches()) {
            throw new InvalidCallRequestException(
                    "Número de destino deve estar em E.164 brasileiro (+55 + DDD + 8 ou 9 dígitos): " + to
            );
        }
    }

    private void checkBlocklist(String to) {
        if (atendenteProperties.blocklist() == null || atendenteProperties.blocklist().isBlank()) {
            return;
        }
        Set<String> blocked = Arrays.stream(atendenteProperties.blocklist().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        if (blocked.contains(to)) {
            throw new InvalidCallRequestException("Número bloqueado pela lista de bloqueio: " + to);
        }
    }

    private static String normalizePhone(String raw) {
        return raw != null ? raw.replaceAll("\\s+", "").trim() : null;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public record StartCallCommand(
            String to,
            String from,
            String externalRef,
            String assistantId,
            Map<String, String> variables,
            String callbackUrl
    ) {}

    public static class InvalidCallRequestException extends RuntimeException {
        public InvalidCallRequestException(String message) {
            super(message);
        }
    }
}

