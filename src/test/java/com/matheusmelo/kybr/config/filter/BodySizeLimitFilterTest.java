package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.config.KybrProperties.Limits;
import com.matheusmelo.kybr.config.KybrProperties.RouteLimit;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import com.matheusmelo.kybr.exception.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.json.JsonMapper;

class BodySizeLimitFilterTest {

    private static final int MIB = 1024 * 1024;
    private static final String DOCUMENT_UPLOAD = "/public/v/{slug}/cases/{token}/documents";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final BodySizeLimitFilter filter = new BodySizeLimitFilter(
            new Limits(DataSize.ofMegabytes(1),
                    List.of(new RouteLimit(HttpMethod.POST, "/v1/batches", DataSize.ofMegabytes(3))),
                    List.of(new RouteLimit(HttpMethod.POST, DOCUMENT_UPLOAD, DataSize.ofMegabytes(11)))),
            new ErrorResponseWriter(mapper));

    /** A request whose body arrives chunked: no Content-Length. */
    private static MockHttpServletRequest chunked(String method, String path, int size) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[size]);
        return prepare(request, "application/json");
    }

    private static MockHttpServletRequest withLength(String method, String path, int size, String contentType) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContent(new byte[size]);
        return prepare(request, contentType);
    }

    private static MockHttpServletRequest prepare(MockHttpServletRequest request, String contentType) {
        request.setContentType(contentType);
        request.setAttribute(PathFirewallFilter.REQUEST_PATH_ATTRIBUTE, ServletRequestPathUtils.parseAndCache(request));
        return request;
    }

    private record Outcome(MockHttpServletResponse response, HttpServletRequest downstream) {
    }

    private Outcome run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> downstream.set((HttpServletRequest) req));
        return new Outcome(response, downstream.get());
    }

    private void assertRejected(Outcome outcome, int status, String code) throws Exception {
        assertThat(outcome.downstream()).as("chain must not run").isNull();
        assertThat(outcome.response().getStatus()).isEqualTo(status);
        assertThat(mapper.readTree(outcome.response().getContentAsString()).get("code").asString()).isEqualTo(code);
    }

    @Test
    void aContentLengthOverTheDefaultIs413BeforeTheChain() throws Exception {
        assertRejected(run(withLength("POST", "/v1/x", MIB + 1, "application/json")), 413, "REQUISICAO_GRANDE");
    }

    @Test
    void exactlyTheLimitPassesAndCanBeRead() throws Exception {
        Outcome outcome = run(withLength("POST", "/v1/x", MIB, "application/json"));

        assertThat(outcome.downstream().getInputStream().readAllBytes()).hasSize(MIB);
    }

    @Test
    void aChunkedBodyOverTheLimitFailsWhileBeingRead() throws Exception {
        Outcome outcome = run(chunked("POST", "/v1/x", MIB + 1));

        assertThatThrownBy(() -> outcome.downstream().getInputStream().readAllBytes())
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void aChunkedBodyOverTheLimitFailsThroughTheReaderToo() throws Exception {
        Outcome outcome = run(chunked("POST", "/v1/x", MIB + 1));

        BufferedReader reader = outcome.downstream().getReader();
        assertThatThrownBy(() -> reader.transferTo(java.io.Writer.nullWriter()))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void aChunkedBodyUnderTheLimitReadsFully() throws Exception {
        Outcome outcome = run(chunked("POST", "/v1/x", MIB));

        assertThat(outcome.downstream().getInputStream().readAllBytes()).hasSize(MIB);
    }

    @Test
    void aRouteOverrideRaisesTheLimit() throws Exception {
        assertThat(run(withLength("POST", "/v1/batches", 2_621_440, "application/json")).downstream()).isNotNull();
        assertRejected(run(withLength("POST", "/v1/batches", 3 * MIB + 1, "application/json")), 413,
                "REQUISICAO_GRANDE");
        assertRejected(run(withLength("PUT", "/v1/batches", 2 * MIB, "application/json")), 413, "REQUISICAO_GRANDE");
    }

    @Test
    void multipartOutsideTheDocumentUploadRouteIs413OverTheLimitAnd415Under() throws Exception {
        String multipart = "multipart/form-data; boundary=x";

        assertRejected(run(withLength("POST", "/v1/x", MIB + 1, multipart)), 413, "REQUISICAO_GRANDE");
        assertRejected(run(withLength("POST", "/v1/x", 10, multipart)), 415, "MIDIA_NAO_SUPORTADA");
        MockHttpServletRequest chunkedMultipart = chunked("POST", "/v1/x", 10);
        chunkedMultipart.setContentType(multipart);
        assertRejected(run(chunkedMultipart), 415, "MIDIA_NAO_SUPORTADA");
    }

    @Test
    void theDocumentUploadRouteAcceptsMultipartUpToItsOwnCap() throws Exception {
        String multipart = "multipart/form-data; boundary=x";

        Outcome outcome = run(withLength("POST", "/public/v/s/cases/t/documents", 5 * MIB, multipart));

        assertThat(outcome.downstream().getInputStream().readAllBytes()).hasSize(5 * MIB);
        assertRejected(run(withLength("POST", "/public/v/s/cases/t/documents", 11 * MIB + 1, multipart)), 413,
                "REQUISICAO_GRANDE");
    }

    @Test
    void aChunkedMultipartBodyOverTheDocumentUploadCapFailsWhileBeingRead() throws Exception {
        MockHttpServletRequest request = chunked("POST", "/public/v/s/cases/t/documents", 11 * MIB + 1);
        request.setContentType("multipart/form-data; boundary=x");

        Outcome outcome = run(request);

        assertThatThrownBy(() -> outcome.downstream().getInputStream().readAllBytes())
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/plain", "multipart/mixed; boundary=x", "application/octet-stream"})
    void theDocumentUploadRouteRefusesAnythingButMultipartFormDataBeforeReading(String contentType) throws Exception {
        MockHttpServletRequest declared = withLength("POST", "/public/v/s/cases/t/documents", 10, contentType);
        MockHttpServletRequest streamed = chunked("POST", "/public/v/s/cases/t/documents", 20 * MIB);
        streamed.setContentType(contentType);

        assertRejected(run(declared), 415, "MIDIA_NAO_SUPORTADA");
        assertRejected(run(streamed), 415, "MIDIA_NAO_SUPORTADA");
        assertThat(streamed.getInputStream().available()).as("body left unread").isEqualTo(20 * MIB);
    }

    @Test
    void aMissingContentTypeOnDocumentUploadIs415() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/public/v/s/cases/t/documents");
        request.setContent(new byte[10]);
        request.setAttribute(PathFirewallFilter.REQUEST_PATH_ATTRIBUTE, ServletRequestPathUtils.parseAndCache(request));

        assertRejected(run(request), 415, "MIDIA_NAO_SUPORTADA");
    }

    @Test
    void otherMethodsAndPathsNearDocumentUploadKeepTheDefaultRules() throws Exception {
        String multipart = "multipart/form-data; boundary=x";

        assertRejected(run(withLength("PUT", "/public/v/s/cases/t/documents", 10, multipart)), 415,
                "MIDIA_NAO_SUPORTADA");
        assertRejected(run(withLength("POST", "/public/v/s/cases/t/documents/x", 10, multipart)), 415,
                "MIDIA_NAO_SUPORTADA");
        assertRejected(run(withLength("POST", "/public/v/s/cases/t/documents/x", MIB + 1, "application/json")),
                413, "REQUISICAO_GRANDE");
    }

    @Test
    void runsAfterThePathFirewall() {
        assertThat(filter.getOrder()).isEqualTo(FilterOrder.BODY_SIZE_LIMIT);
    }
}
