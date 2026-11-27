package br.com.ia369.atendente.adapter.in.rest.dto;

import java.util.UUID;

public record StartCallResponse(
        UUID id,
        String callSid,
        String status
) {
}

