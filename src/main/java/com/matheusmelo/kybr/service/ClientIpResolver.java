package com.matheusmelo.kybr.service;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.BffKeySlot;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.ClientIp.Source;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Resolves the client IP, trusting a header only from a trusted hop. Pure: header values come in as
 * lists (every instance), so the servlet layer stays in the filter.
 *
 * <pre>
 * step 1, socket IP: platform header configured? exactly one valid literal -> it (platformOk),
 *         otherwise remoteAddr with platformOk=false and reason missing|invalid|multiple;
 *         not configured -> remoteAddr (platformOk)
 * step 2: valid BFF key -> X-Kybr-Client-IP (exactly one valid literal) FORWARDED,
 *                          otherwise the socket IP as FORWARDED_ABSENT
 *         no key, platformOk -> socket IP as DIRECT (X-Kybr-Client-IP ignored)
 *         no key, not platformOk -> remoteAddr as PLATFORM_ABSENT
 * </pre>
 *
 * {@code X-Forwarded-For} is never consulted.
 */
@Service
public class ClientIpResolver {

    /**
     * @param clientIp the resolved IP
     * @param platformOk whether the platform header (when configured) was one valid literal
     * @param platformReason {@code missing}, {@code invalid} or {@code multiple} when not platformOk
     * @param clientHeaderPresent whether any {@code X-Kybr-Client-IP} instance was sent
     */
    public record Resolution(ClientIp clientIp, boolean platformOk, String platformReason,
            boolean clientHeaderPresent) {
    }

    private final String platformHeader;

    @Autowired
    public ClientIpResolver(KybrProperties properties) {
        this(properties.clientIp().platformHeader());
    }

    /** @param platformHeader the edge's socket-IP header ({@code Fly-Client-IP}), or null */
    public ClientIpResolver(String platformHeader) {
        this.platformHeader = platformHeader;
    }

    /** The configured platform header name, or null when the socket address is used. */
    public String platformHeader() {
        return platformHeader;
    }

    public Resolution resolve(BffKeySlot slot, List<String> clientIpHeader, List<String> platformHeaderValues,
            String remoteAddr) {
        String socketIp = remoteAddr;
        boolean platformOk = true;
        String platformReason = null;
        if (platformHeader != null) {
            Optional<String> platformIp = single(platformHeaderValues).flatMap(ClientIpResolver::parseLiteral);
            if (platformIp.isPresent()) {
                socketIp = platformIp.get();
            } else {
                platformOk = false;
                platformReason = platformHeaderValues.isEmpty() ? "missing"
                        : isMultiple(platformHeaderValues) ? "multiple" : "invalid";
            }
        }

        ClientIp clientIp;
        if (slot != BffKeySlot.NONE) {
            clientIp = single(clientIpHeader).flatMap(ClientIpResolver::parseLiteral)
                    .map(ip -> new ClientIp(ip, Source.FORWARDED))
                    .orElse(new ClientIp(socketIp, Source.FORWARDED_ABSENT));
        } else if (platformOk) {
            clientIp = new ClientIp(socketIp, Source.DIRECT);
        } else {
            clientIp = new ClientIp(remoteAddr, Source.PLATFORM_ABSENT);
        }
        return new Resolution(clientIp, platformOk, platformReason, !clientIpHeader.isEmpty());
    }

    private static boolean isMultiple(List<String> values) {
        return values.size() > 1 || values.getFirst().contains(",");
    }

    private static Optional<String> single(List<String> values) {
        return values.size() == 1 ? Optional.of(values.getFirst()) : Optional.empty();
    }

    /**
     * An IP literal in canonical form, or empty. No DNS ({@link InetAddress#ofLiteral}); surrounding
     * whitespace, commas, ports, brackets and zone ids are invalid; IPv4-mapped IPv6 becomes IPv4;
     * IPv6 is rendered compressed and lowercase (RFC 5952).
     */
    static Optional<String> parseLiteral(String value) {
        if (value == null || value.isEmpty() || !value.strip().equals(value)
                || value.chars().anyMatch(c -> c == ',' || c == '%' || c == '[' || c == ']' || c == ' ')) {
            return Optional.empty();
        }
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(value);
        } catch (IllegalArgumentException notALiteral) {
            return Optional.empty();
        }
        return Optional.of(address instanceof Inet4Address ? address.getHostAddress() : compressed(address.getAddress()));
    }

    private static String compressed(byte[] bytes) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[2 * i] & 0xff) << 8) | (bytes[2 * i + 1] & 0xff);
        }
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < 8; i++) {
            int length = 0;
            while (i + length < 8 && groups[i + length] == 0) {
                length++;
            }
            if (length > bestLength) {
                bestStart = i;
                bestLength = length;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                out.append("::");
                i += bestLength - 1;
                continue;
            }
            if (!out.isEmpty() && out.charAt(out.length() - 1) != ':') {
                out.append(':');
            }
            out.append(Integer.toHexString(groups[i]));
        }
        return out.toString();
    }
}
