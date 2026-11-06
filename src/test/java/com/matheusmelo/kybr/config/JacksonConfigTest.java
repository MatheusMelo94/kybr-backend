package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** snake_case, ISO-8601 dates, strict input (no duplicate keys, no scalar coercion). */
class JacksonConfigTest {

    record Sample(Instant createdAt, LocalDate dueDate) {
    }

    record Named(String cnpj) {
    }

    record Boxed(Integer count) {
    }

    record Counted(int count) {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    private JsonMapper mapper;

    @BeforeEach
    void loadMapper() {
        runner.run(context -> {
            assertThat(context).as("one JsonMapper, reused by RequestHash").hasSingleBean(JsonMapper.class);
            mapper = context.getBean(JsonMapper.class);
        });
    }

    @Test
    void writesSnakeCaseAndIsoDates() {
        String json = mapper.writeValueAsString(
                new Sample(Instant.parse("2026-01-02T03:04:05Z"), LocalDate.parse("2026-01-02")));

        assertThat(json).isEqualTo("{\"created_at\":\"2026-01-02T03:04:05Z\",\"due_date\":\"2026-01-02\"}");
    }

    @Test
    void readsSnakeCase() {
        Sample sample = mapper.readValue("{\"created_at\":\"2026-01-02T03:04:05Z\",\"due_date\":\"2026-01-02\"}",
                Sample.class);

        assertThat(sample.createdAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
    }

    @Test
    void rejectsUnknownProperties() {
        assertRejected("{\"cnpj\":\"12345678000195\",\"is_admin\":true}", Named.class);
    }

    @Test
    void rejectsDuplicateKeys() {
        assertRejected("{\"cnpj\":\"12345678000195\",\"cnpj\":\"98765432000198\"}", Named.class);
    }

    @Test
    void rejectsTrailingTokens() {
        assertRejected("{} {}", Named.class);
    }

    @Test
    void rejectsNullForAPrimitive() {
        assertRejected("{\"count\":null}", Counted.class);
    }

    @Test
    void rejectsANumberForAString() {
        assertRejected("{\"cnpj\":12345678000195}", Named.class);
    }

    @Test
    void rejectsABooleanForAString() {
        assertRejected("{\"cnpj\":true}", Named.class);
    }

    @Test
    void rejectsAStringForAnInt() {
        assertRejected("{\"count\":\"3\"}", Counted.class);
    }

    @Test
    void rejectsAFloatForAnInt() {
        assertRejected("{\"count\":1.5}", Counted.class);
        assertRejected("{\"count\":1.5}", Boxed.class);
        assertRejected("{\"count\":2.0}", Boxed.class);
    }

    private void assertRejected(String json, Class<?> type) {
        assertThatExceptionOfType(JacksonException.class).isThrownBy(() -> mapper.readValue(json, type));
    }
}
