package com.example.banking.payment;

import com.example.banking.common.BusinessException;
import com.example.banking.common.ErrorCode;
import java.util.UUID;

public class PaymentNotFoundException extends BusinessException {

    public PaymentNotFoundException(UUID paymentId) {
        super(ErrorCode.PAYMENT_NOT_FOUND, "Payment %s was not found".formatted(paymentId));
    }
}
