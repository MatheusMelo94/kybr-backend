package com.matheusmelo.kybr.service.company;

import com.matheusmelo.kybr.model.link.Regime;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import java.time.LocalDate;
import java.util.List;

/**
 * The provider's full record, for a verification. Any field may be missing (null). It is never
 * persisted: partner names ({@code qsaNomes}) live only in memory for the responsible-person
 * match and are dropped with the record.
 *
 * @param razaoSocial as the provider sent it (not CPF-masked yet)
 * @param regime null when the provider didn't say
 * @param inscricoesEstaduais the state registrations, possibly none
 * @param qsaNomes partner names, possibly none (an individual micro-entrepreneur has none)
 */
public record CompanyRecord(
        String razaoSocial,
        String municipio,
        String uf,
        String cnaeCodigo,
        String cnaeDescricao,
        SituacaoCadastral situacao,
        LocalDate dataAbertura,
        Regime regime,
        List<InscricaoEstadual> inscricoesEstaduais,
        List<String> qsaNomes) {

    public CompanyRecord {
        inscricoesEstaduais = inscricoesEstaduais == null ? List.of() : List.copyOf(inscricoesEstaduais);
        qsaNomes = qsaNomes == null ? List.of() : List.copyOf(qsaNomes);
    }

    /** One state registration. */
    public record InscricaoEstadual(String uf, boolean ativa) {
    }

    /** Never prints partner names. */
    @Override
    public String toString() {
        return "CompanyRecord[situacao=" + situacao + ", uf=" + uf + ", regime=" + regime + ", qsa="
                + qsaNomes.size() + " names]";
    }
}
