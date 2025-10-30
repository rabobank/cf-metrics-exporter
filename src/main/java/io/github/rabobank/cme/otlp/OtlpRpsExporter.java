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
package io.github.rabobank.cme.otlp;

import io.github.rabobank.cme.Logger;
import io.github.rabobank.cme.domain.ApplicationInfo;
import io.github.rabobank.cme.rps.RequestsPerSecond;
import io.github.rabobank.cme.util.HttpUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * OTLP HTTP JSON exporter for RPS metric.
 * Sends Gauge datapoint with resource attributes from Cloud Foundry.
 */
public class OtlpRpsExporter {

    private static final Logger log = Logger.getLogger(OtlpRpsExporter.class);

    private final URI otlpMetricsUri; // e.g. http://host:4318/v1/metrics
    private final RequestsPerSecond rps;
    private final ApplicationInfo app;
    private final String environmentTagKey;
    private final String environmentTagValue;

    private final HttpClient httpClient;

    public OtlpRpsExporter(URI otlpMetricsUri, RequestsPerSecond rps, ApplicationInfo app, String environmentVarName) {
        this.otlpMetricsUri = otlpMetricsUri;
        this.rps = rps;
        this.app = app;
        this.environmentTagKey = environmentVarName;
        this.environmentTagValue = environmentVarName == null ? null : System.getenv(environmentVarName);
        this.httpClient = HttpClient.newHttpClient(); // no basic auth or mTLS supported as of now
    }

    public void send() {
        try {
            int currentRps = rps.rps();
            if (currentRps < 0) {
                log.info("RPS not available, skipping OTLP send.");
                return;
            }

            String body = buildOtlpJson(currentRps, calculateCurrentTimeNanos());
            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(otlpMetricsUri)
                    .timeout(HttpUtil.HTTP_REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(publisher)
                    .build();

            HttpUtil.sendRequest(httpClient, request);

        } catch (Throwable t) {
            log.error("Failed to send OTLP metrics", t);
        }
    }

    private static long calculateCurrentTimeNanos() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000_000 + now.getNano();
    }

    String buildOtlpJson(int rpsValue, long timeUnixNano) {
        StringBuilder resourceAttrs = new StringBuilder();
        // application, space, org, instance number
        appendAttr(resourceAttrs, "cf_application_name", app.getApplicationName());
        appendAttr(resourceAttrs, "cf_space_name", app.getSpaceName());
        appendAttr(resourceAttrs, "cf_organization_name", app.getOrganizationName());
        appendAttr(resourceAttrs, "cf_instance_index", String.valueOf(app.getIndex())); // should this be of type int?
        if (environmentTagKey != null && environmentTagValue != null) {
            appendAttr(resourceAttrs, "environment", environmentTagValue);
        }
        // Remove trailing comma if present
        String attrsJson = resourceAttrs.length() > 0 ? resourceAttrs.substring(0, resourceAttrs.length() - 1) : "";

        // Build minimal OTLP JSON (metrics/v1) with a Gauge datapoint
        String metricName = "custom_throughput";
        return "{\n" +
            "  \"resourceMetrics\": [ {\n" +
            "    \"resource\": { \"attributes\": [" + attrsJson + "] },\n" +
            "    \"scopeMetrics\": [ {\n" +
            "      \"metrics\": [ {\n" +
                "        \"name\": \"" + metricName + "\",\n" +
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
