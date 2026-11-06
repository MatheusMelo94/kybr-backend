package com.matheusmelo.kybr.support.web;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.specification.RequestSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Base for web integration tests: the full app on a random port (the management port is
 * randomised too), real MongoDB, driven over HTTP with REST Assured, redirects never followed.
 *
 * <p>Every subclass shares one cached context; don't add per-class properties or beans.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    // A per-route body limit override (the 3 MiB test route).
    "kybr.limits.routes[0].method=POST",
    "kybr.limits.routes[0].pattern=/test/web/large",
    "kybr.limits.routes[0].max-body=3MB"
})
public abstract class WebIntegrationTest extends MongoIntegrationTest {

    public static final String BFF_KEY_HEADER = "X-Kybr-BFF-Key";

    @LocalServerPort
    protected int port;

    @Autowired
    protected KybrProperties properties;

    /** A request from the BFF: carries the configured current key; redirects not followed. */
    protected RequestSpecification http() {
        return httpWithoutKey().header(BFF_KEY_HEADER, bffKey());
    }

    /** A request without the BFF key; redirects not followed. */
    protected RequestSpecification httpWithoutKey() {
        return RestAssured.given()
                .port(port)
                .config(RestAssuredConfig.config().redirect(RedirectConfig.redirectConfig().followRedirects(false)));
    }

    /** The configured current BFF key. */
    protected String bffKey() {
        return properties.bff().current();
    }
}
