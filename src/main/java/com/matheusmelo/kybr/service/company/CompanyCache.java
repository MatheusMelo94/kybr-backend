package com.matheusmelo.kybr.service.company;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.CompanyCacheEntry;
import com.matheusmelo.kybr.model.link.ProjecaoEmpresa;
import com.matheusmelo.kybr.repository.CompanyCacheEntryRepository;
import com.matheusmelo.kybr.util.CpfMasker;
import com.matheusmelo.kybr.util.Uf;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The local company-lookup cache, keyed by canonical CNPJ: the lookup projection (positive TTL,
 * 30 d) or a "not found" answer (negative TTL, 1 d). Only the four lookup fields are stored, never
 * an address or an activity code, and the CPF in an individual's company name is masked before
 * the write. Freshness is decided by the application clock, so an entry past {@code expira_em}
 * misses even before the TTL monitor deletes it.
 */
@Service
public class CompanyCache {

    /** What the cache knows about a CNPJ. */
    public sealed interface Lookup {
        /** A fresh, CPF-masked projection. */
        record Hit(ProjecaoEmpresa projecao) implements Lookup {
        }

        /** A fresh "not found" answer. */
        record NegativeHit() implements Lookup {
        }

        /** Nothing fresh. */
        record Miss() implements Lookup {
        }
    }

    private final CompanyCacheEntryRepository repository;
    private final Duration positiveTtl;
    private final Duration negativeTtl;
    private final Clock clock;

    @Autowired
    public CompanyCache(CompanyCacheEntryRepository repository, KybrProperties properties, Clock clock) {
        this(repository, properties.cache().positiveTtl(), properties.cache().negativeTtl(), clock);
    }

    CompanyCache(CompanyCacheEntryRepository repository, Duration positiveTtl, Duration negativeTtl, Clock clock) {
        this.repository = repository;
        this.positiveTtl = positiveTtl;
        this.negativeTtl = negativeTtl;
        this.clock = clock;
    }

    public Lookup read(String cnpj) {
        Instant now = clock.instant();
        return repository.findById(cnpj)
                .filter(entry -> entry.expiraEm() != null && now.isBefore(entry.expiraEm()))
                .<Lookup>map(entry -> entry.naoEncontrada() || entry.projecao() == null
                        ? new Lookup.NegativeHit()
                        : new Lookup.Hit(entry.projecao()))
                .orElseGet(Lookup.Miss::new);
    }

    /**
     * Caches a found company and returns the stored projection; empty, with nothing stored, when
     * the record lacks a company name, city, state or activity description, or its state isn't a
     * state code.
     */
    public Optional<ProjecaoEmpresa> storeFound(String cnpj, BasicCompany company) {
        if (blank(company.razaoSocial()) || blank(company.municipio()) || blank(company.uf())
                || blank(company.cnaeDescricao()) || !Uf.isValid(company.uf().strip().toUpperCase(Locale.ROOT))) {
            return Optional.empty();
        }
        ProjecaoEmpresa projecao = new ProjecaoEmpresa(CpfMasker.mask(company.razaoSocial().strip()),
                company.municipio().strip(), company.uf().strip().toUpperCase(Locale.ROOT),
                company.cnaeDescricao().strip());
        Instant now = clock.instant();
        repository.save(CompanyCacheEntry.found(cnpj, projecao, now, now.plus(positiveTtl)));
        return Optional.of(projecao);
    }

    public void storeNotFound(String cnpj) {
        Instant now = clock.instant();
        repository.save(CompanyCacheEntry.notFound(cnpj, now, now.plus(negativeTtl)));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
