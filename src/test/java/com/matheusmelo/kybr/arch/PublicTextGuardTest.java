package com.matheusmelo.kybr.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The public-text guard fails on references to private working documents and passes on the
 * repository's own tree. The forbidden samples are assembled at runtime, so this source file never
 * contains one and the guard can scan it like any other.
 */
class PublicTextGuardTest {

    private static final Path SCRIPT = Path.of("scripts/ci/check-public-text.sh").toAbsolutePath();

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {
        "docs|/plans/x.md", "\u00a7| 3", "SEC|-01", "CR|-05", "CR|-P11", "NIT|-04", "F|03",
        "ADR|-0006", "T|15", "hand|off", "Hand|off", "G|8", "B1|-08", "B|-2", "Q|-13", "PRC|-17", "XC|-04",
        "LOG|-11", "ADM|-01", "(VFY|-16)", "CAS|-07:"})
    void failsOnAPrivateReferenceInSource(String parts) throws Exception {
        write("src/main/java/Sample.java", "// see " + parts.replace("|", "") + " for details\n");

        Result result = run(root);

        assertThat(result.exit()).isNotZero();
        assertThat(result.output()).contains("src/main/java/Sample.java:1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"README.md", "deploy/alerting/rules.yml", "scripts/alerts/trigger.sh",
        ".github/workflows/ci.yml", "fly.toml"})
    void failsOnATestCaseIdInAnyFileOutsideSource(String path) throws Exception {
        write(path, "# covers " + "PRC" + "-17\n");

        Result result = run(root);

        assertThat(result.exit()).isNotZero();
        assertThat(result.output()).contains(path + ":1");
    }

    @Test
    void scansOnlyTrackedFilesInAGitWorkTree() throws Exception {
        write("src/main/java/Clean.java", "class Clean {}\n");
        git("init", "-q");
        git("add", "src/main/java/Clean.java");
        write("NOTES.md", "local notes on " + "PRC" + "-17\n");

        Result result = run(root);

        assertThat(result.exit()).as(result.output()).isZero();
    }

    @Test
    void failsOnAPrivateReferenceInTheContract() throws Exception {
        write("docs/openapi.yaml", "description: decided in " + "ADR" + "-0007\n");

        assertThat(run(root).exit()).isNotZero();
    }

    @Test
    void ignoresPrivateDocumentsThemselves() throws Exception {
        write("docs" + "/plans/notes.md", "ADR" + "-0006 and " + "SEC" + "-01 and " + "PRC" + "-17\n");
        write(".gitignore", "docs" + "/*\n!docs/openapi.yaml\n");
        write("src/main/java/Clean.java", "class Clean {}\n");

        assertThat(run(root).exit()).isZero();
    }

    @Test
    void passesOnLegitimateLookalikes() throws Exception {
        write("src/main/java/Ok.java", String.join("\n",
                "String a = \"/v3/api-docs/swagger-config\";",
                "String b = \"docs/openapi.yaml\";",
                "String c = \"/public/v/s/docs/\" + token;",
                "String d = \"2026-11-07T14:03:11Z\";",
                "String e = \"UTF-16\";",
                "String f = \"SHA-256\"; String g = \"UTF-8\"; String h = \"ISO-8859-1\";",
                "String i = \"12.345.678/0001-95\"; String j = \"12ABC34501DE35\";",
                "int status = 404; String version = \"0.2.0\"; String hex = \"0x1F-2A\";",
                "String k = \"AB-123\"; String l = \"ext-12\"; String m = \"loja-ab-12\";",
                ""));

        Result result = run(root);

        assertThat(result.exit()).as(result.output()).isZero();
    }

    @Test
    void anAllowlistedLineDoesNotFail() throws Exception {
        String sample = "T" + "15";
        write("src/main/java/Allowed.java", "String grade = \"" + sample + "\";\n");
        write("scripts/ci/public-text-allowlist.txt",
                "# a synthetic exception for this test\nsrc/main/java/Allowed.java|grade = \"" + sample + "\"\n");

        assertThat(run(root).exit()).isZero();
    }

    @Test
    void passesOnTheRepositoryTree() throws Exception {
        Result result = run(Path.of("").toAbsolutePath());

        assertThat(result.exit()).as(result.output()).isZero();
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void git(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    private static Result run(Path dir) throws Exception {
        Process process = new ProcessBuilder("bash", SCRIPT.toString(), dir.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), output);
    }

    private record Result(int exit, String output) {
    }
}
