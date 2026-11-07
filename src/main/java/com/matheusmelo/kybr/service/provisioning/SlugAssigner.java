package com.matheusmelo.kybr.service.provisioning;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Workspace slugs. An assigned slug is the name slugified (ASCII-folded, lower case, runs of other
 * characters as one {@code -}, at most 50 characters) plus {@code -} and 6 random base32 characters
 * ({@code a-z2-7}). A name or slug is reserved when any of its slug words is on the reserved list
 * ({@code reserved-names.txt}).
 */
@Component
public class SlugAssigner {

    static final int BASE_MAX = 50;
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Supplier<String> suffixes;
    private final Set<String> reserved;

    public SlugAssigner() {
        this(SlugAssigner::randomSuffix);
    }

    SlugAssigner(Supplier<String> suffixes) {
        this.suffixes = suffixes;
        this.reserved = load();
    }

    /** A new candidate slug for {@code nome}; each call has a fresh suffix. */
    public String assign(String nome) {
        String base = slugify(nome);
        return (base.isEmpty() ? "loja" : base) + "-" + suffixes.get();
    }

    /** True when any slug word of {@code text} is reserved. */
    public boolean isReserved(String text) {
        return Arrays.stream(slugify(text).split("-")).anyMatch(reserved::contains);
    }

    static String slugify(String text) {
        String folded = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        String slug = NON_SLUG.matcher(folded).replaceAll("-").replaceAll("^-+|-+$", "");
        if (slug.length() > BASE_MAX) {
            slug = slug.substring(0, BASE_MAX).replaceAll("-+$", "");
        }
        return slug;
    }

    private static String randomSuffix() {
        char[] suffix = new char[6];
        for (int i = 0; i < suffix.length; i++) {
            suffix[i] = BASE32[RANDOM.nextInt(BASE32.length)];
        }
        return new String(suffix);
    }

    private static Set<String> load() {
        try (InputStream in = new ClassPathResource("reserved-names.txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException("reserved-names.txt could not be read", e);
        }
    }
}
