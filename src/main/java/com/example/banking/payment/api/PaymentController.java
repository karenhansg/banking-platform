package com.example.banking.payment.api;

import com.example.banking.common.web.IdempotencyKey;
import com.example.banking.common.web.PageResponse;
import com.example.banking.payment.PaymentService;
import com.example.banking.payment.PaymentStatus;
import com.example.banking.payment.ScheduledPayment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Scheduled payments")
public class PaymentController {

    static final int MAX_PAGE_SIZE = 100;

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @Operation(
            summary = "Schedule a payment to another account",
            description = "Without executeAt the funds are held immediately (status PENDING_ACCEPTANCE); otherwise "
                    + "the payment is SCHEDULED and executes at that time. Held funds count towards the source "
                    + "account's daily withdrawal limit on the day they are taken.")
    public ResponseEntity<PaymentResponse> schedulePayment(
            @Valid @RequestBody SchedulePaymentRequest request,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false)
            @Pattern(regexp = IdempotencyKey.PATTERN) @Nullable String idempotencyKey) {
        ScheduledPayment payment = paymentService.schedulePayment(request.toCommand(idempotencyKey));
        URI location = UriComponentsBuilder.fromPath("/api/v1/payments/{paymentId}")
                .buildAndExpand(payment.getId())
                .toUri();
        return ResponseEntity.created(location).body(PaymentResponse.from(payment));
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get a payment and its status")
    public PaymentResponse getPayment(@PathVariable UUID paymentId) {
        return PaymentResponse.from(paymentService.getPayment(paymentId));
    }

    @GetMapping
    @Operation(summary = "List payments sent or received by an account, newest first")
    public PageResponse<PaymentResponse> findPayments(
            @RequestParam UUID accountId,
            @RequestParam(required = false) @Nullable PaymentStatus status,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.of(paymentService.findPayments(accountId, status, pageRequest), PaymentResponse::from);
    }

    @PostMapping("/{paymentId}/accept")
    @Operation(summary = "Accept a payment as the target account, crediting the held funds")
    public PaymentResponse acceptPayment(@PathVariable UUID paymentId) {
        return PaymentResponse.from(paymentService.acceptPayment(paymentId));
    }

    @PostMapping("/{paymentId}/cancel")
    @Operation(summary = "Cancel a payment that has not executed yet")
    public PaymentResponse cancelPayment(@PathVariable UUID paymentId) {
        return PaymentResponse.from(paymentService.cancelPayment(paymentId));
    }
}
