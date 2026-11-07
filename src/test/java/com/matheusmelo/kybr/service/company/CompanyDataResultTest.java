package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.service.company.CompanyDataResult.Found;
import com.matheusmelo.kybr.service.company.CompanyDataResult.NotFound;
import com.matheusmelo.kybr.service.company.CompanyDataResult.RateLimited;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unavailable;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unsupported;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CompanyDataResultTest {

    @Test
    void eachOutcomeHasItsProviderCallTag() {
        assertThat(new Found<>("x").resultado()).isEqualTo("found");
        assertThat(new NotFound<>().resultado()).isEqualTo("not_found");
        assertThat(new Unsupported<>().resultado()).isEqualTo("unsupported");
        assertThat(new RateLimited<>(Duration.ofSeconds(1)).resultado()).isEqualTo("rate_limited");
        assertThat(new Unavailable<>().resultado()).isEqualTo("error");
    }
}
