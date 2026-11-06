package com.matheusmelo.kybr.service.idempotency;

import org.springframework.stereotype.Component;

/** {@code PANEL} account signup: the constant binding {@code signup}. */
@Component
public class SignupBindingResolver extends ConstantBindingResolver {

    public SignupBindingResolver() {
        super("signup");
    }
}
