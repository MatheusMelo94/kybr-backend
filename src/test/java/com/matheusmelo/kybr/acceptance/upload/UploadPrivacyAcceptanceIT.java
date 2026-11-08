package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.acceptance.upload.fixtures.BrowserMultipart;
import com.matheusmelo.kybr.acceptance.upload.fixtures.SampleFiles;
import com.matheusmelo.kybr.support.web.RawHttp;
import io.restassured.response.Response;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The original file name is ignored completely: whatever a browser puts in it (accents, a CPF-like
 * run, quotes and line breaks, paths, bidi controls, 300 characters, nothing at all), the upload is
 * accepted and the name appears in no response, log line, record, storage key or storage metadata.
 * The case token and the page address the browser may send never reach a log either.
 */
@ExtendWith(OutputCaptureExtension.class)
class UploadPrivacyAcceptanceIT extends UploadAcceptanceTest {

    private final String canary = "canario-" + UUID.randomUUID().toString().substring(0, 8);

    private RawHttp.Response sendNamed(String filename, String label) throws Exception {
        BrowserMultipart form = new BrowserMultipart().field("tipo", TIPO.name())
                .file("arquivos", filename, "image/jpeg", SampleFiles.photo(label));
        byte[] body = form.body();
        return RawHttp.send(port, form.head(uploadPath(pending), body, ALLOWED_ORIGIN), body);
    }

    private void assertNameLeftNoTrace(String needle, CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain(needle);
        assertThat(storage.keys()).noneMatch(key -> key.contains(needle));
        for (String key : storage.keys()) {
            assertThat(storage.metadata(key).toString()).doesNotContain(needle);
        }
        for (String collection : List.of("documents", "audit_events", "case_events", "cases")) {
            String field = "cases".equals(collection) ? "_id" : "case_id";
            for (Document stored : mongo.find(query(where(field).is(pending.linkCase().id())), Document.class, collection)) {
                assertThat(stored.toJson()).as(collection).doesNotContain(needle);
            }
        }
    }

    @Test
    void aBrowserShapedUploadWithAnAccentedNameAndACpfLikeRunLeavesNoTraceOfTheName(CapturedOutput output)
            throws Exception {
        String filename = "Identidade João " + canary + " 123.456.789-09.jpg";

        RawHttp.Response response = sendNamed(filename, "nome");

        assertThat(response.status()).as(response.body()).isEqualTo(202);
        assertThat(response.body()).doesNotContain(canary);
        assertNameLeftNoTrace(canary, output);
        assertNameLeftNoTrace("123.456.789-09", output);
        assertThat(output.getAll()).doesNotContain(pending.token());
    }

    @Test
    void everyAwkwardNameABrowserCanSendIsAccepted(CapturedOutput output) throws Exception {
        List<String> names = List.of(
                canary + "-" + "ç".repeat(290) + ".jpg",
                canary + " \"aspas\" e\r\nquebra.jpg",
                "../../" + canary + ".jpg",
                "C:\\fakepath\\" + canary + ".jpg",
                canary + "-\uD83D\uDCF7.jpg",
                canary + "-\u202Egpj.exe",
                "");
        int accepted = 0;
        for (String name : names) {
            RawHttp.Response response = sendNamed(name, "estranho-" + accepted);

            assertThat(response.status()).as("name: " + name + " -> " + response.body()).isEqualTo(202);
            accepted++;
            assertThat(response.body()).contains("\"documentos_enviados\":" + accepted);
        }
        assertNameLeftNoTrace(canary, output);
        assertNoTempFileLeft();
    }

    @Test
    void aCookieAndThePageAddressChangeNothingAndAreNeverLogged(CapturedOutput output) {
        String referer = "https://kybr.com.br/v/" + workspace.slug() + "/c/" + pending.token() + "?" + canary;

        Response response = multipart(TIPO.name(), SampleFiles.photo("cookie")).header("Origin", ALLOWED_ORIGIN)
                .header("Cookie", "sessao=" + canary).header("Referer", referer).post(uploadPath(pending));

        assertReceived(response, TIPO, 1);
        assertThat(response.header("Access-Control-Allow-Credentials")).isNull();
        assertThat(output.getAll()).doesNotContain(canary).doesNotContain(pending.token());
    }
}
