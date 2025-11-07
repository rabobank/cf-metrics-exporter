/*
 * Copyright (C) 2025 Peter Paul Bakker - Rabobank
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
package io.github.rabobank.cme;

import io.github.rabobank.cme.domain.ApplicationInfo;
import io.github.rabobank.cme.otlp.OtlpRpsExporter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpRpsExporterTest {

    @Test
    void payloadContainsResourceAttributes() throws Exception {
        // Given
        ApplicationInfo ai = new ApplicationInfo("app-id", 1, "app-name", "space", "org");
        OtlpRpsExporter exp = new OtlpRpsExporter(URI.create("http://example/v1/metrics"), ai, null);
        // When
        Method m = OtlpRpsExporter.class.getDeclaredMethod("buildOtlpJson", int.class, long.class);
        m.setAccessible(true);
        String json = (String) m.invoke(exp, 42, 123L);
        // Then
        assertTrue(json.contains("\"name\": \"custom_throughput\""));
        assertTrue(json.contains("\"asDouble\": 42"));
        assertTrue(json.contains("cf_application_name"));
        assertTrue(json.contains("app-name"));
        assertTrue(json.contains("cf_space_name"));
        assertTrue(json.contains("space"));
        assertTrue(json.contains("cf_organization_name"));
        assertTrue(json.contains("org"));
        assertTrue(json.contains("cf_instance_index"));
        assertTrue(json.contains("1"));
    }
}
