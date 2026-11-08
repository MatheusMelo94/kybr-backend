package com.matheusmelo.kybr.exception;

import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.PropertyNamingStrategy;

/**
 * Maps every exception that reaches MVC to the error envelope.
 *
 * <p>Errors are written straight through {@link ErrorResponseWriter}, never through content
 * negotiation, so they are JSON whatever {@code Accept} says.
 *
 * <p><b>Constraint codes.</b> A bean-validation violation raises the {@link ErrorCode} whose name
 * equals the constraint's message template (e.g. {@code @CnpjCanonico} declares
 * {@code message() default "CNPJ_INVALIDO"}); any other template raises {@code VALOR_INVALIDO}.
 * The status comes from {@link LocationStatusTable} for the value's location.
 *
 * <p><b>Logging.</b> Unexpected exceptions are logged at ERROR with the request id (MDC); MongoDB
 * driver exceptions only as class + server error code, never their message, which can carry PII.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final PropertyNamingStrategy SNAKE_CASE = PropertyNamingStrategies.SNAKE_CASE;
    private static final Pattern PATH_SEGMENT = Pattern.compile("([^.\\[\\]]+)|(\\[[^\\]]*\\])|(\\.)");

    /** Statuses of framework exceptions not mapped explicitly below. */
    private static final Map<Integer, ErrorCode> FRAMEWORK_STATUS_CODES = Map.of(
            400, ErrorCode.REQUISICAO_INVALIDA,
            404, ErrorCode.RECURSO_NAO_ENCONTRADO,
            405, ErrorCode.METODO_NAO_PERMITIDO,
            406, ErrorCode.FORMATO_NAO_ACEITO,
            413, ErrorCode.REQUISICAO_GRANDE,
            415, ErrorCode.MIDIA_NAO_SUPORTADA,
            422, ErrorCode.VALOR_INVALIDO,
            503, ErrorCode.INDISPONIVEL);

    private final ErrorResponseWriter writer;

    public GlobalExceptionHandler(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    // ---- application exceptions -------------------------------------------------------------

    @ExceptionHandler(ApiException.class)
    void apiException(ApiException ex, HttpServletResponse response) throws IOException {
        write(response, ex.status(), ex.code(), ex.field(), ex.headers());
    }

    // ---- validation ------------------------------------------------------------------------

    @ExceptionHandler(BindException.class)
    void bindException(BindException ex, HttpServletResponse response) throws IOException {
        ParameterLocation location = ex instanceof MethodArgumentNotValidException
                ? ParameterLocation.BODY : ParameterLocation.QUERY;
        Class<?> target = ex.getTarget() == null ? null : ex.getTarget().getClass();
        Optional<FieldError> first = ex.getFieldErrors().stream().min(fieldOrder(target));
        if (first.isEmpty()) {
            writeCode(response, ErrorCode.VALOR_INVALIDO, null, location);
            return;
        }
        writeCode(response, codeOf(first.get()), wireFieldName(first.get().getField()), location);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    void methodValidation(HandlerMethodValidationException ex, HttpServletResponse response) throws IOException {
        Violation violation = ex.getParameterValidationResults().stream()
                .flatMap(result -> violations(result).stream())
                .min(Comparator.comparing(Violation::field, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(new Violation(ErrorCode.VALOR_INVALIDO, null, ParameterLocation.BODY));
        writeCode(response, violation.code(), violation.field(), violation.location());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    void missingParameter(MissingServletRequestParameterException ex, HttpServletResponse response)
            throws IOException {
        writeCode(response, ErrorCode.VALOR_INVALIDO, ex.getParameterName(), ParameterLocation.QUERY);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    void missingHeader(MissingRequestHeaderException ex, HttpServletResponse response) throws IOException {
        writeCode(response, ErrorCode.VALOR_INVALIDO, ex.getHeaderName(), ParameterLocation.HEADER);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    void typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletResponse response) throws IOException {
        writeCode(response, ErrorCode.VALOR_INVALIDO, parameterName(ex.getParameter()), locationOf(ex.getParameter()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    void notReadable(HttpMessageNotReadableException ex, HttpServletResponse response) throws IOException {
        Optional<ApiException> wrapped = UnhandledExceptions.wrappedApiException(ex);
        if (wrapped.isPresent()) {
            // E.g. the body limit was passed while a converter streamed a chunked body.
            apiException(wrapped.get(), response);
            return;
        }
        if (ex.getCause() instanceof DatabindException databind
                && !databind.getPath().isEmpty()) {
            // Unknown property, wrong scalar type, null for a primitive: the field is on the wire.
            // A wrongly typed field whose constraints raise their own code reports that code.
            JacksonException.Reference last = databind.getPath().getLast();
            Object from = last.from();
            Class<?> owner = from instanceof Class<?> type ? type : from == null ? null : from.getClass();
            ErrorCode code = DeclaredFields.code(owner, last.getPropertyName()).orElse(ErrorCode.VALOR_INVALIDO);
            writeCode(response, code, jsonPath(databind.getPath()), ParameterLocation.BODY);
            return;
        }
        // Malformed JSON, duplicate keys, trailing tokens, a missing or unreadable body.
        write(response, ErrorCode.REQUISICAO_INVALIDA);
    }

    /**
     * A multipart body the container couldn't parse (a cut-off final boundary, no {@code boundary}
     * parameter, a malformed part) is the client's mistake: 400, logged at INFO by class only. Size
     * and part-count limits, and a body cap passed while streaming, keep their own codes.
     */
    @ExceptionHandler(MultipartException.class)
    void malformedMultipart(MultipartException ex, HttpServletResponse response) throws IOException {
        if (ex instanceof MaxUploadSizeExceededException || ex instanceof ErrorResponse
                || UnhandledExceptions.wrappedApiException(ex).isPresent()) {
            unexpected(ex, response);
            return;
        }
        log.info("event=malformed_multipart exception={}", ex.getClass().getSimpleName());
        write(response, ErrorCode.REQUISICAO_INVALIDA);
    }

    // ---- routing and negotiation ---------------------------------------------------------------

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    void noRoute(Exception ex, HttpServletResponse response) throws IOException {
        write(response, ErrorCode.RECURSO_NAO_ENCONTRADO);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletResponse response)
            throws IOException {
        Map<String, String> headers = ex.getSupportedHttpMethods() == null ? Map.of()
                : Map.of(HttpHeaders.ALLOW, ex.getSupportedHttpMethods().stream()
                        .map(HttpMethod::name).sorted().collect(Collectors.joining(", ")));
        write(response, ErrorCode.METODO_NAO_PERMITIDO.status(), ErrorCode.METODO_NAO_PERMITIDO, null, headers);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    void unsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletResponse response)
            throws IOException {
        write(response, ErrorCode.MIDIA_NAO_SUPORTADA);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    void notAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletResponse response) throws IOException {
        write(response, ErrorCode.FORMATO_NAO_ACEITO);
    }

    // ---- dependencies and the rest ------------------------------------------------------------

    @ExceptionHandler({DataAccessResourceFailureException.class, MongoTimeoutException.class,
        MongoSocketException.class})
    void unavailable(Exception ex, HttpServletResponse response) throws IOException {
        log.warn("event=dependency_unavailable exception={}", ex.getClass().getSimpleName());
        write(response, ErrorCode.INDISPONIVEL);
    }

    @ExceptionHandler(Exception.class)
    void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        Optional<ApiException> wrapped = UnhandledExceptions.wrappedApiException(ex);
        if (wrapped.isPresent()) {
            apiException(wrapped.get(), response);
            return;
        }
        if (ex instanceof ErrorResponse framework && !(ex instanceof ApiException)) {
            ErrorCode code = FRAMEWORK_STATUS_CODES.get(framework.getStatusCode().value());
            if (code != null) {
                write(response, code);
                return;
            }
        }
        UnhandledExceptions.log(log, ex);
        write(response, ErrorCode.ERRO_INTERNO);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private record Violation(ErrorCode code, String field, ParameterLocation location) {
    }

    /**
     * The first invalid field in the body type's declared order (then by path and constraint, so
     * the choice is deterministic).
     */
    private static Comparator<FieldError> fieldOrder(Class<?> target) {
        return Comparator.<FieldError>comparingInt(e -> DeclaredFields.index(target, e.getField()))
                .thenComparing(FieldError::getField)
                .thenComparing(e -> String.valueOf(e.getCode()));
    }

    private static List<Violation> violations(ParameterValidationResult result) {
        MethodParameter parameter = result.getMethodParameter();
        if (result instanceof ParameterErrors errors) {
            return errors.getFieldErrors().stream()
                    .map(e -> new Violation(codeOf(e), wireFieldName(e.getField()), ParameterLocation.BODY))
                    .toList();
        }
        ParameterLocation location = locationOf(parameter);
        String name = parameterName(parameter);
        return result.getResolvableErrors().stream()
                .map(error -> new Violation(codeOf(result, error), name, location))
                .toList();
    }

    private static ErrorCode codeOf(FieldError error) {
        if (error.contains(ConstraintViolation.class)) {
            return codeForTemplate(error.unwrap(ConstraintViolation.class).getMessageTemplate());
        }
        return ErrorCode.VALOR_INVALIDO;
    }

    private static ErrorCode codeOf(ParameterValidationResult result, MessageSourceResolvable error) {
        try {
            return codeForTemplate(result.unwrap(error, ConstraintViolation.class).getMessageTemplate());
        } catch (IllegalArgumentException notAViolation) {
            return ErrorCode.VALOR_INVALIDO;
        }
    }

    /** The code a constraint raises: its message template when that is a code name. */
    static ErrorCode codeForTemplate(String template) {
        if (template != null && Arrays.stream(ErrorCode.values()).anyMatch(c -> c.name().equals(template))) {
            return ErrorCode.valueOf(template);
        }
        return ErrorCode.VALOR_INVALIDO;
    }

    private static ParameterLocation locationOf(MethodParameter parameter) {
        if (parameter.hasParameterAnnotation(PathVariable.class)) {
            return ParameterLocation.PATH;
        }
        if (parameter.hasParameterAnnotation(RequestHeader.class)) {
            return ParameterLocation.HEADER;
        }
        if (parameter.hasParameterAnnotation(RequestBody.class)) {
            return ParameterLocation.BODY;
        }
        return ParameterLocation.QUERY;
    }

    /** The parameter's name on the wire (query parameter, path variable or header name). */
    private static String parameterName(MethodParameter parameter) {
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null && StringUtils.hasText(param.name())) {
            return param.name();
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null && StringUtils.hasText(path.name())) {
            return path.name();
        }
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && StringUtils.hasText(header.name())) {
            return header.name();
        }
        return parameter.getParameterName();
    }

    /** A Java property path ({@code items[0].unitPrice}) as the snake_case wire path. */
    static String wireFieldName(String javaPath) {
        StringBuilder wire = new StringBuilder();
        Matcher matcher = PATH_SEGMENT.matcher(javaPath);
        while (matcher.find()) {
            wire.append(matcher.group(1) != null ? SNAKE_CASE.nameForField(null, null, matcher.group(1)) : matcher.group());
        }
        return wire.toString();
    }

    /** A Jackson reference path; names are already the wire names. */
    private static String jsonPath(List<JacksonException.Reference> path) {
        StringBuilder field = new StringBuilder();
        for (JacksonException.Reference reference : path) {
            if (reference.getPropertyName() != null) {
                if (!field.isEmpty()) {
                    field.append('.');
                }
                field.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                field.append('[').append(reference.getIndex()).append(']');
            }
        }
        return field.toString();
    }

    private void writeCode(HttpServletResponse response, ErrorCode code, String field, ParameterLocation location)
            throws IOException {
        write(response, LocationStatusTable.resolve(code, location), code, field, Map.of());
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        write(response, code.status(), code, null, Map.of());
    }

    private void write(HttpServletResponse response, HttpStatus status, ErrorCode code, String field,
            Map<String, String> headers) throws IOException {
        if (response.isCommitted()) {
            log.warn("event=error_after_commit code={}", code);
            return;
        }
        response.resetBuffer();
        writer.write(response, status, code, field, headers);
    }
}
