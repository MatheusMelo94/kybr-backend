package com.matheusmelo.kybr.service.jobs;

import com.matheusmelo.kybr.config.KybrProperties;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link BackgroundJob} bean at its interval, each on its own virtual thread, with a
 * fixed delay between runs (a slow run never overlaps the next). A job whose switch is off
 * ({@code kybr.email.dispatcher.enabled}, {@code kybr.retention.enabled}: tests only; prod refuses
 * to start that way) is never scheduled. A failed run is logged by type and the job runs again at
 * its next interval. Stops at shutdown.
 */
@Component
public class JobScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    /** Whether and how often a job runs. */
    public record Schedule(boolean enabled, Duration interval) {
    }

    private final List<BackgroundJob> jobs;
    private final Map<JobName, Schedule> schedules;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    @Autowired
    public JobScheduler(ObjectProvider<BackgroundJob> jobs, KybrProperties properties) {
        this(jobs.orderedStream().toList(), schedules(properties.email().dispatcher().enabled(), properties.email().dispatcher().interval(),
                properties.retention().enabled(), properties.retention().interval(),
                properties.retention().backstopInterval(), properties.retention().orphanInterval(),
                properties.scan().enabled(), properties.scan().pollInterval()));
    }

    JobScheduler(List<BackgroundJob> jobs, Map<JobName, Schedule> schedules) {
        this.jobs = List.copyOf(jobs);
        this.schedules = Map.copyOf(schedules);
    }

    static Map<JobName, Schedule> schedules(boolean dispatcherEnabled, Duration dispatcherInterval,
            boolean retentionEnabled, Duration retentionInterval, Duration backstopInterval, Duration orphanInterval,
            boolean scanEnabled, Duration scanInterval) {
        Map<JobName, Schedule> schedules = new EnumMap<>(JobName.class);
        schedules.put(JobName.DISPATCHER, new Schedule(dispatcherEnabled, dispatcherInterval));
        schedules.put(JobName.RETENTION, new Schedule(retentionEnabled, retentionInterval));
        schedules.put(JobName.BACKSTOP, new Schedule(retentionEnabled, backstopInterval));
        schedules.put(JobName.ORPHANS, new Schedule(retentionEnabled, orphanInterval));
        schedules.put(JobName.SCAN, new Schedule(scanEnabled, scanInterval));
        return schedules;
    }

    private List<JobName> scheduled = List.of();

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        List<BackgroundJob> enabled = jobs.stream().filter(job -> {
            Schedule schedule = schedules.get(job.name());
            return schedule != null && schedule.enabled();
        }).toList();
        scheduler = Executors.newScheduledThreadPool(Math.max(1, enabled.size()),
                Thread.ofVirtual().name("background-job-", 0).factory());
        for (BackgroundJob job : enabled) {
            long millis = schedules.get(job.name()).interval().toMillis();
            scheduler.scheduleWithFixedDelay(() -> run(job), millis, millis, TimeUnit.MILLISECONDS);
        }
        scheduled = enabled.stream().map(BackgroundJob::name).toList();
        running = true;
    }

    /** The jobs the running scheduler has scheduled, in start order; empty when stopped. */
    synchronized List<JobName> scheduled() {
        return running ? scheduled : List.of();
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run(BackgroundJob job) {
        if (!running) {
            return;
        }
        try {
            job.runOnce();
        } catch (RuntimeException e) {
            log.error("event=background_job_error job={} error={}", job.name(), e.getClass().getSimpleName());
        }
    }
}
