package br.com.ia369.atendente.application;

import br.com.ia369.atendente.config.AtendenteProperties;
import br.com.ia369.atendente.config.TelnyxProperties;
import br.com.ia369.atendente.domain.model.CallRecord;
import br.com.ia369.atendente.domain.model.CallStatus;
import br.com.ia369.atendente.domain.model.OutboundNotification;
import br.com.ia369.atendente.domain.port.CallRepository;
import br.com.ia369.atendente.domain.port.RecordingStorage;
import br.com.ia369.atendente.domain.port.ResultNotifier;
import br.com.ia369.atendente.domain.port.TelephonyGateway;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class ReconcileStuckCallsJob {

    private static final Logger log = LoggerFactory.getLogger(ReconcileStuckCallsJob.class);

    private final CallRepository callRepository;
    private final TelephonyGateway telephonyGateway;
    private final CollectCallDataUseCase collectCallDataUseCase;
    private final ResultNotifier resultNotifier;
    private final RecordingStorage recordingStorage;
    private final TelnyxProperties telnyxProperties;
    private final AtendenteProperties atendenteProperties;

    public ReconcileStuckCallsJob(
            CallRepository callRepository,
            TelephonyGateway telephonyGateway,
            CollectCallDataUseCase collectCallDataUseCase,
            ResultNotifier resultNotifier,
            RecordingStorage recordingStorage,
            TelnyxProperties telnyxProperties,
            AtendenteProperties atendenteProperties
    ) {
        this.callRepository = callRepository;
        this.telephonyGateway = telephonyGateway;
        this.collectCallDataUseCase = collectCallDataUseCase;
        this.resultNotifier = resultNotifier;
        this.recordingStorage = recordingStorage;
        this.telnyxProperties = telnyxProperties;
        this.atendenteProperties = atendenteProperties;
    }

    @Scheduled(fixedDelayString = "${atendente.collection.reconcile-interval-ms:60000}")
    @SchedulerLock(name = "reconcileStuckCalls", lockAtMostFor = "PT4M", lockAtLeastFor = "PT10S")
    public void reconcileAndCollect() {
        Instant now = Instant.now();

        // 1. Reconciliar chamadas presas em QUEUED/RINGING/IN_PROGRESS após TimeLimit + margem (120s)
        long timeLimit = telnyxProperties.callTimeLimitSec() > 0 ? telnyxProperties.callTimeLimitSec() : 600L;
        Instant stuckCutoff = now.minusSeconds(timeLimit + 120L);
        List<CallRecord> stuck = callRepository.findStuckCalls(stuckCutoff);
        for (CallRecord call : stuck) {
            try {
                if (call.callSid() == null) {
                    callRepository.save(call.withStatus(CallStatus.Failed.INSTANCE, "failed", now));
                    continue;
                }
                Optional<TelephonyGateway.CallDetails> details = telephonyGateway.fetchCall(call.callSid());
                if (details.isPresent()) {
                    CallStatus status = CallStatus.fromTelnyxStatus(details.get().status());
                    if (status.requiresCollection()) {
                        CallRecord updated = call.withStatus(status, details.get().status(), now);
                        callRepository.save(updated);
                        collectCallDataUseCase.collect(call.id());
                    }
                }
            } catch (Exception e) {
                log.warn("Erro ao reconciliar chamada {}: {}", call.id(), e.getMessage());
            }
        }

        // 2. Retentativas de coleta pendentes
        int maxAttempts = atendenteProperties.collection() != null && atendenteProperties.collection().maxAttempts() > 0
                ? atendenteProperties.collection().maxAttempts()
                : 8;
        List<CallRecord> pending = callRepository.findCallsPendingCollection(maxAttempts, now);
        for (CallRecord call : pending) {
            try {
                collectCallDataUseCase.collect(call.id());
            } catch (Exception e) {
                log.warn("Erro na retentativa de coleta para {}: {}", call.id(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelayString = "${atendente.collection.notify-interval-ms:30000}")
    @SchedulerLock(name = "deliverPendingNotifications", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void deliverPendingNotifications() {
        List<OutboundNotification> due = resultNotifier.findDueNotifications(Instant.now(), 8);
        for (OutboundNotification notification : due) {
            callRepository.findById(notification.callId()).ifPresent(call ->
                    resultNotifier.deliverNotification(notification, call)
            );
        }
    }

    @Scheduled(cron = "${atendente.retention.cron:0 0 3 * * *}")
    @SchedulerLock(name = "purgeExpiredCallData", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purgeExpiredData() {
        long days = atendenteProperties.retention() != null ? atendenteProperties.retention().days() : 90L;
        if (days <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<CallRecord> expired = callRepository.findExpiredCalls(cutoff);
        if (expired.isEmpty()) {
            return;
        }
        for (CallRecord call : expired) {
            if (call.recording() != null && call.recording().storageKey() != null) {
                recordingStorage.delete(call.recording().storageKey());
            }
        }
        List<UUID> ids = expired.stream().map(CallRecord::id).toList();
        int deleted = callRepository.deleteByIds(ids);
        log.info("Expurgo LGPD concluído: {} chamadas expiradas removidas (retenção={} dias)", deleted, days);
    }
}

