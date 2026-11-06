package com.matheusmelo.kybr.exception;

import com.matheusmelo.kybr.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the error envelope straight to a servlet response: used by filters (outside MVC) and by
 * the exception handler, so errors are JSON whatever the request's {@code Accept} says.
 */
@Component
public class ErrorResponseWriter {

    private final JsonMapper jsonMapper;

    public ErrorResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code) throws IOException {
        write(response, code.status(), code, null, Map.of());
    }

    public void write(HttpServletResponse response, ApiException exception) throws IOException {
        write(response, exception.status(), exception.code(), exception.field(), exception.headers());
    }

    public void write(HttpServletResponse response, HttpStatusCode status, ErrorCode code, String field,
            Map<String, String> headers) throws IOException {
        response.setStatus(status.value());
        headers.forEach(response::setHeader);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.getOutputStream().write(jsonMapper.writeValueAsBytes(ErrorResponse.of(code, field)));
    }
}
