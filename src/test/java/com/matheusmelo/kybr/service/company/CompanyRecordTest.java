package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The full provider record is transient: partner names never reach a document or a log line. */
class CompanyRecordTest {

    @Test
    void itCarriesNoPersistenceAnnotations() {
        assertThat(persistenceAnnotations(CompanyRecord.class.getAnnotations())).isEmpty();
        for (RecordComponent component : CompanyRecord.class.getRecordComponents()) {
            assertThat(persistenceAnnotations(component.getAnnotations())).as(component.getName()).isEmpty();
        }
    }

    @Test
    void itsTextFormNeverShowsPartnerNames() {
        CompanyRecord record = new CompanyRecord("Loja Exemplo Ltda", null, "SP", null, null, null, null, null, null,
                List.of("Maria Exemplo", "Joao Exemplo"));

        assertThat(record.toString()).doesNotContain("Maria Exemplo").doesNotContain("Joao Exemplo");
    }

    private static List<String> persistenceAnnotations(Annotation[] annotations) {
        return Arrays.stream(annotations).map(a -> a.annotationType().getName())
                .filter(name -> name.startsWith("org.springframework.data")).toList();
    }
}
