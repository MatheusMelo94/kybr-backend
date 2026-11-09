package com.matheusmelo.kybr.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.link.TokenSlot;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.service.email.EmailMessage;
import com.matheusmelo.kybr.service.email.RecordingEmailSender;
import com.matheusmelo.kybr.support.TestSecrets;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;

class AdminTestEmailIT extends WebIntegrationTest {

    private static final String PATH = "/actuator/admin/email-teste";
    /** The address the test profile allows (kybr.email.test-recipients). */
    private static final String ALLOWED = "operador.teste@example.test";

    @LocalManagementPort
    private int managementPort;
    @Autowired
    private RecordingEmailSender recorder;
    @Autowired
    private AuditEventRepository audits;

    @BeforeEach
    void clear() {
        recorder.clear();
    }

    @Test
    void anAllowedAddressGetsTheFixedTestEmailAndTheSendIsAudited() {
        long before = testEmailAudits().size();

        Response response = post(TestSecrets.ADMIN_TOKEN, Map.of("email", "Operador.Teste@Example.TEST"));

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.asString()).isEmpty();
        assertThat(recorder.recorded()).singleElement().satisfies(recorded -> {
            EmailMessage message = recorded.message();
            assertThat(message.to()).isEqualTo(ALLOWED);
            assertThat(message.subject()).isNotBlank();
            assertThat(message.text()).isNotBlank();
            assertThat(message.html()).doesNotContainIgnoringCase("<img");
        });
        List<AuditEvent> after = testEmailAudits();
        assertThat(after).hasSize((int) before + 1);
        assertThat(after.getLast()).satisfies(audit -> {
            assertThat(audit.ator()).isEqualTo(Ator.operador);
            assertThat(audit.tokenSlot()).isEqualTo(TokenSlot.CURRENT);
        });
    }

    @Test
    void anyOtherAddressIsRefusedAndNothingIsSent() {
        Response response = post(TestSecrets.ADMIN_TOKEN, Map.of("email", "alguem@example.test"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID");
        assertThat(response.jsonPath().getString("field")).isEqualTo("email");
        assertThat(recorder.recorded()).isEmpty();
    }

    @Test
    void aMissingAddressIsRefused() {
        Response response = post(TestSecrets.ADMIN_TOKEN, Map.of());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("field")).isEqualTo("email");
    }

    @Test
    void withoutAnAdminTokenTheCallIsUnauthorised() {
        Response response = RestAssured.given().port(managementPort).contentType("application/json")
                .body(Map.of("email", ALLOWED)).post(PATH);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(recorder.recorded()).isEmpty();
    }

    private Response post(String token, Map<String, Object> body) {
        return RestAssured.given().port(managementPort).header("Authorization", "Bearer " + token)
                .contentType("application/json").body(body).post(PATH);
    }

    private List<AuditEvent> testEmailAudits() {
        return audits.findAll().stream().filter(event -> event.tipo() == TipoAuditoria.EMAIL_TESTE_ENVIADO)
                .sorted(java.util.Comparator.comparing(AuditEvent::ocorridoEm)).toList();
    }
}
