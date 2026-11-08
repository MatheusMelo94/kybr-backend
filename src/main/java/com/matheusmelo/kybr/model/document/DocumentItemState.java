package com.matheusmelo.kybr.model.document;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What the seller's D4 item shows for one document type, computed from the type's files:
 * {@code verificando} while any file is still being checked; otherwise {@code recusado} when the
 * type's latest upload had a refused file (with that file's reason); otherwise {@code aceito}.
 * Files that still count (accepted or being checked) are {@code enviados}; refused files are
 * {@code recusados}. A record written before the scan job existed counts as accepted. Pure.
 *
 * @param formato the format of the files that still count; null when none does
 */
public record DocumentItemState(SituacaoEnvio situacao, int enviados, int recusados, MotivoRecusa motivo,
        FormatoEnvio formato) {

    /** The state of a type holding {@code files}; empty when nothing was ever sent. */
    public static Optional<DocumentItemState> of(List<Document> files) {
        if (files.isEmpty()) {
            return Optional.empty();
        }
        List<Document> counting = files.stream().filter(file -> file.estadoOuAceito() != EstadoDocumento.RECUSADO)
                .toList();
        List<Document> refused = files.stream().filter(file -> file.estadoOuAceito() == EstadoDocumento.RECUSADO)
                .toList();
        FormatoEnvio formato = counting.isEmpty() ? null : counting.getFirst().formato();
        if (counting.stream().anyMatch(file -> file.estadoOuAceito().pendente())) {
            return Optional.of(new DocumentItemState(SituacaoEnvio.verificando, counting.size(), refused.size(), null,
                    formato));
        }
        Optional<Document> refusedInLatest = latestUpload(files).flatMap(latest -> refused.stream()
                .filter(file -> latest.equals(file.envioId())).findFirst());
        return Optional.of(refusedInLatest
                .map(file -> new DocumentItemState(SituacaoEnvio.recusado, counting.size(), refused.size(),
                        file.motivoRecusa(), formato))
                .orElseGet(() -> new DocumentItemState(SituacaoEnvio.aceito, counting.size(), refused.size(), null,
                        formato)));
    }

    /** The upload received last (by its files' time); empty when no file names its upload. */
    private static Optional<UUID> latestUpload(List<Document> files) {
        return files.stream().filter(file -> file.envioId() != null)
                .max(Comparator.comparing(Document::criadoEm, Comparator.nullsFirst(Comparator.<Instant>naturalOrder())))
                .map(Document::envioId)
                .filter(Objects::nonNull);
    }
}
