package com.matheusmelo.kybr.config.management;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.util.LogRedactor;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/** The admin access line is one line, whatever the request's method and path carry. */
@ExtendWith(OutputCaptureExtension.class)
class ManagementAccessLogFilterTest {

    private final ManagementAccessLogFilter filter = new ManagementAccessLogFilter(request -> false,
            new LogRedactor(List.of(), List.of(), value -> "0".repeat(64)));

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/admin\r\nevent=admin_access method=GET operation=/forged status=200",
        "/actuator/admin\nevent=forged", "/actuator/admin event=forged\u0085x"})
    void aPathWithLineBreaksIsLoggedAsOneSanitisedLine(String path, CapturedOutput output) throws Exception {
        filter.doFilter(request("GET", path), new MockHttpServletResponse(), new MockFilterChain());

        List<String> lines = adminLines(output);
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line).contains("operation=/actuator/admin%").doesNotContain("\r").doesNotContain(" ")
                    .doesNotContain("\u0085");
            assertThat(field("event").matcher(line).results().count()).as("one event field").isEqualTo(1);
            assertThat(field("status").matcher(line).results().count()).as("one status field").isEqualTo(1);
        });
    }

    @Test
    void anEncodedLineBreakStaysEncodedText(CapturedOutput output) throws Exception {
        filter.doFilter(request("GET", "/actuator/admin%0D%0Aevent=forged"), new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(adminLines(output)).singleElement()
                .satisfies(line -> assertThat(line).contains("operation=/actuator/admin%0D%0Aevent=forged"));
    }

    @Test
    void anOverlongPathIsCapped(CapturedOutput output) throws Exception {
        filter.doFilter(request("GET", "/actuator/" + "a".repeat(10_000)), new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(adminLines(output)).singleElement().satisfies(line -> {
            assertThat(line).contains("a".repeat(100) + "…").doesNotContain("a".repeat(LogRedactor.MAX_PATH_CHARS));
            assertThat(line.length()).isLessThan(1_000);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"BREW", "GET\r\nevent=forged", "get", ""})
    void aMethodOutsideTheHttpMethodsIsLoggedAsOther(String method, CapturedOutput output) throws Exception {
        filter.doFilter(request(method, "/actuator/admin"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(adminLines(output)).singleElement().satisfies(line -> assertThat(line).contains("method=OTHER ")
                .doesNotContain("forged"));
    }

    @Test
    void aKnownMethodAndTheMatchedPatternAreLoggedAsIs(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = request("POST", "/actuator/admin/workspaces/loja-exemplo");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/actuator/admin/{arg0}/{arg1}");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(adminLines(output)).singleElement().satisfies(line -> assertThat(line)
                .contains("method=POST operation=/actuator/admin/{arg0}/{arg1} ").doesNotContain("loja-exemplo"));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    /** A field that starts the line or follows whitespace; a forged one stays glued to its encoded break. */
    private static Pattern field(String name) {
        return Pattern.compile("(?:^|\\s)" + name + "=");
    }

    private static List<String> adminLines(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains("event=admin_access")).toList();
    }
}
