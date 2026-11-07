package com.matheusmelo.kybr.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.dto.link.LinkInfoResponse;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import org.junit.jupiter.api.Test;

class LinkInfoMapperTest {

    private final LinkInfoMapper mapper = new LinkInfoMapper();

    @Test
    void mapsTheNameAndAnEmailContact() {
        Workspace workspace = TestWorkspaces.builder().nome("Loja Exemplo").build();

        LinkInfoResponse response = mapper.toResponse(workspace);

        assertThat(response.nome()).isEqualTo("Loja Exemplo");
        assertThat(response.contatoVendedor().tipo()).isEqualTo(LinkInfoResponse.Tipo.email);
        assertThat(response.contatoVendedor().valor()).isEqualTo("contato@example.com");
    }

    @Test
    void mapsAUrlContact() {
        Workspace workspace = TestWorkspaces.builder().contatoUrl("https://loja.example.com/contato").build();

        LinkInfoResponse response = mapper.toResponse(workspace);

        assertThat(response.contatoVendedor().tipo()).isEqualTo(LinkInfoResponse.Tipo.url);
        assertThat(response.contatoVendedor().valor()).isEqualTo("https://loja.example.com/contato");
    }
}
