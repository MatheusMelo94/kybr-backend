package com.matheusmelo.kybr.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.matheusmelo.kybr.exception.LocationStatusTable.Key;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

/** CNPJ_INVALIDO is 400 on a query or path parameter and 422 in a body. */
class LocationStatusTableTest {

    @Test
    void theTableHoldsExactlyTheCnpjEntries() {
        assertThat(LocationStatusTable.entries()).containsExactlyInAnyOrderEntriesOf(Map.of(
                new Key(ErrorCode.CNPJ_INVALIDO, ParameterLocation.QUERY), HttpStatus.BAD_REQUEST,
                new Key(ErrorCode.CNPJ_INVALIDO, ParameterLocation.PATH), HttpStatus.BAD_REQUEST,
                new Key(ErrorCode.CNPJ_INVALIDO, ParameterLocation.BODY), HttpStatus.UNPROCESSABLE_CONTENT));
    }

    @Test
    void cnpjInvalidoResolvesPerLocation() {
        assertThat(LocationStatusTable.resolve(ErrorCode.CNPJ_INVALIDO, ParameterLocation.QUERY))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(LocationStatusTable.resolve(ErrorCode.CNPJ_INVALIDO, ParameterLocation.PATH))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(LocationStatusTable.resolve(ErrorCode.CNPJ_INVALIDO, ParameterLocation.BODY))
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void cnpjInvalidoAtAnUnlistedLocationFailsInsteadOfDefaulting() {
        assertThatIllegalStateException()
                .isThrownBy(() -> LocationStatusTable.resolve(ErrorCode.CNPJ_INVALIDO, ParameterLocation.HEADER));
    }

    @ParameterizedTest
    @EnumSource(ParameterLocation.class)
    void otherCodesKeepTheirDefaultStatusEverywhere(ParameterLocation location) {
        assertThat(LocationStatusTable.resolve(ErrorCode.VALOR_INVALIDO, location))
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
