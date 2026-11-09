package com.matheusmelo.kybr.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    private static final String VALID = "classpath*:email-fixtures/valid/*.txt";
    private static final URI CASE_LINK = URI.create("https://kybr.com.br/v/loja-exemplo/c/caso-sintetico");

    private final TemplateRenderer renderer = new TemplateRenderer(VALID);

    @Test
    void theHtmlPartShowsTheSameWordsAsThePlainPart() {
        RenderedEmail email = render("Maria <b>Souza</b>", "Empresa Exemplo LTDA");

        assertThat(normalise(visibleText(email.html()))).isEqualTo(normalise(email.text()));
        assertThat(email.html()).contains("<p>");
    }

    @Test
    void placeholdersAreEscapedInTheHtmlPart() {
        RenderedEmail email = render("<script>alert(1)</script>", "Empresa Exemplo LTDA");

        assertThat(email.html()).doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(email.text()).contains("<script>alert(1)</script>");
    }

    @Test
    void sellerTextIsNeutralisedAndBounded() {
        RenderedEmail email = render("Ana http://evil.example/x‮" + "a".repeat(200), "Empresa Exemplo LTDA");

        assertThat(email.text()).doesNotContain("://evil").doesNotContain("evil.example").doesNotContain("‮");
        assertThat(email.html()).doesNotContain("evil.example");
    }

    @Test
    void razaoSocialIsCpfMaskedInSubjectAndBothParts() {
        RenderedEmail email = render("Maria", "MARIA SOUZA 123.456.789-09");

        assertThat(email.subject()).isEqualTo("Cadastro de MARIA SOUZA ***.***.***-** atualizado");
        assertThat(email.subject() + email.text() + email.html()).doesNotContain("123.456.789-09")
                .doesNotContain("12345678909");
    }

    @Test
    void datesAreShownInSaoPauloTime() {
        RenderedEmail email = render("Maria", "Empresa Exemplo LTDA");

        assertThat(email.text()).contains("em 07/11/2026 às 14:30.");
    }

    @Test
    void theHtmlHasNoImageAndLinksOnlyToAllowedHosts() {
        RenderedEmail email = render("Maria", "Empresa Exemplo LTDA");

        assertThat(email.html()).doesNotContainIgnoringCase("<img");
        Matcher anchor = Pattern.compile("<a\\b[^>]*href=\"([^\"]*)\"").matcher(email.html());
        int anchors = 0;
        while (anchor.find()) {
            anchors++;
            assertThat(anchor.group(1)).matches("https://(kybr\\.com\\.br/v/|app\\.kybr\\.com\\.br/).*");
        }
        assertThat(anchors).isEqualTo(2);
        assertThat(email.html()).contains("<a href=\"" + CASE_LINK + "\">" + CASE_LINK + "</a>.");
    }

    @Test
    void aTemplateWithALinkToAnotherHostFailsToLoad() {
        assertThatThrownBy(() -> new TemplateRenderer("classpath*:email-fixtures/bad-link/*.txt"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("aviso");
    }

    @Test
    void aTemplateWithALinkInItsSubjectFailsToLoad() {
        assertThatThrownBy(() -> new TemplateRenderer("classpath*:email-fixtures/link-in-subject/*.txt"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("aviso");
    }

    @Test
    void aLinkValueNeverGoesInTheSubject() {
        Map<String, TemplateValue> values = new HashMap<>(values("Maria", "x"));
        values.put("razao_social", new TemplateValue.Link(CASE_LINK));

        assertThatThrownBy(() -> renderer.render("aviso", values))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subject");
    }

    @Test
    void aLinkValueMustBeAnAllowedHttpsPathWithoutAQuery() {
        for (String bad : new String[] {"https://evil.example/v/x", "http://kybr.com.br/v/x",
                "https://kybr.com.br/v/x?t=abc", "https://kybr.com.br/outro", "https://user@kybr.com.br/v/x"}) {
            assertThatThrownBy(() -> new TemplateValue.Link(URI.create(bad)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aMissingValueFailsTheRender() {
        Map<String, TemplateValue> values = new HashMap<>(values("Maria", "x"));
        values.remove("quando");

        assertThatThrownBy(() -> renderer.render("aviso", values)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quando");
    }

    @Test
    void knowsWhichTemplatesExist() {
        assertThat(renderer.has("aviso")).isTrue();
        assertThat(renderer.has("contato-alterado")).isFalse();
        assertThatThrownBy(() -> renderer.render("contato-alterado", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RenderedEmail render(String responsavel, String razaoSocial) {
        return renderer.render("aviso", values(responsavel, razaoSocial));
    }

    private static Map<String, TemplateValue> values(String responsavel, String razaoSocial) {
        return Map.of(
                "responsavel", new TemplateValue.TextoVendedor(responsavel),
                "razao_social", new TemplateValue.RazaoSocial(razaoSocial),
                "quando", new TemplateValue.DataHora(Instant.parse("2026-11-07T17:30:00Z")),
                "link", new TemplateValue.Link(CASE_LINK));
    }

    /** The text a reader sees: the body without tags, entities decoded. */
    static String visibleText(String html) {
        String body = html.replaceAll("(?s).*<body>", "").replaceAll("(?s)</body>.*", "");
        return body.replaceAll("<br>", "\n").replaceAll("</p>", "\n\n").replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    static String normalise(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
