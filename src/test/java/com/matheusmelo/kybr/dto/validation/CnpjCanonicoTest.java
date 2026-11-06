package com.matheusmelo.kybr.dto.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.exception.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The constraint with a plain {@link Validator}; the HTTP rendering (422 body, 400 query/path) is
 * asserted in {@code InputContractAcceptanceIT}.
 */
class CnpjCanonicoTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    record Body(@CnpjCanonico String cnpj) {
    }

    record RequiredBody(@NotNull @CnpjCanonico String cnpj) {
    }

    static class Endpoint {
        @SuppressWarnings("unused")
        void lookup(@CnpjCanonico String cnpj) {
        }
    }

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678000195", "12ABC34501DE35"})
    void acceptsACanonicalCnpj(String cnpj) {
        assertThat(validator.validate(new Body(cnpj))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12abc34501de35", "12.345.678/0001-95", "12ABC34501DE36", "00000000000000", ""})
    void rejectsANonCanonicalOrInvalidCnpjAsCnpjInvalido(String cnpj) {
        Set<ConstraintViolation<Body>> violations = validator.validate(new Body(cnpj));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("cnpj");
            assertThat(violation.getMessage()).isEqualTo(ErrorCode.CNPJ_INVALIDO.name());
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(CnpjCanonico.class);
        });
    }

    @Test
    void exposesTheErrorCodeItRendersAs() {
        assertThat(CnpjCanonico.CODE).isEqualTo(ErrorCode.CNPJ_INVALIDO);
        assertThat(CnpjCanonico.CODE_NAME).isEqualTo(CnpjCanonico.CODE.name());
    }

    @Test
    void leavesNullToNotNull() {
        assertThat(validator.validate(new Body(null))).isEmpty();
        assertThat(validator.validate(new RequiredBody(null))).singleElement()
                .satisfies(v -> assertThat(v.getConstraintDescriptor().getAnnotation()).isInstanceOf(NotNull.class));
    }

    @Test
    void worksOnAMethodParameter() throws NoSuchMethodException {
        Method lookup = Endpoint.class.getDeclaredMethod("lookup", String.class);

        Set<ConstraintViolation<Endpoint>> violations = validator.forExecutables()
                .validateParameters(new Endpoint(), lookup, new Object[] {"12abc34501de35"});

        assertThat(violations).singleElement()
                .satisfies(v -> assertThat(v.getMessage()).isEqualTo(ErrorCode.CNPJ_INVALIDO.name()));
    }
}
