package com.matheusmelo.kybr.model;

/** Which configured BFF key a request presented. */
public enum BffKeySlot {
    CURRENT,
    PREVIOUS,
    NONE;

    /** The request attribute holding the slot, set by the BFF key filter. */
    public static final String ATTRIBUTE = BffKeySlot.class.getName();
}
