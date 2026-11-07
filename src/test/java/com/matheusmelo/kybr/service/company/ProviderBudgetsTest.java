package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProviderBudgetsTest {

    @ParameterizedTest
    @CsvSource({
        // monthly, share, burst, month,   lookups/day, verifications/day
        "10000, 0.20, 2.0, 2026-11, 134, 534",
        "10000, 0.20, 2.0, 2026-12, 130, 517",
        "10000, 0.20, 2.0, 2027-02, 143, 572",
        "10, 0.20, 2.0, 2026-11, 1, 1"})
    void dailyGlobalBudgetsDeriveFromTheMonthlyCreditsShareAndBurst(long monthly, double share, double burst,
            String month, long d2Daily, long d3Daily) {
        YearMonth yearMonth = YearMonth.parse(month);

        assertThat(ProviderBudgets.d2Daily(monthly, share, burst, yearMonth)).isEqualTo(d2Daily);
        assertThat(ProviderBudgets.d3Daily(monthly, share, burst, yearMonth)).isEqualTo(d3Daily);
    }
}
