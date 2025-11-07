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

import io.github.rabobank.cme.rps.RequestsPerSecond;

import java.util.List;

public class MetricsProcessor {

    private static final Logger log = Logger.getLogger(MetricsProcessor.class);

    private final RequestsPerSecond rsp;
    private final List<MetricEmitter> metricEmitters;

    public MetricsProcessor(RequestsPerSecond requestsPerSecond, List<MetricEmitter> metricEmitters) {
        this.rsp = requestsPerSecond;
        this.metricEmitters = List.copyOf(metricEmitters);
    }

    public void tick() {

        try {
            int currentRps = rsp.rps();

            if (currentRps < 0) {
                log.info("RPS not available, skip send.");
                return;
            }

            log.info("Sending RPS: " + currentRps);

            for (MetricEmitter emitter : metricEmitters) {
                try {
                    emitter.emitMetric(CfMetricsAgent.CUSTOM_THROUGHPUT_METRIC_NAME, currentRps);
                } catch (Exception e) {
                    log.error("Error while emitting metric to " + emitter.name(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error while processing metrics", e);
        }

    }

}
