package com.matheusmelo.kybr.model.workspace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A tenant that owns one seller link, addressed by its unique {@code slug}.
 *
 * @param linkAtivo the link's kill switch: off, the link answers "not found" to new activity
 * @param autofillAtivo the company-lookup kill switch for this workspace
 * @param contatoVendedor how sellers reach the workspace; a workspace without one isn't onboarded
 */
@Document(Workspace.COLLECTION)
public record Workspace(
        @Id UUID id,
        @Field("nome") String nome,
        @Field("slug") String slug,
        @Field("categoria") String categoria,
        @Field("contato_vendedor") ContatoVendedor contatoVendedor,
        @Field("plano") Plano plano,
        @Field("link_ativo") boolean linkAtivo,
        @Field("autofill_ativo") boolean autofillAtivo,
        @Field("politica") Politica politica,
        @Field("documentos_habilitados") List<DocumentoTipo> documentosHabilitados,
        @Field("criado_em") Instant criadoEm) {

    public static final String COLLECTION = "workspaces";
    public static final String SLUG = "slug";

    /** A well-formed slug: lower-case letters, digits and inner hyphens, 1 to 64 characters. */
    public static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$");

    public Workspace {
        documentosHabilitados = documentosHabilitados == null ? List.of() : List.copyOf(documentosHabilitados);
    }
}
