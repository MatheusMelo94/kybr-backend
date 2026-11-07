package com.matheusmelo.kybr.service.verification;

import com.matheusmelo.kybr.model.link.ChaveChecagem;
import com.matheusmelo.kybr.model.link.Checagem;
import com.matheusmelo.kybr.model.link.Regime;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import com.matheusmelo.kybr.model.link.StatusChecagem;
import com.matheusmelo.kybr.service.company.CompanyRecord;
import com.matheusmelo.kybr.util.QsaMatcher;
import com.matheusmelo.kybr.util.QsaMatcher.Match;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Runs the six checks of a verification on a complete provider record (status, opening date and
 * company name present), in the panel's order: {@code situacao, qsa, idade, cnae, ie, regime}.
 *
 * <ul>
 *   <li>{@code situacao}: active → ok; anything else → bad, with the status as the value.
 *   <li>{@code qsa}: the responsible person against the partners (a micro-entrepreneur: against
 *       the name in the company name); exact → ok, partial → warn, none → bad.
 *   <li>{@code idade}: days since opening, in São Paulo days and at least 1; below the policy's
 *       "recent" days → warn.
 *   <li>{@code cnae} and {@code ie}: never fail.
 *   <li>{@code regime}: a micro-entrepreneur → warn.
 * </ul>
 *
 * <p>Titles and values are fixed or derived text: a partner's name never appears in a check.
 */
@Component
public class ChecksEvaluator {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    /**
     * The checks plus the facts the verdict needs.
     *
     * @param dias days since opening, at least 1
     * @param recente opened fewer than the policy's days ago
     * @param ieAtivaUf the state of an active state registration; null when there is none
     */
    public record Evaluation(List<Checagem> checagens, SituacaoCadastral situacao, int dias, boolean recente,
            Match qsa, boolean mei, String ieAtivaUf) {
        public Evaluation {
            checagens = List.copyOf(checagens);
        }
    }

    private final Clock clock;

    public ChecksEvaluator(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param diasRecente the policy's "recent company" threshold in days
     */
    public Evaluation evaluate(CompanyRecord company, String responsavel, int diasRecente) {
        boolean mei = company.regime() == Regime.MEI;
        Match qsa = mei ? QsaMatcher.matchMei(responsavel, company.razaoSocial())
                : QsaMatcher.match(responsavel, company.qsaNomes());
        LocalDate today = LocalDate.now(clock.withZone(SAO_PAULO));
        int dias = (int) Math.max(1, ChronoUnit.DAYS.between(company.dataAbertura(), today));
        boolean recente = dias < diasRecente;
        String ieAtivaUf = company.inscricoesEstaduais().stream()
                .filter(CompanyRecord.InscricaoEstadual::ativa)
                .map(CompanyRecord.InscricaoEstadual::uf)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        SituacaoCadastral situacao = company.situacao();

        List<Checagem> checagens = List.of(
                new Checagem(ChaveChecagem.situacao, "Situação cadastral", "Situação na Receita Federal",
                        situacao.name(), situacao == SituacaoCadastral.ativa ? StatusChecagem.ok : StatusChecagem.bad),
                new Checagem(ChaveChecagem.qsa, "Responsável", mei ? "Comparado ao nome do titular" : "Comparado ao quadro societário",
                        qsaValue(qsa), qsaStatus(qsa)),
                new Checagem(ChaveChecagem.idade, "Idade da empresa", "Dias desde a abertura", String.valueOf(dias),
                        recente ? StatusChecagem.warn : StatusChecagem.ok),
                new Checagem(ChaveChecagem.cnae, "Atividade principal", "Código CNAE", company.cnaeCodigo(),
                        StatusChecagem.ok),
                new Checagem(ChaveChecagem.ie, "Inscrição estadual", "Inscrição estadual ativa",
                        ieAtivaUf == null ? "não encontrada" : "ativa (" + ieAtivaUf + ")", StatusChecagem.ok),
                new Checagem(ChaveChecagem.regime, "Regime tributário", "Regime informado pela Receita",
                        company.regime() == null ? "não informado" : company.regime().name(),
                        mei ? StatusChecagem.warn : StatusChecagem.ok));
        return new Evaluation(checagens, situacao, dias, recente, qsa, mei, ieAtivaUf);
    }

    private static String qsaValue(Match match) {
        return switch (match) {
            case EXACT -> "confere";
            case PARTIAL -> "confere em parte";
            case NONE -> "não confere";
        };
    }

    private static StatusChecagem qsaStatus(Match match) {
        return switch (match) {
            case EXACT -> StatusChecagem.ok;
            case PARTIAL -> StatusChecagem.warn;
            case NONE -> StatusChecagem.bad;
        };
    }
}
