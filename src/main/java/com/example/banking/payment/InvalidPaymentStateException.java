package com.example.banking.payment;

import com.example.banking.common.BusinessException;
import com.example.banking.common.ErrorCode;
import java.util.Map;
import java.util.UUID;

public class InvalidPaymentStateException extends BusinessException {

    private final PaymentStatus status;

    public InvalidPaymentStateException(UUID paymentId, PaymentStatus status, String action) {
        super(ErrorCode.INVALID_PAYMENT_STATE,
                "Cannot %s payment %s because it is %s".formatted(action, paymentId, status));
        this.status = status;
    }

    @Override
    public Map<String, Object> details() {
        // Not "status": that member of a problem response is the HTTP status code.
        return Map.of("paymentStatus", status);
    }
}
