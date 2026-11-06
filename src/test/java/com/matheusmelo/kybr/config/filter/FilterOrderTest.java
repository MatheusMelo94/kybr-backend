package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;

/** The request pipeline order is part of the design. */
class FilterOrderTest {

    private static final List<Integer> PIPELINE = List.of(
            FilterOrder.REQUEST_ID,
            FilterOrder.RESPONSE_GUARD,
            FilterOrder.ACCESS_LOG,
            FilterOrder.PATH_FIREWALL,
            FilterOrder.BODY_SIZE_LIMIT,
            FilterOrder.BFF_KEY,
            FilterOrder.CLIENT_IP,
            FilterOrder.CACHED_BODY);

    @Test
    void filtersRunInPipelineOrder() {
        assertThat(PIPELINE).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void pathFirewallIsFourth() {
        assertThat(PIPELINE.indexOf(FilterOrder.PATH_FIREWALL)).isEqualTo(3);
    }

    @Test
    void everyFilterRunsBeforeSpringSecurity() {
        assertThat(FilterOrder.CACHED_BODY).isLessThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER);
    }

    @Test
    void onlyTheOuterThreeAlsoRunOnTheErrorDispatch() {
        assertThat(PIPELINE.stream().filter(FilterOrder::runsOnErrorDispatch))
                .containsExactly(FilterOrder.REQUEST_ID, FilterOrder.RESPONSE_GUARD, FilterOrder.ACCESS_LOG);
    }
}
