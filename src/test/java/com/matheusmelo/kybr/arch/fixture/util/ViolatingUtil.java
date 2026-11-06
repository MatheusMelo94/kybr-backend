package com.matheusmelo.kybr.arch.fixture.util;

import org.springframework.util.StringUtils;

/** Rule fixture, never run: a utility that imports Spring. */
public final class ViolatingUtil {

    static boolean blank(String value) {
        return !StringUtils.hasText(value);
    }
}
