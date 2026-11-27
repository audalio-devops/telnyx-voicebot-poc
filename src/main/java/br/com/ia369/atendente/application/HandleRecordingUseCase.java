package br.com.ia369.atendente.application;

import br.com.ia369.atendente.domain.model.CallRecord;
import br.com.ia369.atendente.domain.model.RecordingRef;
import br.com.ia369.atendente.domain.port.CallRepository;
import br.com.ia369.atendente.domain.port.RecordingStorage;
import br.com.ia369.atendente.domain.port.TelephonyGateway;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class HandleRecordingUseCase {

    private static final Logger log = LoggerFactory.getLogger(HandleRecordingUseCase.class);

    private final CallRepository callRepository;
    private final TelephonyGateway telephonyGateway;
    private final RecordingStorage recordingStorage;
    private final CollectCallDataUseCase collectCallDataUseCase;
    private final CallProcessingLock callLock;
    private final Counter recordingDownloadFailuresCounter;

    public HandleRecordingUseCase(
            CallRepository callRepository,
            TelephonyGateway telephonyGateway,
            RecordingStorage recordingStorage,
            CollectCallDataUseCase collectCallDataUseCase,
            CallProcessingLock callLock,
            Counter recordingDownloadFailuresCounter
    ) {
        this.callRepository = callRepository;
        this.telephonyGateway = telephonyGateway;
        this.recordingStorage = recordingStorage;
        this.collectCallDataUseCase = collectCallDataUseCase;
        this.callLock = callLock;
        this.recordingDownloadFailuresCounter = recordingDownloadFailuresCounter;
    }

    @Async
    public void handleAsync(RecordingCallbackCommand command) {
        handle(command);
    }

    public void handle(RecordingCallbackCommand command) {
        if (command.mediaUrl() == null || command.mediaUrl().isBlank()) {
            log.debug("Callback de gravação sem mediaUrl (status={}) para callSid={}",
                    command.recordingStatus(), command.callSid());
            return;
        }

        Optional<CallRecord> found = callRepository.findByCallSid(command.callSid());
        if (found.isEmpty()) {
            log.warn("RecordingCallback recebido para callSid desconhecido: {}", command.callSid());
            return;
        }

        CallRecord call = found.get();
        try (var ignoredId = MDC.putCloseable("callId", call.id().toString());
             var ignoredSid = MDC.putCloseable("callSid", call.callSid())) {

            byte[] audioBytes = telephonyGateway.downloadRecording(command.mediaUrl());
            String storageKey = recordingStorage.store(call.id(), audioBytes, "audio/mpeg");
            RecordingRef ref = new RecordingRef(
                    storageKey,
                    command.durationSec(),
                    command.channels() != null ? command.channels() : "dual",
                    command.recordingSid()
            );

            callLock.run(call.id(), () -> {
                CallRecord current = callRepository.findById(call.id()).orElse(call);
                if (current.recording() != null && current.recording().storageKey() != null) {
                    log.debug("Gravação já armazenada para callId={}", current.id());
                    return;
                }
                CallRecord saved = callRepository.save(current.withRecording(ref, Instant.now()));
                log.info("Gravação MP3 baixada e persistida com sucesso (storageKey={}, bytes={})",
                        storageKey, audioBytes.length);
                if (saved.status().requiresCollection() || saved.status() instanceof br.com.ia369.atendente.domain.model.CallStatus.Partial) {
                    collectCallDataUseCase.collect(saved.id());
                }
            });
        } catch (Exception e) {
            recordingDownloadFailuresCounter.increment();
            log.error("Falha ao baixar gravação imediata para callSid={}: {}", command.callSid(), e.getMessage());
        }
    }

    public record RecordingCallbackCommand(
            String callSid,
            String recordingSid,
            String recordingStatus,
            String mediaUrl,
            Integer durationSec,
            String channels
    ) {}
}

