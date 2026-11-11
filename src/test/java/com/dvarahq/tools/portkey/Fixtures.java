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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** The Portkey configs under {@code src/test/resources/fixtures}, written after Portkey's documented examples. */
final class Fixtures {

    static final List<String> ALL = List.of("quickstart.json", "fallback.json", "admin-export.json",
            "loadbalance.json", "model-aliases.json", "subscription-tier.json", "guardrails.json", "self-hosted.json");

    /** Literal secrets planted in the fixtures; none may appear in any output. */
    static final List<String> PLANTED_SECRETS = List.of("LITERAL-OPENAI-SECRET-0001", "LITERAL-AWS-SECRET-0002",
            "LITERAL-AWS-SECRET-0003", "LITERAL-HOST-SECRET-0004", "LITERAL-VLLM-SECRET-0005");

    private Fixtures() {}

    static String read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path path(String name) {
        try {
            return Path.of(Fixtures.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static PortkeyImporter.Result importFixture(String name) {
        return PortkeyImporter.importJson(read(name), name);
    }
}
