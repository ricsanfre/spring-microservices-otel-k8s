package com.ricsanfre.reviews.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/**
 * Registers MongoDB auditing support after the MongoMappingContext is available.
 * Declared as auto-configuration so that slice tests (@WebMvcTest) do NOT load it.
 * @AutoConfigureAfter ensures DataMongoAutoConfiguration (which creates MongoMappingContext)
 * is processed first, so the @ConditionalOnBean check succeeds.
 */
@AutoConfiguration
@AutoConfigureAfter(DataMongoAutoConfiguration.class)
@ConditionalOnBean(MongoMappingContext.class)
@EnableMongoAuditing
public class MongoAuditingConfig {
}
