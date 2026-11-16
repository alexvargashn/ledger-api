package io.github.alexvargashn.ledger.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Account(
        UUID id,
        String owner_name,
        String currency,
        BigDecimal balance,
        long version,
        Instant created_at,
        Instant updated_at
) {

    public Account {
        Objects.requireNonNull(id, "Account ID cannot be null");
        Objects.requireNonNull(owner_name, "Account owner name cannot be null");

        if(owner_name.isBlank()) {
            throw new IllegalArgumentException("Account owner name cannot be null or blank");
        }

        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Invalid currency code");
        }

        if (balance == null || balance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Balance cannot be negative");
        }

        if(version <= 0) {
            throw new IllegalArgumentException("Account version must be positive");
        }
    }
}
