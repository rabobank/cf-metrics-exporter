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
package io.github.rabobank.cme.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoScalerInfoTest {

    private static final String VCAP_SERVICES= "{\"user-provided\":[{\"label\":\"user-provided\",\"name\":\"tool-managed\",\"tags\":[],\"instance_guid\":\"d7b845d1-instance-guid\",\"instance_name\":\"tool-managed\",\"binding_guid\":\"3dde0c14-binding-guid\",\"binding_name\":null,\"credentials\":{\"apitoken\":\"TVJ7VW.api.token\",\"apiurl\":\"https://toolmanaged.example.com/e/42ebaabb-environment-id/api\",\"environmentid\":\"42ebaabb-environment-id\",\"skiperrors\":\"true\",\"tag:foundation\":\"f01\"},\"syslog_drain_url\":null,\"volume_mounts\":[]},{\"label\":\"user-provided\",\"name\":\"metrics-endpoint-actuator-prometheus\",\"tags\":[],\"instance_guid\":\"0aed0c1e-instance-guid\",\"instance_name\":\"metrics-endpoint-actuator-prometheus\",\"binding_guid\":\"4643df3e-binding-guid\",\"binding_name\":null,\"credentials\":{},\"syslog_drain_url\":\"metrics-endpoint:///actuator/prometheus\",\"volume_mounts\":[]}],\"app-autoscaler\":[{\"label\":\"app-autoscaler\",\"provider\":null,\"plan\":\"standard\",\"name\":\"my-app-autoscaler\",\"tags\":[\"app-autoscaler\"],\"instance_guid\":\"dc67782d-instance-guid\",\"instance_name\":\"my-app-autoscaler\",\"binding_guid\":\"39edecbe-binding-guid\",\"binding_name\":null,\"credentials\":{\"custom_metrics\":{\"username\":\"ae9978b5-username\",\"password\":\"341804fd-password\",\"url\":\"https://app-autoscalermetrics.example.com\",\"mtls_url\":\"https://app-autoscaler-metricsforwarder-mtls.example.com\"}},\"syslog_drain_url\":null,\"volume_mounts\":[]}]}";

    @Test
    void extractMetricsServerInfo() {
        AutoScalerInfo autoScalerInfo = AutoScalerInfo.extractMetricsServerInfo(VCAP_SERVICES);
        assertEquals("341804fd-password", autoScalerInfo.getPassword());
        assertEquals("https://app-autoscalermetrics.example.com", autoScalerInfo.getUrl());
        assertEquals("ae9978b5-username", autoScalerInfo.getUsername());
    }
}