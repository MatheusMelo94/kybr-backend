package com.matheusmelo.kybr.service.company;

import java.time.Duration;
import java.util.Objects;

/**
 * What a {@link CompanyDataProvider} call produced. Every outcome is a value: a provider failure is
 * never an exception, and none carries the provider's raw response.
 *
 * @param <T> the company data a {@link Found} carries
 */
public sealed interface CompanyDataResult<T> {

    /** The provider-call metric, tagged {@code tipo} and {@link #resultado()}. */
    String CALLS_METRIC = "kybr.provider.calls";

    /** The metric's {@code resultado} when a budget or the spend brake stopped the call before it was made. */
    String BUDGET_DENIED = "budget_denied";

    /** This outcome as the metric's {@code resultado} tag; every failure is {@code error}. */
    default String resultado() {
        return switch (this) {
            case Found<T> found -> "found";
            case NotFound<T> notFound -> "not_found";
            case Unsupported<T> unsupported -> "unsupported";
            case RateLimited<T> rateLimited -> "rate_limited";
            case Unavailable<T> unavailable -> "error";
        };
    }

    record Found<T>(T company) implements CompanyDataResult<T> {
        public Found {
            Objects.requireNonNull(company, "company");
        }
    }

    /** The provider has no record of the CNPJ. */
    record NotFound<T>() implements CompanyDataResult<T> {
    }

    /** The provider can't look this CNPJ up (an alphanumeric CNPJ, for one); retrying won't help. */
    record Unsupported<T>() implements CompanyDataResult<T> {
    }

    /** The provider asked us to slow down; try again after {@code retryAfter}. */
    record RateLimited<T>(Duration retryAfter) implements CompanyDataResult<T> {
        public RateLimited {
            Objects.requireNonNull(retryAfter, "retryAfter");
        }
    }

    /** A timeout, a connection failure or a provider error. */
    record Unavailable<T>() implements CompanyDataResult<T> {
    }
}
