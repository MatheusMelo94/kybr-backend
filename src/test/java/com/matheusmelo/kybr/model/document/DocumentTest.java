package com.matheusmelo.kybr.model.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.Field;

/** A stored document can never hold what the seller called the file. */
class DocumentTest {

    @Test
    void theOnlyTextFieldsAreTheGeneratedKeysTheSniffedTypeTheHashesAndTheLeaseOwner() {
        Set<String> text = Arrays.stream(Document.class.getRecordComponents())
                .filter(component -> component.getType() == String.class)
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(text).containsExactlyInAnyOrder("storageKey", "quarentenaKey", "contentType", "sha256Original",
                "sha256", "leaseOwner");
    }

    @Test
    void noFieldIsNamedLikeAFileName() {
        for (RecordComponent component : Document.class.getRecordComponents()) {
            String stored = component.getAccessor().getAnnotation(Field.class) != null
                    ? component.getAccessor().getAnnotation(Field.class).value()
                    : component.getName();
            for (String name : new String[] {component.getName(), stored}) {
                String lower = name.toLowerCase(Locale.ROOT);
                assertThat(lower).doesNotContain("name").doesNotContain("nome").doesNotContain("original_name")
                        .doesNotContain("titulo").doesNotContain("extens");
            }
        }
    }

    @Test
    void itKeepsBothTheOriginalAndTheStoredBytesHashAndSize() {
        Set<String> names = Arrays.stream(Document.class.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(names).contains("sha256Original", "sha256", "bytesOriginal", "bytesArmazenados");
    }
}
