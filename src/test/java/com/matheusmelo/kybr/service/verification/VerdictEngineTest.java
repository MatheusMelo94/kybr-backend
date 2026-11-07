package com.matheusmelo.kybr.service.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.ModoConsulta;
import com.matheusmelo.kybr.model.link.Regime;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import com.matheusmelo.kybr.model.link.SnapshotEmpresa;
import com.matheusmelo.kybr.model.link.StatusChecagem;
import com.matheusmelo.kybr.model.link.StatusSeller;
import com.matheusmelo.kybr.model.link.Veredito;
import com.matheusmelo.kybr.model.workspace.Acao;
import com.matheusmelo.kybr.model.workspace.Chave;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Politica;
import com.matheusmelo.kybr.model.workspace.PoliticaRegra;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.company.CompanyRecord;
import com.matheusmelo.kybr.service.company.CompanyRecord.InscricaoEstadual;
import com.matheusmelo.kybr.service.verification.VerdictEngine.Verdict;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerdictEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-07T15:00:00Z");
    private static final Instant CRIADO_EM = Instant.parse("2026-11-07T14:59:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);
    private static final String RECEITA = "https://www.gov.br/receitafederal/pt-br/cnpj";
    private static final String PARTNER = "Maria Exemplo Teste";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ChecksEvaluator evaluator = new ChecksEvaluator(clock);
    private final VerdictEngine engine = new VerdictEngine(URI.create(RECEITA), Duration.ofDays(2), clock);
    private final Workspace workspace = TestWorkspaces.builder().build();

    @Test
    void aCleanCompanyIsApprovedWithTheThreeConfirmations() {
        Verdict verdict = decide(company(SituacaoCadastral.ativa, TODAY.minusYears(10), Regime.OUTRO, true), PARTNER);

        assertThat(verdict.estado()).isEqualTo(EstadoCaso.approved);
        assertThat(verdict.statusSeller()).isEqualTo(StatusSeller.aprovado);
        assertThat(verdict.resultado().confirmacoes()).containsExactly(ItemResultado.of(CodigoResultado.SITUACAO_ATIVA),
                ItemResultado.of(CodigoResultado.RESPONSAVEL_CONFERE),
                new ItemResultado(CodigoResultado.IE_ATIVA, null, null, null, "SP", null, null));
        assertThat(verdict.resultado().razaoSocial()).isEqualTo("Loja Exemplo Comercio Ltda");
        assertThat(verdict.resultado().consultadoEm()).isEqualTo(NOW.minusSeconds(5));
        assertThat(verdict.resultado().itens()).isNull();
        assertThat(verdict.resultado().motivo()).isNull();
        assertThat(verdict.prazoRevisao()).isNull();
        assertThat(verdict.verification().veredito()).isEqualTo(Veredito.aprovar);
        assertThat(verdict.verification().status()).isEqualTo(StatusChecagem.ok);
        assertThat(verdict.verification().aprovadoComLimite()).isFalse();
    }

    @Test
    void anInactiveRegistrationLeavesOutTheStateConfirmation() {
        Verdict verdict = decide(company(SituacaoCadastral.ativa, TODAY.minusYears(10), Regime.OUTRO, false), PARTNER);

        assertThat(verdict.resultado().confirmacoes()).extracting(ItemResultado::codigo)
                .containsExactly(CodigoResultado.SITUACAO_ATIVA, CodigoResultado.RESPONSAVEL_CONFERE);
    }

    @Test
    void anInaptaCompanyIsBlockedWithTheRegularisationUrl() {
        Verdict verdict = decide(company(SituacaoCadastral.inapta, TODAY.minusYears(10), Regime.OUTRO, true), PARTNER);

        assertThat(verdict.estado()).isEqualTo(EstadoCaso.blocked);
        assertThat(verdict.statusSeller()).isEqualTo(StatusSeller.bloqueado);
        assertThat(verdict.resultado().motivo()).isEqualTo(new ItemResultado(CodigoResultado.SITUACAO_IRREGULAR,
                SituacaoCadastral.inapta, RECEITA, null, null, null, null));
        assertThat(verdict.verification().veredito()).isEqualTo(Veredito.bloquear);
        assertThat(verdict.verification().status()).isEqualTo(StatusChecagem.bad);
    }

    @Test
    void blockingTakesPrecedenceAndReportsTheFirstBlockingRuleOnly() {
        Verdict verdict = decide(company(SituacaoCadastral.inapta, TODAY.minusDays(10), Regime.OUTRO, true),
                "Pedro Lima");

        assertThat(verdict.estado()).isEqualTo(EstadoCaso.blocked);
        assertThat(verdict.resultado().motivo().codigo()).isEqualTo(CodigoResultado.SITUACAO_IRREGULAR);
        assertThat(verdict.resultado().itens()).isNull();
    }

    @Test
    void thePartnerRuleBlocksBeforeTheRecentRule() {
        List<PoliticaRegra> rules = new ArrayList<>(TestWorkspaces.defaultPolicy().regras());
        rules.replaceAll(rule -> rule.chave() == Chave.qsa || rule.chave() == Chave.recente
                ? new PoliticaRegra(rule.chave(), Acao.block, rule.acoesPermitidas(), rule.dias()) : rule);
        LinkCase linkCase = TestCases.builder(workspace).politica(new Politica(1, rules)).criadoEm(CRIADO_EM)
                .build().linkCase();
        CompanyRecord recent = company(SituacaoCadastral.ativa, TODAY.minusDays(10), Regime.OUTRO, true);

        Verdict verdict = engine.decide(linkCase, recent, evaluator.evaluate(recent, "Pedro Lima", 180),
                ModoConsulta.tempo_real, NOW);

        assertThat(verdict.resultado().motivo()).isEqualTo(ItemResultado.of(CodigoResultado.RESPONSAVEL_NAO_SOCIO));
    }

    @Test
    void aPartnerRuleSetToReviewAsksForTheResponsiblesDocumentAndNeverNamesTheMismatch() {
        Verdict verdict = decideWith(Chave.qsa, Acao.review,
                company(SituacaoCadastral.ativa, TODAY.minusYears(10), Regime.OUTRO, true), "Pedro Lima");

        assertThat(verdict.estado()).isEqualTo(EstadoCaso.pending);
        assertThat(verdict.resultado().itens()).containsExactly(new ItemResultado(
                CodigoResultado.DOCUMENTO_RESPONSAVEL, null, null, null, null, DocumentoTipo.RESPONSAVEL, 0));
    }

    @Test
    void aBlockingPartnerRuleSendsAPartialMatchToPendingWithTheResponsiblesDocument() {
        Verdict verdict = decideWith(Chave.qsa, Acao.block,
                company(SituacaoCadastral.ativa, TODAY.minusYears(10), Regime.OUTRO, true), "Maria Teste");

        assertThat(verdict.estado()).isEqualTo(EstadoCaso.pending);
        assertThat(verdict.resultado().motivo()).isNull();
        assertThat(verdict.resultado().itens()).containsExactly(new ItemResultado(
                CodigoResultado.DOCUMENTO_RESPONSAVEL, null, null, null, null, DocumentoTipo.RESPONSAVEL, 0));
    }

    @Test
    void aBlockingPartnerRuleBlocksOnlyWhenNoNameMatches() {
        Verdict verdict = decideWith(Chave.qsa, Acao.block,
                company(SituacaoCadastral.ativa, TODAY.minusYears(10), Regime.OUTRO, true), "Pedro Lima");

        assertThat(verdict.estado()).isEqualTo(EstadoCaso.blocked);
        assertThat(verdict.resultado().motivo()).isEqualTo(ItemResultado.of(CodigoResultado.RESPONSAVEL_NAO_SOCIO));
    }

    @Test
    void aBlockingRecentRuleGivesOnlyTheCodeAsTheReason() {
        Verdict verdict = decideWith(Chave.recente, Acao.block,
                company(SituacaoCadastral.ativa, TODAY.minusDays(10), Regime.OUTRO, true), PARTNER);

        assertThat(verdict.estado()).isEqualTo(EstadoCaso.blocked);
        assertThat(verdict.resultado().motivo()).isEqualTo(ItemResultado.of(CodigoResultado.EMPRESA_RECENTE));
    }

    private Verdict decideWith(Chave chave, Acao acao, CompanyRecord company, String responsavel) {
        List<PoliticaRegra> rules = new ArrayList<>(TestWorkspaces.defaultPolicy().regras());
        rules.replaceAll(rule -> rule.chave() == chave
                ? new PoliticaRegra(rule.chave(), acao, rule.acoesPermitidas(), rule.dias()) : rule);
        LinkCase linkCase = TestCases.builder(workspace).politica(new Politica(1, rules)).criadoEm(CRIADO_EM)
                .build().linkCase();
        return engine.decide(linkCase, company, evaluator.evaluate(company, responsavel, 180),
                ModoConsulta.tempo_real, NOW);
    }

    @Test
    void aPartnerMatchedOnlyInPartAsksForTheResponsiblesDocument() {
        Verdict verdict = decide(company(SituacaoCadastral.ativa, TODAY.minusYears(10), Regime.OUTRO, true),
                "Maria Teste");

        assertThat(verdict.estado()).isEqualTo(EstadoCaso.pending);
        assertThat(verdict.statusSeller()).isEqualTo(StatusSeller.revisao);
        assertThat(verdict.resultado().itens()).containsExactly(new ItemResultado(
                CodigoResultado.DOCUMENTO_RESPONSAVEL, null, null, null, null, DocumentoTipo.RESPONSAVEL, 0));
        assertThat(verdict.prazoRevisao()).isEqualTo(CRIADO_EM.plus(Duration.ofDays(2)));
        assertThat(verdict.resultado().prazoRevisao()).isEqualTo(verdict.prazoRevisao());
        assertThat(verdict.revisaoSlaAte()).isEqualTo(verdict.prazoRevisao());
        assertThat(verdict.verification().veredito()).isEqualTo(Veredito.revisar);
    }

    @Test
    void aPartialAndANoMatchLookTheSameToTheSeller() {
        CompanyRecord company = company(SituacaoCadastral.ativa, TODAY.minusYears(10), Regime.OUTRO, true);

        assertThat(decide(company, "Pedro Lima").resultado()).isEqualTo(decide(company, "Maria Teste").resultado());
    }

    @Test
    void aRecentCompanyIsPendingWithItsAgeInDays() {
        Verdict verdict = decide(company(SituacaoCadastral.ativa, TODAY.minusDays(30), Regime.OUTRO, true), PARTNER);

        assertThat(verdict.estado()).isEqualTo(EstadoCaso.pending);
        assertThat(verdict.resultado().itens()).containsExactly(
                new ItemResultado(CodigoResultado.EMPRESA_RECENTE, null, null, 30, null, null, null));
    }

    @Test
    void pendingItemsAreTheUnionOfEveryReviewAndRequestRule() {
        Verdict verdict = decide(company(SituacaoCadastral.ativa, TODAY.minusDays(60), Regime.OUTRO, true),
                "Maria Teste");

        assertThat(verdict.resultado().itens()).extracting(ItemResultado::codigo)
                .containsExactly(CodigoResultado.DOCUMENTO_RESPONSAVEL, CodigoResultado.EMPRESA_RECENTE);
    }

    @Test
    void aMicroEntrepreneurIsApprovedWithALimitAndNeverToldItsMei() {
        CompanyRecord mei = new CompanyRecord("JOAO CARLOS SILVA 12345678909", "Belo Horizonte", "MG", "4789099",
                "Comercio", SituacaoCadastral.ativa, TODAY.minusYears(5), Regime.MEI, List.of(), List.of());

        Verdict verdict = decide(mei, "João Carlos Silva");

        assertThat(verdict.estado()).isEqualTo(EstadoCaso.approved);
        assertThat(verdict.verification().aprovadoComLimite()).isTrue();
        assertThat(verdict.resultado().confirmacoes()).extracting(ItemResultado::codigo)
                .containsExactly(CodigoResultado.SITUACAO_ATIVA, CodigoResultado.RESPONSAVEL_CONFERE)
                .doesNotContain(CodigoResultado.MEI);
        assertThat(verdict.resultado().razaoSocial()).isEqualTo("JOAO CARLOS SILVA ***.***.***-**");
        assertThat(verdict.verification().snapshot().razaoSocial()).isEqualTo("JOAO CARLOS SILVA ***.***.***-**");
    }

    @Test
    void theVerdictFollowsTheCasesPolicyCopyNotTheWorkspacesCurrentOne() {
        Politica atCreation = TestWorkspaces.defaultPolicy();
        List<PoliticaRegra> changed = new ArrayList<>(atCreation.regras());
        changed.replaceAll(rule -> rule.chave() == Chave.situacao
                ? new PoliticaRegra(Chave.situacao, Acao.review, rule.acoesPermitidas(), null) : rule);
        Workspace changedWorkspace = TestWorkspaces.builder().politica(new Politica(2, changed)).build();
        LinkCase linkCase = TestCases.builder(changedWorkspace).politica(atCreation).criadoEm(CRIADO_EM)
                .responsavel(PARTNER).build().linkCase();
        CompanyRecord inapta = company(SituacaoCadastral.inapta, TODAY.minusYears(10), Regime.OUTRO, true);

        Verdict verdict = engine.decide(linkCase, inapta, evaluator.evaluate(inapta, PARTNER, 180),
                ModoConsulta.tempo_real, NOW.minusSeconds(5));

        assertThat(verdict.estado()).isEqualTo(EstadoCaso.blocked);
        assertThat(verdict.verification().politicaVersao()).isEqualTo(1);
    }

    @Test
    void theVerificationKeepsTheMinimalSnapshotAndFixedReasons() {
        String responsavel = "Maria Teste";
        Verdict verdict = decide(company(SituacaoCadastral.ativa, TODAY.minusDays(60), Regime.OUTRO, true),
                responsavel);

        assertThat(verdict.verification().snapshot()).isEqualTo(new SnapshotEmpresa("Loja Exemplo Comercio Ltda",
                "Sao Paulo", "SP", "4789099", "Comercio varejista", SituacaoCadastral.ativa, TODAY.minusDays(60),
                Regime.OUTRO));
        assertThat(verdict.verification().motivos()).isNotEmpty().allSatisfy(motivo -> assertThat(motivo)
                .isIn(VerdictEngine.MOTIVOS.values()).doesNotContain(responsavel).doesNotContain("Maria"));
        assertThat(verdict.verification().checagens()).hasSize(6);
        assertThat(verdict.verification().modo()).isEqualTo(ModoConsulta.tempo_real);
        assertThat(verdict.verification().politicaVersao()).isEqualTo(1);
        assertThat(verdict.verification().criadoEm()).isEqualTo(NOW);
        assertThat(verdict.verification().toString()).doesNotContain(PARTNER);
    }

    @Test
    void aRegularisationUrlOffGovBrIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new VerdictEngine(URI.create("https://example.test/x"), Duration.ofDays(2), clock));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KybrProperties.Regularizacao(URI.create("http://www.gov.br/x")));
    }

    private Verdict decide(CompanyRecord company, String responsavel) {
        LinkCase linkCase = TestCases.builder(workspace).criadoEm(CRIADO_EM).responsavel(responsavel).build()
                .linkCase();
        return engine.decide(linkCase, company, evaluator.evaluate(company, responsavel, 180), ModoConsulta.tempo_real,
                NOW.minusSeconds(5));
    }

    private static CompanyRecord company(SituacaoCadastral situacao, LocalDate abertura, Regime regime, boolean ie) {
        return new CompanyRecord("Loja Exemplo Comercio Ltda", "Sao Paulo", "SP", "4789099", "Comercio varejista",
                situacao, abertura, regime, List.of(new InscricaoEstadual("SP", ie)), List.of(PARTNER));
    }
}
