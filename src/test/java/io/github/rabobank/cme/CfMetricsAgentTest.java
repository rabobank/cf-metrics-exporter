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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CfMetricsAgentTest {

    @Test
    void testStartup() {

        CfMetricsAgent cfMetricsExporter = new CfMetricsAgent();
        String[] args = {"-d", "--metricsEndpoint", "https://example.com/test"};
        Arguments arguments = Arguments.parseArgs(args);
        cfMetricsExporter.start(arguments, () -> System.out.println("INITIALIZER CALLED"));

    }

    @Test
    void splitAgentArgs() {
        String[] strings = CfMetricsAgent.splitAgentArgs("--duration=PT2S,--tag=service/MyApp,tag=systemUnderTest/afterburner");
        assertEquals(6, strings.length);
    }

}
