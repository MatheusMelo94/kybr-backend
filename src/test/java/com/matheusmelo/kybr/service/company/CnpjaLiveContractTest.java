package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.service.company.CompanyDataResult.Found;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Opt-in check of the live provider's response shape. Skipped unless both
 * {@code KYBR_CNPJA_API_KEY} and {@code KYBR_CNPJA_CONTRACT_CNPJ} are set, so it never runs in CI.
 *
 * <p>It asserts shape only. It never prints, logs or writes a response body or a field value, and
 * it fails if anything body-like reaches the captured output (test reports keep that output).
 */
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "KYBR_CNPJA_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "KYBR_CNPJA_CONTRACT_CNPJ", matches = "[0-9A-Z]{14}")
class CnpjaLiveContractTest {

    private final CnpjaCompanyDataProvider provider = new CnpjaCompanyDataProvider(
            new CnpjaCompanyDataProvider.Settings(URI.create("https://api.cnpja.com"),
                    System.getenv("KYBR_CNPJA_API_KEY"), "Authorization", "CACHE_IF_FRESH", 45, true, "BR",
                    Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(8)));
    private final String cnpj = System.getenv("KYBR_CNPJA_CONTRACT_CNPJ");

    @Test
    void basicAndFullCheckHaveTheFieldsKybrMaps(CapturedOutput output) {
        CompanyDataResult<BasicCompany> basic = provider.basic(cnpj);
        CompanyDataResult<CompanyRecord> full = provider.fullCheck(cnpj);

        assertThat(basic).as("basic outcome type").isInstanceOf(Found.class);
        assertThat(full).as("full check outcome type").isInstanceOf(Found.class);
        BasicCompany company = ((Found<BasicCompany>) basic).company();
        CompanyRecord record = ((Found<CompanyRecord>) full).company();
        assertThat(company.razaoSocial() != null && company.municipio() != null && company.uf() != null
                && company.cnaeDescricao() != null).as("basic fields present").isTrue();
        assertThat(record.situacao() != null && record.dataAbertura() != null && record.regime() != null
                && record.cnaeCodigo() != null).as("full check fields present").isTrue();
        assertThat(output.getAll()).as("no body or value reaches the output").doesNotContain(cnpj)
                .doesNotContain("taxId").doesNotContain(company.razaoSocial());
    }
}
