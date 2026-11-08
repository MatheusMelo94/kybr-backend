package com.matheusmelo.kybr.support.jobs;

import com.matheusmelo.kybr.service.jobs.BackgroundJob;
import com.matheusmelo.kybr.service.jobs.JobName;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs a background job once, on the calling thread, on demand. The test profile turns scheduling
 * off, so a test decides exactly when the dispatcher, retention, backstop or orphan job runs.
 */
@Component
public class JobTriggers {

    private final Supplier<Stream<BackgroundJob>> jobs;

    @Autowired
    public JobTriggers(ObjectProvider<BackgroundJob> jobs) {
        this.jobs = jobs::orderedStream;
    }

    JobTriggers(List<BackgroundJob> jobs) {
        List<BackgroundJob> copy = List.copyOf(jobs);
        this.jobs = copy::stream;
    }

    public void run(JobName name) {
        jobs.get().filter(job -> job.name() == name).findFirst()
                .orElseThrow(() -> new IllegalStateException("no background job " + name + " in this context"))
                .runOnce();
    }
}
