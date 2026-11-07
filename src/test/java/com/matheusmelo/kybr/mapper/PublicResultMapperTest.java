package com.matheusmelo.kybr.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.dto.link.CaseResponse;
import com.matheusmelo.kybr.dto.link.ResultadoPublico;
import com.matheusmelo.kybr.dto.link.ResultadoPublico.CodigoConfirmacao;
import com.matheusmelo.kybr.dto.link.ResultadoPublico.CodigoItem;
import com.matheusmelo.kybr.dto.link.ResultadoPublico.CodigoMotivo;
import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.Contato;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import com.matheusmelo.kybr.model.link.Variante;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Case → the seller-facing D4 body, per state. */
class PublicResultMapperTest {

    private static final Instant CONSULTADO = Instant.parse("2026-11-07T14:03:11Z");
    private static final Instant PRAZO = Instant.parse("2026-11-09T14:03:00Z");
    private static final String GOV_BR = "https://www.gov.br/receitafederal/regularizar";
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    private final PublicResultMapper mapper = new PublicResultMapper();
    private final Workspace workspace = TestWorkspaces.builder().build();

    @Test
    void processingWithoutAResultIsStateAndCreationTimeOnly() {
        CaseResponse response = mapper.toResponse(TestCases.builder(workspace).build().linkCase());

        assertThat(response.estado()).isEqualTo(CaseResponse.Estado.processing);
        assertThat(response.resultado()).isNull();
        assertThat(json(response)).containsOnlyKeys("estado", "criado_em");
        assertThat((String) json(response).get("criado_em")).endsWith("Z");
    }

    @Test
    void processingAfterAContactChangeCarriesTheNameAndTheMaskedEmail() {
        CaseResponse response = mapper.toResponse(TestCases.builder(workspace).responsavel("José Exemplo")
                .contato(contato("j•••@empresa-exemplo.test")).build().linkCase());

        assertThat(json(response.resultado())).containsOnlyKeys("responsavel", "email_contato_mascarado");
        assertThat(response.resultado().responsavel()).isEqualTo("José Exemplo");
        assertThat(response.resultado().emailContatoMascarado()).isEqualTo("j•••@empresa-exemplo.test");
    }

    @Test
    void processingWithNoCompanyDataCarriesTheVariantWithoutCompanyFields() {
        Resultado noData = new Resultado(null, null, Variante.DADOS_NAO_ENCONTRADOS, null, null, null, null);
        CaseResponse response = mapper.toResponse(TestCases.builder(workspace).variante(Variante.DADOS_NAO_ENCONTRADOS)
                .resultado(noData).build().linkCase());

        assertThat(response.estado()).isEqualTo(CaseResponse.Estado.processing);
        assertThat(json(response.resultado())).containsOnlyKeys("responsavel", "variante");
        assertThat(response.resultado().variante()).isEqualTo(ResultadoPublico.Variante.DADOS_NAO_ENCONTRADOS);
    }

    @Test
    void pendingCarriesTheDeadlineAndCompleteItems() {
        Resultado pending = new Resultado("EMPRESA EXEMPLO LTDA", CONSULTADO, null, PRAZO, null, List.of(
                new ItemResultado(CodigoResultado.DOCUMENTO_RESPONSAVEL, null, null, null, null,
                        DocumentoTipo.RESPONSAVEL, 0),
                new ItemResultado(CodigoResultado.EMPRESA_RECENTE, null, null, 30, null, null, null),
                new ItemResultado(CodigoResultado.SITUACAO_IRREGULAR, SituacaoCadastral.suspensa, GOV_BR, null, null,
                        null, null)), null);

        CaseResponse response = mapper.toResponse(TestCases.builder(workspace).estado(EstadoCaso.pending)
                .resultado(pending).prazoRevisao(PRAZO).build().linkCase());

        ResultadoPublico resultado = response.resultado();
        assertThat(resultado.prazoRevisao()).isEqualTo(PRAZO);
        assertThat(resultado.consultadoEm()).isEqualTo(CONSULTADO);
        assertThat(resultado.razaoSocial()).isEqualTo("EMPRESA EXEMPLO LTDA");
        assertThat(resultado.itens()).extracting(ResultadoPublico.Item::codigo).containsExactly(
                CodigoItem.DOCUMENTO_RESPONSAVEL, CodigoItem.EMPRESA_RECENTE, CodigoItem.SITUACAO_IRREGULAR);
        assertThat(resultado.itens().get(0).documentoTipo()).isEqualTo(ResultadoPublico.DocumentoTipo.RESPONSAVEL);
        assertThat(resultado.itens().get(0).documentosEnviados()).isZero();
        assertThat(resultado.itens().get(1).dias()).isEqualTo(30);
        assertThat(resultado.itens().get(2).situacao()).isEqualTo(ResultadoPublico.Situacao.suspensa);
        assertThat(resultado.itens().get(2).urlRegularizacao()).isEqualTo(GOV_BR);
        assertThat(json(resultado)).containsOnlyKeys("razao_social", "responsavel", "consultado_em", "prazo_revisao",
                "itens");
        assertThat((String) json(resultado).get("consultado_em")).isEqualTo("2026-11-07T14:03:11Z");
    }

