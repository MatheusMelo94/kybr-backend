package com.matheusmelo.kybr.service.email;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Renders e-mails from plain-text templates, one source per e-mail: the plain part is the template
 * with its placeholders filled, and the HTML part is generated from that text (escaped, a
 * {@code <p>} per paragraph, a {@code <br>} per line, and allowed links as plain {@code <a>}), so
 * both parts carry the same words by construction. No images, no tracking, no redirects.
 *
 * <p>Templates live in {@code src/main/resources/email/{name}.txt} and hold only approved copy:
 *
 * <pre>
 * Assunto: the subject, with {placeholders}
 *
 * The body, with {placeholders}. Paragraphs are separated by a blank line.
 * </pre>
 *
 * A template fails to load (startup fails) when its first line isn't the subject, when its subject
 * holds a link, or when its body links anywhere outside {@link TemplateValue#ALLOWED_LINK_PREFIXES}.
 * Placeholder values are typed ({@link TemplateValue}); a {@link TemplateValue.Link} is refused in a
 * subject, so a token never goes in one.
 */
@Component
public class TemplateRenderer {

    /** Where the application's templates are. */
    public static final String LOCATION = "classpath*:email/*.txt";

    private static final String SUBJECT_PREFIX = "Assunto: ";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]{1,40})\\}");
    private static final Pattern URL = Pattern.compile("(?i)(?:[a-z][a-z0-9+.-]{0,20}://|www\\.)[^\\s<>\"]*+");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t]*\\n\\s*");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?)]+$");

    private record Template(String subject, String body) {
    }

    private final Map<String, Template> templates;

    @Autowired
    public TemplateRenderer() {
        this(LOCATION);
    }

    /** Loads every template matching {@code locationPattern}; throws when one is invalid. */
    public TemplateRenderer(String locationPattern) {
        Map<String, Template> loaded = new HashMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(locationPattern)) {
                String filename = Objects.requireNonNull(resource.getFilename());
                String name = filename.substring(0, filename.length() - ".txt".length());
                loaded.put(name, parse(name, resource.getContentAsString(StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("e-mail templates could not be read", e);
        }
        this.templates = Map.copyOf(loaded);
    }

    /** Whether a template with this name exists. */
    public boolean has(String name) {
        return templates.containsKey(name);
    }

    /**
     * @throws IllegalArgumentException for an unknown template, a placeholder without a value, or a
     *     link in the subject
     */
    public RenderedEmail render(String name, Map<String, TemplateValue> values) {
        Template template = templates.get(name);
        if (template == null) {
            throw new IllegalArgumentException("no e-mail template " + name);
        }
        String subject = fill(template.subject(), values, true);
        String text = fill(template.body(), values, false);
        requireAllowedLinks(name, text);
        return new RenderedEmail(subject, text, html(text));
    }

    private static Template parse(String name, String source) {
        String normalised = source.replace("\r\n", "\n").replace('\r', '\n');
        int firstBreak = normalised.indexOf('\n');
        if (!normalised.startsWith(SUBJECT_PREFIX) || firstBreak < 0) {
            throw invalid(name, "the first line must be the subject");
        }
        String subject = normalised.substring(SUBJECT_PREFIX.length(), firstBreak).strip();
        String body = normalised.substring(firstBreak + 1).strip();
        if (subject.isEmpty() || body.isEmpty()) {
            throw invalid(name, "the subject and the body must not be empty");
        }
        if (URL.matcher(subject).find()) {
            throw invalid(name, "the subject must not hold a link");
        }
        Matcher url = URL.matcher(body);
        while (url.find()) {
            if (!TemplateValue.isAllowed(url.group())) {
                throw invalid(name, "the body links outside the allowed hosts");
            }
        }
        if (PLACEHOLDER.matcher(subject + body).replaceAll("").matches("(?s).*[{}].*")) {
            throw invalid(name, "a brace outside a placeholder");
        }
        return new Template(subject, body);
    }

    private static IllegalStateException invalid(String name, String why) {
        return new IllegalStateException("e-mail template " + name + " is invalid: " + why);
    }

    private static String fill(String source, Map<String, TemplateValue> values, boolean subject) {
        Matcher placeholder = PLACEHOLDER.matcher(source);
        StringBuilder out = new StringBuilder(source.length() + 64);
        while (placeholder.find()) {
            String key = placeholder.group(1);
            TemplateValue value = values.get(key);
            if (value == null) {
                throw new IllegalArgumentException("no value for placeholder " + key);
            }
            if (subject && value instanceof TemplateValue.Link) {
                throw new IllegalArgumentException("a link never goes in a subject (placeholder " + key + ")");
            }
            placeholder.appendReplacement(out, Matcher.quoteReplacement(value.rendered()));
        }
        placeholder.appendTail(out);
        return out.toString();
    }

    /** A second line of defence: whatever the values were, the text links only to allowed hosts. */
    private static void requireAllowedLinks(String name, String text) {
        Matcher url = URL.matcher(text);
        while (url.find()) {
            if (!TemplateValue.isAllowed(url.group())) {
                throw new IllegalStateException("e-mail " + name + " would link outside the allowed hosts");
            }
        }
    }

    private static String html(String text) {
        StringBuilder out = new StringBuilder(text.length() * 2 + 128);
        out.append("<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"></head><body>");
        for (String paragraph : PARAGRAPH_BREAK.split(text)) {
            out.append("\n<p>");
            String[] lines = paragraph.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    out.append("<br>");
                }
                appendLinkified(out, lines[i]);
            }
            out.append("</p>");
        }
        return out.append("\n</body></html>").toString();
    }

    private static void appendLinkified(StringBuilder out, String line) {
        Matcher url = URL.matcher(line);
        int last = 0;
        while (url.find()) {
            String link = TRAILING_PUNCTUATION.matcher(url.group()).replaceAll("");
            out.append(escape(line.substring(last, url.start())));
            if (TemplateValue.isAllowed(link)) {
                String escaped = escape(link);
                out.append("<a href=\"").append(escaped).append("\">").append(escaped).append("</a>");
            } else {
                out.append(escape(link));
            }
            last = url.start() + link.length();
        }
        out.append(escape(line.substring(last)));
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
