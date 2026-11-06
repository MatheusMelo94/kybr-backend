package com.matheusmelo.kybr.service.idempotency;

import java.util.Map;
import java.util.Objects;

/** A fixed binding, for routes with no natural scope (account signup uses {@code "signup"}). */
public class ConstantBindingResolver implements IdempotencyBindingResolver {

    private final String binding;

    public ConstantBindingResolver(String binding) {
        if (Objects.requireNonNull(binding, "binding").isBlank()) {
            throw new IllegalArgumentException("binding must not be blank");
        }
        this.binding = binding;
    }

    @Override
    public String resolve(Map<String, String> uriVariables) {
        return binding;
    }
}
