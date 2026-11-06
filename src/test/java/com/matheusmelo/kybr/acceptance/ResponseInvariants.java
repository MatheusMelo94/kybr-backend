package com.matheusmelo.kybr.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;

/**
 * Suite-level guard: a REST Assured filter that records every response an acceptance test
 * receives, then checks the invariants that hold for <b>every</b> B0 response:
 *
 * <ul>
 *   <li>no 3xx and no {@code Location};
 *   <li>no {@code Set-Cookie}, no {@code WWW-Authenticate}, no {@code JSESSIONID} anywhere;
 *   <li>an {@code X-Request-Id} that is a UUID v4;
 *   <li>any {@code Retry-After} is digits only ({@code ^\d+$}) and at least 1;
 *   <li>the security header set on every response.
 * </ul>
 */
public final class ResponseInvariants implements Filter {

    public static final Pattern UUID_V4 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    /** The exact security header values. */
    public static final Map<String, String> SECURITY_HEADERS = Map.of(
            "X-Content-Type-Options", "nosniff",
            "Cache-Control", "no-store",
            "Referrer-Policy", "no-referrer",
            "X-Frame-Options", "DENY",
            "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");

    private record Seen(String request, Response response) {
    }

    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    @Override
    public Response filter(FilterableRequestSpecification request, FilterableResponseSpecification responseSpec,
            FilterContext ctx) {
        Response response = ctx.next(request, responseSpec);
        seen.add(new Seen(request.getMethod() + " " + request.getURI(), response));
        return response;
    }

    public int count() {
        return seen.size();
    }

    /** Asserts every invariant on every recorded response, then forgets them. */
    public void verifyAndReset() {
        SoftAssertions softly = new SoftAssertions();
        for (Seen s : seen) {
            Response r = s.response();
            String where = s.request() + " -> " + r.statusCode();
            softly.assertThat(r.statusCode() / 100).as("no 3xx: " + where).isNotEqualTo(3);
            softly.assertThat(r.header("Location")).as("no Location: " + where).isNull();
            softly.assertThat(r.headers().getValues("Set-Cookie")).as("no Set-Cookie: " + where).isEmpty();
            softly.assertThat(r.header("WWW-Authenticate")).as("no WWW-Authenticate: " + where).isNull();
            for (Header header : r.headers()) {
                softly.assertThat(header.getValue()).as("no JSESSIONID in headers: " + where)
                        .doesNotContainIgnoringCase("jsessionid");
            }
            softly.assertThat(r.asString()).as("no JSESSIONID in body: " + where).doesNotContainIgnoringCase("jsessionid");
            softly.assertThat(r.headers().getValues("X-Request-Id")).as("one X-Request-Id: " + where).hasSize(1);
            String requestId = r.header("X-Request-Id");
            softly.assertThat(requestId == null ? "" : requestId).as("X-Request-Id is a UUID v4: " + where)
                    .matches(UUID_V4);
            String retryAfter = r.header("Retry-After");
            if (retryAfter != null) {
                softly.assertThat(retryAfter).as("Retry-After digits only: " + where).matches("^[1-9]\\d*$");
            }
            SECURITY_HEADERS.forEach((name, value) -> softly.assertThat(r.headers().getValues(name))
                    .as(name + " exactly once: " + where).containsExactly(value));
        }
        seen.clear();
        softly.assertAll();
    }

    /** For a test that wants to fail fast on an empty recording. */
    public void assertRecordedSomething() {
        assertThat(seen).as("the acceptance test sent requests through the recorder").isNotEmpty();
    }
}
