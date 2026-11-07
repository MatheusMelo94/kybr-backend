package com.matheusmelo.kybr.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.dto.admin.AdminWorkspaceResponse;
import com.matheusmelo.kybr.model.workspace.Plano;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminWorkspaceMapperTest {

    private final AdminWorkspaceMapper mapper = new AdminWorkspaceMapper();

    @Test
    void mapsTheOperatorView() {
        UUID id = UUID.randomUUID();
        Workspace workspace = TestWorkspaces.builder().id(id).slug("loja-exemplo").nome("Loja Exemplo")
                .categoria("moda").plano(Plano.Tipo.pago).linkAtivo(false).autofillAtivo(true).build();

        AdminWorkspaceResponse response = mapper.toResponse(workspace);

        assertThat(response).isEqualTo(new AdminWorkspaceResponse(id, "loja-exemplo", "Loja Exemplo", "moda",
                "pago", false, true));
    }
}
