package com.matheusmelo.kybr.acceptance;

import io.restassured.RestAssured;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads one Prometheus sample from the management port. */
final class Metrics {

    private Metrics() {
    }

    /** The value of {@code name} whose label set contains {@code labelFragment} (e.g. {@code slot="CURRENT"}), or 0. */
    static double sample(int managementPort, String name, String labelFragment) {
        String body = RestAssured.given().port(managementPort).get("/actuator/prometheus").asString();
        Pattern line = Pattern.compile("^" + Pattern.quote(name) + "\\{([^}]*)\\} ([0-9.eE+-]+)$", Pattern.MULTILINE);
        Matcher m = line.matcher(body);
        double total = 0;
        while (m.find()) {
            if (m.group(1).contains(labelFragment)) {
                total += Double.parseDouble(m.group(2));
            }
        }
        return total;
    }
}
