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
    private final String applicationName;
    private final String spaceName;
    private final String organizationName;
    public ApplicationInfo(String applicationId, int index, String applicationName, String spaceName, String organizationName) {
        this.applicationId = applicationId;
        this.index = index;
        this.applicationName = applicationName;
        this.spaceName = spaceName;
        this.organizationName = organizationName;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public int getIndex() {
        return index;
    }

    public String getApplicationName() { return applicationName; }
    public String getSpaceName() { return spaceName; }
    public String getOrganizationName() { return organizationName; }

    public static ApplicationInfo extractApplicationInfo(String vcapApplicationJson, String cfInstanceIndex) {
        String applicationId = findField(vcapApplicationJson, APPLICATION_ID_PATTERN, "Application ID");
        String applicationName = findField(vcapApplicationJson, APPLICATION_NAME_PATTERN, "Application Name");
        String spaceName = findField(vcapApplicationJson, SPACE_NAME_PATTERN, "Space Name");
        String organizationName = findField(vcapApplicationJson, ORG_NAME_PATTERN, "Organization Name");
        return new ApplicationInfo(applicationId, Integer.valueOf(cfInstanceIndex), applicationName, spaceName, organizationName);
    }

    public static final Pattern APPLICATION_ID_PATTERN = Pattern.compile("\"application_id\"\\s*:\\s*\"([^\"]+)\"");
    public static final Pattern APPLICATION_NAME_PATTERN = Pattern.compile("\"application_name\"\\s*:\\s*\"([^\"]+)\"");
    public static final Pattern SPACE_NAME_PATTERN = Pattern.compile("\"space_name\"\\s*:\\s*\"([^\"]+)\"");
    public static final Pattern ORG_NAME_PATTERN = Pattern.compile("\"organization_name\"\\s*:\\s*\"([^\"]+)\"");

    private static String findField(String json, Pattern pattern, String fieldName) {
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException(fieldName + " not found in VCAP_APPLICATION");
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
