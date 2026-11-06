package com.matheusmelo.kybr.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.matheusmelo.kybr.config.KybrProperties.Hashing;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.support.TestSecrets;
import com.matheusmelo.kybr.support.TestTokens;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Secrets, case tokens and PII never reach log text. Every value is synthetic. */
class LogRedactorTest {

    /** A synthetic case token shaped like the real ones ({@code [A-Za-z0-9_-]{22,64}}). */
    private static final String TOKEN = TestTokens.caseToken();

    private static final long LINEARITY_SLACK_NANOS = Duration.ofMillis(20).toNanos();

    private final KeyHasher hasher = new KeyHasher(new Hashing(TestSecrets.HASH_SECRET, null));
    private final LogRedactor redactor = new LogRedactor(List.of("cases"),
            List.of(TestSecrets.BFF_KEY_CURRENT, TestSecrets.BFF_KEY_PREVIOUS), hasher::hmac);

    private String tok() {
        return "tok:" + hasher.hmac("casetoken:" + TOKEN).substring(0, 12);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization: Bearer abc.DEF-123_xyz", "authorization: bearer abc.DEF-123_xyz",
        "header BEARER abc.DEF-123_xyz end"})
    void redactsBearerTokens(String input) {
        assertThat(redactor.redact(input)).contains("Bearer [REDACTED]").doesNotContain("abc.DEF-123_xyz");
    }

    @Test
    void redactsTheConfiguredKeyLiteralsAnywhere() {
        String input = "value=" + TestSecrets.BFF_KEY_CURRENT + " and \"" + TestSecrets.BFF_KEY_PREVIOUS + "\"";

        assertThat(redactor.redact(input)).doesNotContain(TestSecrets.BFF_KEY_CURRENT)
                .doesNotContain(TestSecrets.BFF_KEY_PREVIOUS).contains("[REDACTED]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Kybr-BFF-Key: some-presented-value", "x-kybr-bff-key=some-presented-value",
        "X-KYBR-BFF-KEY:some-presented-value"})
    void redactsTheBffHeaderForm(String input) {
        assertThat(redactor.redact(input)).isEqualTo("X-Kybr-BFF-Key: [REDACTED]");
    }

    @Test
    void redactsQueryValuesOnUrls() {
        String out = redactor.redact("GET https://storage.example/obj?signature=c2lnbmF0dXJl&expires=1700000000 failed");

        assertThat(out).isEqualTo("GET https://storage.example/obj?signature=[REDACTED]&expires=[REDACTED] failed");
    }

    @Test
    void redactsAQueryCnpjValue() {
        assertThat(redactor.redact("/public/v/s/lookup?cnpj=12345678000195")).isEqualTo("/public/v/s/lookup?cnpj=[REDACTED]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"token=abc123", "sig=abc123", "signature=abc123", "session=abc123", "Idempotency-Key=abc123",
        "Idempotency-Key: abc123", "\"token\":\"abc123\"", "TOKEN = abc123"})
    void redactsSensitiveKeyValuePairs(String input) {
        assertThat(redactor.redact(input)).doesNotContain("abc123").contains("[REDACTED]");
    }

    static Stream<String> caseTokenPaths() {
        return Stream.of(
                "/public/v/s/cases/" + TOKEN, // the case page
                "/public/v/s/cases/" + TOKEN + "/contact", // the case contact form
                "/public/v/s/cases/" + TOKEN + "/documents", // the seller document upload
                "/public/v/s//cases//" + TOKEN + "/contact",
                "/public/v/s/cases%2F" + TOKEN + "/documents",
                "/public/v/s/cases%2f" + TOKEN,
                "/public/v/s/cases/" + TOKEN + "/",
                "/public/v/s/cases/" + TOKEN + "/x/y",
                "/public/v/s/cases/" + TOKEN + ";p=1",
                "/public/v/s/cases/" + TOKEN + "?q=1",
                "/public/v/s/cases/" + TOKEN + "/contact;p=1?q=1",
                "path=/public/v/s/cases/" + TOKEN + " status=404",
                "GET \"/public/v/s/cases/" + TOKEN + "/documents\" 403");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("caseTokenPaths")
    void hashesTheCaseTokenInEveryPathVariant(String path) {
        String out = redactor.redact(path);

        assertThat(out).doesNotContain(TOKEN).contains(tok());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("caseTokenPaths")
    void isIdempotentOverItsOwnOutput(String path) {
        String once = redactor.redact(path);

        assertThat(redactor.redact(once)).isEqualTo(once);
    }

    @Test
    void theTokenHashIsStablePerTokenAndKeyed() throws Exception {
        String first = redactor.redact("/public/v/s/cases/" + TOKEN);
        String again = redactor.redact("/public/v/other/cases/" + TOKEN + "/contact");
        String other = redactor.redact("/public/v/s/cases/Ab1Cd2Ef3Gh4Ij5Kl6Mn7Op8");
        LogRedactor otherSecret = new LogRedactor(List.of("cases"), List.of(),
                new KeyHasher(new Hashing(TestSecrets.HASH_SECRET_PREVIOUS, null))::hmac);

        assertThat(again).contains(tok());
        assertThat(first).endsWith(tok());
        assertThat(other).doesNotContain(tok());
        assertThat(otherSecret.redact("/public/v/s/cases/" + TOKEN)).doesNotContain(tok());

        byte[] plain = MessageDigest.getInstance("SHA-256").digest(TOKEN.getBytes(StandardCharsets.UTF_8));
        assertThat(tok()).doesNotContain(HexFormat.of().formatHex(plain).substring(0, 12))
                .doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(plain).substring(0, 12));
    }

    @Test
    void theTokenPrefixesAreConfigurable() {
        LogRedactor docs = new LogRedactor(List.of("docs"), List.of(), hasher::hmac);

        assertThat(docs.redact("/public/v/s/docs/" + TOKEN)).doesNotContain(TOKEN);
        assertThat(docs.redact("/public/v/s/cases/" + TOKEN)).contains(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fulano.de.tal+kyb@empresa-exemplo.com.br", "CONTATO@EXEMPLO.ORG"})
    void redactsEmails(String email) {
        assertThat(redactor.redact("E11000 duplicate key: { email: \"" + email + "\" }"))
                .isEqualTo("E11000 duplicate key: { email: \"[EMAIL]\" }");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678000195", "12.345.678/0001-95", "12ABC34501DE35", "12.ABC.345/01DE-35"})
    void redactsCnpjs(String cnpj) {
        assertThat(redactor.redact("dup key cnpj " + cnpj + " exists")).isEqualTo("dup key cnpj [CNPJ] exists");
    }

    @Test
    void masksCpfRunsThroughCpfMasker() {
        assertThat(redactor.redact("razao FULANO DE TAL 123.456.789-09")).isEqualTo("razao FULANO DE TAL ***.***.***-**");
    }

    /** Regression: an 11-digit run inside a hex id or UUID is not a CPF. */
    @ParameterizedTest
    @ValueSource(strings = {"request_id=0c33aef5-f4ed-4d1e-8b5d-71416362082d",
        "request_id=0c33aef5-f4ed-4d1e-8b5d-a71416362082", "trace 12345678901ab",
        "{\"request_id\":\"0c33aef5-f4ed-4d1e-8b5d-71416362082d\"}", "sha=ab12345678901cd"})
    void leavesElevenDigitRunsInsideIdentifiersUnchanged(String input) {
        assertThat(redactor.redact(input)).isEqualTo(input);
    }

    @Test
    void neverAltersARandomUuid() {
        Random random = new Random(5);
        for (int i = 0; i < 20_000; i++) {
            String line = "request_id=" + new UUID(random.nextLong(), random.nextLong());
            assertThat(redactor.redact(line)).isEqualTo(line);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678909", "123.456.789-09", "123 456 789 09", "***.456.789-**"})
    void stillMasksCpfRunsBoundedByNonAlphanumerics(String cpf) {
        for (String template : List.of("%s", "cpf=%s", "cpf: %s;", "\"cpf\":\"%s\"", "(%s)", "user_%s", "/x/%s/y")) {
            assertThat(redactor.redact(template.formatted(cpf))).isEqualTo(template.formatted(CpfMasker.MASK));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Started KybrApplication in 3.2 seconds (process running for 4.1)",
        "event=index_ensured collection=rate_limit_counters index=expires_at_ttl",
        "route=/public/v/{slug}/cases/{token} status=200 duration_ms=12",
        "event=bff_key_rejected reason=missing", "Tests run: 14, Failures: 0", ""})
    void returnsTextWithoutSecretsUnchanged(String input) {
        assertThat(redactor.redact(input)).isEqualTo(input);
    }

    @Test
    void isNullSafe() {
        assertThat(redactor.redact(null)).isNull();
    }

    /** Timed in CPU time, warm, best of 3, so a loaded CI runner doesn't fail it. */
    @Test
    void redactsAMegabyteOfMixedTextInUnderASecond() {
        String line = "GET /public/v/s/cases/" + TOKEN + "?cnpj=12345678000195 Bearer abc a@b.co 123.456.789-09 ok\n";
        String input = line.repeat(1_048_576 / line.length() + 1);

        String out = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> redactor.redact(input));
        long best = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> bestNanos(input, 3));

        assertThat(out).doesNotContain(TOKEN).doesNotContain("12345678000195");
        assertThat(Duration.ofNanos(best)).as("1 MB in < 1 s (CPU time, best of 3, warm)").isLessThan(Duration.ofSeconds(1));
    }

    /**
     * ReDoS guard that holds on a busy machine: quadrupling the input must not grow
     * the time much beyond 4x (catastrophic backtracking is at least quadratic, i.e. ~16x). Each
     * size is timed in CPU time as the best of several runs, and a failed comparison is
     * re-measured a few times; a quadratic rule fails every attempt. The inputs are
     * small enough that the 60 s timeout only catches a hang.
     */
    @ParameterizedTest
    @ValueSource(strings = {"a", "1", "/cases", "%2F", "@", "a.", "?a=", "Bearer ", "1.", "cases/"})
    void pathologicalInputsStayLinear(String unit) {
        String small = unit.repeat(65_536 / unit.length());
        String large = unit.repeat(262_144 / unit.length());

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            redactor.redact(large);
            long smallNanos = 0;
            long largeNanos = 0;
            for (int attempt = 0; attempt < 3; attempt++) {
                smallNanos = bestNanos(small, 5);
                largeNanos = bestNanos(large, 5);
                if (largeNanos < smallNanos * 10 + LINEARITY_SLACK_NANOS) {
                    return;
                }
            }
            assertThat(largeNanos).as("4x input took %d us vs %d us", largeNanos / 1_000, smallNanos / 1_000)
                    .isLessThan(smallNanos * 10 + LINEARITY_SLACK_NANOS);
        });
    }

    /**
     * The best of {@code runs} redactions, in this thread's CPU time: time spent descheduled on a
     * loaded runner isn't counted (wall clock only where CPU time is unsupported).
     */
    private long bestNanos(String input, int runs) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            long start = cpuNanos();
            redactor.redact(input);
            best = Math.min(best, cpuNanos() - start);
        }
        return best;
    }

    private static long cpuNanos() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        return threads.isCurrentThreadCpuTimeSupported() ? threads.getCurrentThreadCpuTime() : System.nanoTime();
    }
}
