package com.matheusmelo.kybr.service;

import com.matheusmelo.kybr.model.workspace.Workspace;

/**
 * Bounded metric tag values. A {@code slug} tag comes only from a workspace loaded from the
 * database, never from a request string, so random slugs in requests can't create tag values.
 */
public final class MetricTags {

    public static final String SLUG = "slug";
    public static final String UNKNOWN = "_unknown";

    private MetricTags() {
    }

    /** The loaded workspace's slug, or {@value #UNKNOWN} when there is none or it's malformed. */
    public static String slug(Workspace workspace) {
        if (workspace == null || workspace.slug() == null
                || !Workspace.SLUG_PATTERN.matcher(workspace.slug()).matches()) {
            return UNKNOWN;
        }
        return workspace.slug();
    }
}
