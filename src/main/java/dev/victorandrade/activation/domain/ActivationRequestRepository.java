package dev.victorandrade.activation.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ActivationRequestRepository
        extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

    Optional<ActivationRequest> findByExternalId(String externalId);

    Optional<ActivationRequest> findByIdAndClientId(UUID id, String clientId);

    long countByStatus(ActivationStatus status);

    /**
     * Locks the next request due for a provider call. {@code SKIP LOCKED} lets several instances poll the same table
     * without blocking each other or claiming the same row.
     */
    @Query(value = """
            SELECT * FROM activation_request
            WHERE status IN ('PENDING', 'PROCESSING') AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ActivationRequest> lockNextDue(Instant now);
}
