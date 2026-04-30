package com.ricsanfre.product.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/**
 * Registers MongoDB auditing support after the MongoMappingContext is available.
 *
 * <p>Placed as an auto-configuration so that slice tests (e.g. {@code @WebMvcTest}) do NOT
 * load it — slice tests only include their own curated auto-configuration list and will
 * therefore never try to create {@code mongoAuditingHandler}, which requires
 * {@code mongoMappingContext} that is absent in a web-layer slice context.
 */
@AutoConfiguration
@ConditionalOnBean(MongoMappingContext.class)
@EnableMongoAuditing
public class MongoAuditingConfig {
}
