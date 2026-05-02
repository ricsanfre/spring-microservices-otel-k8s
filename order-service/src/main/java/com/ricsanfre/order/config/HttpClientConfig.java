package com.ricsanfre.order.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.ricsanfre.order.client.ProductServiceClient;
import com.ricsanfre.order.client.UserServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.support.OAuth2RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

import java.util.concurrent.TimeUnit;

/**
 * HTTP client configuration for service-to-service calls.
 *
 * <p>Registers {@link UserServiceClient} and {@link ProductServiceClient} as Spring beans via
 * {@link ImportHttpServices}. Base URLs are configured via
 * {@code spring.http.serviceclient.<group>.base-url} in {@code application.yaml}.
 * OAuth2 Client Credentials tokens are attached automatically by
 * {@link OAuth2RestClientHttpServiceGroupConfigurer}, which processes the
 * {@code @ClientRegistrationId} annotation on each interface.
 *
 * <p>Also enables and configures the Caffeine-backed {@link CacheManager} used by
 * {@link com.ricsanfre.order.service.UserIdResolverService} to cache sub → userId mappings.
 */
@Configuration
@EnableCaching
@ImportHttpServices(group = "user-service", types = UserServiceClient.class)
@ImportHttpServices(group = "product-service", types = ProductServiceClient.class)
public class HttpClientConfig {

    @Bean
    OAuth2RestClientHttpServiceGroupConfigurer oauth2Configurer(
            OAuth2AuthorizedClientManager authorizedClientManager) {
        return OAuth2RestClientHttpServiceGroupConfigurer.from(authorizedClientManager);
    }

    @Bean
    CacheManager cacheManager(
            @Value("${order.user-resolver.cache-ttl-minutes:10}") long ttlMinutes) {
        CaffeineCacheManager manager = new CaffeineCacheManager("userIdBySubject");
        manager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(ttlMinutes, TimeUnit.MINUTES));
        return manager;
    }
}
