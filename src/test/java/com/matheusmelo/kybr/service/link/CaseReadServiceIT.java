package com.matheusmelo.kybr.service.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Case lookup against MongoDB: a token resolves only under its own workspace. */
class CaseReadServiceIT extends WebIntegrationTest {

    @Autowired
    private CaseReadService service;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private LinkCaseRepository cases;

    @Test
    void aTokenResolvesUnderItsWorkspaceAndMissesUnderAnother() {
        Workspace owner = workspaces.save(TestWorkspaces.builder().build());
        Workspace other = workspaces.save(TestWorkspaces.builder().build());
        TestCases.Built built = TestCases.builder(owner).build();
        cases.save(built.linkCase());

        assertThat(service.find(owner, built.token()).id()).isEqualTo(built.linkCase().id());
        assertThatThrownBy(() -> service.find(other, built.token())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CASO_NAO_ENCONTRADO));
        assertThatThrownBy(() -> service.find(owner, TestCases.newToken())).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CASO_NAO_ENCONTRADO));
    }

    @Test
    void aRevokedTokenIsCaseNotFoundOnTheCaseReadAndTheContactChange() {
        Workspace owner = workspaces.save(TestWorkspaces.builder().build());
        TestCases.Built built = TestCases.builder(owner).tokenRevogadoEm(Instant.now()).build();
        cases.save(built.linkCase());
        String path = "/public/v/" + owner.slug() + "/cases/" + built.token();

        Response read = http().get(path);
        Response contact = http().contentType(ContentType.JSON).body(Map.of("email", "vendedor@empresa-exemplo.test"))
                .put(path + "/contact");

        assertThat(read.statusCode()).isEqualTo(404);
        assertThat(read.jsonPath().getString("code")).isEqualTo("CASO_NAO_ENCONTRADO");
        assertThat(contact.statusCode()).isEqualTo(404);
        assertThat(contact.jsonPath().getString("code")).isEqualTo("CASO_NAO_ENCONTRADO");
        assertThatThrownBy(() -> service.find(owner, built.token())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CASO_NAO_ENCONTRADO));
    }
}
