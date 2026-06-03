package com.ricsanfre.product.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.observability.ContextProviderFactory;
import org.springframework.data.mongodb.observability.MongoObservationCommandListener;

/**
 * Registers a {@link MongoObservationCommandListener} as a {@link MongoClientSettingsBuilderCustomizer}
 * so that every MongoDB command is recorded as a child span inside the active trace.
 *
 * <p>Spring Boot 4.0.x does not ship a {@code MongoObservationAutoConfiguration} — the
 * {@code management.observations.mongodb.enabled} property is unrecognised and has no effect.
 * This class fills that gap by wiring the listener manually, following the same pattern as
 * {@code MongoMetricsAutoConfiguration}.
 *
 * <p>Declared as {@code @AutoConfiguration} (not {@code @Configuration}) so that
 * {@code @WebMvcTest} slices never load it — slice tests only activate their own curated
 * auto-configuration list and do not have a {@code MongoClient} in context.
 */
@AutoConfiguration
@AutoConfigureBefore(MongoAutoConfiguration.class)
public class MongoObservationConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer mongoObservationCommandListenerCustomizer(
            ObservationRegistry observationRegistry) {
        return clientSettingsBuilder -> clientSettingsBuilder
                .contextProvider(ContextProviderFactory.create(observationRegistry))
                .addCommandListener(new MongoObservationCommandListener(observationRegistry));
    }
}
