package com.matheusmelo.kybr.model.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A verification stores the minimum company data: nothing that could hold a partner's name. */
class SnapshotEmpresaTest {

    @Test
    void theSnapshotHoldsOnlyTheMinimalCompanyFields() {
        assertThat(Arrays.stream(SnapshotEmpresa.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("razaoSocial", "municipio", "uf", "cnaeCodigo", "cnaeDescricao", "situacao",
                        "dataAbertura", "regime");
    }

    @Test
    void noSnapshotFieldIsACollectionOrANestedRecord() {
        for (RecordComponent component : SnapshotEmpresa.class.getRecordComponents()) {
            Class<?> type = component.getType();
            assertThat(Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type) || type.isArray()
                    || type.isRecord()).as(component.getName()).isFalse();
        }
    }

    @Test
    void theVerificationKeepsCompanyDataOnlyInTheSnapshot() {
        assertThat(Arrays.stream(Verification.class.getRecordComponents()).map(RecordComponent::getName))
                .noneMatch(name -> name.toLowerCase().contains("qsa") || name.toLowerCase().contains("socio"));
    }
}
