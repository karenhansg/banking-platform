package com.example.banking.common;

public class IdempotencyKeyReusedException extends BusinessException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "Idempotency key '%s' was already used for a different request".formatted(idempotencyKey));
    }
}
