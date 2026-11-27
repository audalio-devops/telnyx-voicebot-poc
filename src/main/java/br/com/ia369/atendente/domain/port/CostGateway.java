package br.com.ia369.atendente.domain.port;

import br.com.ia369.atendente.domain.model.CallCost;

import java.util.Optional;

public interface CostGateway {

    Optional<CallCost> fetchSessionCost(String callSessionId);
}

