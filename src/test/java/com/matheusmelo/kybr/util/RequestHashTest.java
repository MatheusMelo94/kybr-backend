package com.matheusmelo.kybr.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.config.JacksonConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** The request hash, with the application's own (strict) JsonMapper. */
class RequestHashTest {

    record VerifyBody(String cnpj, String name) {
    }

    private static final String ROUTE = "/public/v/{slug}/verify";

    private JsonMapper mapper;

    @BeforeEach
    void loadApplicationMapper() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> mapper = context.getBean(JsonMapper.class));
    }

    @Test
    void keyOrderAndWhitespaceDontChangeTheHash() {
        String a = hash("POST", ROUTE, "slug-a", "{\"cnpj\":\"12345678000195\",\"name\":\"X\",\"n\":{\"b\":1,\"a\":[2,1]}}");
        String b = hash("POST", ROUTE, "slug-a",
                "{ \"n\" : { \"a\" : [ 2, 1 ], \"b\" : 1 },\n  \"name\":\"X\",\t\"cnpj\": \"12345678000195\" }");

        assertThat(a).isEqualTo(b).matches("^[0-9a-f]{64}$");
    }

    @Test
    void aChangedValueRouteBindingOrMethodChangesTheHash() {
        String base = hash("POST", ROUTE, "slug-a", "{\"cnpj\":\"12345678000195\"}");

        assertThat(hash("POST", ROUTE, "slug-a", "{\"cnpj\":\"98765432000198\"}")).isNotEqualTo(base);
        assertThat(hash("POST", "/public/v/{slug}/other", "slug-a", "{\"cnpj\":\"12345678000195\"}")).isNotEqualTo(base);
        assertThat(hash("POST", ROUTE, "slug-b", "{\"cnpj\":\"12345678000195\"}")).isNotEqualTo(base);
        assertThat(hash("PUT", ROUTE, "slug-a", "{\"cnpj\":\"12345678000195\"}")).isNotEqualTo(base);
    }

    @Test
    void arrayOrderAndValueTypesAreSignificant() {
        assertThat(hash("POST", ROUTE, "s", "{\"a\":[1,2]}")).isNotEqualTo(hash("POST", ROUTE, "s", "{\"a\":[2,1]}"));
        assertThat(hash("POST", ROUTE, "s", "{\"a\":1}")).isNotEqualTo(hash("POST", ROUTE, "s", "{\"a\":\"1\"}"));
    }

    @Test
    void anEmptyBodyHashesTheEmptyString() throws Exception {
        String expected = sha256Hex("POST|" + ROUTE + "|s|");

        assertThat(RequestHash.of(mapper, "POST", ROUTE, "s", new byte[0])).isEqualTo(expected);
        assertThat(RequestHash.of(mapper, "POST", ROUTE, "s", null)).isEqualTo(expected);
    }

    @Test
    void theHashIsOverMethodRouteBindingAndCanonicalJson() throws Exception {
        assertThat(hash("POST", ROUTE, "s", "{ \"b\": 2, \"a\": 1 }"))
                .isEqualTo(sha256Hex("POST|" + ROUTE + "|s|{\"a\":1,\"b\":2}"));
    }

    @Test
    void invalidJsonIsHashedOverItsRawBytes() throws Exception {
        String raw = "{\"cnpj\": \"12345678000195\"";

        assertThat(hash("POST", ROUTE, "s", raw)).isEqualTo(sha256Hex("POST|" + ROUTE + "|s|" + raw));
        assertThat(hash("POST", ROUTE, "s", raw)).isNotEqualTo(hash("POST", ROUTE, "s", raw + " "));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "{\"cnpj\":\"12345678000195\",\"cnpj\":\"98765432000198\"}", // duplicate key
        "{\"cnpj\":\"12345678000195\"} {}", // trailing tokens
        "{\"cnpj\":"})
    void hashingAndBindingAgreeOnRejectingABody(String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        assertThat(RequestHash.canonicalJson(mapper, bytes)).isEmpty();
        assertThatThrownBy(() -> mapper.readValue(bytes, VerifyBody.class)).isInstanceOf(JacksonException.class);
        assertThat(RequestHash.of(mapper, "POST", ROUTE, "s", bytes)).isEqualTo(sha256Hex("POST|" + ROUTE + "|s|" + body));
    }

    @Test
    void hashingAndBindingAgreeOnAcceptingABody() {
        byte[] bytes = "{\"name\":\"X\",\"cnpj\":\"12345678000195\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(RequestHash.canonicalJson(mapper, bytes)).contains("{\"cnpj\":\"12345678000195\",\"name\":\"X\"}");
        assertThat(mapper.readValue(bytes, VerifyBody.class)).isEqualTo(new VerifyBody("12345678000195", "X"));
    }

    private String hash(String method, String route, String binding, String body) {
        return RequestHash.of(mapper, method, route, binding, body.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
