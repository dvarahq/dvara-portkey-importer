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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    @Test
    void readsObjectsArraysAndScalarsInOrder() {
        Object v = Json.parse("""
                {"b": [1, 2.5, -3e2, true, false, null], "a": "x\\u0041\\n\\"", "c": {}}""");
        assertThat(v).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        assertThat(m.keySet()).containsExactly("b", "a", "c");
        assertThat(m.get("b")).isEqualTo(java.util.Arrays.asList(1L, 2.5, -300.0, true, false, null));
        assertThat(m.get("a")).isEqualTo("xA\n\"");
    }

    @Test
    void anErrorNamesThePlaceButNotTheText() {
        assertThatThrownBy(() -> Json.parse("{\n  \"api_key\": sk-SECRET\n}"))
                .isInstanceOf(Json.ParseException.class).hasMessageContaining("line 2").hasMessageNotContaining("SECRET");
        assertThatThrownBy(() -> Json.parse("{} {}")).isInstanceOf(Json.ParseException.class);
        assertThatThrownBy(() -> Json.parse("[".repeat(100))).isInstanceOf(Json.ParseException.class);
    }
}
