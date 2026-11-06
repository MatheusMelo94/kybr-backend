package com.matheusmelo.kybr.exception;

import com.mongodb.MongoException;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Shared by {@link GlobalExceptionHandler} (inside MVC) and the {@code /error} controller (outside
 * it), so an exception gets the same code and the same log line on either path.
 */
public final class UnhandledExceptions {

    private UnhandledExceptions() {
    }

    /** An {@link ApiException} somewhere in the cause chain (a library wrapped it). */
    public static Optional<ApiException> wrappedApiException(Throwable throwable) {
        for (Throwable t = throwable.getCause(); t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ApiException api) {
                return Optional.of(api);
            }
        }
        return Optional.empty();
    }

    /**
     * {@code exception=… mongo_exception=… mongo_code=…} when a MongoDB driver exception is in the
     * cause chain; never the driver's message, which can echo key values (PII) into logs.
     */
    public static Optional<String> mongoDescription(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof MongoException mongo) {
                return Optional.of("exception=" + throwable.getClass().getSimpleName()
                        + " mongo_exception=" + mongo.getClass().getSimpleName()
                        + " mongo_code=" + mongo.getCode());
            }
        }
        return Optional.empty();
    }

    /** Logs an exception that becomes {@code ERRO_INTERNO}: a Mongo failure without its message. */
    public static void log(Logger log, Throwable exception) {
        Optional<String> mongo = mongoDescription(exception);
        if (mongo.isPresent()) {
            log.error("event=unhandled_exception {}", mongo.get());
        } else {
            log.error("event=unhandled_exception exception={}", exception.getClass().getName(), exception);
        }
    }
}
