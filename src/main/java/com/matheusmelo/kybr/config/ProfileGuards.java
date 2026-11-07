package com.matheusmelo.kybr.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Startup checks that depend on the active profile, which {@link KybrProperties} can't see. Under
 * {@code prod}, provisioning never takes a chosen slug: explicit slugs exist only so non-production
 * environments can mirror fixture slugs. The recovery poller is never off in prod: without it, a
 * case whose dispatch was dropped or whose worker died would wait forever. The company data
 * provider is always live in prod: the stub answers from the public fixtures, so anyone who read
 * them could get an approval.
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
    }
}
