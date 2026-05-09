package com.ricsanfre.reviews.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.ricsanfre.reviews.client.OrderServiceClient;
import com.ricsanfre.reviews.client.ProductServiceClient;
import com.ricsanfre.reviews.client.UserServiceClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.support.OAuth2RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client configuration for service-to-service calls.
 *
 * <p>Registers {@link UserServiceClient}, {@link ProductServiceClient}, and
 * {@link OrderServiceClient} via {@link ImportHttpServices}. OAuth2 Client Credentials
 * tokens are attached automatically. Base URLs configured via
 * {@code spring.http.serviceclient.<group>.base-url} in {@code application.yaml}.
 *
 * <p>Uses {@link AuthorizedClientServiceOAuth2AuthorizedClientManager} (not the default
 * {@code DefaultOAuth2AuthorizedClientManager}) to support token acquisition on Resilience4j
 * background threads where no {@code HttpServletRequest} is present.
 */
@Configuration
@EnableCaching
@ImportHttpServices(group = "user-service", types = UserServiceClient.class)
@ImportHttpServices(group = "product-service", types = ProductServiceClient.class)
@ImportHttpServices(group = "order-service", types = OrderServiceClient.class)
public class HttpClientConfig {

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {
        return new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);
    }

    @Bean
    OAuth2RestClientHttpServiceGroupConfigurer oauth2Configurer(
            OAuth2AuthorizedClientManager authorizedClientManager) {
        return OAuth2RestClientHttpServiceGroupConfigurer.from(authorizedClientManager);
    }

    @Bean
    CacheManager cacheManager(
            MeterRegistry meterRegistry,
            @Value("${reviews.user-resolver.cache-ttl-minutes:10}") long ttlMinutes) {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                Caffeine.newBuilder()
                        .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                        .recordStats()
                        .build();
        CaffeineCacheMetrics.monitor(meterRegistry, nativeCache, "user.id.resolution");
        CaffeineCache springCache = new CaffeineCache("userIdBySubject", nativeCache);
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(springCache));
        return manager;
    }
}
