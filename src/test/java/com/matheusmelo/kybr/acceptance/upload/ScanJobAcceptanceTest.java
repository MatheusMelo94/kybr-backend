package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.support.link.TestCases;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Base for the acceptance cases of the asynchronous verification: uploads are received (202) without
 * running the scan job, and the test runs it when it chooses ({@link #scanAll()}), so it can look at
 * the files while they wait. The scanner wake-up is shortened (a scanner that never wakes gives up
 * within a second), which needs its own context, closed after each class. Before each test every
 * file other tests left waiting in the shared database is retired, so a session sees only this
 * test's files and the backlog gauges count only them.
 */
@TestPropertySource(properties = {"kybr.scan.wake-timeout=1s", "kybr.scan.wake-interval=50ms"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class ScanJobAcceptanceTest extends UploadAcceptanceTest {

    @LocalManagementPort
    protected int managementPort;

    @BeforeEach
    void noOtherFileIsWaiting() {
        mongo.updateMulti(query(where(Document.ESTADO).in(EstadoDocumento.RECEBIDO, EstadoDocumento.VERIFICANDO)),
                new Update().set(Document.ESTADO, EstadoDocumento.RECUSADO).unset(Document.ATIVO), Document.class);
    }

    /** Posts the upload and returns its answer, leaving the files waiting for the scan job. */
    protected Response receive(TestCases.Built linkCase, DocumentoTipo tipo, byte[]... files) {
        return multipart(tipo.name(), files).post(uploadPath(linkCase));
    }

    /** Makes every waiting file of the case due now, as if its backoff had passed. */
    protected void makeDue(UUID caseId) {
        mongo.updateMulti(query(where(Document.CASE_ID).is(caseId).and(Document.ESTADO).is(EstadoDocumento.RECEBIDO)),
                new Update().set(Document.PROXIMA_TENTATIVA, Instant.now().minusSeconds(1)), Document.class);
    }

    /** Moves the case's files' reception time back by {@code age}. */
    protected void receivedAgo(UUID caseId, Duration age) {
        mongo.updateMulti(query(where(Document.CASE_ID).is(caseId)),
                new Update().set(Document.CRIADO_EM, Instant.now().minus(age)), Document.class);
    }

    protected Document onlyFile(UUID caseId) {
        List<Document> files = documentsOf(caseId);
        assertThat(files).hasSize(1);
        return files.getFirst();
    }

    /** The case read's item for {@code tipo}. */
    protected Map<String, Object> item(TestCases.Built linkCase, DocumentoTipo tipo) {
        Response read = readCase(workspace.slug(), linkCase.token());
        assertThat(read.statusCode()).as(read.asString()).isEqualTo(200);
        List<Map<String, Object>> items = read.jsonPath().getList("resultado.itens");
        return items.stream().filter(i -> tipo.name().equals(i.get("documento_tipo"))).findFirst().orElseThrow();
    }

    /** The sum of every sample of {@code name} whose labels contain all of {@code labels}. */
    protected double metric(String name, String... labels) {
        String body = RestAssured.given().port(managementPort).get("/actuator/prometheus").asString();
        Matcher m = Pattern.compile("^" + Pattern.quote(name) + "(?:\\{([^}]*)\\})? ([0-9.eE+-]+)$", Pattern.MULTILINE)
                .matcher(body);
        double total = 0;
        while (m.find()) {
            String found = m.group(1) == null ? "" : m.group(1);
            boolean all = true;
            for (String label : labels) {
                all &= found.contains(label);
            }
            if (all) {
                total += Double.parseDouble(m.group(2));
            }
        }
        return total;
    }
}
