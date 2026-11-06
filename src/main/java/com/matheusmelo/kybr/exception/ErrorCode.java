package com.matheusmelo.kybr.exception;

import org.springframework.http.HttpStatus;

/**
 * The closed set of error codes on the wire: every code the backend sends to the
 * frontend plus the B0 framework codes. A new code is a contract change.
 *
 * <p>Each code has one default status and a fixed, generic English message that never carries
 * input, ids or internal details. A status that depends on where a value came from lives in
 * {@link LocationStatusTable}, never here.
 */
public enum ErrorCode {

    LINK_NAO_ENCONTRADO(HttpStatus.NOT_FOUND, "Link not found."),
    EMPRESA_NAO_ENCONTRADA(HttpStatus.NOT_FOUND, "Company not found."),
    CNPJ_INVALIDO(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid CNPJ."),
    RESPONSAVEL_INVALIDO(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid responsible person."),
    LIMITE_CONSULTAS(HttpStatus.TOO_MANY_REQUESTS, "Too many lookups."),
    LIMITE_CNPJ(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts for this CNPJ."),
    LIMITE_TENTATIVAS(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts."),
    CASO_NAO_ENCONTRADO(HttpStatus.NOT_FOUND, "Case not found."),
    EMAIL_INVALIDO(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid e-mail address."),
    ARQUIVO_GRANDE(HttpStatus.CONTENT_TOO_LARGE, "File too large."),
    FORMATO_INVALIDO(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "File format not accepted."),
    CASO_DECIDIDO(HttpStatus.CONFLICT, "Case already decided."),
    LIMITE_ARQUIVOS(HttpStatus.CONTENT_TOO_LARGE, "Too many files."),
    AUTOFILL_DESATIVADO(HttpStatus.SERVICE_UNAVAILABLE, "Autofill unavailable."),
    SESSAO_EXPIRADA(HttpStatus.UNAUTHORIZED, "Session expired."),
    TURNSTILE_REQUERIDO(HttpStatus.PRECONDITION_REQUIRED, "Challenge required."),
    TURNSTILE_INVALIDO(HttpStatus.BAD_REQUEST, "Invalid challenge."),
    TESTE_INDISPONIVEL(HttpStatus.FORBIDDEN, "Trial not available."),
    TESTE_PAUSADO(HttpStatus.SERVICE_UNAVAILABLE, "Trials paused."),
    CREDENCIAIS_INVALIDAS(HttpStatus.UNAUTHORIZED, "Invalid credentials."),
    EMAIL_NAO_CONFIRMADO(HttpStatus.FORBIDDEN, "E-mail not confirmed."),
    EMAIL_EM_USO(HttpStatus.CONFLICT, "E-mail already in use."),
    SENHA_FRACA(HttpStatus.UNPROCESSABLE_CONTENT, "Password too weak."),
    VALOR_INVALIDO(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid value."),
    CODIGO_INCORRETO(HttpStatus.BAD_REQUEST, "Incorrect code."),
    CODIGO_EXPIRADO(HttpStatus.BAD_REQUEST, "Code expired."),
    TOKEN_INVALIDO(HttpStatus.BAD_REQUEST, "Invalid token."),
    RECURSO_NAO_ENCONTRADO(HttpStatus.NOT_FOUND, "Resource not found."),
    LOTE_NAO_ENCONTRADO(HttpStatus.NOT_FOUND, "Batch not found."),
    SEM_SALDO(HttpStatus.PAYMENT_REQUIRED, "Insufficient balance."),
    MUITAS_LINHAS(HttpStatus.UNPROCESSABLE_CONTENT, "Too many rows."),
    SEM_CNPJ_VALIDO(HttpStatus.UNPROCESSABLE_CONTENT, "No valid CNPJ found."),
    LOTE_EM_ANDAMENTO(HttpStatus.CONFLICT, "Batch still processing."),
    CASO_JA_DECIDIDO(HttpStatus.CONFLICT, "Case already decided."),
    MOTIVO_OBRIGATORIO(HttpStatus.UNPROCESSABLE_CONTENT, "Reason required."),
    DOCUMENTO_NAO_HABILITADO(HttpStatus.UNPROCESSABLE_CONTENT, "Document type not enabled."),
    SENHA_ATUAL_INCORRETA(HttpStatus.FORBIDDEN, "Current password incorrect."),
    REVERIFICACAO_EM_ANDAMENTO(HttpStatus.CONFLICT, "Reverification already pending."),
    POLITICA_DESATUALIZADA(HttpStatus.CONFLICT, "Policy version is stale."),
    SEM_DOCUMENTO_HABILITADO(HttpStatus.UNPROCESSABLE_CONTENT, "No document type enabled."),

    BFF_KEY_AUSENTE(HttpStatus.FORBIDDEN, "Forbidden."),
    ERRO_INTERNO(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error."),
    INDISPONIVEL(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable."),

    REQUISICAO_INVALIDA(HttpStatus.BAD_REQUEST, "Malformed request."),
    METODO_NAO_PERMITIDO(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed."),
    FORMATO_NAO_ACEITO(HttpStatus.NOT_ACCEPTABLE, "Response format not acceptable."),
    MIDIA_NAO_SUPORTADA(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type."),
    REQUISICAO_GRANDE(HttpStatus.CONTENT_TOO_LARGE, "Request too large.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
