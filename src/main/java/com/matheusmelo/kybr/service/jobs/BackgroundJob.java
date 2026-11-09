package com.matheusmelo.kybr.service.jobs;

/**
 * One background job. {@link JobScheduler} runs it at its configured interval on every machine, so
 * a run claims its own work atomically; tests, where scheduling is off, run it on demand.
 */
public interface BackgroundJob {

    JobName name();

    /** One pass over whatever is due; returns when done. Exceptions are logged by the caller. */
    void runOnce();
}
