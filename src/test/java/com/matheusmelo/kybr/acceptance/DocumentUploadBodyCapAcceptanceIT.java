package com.matheusmelo.kybr.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.RawHttp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * No request body is ever uncapped, at the acceptance level, on the seller document upload
 * ({@code POST /public/v/{slug}/cases/{token}/documents}, no BFF key, multipart only, 11 MB cap).
 * {@code BodySizeLimitFilterIT} proves the statuses; this adds the cross-cutting guarantees on
 * those rejections: the envelope, {@code X-Request-Id}, the security headers, exactly one access
 * line, and the case token in the path never reaching the logs.
 */
@ExtendWith(OutputCaptureExtension.class)
class DocumentUploadBodyCapAcceptanceIT extends AcceptanceTest {

    private static final long OVER_CAP = 12L * 1024 * 1024;

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "multipart/form-data; boundary=x,413,REQUISICAO_GRANDE",
        "application/json,415,MIDIA_NAO_SUPORTADA",
    })
    void aRejectedDocumentUploadBodyIsTheEnvelopeAndTheTokenIsNotLogged(String contentType, int status, String code,
            CapturedOutput output) throws Exception {
        String token = "qaDocumentUploadTok_" + UUID.randomUUID().toString().replace("-", "");
        String path = "/public/v/s/cases/" + token + "/documents";

        try (AccessLines lines = AccessLines.capture()) {
            RawHttp.Response response = RawHttp.send(port, "POST " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: " + contentType + "\r\nContent-Length: " + OVER_CAP);

            assertThat(response.status()).as(response.toString()).isEqualTo(status);
            Envelope.assertBody(response.header("Content-Type"), response.body(), code);
            assertThat(response.header("X-Request-Id")).matches(ResponseInvariants.UUID_V4);
            ResponseInvariants.SECURITY_HEADERS.forEach((name, value) ->
                    assertThat(response.header(name)).as(name).isEqualTo(value));
            assertThat(response.header("Set-Cookie")).isNull();
            Map<String, String> line = lines.lineFor(response.header("X-Request-Id"));
            assertThat(line).containsEntry("status", String.valueOf(status));
            assertThat(line.get("route")).doesNotContain(token).contains("tok:");
        }
        assertThat(httpWithoutKey().get("/livez").statusCode()).as("the server is still serving").isEqualTo(200);
        assertThat(output.getAll()).doesNotContain(token);
    }
}
