package dev.victorandrade.activation.integration.provider;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
class ProviderClientConfig {

    /**
     * Built from Spring Boot's RestClient.Builder so calls are observed (http.client.requests metrics). Explicit
     * timeouts: without them a hung provider would hold a worker thread forever.
     */
    @Bean
    RestClient providerRestClient(RestClient.Builder builder, ProviderProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        builder.baseUrl(properties.baseUrl().toString()).requestFactory(requestFactory);
        if (StringUtils.hasText(properties.apiKey())) {
            builder.defaultHeader("X-Api-Key", properties.apiKey());
        }
        return builder.build();
    }
}
