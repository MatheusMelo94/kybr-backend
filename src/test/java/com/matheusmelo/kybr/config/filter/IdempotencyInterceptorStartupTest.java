package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import com.matheusmelo.kybr.service.idempotency.IdempotencyBindingResolver;
import com.matheusmelo.kybr.service.idempotency.IdempotencyCompletion;
import com.matheusmelo.kybr.service.idempotency.IdempotencyProfile;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService;
import com.matheusmelo.kybr.service.idempotency.Idempotent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Misdeclared {@link Idempotent} handlers fail startup, not their first request: a binding resolver
 * with no bean, or a unique-key handler whose collection has no unique {@code idempotency_key} index.
 */
class IdempotencyInterceptorStartupTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(MvcConfig.class)
            .withBean(IdempotencyService.class, () -> mock(IdempotencyService.class))
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(ErrorResponseWriter.class, () -> new ErrorResponseWriter(JsonMapper.builder().build()))
            .withBean(IdempotencyInterceptor.class);

    @Test
    void startupFailsWhenAnIdempotentHandlersResolverHasNoBean() {
        runner.withBean(ProbeController.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(ProbeResolver.class.getName())
                    .hasMessageContaining(ProbeController.class.getName() + "#create");
        });
    }

    @Test
    void startupSucceedsWhenEveryResolverIsABean() {
        runner.withBean(ProbeController.class).withBean(ProbeResolver.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startupFailsWhenAUniqueKeyHandlerNamesNoCollection() {
        runner.withBean(NoCollectionController.class).withBean(ProbeResolver.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(NoCollectionController.class.getName() + "#create")
                    .hasMessageContaining("uniqueKeyCollection");
        });
    }

    @Test
    void startupFailsWhenTheUniqueKeyCollectionHasNoUniqueKeyIndex() {
        runner.withBean(UniqueKeyController.class).withBean(ProbeResolver.class)
                .withBean("plainIndex", IndexDefinitionProvider.class,
                        () -> provider(new Index().on("idempotency_key", Sort.Direction.ASC)))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining(UniqueKeyController.class.getName() + "#create")
                            .hasMessageContaining("probe_items");
                });
    }

    @Test
    void startupSucceedsWhenTheUniqueKeyCollectionDeclaresTheUniqueIndex() {
        runner.withBean(UniqueKeyController.class).withBean(ProbeResolver.class)
                .withBean("uniqueIndex", IndexDefinitionProvider.class,
                        () -> provider(new Index().on("idempotency_key", Sort.Direction.ASC).unique()))
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static IndexDefinitionProvider provider(IndexDefinition index) {
        return new IndexDefinitionProvider() {
            @Override
            public String collection() {
                return "probe_items";
            }

            @Override
            public List<IndexDefinition> indexes() {
                return List.of(index);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfig {
    }

    @RestController
    static class ProbeController {

        @Idempotent(profile = IdempotencyProfile.PANEL, binding = ProbeResolver.class,
                completion = IdempotencyCompletion.TRANSACTIONAL)
        @PostMapping("/probe")
        void create() {
        }
    }

    @RestController
    static class NoCollectionController {

        @Idempotent(profile = IdempotencyProfile.PANEL, binding = ProbeResolver.class,
                completion = IdempotencyCompletion.UNIQUE_KEY)
        @PostMapping("/probe-no-collection")
        void create() {
        }
    }

    @RestController
    static class UniqueKeyController {

        @Idempotent(profile = IdempotencyProfile.PANEL, binding = ProbeResolver.class,
                completion = IdempotencyCompletion.UNIQUE_KEY, uniqueKeyCollection = "probe_items")
        @PostMapping("/probe-unique")
        void create() {
        }
    }

    static class ProbeResolver implements IdempotencyBindingResolver {

        @Override
        public String resolve(Map<String, String> uriVariables) {
            return "probe";
        }
    }
}
