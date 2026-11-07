package com.matheusmelo.kybr.support.web;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * Test-only endpoint, exposed on the management port by {@link WebIntegrationTest}: stands for an
 * endpoint someone adds later without a security rule naming it, which must stay denied.
 */
@Component
@Endpoint(id = ManagementProbeEndpoint.ID)
public class ManagementProbeEndpoint {

    public static final String ID = "mgmtprobe";

    @ReadOperation
    public String probe() {
        return "reachable";
    }
}
