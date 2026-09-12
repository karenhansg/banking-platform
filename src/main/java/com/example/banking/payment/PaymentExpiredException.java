package com.example.banking.payment;

import com.example.banking.common.BusinessException;
import com.example.banking.common.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class PaymentExpiredException extends BusinessException {

    private final Instant expiredAt;

    public PaymentExpiredException(UUID paymentId, @Nullable Instant expiredAt) {
        super(ErrorCode.PAYMENT_EXPIRED,
                "Payment %s expired at %s and the held funds were returned to the source account"
                        .formatted(paymentId, expiredAt));
        this.expiredAt = Objects.requireNonNull(expiredAt, "expiredAt must not be null");
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("expiredAt", expiredAt);
    }
}
