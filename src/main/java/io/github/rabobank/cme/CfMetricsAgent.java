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
import io.github.rabobank.cme.domain.AutoScalerInfo;
import io.github.rabobank.cme.domain.MtlsInfo;
import io.github.rabobank.cme.otlp.OtlpRpsExporter;
import io.github.rabobank.cme.rps.*;
import io.github.rabobank.cme.util.CertAndKeyProcessing;

import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static io.github.rabobank.cme.domain.ApplicationInfo.extractApplicationInfo;
import static io.github.rabobank.cme.domain.AutoScalerInfo.extractMetricsServerInfo;
import static io.github.rabobank.cme.domain.MtlsInfo.INVALID_MTLS_INFO;

public class CfMetricsAgent {

    private static final Logger log = Logger.getLogger(CfMetricsAgent.class);

    public static void main(String[] args) {

        Arguments arguments = Arguments.parseArgs(args);

        enableLogging(arguments);

        try {
            CfMetricsAgent cfMetricsExporter = new CfMetricsAgent();
            cfMetricsExporter.start(arguments);

            // Keep the main thread alive
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Main thread interrupted: %s", e);
            }
        } catch (Exception e) {
            log.error("Error starting CfMetricsAgent.", e);
        }
    }

    private static void enableLogging(Arguments arguments) {
        if (arguments.isDebug()) {
            Logger.enableDebug();
        }

        if (arguments.isTrace()) {
            Logger.enableTrace();
        }
    }

    public void start(Arguments args) {

        enableLogging(args);

        String vcapApplicationJson = System.getenv("VCAP_APPLICATION");
        String vcapServicesJson = System.getenv("VCAP_SERVICES");
        String cfInstanceIndex = System.getenv("CF_INSTANCE_INDEX");

        if (vcapApplicationJson == null || vcapServicesJson == null || cfInstanceIndex == null) {
            log.error("VCAP_APPLICATION,VCAP_SERVICES or CF_INSTANCE_INDEX is not available in env variables, agent cannot continue.");
            return;
        }

        ApplicationInfo applicationInfo = extractApplicationInfo(vcapApplicationJson, cfInstanceIndex);
        AutoScalerInfo autoScalerInfo = extractMetricsServerInfo(vcapServicesJson);

        if (!(autoScalerInfo.isBasicAuthConfigured() || autoScalerInfo.isMtlsAuthConfigured())) {
            log.error("Missing auto-scaler connection information for basic-auth and mtls, CfMetricsAgent cannot start.");
            return;
        }

        // can return invalid mtlsInfo when not all info is available
        MtlsInfo mtlsInfo = autoScalerInfo.isBasicAuthConfigured() ? INVALID_MTLS_INFO : CertAndKeyProcessing.initializeMtlsInfo();

        if (!autoScalerInfo.isBasicAuthConfigured() && (mtlsInfo == null || !mtlsInfo.isValid())) {
            log.error("Autoscaler basic auth not available and mTLS settings are incomplete, CfMetricsAgent cannot start.");
            return;
        }

        log.info("Start CfMetricsAgent with arguments: %s", args);
        
        // Create a single thread scheduler that runs every 10 seconds
        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r);
                thread.setName("cf-metrics-scheduler");
                thread.setDaemon(true);
                return thread;
            });

        RequestsPerSecond requestsPerSecond = null;

        switch (args.type()) {
            case TOMCAT_MBEAN:
                requestsPerSecond = new TomcatMBeanRPS();
                break;
            case RANDOM:
                requestsPerSecond = new RandomRPS(10, 100, 100);
                break;
            case SPRING_REQUEST:
                requestsPerSecond = new SpringRequestRPS();
                break;
        }

        CustomMetricsSender customMetricsSender;
        try {
            customMetricsSender = new CustomMetricsSender(requestsPerSecond, autoScalerInfo, applicationInfo, mtlsInfo);
        } catch (Exception e) {
            log.error("Cannot create CustomMetricsSender, not starting CfMetricsAgent.", e);
            return;
        }

        // Schedule App Autoscaler custom metrics sender
        ScheduledFuture<?> scheduledAutoscaler = scheduler.scheduleAtFixedRate(customMetricsSender::send, 30, args.intervalSeconds(), TimeUnit.SECONDS);

        // If OTLP metrics endpoint env var is present, schedule OTLP exporter as well
        String otlpUrl = System.getenv("MANAGEMENT_OTLP_METRICS_EXPORT_URL");
        URI otlpMetricsUri = parseUri(otlpUrl);

        if (otlpMetricsUri != null) {
            log.info("OTLP metrics export enabled to %s", otlpUrl);
            OtlpRpsExporter otlp = new OtlpRpsExporter(otlpMetricsUri, requestsPerSecond, applicationInfo, args.environmentVarName());
            ScheduledFuture<?> scheduledOtlp = scheduler.scheduleAtFixedRate(otlp::send, 30, args.intervalSeconds(), TimeUnit.SECONDS);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown hook triggered, cancelling scheduled tasks.");
                scheduledAutoscaler.cancel(true);
                scheduledOtlp.cancel(true);
            }));
        } else {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown hook triggered, cancelling scheduled task.");
                scheduledAutoscaler.cancel(true);
            }));
        }
    }

    /**
     * @param otlpUrl the url in String format
     * @return null if the given URL is invalid
     */
    private static URI parseUri(String otlpUrl) {
        if (otlpUrl == null || otlpUrl.isEmpty()) {
            return null;
        }

        final URI otlpMetricsUri;
        try {
            otlpMetricsUri = new URI(otlpUrl);
        } catch (URISyntaxException e) {
            log.error("Invalid OTLP metrics endpoint URL: %s, OTLP metric sending disabled.", otlpUrl);
            return null;
        }
        return otlpMetricsUri;
    }

    public static void premain(String args, Instrumentation instrumentation){
        log.info("premain: %s", (args == null ? "<no args>" : args));

        try {
            CfMetricsAgent cfMetricsExporter = new CfMetricsAgent();
            String[] argsArray = splitAgentArgs(args);

            Arguments parsedArguments = Arguments.parseArgs(argsArray);
            if (parsedArguments.type() == RequestsPerSecondType.SPRING_REQUEST) {
                log.info("Spring instrumentation enabled");
                SpringRequestRPS.initializeSpringInstrumentation(instrumentation);
            } else {
                log.info("Spring instrumentation not enabled");
            }
            cfMetricsExporter.start(parsedArguments);
        } catch (Exception e) {
            log.error("Error starting CfMetricsAgent.", e);
        }
    }

    static String[] splitAgentArgs(String args) {
        return args == null ? new String[]{} : args.split("[=,]"); //NOPMD - suppressed AvoidImplicitlyRecompilingRegex - one time call
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        log.info("agentmain: calling premain");
        premain(args, instrumentation);
    }
}