    @Test
    void incompleteItemsAreDroppedNeverRenderedHalfEmpty() {
        Resultado pending = new Resultado("EMPRESA EXEMPLO LTDA", CONSULTADO, null, PRAZO, null, List.of(
                new ItemResultado(CodigoResultado.DOCUMENTO_SOLICITADO, null, null, null, null, null, null),
                new ItemResultado(CodigoResultado.EMPRESA_RECENTE, null, null, 0, null, null, null),
                new ItemResultado(CodigoResultado.SITUACAO_IRREGULAR, null, null, null, null, null, null),
                new ItemResultado(CodigoResultado.SITUACAO_IRREGULAR, SituacaoCadastral.ativa, null, null, null, null,
                        null),
                new ItemResultado(CodigoResultado.RESPONSAVEL_CONFERE, null, null, null, null, null, null),
                new ItemResultado(CodigoResultado.MEI, null, null, null, null, null, null)), null);

        CaseResponse response = mapper.toResponse(TestCases.builder(workspace).estado(EstadoCaso.pending)
                .resultado(pending).build().linkCase());

        assertThat(response.resultado().itens()).extracting(ResultadoPublico.Item::codigo)
                .containsExactly(CodigoItem.MEI);
    }

    @Test
    void approvedCarriesConfirmationsAndIeAtivaKeepsItsUf() {
        Resultado approved = new Resultado("EMPRESA EXEMPLO LTDA", CONSULTADO, null, null, List.of(
                ItemResultado.of(CodigoResultado.SITUACAO_ATIVA),
                ItemResultado.of(CodigoResultado.RESPONSAVEL_CONFERE),
                new ItemResultado(CodigoResultado.IE_ATIVA, null, null, null, "SP", null, null),
                ItemResultado.of(CodigoResultado.IE_ATIVA)), null, null);

        CaseResponse response = mapper.toResponse(TestCases.builder(workspace).estado(EstadoCaso.approved)
                .resultado(approved).build().linkCase());

        assertThat(response.resultado().confirmacoes()).containsExactly(
                new ResultadoPublico.Confirmacao(CodigoConfirmacao.SITUACAO_ATIVA, null),
                new ResultadoPublico.Confirmacao(CodigoConfirmacao.RESPONSAVEL_CONFERE, null),
                new ResultadoPublico.Confirmacao(CodigoConfirmacao.IE_ATIVA, "SP"));
        assertThat(json(response.resultado())).containsOnlyKeys("razao_social", "responsavel", "consultado_em",
                "confirmacoes");
    }

    @Test
    void blockedCarriesTheReasonAndDropsANonGovBrUrl() {
        Resultado blocked = new Resultado("EMPRESA EXEMPLO LTDA", CONSULTADO, null, null, null, null,
                new ItemResultado(CodigoResultado.SITUACAO_IRREGULAR, SituacaoCadastral.inapta,
                        "https://gov.br.example.com/x", null, null, null, null));

        CaseResponse response = mapper.toResponse(TestCases.builder(workspace).estado(EstadoCaso.blocked)
                .resultado(blocked).build().linkCase());

        assertThat(response.resultado().motivo()).isEqualTo(
                new ResultadoPublico.Motivo(CodigoMotivo.SITUACAO_IRREGULAR, ResultadoPublico.Situacao.inapta, null));
    }

