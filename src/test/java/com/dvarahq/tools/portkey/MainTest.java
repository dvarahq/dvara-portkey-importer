/*
 * Copyright 2026 DVARA Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dvarahq.tools.portkey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args, new PrintStream(out), new PrintStream(err));
    }

    @Test
    void writesTheFilesAndASummary(@TempDir Path dir) {
        Path target = dir.resolve("new/folder");
        assertThat(run(Fixtures.path("loadbalance.json").toString(), "-o", target.toString())).isEqualTo(Main.OK);
        assertThat(target.resolve(Main.GATEWAY_YAML)).exists();
        assertThat(target.resolve(Main.REPORT)).content().startsWith("# Portkey import report");
        assertThat(target.resolve(Main.PROPERTIES)).exists();
        assertThat(out.toString()).contains("1 configs").contains("routes").contains("Read ").contains(Main.REPORT);
    }

    @Test
    void refusesToReplaceFilesWithoutForce(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(Main.GATEWAY_YAML), "mine");
        assertThat(run(Fixtures.path("quickstart.json").toString(), "-o", dir.toString()))
                .isEqualTo(Main.WOULD_OVERWRITE);
        assertThat(dir.resolve(Main.GATEWAY_YAML)).hasContent("mine");
        assertThat(err.toString()).contains("--force");

        assertThat(run(Fixtures.path("quickstart.json").toString(), "-o", dir.toString(), "--force"))
                .isEqualTo(Main.OK);
        assertThat(dir.resolve(Main.GATEWAY_YAML)).content().startsWith("# gateway.yaml");
    }

    @Test
    void aStalePropertiesFileIsRemovedWhenTheNewImportHasNone(@TempDir Path dir) {
        assertThat(run(Fixtures.path("loadbalance.json").toString(), "-o", dir.toString())).isEqualTo(Main.OK);
        assertThat(run(Fixtures.path("guardrails.json").toString(), "-o", dir.toString(), "--force")).isEqualTo(Main.OK);
        assertThat(dir.resolve(Main.PROPERTIES)).doesNotExist();
    }

    @Test
    void usageErrorsExitWithTwo() {
        assertThat(run()).isEqualTo(Main.USAGE);
        assertThat(run("--bogus")).isEqualTo(Main.USAGE);
        assertThat(run("a.json", "b.json")).isEqualTo(Main.USAGE);
        assertThat(run("a.json", "-o")).isEqualTo(Main.USAGE);
        assertThat(run("--help")).isEqualTo(Main.OK);
        assertThat(out.toString()).contains("Usage:");
    }

    @Test
    void unreadableOrInvalidInputExitsWithOneAndWritesNothing(@TempDir Path dir) throws IOException {
        Path outDir = dir.resolve("out");
        assertThat(run(dir.resolve("missing.json").toString(), "-o", outDir.toString())).isEqualTo(Main.BAD_INPUT);
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{\"model_list\": []}");
        assertThat(run(bad.toString(), "-o", outDir.toString())).isEqualTo(Main.BAD_INPUT);
        assertThat(err.toString()).contains("not a Portkey config");
        Files.writeString(bad, "{\"provider\": \"openai\", \"api_key\": \"sk-NOT-JSON-SECRET\" ");
        assertThat(run(bad.toString(), "-o", outDir.toString())).isEqualTo(Main.BAD_INPUT);
        assertThat(err.toString()).contains("not valid JSON").doesNotContain("sk-NOT-JSON-SECRET");
        Path empty = Files.createDirectory(dir.resolve("empty"));
        assertThat(run(empty.toString(), "-o", outDir.toString())).isEqualTo(Main.BAD_INPUT);
        assertThat(outDir).doesNotExist();
    }

    @Test
    void aFolderImportsEveryJsonFileInItAndNothingElse(@TempDir Path dir) throws IOException {
        Path in = Files.createDirectory(dir.resolve("configs"));
        Files.writeString(in.resolve("chat.json"), Fixtures.read("quickstart.json"));
        Files.writeString(in.resolve("support.json"), Fixtures.read("guardrails.json"));
        Files.writeString(in.resolve("notes.txt"), "not a config");
        Path outDir = dir.resolve("out");
        assertThat(run(in.toString(), "-o", outDir.toString())).isEqualTo(Main.OK);
        assertThat(out.toString()).contains("2 configs");
        assertThat(outDir.resolve(Main.GATEWAY_YAML)).content().contains("model: \"chat\"").contains("model: \"support\"");
    }

    static Stream<String> fixtures() {
        return Fixtures.ALL.stream();
    }

    /** The promise the tool makes: no secret value from the source reaches any file it writes, or the console. */
    @ParameterizedTest
    @MethodSource("fixtures")
    void noLiteralSecretReachesAnyOutput(String fixture, @TempDir Path dir) throws IOException {
        assertThat(run(Fixtures.path(fixture).toString(), "-o", dir.toString())).isEqualTo(Main.OK);
        StringBuilder everything = new StringBuilder(out.toString()).append(err);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                everything.append(Files.readString(f));
            }
        }
        for (String secret : Fixtures.PLANTED_SECRETS) {
            assertThat(everything.toString()).as("output of %s", fixture).doesNotContain(secret);
        }
    }

    @Test
    void theFixturesReallyPlantTheSecretsTheTestLooksFor() {
        String all = String.join("\n", Fixtures.ALL.stream().map(Fixtures::read).toList());
        Fixtures.PLANTED_SECRETS.forEach(s -> assertThat(all).contains(s));
    }
}
