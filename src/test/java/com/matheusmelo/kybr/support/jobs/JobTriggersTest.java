package com.matheusmelo.kybr.support.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.matheusmelo.kybr.service.jobs.BackgroundJob;
import com.matheusmelo.kybr.service.jobs.JobName;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobTriggersTest {

    @Test
    void runsTheNamedJobOnceOnTheCallersThread() {
        List<String> runs = new ArrayList<>();
        JobTriggers triggers = new JobTriggers(List.of(job(JobName.DISPATCHER, runs), job(JobName.ORPHANS, runs)));

        triggers.run(JobName.ORPHANS);
        triggers.run(JobName.DISPATCHER);

        assertThat(runs).containsExactly("ORPHANS@" + Thread.currentThread().getName(),
                "DISPATCHER@" + Thread.currentThread().getName());
    }

    @Test
    void aJobThatDoesNotExistYetIsAClearFailure() {
        JobTriggers triggers = new JobTriggers(List.of());

        assertThatIllegalStateException().isThrownBy(() -> triggers.run(JobName.RETENTION))
                .withMessageContaining("RETENTION");
    }

    private static BackgroundJob job(JobName name, List<String> runs) {
        return new BackgroundJob() {
            @Override
            public JobName name() {
                return name;
            }

            @Override
            public void runOnce() {
                runs.add(name + "@" + Thread.currentThread().getName());
            }
        };
    }
}
