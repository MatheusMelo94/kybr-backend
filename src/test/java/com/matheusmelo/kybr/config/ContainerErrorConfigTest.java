package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.dto.ErrorResponse;
import com.matheusmelo.kybr.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ContainerErrorConfigTest {

    @Test
    void theValvesEnvelopeIsByteIdenticalToTheSerialisedErrorResponseForEveryCode() {
        JsonMapper mapper = JsonMapper.builder().build();

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(ContainerErrorConfig.EnvelopeErrorReportValve.envelope(code))
                    .isEqualTo(mapper.writeValueAsString(ErrorResponse.of(code)));
        }
    }
}
