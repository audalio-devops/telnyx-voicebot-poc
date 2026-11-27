package br.com.ia369.atendente.application;

import br.com.ia369.atendente.config.AtendenteProperties;
import br.com.ia369.atendente.domain.model.CallCost;
import br.com.ia369.atendente.domain.model.CallRecord;
import br.com.ia369.atendente.domain.model.CallStatus;
import br.com.ia369.atendente.domain.model.RecordingRef;
import br.com.ia369.atendente.domain.model.TranscriptLine;
import br.com.ia369.atendente.domain.port.CallRepository;
import br.com.ia369.atendente.domain.port.ConversationGateway;
import br.com.ia369.atendente.domain.port.CostGateway;
import br.com.ia369.atendente.domain.port.RecordingStorage;
import br.com.ia369.atendente.domain.port.ResultNotifier;
import br.com.ia369.atendente.domain.port.TelephonyGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CollectCallDataUseCase {

    private static final Logger log = LoggerFactory.getLogger(CollectCallDataUseCase.class);
    private static final int[] BACKOFF_SECONDS = {15, 30, 60, 120, 300, 300, 600, 600};

    private final CallRepository callRepository;
    private final TelephonyGateway telephonyGateway;
    private final ConversationGateway conversationGateway;
    private final CostGateway costGateway;
    private final RecordingStorage recordingStorage;
    private final ResultNotifier resultNotifier;
    private final AtendenteProperties properties;
    private final CallProcessingLock callLock;
    private final MeterRegistry meterRegistry;
    private final Counter recordingDownloadFailuresCounter;
    private final Timer collectionDurationTimer;

    public CollectCallDataUseCase(
            CallRepository callRepository,
            TelephonyGateway telephonyGateway,
            ConversationGateway conversationGateway,
            CostGateway costGateway,
            RecordingStorage recordingStorage,
            ResultNotifier resultNotifier,
            AtendenteProperties properties,
            CallProcessingLock callLock,
            MeterRegistry meterRegistry,
            Counter recordingDownloadFailuresCounter,
            Timer collectionDurationTimer
    ) {
        this.callRepository = callRepository;
        this.telephonyGateway = telephonyGateway;
        this.conversationGateway = conversationGateway;
        this.costGateway = costGateway;
        this.recordingStorage = recordingStorage;
        this.resultNotifier = resultNotifier;
        this.properties = properties;
        this.callLock = callLock;
        this.meterRegistry = meterRegistry;
        this.recordingDownloadFailuresCounter = recordingDownloadFailuresCounter;
        this.collectionDurationTimer = collectionDurationTimer;
    }

    public CallRecord collect(UUID callId) {
        return callLock.execute(callId, () -> doCollect(callId));
    }

    private CallRecord doCollect(UUID callId) {
        Optional<CallRecord> opt = callRepository.findById(callId);
        if (opt.isEmpty()) {
            return null;
        }
        CallRecord call = opt.get();
        if (call.status() instanceof CallStatus.Completed) {
            return call;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try (var ignoredId = MDC.putCloseable("callId", call.id().toString());
             var ignoredSid = MDC.putCloseable("callSid", String.valueOf(call.callSid()))) {

            int attempt = call.collectionAttempts() + 1;
            String lastError = null;

            // 1. Detalhes da chamada TeXML
            Optional<TelephonyGateway.CallDetails> detailsOpt = telephonyGateway.fetchCall(call.callSid());
            String telnyxStatus = detailsOpt.map(TelephonyGateway.CallDetails::status).orElse(call.telnyxStatus());
            String answeredBy = detailsOpt.map(TelephonyGateway.CallDetails::answeredBy).orElse(call.answeredBy());
            Instant startedAt = detailsOpt.map(TelephonyGateway.CallDetails::startedAt).orElse(call.startedAt());
            Instant endedAt = detailsOpt.map(TelephonyGateway.CallDetails::endedAt).orElse(call.endedAt());
            Integer durationSec = detailsOpt.map(TelephonyGateway.CallDetails::durationSec).orElse(call.durationSec());
            var price = detailsOpt.map(TelephonyGateway.CallDetails::price).orElse(call.price());
            String priceUnit = detailsOpt.map(TelephonyGateway.CallDetails::priceUnit).orElse(call.priceUnit());

            // 2. Fallback para gravação caso o RecordingStatusCallback ainda não tenha persistido o MP3
            if (call.recording() == null && wasAnswered(call, telnyxStatus, durationSec)) {
                call = tryFallbackRecordingDownload(call);
            }

            // 3. Vincular conversationId / callSessionId se ausentes
            String conversationId = call.conversationId();
            String callControlId = call.callControlId() != null ? call.callControlId() : call.callSid();
            String callSessionId = call.callSessionId();

            Optional<ConversationGateway.ConversationInfo> convInfo = Optional.empty();
            if (conversationId != null && !conversationId.isBlank()) {
                convInfo = conversationGateway.getConversation(conversationId);
            } else if (callControlId != null && !callControlId.isBlank()) {
                convInfo = conversationGateway.findConversationByCallControlId(callControlId);
            }
            if (convInfo.isEmpty() && wasAnswered(call, telnyxStatus, durationSec)) {
                convInfo = conversationGateway.findConversationByEndUserAndWindow(
                        call.toNumber(), call.createdAt(), Instant.now()
                );
            }
            if (convInfo.isPresent()) {
                conversationId = convInfo.get().conversationId();
                if (convInfo.get().callControlId() != null) {
                    callControlId = convInfo.get().callControlId();
                }
                if (convInfo.get().callSessionId() != null) {
                    callSessionId = convInfo.get().callSessionId();
                }
                call = call.withConversationId(conversationId, callControlId, callSessionId, Instant.now());
            }

            // 4. Transcrição turno a turno
            List<TranscriptLine> transcript = call.transcript();
            if (conversationId != null && !conversationId.isBlank()) {
                List<TranscriptLine> fetched = conversationGateway.listMessages(conversationId);
                if (!fetched.isEmpty()) {
                    transcript = fetched;
                }
            }

            // 5. Custo oficial via Session Analysis (ou fallback)
            CallCost cost = call.cost();
            if (callSessionId != null && !callSessionId.isBlank()) {
                Optional<CallCost> fetchedCost = costGateway.fetchSessionCost(callSessionId);
                if (fetchedCost.isPresent()) {
                    cost = fetchedCost.get();
                }
            }
            if ((cost == null || !"FINAL".equals(cost.status())) && price != null) {
                cost = new CallCost(price, priceUnit != null ? priceUnit : "USD", price, null, "PENDING", Map.of());
            }

            // 6. Avaliar completude dos artefatos
            boolean answered = wasAnswered(call, telnyxStatus, durationSec);
            boolean hasRecording = call.recording() != null && call.recording().storageKey() != null;
            boolean hasTranscript = transcript != null && !transcript.isEmpty();
            boolean hasFinalCost = cost != null && "FINAL".equals(cost.status());

            boolean complete = answered
                    ? (detailsOpt.isPresent() && hasRecording && hasTranscript && hasFinalCost)
                    : (detailsOpt.isPresent() && (callSessionId == null || hasFinalCost));

            int maxAttempts = properties.collection() != null && properties.collection().maxAttempts() > 0
                    ? properties.collection().maxAttempts()
                    : 8;
            long maxWaitMin = properties.collection() != null && properties.collection().maxWaitMin() > 0
                    ? properties.collection().maxWaitMin()
                    : 30L;

            Instant referenceStart = call.createdAt() != null ? call.createdAt() : Instant.now();
            boolean expiredWindow = attempt >= 2 && Duration.between(referenceStart, Instant.now()).toMinutes() >= maxWaitMin;
            boolean exhausted = attempt >= maxAttempts || expiredWindow;

            CallStatus nextStatus;
            Instant nextCollectionAt = null;
            if (complete) {
                nextStatus = CallStatus.Completed.INSTANCE;
            } else if (exhausted) {
                nextStatus = CallStatus.Partial.INSTANCE;
                lastError = buildMissingReason(answered, detailsOpt.isPresent(), hasRecording, hasTranscript, hasFinalCost);
            } else {
                nextStatus = CallStatus.Collecting.INSTANCE;
                int delaySec = BACKOFF_SECONDS[Math.min(attempt - 1, BACKOFF_SECONDS.length - 1)];
                nextCollectionAt = Instant.now().plusSeconds(delaySec);
                lastError = buildMissingReason(answered, detailsOpt.isPresent(), hasRecording, hasTranscript, hasFinalCost);
            }

            CallRecord saved = callRepository.save(call.withCollectedData(
                    nextStatus,
                    telnyxStatus,
                    answeredBy,
                    startedAt,
                    endedAt,
                    durationSec,
                    price,
                    priceUnit,
                    cost,
                    transcript,
                    attempt,
                    nextCollectionAt,
                    lastError,
                    Instant.now()
            ));

            if (nextStatus.isFinalPackageReady()) {
                meterRegistry.counter("calls_completed_total", "status", nextStatus.code()).increment();
                if (cost != null && cost.total() != null) {
                    meterRegistry.counter("call_cost_usd_sum").increment(cost.total().doubleValue());
                }
                resultNotifier.enqueueNotification(saved);
                log.info("Pacote da chamada finalizado com status {} (tentativas={}, linhas={}, recording={})",
                        nextStatus.code(), attempt, transcript != null ? transcript.size() : 0, hasRecording);
            } else {
                log.info("Coleta parcial (tentativa {}/{}), nova tentativa em {}: {}",
                        attempt, maxAttempts, nextCollectionAt, lastError);
            }

            return saved;
        } finally {
            sample.stop(collectionDurationTimer);
        }
    }

    private CallRecord tryFallbackRecordingDownload(CallRecord call) {
        try {
            List<TelephonyGateway.RecordingInfo> recordings = telephonyGateway.listRecordings(call.callSid());
            for (TelephonyGateway.RecordingInfo rec : recordings) {
                if (rec.mediaUrl() != null && !rec.mediaUrl().isBlank()) {
                    byte[] bytes = telephonyGateway.downloadRecording(rec.mediaUrl());
                    String storageKey = recordingStorage.store(call.id(), bytes, "audio/mpeg");
                    RecordingRef ref = new RecordingRef(storageKey, rec.durationSec(), rec.channels(), rec.sid());
                    return callRepository.save(call.withRecording(ref, Instant.now()));
                }
            }
        } catch (Exception e) {
            recordingDownloadFailuresCounter.increment();
            log.warn("Fallback de download de gravação falhou para callSid={}: {}", call.callSid(), e.getMessage());
        }
        return call;
    }

    private boolean wasAnswered(CallRecord call, String telnyxStatus, Integer durationSec) {
        if (call.status() instanceof CallStatus.NoAnswer
                || call.status() instanceof CallStatus.Busy
                || call.status() instanceof CallStatus.Failed
                || call.status() instanceof CallStatus.Canceled) {
            return false;
        }
        if (telnyxStatus != null) {
            String normalized = telnyxStatus.toLowerCase();
            if (normalized.equals("no-answer") || normalized.equals("busy")
                    || normalized.equals("failed") || normalized.equals("canceled")) {
                return false;
            }
        }
        return durationSec == null || durationSec > 0;
    }

    private String buildMissingReason(
            boolean answered,
            boolean hasDetails,
            boolean hasRecording,
            boolean hasTranscript,
            boolean hasFinalCost
    ) {
        StringBuilder sb = new StringBuilder("Aguardando: ");
        if (!hasDetails) sb.append("detalhes ");
        if (answered && !hasRecording) sb.append("gravação_mp3 ");
        if (answered && !hasTranscript) sb.append("transcrição ");
        if (!hasFinalCost) sb.append("custo_final ");
        return sb.toString().trim();
    }
}

