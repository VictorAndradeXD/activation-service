package dev.victorandrade.activation.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.victorandrade.activation.api.dto.ActivationRequestResponse;
import dev.victorandrade.activation.api.dto.CreateActivationRequest;
import dev.victorandrade.activation.api.dto.PageResponse;
import dev.victorandrade.activation.application.ActivationRequestService;
import dev.victorandrade.activation.application.CreateActivationCommand;
import dev.victorandrade.activation.application.CreationResult;
import dev.victorandrade.activation.domain.ActivationRequest;
import dev.victorandrade.activation.domain.ActivationStatus;
import dev.victorandrade.activation.observability.CorrelationId;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    /**
     * 201 when the request is created; 200 with the existing request when the same submission is retried (the
     * source system gets the same answer either way and can safely retry).
     */
    @PostMapping
    public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest body,
                                                            @AuthenticationPrincipal Jwt jwt) {
        var command = new CreateActivationCommand(body.externalId(), body.customerId(), body.productCode(),
                clientId(jwt), CorrelationId.current());
        return switch (service.create(command)) {
            case CreationResult.Created(var request) ->
                    ResponseEntity.created(location(request)).body(ActivationRequestResponse.from(request));
            case CreationResult.AlreadyExists(var request) ->
                    ResponseEntity.ok().location(location(request)).body(ActivationRequestResponse.from(request));
        };
    }

    @GetMapping("/{id}")
    public ActivationRequestResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ActivationRequestResponse.from(service.get(id, clientId(jwt)));
    }

    @GetMapping
    public PageResponse<ActivationRequestResponse> list(
            @RequestParam(required = false) ActivationStatus status,
            @RequestParam(required = false) @Size(max = 64) String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal Jwt jwt) {
        var result = service.list(clientId(jwt), status, customerId, page, size);
        return PageResponse.from(result, ActivationRequestResponse::from);
    }

    /** In the client_credentials flow the token subject identifies the calling system. */
    private static String clientId(Jwt jwt) {
        return jwt.getSubject();
    }

    private static URI location(ActivationRequest request) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/requests/{id}")
                .buildAndExpand(request.getId())
                .toUri();
    }
}
