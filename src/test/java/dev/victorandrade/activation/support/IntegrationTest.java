package dev.victorandrade.activation.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;

import dev.victorandrade.activation.domain.ActivationRequestRepository;

/**
 * Full application against a real PostgreSQL (Testcontainers) and a fake provider (WireMock). Both are started once
 * and shared by every integration test class, so the Spring context is cached between classes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTest.ClockConfig.class)
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    protected static final WireMockServer PROVIDER = new WireMockServer(options().dynamicPort());

    static {
        POSTGRES.start();
        PROVIDER.start();
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.provider.base-url", PROVIDER::baseUrl);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ActivationRequestRepository repository;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        PROVIDER.resetAll();
        repository.deleteAll();
        clock.reset();
    }

    protected static JwtRequestPostProcessor client(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("scope", "activation:read activation:write"));
    }

    protected static MockHttpServletRequestBuilder createRequest(String subject, String externalId,
                                                                 String customerId, String productCode) {
        return post("/api/v1/requests")
                .with(client(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalId": "%s", "customerId": "%s", "productCode": "%s"}
                        """.formatted(externalId, customerId, productCode));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
