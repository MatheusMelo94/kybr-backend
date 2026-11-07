package com.matheusmelo.kybr.controller;

/**
 * The seller link's contract text, shared by the {@link LinkController} annotations. Each error
 * response names its codes in backticks; the contract generator turns every named code into an
 * example of the error envelope.
 */
final class LinkApiDocs {

    static final String BFF_KEY = "#/components/parameters/X-Kybr-BFF-Key";
    static final String CLIENT_IP = "#/components/parameters/X-Kybr-Client-IP";

    static final String SLUG = "The link's slug: 1 to 64 characters of `[a-z0-9-]`, starting and ending with a "
            + "letter or digit. Anything else is 404 `LINK_NAO_ENCONTRADO`.";
    static final String TOKEN = "The case token returned when the verification started. Anything that doesn't "
            + "match `^[A-Za-z0-9_-]{22,64}$` is 404 `CASO_NAO_ENCONTRADO`.";
    static final String SLUG_PATTERN = "^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$";
    static final String TOKEN_PATTERN = "^[A-Za-z0-9_-]{22,64}$";

    static final String BAD_REQUEST = "- `REQUISICAO_INVALIDA`: a path that isn't normalised.";
    static final String BAD_BODY = "- `REQUISICAO_INVALIDA`: malformed JSON (including duplicate keys or trailing "
            + "content), a missing body, or a path that isn't normalised.";
    static final String FORBIDDEN = "- `BFF_KEY_AUSENTE`: `X-Kybr-BFF-Key` is missing or wrong (the same body "
            + "either way).";
    static final String LINK_NOT_FOUND = "- `LINK_NAO_ENCONTRADO`: the slug is unknown or malformed, the link isn't "
            + "set up yet, or it is disabled. Decided from the slug alone, before anything else in the request is "
            + "read.";
    static final String LINK_NOT_FOUND_READ = "- `LINK_NAO_ENCONTRADO`: the slug is unknown or malformed, or the "
            + "link isn't set up yet. Decided from the slug alone, before the token is read; a disabled link still "
            + "serves its existing cases.";
    static final String CASE_NOT_FOUND = "- `CASO_NAO_ENCONTRADO`: the token is unknown or belongs to another link "
            + "(the same response either way).";
    static final String NOT_ACCEPTABLE = "- `FORMATO_NAO_ACEITO`: `Accept` excludes `application/json` (the body is "
            + "still the JSON envelope).";
    static final String TOO_LARGE = "- `REQUISICAO_GRANDE`: the body is over 1 MiB.";
    static final String UNSUPPORTED = "- `MIDIA_NAO_SUPORTADA`: the body isn't `application/json`.";
    static final String INTERNAL = "- `ERRO_INTERNO`: an unexpected error. No detail is given.";
    static final String UNAVAILABLE = "- `INDISPONIVEL`: the database is unavailable. Retry later.";

    private LinkApiDocs() {
    }
}
