package com.matheusmelo.kybr.service.provisioning;

import com.matheusmelo.kybr.model.workspace.Acao;
import com.matheusmelo.kybr.model.workspace.Chave;
import com.matheusmelo.kybr.model.workspace.Politica;
import com.matheusmelo.kybr.model.workspace.PoliticaRegra;
import java.util.List;

/**
 * The policy a new workspace starts with. Every category gets the panel's default policy, version 1
 * (provisional rules): an irregular status blocks, a recent company (under 180 days) and a
 * non-matching responsible person are reviewed (the latter asks for a document), and a
 * micro-entrepreneur is approved with a limit.
 */
public final class DefaultPolicies {

    private DefaultPolicies() {
    }

    public static Politica forCategory(String categoria) {
        return new Politica(1, List.of(
                new PoliticaRegra(Chave.situacao, Acao.block, List.of(Acao.review, Acao.block), null),
                new PoliticaRegra(Chave.recente, Acao.review,
                        List.of(Acao.approve, Acao.review, Acao.request, Acao.block), 180),
                new PoliticaRegra(Chave.qsa, Acao.request,
                        List.of(Acao.approve, Acao.review, Acao.request, Acao.block), null),
                new PoliticaRegra(Chave.cnae, Acao.review,
                        List.of(Acao.approve, Acao.approve_limit, Acao.review, Acao.block), null),
                new PoliticaRegra(Chave.ie, Acao.approve_limit,
                        List.of(Acao.approve, Acao.approve_limit, Acao.review, Acao.request, Acao.block), null),
                new PoliticaRegra(Chave.mei, Acao.approve_limit,
                        List.of(Acao.approve, Acao.approve_limit, Acao.review), null),
                new PoliticaRegra(Chave.ok, Acao.auto, List.of(Acao.auto, Acao.review), null)));
    }
}
