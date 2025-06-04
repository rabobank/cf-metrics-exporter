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

public class ApplicationInfo {

    private final String applicationId;
    private final int index;
    public ApplicationInfo(String applicationId, int index) {
        this.applicationId = applicationId;
        this.index = index;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public int getIndex() {
        return index;
    }

    public static ApplicationInfo extractApplicationInfo(String vcapApplicationJson, String cfInstanceIndex) {
        String applicationId = findApplicationId(vcapApplicationJson);
        return new ApplicationInfo(applicationId, Integer.valueOf(cfInstanceIndex));
    }

    public static final Pattern APPLICATION_ID_PATTERN = Pattern.compile("\"application_id\"\\s*:\\s*\"([^\"]+)\"");

    private static String findApplicationId(String vcapApplicationJson) {
        String applicationId;
        Matcher matcher = APPLICATION_ID_PATTERN.matcher(vcapApplicationJson);
        if (matcher.find()) {
            applicationId = matcher.group(1);
        } else {
            throw new IllegalArgumentException("Application ID not found in VCAP_APPLICATION");
        }
        return applicationId;
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
