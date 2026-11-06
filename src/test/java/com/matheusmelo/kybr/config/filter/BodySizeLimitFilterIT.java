package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.RawHttp;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import com.matheusmelo.kybr.support.web.WebProbeController;
import io.restassured.response.Response;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BodySizeLimitFilterIT extends WebIntegrationTest {

    private static final int MIB = 1024 * 1024;
    private static final String DOCUMENT_UPLOAD = "/public/v/s/cases/t/documents";
    /** The seller document upload cap in application.yml (no route is uncapped). */
    private static final int DOCUMENT_UPLOAD_CAP = 11 * MIB;
    /** A declared length no machine could buffer: 8 GiB. */
    private static final long HUGE = 8L * 1024 * MIB;

    private Response post(String path, String contentType, int size) {
        return http().contentType(contentType).body(new byte[size]).post(path);
    }

    @Test
    void oneByteOverTheDefaultIs413BeforeTheHandler() {
        int before = WebProbeController.INVOCATIONS.get();

        Response response = post("/test/web/echo", "application/octet-stream", MIB + 1);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.jsonPath().getString("code")).isEqualTo("REQUISICAO_GRANDE");
        assertThat(response.header(RequestIdFilter.HEADER)).matches(RequestIdFilterTest.UUID_V4);
        assertThat(WebProbeController.INVOCATIONS.get()).isEqualTo(before);
    }

    @Test
    void exactlyTheDefaultLimitReachesTheHandler() {
        Response response = post("/test/web/echo", "application/octet-stream", MIB);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("size")).isEqualTo(MIB);
    }

    @Test
    void aChunkedBodyOverTheLimitIs413() throws Exception {
        RawHttp.Response response = RawHttp.send(port, chunkedHead("/test/web/echo"), chunked(MIB + 1));

        assertThat(response.status()).as(response.toString()).isEqualTo(413);
        assertThat(response.body()).startsWith("{\"code\":\"REQUISICAO_GRANDE\"");
    }

    @Test
    void aChunkedBodyUnderTheLimitIsAccepted() throws Exception {
        RawHttp.Response response = RawHttp.send(port, chunkedHead("/test/web/echo"), chunked(1000));

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
    }

    @Test
    void aRouteOverrideAccepts2Point5Megabytes() {
        Response response = post("/test/web/large", "application/octet-stream", 2_621_440);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("size")).isEqualTo(2_621_440);
    }

    @Test
    void multipartOutsideTheDocumentUploadRouteNeverReachesTheHandler() {
        int before = WebProbeController.INVOCATIONS.get();

        Response small = post("/test/web/echo", "multipart/form-data; boundary=x", 100);
        Response big = post("/test/web/echo", "multipart/form-data; boundary=x", MIB + 1);

        assertThat(small.statusCode()).isEqualTo(415);
        assertThat(small.jsonPath().getString("code")).isEqualTo("MIDIA_NAO_SUPORTADA");
        assertThat(big.statusCode()).isEqualTo(413);
        assertThat(WebProbeController.INVOCATIONS.get()).isEqualTo(before);
    }

    /** The document upload needs no key, so its body is capped and typed before anything reads it. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aChunkedJsonBodyOnDocumentUploadIs415WithoutBeingRead(boolean withKey) throws Exception {
        RawHttp.Response response = RawHttp.send(port,
                head(DOCUMENT_UPLOAD, "application/json", "Transfer-Encoding: chunked", withKey), chunked(MIB + MIB / 2));

        assertThat(response.status()).as(response.toString()).isEqualTo(415);
        assertThat(response.body()).startsWith("{\"code\":\"MIDIA_NAO_SUPORTADA\"");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aHugeDeclaredJsonBodyOnDocumentUploadIs415Immediately(boolean withKey) throws Exception {
        RawHttp.Response response = RawHttp.send(port,
                head(DOCUMENT_UPLOAD, "application/json", "Content-Length: " + HUGE, withKey), new byte[0]);

        assertThat(response.status()).as(response.toString()).isEqualTo(415);
    }

    /**
     * B0 has no document upload handler, so nothing reads the body: 404, never buffered. Once B2 adds the
     * handler, reading past the cap is 413 (the counting stream; see BodySizeLimitFilterTest).
     *
     * <p>The server stops reading and closes the connection, so the client may get a broken pipe or a
     * connection reset while still sending, with no response left to read. That is a pass too; a
     * response, when one arrives, must be the 404/413 rejection, never a 2xx.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aChunkedMultipartBodyOverTheDocumentUploadCapIsNeverBuffered(boolean withKey) throws Exception {
        byte[] body = chunked(DOCUMENT_UPLOAD_CAP + 64 * 1024);
        RawHttp.Outcome outcome = RawHttp.sendUntilClosed(port,
                head(DOCUMENT_UPLOAD, "multipart/form-data; boundary=x", "Transfer-Encoding: chunked", withKey), body);

        if (outcome.response() != null) {
            assertThat(outcome.response().status()).as(outcome.response().toString()).isIn(404, 413);
        } else {
            assertThat(outcome.bytesSent()).as("closed early: %s", outcome.closedEarly()).isPositive();
        }
        assertThat(http().get("/livez").statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aHugeDeclaredMultipartBodyOnDocumentUploadIs413Immediately(boolean withKey) throws Exception {
        RawHttp.Response response = RawHttp.send(port,
                head(DOCUMENT_UPLOAD, "multipart/form-data; boundary=x", "Content-Length: " + HUGE, withKey), new byte[0]);

        assertThat(response.status()).as(response.toString()).isEqualTo(413);
    }

    @Test
    void multipartUnderTheDocumentUploadCapPassesTheBodyLimit() {
        Response response = httpWithoutKey().contentType("multipart/form-data; boundary=x")
                .body(new byte[5 * MIB]).post("/public/v/s/cases/t/documents");

        assertThat(response.statusCode()).isNotIn(413, 415, 403);
    }

    @Test
    void aChunkedJsonBodyOverTheLimitWithoutAKeyIs403BeforeAnyRead() throws Exception {
        RawHttp.Response response = RawHttp.send(port,
                head("/test/web/echo", "application/json", "Transfer-Encoding: chunked", false), chunked(MIB + 1));

        assertThat(response.status()).as(response.toString()).isEqualTo(403);
    }

    @Test
    void aChunkedJsonBodyOverTheLimitWithAKeyIs413() throws Exception {
        RawHttp.Response response = RawHttp.send(port,
                head("/test/web/echo", "application/json", "Transfer-Encoding: chunked", true), chunked(MIB + 1));

        assertThat(response.status()).as(response.toString()).isEqualTo(413);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aHugeDeclaredJsonBodyIs413Immediately(boolean withKey) throws Exception {
        RawHttp.Response response = RawHttp.send(port,
                head("/test/web/echo", "application/json", "Content-Length: " + HUGE, withKey), new byte[0]);

        assertThat(response.status()).as(response.toString()).isEqualTo(413);
    }

    private byte[] head(String path, String contentType, String lengthHeader, boolean withKey) {
        return ("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: " + contentType + "\r\n"
                + lengthHeader + (withKey ? "\r\n" + BFF_KEY_HEADER + ": " + bffKey() : ""))
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private byte[] chunkedHead(String path) {
        return ("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/octet-stream"
                + "\r\nTransfer-Encoding: chunked\r\n" + BFF_KEY_HEADER + ": " + bffKey())
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] chunked(int size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int chunk = 64 * 1024;
        for (int sent = 0; sent < size; sent += chunk) {
            int length = Math.min(chunk, size - sent);
            out.writeBytes((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(new byte[length]);
            out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        out.writeBytes("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }
}
