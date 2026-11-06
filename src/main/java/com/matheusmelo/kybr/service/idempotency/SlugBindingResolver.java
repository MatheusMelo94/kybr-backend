package com.matheusmelo.kybr.service.idempotency;

import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code PUBLIC_VERIFY} (public {@code /public/v/{slug}} routes): the {@code slug} path variable. */
@Component
public class SlugBindingResolver implements IdempotencyBindingResolver {

    @Override
    public String resolve(Map<String, String> uriVariables) {
        String slug = uriVariables.get("slug");
        if (slug == null || slug.isBlank()) {
            throw new IllegalStateException("@Idempotent route bound to the slug has no {slug} variable");
        }
        return slug;
    }
}
