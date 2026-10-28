package dev.victorandrade.activation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * An activation request received from a source system.
 * <p>
 * Status changes only through the domain methods below, which enforce the transitions allowed by
 * {@link ActivationStatus}. There are no public setters.
 */
@Entity
@Table(name = "activation_request")
public class ActivationRequest {

    static final int MAX_ERROR_LENGTH = 500;

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String externalId;

    @Column(nullable = false, updatable = false)
    private String customerId;

    @Column(nullable = false, updatable = false)
    private String productCode;

    /** Consumer system (JWT subject) that created the request; used to isolate data between consumers. */
    @Column(nullable = false, updatable = false)
    private String clientId;

    /** Correlation id of the HTTP request that created it, propagated to the provider call. */
    @Column(updatable = false)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivationStatus status;

    @Column(nullable = false)
    private int attempts;

    /** When the worker may (re)claim this request. Null once the request reaches a final status. */
    private Instant nextAttemptAt;

    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected ActivationRequest() {
        // for JPA
    }

    public static ActivationRequest create(String externalId, String customerId, String productCode,
                                           String clientId, String correlationId, Instant now) {
        var request = new ActivationRequest();
        request.id = UUID.randomUUID();
        request.externalId = Objects.requireNonNull(externalId);
        request.customerId = Objects.requireNonNull(customerId);
        request.productCode = Objects.requireNonNull(productCode);
        request.clientId = Objects.requireNonNull(clientId);
        request.correlationId = correlationId;
        request.status = ActivationStatus.PENDING;
        request.nextAttemptAt = now;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    /**
     * Claims the request for a call to the provider. The lease keeps other workers away while the call is in flight;
     * if this instance dies, the lease expires and the request is picked up again.
     */
    public void startAttempt(Instant now, Duration lease) {
        transitionTo(ActivationStatus.PROCESSING, now);
        attempts++;
        nextAttemptAt = now.plus(lease);
    }

    public void complete(Instant now) {
        transitionTo(ActivationStatus.COMPLETED, now);
        nextAttemptAt = null;
        lastError = null;
    }

    public void fail(String reason, Instant now) {
        transitionTo(ActivationStatus.FAILED, now);
        nextAttemptAt = null;
        lastError = truncate(reason);
    }

    /**
     * A temporary failure keeps the request in PROCESSING (the provider may already have acted on it, so it is not
     * "pending" anymore) and schedules the next attempt.
     */
    public void scheduleRetry(Instant retryAt, String reason, Instant now) {
        if (status != ActivationStatus.PROCESSING) {
            throw new InvalidStatusTransitionException(id, status, ActivationStatus.PROCESSING);
        }
        transitionTo(ActivationStatus.PROCESSING, now);
        nextAttemptAt = retryAt;
        lastError = truncate(reason);
    }

    /** Whether a repeated submission carries the same data, i.e. it is a legitimate retry from the source system. */
    public boolean isSameSubmission(String customerId, String productCode, String clientId) {
        return this.customerId.equals(customerId)
                && this.productCode.equals(productCode)
                && this.clientId.equals(clientId);
    }

    private void transitionTo(ActivationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(id, status, target);
        }
        status = target;
        updatedAt = now;
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= MAX_ERROR_LENGTH ? reason : reason.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getClientId() {
        return clientId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public ActivationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
