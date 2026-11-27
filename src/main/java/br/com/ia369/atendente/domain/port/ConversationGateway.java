package br.com.ia369.atendente.domain.port;

import br.com.ia369.atendente.domain.model.TranscriptLine;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ConversationGateway {

    Optional<ConversationInfo> findConversationByCallControlId(String callControlId);

    Optional<ConversationInfo> findConversationByEndUserAndWindow(String endUserPhone, Instant from, Instant to);

    Optional<ConversationInfo> getConversation(String conversationId);

    List<TranscriptLine> listMessages(String conversationId);

    record ConversationInfo(
            String conversationId,
            String callControlId,
            String callSessionId,
            String callLegId,
            Instant createdAt
    ) {}
}

