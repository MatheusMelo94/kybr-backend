package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.CompanyCacheEntry;
import com.matheusmelo.kybr.model.link.ProjecaoEmpresa;
import com.matheusmelo.kybr.repository.CompanyCacheEntryRepository;
import com.matheusmelo.kybr.service.company.CompanyCache.Lookup;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.MovableClock;
import java.time.Duration;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

class CompanyCacheIT extends MongoIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final String CNPJ = "36925814000184";

    @Autowired
    private CompanyCacheEntryRepository repository;
    @Autowired
    private MongoTemplate mongo;

    private final MovableClock clock = new MovableClock(NOW);
    private CompanyCache cache;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        cache = new CompanyCache(repository, Duration.ofDays(30), Duration.ofDays(1), clock);
    }

    @Test
    void aFoundCompanyIsStoredWithItsCpfMaskedAndOnlyTheLookupFields() {
        ProjecaoEmpresa stored = cache.storeFound(CNPJ,
                new BasicCompany("JOAO CARLOS SILVA 12345678909", "Belo Horizonte", "mg", "Comercio varejista"))
                .orElseThrow();

        assertThat(stored).isEqualTo(new ProjecaoEmpresa("JOAO CARLOS SILVA ***.***.***-**", "Belo Horizonte", "MG",
                "Comercio varejista"));
        Document raw = mongo.getCollection(CompanyCacheEntry.COLLECTION).find(new Document("_id", CNPJ)).first();
        assertThat(raw.toJson()).doesNotContain("12345678909");
        assertThat(raw.get("projecao", Document.class).keySet())
                .containsExactlyInAnyOrder("razao_social", "municipio", "uf", "cnae_descricao");
        assertThat(raw.getDate("expira_em").toInstant()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(cache.read(CNPJ)).isEqualTo(new Lookup.Hit(stored));
    }

    @Test
    void aCompanyMissingARequiredFieldIsNeverStored() {
        assertThat(cache.storeFound(CNPJ, new BasicCompany("Loja Exemplo Ltda", null, "SP", "Comercio"))).isEmpty();
        assertThat(cache.storeFound(CNPJ, new BasicCompany("Loja Exemplo Ltda", "Sao Paulo", "SP", " "))).isEmpty();
        assertThat(cache.storeFound(CNPJ, new BasicCompany(" ", "Sao Paulo", "SP", "Comercio"))).isEmpty();
        assertThat(cache.storeFound(CNPJ, new BasicCompany("Loja Exemplo Ltda", "Sao Paulo", null, "Comercio")))
                .isEmpty();
        assertThat(cache.storeFound(CNPJ, new BasicCompany("Loja Exemplo Ltda", "Sao Paulo", "XX", "Comercio")))
                .as("not a state code").isEmpty();

        assertThat(repository.count()).isZero();
        assertThat(cache.read(CNPJ)).isEqualTo(new Lookup.Miss());
    }

    @Test
    void aNotFoundAnswerIsCachedForTheNegativeTtl() {
        cache.storeNotFound(CNPJ);

        assertThat(cache.read(CNPJ)).isEqualTo(new Lookup.NegativeHit());
        clock.advance(Duration.ofDays(1));
        assertThat(cache.read(CNPJ)).isEqualTo(new Lookup.Miss());
    }

    @Test
    void aReadAfterExpiryMissesEvenBeforeTheTtlMonitorRemovesTheEntry() {
        cache.storeFound(CNPJ, new BasicCompany("Loja Exemplo Ltda", "Sao Paulo", "SP", "Comercio")).orElseThrow();

        clock.advance(Duration.ofDays(30).minusSeconds(1));
        assertThat(cache.read(CNPJ)).isInstanceOf(Lookup.Hit.class);
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.read(CNPJ)).isEqualTo(new Lookup.Miss());
        assertThat(repository.existsById(CNPJ)).isTrue();
    }

    @Test
    void anUnknownCnpjMisses() {
        assertThat(cache.read("12345678000195")).isEqualTo(new Lookup.Miss());
    }
}
