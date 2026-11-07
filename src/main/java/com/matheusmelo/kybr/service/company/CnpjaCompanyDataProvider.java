package com.matheusmelo.kybr.service.company;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Found;
import com.matheusmelo.kybr.service.company.CompanyDataResult.NotFound;
import com.matheusmelo.kybr.service.company.CompanyDataResult.RateLimited;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unavailable;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unsupported;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The live provider client ({@code kybr.cnpja.mode=live}). One GET per call on the provider's
 * company endpoint; a full check adds the configured add-ons. The API key travels only in the
 * configured request header, never in a URL.
 *
 * <p>Outcomes: 200 → found (the body is mapped, then dropped); 404 → not found; 400 on an
 * alphanumeric CNPJ → unsupported; 429 → rate limited for its {@code Retry-After} (a minute when
 * absent); anything else, a timeout or an unreadable body → unavailable. A failure is logged as
 * the call type and status only: never the URL, the CNPJ, the body or the key.
 */
@Component
@ConditionalOnProperty(prefix = "kybr.cnpja", name = "mode", havingValue = "live")
public class CnpjaCompanyDataProvider implements CompanyDataProvider {

    private static final Logger log = LoggerFactory.getLogger(CnpjaCompanyDataProvider.class);
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);
    private static final String BASIC = "basic";
    private static final String FULL_CHECK = "full_check";
    /** A company record is a few KiB; anything past this is refused unread. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private final Settings settings;
    private final RestClient lookupClient;
    private final RestClient workerClient;
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * @param registrations the states whose registrations a full check fetches: {@code BR} or a
     *     comma-separated list
     */
    record Settings(URI baseUrl, String apiKey, String authHeader, String strategy, int maxAgeDays,
            boolean simples, String registrations, Duration connect, Duration lookupRead, Duration workerRead) {

        static Settings of(KybrProperties.Cnpja cnpja) {
            return new Settings(cnpja.baseUrl(), cnpja.apiKey(), cnpja.authHeader(), cnpja.strategy(),
                    cnpja.maxAgeDays(), cnpja.addons().simples(), cnpja.addons().registrations(),
                    cnpja.timeouts().connect(), cnpja.timeouts().lookupRead(), cnpja.timeouts().workerRead());
        }

        /** Never prints the key. */
        @Override
        public String toString() {
            return "Settings[authHeader=" + authHeader + ", strategy=" + strategy + ", maxAgeDays=" + maxAgeDays + "]";
        }
    }

    @Autowired
    public CnpjaCompanyDataProvider(KybrProperties properties) {
        this(Settings.of(properties.cnpja()));
    }

    CnpjaCompanyDataProvider(Settings settings) {
        this(settings, client(settings, settings.lookupRead()), client(settings, settings.workerRead()));
    }

    CnpjaCompanyDataProvider(Settings settings, RestClient lookupClient, RestClient workerClient) {
        this.settings = settings;
        this.lookupClient = lookupClient;
        this.workerClient = workerClient;
    }

    @Override
    public CompanyDataResult<BasicCompany> basic(String cnpj) {
        return call(BASIC, lookupClient, cnpj, false, CnpjaOfficeMapper::basic);
    }

    @Override
    public CompanyDataResult<CompanyRecord> fullCheck(String cnpj) {
        return call(FULL_CHECK, workerClient, cnpj, true, CnpjaOfficeMapper::record);
    }

    @Override
    public String toString() {
        return "CnpjaCompanyDataProvider[" + settings + "]";
    }

    private <T> CompanyDataResult<T> call(String tipo, RestClient client, String cnpj, boolean addons,
            Function<JsonNode, T> toResult) {
        try {
            return client.get()
                    .uri(builder -> uri(builder, cnpj, addons))
                    .header(settings.authHeader(), settings.apiKey())
                    .exchange((request, response) -> outcome(tipo, cnpj, response.getStatusCode(),
                            response.getHeaders(), response.getBody(), toResult), true);
        } catch (RestClientException e) {
            log.warn("event=provider_error tipo={} status=none error={}", tipo, rootType(e));
            return new Unavailable<>();
        }
    }

    private URI uri(UriBuilder builder, String cnpj, boolean addons) {
        UriBuilder uri = builder.path("/office/{cnpj}")
                .queryParam("strategy", settings.strategy())
                .queryParam("maxAge", settings.maxAgeDays());
        if (addons) {
            uri.queryParam("simples", settings.simples()).queryParam("registrations", settings.registrations());
        }
        return uri.build(cnpj);
    }

    private <T> CompanyDataResult<T> outcome(String tipo, String cnpj, HttpStatusCode status, HttpHeaders headers,
            InputStream body, Function<JsonNode, T> toResult) throws IOException {
        int code = status.value();
        if (code == 200) {
            byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                log.warn("event=provider_error tipo={} status=200 error=body_too_large", tipo);
                return new Unavailable<>();
            }
            Optional<T> parsed = parse(bytes, toResult);
            if (parsed.isPresent()) {
                return new Found<>(parsed.get());
            }
            log.warn("event=provider_error tipo={} status=200 error=unreadable_body", tipo);
            return new Unavailable<>();
        }
        if (code == 404) {
            return new NotFound<>();
        }
        log.warn("event=provider_error tipo={} status={}", tipo, code);
        if (code == 400 && !cnpj.chars().allMatch(Character::isDigit)) {
            return new Unsupported<>();
        }
        if (code == 429) {
            return new RateLimited<>(retryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)));
        }
        return new Unavailable<>();
    }

    /** The body read through {@code toResult}, or empty when it isn't JSON. Holds the body, so never logs. */
    private <T> Optional<T> parse(byte[] body, Function<JsonNode, T> toResult) {
        try {
            return Optional.of(toResult.apply(mapper.readTree(body)));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private static Duration retryAfter(String header) {
        if (header != null && header.matches("[0-9]{1,6}")) {
            long seconds = Long.parseLong(header);
            return seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_RETRY_AFTER;
        }
        return DEFAULT_RETRY_AFTER;
    }

    /** The innermost exception's type: its message may carry the URL. */
    private static String rootType(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private static RestClient client(Settings settings, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(settings.connect()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(settings.baseUrl().toString()).requestFactory(factory).build();
    }
}
