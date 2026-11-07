package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.model.link.ProjecaoEmpresa;
import com.matheusmelo.kybr.model.workspace.Plano;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Found;
import com.matheusmelo.kybr.service.company.CompanyDataResult.NotFound;
import com.matheusmelo.kybr.service.company.CompanyDataResult.RateLimited;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unavailable;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unsupported;
import com.matheusmelo.kybr.service.company.CompanyLookupService.CacheOnlyMiss;
import com.matheusmelo.kybr.service.company.CompanyLookupService.Disabled;
import com.matheusmelo.kybr.service.company.CompanyLookupService.Result;
import com.matheusmelo.kybr.service.company.ProviderBudgets.Budget;
import com.matheusmelo.kybr.service.company.ProviderBudgets.Decision;
import com.matheusmelo.kybr.support.link.FakeCompanyDataProvider;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CachedCompanyLookupServiceTest {

    private static final String CNPJ = "12345678000195";
    private static final ProjecaoEmpresa MASKED =
            new ProjecaoEmpresa("JOAO CARLOS SILVA ***.***.***-**", "Belo Horizonte", "MG", "Comercio varejista");
    private static final BasicCompany COMPANY =
            new BasicCompany("JOAO CARLOS SILVA 12345678909", "Belo Horizonte", "MG", "Comercio varejista");

    private final RuntimeFlagsService flags = mock(RuntimeFlagsService.class);
    private final CompanyCache cache = mock(CompanyCache.class);
    private final ProviderBudgets budgets = mock(ProviderBudgets.class);
    private final FakeCompanyDataProvider provider = new FakeCompanyDataProvider();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CachedCompanyLookupService service =
            new CachedCompanyLookupService(flags, cache, budgets, provider, meters);
    private final Workspace workspace = TestWorkspaces.builder().slug("loja-exemplo").build();

    @BeforeEach
    void everythingOnAndNothingCached() {
        when(flags.autofillOn()).thenReturn(true);
        when(cache.read(anyString())).thenReturn(new CompanyCache.Lookup.Miss());
        when(budgets.canSpendD2(anyString())).thenReturn(Decision.ALLOWED);
    }

    @Test
    void autofillIsDisabledWhenSwitchedOffGloballyOrForTheWorkspace() {
        assertThat(service.autofillEnabled(workspace)).isTrue();
        assertThat(service.autofillEnabled(TestWorkspaces.builder().autofillAtivo(false).build())).isFalse();
        when(flags.autofillOn()).thenReturn(false);
        assertThat(service.autofillEnabled(workspace)).isFalse();
    }

    @Test
    void aSwitchedOffLookupIsDisabledWithoutTouchingTheCacheOrTheProvider() {
        when(flags.autofillOn()).thenReturn(false);

        assertThat(service.lookup(workspace, CNPJ)).isEqualTo(new Disabled());

        verify(cache, never()).read(anyString());
        assertThat(provider.basicCalls(CNPJ)).isZero();
        assertThat(lookups("disabled")).isEqualTo(1);
    }

    @Test
    void aCacheHitAnswersWithoutCallingTheProviderOrSpendingCredits() {
        when(cache.read(CNPJ)).thenReturn(new CompanyCache.Lookup.Hit(MASKED));

        assertThat(service.lookup(workspace, CNPJ)).isEqualTo(new CompanyLookupService.Found(MASKED));

        assertThat(provider.basicCalls(CNPJ)).isZero();
        verify(budgets, never()).bookD2(anyString());
        verify(budgets).recordCacheRead("loja-exemplo");
        assertThat(lookups("hit")).isEqualTo(1);
        assertThat(meters.get("kybr.link.lookup.cache_reads").tag("slug", "loja-exemplo").counter().count())
                .isEqualTo(1);
    }

    @Test
    void aNegativeCacheHitIsNotFound() {
        when(cache.read(CNPJ)).thenReturn(new CompanyCache.Lookup.NegativeHit());

        assertThat(service.lookup(workspace, CNPJ)).isEqualTo(new CompanyLookupService.NotFound());

        assertThat(provider.basicCalls(CNPJ)).isZero();
        verify(budgets).recordCacheRead("loja-exemplo");
        assertThat(lookups("not_found")).isEqualTo(1);
    }

    @Test
    void aTrialWorkspaceMissIsCacheOnly() {
        Workspace trial = TestWorkspaces.builder().slug("loja-teste").plano(Plano.Tipo.teste).build();

        assertThat(service.lookup(trial, CNPJ)).isEqualTo(new CacheOnlyMiss());

        assertThat(provider.basicCalls(CNPJ)).isZero();
        assertThat(meters.get("kybr.link.lookup").tag("slug", "loja-teste").tag("resultado", "cache_only_miss")
                .counter().count()).isEqualTo(1);
    }

    static Stream<Arguments> spentBudgets() {
        return Stream.of(Arguments.of(Decision.exhausted(Budget.D2_SLUG)), Arguments.of(Decision.exhausted(Budget.D2_DAILY)),
                Arguments.of(Decision.exhausted(Budget.D2_SHARE)), Arguments.of(Decision.exhausted(Budget.MONTHLY)),
                Arguments.of(Decision.SPEND_OFF));
    }

    @ParameterizedTest
    @MethodSource("spentBudgets")
    void aMissWithASpentBudgetOrSpendOffIsCacheOnly(Decision decision) {
        when(budgets.canSpendD2("loja-exemplo")).thenReturn(decision);

        assertThat(service.lookup(workspace, CNPJ)).isEqualTo(new CacheOnlyMiss());

        assertThat(provider.basicCalls(CNPJ)).isZero();
        assertThat(lookups("cache_only_miss")).isEqualTo(1);
    }

    @Test
    void aMissWhenTheProvidersPerMinuteGateIsFullIsCacheOnlyWithoutACall() {
        when(budgets.acquireProviderCall()).thenReturn(Optional.of(Duration.ofSeconds(20)));

        assertThat(service.lookup(workspace, CNPJ)).isEqualTo(new CacheOnlyMiss());

        assertThat(provider.basicCalls(CNPJ)).isZero();
        assertThat(lookups("cache_only_miss")).isEqualTo(1);
        verify(budgets, never()).bookD2(anyString());
    }

    @Test
    void aFreshCallTakesASlotOfTheProvidersPerMinuteGate() {
        provider.scriptBasic(CNPJ, new CompanyDataResult.Found<>(FakeCompanyDataProvider.basicCompany()));

        service.lookup(workspace, CNPJ);

        verify(budgets).acquireProviderCall();
    }

    @Test
    void aFreshFoundIsCachedReturnedMaskedAndBooked() {
        provider.scriptBasic(CNPJ, new Found<>(COMPANY));
        when(cache.storeFound(CNPJ, COMPANY)).thenReturn(Optional.of(MASKED));

        assertThat(service.lookup(workspace, CNPJ)).isEqualTo(new CompanyLookupService.Found(MASKED));

        verify(cache).storeFound(CNPJ, COMPANY);
        verify(budgets).bookD2("loja-exemplo");
        assertThat(lookups("fresh")).isEqualTo(1);
    }

    @Test
    void aFreshNotFoundIsNegativelyCachedAndBooked() {
        provider.scriptBasic(CNPJ, new NotFound<>());

        assertThat(service.lookup(workspace, CNPJ)).isEqualTo(new CompanyLookupService.NotFound());

        verify(cache).storeNotFound(CNPJ);
        verify(budgets).bookD2("loja-exemplo");
        assertThat(lookups("not_found")).isEqualTo(1);
    }

    @Test
    void anIncompleteRecordIsUnavailableAndNotCached(CapturedOutput output) {
        BasicCompany incomplete = new BasicCompany("Loja Exemplo Ltda", null, "SP", "Comercio");
        provider.scriptBasic(CNPJ, new Found<>(incomplete));
        when(cache.storeFound(CNPJ, incomplete)).thenReturn(Optional.empty());

        assertThat(service.lookup(workspace, CNPJ)).isEqualTo(new CompanyLookupService.Unavailable());

        assertThat(lookups("error")).isEqualTo(1);
        assertThat(output.getAll()).contains("event=provider_incomplete_record").doesNotContain(CNPJ);
    }

    @Test
    void anUnsupportedCnpjIsUnavailableWithAnEvent(CapturedOutput output) {
        provider.scriptBasic("12ABC34501DE35", new Unsupported<>());

        assertThat(service.lookup(workspace, "12ABC34501DE35")).isEqualTo(new CompanyLookupService.Unavailable());

        verify(cache, never()).storeNotFound(anyString());
        verify(budgets, never()).bookD2(anyString());
        assertThat(output.getAll()).contains("event=provider_unsupported_cnpj").doesNotContain("12ABC34501DE35");
    }

    @Test
    void anUnavailableOrRateLimitedProviderIsUnavailableAndNothingIsCached() {
        provider.scriptBasic(CNPJ, new Unavailable<>(), new RateLimited<>(Duration.ofSeconds(5)));

        assertThat(service.lookup(workspace, CNPJ)).isEqualTo(new CompanyLookupService.Unavailable());
        assertThat(service.lookup(workspace, CNPJ)).isEqualTo(new CompanyLookupService.Unavailable());

        verify(cache, never()).storeFound(anyString(), any());
        verify(cache, never()).storeNotFound(anyString());
        verify(budgets, never()).bookD2(anyString());
        assertThat(lookups("error")).isEqualTo(2);
    }

    @Test
    void theSlugTagIsTheLoadedWorkspacesOrUnknown() {
        Workspace malformed = TestWorkspaces.builder().slug("Not A Slug").build();
        when(flags.autofillOn()).thenReturn(false);

        Result result = service.lookup(malformed, CNPJ);

        assertThat(result).isEqualTo(new Disabled());
        assertThat(meters.get("kybr.link.lookup").tag("slug", "_unknown").tag("resultado", "disabled").counter()
                .count()).isEqualTo(1);
    }

    private double lookups(String resultado) {
        var counter = meters.find("kybr.link.lookup").tag("slug", "loja-exemplo").tag("resultado", resultado).counter();
        return counter == null ? 0 : counter.count();
    }
}
