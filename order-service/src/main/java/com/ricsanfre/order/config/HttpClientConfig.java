package com.ricsanfre.order.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.ricsanfre.order.client.UserServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.concurrent.TimeUnit;

/**
 * HTTP client configuration for service-to-service calls.
 *
 * <p>Configures a {@link UserServiceClient} RestClient bean that attaches a Client Credentials
 * OAuth2 token (via {@link OAuth2ClientHttpRequestInterceptor}) on every outbound request to
 * {@code user-service}.
 *
 * <p>Also enables and configures the Caffeine-backed {@link CacheManager} used by
 * {@link com.ricsanfre.order.service.UserIdResolverService} to cache sub → userId mappings.
 */
@Configuration
@EnableCaching
public class HttpClientConfig {

    @Bean
    public UserServiceClient userServiceClient(
            RestClient.Builder builder,
            OAuth2AuthorizedClientManager authorizedClientManager,
            @Value("${services.user-service.url:http://localhost:8085}") String userServiceUrl) {

        RestClient restClient = builder
                .baseUrl(userServiceUrl)
                .requestInterceptor(new OAuth2ClientHttpRequestInterceptor(authorizedClientManager))
                .build();

        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build();

        return factory.createClient(UserServiceClient.class);
    }

    @Bean
    public CacheManager cacheManager(
            @Value("${order.user-resolver.cache-ttl-minutes:10}") long ttlMinutes) {
        CaffeineCacheManager manager = new CaffeineCacheManager("userIdBySubject");
        manager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(ttlMinutes, TimeUnit.MINUTES));
        return manager;
    }
}
