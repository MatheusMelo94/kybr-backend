package com.matheusmelo.kybr.mapper;

import com.matheusmelo.kybr.dto.link.CaseResponse;
import com.matheusmelo.kybr.dto.link.ResultadoPublico;
import com.matheusmelo.kybr.dto.link.ResultadoPublico.CodigoConfirmacao;
import com.matheusmelo.kybr.dto.link.ResultadoPublico.CodigoItem;
import com.matheusmelo.kybr.dto.link.ResultadoPublico.CodigoMotivo;
import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import com.matheusmelo.kybr.model.link.Variante;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.util.CpfMasker;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Case → the seller-facing E04 body. The stored projection is copied field by field into the
 * contract's closed vocabulary, so nothing outside the contract can appear:
 *
 * <ul>
 *   <li>{@code processing}: no result, unless a contact e-mail was saved ({@code responsavel} and
 *       the masked e-mail) or no company data was found ({@code responsavel} and the variant);
 *   <li>otherwise: company name (any CPF masked again), {@code consultado_em}, and the state's own
 *       field: {@code prazo_revisao} and {@code itens} on pending, {@code confirmacoes} on approved,
 *       {@code motivo} on blocked;
 *   <li>without company data, only requested-document items and a {@code GENERICO} block show;
 *   <li>an item or confirmation missing a field it requires is dropped; an unknown block reason is
 *       {@code GENERICO}; a regularisation URL that isn't {@code https:} on gov.br is dropped.
 * </ul>
 */
@Component
public class PublicResultMapper {

    public CaseResponse toResponse(LinkCase linkCase) {
        return new CaseResponse(estado(linkCase), linkCase.criadoEm(), resultado(linkCase));
    }

    private static ResultadoPublico resultado(LinkCase linkCase) {
        Resultado stored = linkCase.resultado();
        boolean noData = linkCase.variante() == Variante.DADOS_NAO_ENCONTRADOS
                || stored != null && stored.variante() == Variante.DADOS_NAO_ENCONTRADOS;
        ResultadoPublico.Variante variante = noData ? ResultadoPublico.Variante.DADOS_NAO_ENCONTRADOS : null;
        String responsavel = linkCase.responsavel();
        String email = linkCase.contato() == null ? null : linkCase.contato().emailMascarado();
        if (linkCase.estado() == EstadoCaso.processing) {
            return !noData && email == null ? null
                    : new ResultadoPublico(null, responsavel, null, variante, null, email, null, null, null);
        }
        boolean companyData = !noData && stored != null;
        String razaoSocial = companyData ? CpfMasker.mask(stored.razaoSocial()) : null;
        Instant consultadoEm = companyData ? stored.consultadoEm() : null;
        return switch (linkCase.estado()) {
            case pending -> new ResultadoPublico(razaoSocial, responsavel, consultadoEm, variante,
                    prazo(linkCase, stored), email, null, itens(stored, noData), null);
            case approved -> new ResultadoPublico(razaoSocial, responsavel, consultadoEm, variante, null, email,
                    companyData ? confirmacoes(stored) : null, null, null);
            case blocked -> new ResultadoPublico(razaoSocial, responsavel, consultadoEm, variante, null, email,
                    null, null, motivo(stored, noData));
            case processing -> throw new IllegalStateException("processing is handled above");
        };
    }

    private static CaseResponse.Estado estado(LinkCase linkCase) {
        return CaseResponse.Estado.valueOf(linkCase.estado().name());
    }

    private static Instant prazo(LinkCase linkCase, Resultado stored) {
        return stored != null && stored.prazoRevisao() != null ? stored.prazoRevisao() : linkCase.prazoRevisao();
    }

    private static List<ResultadoPublico.Item> itens(Resultado stored, boolean noData) {
        if (stored == null || stored.itens() == null) {
            return List.of();
        }
        return stored.itens().stream()
                .filter(item -> !noData || item.codigo() == CodigoResultado.DOCUMENTO_SOLICITADO)
                .map(PublicResultMapper::item)
                .filter(Objects::nonNull)
                .toList();
    }

