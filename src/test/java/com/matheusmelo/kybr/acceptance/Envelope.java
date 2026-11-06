package com.matheusmelo.kybr.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.exception.ErrorCode;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/** The error envelope is exactly {@code {code,message[,field]}}. */
final class Envelope {

    private static final Set<String> CODES =
            Set.copyOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());

    private Envelope() {
    }

    /** Asserts status, JSON content type and the exact envelope shape; returns the body. */
    static Map<String, Object> assertEnvelope(Response response, int status, String code) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        return assertBody(response.contentType(), response.asString(), code);
    }

    static Map<String, Object> assertBody(String contentType, String body, String code) {
        assertThat(contentType).as("Content-Type").startsWith("application/json");
        Map<String, Object> map = JsonPath.from(body).getMap("");
        assertThat(map.keySet()).as("envelope keys of " + body).containsAnyOf("code").isSubsetOf("code", "message", "field");
        assertThat(map).containsKeys("code", "message");
        assertThat((String) map.get("code")).isIn(CODES).isEqualTo(code);
        assertThat((String) map.get("message")).isEqualTo(ErrorCode.valueOf(code).message());
        return map;
    }
}
