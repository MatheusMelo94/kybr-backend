package com.matheusmelo.kybr.mapper;

import com.matheusmelo.kybr.dto.admin.AdminWorkspaceResponse;
import com.matheusmelo.kybr.model.workspace.Workspace;
import org.springframework.stereotype.Component;

/** Workspace → the operator's view: identity, plan and switches, no contact or policy. */
@Component
public class AdminWorkspaceMapper {

    public AdminWorkspaceResponse toResponse(Workspace workspace) {
        return new AdminWorkspaceResponse(workspace.id(), workspace.slug(), workspace.nome(), workspace.categoria(),
                workspace.plano().tipo().name(), workspace.linkAtivo(), workspace.autofillAtivo());
    }
}
