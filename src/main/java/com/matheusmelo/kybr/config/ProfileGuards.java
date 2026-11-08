package com.matheusmelo.kybr.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Startup checks that depend on the active profile, which {@link KybrProperties} can't see. Under
 * {@code prod}, provisioning never takes a chosen slug: explicit slugs exist only so non-production
 * environments can mirror fixture slugs. The recovery poller is never off in prod: without it, a
 * case whose dispatch was dropped or whose worker died would wait forever. The company data
 * provider is always live in prod: the stub answers from the public fixtures, so anyone who read
 * them could get an approval. Likewise the scanner, storage and e-mail are always the real ones (a
 * stub scanner passes anything without a test signature, memory storage loses documents, the
 * recording sender drops mail), photos are always re-encoded by the isolated sanitizer service, the
 * scheduled e-mail and retention jobs always run, and the document upload accepts no origin pattern.
 */
@Component
@Profile("prod")
public class ProfileGuards {

    public ProfileGuards(KybrProperties properties) {
        if (properties.admin().allowExplicitSlug()) {
            throw new IllegalStateException("kybr.admin.allow-explicit-slug must be false under the prod profile");
        }
        if (!properties.verification().pollerEnabled()) {
            throw new IllegalStateException("kybr.verification.poller-enabled must be true under the prod profile");
        }
        if (properties.cnpja().mode() != KybrProperties.Cnpja.Mode.LIVE) {
            throw new IllegalStateException("kybr.cnpja.mode must be live under the prod profile");
        }
        if (properties.scanner().mode() != KybrProperties.Scanner.Mode.CLAMD) {
            throw new IllegalStateException("kybr.scanner.mode must be clamd under the prod profile");
        }
        if (properties.storage().mode() != KybrProperties.Storage.Mode.S3) {
            throw new IllegalStateException("kybr.storage.mode must be s3 under the prod profile");
        }
        if (properties.storage().endpoint() != null) {
            throw new IllegalStateException("kybr.storage.endpoint must not be set under the prod profile");
        }
        if (properties.email().mode() != KybrProperties.Email.Mode.RESEND) {
            throw new IllegalStateException("kybr.email.mode must be resend under the prod profile");
        }
        if (properties.image().mode() != KybrProperties.Image.Mode.REMOTE) {
            throw new IllegalStateException("kybr.image.mode must be remote under the prod profile");
        }
        if (properties.sanitizer().host() == null) {
            throw new IllegalStateException("kybr.sanitizer.host is required under the prod profile");
        }
        if (properties.sanitizer().port() == null) {
            throw new IllegalStateException("kybr.sanitizer.port is required under the prod profile");
        }
        if (!properties.email().dispatcher().enabled()) {
            throw new IllegalStateException("kybr.email.dispatcher.enabled must be true under the prod profile");
        }
        if (!properties.scan().enabled()) {
            throw new IllegalStateException("kybr.scan.enabled must be true under the prod profile");
        }
        if (!properties.retention().enabled()) {
            throw new IllegalStateException("kybr.retention.enabled must be true under the prod profile");
        }
        if (!properties.cors().d6().originPatterns().isEmpty()) {
            throw new IllegalStateException("kybr.cors.d6.origin-patterns must be empty under the prod profile");
        }
    }
}
