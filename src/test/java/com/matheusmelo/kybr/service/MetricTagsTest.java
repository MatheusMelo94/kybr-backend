package com.matheusmelo.kybr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.link.TestWorkspaces;
import org.junit.jupiter.api.Test;

/** The slug metric tag comes only from a loaded workspace, so request input can't grow its values. */
class MetricTagsTest {

    @Test
    void aLoadedWorkspaceIsTaggedWithItsSlug() {
        assertThat(MetricTags.slug(TestWorkspaces.builder().slug("loja-exemplo").build())).isEqualTo("loja-exemplo");
    }

    @Test
    void noWorkspaceIsTaggedUnknown() {
        assertThat(MetricTags.slug(null)).isEqualTo(MetricTags.UNKNOWN).isEqualTo("_unknown");
    }

    @Test
    void aWorkspaceWithAnOffPatternSlugIsTaggedUnknown() {
        assertThat(MetricTags.slug(TestWorkspaces.builder().slug("Loja Exemplo!").build()))
                .isEqualTo(MetricTags.UNKNOWN);
    }
}
