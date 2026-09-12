package com.example.banking.payment;

import com.example.banking.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A payment from one account to another. The state transitions (see {@link PaymentStatus}) are package-private: only
 * {@link PaymentService} drives them, together with the matching ledger postings.
 */
@Entity
@Table(name = "scheduled_payments")
public class ScheduledPayment {

    static final int MAX_FAILURE_REASON_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", nullable = false, updatable = false)
    private UUID targetAccountId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 140, updatable = false)
    private @Nullable String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "execute_at", nullable = false, updatable = false)
    private Instant executeAt;

    @Column(name = "held_at")
    private @Nullable Instant heldAt;

    @Column(name = "expires_at")
    private @Nullable Instant expiresAt;

    @Column(name = "resolved_at")
    private @Nullable Instant resolvedAt;

    @Column(name = "failure_reason", length = MAX_FAILURE_REASON_LENGTH)
    private @Nullable String failureReason;

    @Column(name = "idempotency_key", length = 64, updatable = false)
    private @Nullable String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ScheduledPayment() {
        // for JPA
    }

    ScheduledPayment(UUID sourceAccountId, UUID targetAccountId, BigDecimal amount, Instant executeAt,
            @Nullable String description, @Nullable String idempotencyKey, Instant now) {
        if (Objects.requireNonNull(sourceAccountId).equals(Objects.requireNonNull(targetAccountId))) {
            throw new IllegalArgumentException("Source and target accounts must be different");
        }
        this.sourceAccountId = sourceAccountId;
        this.targetAccountId = targetAccountId;
        this.amount = Money.positive(amount);
        this.executeAt = Objects.requireNonNull(executeAt);
        this.description = description;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.SCHEDULED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Whether the hold has reached its expiry time and can no longer be accepted. */
    boolean isOverdue(Instant now) {
        return status == PaymentStatus.PENDING_ACCEPTANCE && expiresAt != null && !now.isBefore(expiresAt);
    }

    /** Funds have been taken from the source account; they now wait for the target to accept. */
    void hold(Instant now, Duration acceptanceWindow) {
        requireStatus(PaymentStatus.SCHEDULED, "execute");
        status = PaymentStatus.PENDING_ACCEPTANCE;
        heldAt = now;
        expiresAt = now.plus(acceptanceWindow);
        updatedAt = now;
    }

    void complete(Instant now) {
        requireStatus(PaymentStatus.PENDING_ACCEPTANCE, "accept");
        if (isOverdue(now)) {
            throw new PaymentExpiredException(id, expiresAt);
        }
        resolve(PaymentStatus.COMPLETED, now);
    }

    void expire(Instant now) {
        requireStatus(PaymentStatus.PENDING_ACCEPTANCE, "expire");
        if (!isOverdue(now)) {
            throw new IllegalStateException("Payment %s does not expire until %s".formatted(id, expiresAt));
        }
        resolve(PaymentStatus.EXPIRED, now);
    }

    void cancel(Instant now) {
        requireStatus(PaymentStatus.SCHEDULED, "cancel");
        resolve(PaymentStatus.CANCELLED, now);
    }

    void fail(String reason, Instant now) {
        requireStatus(PaymentStatus.SCHEDULED, "fail");
        failureReason = reason.length() > MAX_FAILURE_REASON_LENGTH ? reason.substring(0, MAX_FAILURE_REASON_LENGTH) : reason;
        resolve(PaymentStatus.FAILED, now);
    }

    private void resolve(PaymentStatus finalStatus, Instant now) {
        status = finalStatus;
        resolvedAt = now;
        updatedAt = now;
    }

    private void requireStatus(PaymentStatus required, String action) {
        if (status != required) {
            throw new InvalidPaymentStateException(id, status, action);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getTargetAccountId() {
        return targetAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getExecuteAt() {
        return executeAt;
    }

    public @Nullable Instant getHeldAt() {
        return heldAt;
    }

    public @Nullable Instant getExpiresAt() {
        return expiresAt;
    }

    public @Nullable Instant getResolvedAt() {
        return resolvedAt;
    }

    public @Nullable String getFailureReason() {
        return failureReason;
    }

    public @Nullable String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
