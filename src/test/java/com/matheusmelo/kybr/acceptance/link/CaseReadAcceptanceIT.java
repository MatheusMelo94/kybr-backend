package com.matheusmelo.kybr.acceptance.link;

import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTNER;
import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.acceptance.link.fixtures.Cnpjs;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.LimiterClock;
import io.restassured.http.Header;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reading a case (E04): shapes, the identical not-found answers, the disabled link and the read limit. */
class CaseReadAcceptanceIT extends LinkAcceptanceTest {

    private TestCases.Built saved(Workspace workspace) {
        TestCases.Built built = TestCases.builder(workspace).build();
        cases.save(built.linkCase());
        return built;
    }

    private static List<String> headerNames(Response response) {
        return response.headers().asList().stream().map(Header::getName).map(n -> n.toLowerCase(Locale.ROOT))
                .sorted().toList();
    }

    @Test
    @DisplayName("a fresh case is exactly its state and an offset creation time")
    void aFreshCaseIsProcessing() {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace);

        Response response = readCase(workspace.slug(), built.token(), newIp());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response)).containsOnlyKeys("estado", "criado_em").containsEntry("estado", "processing");
        assertThat(response.jsonPath().getString("criado_em")).matches(FrontendContract.OFFSET_DATE_TIME);
    }

    @Test
    @DisplayName("an unknown token and another link's token are byte-identical 404s")
    void unknownAndForeignTokensAreIdentical() {
        Workspace workspace = newWorkspace();
        TestCases.Built foreign = saved(newWorkspace());

        Response unknown = readCase(workspace.slug(), TestCases.newToken(), newIp());
        Response fromOtherLink = readCase(workspace.slug(), foreign.token(), newIp());

        assertError(unknown, 404, "CASO_NAO_ENCONTRADO");
        assertThat(fromOtherLink.asByteArray()).isEqualTo(unknown.asByteArray());
        assertThat(headerNames(fromOtherLink)).isEqualTo(headerNames(unknown));
    }

    @Test
    @DisplayName("an unknown slug is LINK_NAO_ENCONTRADO whatever the token")
    void anUnknownSlugWinsOverTheToken() {
        TestCases.Built real = saved(newWorkspace());

        Response withRealToken = readCase("loja-desconhecida-qa", real.token(), newIp());
        Response withGarbage = readCase("loja-desconhecida-qa", "garbage", newIp());

        assertError(withRealToken, 404, "LINK_NAO_ENCONTRADO");
        assertThat(withGarbage.asByteArray()).isEqualTo(withRealToken.asByteArray());
    }

    @Test
    @DisplayName("off-pattern tokens are 404 CASO_NAO_ENCONTRADO, never 400 or 5xx")
    void offPatternTokensAreNotFound() {
        Workspace workspace = newWorkspace();
        TestCases.Built real = saved(workspace);
        String flipped = flipCase(real.token());
        List<String> tokens = List.of("a".repeat(21), "a".repeat(65), "abc+def" + "a".repeat(20), "abc=def" + "a".repeat(20),
                flipped);

        for (String token : tokens) {
            assertError(readCase(workspace.slug(), token, newIp()), 404, "CASO_NAO_ENCONTRADO");
        }
        for (String encoded : List.of("abc%2Fdef" + "a".repeat(20), "..")) {
            Response response = from(newIp()).urlEncodingEnabled(false)
                    .get("/public/v/" + workspace.slug() + "/cases/" + encoded);
            assertThat(response.statusCode()).as(encoded).isIn(400, 404);
            assertThat(response.statusCode()).isLessThan(500);
        }
    }

    private static String flipCase(String token) {
        StringBuilder flipped = new StringBuilder(token);
        for (int i = 0; i < flipped.length(); i++) {
            char c = flipped.charAt(i);
            if (Character.isLetter(c)) {
                flipped.setCharAt(i, Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
                return flipped.toString();
            }
        }
        return token + "A";
    }

    @Test
    @DisplayName("a disabled link still serves an existing case but refuses contact changes")
    void aDisabledLinkStillServesItsCases() {
        Workspace workspace = newWorkspace();
        String token = start(workspace, Cnpjs.unknownToTheStub(), PARTNER);
        awaitOutcome(workspace, token);
        Workspace disabled = workspaces.save(withLink(workspace, false));

        assertThat(readCase(disabled.slug(), token, newIp()).statusCode()).isEqualTo(200);
        assertError(putEmail(disabled.slug(), token, "vendedor@empresa-exemplo.test", newIp()), 404,
                "LINK_NAO_ENCONTRADO");
        assertError(info(disabled.slug()), 404, "LINK_NAO_ENCONTRADO");

        workspaces.save(withLink(workspace, true));
        assertThat(putEmail(workspace.slug(), token, "vendedor@empresa-exemplo.test", newIp()).statusCode())
                .isEqualTo(204);
    }

    static Workspace withLink(Workspace w, boolean ativo) {
        return new Workspace(w.id(), w.nome(), w.slug(), w.categoria(), w.contatoVendedor(), w.plano(), ativo,
                w.autofillAtivo(), w.politica(), w.documentosHabilitados(), w.criadoEm());
    }

    @Test
    @DisplayName("timing smoke: unknown and foreign tokens take comparable time")
    void unknownAndForeignTokensTakeComparableTime() {
        Workspace workspace = newWorkspace();
        TestCases.Built foreign = saved(newWorkspace());
        List<Long> unknown = new ArrayList<>();
        List<Long> other = new ArrayList<>();
        String ip = newIp();

        for (int i = 0; i < 120; i++) {
            unknown.add(timed(() -> readCase(workspace.slug(), TestCases.newToken(), ip)));
            other.add(timed(() -> readCase(workspace.slug(), foreign.token(), ip)));
        }

        Collections.sort(unknown);
        Collections.sort(other);
        long unknownMedian = unknown.get(unknown.size() / 2);
        long otherMedian = other.get(other.size() / 2);
        System.out.println("event=qa_timing_smoke unknown_median_us=" + unknownMedian + " unknown_p90_us="
                + unknown.get(unknown.size() * 9 / 10) + " foreign_median_us=" + otherMedian + " foreign_p90_us="
                + other.get(other.size() * 9 / 10));
        assertThat(Math.abs(unknownMedian - otherMedian)).as("median gap in microseconds")
                .isLessThan(Math.max(5_000, unknownMedian));
    }

    private static long timed(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return (System.nanoTime() - start) / 1_000;
    }

    @Test
    @DisplayName("normal polling is never limited; the 301st read in a minute from one client is 429")
    void pollingToleranceAndTheReadLimit() {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace);
        LimiterClock.INSTANCE.freeze(Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofSeconds(150)));
        String ip = newIp();

        for (int i = 0; i < 300; i++) {
            assertThat(readCase(workspace.slug(), built.token(), ip).statusCode()).as("read " + (i + 1)).isEqualTo(200);
        }
        Response limited = readCase(workspace.slug(), built.token(), ip);
        Response garbage = readCase(workspace.slug(), "garbage", ip);

        assertError(limited, 429, "LIMITE_TENTATIVAS");
        assertRetryAfterAtMost(limited, 60);
        assertError(garbage, 429, "LIMITE_TENTATIVAS");
        assertThat(readCase(workspace.slug(), built.token(), newIp()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a read right after the verification start finds the case")
    void aReadRightAfterTheStartFindsTheCase() {
        Workspace workspace = newWorkspace();
        String ip = newIp();

        for (int i = 0; i < 5; i++) {
            Response created = verify(workspace.slug(), Cnpjs.unknownToTheStub(), PARTNER, ip);
            Response read = readCase(workspace.slug(), created.jsonPath().getString("token"), ip);

            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(json(read).get("estado")).isEqualTo("processing");
        }
    }

    @Test
    @DisplayName("every timestamp on a decided case is an offset date-time")
    void everyTimestampHasAnOffset() {
        Workspace workspace = newWorkspace();
        String token = start(workspace, com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTIAL_MATCH,
                PARTNER);

        Outcome outcome = awaitOutcome(workspace, token);

        @SuppressWarnings("unchecked")
        Map<String, Object> resultado = (Map<String, Object>) outcome.body().get("resultado");
        assertThat((String) outcome.body().get("criado_em")).matches(FrontendContract.OFFSET_DATE_TIME);
        assertThat((String) resultado.get("consultado_em")).matches(FrontendContract.OFFSET_DATE_TIME);
        assertThat((String) resultado.get("prazo_revisao")).matches(FrontendContract.OFFSET_DATE_TIME);
    }

    @Test
    @DisplayName("a disabled link with no contact is not found even for reads")
    void anUncontactedWorkspaceIsNotFoundForReads() {
        Workspace uncontacted = newWorkspace(TestWorkspaces.builder().contato(null));
        TestCases.Built built = saved(uncontacted);

        assertError(readCase(uncontacted.slug(), built.token(), newIp()), 404, "LINK_NAO_ENCONTRADO");
    }
}
