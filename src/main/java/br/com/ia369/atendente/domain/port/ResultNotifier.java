package br.com.ia369.atendente.domain.port;

import br.com.ia369.atendente.domain.model.CallRecord;
import br.com.ia369.atendente.domain.model.OutboundNotification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ResultNotifier {

    void enqueueNotification(CallRecord callRecord);

    void deliverNotification(OutboundNotification notification, CallRecord callRecord);

    List<OutboundNotification> findDueNotifications(Instant now, int maxAttempts);
}

