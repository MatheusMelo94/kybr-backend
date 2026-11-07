package com.matheusmelo.kybr.service.verification;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.ModoConsulta;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import com.matheusmelo.kybr.model.link.SnapshotEmpresa;
import com.matheusmelo.kybr.model.link.StatusChecagem;
import com.matheusmelo.kybr.model.link.StatusSeller;
import com.matheusmelo.kybr.model.link.Veredito;
import com.matheusmelo.kybr.model.link.Verification;
import com.matheusmelo.kybr.model.workspace.Acao;
import com.matheusmelo.kybr.model.workspace.Chave;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Politica;
import com.matheusmelo.kybr.model.workspace.PoliticaRegra;
import com.matheusmelo.kybr.service.company.CompanyRecord;
import com.matheusmelo.kybr.service.verification.ChecksEvaluator.Evaluation;
import com.matheusmelo.kybr.util.CpfMasker;
import com.matheusmelo.kybr.util.GovBrUrl;
import com.matheusmelo.kybr.util.QsaMatcher.Match;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Applies a case's policy copy to its checks (never the workspace's current policy, so a change
 * is never retroactive).
 *
 * <p>Rules fire in the order {@code situacao, qsa, recente, cnae, ie, mei}: an irregular status, a
 * responsible person who isn't an exact partner match, a recent company, a micro-entrepreneur
 * ({@code cnae} and {@code ie} never fire). The fired rules' actions decide, in precedence:
 *
 * <ul>
 *   <li>any {@code block} → blocked, with the first blocking rule as the reason (its code, and for
 *       {@code situacao} the status and the regularisation page; nothing else);
 *   <li>else any {@code review} or {@code request} → pending with the union of their items
 *       ({@code review}: an informational item, or the responsible's document for {@code qsa};
 *       {@code request}: a document to send), due after the
 *       review deadline from the case's creation;
 *   <li>else approved ({@code auto}, {@code approve} and {@code approve_limit}; the last is recorded
 *       as a limited approval), with the confirmations that hold.
 * </ul>
 *
 * <p>The verification keeps a minimal company snapshot (company name CPF-masked; no partner names)
 * and fixed reasons per fired rule, never seller-typed text.
 */
@Component
public class VerdictEngine {

    /** The fixed reason per rule, recorded on a verification when the rule fires. */
    public static final Map<Chave, String> MOTIVOS = Map.of(
            Chave.situacao, "Situação cadastral irregular na Receita Federal",
            Chave.qsa, "Responsável não confere com o quadro societário",
            Chave.recente, "Empresa aberta recentemente",
            Chave.cnae, "Atividade fora da categoria",
            Chave.ie, "Inscrição estadual não encontrada",
            Chave.mei, "Empresa optante pelo MEI");

    private static final List<Chave> RULE_ORDER =
            List.of(Chave.situacao, Chave.qsa, Chave.recente, Chave.cnae, Chave.ie, Chave.mei);

    /**
     * The outcome of a verification.
     *
     * @param prazoRevisao and {@code revisaoSlaAte}: set when pending
     * @param verification ready to insert
     */
    public record Verdict(EstadoCaso estado, Resultado resultado, Instant prazoRevisao, Instant revisaoSlaAte,
            StatusSeller statusSeller, Verification verification) {
    }

    private final String receitaUrl;
    private final Duration reviewDeadline;
    private final Clock clock;

    @Autowired
    public VerdictEngine(KybrProperties properties, Clock clock) {
        this(properties.link().regularizacao().receitaUrl(), properties.link().reviewDeadline(), clock);
    }

    VerdictEngine(URI receitaUrl, Duration reviewDeadline, Clock clock) {
        if (receitaUrl == null || !GovBrUrl.isGovBr(receitaUrl.toString())) {
            throw new IllegalArgumentException("the regularisation URL must be https: on gov.br");
        }
        this.receitaUrl = receitaUrl.toString();
        this.reviewDeadline = reviewDeadline;
        this.clock = clock;
    }

    public Verdict decide(LinkCase linkCase, CompanyRecord company, Evaluation evaluation, ModoConsulta modo,
            Instant consultadoEm) {
        Map<Chave, Acao> fired = fired(linkCase.politica(), evaluation);
        String razaoSocial = CpfMasker.mask(company.razaoSocial());

        EstadoCaso estado;
        Resultado resultado;
        Instant prazo = null;
        Chave blocking = fired.entrySet().stream().filter(rule -> rule.getValue() == Acao.block)
                .map(Map.Entry::getKey).findFirst().orElse(null);
        List<ItemResultado> itens = new ArrayList<>();
        fired.forEach((chave, acao) -> {
            if (acao == Acao.review) {
                itens.add(informational(chave, evaluation));
            } else if (acao == Acao.request) {
                itens.add(document(chave));
            }
        });
        if (blocking != null) {
            estado = EstadoCaso.blocked;
            resultado = new Resultado(razaoSocial, consultadoEm, null, null, null, null,
                    motivo(blocking, evaluation));
        } else if (!itens.isEmpty()) {
            estado = EstadoCaso.pending;
            prazo = linkCase.criadoEm().plus(reviewDeadline);
            resultado = new Resultado(razaoSocial, consultadoEm, null, prazo, null, itens, null);
        } else {
            estado = EstadoCaso.approved;
            resultado = new Resultado(razaoSocial, consultadoEm, null, null, confirmations(evaluation), null, null);
        }

        boolean limited = estado == EstadoCaso.approved && fired.containsValue(Acao.approve_limit);
        Verification verification = new Verification(UUID.randomUUID(), linkCase.workspaceId(), linkCase.id(),
                linkCase.cnpj(), modo, consultadoEm, snapshot(company, razaoSocial), evaluation.checagens(),
                veredito(estado), status(estado), fired.keySet().stream().map(MOTIVOS::get).toList(),
                linkCase.politica() == null ? 0 : linkCase.politica().versao(), limited, clock.instant());
        return new Verdict(estado, resultado, prazo, prazo, statusSeller(estado), verification);
    }

