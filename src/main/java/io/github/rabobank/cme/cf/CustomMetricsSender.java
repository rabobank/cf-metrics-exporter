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
package io.github.rabobank.cme.cf;

import io.github.rabobank.cme.CfMetricsAgentException;
import io.github.rabobank.cme.Logger;
import io.github.rabobank.cme.MetricEmitter;
import io.github.rabobank.cme.domain.ApplicationInfo;
import io.github.rabobank.cme.domain.AutoScalerInfo;
import io.github.rabobank.cme.domain.MtlsInfo;
import io.github.rabobank.cme.rps.RequestsPerSecond;
import io.github.rabobank.cme.util.HttpUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

public class CustomMetricsSender implements MetricEmitter {

    private static final Logger log = Logger.getLogger(CustomMetricsSender.class);

    private final ApplicationInfo applicationInfo;

    private final HttpClient httpClient;

    private final String url;
    private URI metricsUri;
    private String basicAuthHeader;

    public CustomMetricsSender(AutoScalerInfo autoScalerInfo, ApplicationInfo applicationInfo) throws CfMetricsAgentException {
        this(autoScalerInfo, applicationInfo, null);
    }

    public CustomMetricsSender(AutoScalerInfo autoScalerInfo, ApplicationInfo applicationInfo, MtlsInfo mtlsInfo) throws CfMetricsAgentException {

        if (mtlsInfo == null && !autoScalerInfo.isBasicAuthConfigured()) {
            throw new CfMetricsAgentException("No basic auth and no mTLS settings found.");
        }

        if (!autoScalerInfo.isBasicAuthConfigured()) {
            log.info("No basic auth settings found, will use mTLS instead.");
        }

        this.applicationInfo = applicationInfo;
        boolean isMtlsEnabled = !autoScalerInfo.isBasicAuthConfigured() && autoScalerInfo.isMtlsAuthConfigured();
        this.url = isMtlsEnabled ? autoScalerInfo.getUrlMtls() : autoScalerInfo.getUrl();
        this.httpClient = isMtlsEnabled ? HttpUtil.createHttpClientMtls(mtlsInfo) : HttpUtil.createHttpClient();
        this.metricsUri = URI.create(url + "/v1/apps/" + applicationInfo.getApplicationId() + "/metrics");
        this.basicAuthHeader = HttpUtil.encodeBasicAuthHeader(autoScalerInfo.getUsername(), autoScalerInfo.getPassword());
    }

    @Override
    public void emitMetric(String metricName, int rps) {
        try {
            log.debug("Sending RPS: %d", rps);

            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofString(
                    createPayload(rps, applicationInfo.getIndex(), metricName), StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(HttpUtil.HTTP_REQUEST_TIMEOUT)
                    .uri(metricsUri)
                    .header("Content-Type", "application/json")
                    .header("Authorization", basicAuthHeader)
                    .POST(publisher)
                    .build();

            HttpUtil.sendRequest(httpClient, request);

        } catch (Throwable e) {
            log.error("error sending RPS", e);
        }
    }

    @Override
    public String name() {
        return "CustomMetricsSender";
    }

    private String createPayload(int value, int index, String name) {
        return "{\n" +
                "    \"instance_index\": " + index + ",\n" +
                "    \"metrics\": [\n" +
                "      {\n" +
                "        \"name\": \"" + name + "\",\n" +
                "        \"value\": " + value + ",\n" +
                "        \"unit\": \"rps\"\n" +
                "      }\n" +
                "    ]\n" +
                "  }";
    }

}