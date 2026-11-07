package com.matheusmelo.kybr.service.company;

/** The operator's "provider spend" brake. Off: no provider call is paid for anywhere. */
@FunctionalInterface
public interface ProviderSpendSwitch {

    /** True unless an operator switched provider spend off. */
    boolean providerSpendOn();
}