    /**
     * The fired rules in rule order, each with its action; a rule missing from the policy reviews. A
     * partner rule set to block blocks only when no name matches: a partial match asks for the
     * responsible person's document instead, so the seller never learns a match was partial.
     */
    private static Map<Chave, Acao> fired(Politica politica, Evaluation evaluation) {
        Map<Chave, Acao> fired = new LinkedHashMap<>();
        for (Chave chave : RULE_ORDER) {
            if (fires(chave, evaluation)) {
                Acao acao = action(politica, chave);
                boolean partialPartner = chave == Chave.qsa && evaluation.qsa() == Match.PARTIAL;
                fired.put(chave, acao == Acao.block && partialPartner ? Acao.request : acao);
            }
        }
        return fired;
    }

    private static boolean fires(Chave chave, Evaluation evaluation) {
        return switch (chave) {
            case situacao -> evaluation.situacao() != SituacaoCadastral.ativa;
            case qsa -> evaluation.qsa() != Match.EXACT;
            case recente -> evaluation.recente();
            case mei -> evaluation.mei();
            case cnae, ie, ok -> false;
        };
    }

    private static Acao action(Politica politica, Chave chave) {
        if (politica == null) {
            return Acao.review;
        }
        return politica.regras().stream().filter(rule -> rule.chave() == chave).map(PoliticaRegra::acao)
                .findFirst().orElse(Acao.review);
    }

    /**
     * The pending item a {@code review} rule adds. A partner mismatch under review asks for the
     * responsible person's document, so the seller is never told the names didn't match.
     */
    private ItemResultado informational(Chave chave, Evaluation evaluation) {
        return switch (chave) {
            case situacao -> motivo(chave, evaluation);
            case qsa -> document(chave);
            case recente -> new ItemResultado(CodigoResultado.EMPRESA_RECENTE, null, null, evaluation.dias(), null,
                    null, null);
            default -> ItemResultado.of(codigo(chave));
        };
    }

    /** The reason a {@code block} rule gives: only the code, plus the status and the gov.br page. */
    private ItemResultado motivo(Chave chave, Evaluation evaluation) {
        if (chave == Chave.situacao) {
            return new ItemResultado(CodigoResultado.SITUACAO_IRREGULAR, evaluation.situacao(), receitaUrl, null,
                    null, null, null);
        }
        return ItemResultado.of(codigo(chave));
    }

    private static CodigoResultado codigo(Chave chave) {
        return switch (chave) {
            case situacao -> CodigoResultado.SITUACAO_IRREGULAR;
            case qsa -> CodigoResultado.RESPONSAVEL_NAO_SOCIO;
            case recente -> CodigoResultado.EMPRESA_RECENTE;
            case cnae -> CodigoResultado.ATIVIDADE_FORA_DA_CATEGORIA;
            case ie -> CodigoResultado.IE_NAO_ENCONTRADA;
            case mei -> CodigoResultado.MEI;
            case ok -> CodigoResultado.GENERICO;
        };
    }

    /** The document a {@code request} rule asks for. */
    private static ItemResultado document(Chave chave) {
        if (chave == Chave.qsa) {
            return new ItemResultado(CodigoResultado.DOCUMENTO_RESPONSAVEL, null, null, null, null,
                    DocumentoTipo.RESPONSAVEL, 0);
        }
        DocumentoTipo tipo = chave == Chave.situacao ? DocumentoTipo.CARTAO_CNPJ : DocumentoTipo.CONTRATO_SOCIAL_OU_CCMEI;
        return new ItemResultado(CodigoResultado.DOCUMENTO_SOLICITADO, null, null, null, null, tipo, 0);
    }

    private static List<ItemResultado> confirmations(Evaluation evaluation) {
        List<ItemResultado> confirmations = new ArrayList<>();
        if (evaluation.situacao() == SituacaoCadastral.ativa) {
            confirmations.add(ItemResultado.of(CodigoResultado.SITUACAO_ATIVA));
        }
        if (evaluation.qsa() == Match.EXACT) {
            confirmations.add(ItemResultado.of(CodigoResultado.RESPONSAVEL_CONFERE));
        }
        if (evaluation.ieAtivaUf() != null) {
            confirmations.add(new ItemResultado(CodigoResultado.IE_ATIVA, null, null, null, evaluation.ieAtivaUf(),
                    null, null));
        }
        return confirmations;
    }

    private static SnapshotEmpresa snapshot(CompanyRecord company, String razaoSocial) {
        return new SnapshotEmpresa(razaoSocial, company.municipio(), company.uf(), company.cnaeCodigo(),
                company.cnaeDescricao(), company.situacao(), company.dataAbertura(), company.regime());
    }

    private static Veredito veredito(EstadoCaso estado) {
        return switch (estado) {
            case approved -> Veredito.aprovar;
            case blocked -> Veredito.bloquear;
            default -> Veredito.revisar;
        };
    }

    private static StatusChecagem status(EstadoCaso estado) {
        return switch (estado) {
            case approved -> StatusChecagem.ok;
            case blocked -> StatusChecagem.bad;
            default -> StatusChecagem.warn;
        };
    }

    private static StatusSeller statusSeller(EstadoCaso estado) {
        return switch (estado) {
            case approved -> StatusSeller.aprovado;
            case blocked -> StatusSeller.bloqueado;
            default -> StatusSeller.revisao;
        };
    }
}
