package com.matheusmelo.kybr.service.scan;

/**
 * A scanner's answer. Only {@link Clean} lets a file through; {@link Unavailable} covers every reply
 * that isn't exactly clean or a detection, a timeout and a connection or TLS failure (fail closed).
 */
public sealed interface ScanVerdict {

    /** The {@code resultado} tag of {@code kybr.scanner.calls}. */
    String resultado();

    record Clean() implements ScanVerdict {
        @Override
        public String resultado() {
            return "clean";
        }
    }

    /** @param signature the scanner's signature name (never file content) */
    record Infected(String signature) implements ScanVerdict {
        @Override
        public String resultado() {
            return "infected";
        }
    }

    record Unavailable() implements ScanVerdict {
        @Override
        public String resultado() {
            return "unavailable";
        }
    }
}
