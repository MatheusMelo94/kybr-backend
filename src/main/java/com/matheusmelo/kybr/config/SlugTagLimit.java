package com.matheusmelo.kybr.config;

import com.matheusmelo.kybr.service.MetricTags;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caps the distinct values of the {@code slug} tag across every meter. Slug tags already come only
 * from loaded workspaces ({@link MetricTags}); this is the backstop: past {@code max} distinct
 * values, a new slug is tagged {@value #OVERFLOW}, with one WARN for the process's lifetime.
 * {@link MetricTags#UNKNOWN} and {@value #OVERFLOW} never count against the cap.
 */
public class SlugTagLimit implements MeterFilter {

    public static final String OVERFLOW = "_overflow";

    private static final Logger log = LoggerFactory.getLogger(SlugTagLimit.class);

    private final int max;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean warned = new AtomicBoolean();

    public SlugTagLimit(int max) {
        this.max = max;
    }

    @Override
    public Meter.Id map(Meter.Id id) {
        String slug = id.getTag(MetricTags.SLUG);
        if (slug == null || slug.equals(MetricTags.UNKNOWN) || slug.equals(OVERFLOW) || admit(slug)) {
            return id;
        }
        if (warned.compareAndSet(false, true)) {
            log.warn("event=metric_slug_overflow max={} (further slugs are tagged {})", max, OVERFLOW);
        }
        Iterable<Tag> tags = StreamSupport.stream(id.getTagsAsIterable().spliterator(), false)
                .map(tag -> tag.getKey().equals(MetricTags.SLUG) ? Tag.of(MetricTags.SLUG, OVERFLOW) : tag)
                .toList();
        return id.replaceTags(tags);
    }

    private boolean admit(String slug) {
        if (seen.contains(slug)) {
            return true;
        }
        synchronized (seen) {
            if (seen.size() >= max) {
                return seen.contains(slug);
            }
            seen.add(slug);
            return true;
        }
    }
}
