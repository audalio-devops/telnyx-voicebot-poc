package br.com.ia369.atendente.domain.model;

import java.math.BigDecimal;
import java.util.Map;

public record CallCost(
        BigDecimal total,
        String currency,
        BigDecimal telephony,
        BigDecimal ai,
        String status,
        Map<String, Object> breakdown
) {
    public static CallCost pending() {
        return new CallCost(null, "USD", null, null, "PENDING", Map.of());
    }
}

