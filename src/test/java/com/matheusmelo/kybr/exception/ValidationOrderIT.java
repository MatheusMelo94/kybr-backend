package com.matheusmelo.kybr.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Several invalid fields report the first in declared order; a missing, null or wrongly typed
 * field reports its own code.
 */
class ValidationOrderIT extends WebIntegrationTest {

    private static final String VALID_CNPJ = "\"11222333000181\"";

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "cnpj and responsavel invalid      | {\"cnpj\":\"11222333000180\",\"responsavel\":\"Maria\"}                     | CNPJ_INVALIDO        | cnpj",
        "responsavel and ref invalid       | {\"cnpj\":" + VALID_CNPJ + ",\"responsavel\":\"Maria\",\"ref\":\"\"}        | RESPONSAVEL_INVALIDO | responsavel",
        "ref and ext_id invalid            | {\"cnpj\":" + VALID_CNPJ + ",\"responsavel\":\"Ana Souza\",\"ref\":\"é\",\"ext_id\":\"é\"} | VALOR_INVALIDO | ref",
        "ext_id invalid                    | {\"cnpj\":" + VALID_CNPJ + ",\"responsavel\":\"Ana Souza\",\"ext_id\":\"é\"} | VALOR_INVALIDO       | ext_id",
        "cnpj missing                      | {\"responsavel\":\"Ana Souza\"}                                              | CNPJ_INVALIDO        | cnpj",
        "cnpj null                         | {\"cnpj\":null,\"responsavel\":\"Ana Souza\"}                                | CNPJ_INVALIDO        | cnpj",
        "responsavel missing               | {\"cnpj\":" + VALID_CNPJ + "}                                                 | RESPONSAVEL_INVALIDO | responsavel",
        "empty body                        | {}                                                                           | CNPJ_INVALIDO        | cnpj",
        "cnpj a number                     | {\"cnpj\":11222333000181,\"responsavel\":\"Ana Souza\"}                      | CNPJ_INVALIDO        | cnpj",
        "responsavel a number              | {\"cnpj\":" + VALID_CNPJ + ",\"responsavel\":42}                             | RESPONSAVEL_INVALIDO | responsavel",
        "responsavel an object             | {\"cnpj\":" + VALID_CNPJ + ",\"responsavel\":{}}                             | RESPONSAVEL_INVALIDO | responsavel",
        "ref a number                      | {\"cnpj\":" + VALID_CNPJ + ",\"responsavel\":\"Ana Souza\",\"ref\":7}        | VALOR_INVALIDO       | ref",
        "unknown property                  | {\"cnpj\":" + VALID_CNPJ + ",\"responsavel\":\"Ana Souza\",\"turnstile_token\":\"x\"} | VALOR_INVALIDO | turnstile_token"
    })
    void reportsTheExpectedCodeAndField(String scenario, String body, String code, String field) {
        Response response = http().contentType("application/json").body(body).post("/test/link/validation");

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(422);
        assertThat(response.jsonPath().getString("code")).isEqualTo(code);
        assertThat(response.jsonPath().getString("field")).isEqualTo(field);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "all fields valid   | {\"cnpj\":\"11222333000181\",\"responsavel\":\"Ana O'Neil-Souza\",\"ref\":\"~x\",\"ext_id\":null}"
    })
    void aValidBodyPasses(String scenario, String body) {
        Response response = http().contentType("application/json").body(body).post("/test/link/validation");

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
    }
}
