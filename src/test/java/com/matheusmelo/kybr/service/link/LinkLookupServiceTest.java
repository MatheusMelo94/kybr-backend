package com.matheusmelo.kybr.service.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.RateLimitExceededException;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.link.ProjecaoEmpresa;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.company.CompanyLookupService;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The order of checks: kill switch, CNPJ, request limits, then the company lookup. */
class LinkLookupServiceTest {

    private static final ClientIp IP = new ClientIp("198.51.100.7", ClientIp.Source.FORWARDED);
    private static final String CNPJ = "12345678000195";
    private static final ProjecaoEmpresa PROJECAO =
            new ProjecaoEmpresa("Loja Exemplo Ltda", "Sao Paulo", "SP", "Comercio varejista");

    private final CompanyLookupService companies = mock(CompanyLookupService.class);
    private final LinkRateLimits limits = mock(LinkRateLimits.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final LinkLookupService service = new LinkLookupService(companies, limits, meters);
    private final Workspace workspace = TestWorkspaces.builder().build();

    @Test
    void theKillSwitchWinsOverAnInvalidCnpjAndIsCountedAsDisabled() {
        when(companies.autofillEnabled(workspace)).thenReturn(false);

        assertCode(() -> service.lookup(workspace, IP, "not-a-cnpj"), ErrorCode.AUTOFILL_DESATIVADO, 503, null);

        verifyNoInteractions(limits);
        verify(companies, never()).lookup(any(), any());
        assertThat(count("disabled")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345678000196", "12abc34501de35", "12.345.678/0001-95", "1234567800019",
        "12345678000195,98765432000198", "00000000000000"})
    void anInvalidCnpjIs400OnTheCnpjFieldBeforeAnyLimitAndIsCountedAsInvalid(String cnpj) {
        when(companies.autofillEnabled(workspace)).thenReturn(true);

        assertCode(() -> service.lookup(workspace, IP, cnpj), ErrorCode.CNPJ_INVALIDO, 400, "cnpj");

        verifyNoInteractions(limits);
        verify(companies, never()).lookup(any(), any());
        assertThat(count("invalid")).isEqualTo(1);
    }

    @Test
    void aMissingCnpjIsTheSame400() {
        when(companies.autofillEnabled(workspace)).thenReturn(true);

        assertCode(() -> service.lookup(workspace, IP, null), ErrorCode.CNPJ_INVALIDO, 400, "cnpj");
    }

    @Test
    void theRequestLimitPrecedesTheCompanyLookup() {
        when(companies.autofillEnabled(workspace)).thenReturn(true);
        doThrow(new RateLimitExceededException(ErrorCode.LIMITE_CONSULTAS, 30)).when(limits).lookup(IP, workspace);

        assertThatThrownBy(() -> service.lookup(workspace, IP, CNPJ)).isInstanceOf(RateLimitExceededException.class);

        verify(companies, never()).lookup(any(), any());
    }

    @Test
    void aFoundCompanyIsReturned() {
        when(companies.autofillEnabled(workspace)).thenReturn(true);
        when(companies.lookup(workspace, CNPJ)).thenReturn(new CompanyLookupService.Found(PROJECAO));

        assertThat(service.lookup(workspace, IP, CNPJ)).isEqualTo(PROJECAO);
    }

    static Stream<Arguments> failedLookups() {
        return Stream.of(
                Arguments.of(new CompanyLookupService.NotFound(), ErrorCode.EMPRESA_NAO_ENCONTRADA, 404),
                Arguments.of(new CompanyLookupService.Disabled(), ErrorCode.AUTOFILL_DESATIVADO, 503),
                Arguments.of(new CompanyLookupService.CacheOnlyMiss(), ErrorCode.AUTOFILL_DESATIVADO, 503),
                Arguments.of(new CompanyLookupService.Unavailable(), ErrorCode.INDISPONIVEL, 503));
    }

    @ParameterizedTest
    @MethodSource("failedLookups")
    void eachLookupOutcomeHasItsCodeAndTheLookupServiceAloneCountsIt(CompanyLookupService.Result result,
            ErrorCode code, int status) {
        when(companies.autofillEnabled(workspace)).thenReturn(true);
        when(companies.lookup(workspace, CNPJ)).thenReturn(result);

        assertCode(() -> service.lookup(workspace, IP, CNPJ), code, status, null);

        assertThat(meters.find(LinkLookupService.METRIC).counters()).isEmpty();
    }

    private double count(String resultado) {
        return meters.get(LinkLookupService.METRIC).tag("slug", workspace.slug()).tag("resultado", resultado)
                .counter().count();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code,
            int status, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.status().value()).isEqualTo(status);
            assertThat(e.field()).isEqualTo(field);
        });
    }
}
