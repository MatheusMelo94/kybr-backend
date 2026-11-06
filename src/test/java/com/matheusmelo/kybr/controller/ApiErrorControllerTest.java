package com.matheusmelo.kybr.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import com.matheusmelo.kybr.exception.PayloadTooLargeException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class ApiErrorControllerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ApiErrorController controller = new ApiErrorController(new ErrorResponseWriter(mapper));

    @ParameterizedTest
    @CsvSource({
        "400, REQUISICAO_INVALIDA", "404, RECURSO_NAO_ENCONTRADO", "405, METODO_NAO_PERMITIDO",
        "406, FORMATO_NAO_ACEITO", "413, REQUISICAO_GRANDE", "415, MIDIA_NAO_SUPORTADA",
        "414, REQUISICAO_INVALIDA", "431, REQUISICAO_INVALIDA", "500, ERRO_INTERNO", "501, ERRO_INTERNO",
        "503, INDISPONIVEL"
    })
    void mapsAContainerStatusToACodeThatCarriesItsOwnStatus(int status, ErrorCode code) {
        assertThat(ApiErrorController.codeForStatus(status)).isEqualTo(code);
    }

    @Test
    void aDirectRequestIs404() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.error(new MockHttpServletRequest("GET", "/error"), response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("RECURSO_NAO_ENCONTRADO");
    }

    @Test
    void aFilterExceptionOnTheErrorDispatchIs500WithoutDetail() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("secret detail"));
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.error(request, response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("ERRO_INTERNO").doesNotContain("secret");
    }

    @Test
    void aWrappedApiExceptionOnTheErrorDispatchKeepsItsCode() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION,
                new ServletException("wrapped by a library", new PayloadTooLargeException()));
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.error(request, response);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("REQUISICAO_GRANDE");
    }

    @Test
    void aContainerStatusOnTheErrorDispatchIsMapped() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 405);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.error(request, response);

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getContentAsString()).contains("METODO_NAO_PERMITIDO");
    }
}
