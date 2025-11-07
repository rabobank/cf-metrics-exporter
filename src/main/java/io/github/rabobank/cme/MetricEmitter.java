package io.github.rabobank.cme;

public interface MetricEmitter {
    void emitMetric(String metricName, int metricValue);
    String name();
}
