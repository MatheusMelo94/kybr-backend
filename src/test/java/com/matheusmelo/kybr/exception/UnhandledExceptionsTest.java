package com.matheusmelo.kybr.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import java.util.Optional;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(OutputCaptureExtension.class)
class UnhandledExceptionsTest {

    @Test
    void describesMongoFailuresByClassAndCodeOnly() {
        MongoWriteException mongo = new MongoWriteException(
                new WriteError(11000, "E11000 dup key: { email: \"someone@example.invalid\" }", new BsonDocument()),
                new ServerAddress());
        DuplicateKeyException wrapped = new DuplicateKeyException("Write error: " + mongo.getMessage(), mongo);

        Optional<String> description = UnhandledExceptions.mongoDescription(wrapped);

        assertThat(description).hasValueSatisfying(text -> assertThat(text)
                .contains("DuplicateKeyException").contains("MongoWriteException").contains("11000")
                .doesNotContain("example.invalid").doesNotContain("E11000"));
    }

    @Test
    void findsAnApiExceptionWrappedByALibrary() {
        PayloadTooLargeException tooLarge = new PayloadTooLargeException();

        assertThat(UnhandledExceptions.wrappedApiException(new IllegalStateException("x", tooLarge)))
                .containsSame(tooLarge);
        assertThat(UnhandledExceptions.wrappedApiException(new IllegalStateException("x"))).isEmpty();
    }

    @Test
    void hasNoMongoDescriptionForOtherExceptions() {
        assertThat(UnhandledExceptions.mongoDescription(new IllegalStateException("x"))).isEmpty();
    }

    @Test
    void logsAMongoFailureWithoutItsMessageAndAnythingElseWithItsStackTrace(CapturedOutput output) {
        MongoWriteException mongo = new MongoWriteException(
                new WriteError(11000, "E11000 dup key: { email: \"someone@example.invalid\" }", new BsonDocument()),
                new ServerAddress());

        UnhandledExceptions.log(LoggerFactory.getLogger(UnhandledExceptionsTest.class),
                new DuplicateKeyException("Write error: " + mongo.getMessage(), mongo));
        UnhandledExceptions.log(LoggerFactory.getLogger(UnhandledExceptionsTest.class),
                new IllegalStateException("boom"));

        assertThat(output).contains("event=unhandled_exception exception=DuplicateKeyException")
                .contains("event=unhandled_exception exception=java.lang.IllegalStateException")
                .contains("at com.matheusmelo.kybr")
                .doesNotContain("example.invalid");
    }
}