    private static ResultadoPublico.Item item(ItemResultado item) {
        CodigoItem codigo = enumOrNull(CodigoItem.class, item.codigo());
        if (codigo == null) {
            return null;
        }
        String url = govBrUrl(item.urlRegularizacao());
        return switch (codigo) {
            case DOCUMENTO_RESPONSAVEL, DOCUMENTO_SOLICITADO -> {
                ResultadoPublico.DocumentoTipo tipo = documentoTipo(item.documentoTipo());
                yield tipo == null || item.documentosEnviados() == null || item.documentosEnviados() < 0 ? null
                        : new ResultadoPublico.Item(codigo, tipo, item.documentosEnviados(), null, null, null);
            }
            case SITUACAO_IRREGULAR -> {
                ResultadoPublico.Situacao situacao = situacao(item.situacao());
                yield situacao == null ? null : new ResultadoPublico.Item(codigo, null, null, situacao, null, url);
            }
            case EMPRESA_RECENTE -> item.dias() == null || item.dias() < 1 ? null
                    : new ResultadoPublico.Item(codigo, null, null, null, item.dias(), null);
            default -> new ResultadoPublico.Item(codigo, null, null, null, null, url);
        };
    }

    private static List<ResultadoPublico.Confirmacao> confirmacoes(Resultado stored) {
        if (stored == null || stored.confirmacoes() == null) {
            return List.of();
        }
        return stored.confirmacoes().stream()
                .map(PublicResultMapper::confirmacao)
                .filter(Objects::nonNull)
                .toList();
    }

    private static ResultadoPublico.Confirmacao confirmacao(ItemResultado item) {
        CodigoConfirmacao codigo = enumOrNull(CodigoConfirmacao.class, item.codigo());
        if (codigo == null) {
            return null;
        }
        if (codigo == CodigoConfirmacao.IE_ATIVA) {
            return item.uf() == null || !item.uf().matches("^[A-Z]{2}$") ? null
                    : new ResultadoPublico.Confirmacao(codigo, item.uf());
        }
        return new ResultadoPublico.Confirmacao(codigo, null);
    }

    private static ResultadoPublico.Motivo motivo(Resultado stored, boolean noData) {
        ItemResultado motivo = stored == null ? null : stored.motivo();
        CodigoMotivo codigo = motivo == null || noData ? CodigoMotivo.GENERICO
                : Objects.requireNonNullElse(enumOrNull(CodigoMotivo.class, motivo.codigo()), CodigoMotivo.GENERICO);
        if (codigo == CodigoMotivo.SITUACAO_IRREGULAR) {
            ResultadoPublico.Situacao situacao = situacao(motivo.situacao());
            return situacao == null ? new ResultadoPublico.Motivo(CodigoMotivo.GENERICO, null, null)
                    : new ResultadoPublico.Motivo(codigo, situacao, govBrUrl(motivo.urlRegularizacao()));
        }
        return new ResultadoPublico.Motivo(codigo, null, null);
    }

    private static ResultadoPublico.Situacao situacao(SituacaoCadastral situacao) {
        return situacao == null || situacao == SituacaoCadastral.ativa ? null
                : ResultadoPublico.Situacao.valueOf(situacao.name());
    }

    private static ResultadoPublico.DocumentoTipo documentoTipo(DocumentoTipo tipo) {
        return tipo == null ? null : ResultadoPublico.DocumentoTipo.valueOf(tipo.name());
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, CodigoResultado codigo) {
        if (codigo == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(codigo.name())) {
                return constant;
            }
        }
        return null;
    }

    /** The URL when it is an absolute {@code https:} URL on gov.br or a gov.br subdomain, else null. */
    static String govBrUrl(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            boolean valid = "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null && host != null
                    && (host.equals("gov.br") || host.endsWith(".gov.br"));
            return valid ? url : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
