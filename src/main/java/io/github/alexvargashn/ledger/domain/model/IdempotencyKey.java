package io.github.alexvargashn.ledger.domain.model;

import org.springframework.boot.jackson.autoconfigure.JacksonProperties;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IdempotencyKey(
        UUID key,
        String request_hash,
        String status,
        UUID response_code,
        String response_body,
        UUID transfer_id,
        Instant created_at
) {
    public IdempotencyKey {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(request_hash, "request_hash cannot be null");
    }
}
