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

import io.github.rabobank.cme.rps.RequestsPerSecondType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArgumentsTest {

    @Test
    void parseArgsMetricsEndpoint() {

        String[] args = {"-d", "--metricsEndpoint", "https://example.com/metrics", "--intervalSeconds", "10", "--rpsType", "tomcat-mbean", "-e", "PCF_SYSTEM_ENV"};

        Arguments arguments = Arguments.parseArgs(args);

        assertNotNull(arguments);
        assertEquals("https://example.com/metrics", arguments.metricsEndpoint());
        assertTrue(arguments.isDebug());
        assertEquals(10, arguments.intervalSeconds());
        Assertions.assertEquals(RequestsPerSecondType.TOMCAT_MBEAN, arguments.type());

    }

    @Test
    void parseArgsIntervalSeconds() {
        String[] args = {"-i", "10s"};

        assertThrows(NumberFormatException.class, () -> Arguments.parseArgs(args));
    }

    @Test
    void parseArgsType() {
        String[] args = {"-r", "none" };

        assertThrows(IllegalArgumentException.class, () -> Arguments.parseArgs(args));
    }

}