package com.matheusmelo.kybr.dto.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The seller link's input rules: responsible person, printable ASCII references, contact e-mail. */
class LinkValidatorsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    record Person(@Responsavel String responsavel) {
    }

    record Reference(@PrintableAscii(max = 256) String ref) {
    }

    record Contact(@ContactEmail String email) {
    }

    @BeforeAll
    static void start() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stop() {
        factory.close();
    }

    // ---- @Responsavel ----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"João da Silva", "Ana O'Neil-Souza", "José Souza", "  Maria Exemplo  ",
        "J. R. Tolkien"})
    void responsibleNamesThatPass(String name) {
        assertThat(violations(new Person(name))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Maria", "Maria2 Souza", "Maria <b> Souza", "Mariaㅤ Souza", "Mariaﾠ Souza",
        "Mariaᅟ Souza", "Mariaᅠ Souza", "Max́̂̃ Souza", "Maria ‮Souza", "",
        "   ", "' -", "Maria\tSouza"})
    void responsibleNamesThatFailWithTheirOwnCode(String name) {
        assertThat(violations(new Person(name))).containsExactly("RESPONSAVEL_INVALIDO");
    }

    @Test
    void theLengthLimitIs120CodePointsAfterNfc() {
        String decomposed119 = "José " + "a".repeat(114);
        String atLimit = "Ana " + "b".repeat(116);
        String overLimit = "Ana " + "b".repeat(117);

        assertThat(atLimit.codePointCount(0, atLimit.length())).isEqualTo(120);
        assertThat(violations(new Person(atLimit))).isEmpty();
        assertThat(violations(new Person(decomposed119 + "a"))).isEmpty();
        assertThat(violations(new Person(overLimit))).containsExactly("RESPONSAVEL_INVALIDO");
    }

    @Test
    void normalisationIsNfcAndTrimmed() {
        assertThat(ResponsavelValidator.normalize("  José Souza ")).isEqualTo("José Souza");
        assertThat(ResponsavelValidator.normalize(null)).isNull();
    }

    @Test
    void nullIsLeftToNotNull() {
        assertThat(violations(new Person(null))).isEmpty();
    }

    // ---- @PrintableAscii -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"pedido-123", "~`\"\\ {}", " "})
    void printableAsciiPasses(String ref) {
        assertThat(violations(new Reference(ref))).isEmpty();
    }

    @Test
    void printableAsciiAllows256Characters() {
        assertThat(violations(new Reference("x".repeat(256)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0007b", "café", "", "a\nb", "a\u007Fb"})
    void printableAsciiRefusesControlAndNonAsciiCharacters(String ref) {
        assertThat(violations(new Reference(ref))).containsExactly("VALOR_INVALIDO");
    }

    @Test
    void printableAsciiRefuses257Characters() {
        assertThat(violations(new Reference("x".repeat(257)))).containsExactly("VALOR_INVALIDO");
    }

    // ---- @ContactEmail ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"a@b.co", "\"<x>\"@x.test", "joao@empresa-exemplo.test", "  joao@empresa-exemplo.test  "})
    void contactEmailsThatPass(String email) {
        assertThat(violations(new Contact(email))).isEmpty();
        assertThat(ContactEmailValidator.isValid(email)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a​@x.test", "a\u0007@x.test", "a b@x.test", "a@@x.test", "a@b@x.test", "a@x",
        "@x.test", "", "   "})
    void contactEmailsThatFailWithTheirOwnCode(String email) {
        assertThat(violations(new Contact(email))).containsExactly("EMAIL_INVALIDO");
        assertThat(ContactEmailValidator.isValid(email)).isFalse();
    }

    @Test
    void aContactEmailOver254CharactersFails() {
        String email = "a".repeat(64) + "@" + "b".repeat(186) + ".test";

        assertThat(email).hasSize(256);
        assertThat(violations(new Contact(email.substring(1)))).containsExactly("EMAIL_INVALIDO");
        assertThat(violations(new Contact(email.substring(2)))).isEmpty();
    }

    @Test
    void contactEmailNormalisationTrims() {
        assertThat(ContactEmailValidator.normalize("  a@b.co ")).isEqualTo("a@b.co");
        assertThat(ContactEmailValidator.isValid(null)).isFalse();
    }

    private static Set<String> violations(Object bean) {
        return Set.copyOf(validator.validate(bean).stream().map(ConstraintViolation::getMessageTemplate).toList());
    }
}
