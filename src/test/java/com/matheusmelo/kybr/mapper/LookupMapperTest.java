package com.matheusmelo.kybr.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.dto.link.LookupResponse;
import com.matheusmelo.kybr.model.link.ProjecaoEmpresa;
import org.junit.jupiter.api.Test;

class LookupMapperTest {

    private final LookupMapper mapper = new LookupMapper();

    @Test
    void mapsTheNameTownStateAndActivityDescription() {
        LookupResponse response = mapper.toResponse(
                new ProjecaoEmpresa("Loja Exemplo Ltda", "Sao Paulo", "SP", "Comercio varejista"));

        assertThat(response.razaoSocial()).isEqualTo("Loja Exemplo Ltda");
        assertThat(response.municipio()).isEqualTo("Sao Paulo");
        assertThat(response.uf()).isEqualTo("SP");
        assertThat(response.cnae().descricao()).isEqualTo("Comercio varejista");
    }

    @Test
    void masksACpfInTheNameOnOutputEvenIfTheStoredValueWasNotMasked() {
        LookupResponse response = mapper.toResponse(
                new ProjecaoEmpresa("JOAO CARLOS SILVA 12345678909", "Belo Horizonte", "MG", "Comercio"));

        assertThat(response.razaoSocial()).isEqualTo("JOAO CARLOS SILVA ***.***.***-**");
    }
}
