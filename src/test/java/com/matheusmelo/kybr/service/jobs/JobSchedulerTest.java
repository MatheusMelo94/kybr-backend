package com.matheusmelo.kybr.service.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JobSchedulerTest {

    @Test
    void runsEachEnabledJobAtItsIntervalAndKeepsGoingAfterAFailure() {
        CountingJob dispatcher = new CountingJob(JobName.DISPATCHER, true);
        JobScheduler scheduler = new JobScheduler(List.of(dispatcher),
                Map.of(JobName.DISPATCHER, new JobScheduler.Schedule(true, Duration.ofMillis(20))));

        scheduler.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> dispatcher.runs.get() >= 3);
        } finally {
            scheduler.stop();
        }
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void neverSchedulesADisabledJob() {
        CountingJob retention = new CountingJob(JobName.RETENTION, false);
        CountingJob dispatcher = new CountingJob(JobName.DISPATCHER, false);
        JobScheduler scheduler = new JobScheduler(List.of(retention, dispatcher),
                Map.of(JobName.RETENTION, new JobScheduler.Schedule(false, Duration.ofMillis(10)),
                        JobName.DISPATCHER, new JobScheduler.Schedule(true, Duration.ofHours(1))));

        scheduler.start();
        try {
            assertThat(scheduler.scheduled()).containsExactly(JobName.DISPATCHER);
        } finally {
            scheduler.stop();
        }
        assertThat(retention.runs).hasValue(0);
    }

    @Test
    void theSchedulesComeFromTheEmailRetentionAndScanSettings() {
        Map<JobName, JobScheduler.Schedule> schedules = JobScheduler.schedules(true, Duration.ofSeconds(10), false,
                Duration.ofHours(1), Duration.ofDays(1), Duration.ofDays(7), true, Duration.ofSeconds(10));

        assertThat(schedules).containsEntry(JobName.DISPATCHER, new JobScheduler.Schedule(true, Duration.ofSeconds(10)))
                .containsEntry(JobName.RETENTION, new JobScheduler.Schedule(false, Duration.ofHours(1)))
                .containsEntry(JobName.BACKSTOP, new JobScheduler.Schedule(false, Duration.ofDays(1)))
                .containsEntry(JobName.ORPHANS, new JobScheduler.Schedule(false, Duration.ofDays(7)))
                .containsEntry(JobName.SCAN, new JobScheduler.Schedule(true, Duration.ofSeconds(10)));
    }

    private static final class CountingJob implements BackgroundJob {
        private final JobName name;
        private final boolean failFirst;
        private final AtomicInteger runs = new AtomicInteger();

        CountingJob(JobName name, boolean failFirst) {
            this.name = name;
            this.failFirst = failFirst;
        }

        @Override
        public JobName name() {
            return name;
        }

        @Override
        public void runOnce() {
            if (runs.incrementAndGet() == 1 && failFirst) {
                throw new IllegalStateException("first run fails");
            }
        }
    }
}
