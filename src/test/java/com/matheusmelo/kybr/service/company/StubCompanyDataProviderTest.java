package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.Regime;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Found;
import com.matheusmelo.kybr.service.company.CompanyDataResult.NotFound;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unavailable;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unsupported;
import com.matheusmelo.kybr.util.Cnpj;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class StubCompanyDataProviderTest {

    /** 02:30 UTC is 23:30 the day before in São Paulo: opening-date offsets count São Paulo days. */
    private static final Instant NOW = Instant.parse("2026-11-08T02:30:00Z");
    private static final LocalDate TODAY_SAO_PAULO = LocalDate.of(2026, 11, 7);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final List<String> STUB_CNPJS = List.of("12345678000195", "98765432000198", "11222333000181",
            "12ABC34501DE35", "36925814000184", "47158263000197", "71438562000160", "58293614000199",
            "25873916000187", "52000001000130", "52000013000164", "52000014000109", "52000012000110",
            "52000002000184", "52000003000129", "52000004000173", "52000005000118", "52000006000162",
            "52000007000107", "52000010000120", "52000011000175", "52000015000153", "52000016000106",
            "5200A000000100");

    /** The only CPF-shaped values the fixtures may hold: well-known synthetic test numbers. */
    private static final Set<String> SYNTHETIC_CPFS = Set.of("12345678909");
    private static final Pattern CPF_SHAPED =
            Pattern.compile("(?<![0-9])[0-9]{3}[.\\- ]?[0-9]{3}[.\\- ]?[0-9]{3}[.\\- ]?[0-9]{2}(?![0-9])");

    private final StubCompanyDataProvider stub =
            new StubCompanyDataProvider(Duration.ofMillis(200), Duration.ofMillis(2300), CLOCK);

    @Test
    void hasOneFixturePerCatalogueCnpjAndNoOther() throws IOException {
        assertThat(STUB_CNPJS).allMatch(Cnpj::isValidCanonical);
        List<String> files = new ArrayList<>();
        for (Resource resource : fixtures()) {
            files.add(resource.getFilename().replace(".json", ""));
        }
        assertThat(files).containsExactlyInAnyOrderElementsOf(STUB_CNPJS);
    }

    @Test
    void everyFixtureEmbedsTheBodySentinel() throws IOException {
        for (Resource resource : fixtures()) {
            assertThat(read(resource)).as(resource.getFilename()).contains("qa-stub-body-sentinel");
        }
    }

    @Test
    void everyCpfShapedValueInTheFixturesIsFromTheSyntheticSet() throws IOException {
        for (Resource resource : fixtures()) {
            Matcher run = CPF_SHAPED.matcher(read(resource));
            while (run.find()) {
                assertThat(run.group().replaceAll("[^0-9]", "")).as(resource.getFilename()).isIn(SYNTHETIC_CPFS);
            }
        }
    }

    @Test
    void approvedCompanyMapsBothCallsWithoutLeakingTheSentinel() {
        CompanyDataResult<BasicCompany> basic = stub.basic("12345678000195");
        CompanyDataResult<CompanyRecord> full = stub.fullCheck("12345678000195");

        assertThat(basic).isInstanceOf(Found.class);
        BasicCompany company = ((Found<BasicCompany>) basic).company();
        assertThat(company.razaoSocial()).isNotBlank();
        assertThat(company.municipio()).isNotBlank();
        assertThat(company.uf()).matches("[A-Z]{2}");
        assertThat(company.cnaeDescricao()).isNotBlank();
        assertThat(company.toString()).doesNotContain("qa-stub-body-sentinel");

        CompanyRecord record = ((Found<CompanyRecord>) full).company();
        assertThat(record.situacao()).isEqualTo(SituacaoCadastral.ativa);
        assertThat(record.regime()).isEqualTo(Regime.OUTRO);
        assertThat(record.cnaeCodigo()).matches("[0-9]{7}");
        assertThat(record.qsaNomes()).containsExactly("Maria Exemplo Teste");
        assertThat(record.inscricoesEstaduais()).containsExactly(new CompanyRecord.InscricaoEstadual(record.uf(), true));
        assertThat(record.dataAbertura()).isBefore(TODAY_SAO_PAULO.minusYears(5));
    }

    @Test
    void alphanumericCnpjIsSupportedByTheStub() {
        assertThat(stub.basic("12ABC34501DE35")).isInstanceOf(Found.class);
        assertThat(stub.fullCheck("12ABC34501DE35")).isInstanceOf(Found.class);
    }

    @Test
    void longNameCompanyKeepsItsFullName() {
        BasicCompany company = ((Found<BasicCompany>) stub.basic("58293614000199")).company();
        assertThat(company.razaoSocial()).isEqualTo("Comercial Exemplo de Peças Automotivas do Interior Ltda.");
    }

    @ParameterizedTest
    @CsvSource({"52000001000130, 30", "52000013000164, 179", "52000014000109, 180", "52000012000110, 0",
        "52000006000162, 10", "52000007000107, 60"})
    void openingDatesAreOffsetsFromTheClockInSaoPauloDays(String cnpj, int daysAgo) {
        CompanyRecord record = ((Found<CompanyRecord>) stub.fullCheck(cnpj)).company();
        assertThat(record.dataAbertura()).isEqualTo(TODAY_SAO_PAULO.minusDays(daysAgo));
    }

    @Test
    void openingDatesFollowTheClock() {
        Clock later = Clock.fixed(NOW.plus(Duration.ofDays(400)), ZoneId.of("UTC"));
        StubCompanyDataProvider laterStub = new StubCompanyDataProvider(Duration.ofMillis(200),
                Duration.ofMillis(2300), later);
        CompanyRecord record = ((Found<CompanyRecord>) laterStub.fullCheck("52000001000130")).company();
        assertThat(record.dataAbertura()).isEqualTo(TODAY_SAO_PAULO.plusDays(400).minusDays(30));
    }

    @ParameterizedTest
    @CsvSource({"11222333000181, inapta", "52000002000184, suspensa", "52000003000129, baixada",
        "52000004000173, nula", "52000006000162, inapta"})
    void irregularRegistrationsMapToTheirSituacao(String cnpj, SituacaoCadastral situacao) {
        assertThat(((Found<CompanyRecord>) stub.fullCheck(cnpj)).company().situacao()).isEqualTo(situacao);
    }

    @Test
    void microEntrepreneursHaveNoPartnersAndTheirNameInTheRazaoSocial() {
        CompanyRecord masked = ((Found<CompanyRecord>) stub.fullCheck("36925814000184")).company();
        CompanyRecord unmasked = ((Found<CompanyRecord>) stub.fullCheck("47158263000197")).company();

        assertThat(masked.regime()).isEqualTo(Regime.MEI);
        assertThat(masked.qsaNomes()).isEmpty();
        assertThat(masked.razaoSocial()).isEqualTo("JOAO CARLOS SILVA ***.***.***-**");
        assertThat(unmasked.regime()).isEqualTo(Regime.MEI);
        assertThat(unmasked.razaoSocial()).isEqualTo("JOAO CARLOS SILVA 12345678909");
    }

    @Test
    void partnerScenariosCarryTheirPartnerNames() {
        assertThat(((Found<CompanyRecord>) stub.fullCheck("98765432000198")).company().qsaNomes())
                .containsExactly("Maria Aparecida Exemplo Teste");
        assertThat(((Found<CompanyRecord>) stub.fullCheck("52000005000118")).company().qsaNomes())
                .containsExactly("Roberto Ficticio Nunes");
    }

    @Test
    void inactiveStateRegistrationIsReportedInactive() {
        CompanyRecord record = ((Found<CompanyRecord>) stub.fullCheck("52000010000120")).company();
        assertThat(record.inscricoesEstaduais()).extracting(CompanyRecord.InscricaoEstadual::ativa)
                .containsExactly(false);
    }

    @Test
    void incompleteRecordComesBackWithTheMissingFieldsNull() {
        BasicCompany company = ((Found<BasicCompany>) stub.basic("52000011000175")).company();
        assertThat(company.razaoSocial()).isNotBlank();
        assertThat(company.municipio()).isNull();
        assertThat(company.cnaeDescricao()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"71438562000160", "45997418000153"})
    void notFoundAndUnknownCnpjsAreNotFound(String cnpj) {
        assertThat(stub.basic(cnpj)).isInstanceOf(NotFound.class);
        assertThat(stub.fullCheck(cnpj)).isInstanceOf(NotFound.class);
    }

    @Test
    void unavailableAndUnsupportedScenariosAnswerTheirOutcome() {
        assertThat(stub.basic("52000015000153")).isInstanceOf(Unavailable.class);
        assertThat(stub.fullCheck("52000015000153")).isInstanceOf(Unavailable.class);
        assertThat(stub.basic("5200A000000100")).isInstanceOf(Unsupported.class);
        assertThat(stub.fullCheck("5200A000000100")).isInstanceOf(Unsupported.class);
    }

    @Test
    void timeoutScenarioWaitsOutEachReadTimeoutThenIsUnavailable() {
        long start = System.nanoTime();
        assertThat(stub.basic("52000016000106")).isInstanceOf(Unavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(200));

        StubCompanyDataProvider quick = new StubCompanyDataProvider(Duration.ofMillis(50), Duration.ofMillis(300),
                CLOCK);
        start = System.nanoTime();
        assertThat(quick.fullCheck("52000016000106")).isInstanceOf(Unavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void slowScenarioApprovesTwoSecondsInsideTheWorkerReadTimeout() {
        long start = System.nanoTime();
        assertThat(stub.fullCheck("25873916000187")).isInstanceOf(Found.class);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);
        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(300)).isLessThan(Duration.ofMillis(2300));
        assertThat(stub.basic("25873916000187")).isInstanceOf(Found.class);
    }

    private static Resource[] fixtures() throws IOException {
        return new PathMatchingResourcePatternResolver().getResources("classpath:provider-fixtures/*.json");
    }

    private static String read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
