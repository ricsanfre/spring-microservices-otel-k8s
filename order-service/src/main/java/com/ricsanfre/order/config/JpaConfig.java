package com.ricsanfre.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables JPA auditing ({@code @CreatedDate}, {@code @LastModifiedDate}).
 *
 * <p>Kept in a separate {@code @Configuration} class (not on the main application class)
 * so that {@code @WebMvcTest} slices do not trigger JPA metamodel initialisation
 * when there is no real JPA context.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
