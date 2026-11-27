package br.com.ia369.atendente.application;

import br.com.ia369.atendente.domain.model.CallRecord;
import br.com.ia369.atendente.domain.model.CallStatus;
import br.com.ia369.atendente.domain.port.CallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class HandleCallStatusUseCase {

    private static final Logger log = LoggerFactory.getLogger(HandleCallStatusUseCase.class);

    private final CallRepository callRepository;
    private final CollectCallDataUseCase collectCallDataUseCase;
    private final CallProcessingLock callLock;

    public HandleCallStatusUseCase(
            CallRepository callRepository,
            CollectCallDataUseCase collectCallDataUseCase,
            CallProcessingLock callLock
    ) {
        this.callRepository = callRepository;
        this.collectCallDataUseCase = collectCallDataUseCase;
        this.callLock = callLock;
    }

    @Async
    public void handleAsync(CallStatusCommand command) {
        handle(command);
    }

    public void handle(CallStatusCommand command) {
        Optional<CallRecord> found = callRepository.findByCallSid(command.callSid());
        if (found.isEmpty()) {
            log.warn("Status callback recebido para callSid desconhecido: {}", command.callSid());
            return;
        }

        CallRecord existing = found.get();
        callLock.run(existing.id(), () -> {
            CallRecord current = callRepository.findById(existing.id()).orElse(existing);
            try (var ignoredId = MDC.putCloseable("callId", current.id().toString());
                 var ignoredSid = MDC.putCloseable("callSid", current.callSid())) {

                CallStatus incomingStatus = CallStatus.fromTelnyxStatus(command.callStatus());
                CallStatus nextStatus = resolveNextStatus(current.status(), incomingStatus);

                Instant now = Instant.now();
                CallRecord updated = new CallRecord(
                        current.id(),
                        current.externalRef(),
                        current.callSid(),
                        firstNonBlank(command.callControlId(), current.callControlId()),
                        firstNonBlank(command.callSessionId(), current.callSessionId()),
                        current.conversationId(),
                        current.assistantId(),
                        current.fromNumber(),
                        current.toNumber(),
                        nextStatus,
                        firstNonBlank(command.callStatus(), current.telnyxStatus()),
                        firstNonBlank(command.answeredBy(), current.answeredBy()),
                        current.startedAt() != null ? current.startedAt() :
                                (nextStatus instanceof CallStatus.InProgress ? now : null),
                        nextStatus.requiresCollection() && current.endedAt() == null ? now : current.endedAt(),
                        command.durationSec() != null ? command.durationSec() : current.durationSec(),
                        current.price(),
                        current.priceUnit(),
                        current.cost(),
                        current.recording(),
                        current.callbackUrl(),
                        current.dynamicVariables(),
                        current.collectionAttempts(),
                        nextStatus.requiresCollection() && current.nextCollectionAt() == null ? now : current.nextCollectionAt(),
                        current.lastError(),
                        current.createdAt(),
                        now,
                        current.transcript()
                );

                CallRecord saved = callRepository.save(updated);
                log.info("Status atualizado: {} -> {} (telnyxStatus={})",
                        current.status().code(), saved.status().code(), saved.telnyxStatus());

                if (saved.status().requiresCollection() && !current.status().isFinalPackageReady()) {
                    collectCallDataUseCase.collect(saved.id());
                }
            }
        });
    }

    private CallStatus resolveNextStatus(CallStatus current, CallStatus incoming) {
        if (current.isFinalPackageReady() || current instanceof CallStatus.Collecting) {
            return current;
        }
        return switch (current) {
            case CallStatus.Ended ignored when incoming.isActiveInTelnyx() -> current;
            case CallStatus.InProgress ignored when (incoming instanceof CallStatus.Queued || incoming instanceof CallStatus.Ringing) -> current;
            case CallStatus.Ringing ignored when incoming instanceof CallStatus.Queued -> current;
            default -> incoming;
        };
    }

    private static String firstNonBlank(String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate : fallback;
    }

    public record CallStatusCommand(
            String callSid,
            String callStatus,
            String answeredBy,
            String callControlId,
            String callSessionId,
            Integer durationSec
    ) {}
}

