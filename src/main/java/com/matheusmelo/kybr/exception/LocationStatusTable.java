package com.matheusmelo.kybr.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The status of a code that depends on where the rejected value came from.
 *
 * <p>A code listed here must have an entry for every location it is raised at: an unlisted
 * location throws rather than silently falling back. Codes not listed keep
 * {@link ErrorCode#status()}. A new entry is a contract change.
 */
public final class LocationStatusTable {

    public record Key(ErrorCode code, ParameterLocation location) {
    }

    private static final Map<Key, HttpStatus> ENTRIES = Map.of(
            new Key(ErrorCode.CNPJ_INVALIDO, ParameterLocation.QUERY), HttpStatus.BAD_REQUEST,
            new Key(ErrorCode.CNPJ_INVALIDO, ParameterLocation.PATH), HttpStatus.BAD_REQUEST,
            new Key(ErrorCode.CNPJ_INVALIDO, ParameterLocation.BODY), HttpStatus.UNPROCESSABLE_CONTENT);

    private LocationStatusTable() {
    }

    public static Map<Key, HttpStatus> entries() {
        return ENTRIES;
    }

    public static HttpStatus resolve(ErrorCode code, ParameterLocation location) {
        HttpStatus status = ENTRIES.get(new Key(code, location));
        if (status != null) {
            return status;
        }
        if (ENTRIES.keySet().stream().anyMatch(key -> key.code() == code)) {
            throw new IllegalStateException(code + " has no status for location " + location);
        }
        return code.status();
    }
}
