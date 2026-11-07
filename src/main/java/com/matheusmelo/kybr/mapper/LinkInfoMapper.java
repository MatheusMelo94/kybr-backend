package com.matheusmelo.kybr.mapper;

import com.matheusmelo.kybr.dto.link.LinkInfoResponse;
import com.matheusmelo.kybr.model.workspace.ContatoVendedor;
import com.matheusmelo.kybr.model.workspace.Workspace;
import org.springframework.stereotype.Component;

/** Workspace → E01 link info: the name and the seller contact, nothing else. */
@Component
public class LinkInfoMapper {

    public LinkInfoResponse toResponse(Workspace workspace) {
        ContatoVendedor contato = workspace.contatoVendedor();
        LinkInfoResponse.Tipo tipo = switch (contato.tipo()) {
            case email -> LinkInfoResponse.Tipo.email;
            case url -> LinkInfoResponse.Tipo.url;
        };
        return new LinkInfoResponse(workspace.nome(), new LinkInfoResponse.ContatoVendedor(tipo, contato.valor()));
    }
}
