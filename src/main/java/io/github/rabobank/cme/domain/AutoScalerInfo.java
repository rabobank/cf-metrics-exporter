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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoScalerInfo {
    private final String password;
    private final String url;
    private final String username;

    public AutoScalerInfo(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public String getPassword() {
        return password;
    }

    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    public static AutoScalerInfo extractMetricsServerInfo(String vcapServicesJson) {
        String url = findUrl(vcapServicesJson);
        String username = findUsername(vcapServicesJson);
        String password = findPassword(vcapServicesJson);
        
        return new AutoScalerInfo(url, username, password);
    }

    private static String findCustomMetricValue(String vcapServices, String key) {
        Pattern pattern = Pattern.compile("\"custom_metrics\"\\s*:\\s*\\{[^}]*\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(vcapServices);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            throw new IllegalStateException("'" + key + "' not found in 'custom_metrics' within VCAP_SERVICES");
        }
    }

    private static String findPassword(String vcapServices) {
        return findCustomMetricValue(vcapServices, "password");
    }

    private static String findUsername(String vcapServices) {
        return findCustomMetricValue(vcapServices, "username");
    }

    private static String findUrl(String vcapServices) {
        return findCustomMetricValue(vcapServices, "url");
    }

    @Override
    public int hashCode() {
        throw new UnsupportedOperationException("hashCode() not implemented");
    }

    @Override
    public boolean equals(Object obj) {
        throw new UnsupportedOperationException("equals() not implemented");
    }
}
