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
import io.github.rabobank.cme.rps.RequestsPerSecond;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Minimal OTLP HTTP JSON exporter for RPS metric.
 * Sends Gauge datapoint with resource attributes from Cloud Foundry.
 */
public class OtlpRpsExporter {

    private static final Logger log = Logger.getLogger(OtlpRpsExporter.class);

    private final String otlpMetricsUrl; // e.g. http://host:4318/v1/metrics
    private final RequestsPerSecond rps;
    private final ApplicationInfo app;
    private final String environmentTagKey;
    private final String environmentTagValue;

    public OtlpRpsExporter(String otlpMetricsUrl, RequestsPerSecond rps, ApplicationInfo app, String environmentVarName) {
        this.otlpMetricsUrl = otlpMetricsUrl;
        this.rps = rps;
        this.app = app;
        this.environmentTagKey = environmentVarName;
        this.environmentTagValue = environmentVarName == null ? null : System.getenv(environmentVarName);
    }

    public void send() {
        try {
            int value = rps.rps();
            if (value < 0) {
                log.info("RPS not available, skipping OTLP send.");
                return;
            }
            long nowNanos = Instant.now().toEpochMilli() * 1_000_000L;
            String body = buildOtlpJson(value, nowNanos);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(otlpMetricsUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    if (err != null) {
                        log.error("Error sending OTLP: %s", err, otlpMetricsUrl);
                    } else if (log.isTraceEnabled()) {
                        log.trace("OTLP status: %d body: %s", resp.statusCode(), resp.body());
                    }
                });
        } catch (Throwable t) {
            log.error("Failed to send OTLP metrics", t);
        }
    }

    String buildOtlpJson(int rpsValue, long timeUnixNano) {
        StringBuilder resourceAttrs = new StringBuilder();
        // application, space, org, instance number
        appendAttr(resourceAttrs, "cf.application_name", app.getApplicationName());
        appendAttr(resourceAttrs, "cf.space_name", app.getSpaceName());
        appendAttr(resourceAttrs, "cf.organization_name", app.getOrganizationName());
        appendAttr(resourceAttrs, "cf.instance_index", String.valueOf(app.getIndex()));
        if (environmentTagKey != null && environmentTagValue != null) {
            appendAttr(resourceAttrs, "environment", environmentTagValue);
        }
        // Remove trailing comma if present
        String attrsJson = resourceAttrs.length() > 0 ? resourceAttrs.substring(0, resourceAttrs.length() - 1) : "";

        // Build minimal OTLP JSON (metrics/v1) with a Gauge datapoint
        // name: rps
        return "{\n" +
            "  \"resourceMetrics\": [ {\n" +
            "    \"resource\": { \"attributes\": [" + attrsJson + "] },\n" +
            "    \"scopeMetrics\": [ {\n" +
            "      \"metrics\": [ {\n" +
            "        \"name\": \"custom_throughput\",\n" +
            "        \"unit\": \"1/s\",\n" +
            "        \"gauge\": { \"dataPoints\": [ {\n" +
            "          \"asDouble\": " + rpsValue + ",\n" +
            "          \"timeUnixNano\": " + timeUnixNano + "\n" +
            "        } ] }\n" +
            "      } ]\n" +
            "    } ]\n" +
            "  } ]\n" +
            "}";
    }

    private static void appendAttr(StringBuilder sb, String key, String value) {
        if (value == null) return;
        // OTLP JSON attribute
        sb.append("{\"key\":\"").append(escape(key)).append("\",\"value\":{\"stringValue\":\"")
          .append(escape(value)).append("\"}},");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
