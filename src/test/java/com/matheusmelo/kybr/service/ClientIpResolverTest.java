package com.matheusmelo.kybr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.BffKeySlot;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.ClientIp.Source;
import com.matheusmelo.kybr.service.ClientIpResolver.Resolution;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Documentation addresses only (RFC 5737, RFC 3849). */
class ClientIpResolverTest {

    private static final String SOCKET = "192.0.2.10";
    private static final String FLY = "Fly-Client-IP";

    private final ClientIpResolver local = new ClientIpResolver((String) null);
    private final ClientIpResolver fly = new ClientIpResolver(FLY);

    /** "-" = header absent; "|" separates two header instances. */
    private static List<String> values(String cell) {
        if (cell == null || cell.equals("-")) {
            return List.of();
        }
        return Arrays.asList(cell.split("\\|", -1));
    }

    @ParameterizedTest(name = "[{index}] slot={0} header={1} platform={2} -> {3}/{4}")
    @CsvSource(delimiter = ';', nullValues = "NULL", value = {
        // valid key + valid header -> FORWARDED, canonical form
        "CURRENT;  198.51.100.4;            -; 198.51.100.4;   FORWARDED",
        "PREVIOUS; 198.51.100.4;            -; 198.51.100.4;   FORWARDED",
        "CURRENT;  2001:DB8:0:0:0:0:0:1;    -; 2001:db8::1;    FORWARDED",
        "CURRENT;  2001:db8:0:1:0:0:0:1;    -; 2001:db8:0:1::1; FORWARDED",
        "CURRENT;  ::ffff:198.51.100.4;     -; 198.51.100.4;   FORWARDED",
        // valid key + unusable header -> FORWARDED_ABSENT with the socket IP
        "CURRENT;  -;                       -; 192.0.2.10;     FORWARDED_ABSENT",
        "CURRENT;  '';                      -; 192.0.2.10;     FORWARDED_ABSENT",
        "CURRENT;  ' 198.51.100.4';         -; 192.0.2.10;     FORWARDED_ABSENT",
        "CURRENT;  198.51.100.4:80;         -; 192.0.2.10;     FORWARDED_ABSENT",
        "CURRENT;  [2001:db8::1]:443;       -; 192.0.2.10;     FORWARDED_ABSENT",
        "CURRENT;  [2001:db8::1];           -; 192.0.2.10;     FORWARDED_ABSENT",
        "CURRENT;  fe80::1%eth0;            -; 192.0.2.10;     FORWARDED_ABSENT",
        "CURRENT;  example.invalid;         -; 192.0.2.10;     FORWARDED_ABSENT",
        "CURRENT;  '198.51.100.4, 203.0.113.7'; -; 192.0.2.10; FORWARDED_ABSENT",
        "CURRENT;  999.1.1.1;               -; 192.0.2.10;     FORWARDED_ABSENT",
        // two instances
        "CURRENT;  198.51.100.4|198.51.100.4; -; 192.0.2.10;   FORWARDED_ABSENT",
        // no key: the header is ignored
        "NONE;     198.51.100.4;            -; 192.0.2.10;     DIRECT",
        "NONE;     -;                       -; 192.0.2.10;     DIRECT",
    })
    void resolvesWithoutAPlatformHeader(BffKeySlot slot, String header, String platform, String ip, Source source) {
        Resolution resolution = local.resolve(slot, values(header), values(platform), SOCKET);

        assertThat(resolution.clientIp()).isEqualTo(new ClientIp(ip, source));
        assertThat(resolution.platformOk()).isTrue();
    }

    @ParameterizedTest(name = "[{index}] slot={0} header={1} platform={2} -> {3}/{4} ok={5} reason={6}")
    @CsvSource(delimiter = ';', nullValues = "NULL", value = {
        // platform header valid
        "NONE;    203.0.113.9;  198.51.100.7;            198.51.100.7; DIRECT;          true;  NULL",
        "CURRENT; 198.51.100.4; 203.0.113.8;             198.51.100.4; FORWARDED;       true;  NULL",
        "CURRENT; -;            198.51.100.7;            198.51.100.7; FORWARDED_ABSENT; true; NULL",
        // platform header missing, invalid, comma-joined or twice -> PLATFORM_ABSENT
        "NONE;    -;            -;                       192.0.2.10;   PLATFORM_ABSENT; false; missing",
        "NONE;    -;            not-an-ip;               192.0.2.10;   PLATFORM_ABSENT; false; invalid",
        "NONE;    -;            '1.2.3.4, 5.6.7.8';      192.0.2.10;   PLATFORM_ABSENT; false; multiple",
        "NONE;    -;            198.51.100.7|198.51.100.8; 192.0.2.10; PLATFORM_ABSENT; false; multiple",
        // a valid key + valid client header still wins, with platformOk=false reported
        "CURRENT; 198.51.100.4; -;                       198.51.100.4; FORWARDED;       false; missing",
        "CURRENT; -;            not-an-ip;               192.0.2.10;   FORWARDED_ABSENT; false; invalid",
    })
    void resolvesWithThePlatformHeaderConfigured(BffKeySlot slot, String header, String platform, String ip,
            Source source, boolean platformOk, String reason) {
        Resolution resolution = fly.resolve(slot, values(header), values(platform), SOCKET);

        assertThat(resolution.clientIp()).isEqualTo(new ClientIp(ip, source));
        assertThat(resolution.platformOk()).isEqualTo(platformOk);
        assertThat(resolution.platformReason()).isEqualTo(reason);
    }

    @Test
    void aPlatformHeaderIsIgnoredWhenNotConfigured() {
        Resolution resolution = local.resolve(BffKeySlot.NONE, List.of(), List.of("203.0.113.8"), SOCKET);

        assertThat(resolution.clientIp()).isEqualTo(new ClientIp(SOCKET, Source.DIRECT));
    }

    @Test
    void reportsWhetherAClientHeaderWasPresent() {
        assertThat(local.resolve(BffKeySlot.NONE, List.of("198.51.100.4"), List.of(), SOCKET).clientHeaderPresent())
                .isTrue();
        assertThat(local.resolve(BffKeySlot.NONE, List.of(), List.of(), SOCKET).clientHeaderPresent()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void onlyForwardedAndDirectArePerClientIdentities(Source source) {
        boolean expected = source == Source.FORWARDED || source == Source.DIRECT;

        assertThat(new ClientIp(SOCKET, source).isPerClientIdentity()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.invalid", "localhost", "nonexistent-host-for-test.invalid"})
    void aHostnameIsInvalidWithoutAnyLookup(String hostname) {
        long start = System.nanoTime();

        assertThat(ClientIpResolver.parseLiteral(hostname)).isEmpty();
        assertThat(System.nanoTime() - start).as("no DNS round trip").isLessThan(50_000_000L);
    }
}
