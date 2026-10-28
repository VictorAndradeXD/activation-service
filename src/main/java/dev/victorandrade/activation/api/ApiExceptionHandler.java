package dev.victorandrade.activation.api;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import dev.victorandrade.activation.application.ActivationRequestNotFoundException;
import dev.victorandrade.activation.application.DuplicateActivationRequestException;
import dev.victorandrade.activation.observability.CorrelationId;

/**
 * Maps every error to RFC 9457 Problem Details with the correlation id, so a consumer can report it and we can find
 * it in the logs. Internal details (stack traces, SQL, class names) never reach the response.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static final URI VALIDATION_ERROR = URI.create("/problems/validation-error");
    static final URI MALFORMED_REQUEST = URI.create("/problems/malformed-request");
    static final URI NOT_FOUND = URI.create("/problems/not-found");
    static final URI DUPLICATE_REQUEST = URI.create("/problems/duplicate-request");
    static final URI SERVICE_UNAVAILABLE = URI.create("/problems/service-unavailable");
    static final URI INTERNAL_ERROR = URI.create("/problems/internal-error");

    @ExceptionHandler(ActivationRequestNotFoundException.class)
    ProblemDetail handleNotFound(ActivationRequestNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, NOT_FOUND, "Solicitação não encontrada",
                "Não existe solicitação com o id informado.");
    }

    @ExceptionHandler(DuplicateActivationRequestException.class)
    ProblemDetail handleDuplicate(DuplicateActivationRequestException ex) {
        var problem = problem(HttpStatus.CONFLICT, DUPLICATE_REQUEST, "Solicitação duplicada",
                "O externalId informado já foi utilizado em uma solicitação com dados diferentes.");
        problem.setProperty("externalId", ex.getExternalId());
        return problem;
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ProblemDetail handleDatabaseUnavailable(Exception ex) {
        log.error("Database unavailable", ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE, "Serviço indisponível",
                "O serviço está temporariamente indisponível. Tente novamente em instantes.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, "Erro interno",
                "Ocorreu um erro inesperado. Informe o traceId ao suporte.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        var problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, "Dados inválidos",
                "Um ou mais campos não passaram na validação.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        var problem = problem(HttpStatus.BAD_REQUEST, MALFORMED_REQUEST, "Requisição malformada",
                "O corpo da requisição está ausente ou não é um JSON válido.");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Every problem built by this handler or by Spring's defaults passes through here. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            withTraceId(problem);
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return withTraceId(problem);
    }

    private static ProblemDetail withTraceId(ProblemDetail problem) {
        var traceId = CorrelationId.current();
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }
}
