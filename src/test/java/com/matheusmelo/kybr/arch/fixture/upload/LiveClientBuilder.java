package com.matheusmelo.kybr.arch.fixture.upload;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/** Violates the construction rule: a non-configuration class building live clients itself. */
public class LiveClientBuilder {

    FixtureLiveClient client() {
        return new FixtureLiveClient();
    }

    S3ClientBuilder s3() {
        return S3Client.builder();
    }
}
