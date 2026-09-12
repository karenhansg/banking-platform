package com.example.banking.payment;

import com.example.banking.common.BusinessException;
import com.example.banking.common.ErrorCode;

public class InvalidPaymentRequestException extends BusinessException {

    public InvalidPaymentRequestException(String message) {
        super(ErrorCode.INVALID_PAYMENT_REQUEST, message);
    }
}
