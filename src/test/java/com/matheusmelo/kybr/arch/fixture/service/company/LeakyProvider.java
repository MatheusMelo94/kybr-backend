package com.matheusmelo.kybr.arch.fixture.service.company;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Rule fixture, never run: a provider adapter that logs where it holds the provider's response. */
public class LeakyProvider {

    private static final Logger log = LoggerFactory.getLogger(LeakyProvider.class);

    ObjectMapper mapper;

    void parsed(String body) {
        JsonNode node = mapper.readTree(body);
        log.info("body={}", node);
    }

    void raw(ClientHttpResponse response) {
        log.info("response={}", response);
    }

    void failed(Runnable call) {
        try {
            call.run();
        } catch (RestClientResponseException e) {
            log.warn("error={}", e.getResponseBodyAsString());
        }
    }
}
