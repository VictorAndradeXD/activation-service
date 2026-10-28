package dev.victorandrade.activation.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The service is an OAuth 2.0 resource server: consumers obtain a JWT from the authorization server (client
 * credentials flow) and send it as a bearer token. Token validation (signature via JWKS, iss, aud, exp) is configured
 * in application.yml under spring.security.oauth2.resourceserver.jwt.
 */
@Configuration
public class SecurityConfig {

    static final String SCOPE_READ = "SCOPE_activation:read";
    static final String SCOPE_WRITE = "SCOPE_activation:write";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailsSecurityHandler problemHandler)
            throws Exception {
        http
                // Stateless API authenticated by bearer tokens: no session, no cookie, so CSRF does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority(SCOPE_WRITE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/*")
                        .hasAuthority(SCOPE_READ)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> { })
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler));
        return http.build();
    }

    @Bean
    ProblemDetailsSecurityHandler problemDetailsSecurityHandler(ObjectMapper objectMapper) {
        return new ProblemDetailsSecurityHandler(objectMapper);
    }
}
