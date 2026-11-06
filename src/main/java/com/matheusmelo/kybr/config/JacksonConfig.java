package com.matheusmelo.kybr.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.type.LogicalType;

/**
 * Global JSON rules, applied to Boot's single {@code JsonMapper} bean. MVC binds
 * request bodies with it and {@code RequestHash} reuses it, so hashing and binding can never
 * disagree about a body.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer kybrJsonRules() {
        return builder -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Mass-assignment guard: an unknown property is a 422.
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Parser strictness: duplicate keys and trailing tokens are errors.
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                // No scalar coercion: numbers and booleans aren't strings, and vice versa.
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                // A fractional number for an integer is refused, never truncated (1.5 → 1).
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
