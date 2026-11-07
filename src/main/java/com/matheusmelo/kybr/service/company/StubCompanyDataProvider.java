package com.matheusmelo.kybr.service.company;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Found;
import com.matheusmelo.kybr.service.company.CompanyDataResult.NotFound;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unavailable;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unsupported;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The provider stub ({@code kybr.cnpja.mode=stub}: local, docker, test and staging). It replays
 * the synthetic fixtures in {@code provider-fixtures/}, one per CNPJ, each shaped like a provider
 * response and run through the live client's mapper. No fixture is a real company.
 *
 * <p>A fixture's {@code outcome} is {@code found}, {@code not_found}, {@code unavailable},
 * {@code unsupported} or {@code timeout} (waits out the call's read timeout, then unavailable).
 * {@code founded_days_ago} sets the opening date relative to today in São Paulo, so "recent"
 * scenarios never go stale. {@code delay: worker-read-minus-2s} makes the full check answer two
 * seconds inside the worker's read timeout. A CNPJ without a fixture is not found.
 */
@Component
@ConditionalOnProperty(prefix = "kybr.cnpja", name = "mode", havingValue = "stub", matchIfMissing = true)
public class StubCompanyDataProvider implements CompanyDataProvider {

    static final String FIXTURES = "classpath:provider-fixtures/*.json";
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final Duration SLOW_MARGIN = Duration.ofSeconds(2);

    private final Duration lookupRead;
    private final Duration workerRead;
    private final Clock clock;
    private final Map<String, Fixture> fixtures;

    @Autowired
    public StubCompanyDataProvider(KybrProperties properties, Clock clock) {
        this(properties.cnpja().timeouts().lookupRead(), properties.cnpja().timeouts().workerRead(), clock);
    }

    StubCompanyDataProvider(Duration lookupRead, Duration workerRead, Clock clock) {
        this.lookupRead = lookupRead;
        this.workerRead = workerRead;
        this.clock = clock;
        this.fixtures = load();
    }

    @Override
    public CompanyDataResult<BasicCompany> basic(String cnpj) {
        return answer(cnpj, lookupRead, Duration.ZERO, CnpjaOfficeMapper::basic);
    }

    @Override
    public CompanyDataResult<CompanyRecord> fullCheck(String cnpj) {
        Fixture fixture = fixtures.get(cnpj);
        Duration delay = fixture != null && fixture.slow() ? positiveOrZero(workerRead.minus(SLOW_MARGIN)) : Duration.ZERO;
        return answer(cnpj, workerRead, delay, CnpjaOfficeMapper::record);
    }

    private <T> CompanyDataResult<T> answer(String cnpj, Duration readTimeout, Duration delay,
            Function<JsonNode, T> mapper) {
        Fixture fixture = fixtures.get(cnpj);
        if (fixture == null) {
            return new NotFound<>();
        }
        return switch (fixture.outcome()) {
            case FOUND -> {
                pause(delay);
                yield new Found<>(mapper.apply(fixture.responseAsOf(LocalDate.now(clock.withZone(SAO_PAULO)))));
            }
            case NOT_FOUND -> new NotFound<>();
            case UNAVAILABLE -> new Unavailable<>();
            case UNSUPPORTED -> new Unsupported<>();
            case TIMEOUT -> {
                pause(readTimeout);
                yield new Unavailable<>();
            }
        };
    }

    private static void pause(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration positiveOrZero(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static Map<String, Fixture> load() {
        JsonMapper mapper = JsonMapper.builder().build();
        Map<String, Fixture> loaded = new HashMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(FIXTURES)) {
                try (InputStream in = resource.getInputStream()) {
                    JsonNode doc = mapper.readTree(in);
                    String cnpj = resource.getFilename().replace(".json", "");
                    loaded.put(cnpj, Fixture.of(doc));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("provider fixtures could not be read", e);
        }
        return Map.copyOf(loaded);
    }

    private enum Outcome { FOUND, NOT_FOUND, UNAVAILABLE, UNSUPPORTED, TIMEOUT }

    private record Fixture(Outcome outcome, boolean slow, Integer foundedDaysAgo, ObjectNode response) {

        static Fixture of(JsonNode doc) {
            Outcome outcome = Outcome.valueOf(doc.path("outcome").asString().toUpperCase(Locale.ROOT));
            boolean slow = "worker-read-minus-2s".equals(doc.path("delay").asString(null));
            Integer daysAgo = doc.path("founded_days_ago").isIntegralNumber() ? doc.path("founded_days_ago").intValue()
                    : null;
            ObjectNode response = doc.path("response").isObject() ? (ObjectNode) doc.path("response") : null;
            if (outcome == Outcome.FOUND && response == null) {
                throw new IllegalStateException("a found provider fixture needs a response");
            }
            return new Fixture(outcome, slow, daysAgo, response);
        }

        /** A copy of the response with the opening date set relative to {@code today}. */
        JsonNode responseAsOf(LocalDate today) {
            ObjectNode copy = response.deepCopy();
            if (foundedDaysAgo != null) {
                copy.put("founded", today.minusDays(foundedDaysAgo).toString());
            }
            return copy;
        }
    }
}
