package com.matheusmelo.kybr.acceptance.upload.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.service.email.EmailMessage;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;

/**
 * The rules every e-mail Kybr sends must follow, checked on a recorded message: a plain-text part
 * and an HTML part saying the same words; no image and no tracking (every link goes to a Kybr host,
 * with no query string); and none of the given values (tokens, raw addresses, IPs) anywhere.
 */
public final class EmailRules {

    /** The hosts an e-mail may link to: the seller link and the panel. */
    public static final Set<String> LINK_HOSTS = Set.of("kybr.com.br", "app.kybr.com.br");

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HREF = Pattern.compile("(?i)\\bhref\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://[^\\s<>\"]+");

    private EmailRules() {
    }

    public static void assertFollowsTheRules(EmailMessage message, String... neverPresent) {
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(message.text()).as("plain-text part").isNotBlank();
        softly.assertThat(message.html()).as("HTML part").isNotBlank();
        softly.assertThat(words(htmlToText(message.html()))).as("the HTML says the same words as the text")
                .isEqualTo(words(message.text()));
        softly.assertThat(message.html()).as("no image").doesNotContainIgnoringCase("<img")
                .doesNotContainIgnoringCase("background=").doesNotContainIgnoringCase("url(");
        for (String link : links(message)) {
            URI uri = URI.create(link);
            softly.assertThat(uri.getScheme()).as("link scheme: " + link).isEqualTo("https");
            softly.assertThat(LINK_HOSTS).as("link host: " + link).contains(uri.getHost());
            softly.assertThat(uri.getRawQuery()).as("no query string (no token, no tracking): " + link).isNull();
        }
        String everything = message.subject() + "\n" + message.text() + "\n" + message.html();
        for (String value : neverPresent) {
            softly.assertThat(everything).as("never in the e-mail").doesNotContain(value);
        }
        softly.assertAll();
    }

    /** Every link in the message: HTML {@code href}s and URLs written out in either part. */
    public static List<String> links(EmailMessage message) {
        List<String> links = new ArrayList<>();
        Matcher href = HREF.matcher(message.html());
        while (href.find()) {
            links.add(unescape(href.group(1)));
        }
        for (String part : List.of(message.text(), htmlToText(message.html()), message.subject())) {
            Matcher url = URL.matcher(part);
            while (url.find()) {
                links.add(url.group());
            }
        }
        return links;
    }

    static String htmlToText(String html) {
        return unescape(TAG.matcher(html).replaceAll(" "));
    }

    private static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&#x27;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    private static List<String> words(String text) {
        return List.of(text.strip().split("\\s+"));
    }
}
