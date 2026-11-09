package com.matheusmelo.kybr.service.storage;

import java.time.Instant;

/** An object in document storage, as a listing shows it. */
public record StoredObject(String key, long size, Instant lastModified) {
}
