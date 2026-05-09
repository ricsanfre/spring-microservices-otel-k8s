package com.ricsanfre.reviews.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.ricsanfre.reviews.client.OrderServiceClient;
import com.ricsanfre.reviews.client.ProductServiceClient;
import com.ricsanfre.reviews.client.UserServiceClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.support.OAuth2RestClientHttpServiceGroupConfigurer;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

import java.util.List;
import java.util.concurrent.Executors;
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

    /**
     * Injects the Micrometer {@link ObservationRegistry} into every group's {@link org.springframework.web.client.RestClient}.
     * Without this, outbound calls are plain HTTP with no {@code traceparent} header,
     * causing downstream services to start a new trace instead of continuing the caller's trace.
     */
    @Bean
    RestClientHttpServiceGroupConfigurer observationGroupConfigurer(ObservationRegistry observationRegistry) {
        return groups -> groups.forEachClient(
                (group, builder) -> builder.observationRegistry(observationRegistry));
    }

    /**
     * Wraps the {@link Resilience4JCircuitBreakerFactory}'s internal executor with
     * {@link ContextExecutorService} so that Micrometer context (OTel trace/span) is
     * captured on the calling Tomcat thread at task submission and restored inside the
     * circuit-breaker pool thread before the HTTP Interface method executes.
     *
     * <p>Spring Cloud's {@code CircuitBreakerAdapterDecorator} wraps every
     * {@code @ImportHttpServices} call via {@code executorService.submit(FutureTask)}.
     * Without this, pool threads carry no OTel context and outbound requests have no
     * {@code traceparent} header — downstream services start a new disconnected trace.
     */
    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> contextPropagatingExecutorCustomizer() {
        return factory -> factory.configureExecutorService(
                ContextExecutorService.wrap(Executors.newCachedThreadPool(), ContextSnapshotFactory.builder().build()));
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
