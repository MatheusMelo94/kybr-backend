package com.matheusmelo.kybr.model.link;

import java.time.Instant;
import org.springframework.data.mongodb.core.mapping.Field;

/** Manual review bookkeeping for a pending case. @param slaAte when a reviewer should have decided */
public record Revisao(@Field("sla_ate") Instant slaAte) {
}
