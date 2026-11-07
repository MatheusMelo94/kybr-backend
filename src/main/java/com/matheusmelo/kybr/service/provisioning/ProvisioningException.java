package com.matheusmelo.kybr.service.provisioning;

public class ProvisioningException extends RuntimeException {

    public enum Reason { INVALID, NOT_FOUND, CONFLICT }

    private final Reason reason;
    private final String field;

    public ProvisioningException(Reason reason, String field) {
        super(reason + " " + field);
        this.reason = reason;
        this.field = field;
    }

    public Reason reason() {
        return reason;
    }

    public String field() {
        return field;
    }
}
