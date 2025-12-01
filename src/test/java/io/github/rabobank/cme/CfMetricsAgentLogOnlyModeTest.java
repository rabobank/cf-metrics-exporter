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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * Verifies that when enableLogEmitter is true, the agent activates even if CF env vars are missing.
 */
class CfMetricsAgentLogOnlyModeTest {

    @Test
    void start_activatesWithLogEmitter_whenCfEnvMissing() {
        // Given CF env variables are not set in test environment by default
        CfMetricsAgent agent = new CfMetricsAgent();
        String[] args = {"--enableLogEmitter", "--intervalSeconds", "1", "--rpsType", "random"};
        Arguments arguments = Arguments.parseArgs(args);

        AtomicBoolean initialized = new AtomicBoolean(false);

        // When
        agent.start(arguments, () -> initialized.set(true));

        // Then initializer should have been called, meaning agent activated in log-only mode
        assertTrue(initialized.get(), "Agent should initialize when log emitter is enabled even without CF env vars");
    }

    @Test
    void start_doesNotActivate_whenCfEnvMissing_andLogEmitterDisabled() {
        CfMetricsAgent agent = new CfMetricsAgent();
        String[] args = {"--intervalSeconds", "1", "--rpsType", "random"};
        Arguments arguments = Arguments.parseArgs(args);

        AtomicBoolean initialized = new AtomicBoolean(false);

        agent.start(arguments, () -> initialized.set(true));

        assertFalse(initialized.get(), "Agent should not initialize when CF env vars are missing and log emitter disabled");
    }
}
