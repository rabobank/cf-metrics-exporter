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
package io.github.rabobank.cme.rps;

import io.github.rabobank.cme.Logger;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of RequestsPerSecond that retrieves the request count from a Tomcat MBean.
 * It calculates the requests per second based on the difference in request count over time.
 *
 * In Spring Boot application configuration add `server.tomcat.mbeanregistry.enabled=true` to enable the MBean registry.
 */
public class TomcatMBeanRPS implements RequestsPerSecond {

    private static final Logger log = Logger.getLogger(TomcatMBeanRPS.class);

    private static final MBeanServer PLATFORM_M_BEAN_SERVER = ManagementFactory.getPlatformMBeanServer();
    private static final ObjectName OBJECT_NAME = initObjectName();

    private final AtomicInteger lastRequestCount = new AtomicInteger(0);
    private final AtomicLong lastTimestampMillis = new AtomicLong(System.currentTimeMillis());
    private final AtomicReference<ObjectName> globalRequestProcessorObjectName = new AtomicReference<>(null);

    private static ObjectInstance findTomcatGlobalRequestProcessor() {
        Set<ObjectInstance> mBeans = PLATFORM_M_BEAN_SERVER.queryMBeans(OBJECT_NAME, null);
        if (mBeans.isEmpty()) {
            log.error("No MBean found for ObjectName: %s", OBJECT_NAME);
            return null;
        }
        if (mBeans.size() > 1) {
            log.error("Multiple MBeans found for ObjectName: %s, using only first found.", OBJECT_NAME);
        }
        return mBeans.iterator().next();
    }

    @Override
    public int rps() {
            try {
                if (globalRequestProcessorObjectName.get() == null) {
                    ObjectInstance instance = TomcatMBeanRPS.findTomcatGlobalRequestProcessor();
                    if (instance == null) return -1;
                    ObjectName objectName = instance.getObjectName();
                    TomcatMBeanRPS.log.info("Found MBean: %s", objectName);
                    globalRequestProcessorObjectName.set(objectName);
                }
                Integer requestCount = (Integer) PLATFORM_M_BEAN_SERVER.getAttribute(globalRequestProcessorObjectName.get(), "requestCount");
                long now = System.currentTimeMillis();
                int secondsSinceLastCall = (int) ((now - lastTimestampMillis.getAndSet(now)) / 1000);
                return (requestCount - lastRequestCount.getAndSet(requestCount)) / secondsSinceLastCall;
            } catch (Exception e) {
                TomcatMBeanRPS.log.error("Failed to retrieve request rate via JMX: %s", e.getMessage());
                return -1;
            }
    }

    private static ObjectName initObjectName() {
        try {
            // example name: "Tomcat:type=GlobalRequestProcessor,name=\"http-nio-8080\""
            return new ObjectName("Tomcat:type=GlobalRequestProcessor,name=\"*\"");
        } catch (MalformedObjectNameException e) {
            log.error("Failed to create ObjectName: %s", e.getMessage());
            return null;
        }
    }
}
