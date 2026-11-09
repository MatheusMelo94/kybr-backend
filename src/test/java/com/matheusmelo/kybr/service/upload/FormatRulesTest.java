package com.matheusmelo.kybr.service.upload;

import static com.matheusmelo.kybr.service.upload.MagicBytes.Kind.JPEG;
import static com.matheusmelo.kybr.service.upload.MagicBytes.Kind.PDF;
import static com.matheusmelo.kybr.service.upload.MagicBytes.Kind.PNG;
import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.service.upload.MagicBytes.Kind;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FormatRulesTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                // Nothing stored yet for the type.
                Arguments.of("one PDF", null, List.of(PDF), FormatoEnvio.pdf),
                Arguments.of("one photo", null, List.of(JPEG), FormatoEnvio.fotos),
                Arguments.of("several photos", null, List.of(JPEG, JPEG, JPEG), FormatoEnvio.fotos),
                Arguments.of("JPEG and PNG photos", null, List.of(JPEG, PNG), FormatoEnvio.fotos),
                Arguments.of("no files", null, List.of(), null),
                Arguments.of("two PDFs", null, List.of(PDF, PDF), null),
                Arguments.of("a PDF and a photo", null, List.of(PDF, JPEG), null),
                Arguments.of("a photo and a PDF", null, List.of(PNG, PDF), null),
                // The type already holds photos.
                Arguments.of("photos added to photos", FormatoEnvio.fotos, List.of(JPEG), FormatoEnvio.fotos),
                Arguments.of("a PDF added to photos", FormatoEnvio.fotos, List.of(PDF), null),
                Arguments.of("a PDF and photos added to photos", FormatoEnvio.fotos, List.of(JPEG, PDF), null),
                // The type already holds a PDF.
                Arguments.of("a PDF added to a PDF", FormatoEnvio.pdf, List.of(PDF), null),
                Arguments.of("photos added to a PDF", FormatoEnvio.pdf, List.of(JPEG), null),
                Arguments.of("nothing added to a PDF", FormatoEnvio.pdf, List.of(), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void decidesTheFormatOrRefuses(String name, FormatoEnvio stored, List<Kind> incoming, FormatoEnvio expected) {
        assertThat(FormatRules.formatFor(stored, incoming)).isEqualTo(Optional.ofNullable(expected));
    }
}
