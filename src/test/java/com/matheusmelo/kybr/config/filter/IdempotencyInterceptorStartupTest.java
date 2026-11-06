package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.matheusmelo.kybr.service.idempotency.IdempotencyBindingResolver;
import com.matheusmelo.kybr.service.idempotency.IdempotencyProfile;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService;
import com.matheusmelo.kybr.service.idempotency.Idempotent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

/** An {@link Idempotent} handler whose binding resolver has no bean fails startup, not its first request. */
class IdempotencyInterceptorStartupTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(MvcConfig.class)
            .withBean(IdempotencyService.class, () -> mock(IdempotencyService.class))
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(IdempotencyInterceptor.class)
            .withBean(ProbeController.class);

    @Test
    void startupFailsWhenAnIdempotentHandlersResolverHasNoBean() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(ProbeResolver.class.getName())
                    .hasMessageContaining(ProbeController.class.getName() + "#create");
        });
    }

    @Test
    void startupSucceedsWhenEveryResolverIsABean() {
        runner.withBean(ProbeResolver.class).run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfig {
    }

    @RestController
    static class ProbeController {

        @Idempotent(profile = IdempotencyProfile.PANEL, binding = ProbeResolver.class)
        @PostMapping("/probe")
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
