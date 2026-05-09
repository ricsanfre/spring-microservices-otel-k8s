package com.ricsanfre.cart.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.ricsanfre.cart.client.OrderServiceClient;
import com.ricsanfre.cart.client.UserServiceClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
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
 * <p>Registers {@link UserServiceClient} and {@link OrderServiceClient} as Spring beans via
 * {@link ImportHttpServices}. Base URLs are configured via
 * {@code spring.http.serviceclient.<group>.base-url} in {@code application.yaml}.
 * OAuth2 Client Credentials tokens are attached automatically by
 * {@link OAuth2RestClientHttpServiceGroupConfigurer}, which processes the
 * {@code @ClientRegistrationId} annotation on each interface.
 *
 * <p>Also enables and configures the Caffeine-backed {@link CacheManager} used by
 * {@link com.ricsanfre.cart.service.UserIdResolverService} to cache sub → userId mappings.
 */
@Configuration
@EnableCaching
@ImportHttpServices(group = "user-service", types = UserServiceClient.class)
@ImportHttpServices(group = "order-service", types = OrderServiceClient.class)
public class HttpClientConfig {

    /**
     * Use AuthorizedClientServiceOAuth2AuthorizedClientManager instead of the default
     * DefaultOAuth2AuthorizedClientManager. The default implementation requires an
     * HttpServletRequest in the current thread, which is not available when Resilience4J
     * executes the HTTP call inside a FutureTask on its own thread pool.
     * AuthorizedClientServiceOAuth2AuthorizedClientManager works without a servlet request
     * and is the correct choice for client_credentials flows in background threads.
     */
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
     * Injects the Micrometer {@link ObservationRegistry} into every group's
     * {@link org.springframework.web.client.RestClient} so that outgoing HTTP calls
     * create child spans and propagate the {@code traceparent} header.
     */
    @Bean
    RestClientHttpServiceGroupConfigurer observationGroupConfigurer(ObservationRegistry observationRegistry) {
        return groups -> groups.forEachClient((group, builder) ->
                builder.observationRegistry(observationRegistry));
    }

    /**
     * Wraps the {@link Resilience4JCircuitBreakerFactory}'s internal executor with
     * {@link ContextExecutorService} so that Micrometer's thread-local context (OTel
     * trace/span) is captured on the calling Tomcat thread and restored inside the
     * circuit-breaker pool thread before the HTTP Interface method executes.
     *
     * <p>Spring Cloud's {@code CircuitBreakerAdapterDecorator} wraps every
     * {@code @ImportHttpServices} HTTP Interface call via
     * {@code Resilience4JCircuitBreakerFactory} → {@code executorService.submit(FutureTask)}.
     * Task submission happens on the Tomcat thread ({@code nio-NNNN-exec-N}), which carries
     * the active OTel trace context. {@link ContextExecutorService#wrap} captures the context
     * at submission time and restores it in the pool thread, so {@code RestClient} finds
     * the parent span and injects the correct {@code traceparent} header.
     *
     * <p>Note: this approach works because submission is from the Tomcat thread (which HAS
     * context). It differs from the earlier failed attempt with {@code JdkClientHttpRequestFactory},
     * where {@code HttpClient.sendAsync()} submitted completions from the JDK NIO event loop
     * thread (no context).
     */
    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> contextPropagatingExecutorCustomizer() {
        return factory -> factory.configureExecutorService(
                ContextExecutorService.wrap(Executors.newCachedThreadPool(), ContextSnapshotFactory.builder().build()));
    }

    @Bean
    CacheManager cacheManager(
            MeterRegistry meterRegistry,
            @Value("${cart.user-resolver.cache-ttl-minutes:10}") long ttlMinutes) {
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
