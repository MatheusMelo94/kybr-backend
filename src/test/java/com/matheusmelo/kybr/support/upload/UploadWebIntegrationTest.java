package com.matheusmelo.kybr.support.upload;

import com.matheusmelo.kybr.model.RateLimitCounter;
import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Base for document upload integration tests: the full app over HTTP with the scripted scanner,
 * storage and photo sanitizer and the multipart parse spy (all reset before each test), plus a
 * seeded open link with a pending case asking for two document types. Every subclass shares one
 * cached context.
 */
@Import({UploadTestDoublesConfiguration.class, MultipartParseSpy.class})
public abstract class UploadWebIntegrationTest extends WebIntegrationTest {

    protected static final DocumentoTipo TIPO = DocumentoTipo.RESPONSAVEL;
    protected static final DocumentoTipo OTHER_TIPO = DocumentoTipo.CONTRATO_SOCIAL_OU_CCMEI;

    @Autowired
    protected MongoTemplate mongo;
    @Autowired
    protected ScriptedScanner scanner;
    @Autowired
    protected ScriptedStorage storage;
    @Autowired
    protected ScriptedPhotoSanitizer sanitizer;

    protected Workspace workspace;
    protected TestCases.Built pending;

    @BeforeEach
    void resetDoublesAndSeedAPendingCase() {
        scanner.reset();
        storage.reset();
        sanitizer.reset();
        MultipartParseSpy.reset();
        workspace = TestWorkspaces.builder().build();
        mongo.insert(workspace);
        pending = seedCase(EstadoCaso.pending);
    }

    /**
     * Subclasses freeze the limiter clock years ahead to get a fresh window per test; the counters
     * that leaves behind would only expire years from now, so they go when the test ends.
     */
    @AfterEach
    void removeTheFutureRateLimitCounters() {
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where(RateLimitCounter.EXPIRES_AT)
                        .gt(Instant.now().plus(Duration.ofDays(2)))), RateLimitCounter.class);
    }

    /** A case of {@link #workspace} in {@code estado}, asking for {@link #TIPO} and {@link #OTHER_TIPO}. */
    protected TestCases.Built seedCase(EstadoCaso estado) {
        Instant now = Instant.now();
        Resultado resultado = new Resultado("EMPRESA EXEMPLO LTDA", now, null, now.plus(Duration.ofDays(2)), null,
                List.of(new ItemResultado(CodigoResultado.DOCUMENTO_RESPONSAVEL, null, null, null, null, TIPO, 0),
                        new ItemResultado(CodigoResultado.DOCUMENTO_SOLICITADO, null, null, null, null, OTHER_TIPO, 0)),
                null);
        TestCases.Built built = TestCases.builder(workspace).estado(estado).resultado(resultado).build();
        mongo.insert(built.linkCase());
        return built;
    }

    /** A documentation-range address unique enough that per-IP limits never carry across tests. */
    protected static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "198.51." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
    }
}
