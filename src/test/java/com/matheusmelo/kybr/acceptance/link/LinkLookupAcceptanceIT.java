package com.matheusmelo.kybr.acceptance.link;

import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.APPROVED;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.APPROVED_ALPHANUMERIC;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.INCOMPLETE_RECORD;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.MEI_MASKED;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.MEI_UNMASKED;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.NOT_FOUND;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTIAL_MATCH;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.TIMEOUT;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.UNAVAILABLE;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.UNMASKED_CPF;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.matheusmelo.kybr.model.RateLimitCounter;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.CompanyCacheEntry;
import com.matheusmelo.kybr.model.link.CreditLedger;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.Seller;
import com.matheusmelo.kybr.model.workspace.Plano;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.company.CachedCompanyLookupService;
import com.matheusmelo.kybr.service.company.RuntimeFlagsService;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import com.matheusmelo.kybr.support.web.LimiterClock;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** Company lookup (E02): cache first, the kill switches, the check order, limits and provider failures. */
class LinkLookupAcceptanceIT extends LinkAcceptanceTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Autowired
    private RuntimeFlagsService flags;

    @AfterEach
    void restoreGlobalAutofill() {
        flags.setAutofill(true);
    }

    private void forget(String cnpj) {
        mongo.remove(query(where("_id").is(cnpj)), CompanyCacheEntry.COLLECTION);
    }

    /**
     * Clears every per-client lookup counter. The daily window is shared by the whole run (and by
     * earlier runs on the same day), so a random client IP that another lookup already used today
     * would start a count above zero.
     */
    private void forgetClientLookupCounters() {
        mongo.remove(query(where("_id").regex("^link\\.lookup\\.ip(-daily)?:")), RateLimitCounter.COLLECTION);
    }

    private long ledgerD2() {
        CreditLedger ledger = mongo.findById(CreditLedger.idFor(YearMonth.now(SAO_PAULO)), CreditLedger.class);
        return ledger == null ? 0 : ledger.d2();
    }

    private double lookups(Workspace workspace, String resultado) {
        return metric("kybr_link_lookup_total", "slug=\"" + workspace.slug() + "\"", "resultado=\"" + resultado + "\"");
    }

    @Test
    @DisplayName("a fresh lookup answers exactly the four projection fields")
    void aFreshLookupAnswersTheProjection() {
        Workspace workspace = newWorkspace();
        forget(APPROVED);

        Response response = lookup(workspace.slug(), APPROVED, newIp());

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = json(response);
        assertThat(body).containsOnlyKeys("razao_social", "municipio", "uf", "cnae");
        assertThat(body.get("cnae")).isEqualTo(Map.of("descricao",
                "Comércio varejista de outros produtos não especificados anteriormente"));
        assertThat(body).containsEntry("razao_social", "Loja Exemplo Comercio de Utilidades Ltda")
                .containsEntry("municipio", "São Paulo").containsEntry("uf", "SP");
        assertThat(response.asString()).doesNotContain("endereco", "codigo", "Rua Exemplo", "4789099");
        assertThat(ledgerD2()).isEqualTo(1);
    }

    @Test
    @DisplayName("a micro-entrepreneur's CPF is masked in the answer and in the cache")
    void aMicroEntrepreneursCpfIsMasked() {
        Workspace workspace = newWorkspace();
        forget(MEI_MASKED);
        forget(MEI_UNMASKED);

        Response masked = lookup(workspace.slug(), MEI_MASKED, newIp());
        Response unmasked = lookup(workspace.slug(), MEI_UNMASKED, newIp());

        assertThat(masked.jsonPath().getString("razao_social")).isEqualTo("JOAO CARLOS SILVA ***.***.***-**");
        assertThat(unmasked.jsonPath().getString("razao_social")).isEqualTo("JOAO CARLOS SILVA ***.***.***-**");
        Document cached = mongo.getCollection(CompanyCacheEntry.COLLECTION).find(new Document("_id", MEI_UNMASKED))
                .first();
        assertThat(cached).isNotNull();
        assertThat(cached.toJson()).doesNotContain(UNMASKED_CPF).contains("***.***.***-**");
    }

    @ParameterizedTest(name = "cnpj=[{0}] is 400 CNPJ_INVALIDO")
    @ValueSource(strings = {"12ABC34501DE36", "12ABC34501DE3A", "00000000000000", "12abc34501de35",
        "12.345.678/0001-95", "", "1234567800019", "123456780001950"})
    void anInvalidCnpjIs400(String cnpj) {
        Workspace workspace = newWorkspace();

        assertError(lookup(workspace.slug(), cnpj, newIp()), 400, "CNPJ_INVALIDO", "cnpj");
    }

    @Test
    @DisplayName("a missing or repeated cnpj parameter is 400 CNPJ_INVALIDO")
    void aMissingOrRepeatedCnpjIs400() {
        Workspace workspace = newWorkspace();
        String path = "/public/v/" + workspace.slug() + "/lookup";

        assertError(from(newIp()).get(path), 400, "CNPJ_INVALIDO", "cnpj");
        assertError(from(newIp()).queryParam("cnpj", APPROVED).queryParam("cnpj", PARTIAL_MATCH).get(path),
                400, "CNPJ_INVALIDO", "cnpj");
    }

    @Test
    @DisplayName("not found is 404 and is cached negatively")
    void notFoundIsCachedNegatively() {
        Workspace workspace = newWorkspace();
        forget(NOT_FOUND);

        assertError(lookup(workspace.slug(), NOT_FOUND, newIp()), 404, "EMPRESA_NAO_ENCONTRADA");
        long afterFirst = ledgerD2();
        assertError(lookup(workspace.slug(), NOT_FOUND, newIp()), 404, "EMPRESA_NAO_ENCONTRADA");

        assertThat(afterFirst).isEqualTo(1);
        assertThat(ledgerD2()).as("the second answer came from the cache").isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("a second lookup is a byte-identical cache hit that costs nothing")
    void aSecondLookupIsAFreeCacheHit() {
        Workspace workspace = newWorkspace();
        forget(APPROVED_ALPHANUMERIC);

        Response fresh = lookup(workspace.slug(), APPROVED_ALPHANUMERIC, newIp());
        long afterFresh = ledgerD2();
        double hitsBefore = lookups(workspace, "hit");
        Response hit = lookup(workspace.slug(), APPROVED_ALPHANUMERIC, newIp());

        assertThat(fresh.statusCode()).isEqualTo(200);
        assertThat(hit.asByteArray()).isEqualTo(fresh.asByteArray());
        assertThat(afterFresh).isEqualTo(1);
        assertThat(ledgerD2()).isEqualTo(afterFresh);
        assertThat(lookups(workspace, "hit")).isEqualTo(hitsBefore + 1);
    }

    @Test
    @DisplayName("the check order: slug, kill switch, CNPJ, then the limit before any cache read")
    void theCheckOrder() {
        Workspace autofillOff = newWorkspace(TestWorkspaces.builder().autofillAtivo(false));
        Workspace workspace = newWorkspace();
        lookup(workspace.slug(), APPROVED, newIp());

        assertError(lookup("loja-desconhecida-qa", "123", newIp()), 404, "LINK_NAO_ENCONTRADO");
        assertError(lookup(autofillOff.slug(), "123", newIp()), 503, "AUTOFILL_DESATIVADO");
        flags.setAutofill(false);
        assertError(lookup(workspace.slug(), APPROVED, newIp()), 503, "AUTOFILL_DESATIVADO");
        flags.setAutofill(true);

        LimiterClock.INSTANCE.freeze(midMinute());
        String ip = newIp();
        for (int i = 0; i < 5; i++) {
            assertThat(lookup(workspace.slug(), APPROVED, ip).statusCode()).isEqualTo(200);
        }
        double readsBefore = metric("kybr_link_lookup_cache_reads_total", "slug=\"" + workspace.slug() + "\"");
        Response limited = lookup(workspace.slug(), APPROVED, ip);
        assertError(limited, 429, "LIMITE_CONSULTAS");
        assertThat(metric("kybr_link_lookup_cache_reads_total", "slug=\"" + workspace.slug() + "\""))
                .as("no cache read once limited").isEqualTo(readsBefore);
        assertError(lookup(workspace.slug(), "123", ip), 400, "CNPJ_INVALIDO", "cnpj");
    }

    @Test
    @DisplayName("the global and the per-link switches turn lookups off without a provider call")
    void theSwitchesTurnLookupsOff() {
        Workspace workspace = newWorkspace();
        Workspace autofillOff = newWorkspace(TestWorkspaces.builder().autofillAtivo(false));
        forget(PARTIAL_MATCH);

        flags.setAutofill(false);
        assertError(lookup(workspace.slug(), PARTIAL_MATCH, newIp()), 503, "AUTOFILL_DESATIVADO");
        assertThat(ledgerD2()).isZero();
        flags.setAutofill(true);
        assertError(lookup(autofillOff.slug(), PARTIAL_MATCH, newIp()), 503, "AUTOFILL_DESATIVADO");
        assertThat(ledgerD2()).isZero();

        assertThat(lookup(workspace.slug(), PARTIAL_MATCH, newIp()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a trial link reads the cache only")
    void aTrialLinkReadsTheCacheOnly() {
        Workspace trial = newWorkspace(TestWorkspaces.builder().plano(Plano.Tipo.teste));
        Workspace paid = newWorkspace();
        forget(APPROVED);
        forget(PARTIAL_MATCH);
        lookup(paid.slug(), APPROVED, newIp());
        long before = ledgerD2();

        assertThat(lookup(trial.slug(), APPROVED, newIp()).statusCode()).isEqualTo(200);
        assertError(lookup(trial.slug(), PARTIAL_MATCH, newIp()), 503, "AUTOFILL_DESATIVADO");
        assertThat(ledgerD2()).isEqualTo(before);
    }

    @Test
    @DisplayName("an unavailable or slow provider is 503 INDISPONIVEL within the timeout, never cached")
    void providerFailuresAreUnavailableAndNotCached() {
        Workspace workspace = newWorkspace();

        assertError(lookup(workspace.slug(), UNAVAILABLE, newIp()), 503, "INDISPONIVEL");
        long start = System.nanoTime();
        Response slow = lookup(workspace.slug(), TIMEOUT, newIp());
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertError(slow, 503, "INDISPONIVEL");
        assertThat(took).isLessThanOrEqualTo(Duration.ofSeconds(4));
        assertThat(mongo.findById(UNAVAILABLE, CompanyCacheEntry.class)).isNull();
        assertThat(mongo.findById(TIMEOUT, CompanyCacheEntry.class)).isNull();
        double errors = lookups(workspace, "error");
        assertError(lookup(workspace.slug(), UNAVAILABLE, newIp()), 503, "INDISPONIVEL");
        assertThat(lookups(workspace, "error")).as("reached the provider again").isEqualTo(errors + 1);
    }

    @Test
    @DisplayName("an unsupported CNPJ is 503 INDISPONIVEL and an event without the CNPJ")
    void anUnsupportedCnpjIsUnavailableWithAnEvent() {
        Workspace workspace = newWorkspace();

        try (CapturedEvents events = CapturedEvents.of(CachedCompanyLookupService.class)) {
            assertError(lookup(workspace.slug(), UNSUPPORTED, newIp()), 503, "INDISPONIVEL");

            List<String> messages = events.events().stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(m -> m.contains("event=provider_unsupported_cnpj"));
            assertThat(messages).noneMatch(m -> m.contains(UNSUPPORTED));
        }
    }

    @Test
    @DisplayName("the sixth lookup in a minute from one client is 429 LIMITE_CONSULTAS; others are unaffected")
    void thePerClientMinuteLimit() {
        Workspace workspace = newWorkspace();
        lookup(workspace.slug(), APPROVED, newIp());
        LimiterClock.INSTANCE.freeze(midMinute());
        String ip = newIp();

        for (int i = 0; i < 5; i++) {
            assertThat(lookup(workspace.slug(), APPROVED, ip).statusCode()).as("call " + (i + 1)).isEqualTo(200);
        }
        Response limited = lookup(workspace.slug(), APPROVED, ip);

        assertError(limited, 429, "LIMITE_CONSULTAS");
        assertRetryAfterAtMost(limited, 60);
        assertThat(lookup(workspace.slug(), APPROVED, newIp()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("the thirty-first lookup in a day from one client is 429 LIMITE_CONSULTAS")
    void thePerClientDailyLimit() {
        Workspace workspace = newWorkspace();
        lookup(workspace.slug(), APPROVED, newIp());
        Instant minute = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofHours(12));
        String ip = newIp();
        forgetClientLookupCounters();

        for (int i = 0; i < 30; i++) {
            if (i % 5 == 0) {
                minute = minute.plus(Duration.ofMinutes(1));
                LimiterClock.INSTANCE.freeze(minute.plusSeconds(30));
            }
            assertThat(lookup(workspace.slug(), APPROVED, ip).statusCode()).as("call " + (i + 1)).isEqualTo(200);
        }
        LimiterClock.INSTANCE.freeze(minute.plus(Duration.ofMinutes(1)).plusSeconds(30));
        Response limited = lookup(workspace.slug(), APPROVED, ip);

        assertError(limited, 429, "LIMITE_CONSULTAS");
        assertRetryAfterAtMost(limited, 86_400);
        assertThat(Integer.parseInt(limited.header("Retry-After"))).isGreaterThan(60);
    }

    @Test
    @DisplayName("without a client IP the per-link shared limit applies, and links are independent")
    void withoutAClientIpTheSharedPerLinkLimitApplies() {
        Workspace first = newWorkspace();
        Workspace second = newWorkspace();
        lookup(first.slug(), APPROVED, newIp());
        LimiterClock.INSTANCE.freeze(midMinute());

        for (int i = 0; i < 120; i++) {
            assertThat(http().queryParam("cnpj", APPROVED).get("/public/v/" + first.slug() + "/lookup").statusCode())
                    .as("call " + (i + 1)).isEqualTo(200);
        }
        Response limited = http().queryParam("cnpj", APPROVED).get("/public/v/" + first.slug() + "/lookup");

        assertError(limited, 429, "LIMITE_CONSULTAS");
        assertThat(http().queryParam("cnpj", APPROVED).get("/public/v/" + second.slug() + "/lookup").statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("an incomplete provider record is 503 INDISPONIVEL, never an empty field, never cached")
    void anIncompleteRecordIsUnavailable() {
        Workspace workspace = newWorkspace();
        forget(INCOMPLETE_RECORD);

        try (CapturedEvents events = CapturedEvents.of(CachedCompanyLookupService.class)) {
            assertError(lookup(workspace.slug(), INCOMPLETE_RECORD, newIp()), 503, "INDISPONIVEL");

            assertThat(events.events()).anyMatch(e -> e.getFormattedMessage().contains("event=provider_incomplete_record"));
        }
        assertThat(mongo.findById(INCOMPLETE_RECORD, CompanyCacheEntry.class)).isNull();
    }

    @Test
    @DisplayName("a lookup never creates a case, a seller or an audit event")
    void aLookupWritesNothingElse() {
        Workspace workspace = newWorkspace();

        lookup(workspace.slug(), APPROVED, newIp());
        lookup(workspace.slug(), NOT_FOUND, newIp());
        lookup(workspace.slug(), "123", newIp());
        lookup(workspace.slug(), UNAVAILABLE, newIp());

        assertThat(mongo.count(query(where(LinkCase.WORKSPACE_ID).is(workspace.id())), LinkCase.class)).isZero();
        assertThat(mongo.count(query(where(Seller.WORKSPACE_ID).is(workspace.id())), Seller.class)).isZero();
        assertThat(mongo.count(query(where(AuditEvent.WORKSPACE_ID).is(workspace.id())), AuditEvent.class)).isZero();
    }

    @Test
    @DisplayName("each outcome moves its own lookup counter once, tagged with the link")
    void eachOutcomeIsCountedOnce() {
        Workspace workspace = newWorkspace();
        Workspace trial = newWorkspace(TestWorkspaces.builder().plano(Plano.Tipo.teste));
        Workspace off = newWorkspace(TestWorkspaces.builder().autofillAtivo(false));
        forget(PARTIAL_MATCH);
        forget(NOT_FOUND);
        forget(MEI_MASKED);

        lookup(workspace.slug(), PARTIAL_MATCH, newIp());
        lookup(workspace.slug(), PARTIAL_MATCH, newIp());
        lookup(workspace.slug(), NOT_FOUND, newIp());
        lookup(trial.slug(), MEI_MASKED, newIp());
        lookup(off.slug(), APPROVED, newIp());
        lookup(workspace.slug(), "123", newIp());
        lookup(workspace.slug(), UNAVAILABLE, newIp());

        assertThat(lookups(workspace, "fresh")).isEqualTo(1);
        assertThat(lookups(workspace, "hit")).isEqualTo(1);
        assertThat(lookups(workspace, "not_found")).isEqualTo(1);
        assertThat(lookups(trial, "cache_only_miss")).isEqualTo(1);
        assertThat(lookups(off, "disabled")).isEqualTo(1);
        assertThat(lookups(workspace, "invalid")).isEqualTo(1);
        assertThat(lookups(workspace, "error")).isEqualTo(1);
        assertThat(metric("kybr_link_lookup_total", "slug=\"loja-desconhecida-qa\"")).isZero();
    }

    private static Instant midMinute() {
        return Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofSeconds(150));
    }
}
