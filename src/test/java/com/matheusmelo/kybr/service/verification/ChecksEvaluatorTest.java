package com.matheusmelo.kybr.service.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.ChaveChecagem;
import com.matheusmelo.kybr.model.link.Checagem;
import com.matheusmelo.kybr.model.link.Regime;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import com.matheusmelo.kybr.model.link.StatusChecagem;
import com.matheusmelo.kybr.service.company.CompanyRecord;
import com.matheusmelo.kybr.service.company.CompanyRecord.InscricaoEstadual;
import com.matheusmelo.kybr.service.verification.ChecksEvaluator.Evaluation;
import com.matheusmelo.kybr.util.QsaMatcher.Match;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChecksEvaluatorTest {

    /** 23:30 on 7 November in São Paulo. */
    private static final Instant NOW = Instant.parse("2026-11-08T02:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);
    private static final String PARTNER = "Maria Exemplo Teste";

    private final ChecksEvaluator evaluator = new ChecksEvaluator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void anActiveOldCompanyWithAnExactPartnerPassesEveryCheck() {
        Evaluation evaluation = evaluator.evaluate(company(SituacaoCadastral.ativa, TODAY.minusYears(10), Regime.OUTRO,
                List.of(new InscricaoEstadual("SP", true)), List.of(PARTNER)), PARTNER, 180);

        assertThat(evaluation.checagens()).extracting(Checagem::chave).containsExactly(ChaveChecagem.situacao,
                ChaveChecagem.qsa, ChaveChecagem.idade, ChaveChecagem.cnae, ChaveChecagem.ie, ChaveChecagem.regime);
        assertThat(evaluation.checagens()).extracting(Checagem::status).containsOnly(StatusChecagem.ok);
        assertThat(evaluation.qsa()).isEqualTo(Match.EXACT);
        assertThat(evaluation.recente()).isFalse();
        assertThat(evaluation.mei()).isFalse();
        assertThat(evaluation.ieAtivaUf()).isEqualTo("SP");
    }

    @Test
    void anIrregularStatusFailsTheStatusCheckWithItsValue() {
        Evaluation evaluation = evaluator.evaluate(company(SituacaoCadastral.inapta, TODAY.minusYears(10), Regime.OUTRO,
                List.of(), List.of(PARTNER)), PARTNER, 180);

        Checagem situacao = byKey(evaluation).get(ChaveChecagem.situacao);
        assertThat(situacao.status()).isEqualTo(StatusChecagem.bad);
        assertThat(situacao.valor()).isEqualTo("inapta");
        assertThat(evaluation.situacao()).isEqualTo(SituacaoCadastral.inapta);
    }

    @ParameterizedTest
    @CsvSource({"30, 30, warn, true", "179, 179, warn, true", "180, 180, ok, false", "0, 1, warn, true",
        "3650, 3650, ok, false"})
    void theAgeCheckWarnsBelowTheRulesDaysAndNeverReportsZeroDays(int daysAgo, int dias, StatusChecagem status,
            boolean recente) {
        Evaluation evaluation = evaluator.evaluate(company(SituacaoCadastral.ativa, TODAY.minusDays(daysAgo),
                Regime.OUTRO, List.of(), List.of(PARTNER)), PARTNER, 180);

        Checagem idade = byKey(evaluation).get(ChaveChecagem.idade);
        assertThat(idade.status()).isEqualTo(status);
        assertThat(idade.valor()).isEqualTo(String.valueOf(dias));
        assertThat(evaluation.dias()).isEqualTo(dias);
        assertThat(evaluation.recente()).isEqualTo(recente);
    }

    @Test
    void ageIsCountedInSaoPauloDaysJustBeforeMidnight() {
        Clock lateUtc = Clock.fixed(Instant.parse("2026-11-08T02:59:59Z"), ZoneOffset.UTC);
        Evaluation evaluation = new ChecksEvaluator(lateUtc).evaluate(company(SituacaoCadastral.ativa,
                TODAY.minusDays(179), Regime.OUTRO, List.of(), List.of(PARTNER)), PARTNER, 180);

        assertThat(evaluation.dias()).isEqualTo(179);
    }

    @ParameterizedTest
    @CsvSource({"Maria Exemplo Teste, ok, EXACT", "Maria Teste, warn, PARTIAL", "Pedro Lima, bad, NONE"})
    void thePartnerCheckGradesTheMatch(String responsavel, StatusChecagem status, Match match) {
        Evaluation evaluation = evaluator.evaluate(company(SituacaoCadastral.ativa, TODAY.minusYears(10), Regime.OUTRO,
                List.of(), List.of(PARTNER)), responsavel, 180);

        assertThat(byKey(evaluation).get(ChaveChecagem.qsa).status()).isEqualTo(status);
        assertThat(evaluation.qsa()).isEqualTo(match);
    }

    @Test
    void aMicroEntrepreneurWarnsOnRegimeAndMatchesTheNameInTheCompanyName() {
        CompanyRecord mei = new CompanyRecord("JOAO CARLOS SILVA ***.***.***-**", "Belo Horizonte", "MG", "4789099",
                "Comercio", SituacaoCadastral.ativa, TODAY.minusYears(5), Regime.MEI, List.of(), List.of());

        Evaluation evaluation = evaluator.evaluate(mei, "João Carlos Silva", 180);

        assertThat(byKey(evaluation).get(ChaveChecagem.regime).status()).isEqualTo(StatusChecagem.warn);
        assertThat(byKey(evaluation).get(ChaveChecagem.qsa).status()).isEqualTo(StatusChecagem.ok);
        assertThat(evaluation.mei()).isTrue();
        assertThat(evaluation.qsa()).isEqualTo(Match.EXACT);
    }

    @Test
    void activityAndStateRegistrationChecksNeverFail() {
        Evaluation noIe = evaluator.evaluate(company(SituacaoCadastral.ativa, TODAY.minusYears(10), Regime.SIMPLES,
                List.of(new InscricaoEstadual("SP", false)), List.of(PARTNER)), PARTNER, 180);

        assertThat(byKey(noIe).get(ChaveChecagem.cnae).status()).isEqualTo(StatusChecagem.ok);
        assertThat(byKey(noIe).get(ChaveChecagem.ie).status()).isEqualTo(StatusChecagem.ok);
        assertThat(noIe.ieAtivaUf()).isNull();
    }

    @Test
    void partnerNamesNeverAppearInTheChecks() {
        Evaluation evaluation = evaluator.evaluate(company(SituacaoCadastral.ativa, TODAY.minusYears(10), Regime.OUTRO,
                List.of(), List.of("Roberto Ficticio Nunes", PARTNER)), "Maria Teste", 180);

        assertThat(evaluation.checagens().toString()).doesNotContain("Roberto").doesNotContain("Ficticio")
                .doesNotContain("Exemplo").doesNotContain("MARIA").doesNotContain("Maria");
    }

    private static CompanyRecord company(SituacaoCadastral situacao, LocalDate abertura, Regime regime,
            List<InscricaoEstadual> ies, List<String> partners) {
        return new CompanyRecord("Loja Exemplo Comercio Ltda", "Sao Paulo", "SP", "4789099", "Comercio varejista",
                situacao, abertura, regime, ies, partners);
    }

    private static java.util.Map<ChaveChecagem, Checagem> byKey(Evaluation evaluation) {
        return evaluation.checagens().stream().collect(Collectors.toMap(Checagem::chave, Function.identity()));
    }
}
