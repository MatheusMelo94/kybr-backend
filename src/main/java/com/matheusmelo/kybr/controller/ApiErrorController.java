package com.matheusmelo.kybr.controller;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import com.matheusmelo.kybr.exception.UnhandledExceptions;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Replaces Boot's {@code /error} handling: errors the container dispatches to
 * {@code /error}, outside MVC (filter exceptions, Tomcat-detected 4xx), are rendered as the
 * envelope. Filters [1] to [3] also run on that dispatch, so it carries {@code X-Request-Id} and
 * the security headers. Requested directly, {@code /error} is just an unknown route: 404.
 */
@Controller
public class ApiErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

    private final ErrorResponseWriter writer;

    public ApiErrorController(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @RequestMapping("${server.error.path:/error}")
    void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getDispatcherType() != DispatcherType.ERROR) {
            writer.write(response, ErrorCode.RECURSO_NAO_ENCONTRADO);
            return;
        }
        Throwable exception = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (exception instanceof ApiException api) {
            writer.write(response, api);
            return;
        }
        if (exception != null) {
            Optional<ApiException> wrapped = UnhandledExceptions.wrappedApiException(exception);
            if (wrapped.isPresent()) {
                writer.write(response, wrapped.get());
                return;
            }
            UnhandledExceptions.log(log, exception);
            writer.write(response, ErrorCode.ERRO_INTERNO);
            return;
        }
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        writer.write(response, codeForStatus(status instanceof Integer code ? code : 500));
    }

    /**
     * The envelope code for a status the container chose. Statuses with no code of their own fall
     * back to the generic 400 or 500 code, so a code always travels with its own status.
     */
    public static ErrorCode codeForStatus(int status) {
        return switch (status) {
            case 404 -> ErrorCode.RECURSO_NAO_ENCONTRADO;
            case 405 -> ErrorCode.METODO_NAO_PERMITIDO;
            case 406 -> ErrorCode.FORMATO_NAO_ACEITO;
            case 413 -> ErrorCode.REQUISICAO_GRANDE;
            case 415 -> ErrorCode.MIDIA_NAO_SUPORTADA;
            case 503 -> ErrorCode.INDISPONIVEL;
            default -> status >= 400 && status < 500 ? ErrorCode.REQUISICAO_INVALIDA : ErrorCode.ERRO_INTERNO;
        };
    }
}
