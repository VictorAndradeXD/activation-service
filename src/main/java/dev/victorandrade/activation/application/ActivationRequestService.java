package dev.victorandrade.activation.application;

import static dev.victorandrade.activation.domain.ActivationRequestSpecifications.hasCustomerId;
import static dev.victorandrade.activation.domain.ActivationRequestSpecifications.hasStatus;
import static dev.victorandrade.activation.domain.ActivationRequestSpecifications.ownedBy;

import java.time.Clock;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import dev.victorandrade.activation.domain.ActivationRequest;
import dev.victorandrade.activation.domain.ActivationRequestRepository;
import dev.victorandrade.activation.domain.ActivationStatus;

@Service
public class ActivationRequestService {

    private final ActivationRequestRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ActivationRequestService(ActivationRequestRepository repository, TransactionTemplate transactionTemplate,
                                    Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * Creates a request, or returns the existing one when the same submission arrives again.
     * <p>
     * The lookup is only a fast path for ordinary retries. Two concurrent submissions can both miss it, so the unique
     * constraint on {@code external_id} is what actually prevents duplicates: the losing insert fails and is resolved
     * against the row that won.
     */
    public CreationResult create(CreateActivationCommand command) {
        return repository.findByExternalId(command.externalId())
                .<CreationResult>map(existing -> replay(existing, command))
                .orElseGet(() -> insert(command));
    }

    private CreationResult insert(CreateActivationCommand command) {
        var request = ActivationRequest.create(command.externalId(), command.customerId(), command.productCode(),
                command.clientId(), command.correlationId(), clock.instant());
        try {
            transactionTemplate.executeWithoutResult(status -> repository.saveAndFlush(request));
            return new CreationResult.Created(request);
        } catch (DataIntegrityViolationException e) {
            var winner = repository.findByExternalId(command.externalId()).orElseThrow(() -> e);
            return replay(winner, command);
        }
    }

    private CreationResult replay(ActivationRequest existing, CreateActivationCommand command) {
        if (!existing.isSameSubmission(command.customerId(), command.productCode(), command.clientId())) {
            throw new DuplicateActivationRequestException(command.externalId());
        }
        return new CreationResult.AlreadyExists(existing);
    }

    @Transactional(readOnly = true)
    public ActivationRequest get(UUID id, String clientId) {
        return repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new ActivationRequestNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ActivationRequest> list(String clientId, ActivationStatus status, String customerId, int page,
                                       int size) {
        var spec = ownedBy(clientId).and(hasStatus(status)).and(hasCustomerId(customerId));
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return repository.findAll(spec, pageable);
    }
}
