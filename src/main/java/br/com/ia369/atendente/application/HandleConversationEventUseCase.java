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
public class HandleConversationEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(HandleConversationEventUseCase.class);

    private final CallRepository callRepository;
    private final CollectCallDataUseCase collectCallDataUseCase;
    private final CallProcessingLock callLock;

    public HandleConversationEventUseCase(
            CallRepository callRepository,
            CollectCallDataUseCase collectCallDataUseCase,
            CallProcessingLock callLock
    ) {
        this.callRepository = callRepository;
        this.collectCallDataUseCase = collectCallDataUseCase;
        this.callLock = callLock;
    }

    @Async
    public void handleAsync(ConversationEventCommand command) {
        handle(command);
    }

    public void handle(ConversationEventCommand command) {
        Optional<CallRecord> found = findMatchingCall(command);
        if (found.isEmpty()) {
            log.warn("ConversationCallback recebido sem chamada correspondente (callSid={}, conversationId={})",
                    command.callSid(), command.conversationId());
            return;
        }

        CallRecord existing = found.get();
        callLock.run(existing.id(), () -> {
            CallRecord current = callRepository.findById(existing.id()).orElse(existing);
            CallRecord updated = current.withConversationId(
                    command.conversationId(),
                    command.callControlId(),
                    command.callSessionId(),
                    Instant.now()
            );
            CallRecord saved = callRepository.save(updated);

            try (var ignoredId = MDC.putCloseable("callId", saved.id().toString());
                 var ignoredConv = MDC.putCloseable("conversationId", String.valueOf(saved.conversationId()))) {
                log.info("Evento de conversa '{}' vinculado à chamada (callSid={})", command.eventType(), saved.callSid());

                if ("conversation_ended".equalsIgnoreCase(command.eventType())
                        && (saved.status().requiresCollection() || saved.status() instanceof CallStatus.Partial)) {
                    collectCallDataUseCase.collect(saved.id());
                }
            }
        });
    }

    private Optional<CallRecord> findMatchingCall(ConversationEventCommand command) {
        if (command.callSid() != null && !command.callSid().isBlank()) {
            Optional<CallRecord> bySid = callRepository.findByCallSid(command.callSid());
            if (bySid.isPresent()) return bySid;
        }
        if (command.callControlId() != null && !command.callControlId().isBlank()) {
            Optional<CallRecord> byControl = callRepository.findByCallSid(command.callControlId());
            if (byControl.isPresent()) return byControl;
        }
        if (command.conversationId() != null && !command.conversationId().isBlank()) {
            return callRepository.findByConversationId(command.conversationId());
        }
        return Optional.empty();
    }

    public record ConversationEventCommand(
            String eventType,
            String conversationId,
            String callSid,
            String callControlId,
            String callSessionId
    ) {}
}

