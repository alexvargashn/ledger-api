package io.github.alexvargashn.ledger.domain.model;

import org.hibernate.annotations.DialectOverride;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;import java.util.UUID;

public record Transfer(
        UUID id,
        UUID from_account_id,
        UUID to_account_id,
        BigDecimal amount,
        String currency,
        Status status,
        String failure_reason,
        Instant created_at,
        Instant updated_at
) {
    public Transfer {
        Objects.requireNonNull(id, "Transfer ID cannot be null");
        Objects.requireNonNull(to_account_id, "Transfer To account ID cannot be null");
        Objects.requireNonNull(from_account_id, "Transfer From account ID cannot be null");

        if( amount == null || amount.compareTo(BigDecimal.ZERO) <= 0 ) {
            throw new IllegalArgumentException("Transfer amount must be greater than zero");
        }

        if(from_account_id.equals(to_account_id)) {
            throw new IllegalArgumentException("Transfer From account ID cannot be the same as To Account");
        }

        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Invalid currency code");
        }

        if(!List.of(Status.values()).contains(status)) {
            throw new IllegalArgumentException("Transfer status cannot be " + status);
        }

        if(status.equals(Status.FAILED)) {
            Objects.requireNonNull(failure_reason, "Transfer failure reason cannot be null");
        }

    }
}
