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

class ApplicationInfoTest {

    private static final String VCAP_APPLICATION = "{\"cf_api\":\"https://api.example.com\",\"limits\":{\"fds\":16384,\"mem\":2048,\"disk\":512},\"application_name\":\"my-app-demo\",\"application_uris\":[\"my-app-demo.apps.example.com\"],\"name\":\"my-app-demo\",\"space_name\":\"my-space\",\"space_id\":\"0dbe1d3d-space-id\",\"organization_id\":\"5124f9b4-org-id\",\"organization_name\":\"example-org\",\"uris\":[\"my-app-demo.apps.example.com\"],\"process_id\":\"6f452e74-application-id\",\"process_type\":\"web\",\"application_id\":\"6f452e74-application-id\",\"version\":\"7b6469c4-version\",\"application_version\":\"7b6469c4-app-version\"}";
    private static final String CF_INSTANCE_INDEX = "0";

    @Test
    void extractApplicationInfo() {
        ApplicationInfo applicationInfo = ApplicationInfo.extractApplicationInfo(VCAP_APPLICATION, CF_INSTANCE_INDEX);
        assertEquals("6f452e74-application-id", applicationInfo.getApplicationId());
        assertEquals(0, applicationInfo.getIndex());
        assertEquals("my-app-demo", applicationInfo.getApplicationName());
        assertEquals("my-space", applicationInfo.getSpaceName());
        assertEquals("example-org", applicationInfo.getOrganizationName());
    }
}