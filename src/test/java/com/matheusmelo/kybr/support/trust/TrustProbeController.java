package com.matheusmelo.kybr.support.trust;

import com.matheusmelo.kybr.config.filter.PathFirewallFilter;
import com.matheusmelo.kybr.model.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.ServletRequestPathUtils;

/** Test-only endpoints for the trust boundary. */
@RestController
@RequestMapping("/test/trust")
public class TrustProbeController {

    /** The firewall's cached path next to the dispatcher's own parse. */
    @GetMapping("/path/**")
    Map<String, Object> path(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firewall", PathFirewallFilter.requestPath(request).value());
        body.put("dispatcher", ServletRequestPathUtils.getParsedRequestPath(request).value());
        return body;
    }

    /** The resolved client IP. */
    @GetMapping("/client-ip")
    Map<String, Object> clientIp(HttpServletRequest request) {
        ClientIp ip = (ClientIp) request.getAttribute(ClientIp.ATTRIBUTE);
        return Map.of("ip", ip.ip(), "source", ip.source().name());
    }

    /** What the handler can see of the request headers. */
    @GetMapping("/headers")
    Map<String, Object> headers(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("names", Collections.list(request.getHeaderNames()));
        body.put("bff_key", String.valueOf(request.getHeader("X-Kybr-BFF-Key")));
        return body;
    }
}
