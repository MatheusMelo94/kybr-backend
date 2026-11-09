package com.matheusmelo.kybr.service.upload;

import com.matheusmelo.kybr.model.document.FormatoEnvio;
import java.util.List;
import java.util.Optional;

/**
 * How one document type may be sent: exactly one PDF, or one or more photos (pages), never both.
 * A PDF can't be added to a type that already holds anything, and photos can't be added to a type
 * holding a PDF. Pure rules, checked again atomically when the upload is reserved.
 */
public final class FormatRules {

    private FormatRules() {
    }

    /**
     * @param stored how the type was sent so far; null when it holds nothing
     * @param incoming the types of the files in this request, in order
     * @return the type's format after this request, or empty when the combination is refused
     */
    public static Optional<FormatoEnvio> formatFor(FormatoEnvio stored, List<MagicBytes.Kind> incoming) {
        if (incoming.isEmpty()) {
            return Optional.empty();
        }
        long pdfs = incoming.stream().filter(kind -> !kind.isPhoto()).count();
        if (pdfs == 0) {
            return stored == FormatoEnvio.pdf ? Optional.empty() : Optional.of(FormatoEnvio.fotos);
        }
        if (pdfs == 1 && incoming.size() == 1 && stored == null) {
            return Optional.of(FormatoEnvio.pdf);
        }
        return Optional.empty();
    }
}
