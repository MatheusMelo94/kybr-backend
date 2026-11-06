package com.matheusmelo.kybr.support.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/**
 * Captures one logger's events with their MDC, frozen at log time (a plain ListAppender would read
 * the MDC later, from the wrong thread). Close it to detach.
 */
public final class CapturedEvents extends AppenderBase<ILoggingEvent> implements AutoCloseable {

    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private final Logger logger;

    private CapturedEvents(Class<?> loggerClass) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerClass);
    }

    public static CapturedEvents of(Class<?> loggerClass) {
        CapturedEvents captured = new CapturedEvents(loggerClass);
        captured.setContext(captured.logger.getLoggerContext());
        captured.start();
        captured.logger.addAppender(captured);
        return captured;
    }

    @Override
    protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        events.add(event);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(events);
    }

    @Override
    public void close() {
        logger.detachAppender(this);
        stop();
    }
}
