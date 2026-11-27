package br.com.ia369.atendente.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

public record StartCallRequest(
        @NotBlank(message = "Campo 'to' é obrigatório")
        @Pattern(regexp = "^\\+55\\d{10,11}$", message = "Número 'to' deve estar no formato E.164 brasileiro (+55 + DDD + 8 ou 9 dígitos)")
        String to,
        String from,
        String externalRef,
        String assistantId,
        Map<String, String> variables,
        String callbackUrl
) {
}

