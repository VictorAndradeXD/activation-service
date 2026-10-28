package dev.victorandrade.activation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.victorandrade.activation.application.ActivationRequestNotFoundException;
import dev.victorandrade.activation.application.ActivationRequestService;
import dev.victorandrade.activation.application.CreateActivationCommand;
import dev.victorandrade.activation.application.CreationResult;
import dev.victorandrade.activation.application.DuplicateActivationRequestException;
import dev.victorandrade.activation.domain.ActivationRequest;
import dev.victorandrade.activation.domain.ActivationStatus;
import dev.victorandrade.activation.security.SecurityConfig;

@WebMvcTest(ActivationRequestController.class)
@Import(SecurityConfig.class)
class ActivationRequestControllerTest {

    private static final String VALID_BODY = """
            {"externalId": "EXT-12345", "customerId": "CUST-10001", "productCode": "INTERNET-500"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActivationRequestService service;

    private static JwtRequestPostProcessor client(String... scopes) {
        return jwt().jwt(token -> token.subject("erp").claim("scope", String.join(" ", scopes)));
    }

    private static ActivationRequest sampleRequest() {
        return ActivationRequest.create("EXT-12345", "CUST-10001", "INTERNET-500", "erp", "corr-1",
                Instant.parse("2026-10-26T14:03:11Z"));
    }

    @Nested
    class Create {

        @Test
        void returns201WithLocationForNewRequest() throws Exception {
            var request = sampleRequest();
            given(service.create(any())).willReturn(new CreationResult.Created(request));

            mockMvc.perform(post("/api/v1/requests").with(client("activation:write"))
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/v1/requests/" + request.getId())))
                    .andExpect(jsonPath("$.id").value(request.getId().toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"))
                    .andExpect(jsonPath("$.failureReason").doesNotExist());
        }

        @Test
        void passesTheTokenSubjectAsOwner() throws Exception {
            given(service.create(any())).willReturn(new CreationResult.Created(sampleRequest()));

            mockMvc.perform(post("/api/v1/requests").with(client("activation:write"))
                    .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY));

            var command = ArgumentCaptor.forClass(CreateActivationCommand.class);
            verify(service).create(command.capture());
            assertThat(command.getValue().clientId()).isEqualTo("erp");
            assertThat(command.getValue().correlationId()).isNotBlank();
        }

        @Test
        void returns200WithExistingRequestOnRetry() throws Exception {
            var request = sampleRequest();
            given(service.create(any())).willReturn(new CreationResult.AlreadyExists(request));

            mockMvc.perform(post("/api/v1/requests").with(client("activation:write"))
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/v1/requests/" + request.getId())))
                    .andExpect(jsonPath("$.id").value(request.getId().toString()));
        }

        @Test
        void returns409WhenExternalIdIsReusedWithDifferentData() throws Exception {
            given(service.create(any())).willThrow(new DuplicateActivationRequestException("EXT-12345"));

            mockMvc.perform(post("/api/v1/requests").with(client("activation:write"))
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("/problems/duplicate-request"))
                    .andExpect(jsonPath("$.externalId").value("EXT-12345"));
        }

        @Test
        void returns400ListingEachInvalidField() throws Exception {
            var body = """
                    {"externalId": "", "productCode": "%s"}
                    """.formatted("X".repeat(65));

            mockMvc.perform(post("/api/v1/requests").with(client("activation:write"))
                            .header("X-Correlation-Id", "4bf92f3577b34da6a3ce929d0e0e4736")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Dados inválidos"))
                    .andExpect(jsonPath("$.instance").value("/api/v1/requests"))
                    .andExpect(jsonPath("$.traceId").value("4bf92f3577b34da6a3ce929d0e0e4736"))
                    .andExpect(jsonPath("$.errors", hasSize(3)))
                    .andExpect(jsonPath("$.errors[?(@.field == 'customerId')].message").value("é obrigatório"));
            verifyNoInteractions(service);
        }

        @Test
        void returns400ForMalformedJson() throws Exception {
            mockMvc.perform(post("/api/v1/requests").with(client("activation:write"))
                            .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/problems/malformed-request"))
                    .andExpect(jsonPath("$.detail").value("O corpo da requisição está ausente ou não é um JSON válido."));
        }

        @Test
        void hidesInternalDetailsOnUnexpectedErrors() throws Exception {
            given(service.create(any())).willThrow(new IllegalStateException("connection pool secret details"));

            mockMvc.perform(post("/api/v1/requests").with(client("activation:write"))
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("Ocorreu um erro inesperado. Informe o traceId ao suporte."))
                    .andExpect(jsonPath("$.traceId", notNullValue()))
                    .andExpect(content().string(not(
                            containsString("secret"))));
        }
    }

    @Nested
    class Read {

        @Test
        void returnsExistingRequest() throws Exception {
            var request = sampleRequest();
            given(service.get(request.getId(), "erp")).willReturn(request);

            mockMvc.perform(get("/api/v1/requests/{id}", request.getId()).with(client("activation:read")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.externalId").value("EXT-12345"))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        void returns404ForUnknownRequest() throws Exception {
            var id = UUID.randomUUID();
            given(service.get(id, "erp")).willThrow(new ActivationRequestNotFoundException(id));

            mockMvc.perform(get("/api/v1/requests/{id}", id).with(client("activation:read")))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Solicitação não encontrada"));
        }

        @Test
        void returns400ForMalformedId() throws Exception {
            mockMvc.perform(get("/api/v1/requests/not-a-uuid").with(client("activation:read")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void listsWithFiltersAndPagination() throws Exception {
            var request = sampleRequest();
            given(service.list("erp", ActivationStatus.PENDING, "CUST-10001", 0, 10))
                    .willReturn(new PageImpl<>(List.of(request), PageRequest.of(0, 10), 1));

            mockMvc.perform(get("/api/v1/requests").with(client("activation:read"))
                            .param("status", "PENDING").param("customerId", "CUST-10001")
                            .param("page", "0").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.size").value(10));
        }

        @Test
        void rejectsPageSizeAboveLimit() throws Exception {
            mockMvc.perform(get("/api/v1/requests").with(client("activation:read")).param("size", "500"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(service);
        }
    }

    @Nested
    class Security {

        @Test
        void returns401WithoutToken() throws Exception {
            mockMvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.traceId", notNullValue()));
            verifyNoInteractions(service);
        }

        @Test
        void returns403WhenReadOnlyClientTriesToCreate() throws Exception {
            mockMvc.perform(post("/api/v1/requests").with(client("activation:read"))
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Acesso negado"));
            verifyNoInteractions(service);
        }

        @Test
        void returns403WhenWriteOnlyClientTriesToRead() throws Exception {
            mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(client("activation:write")))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(service);
        }

        @Test
        void queriesAreScopedToTheCallingClient() throws Exception {
            var id = UUID.randomUUID();
            given(service.get(eq(id), eq("crm"))).willThrow(new ActivationRequestNotFoundException(id));

            mockMvc.perform(get("/api/v1/requests/{id}", id)
                            .with(jwt().jwt(t -> t.subject("crm").claim("scope", "activation:read"))))
                    .andExpect(status().isNotFound());
        }
    }
}
