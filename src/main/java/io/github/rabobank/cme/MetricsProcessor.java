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
