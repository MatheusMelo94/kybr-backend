package com.matheusmelo.kybr.service.jobs;

/** The background jobs, each scheduled from its own settings. */
public enum JobName {
    /** Sends due outbox e-mails ({@code kybr.email.dispatcher}). */
    DISPATCHER,
    /** Deletes the documents and contact of cases past their retention date ({@code kybr.retention.interval}). */
    RETENTION,
    /** Recomputes the retention date of cases never decided ({@code kybr.retention.backstop-interval}). */
    BACKSTOP,
    /** Deletes stored objects that have no record ({@code kybr.retention.orphan-interval}). */
    ORPHANS,
    /** Scans received files waiting in quarantine ({@code kybr.scan.poll-interval}). */
    SCAN
}
