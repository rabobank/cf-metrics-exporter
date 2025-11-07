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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.mockito.Mockito.*;

class MetricsProcessorTest {

    private RequestsPerSecond rps;
    private MetricEmitter emitter1;
    private MetricEmitter emitter2;

    @BeforeEach
    void setUp() {
        rps = mock(RequestsPerSecond.class);
        emitter1 = mock(MetricEmitter.class);
        emitter2 = mock(MetricEmitter.class);
        when(emitter1.name()).thenReturn("emitter1");
        when(emitter2.name()).thenReturn("emitter2");
    }

    @Test
    void emitsMetricToAllEmittersWhenRpsIsNonNegative() {
        // Given
        when(rps.rps()).thenReturn(42);
        MetricsProcessor processor = new MetricsProcessor(rps, List.of(emitter1, emitter2));
        // When
        processor.tick();
        // Then
        verify(emitter1).emitMetric(CfMetricsAgent.CUSTOM_THROUGHPUT_METRIC_NAME, 42);
        verify(emitter2).emitMetric(CfMetricsAgent.CUSTOM_THROUGHPUT_METRIC_NAME, 42);
        verifyNoMoreInteractions(emitter1, emitter2);
    }

    @Test
    void skipsEmittingWhenRpsIsNegative() {
        // Given
        when(rps.rps()).thenReturn(-1);
        MetricsProcessor processor = new MetricsProcessor(rps, List.of(emitter1, emitter2));
        // When
        processor.tick();
        // Then
        verifyNoInteractions(emitter1, emitter2);
    }

    @Test
    void continuesWhenOneEmitterThrows() {
        // Given
        when(rps.rps()).thenReturn(7);
        doThrow(new RuntimeException("boom")).when(emitter1).emitMetric(anyString(), anyInt());
        MetricsProcessor processor = new MetricsProcessor(rps, List.of(emitter1, emitter2));
        // When
        processor.tick();
        // Then
        verify(emitter1).emitMetric(CfMetricsAgent.CUSTOM_THROUGHPUT_METRIC_NAME, 7);
        verify(emitter1).name();
        verify(emitter2).emitMetric(CfMetricsAgent.CUSTOM_THROUGHPUT_METRIC_NAME, 7);
        verifyNoMoreInteractions(emitter2);
    }

    @Test
    void handlesExceptionFromRpsSupplierAndDoesNotEmit() {
        // Given
        when(rps.rps()).thenThrow(new RuntimeException("rps failed"));
        MetricsProcessor processor = new MetricsProcessor(rps, List.of(emitter1, emitter2));
        // When
        processor.tick();
        // Then
        verifyNoInteractions(emitter1, emitter2);
    }
}
