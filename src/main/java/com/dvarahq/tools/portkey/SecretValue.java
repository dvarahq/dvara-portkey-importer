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

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A secret, as the output may hold it: the name of an environment variable, never a value.
 *
 * <p>There is no constructor that takes a value. A literal key found in the input is replaced by a
 * variable name before it reaches this class, so nothing downstream can write it out by mistake.
 */
record SecretValue(String envName) {

    /** The names the gateway's loader resolves. Anything else would be left in the file as text. */
    static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * A value that only names a variable, such as {@code ${OPENAI_API_KEY}} or {@code $OPENAI_API_KEY}. Portkey
     * has no syntax of its own for this, but teams that keep a config in their repo often template it this way.
     */
    private static final Pattern ENV_REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}|\\$([A-Za-z_][A-Za-z0-9_]*)");

    SecretValue {
        if (envName == null || !ENV_NAME.matcher(envName).matches()) {
            throw new IllegalArgumentException("not an environment variable name: " + envName);
        }
    }

    /** How {@code gateway.yaml} writes it: the gateway resolves {@code ${NAME}} at startup. */
    String yaml() {
        return "${" + envName + "}";
    }

    /** The variable a value names, when the whole value is a reference such as {@code ${NAME}}. */
    static Optional<String> envReference(Object value) {
        if (value instanceof String s) {
            var m = ENV_REFERENCE.matcher(s.strip());
            if (m.matches()) {
                return Optional.of(m.group(1) != null ? m.group(1) : m.group(2));
            }
        }
        return Optional.empty();
    }

    static boolean isValidName(String name) {
        return name != null && ENV_NAME.matcher(name).matches();
    }
}