    @Test
    void onlyHttpsGovBrUrlsSurvive() {
        assertThat(PublicResultMapper.govBrUrl(GOV_BR)).isEqualTo(GOV_BR);
        assertThat(PublicResultMapper.govBrUrl("https://gov.br/x")).isEqualTo("https://gov.br/x");
        assertThat(PublicResultMapper.govBrUrl("http://www.gov.br/x")).isNull();
        assertThat(PublicResultMapper.govBrUrl("https://u@www.gov.br/x")).isNull();
        assertThat(PublicResultMapper.govBrUrl("https://evilgov.br/x")).isNull();
        assertThat(PublicResultMapper.govBrUrl("javascript:alert(1)")).isNull();
        assertThat(PublicResultMapper.govBrUrl(null)).isNull();
    }

    @Test
    void withNoCompanyDataABlockIsAlwaysGenericAndOnlyRequestedDocumentsShow() {
        Resultado blocked = new Resultado(null, null, Variante.DADOS_NAO_ENCONTRADOS, null, null, null,
                new ItemResultado(CodigoResultado.SITUACAO_IRREGULAR, SituacaoCadastral.inapta, GOV_BR, null, null,
                        null, null));
        Resultado pending = new Resultado(null, null, Variante.DADOS_NAO_ENCONTRADOS, PRAZO, null, List.of(
                new ItemResultado(CodigoResultado.DOCUMENTO_SOLICITADO, null, null, null, null,
                        DocumentoTipo.CARTAO_CNPJ, 1),
                new ItemResultado(CodigoResultado.EMPRESA_RECENTE, null, null, 30, null, null, null)), null);

        CaseResponse block = mapper.toResponse(TestCases.builder(workspace).estado(EstadoCaso.blocked)
                .variante(Variante.DADOS_NAO_ENCONTRADOS).resultado(blocked).build().linkCase());
        CaseResponse pend = mapper.toResponse(TestCases.builder(workspace).estado(EstadoCaso.pending)
                .variante(Variante.DADOS_NAO_ENCONTRADOS).resultado(pending).prazoRevisao(PRAZO).build().linkCase());

        assertThat(block.resultado().motivo()).isEqualTo(new ResultadoPublico.Motivo(CodigoMotivo.GENERICO, null, null));
        assertThat(block.resultado().consultadoEm()).isNull();
        assertThat(pend.resultado().itens()).extracting(ResultadoPublico.Item::codigo)
                .containsExactly(CodigoItem.DOCUMENTO_SOLICITADO);
    }

    @Test
    void anUnmaskedCpfInTheCompanyNameIsMaskedOnOutput() {
        Resultado approved = new Resultado("JOAO CARLOS SILVA 52998224725", CONSULTADO, null, null,
                List.of(ItemResultado.of(CodigoResultado.SITUACAO_ATIVA)), null, null);

        CaseResponse response = mapper.toResponse(TestCases.builder(workspace).estado(EstadoCaso.approved)
                .resultado(approved).build().linkCase());

        assertThat(response.resultado().razaoSocial()).isEqualTo("JOAO CARLOS SILVA ***.***.***-**");
    }

    @Test
    void theOnlyPartnerSignalIsResponsibleMatchesOrDocumentRequested() {
        Resultado approved = new Resultado("EMPRESA EXEMPLO LTDA", CONSULTADO, null, null,
                List.of(ItemResultado.of(CodigoResultado.RESPONSAVEL_CONFERE)), null, null);

        String body = new String(JSON.writeValueAsBytes(mapper.toResponse(TestCases.builder(workspace)
                .estado(EstadoCaso.approved).resultado(approved).build().linkCase())));

        assertThat(body).doesNotContain("qsa").doesNotContain("socio").doesNotContain("score")
                .doesNotContain("checagens").doesNotContain("veredito").doesNotContain("politica");
    }

    private static Contato contato(String masked) {
        return new Contato(new byte[] {1, 2, 3}, masked, Instant.parse("2026-11-07T15:00:00Z"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Object value) {
        return JSON.readValue(JSON.writeValueAsBytes(value), Map.class);
    }
}
