package br.com.ia369.atendente.domain.port;

import br.com.ia369.atendente.domain.model.CallRecord;
import br.com.ia369.atendente.domain.model.PageResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CallRepository {

    CallRecord save(CallRecord record);

    Optional<CallRecord> findById(UUID id);

    Optional<CallRecord> findByCallSid(String callSid);

    Optional<CallRecord> findByConversationId(String conversationId);

    PageResult<CallRecord> search(String status, String from, String to, String externalRef, int page, int size);

    List<CallRecord> findStuckCalls(Instant startedBefore);

    List<CallRecord> findCallsPendingCollection(int maxAttempts, Instant now);

    List<CallRecord> findExpiredCalls(Instant createdBefore);

    int deleteByIds(List<UUID> ids);
}

