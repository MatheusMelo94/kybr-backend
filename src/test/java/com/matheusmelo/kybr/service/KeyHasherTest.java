package com.matheusmelo.kybr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties.Hashing;
import com.matheusmelo.kybr.support.TestSecrets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class KeyHasherTest {

    private final KeyHasher hasher = new KeyHasher(new Hashing(TestSecrets.HASH_SECRET, null));

    @Test
    void matchesTheRfc4231LargeKeyVector() {
        // RFC 4231 test case 6: a 131-byte key of 0xaa.
        String key = TestSecrets.base64Url(131, 0xaa);
        KeyHasher rfc = new KeyHasher(new Hashing(key, null));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of()
                .parseHex("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"));

        assertThat(rfc.hmac("Test Using Larger Than Block-Size Key - Hash Key First")).isEqualTo(expected);
    }

    @Test
    void isDeterministicBase64UrlWithoutPadding() {
        String first = hasher.hmac("ip:198.51.100.4");

        assertThat(hasher.hmac("ip:198.51.100.4")).isEqualTo(first);
        assertThat(first).matches("^[A-Za-z0-9_-]{43}$");
    }

    @Test
    void differsAcrossSecrets() {
        KeyHasher other = new KeyHasher(new Hashing(TestSecrets.HASH_SECRET_PREVIOUS, null));

        assertThat(other.hmac("ip:198.51.100.4")).isNotEqualTo(hasher.hmac("ip:198.51.100.4"));
    }

    @Test
    void differsAcrossSubjects() {
        assertThat(hasher.hmac("cnpj:12345678000195")).isNotEqualTo(hasher.hmac("cnpj:98765432000198"));
    }

    @Test
    void neverContainsTheInput() {
        for (String subject : List.of("12345678000195", "198.51.100.4", "fulano@example.com")) {
            assertThat(hasher.hmac(subject)).doesNotContain(subject);
        }
    }

    @Test
    void isThreadSafe() throws Exception {
        List<String> subjects = IntStream.range(0, 64).mapToObj(i -> "subject-" + i).toList();
        List<String> expected = subjects.stream().map(hasher::hmac).toList();

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            tasks.add(() -> {
                for (int round = 0; round < 200; round++) {
                    for (int i = 0; i < subjects.size(); i++) {
                        if (!hasher.hmac(subjects.get(i)).equals(expected.get(i))) {
                            return false;
                        }
                    }
                }
                return true;
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (Future<Boolean> result : pool.invokeAll(tasks)) {
                assertThat(result.get()).isTrue();
            }
        }
    }

    @Test
    void candidatesAreCurrentOnlyWithoutAPreviousSecret() {
        assertThat(hasher.hmacCandidates("key")).containsExactly(hasher.hmac("key"));
    }

    @Test
    void candidatesAreCurrentThenPreviousDuringARotation() {
        KeyHasher rotating = new KeyHasher(new Hashing(TestSecrets.HASH_SECRET, TestSecrets.HASH_SECRET_PREVIOUS));
        KeyHasher previousOnly = new KeyHasher(new Hashing(TestSecrets.HASH_SECRET_PREVIOUS, null));

        assertThat(rotating.hmacCandidates("key")).containsExactly(hasher.hmac("key"), previousOnly.hmac("key"));
        assertThat(rotating.hmac("key")).as("hmac always uses current").isEqualTo(hasher.hmac("key"));
    }
}
