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

import io.github.rabobank.cme.cf.CustomMetricsSender;
import io.github.rabobank.cme.domain.ApplicationInfo;
import io.github.rabobank.cme.domain.AutoScalerInfo;
import io.github.rabobank.cme.domain.MtlsInfo;
import io.github.rabobank.cme.otlp.OtlpRpsExporter;
import io.github.rabobank.cme.rps.*;
import io.github.rabobank.cme.util.CertAndKeyProcessing;

import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static io.github.rabobank.cme.domain.ApplicationInfo.extractApplicationInfo;
import static io.github.rabobank.cme.domain.AutoScalerInfo.AutoScalerAuthType.BASIC;
import static io.github.rabobank.cme.domain.AutoScalerInfo.AutoScalerAuthType.MTLS;
import static io.github.rabobank.cme.domain.AutoScalerInfo.extractMetricsServerInfo;
import static io.github.rabobank.cme.domain.MtlsInfo.INVALID_MTLS_INFO;

public class CfMetricsAgent {

    public static final String CUSTOM_THROUGHPUT_METRIC_NAME = "custom_throughput";

    private static final Logger log = Logger.getLogger(CfMetricsAgent.class);
    private static final Initializer NOP_INITIALIZER = () -> log.info("No special initialization required.");

    public static void main(String[] args) {

        Arguments arguments = Arguments.parseArgs(args);

        enableLogging(arguments);

        try {
            CfMetricsAgent cfMetricsExporter = new CfMetricsAgent();
            cfMetricsExporter.start(arguments, NOP_INITIALIZER);

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

    public void start(Arguments args, Initializer initializer) {

        enableLogging(args);

        if (args.isDisableAgent()) {
            log.info("Agent disabled via command line argument.");
            return;
        }

        log.info("arguments: %s", args);

        String vcapApplicationJson = System.getenv("VCAP_APPLICATION");
        String vcapServicesJson = System.getenv("VCAP_SERVICES");
        String cfInstanceIndex = System.getenv("CF_INSTANCE_INDEX");

        if (vcapApplicationJson == null || vcapServicesJson == null || cfInstanceIndex == null) {
            log.error("VCAP_APPLICATION,VCAP_SERVICES or CF_INSTANCE_INDEX is not available in env variables: CfMetricsAgent will not be activated.");
            return;
        }

        ApplicationInfo applicationInfo = extractApplicationInfo(vcapApplicationJson, cfInstanceIndex);
        AutoScalerInfo autoScalerInfo = extractMetricsServerInfo(vcapServicesJson);

        // can return invalid mtlsInfo when not all info is available
        MtlsInfo mtlsInfo = autoScalerInfo.getAuthType() == MTLS
                ? CertAndKeyProcessing.initializeMtlsInfo()
                : INVALID_MTLS_INFO;

        boolean isAutoscalerMtlsOk = autoScalerInfo.getAuthType() == MTLS && mtlsInfo.isValid();
        boolean isAutoScalerAvailable = autoScalerInfo.getAuthType() == BASIC || isAutoscalerMtlsOk;

        // If OTLP metrics endpoint env var is present, schedule OTLP exporter as well
        String otlpUrl = System.getenv("MANAGEMENT_OTLP_METRICS_EXPORT_URL");
        URI otlpMetricsUri = parseUri(otlpUrl);
        boolean isOtlpEnabled = otlpMetricsUri != null;

        List<MetricEmitter> emitters = new ArrayList<>();
        if (isAutoScalerAvailable) {
            createAndAddCustomMetricsSender(autoScalerInfo, applicationInfo, mtlsInfo, emitters);
        }
        else {
            log.info("Auto-scaler basic-auth or mTLS configuration not found, no metrics to auto-scaler.");
        }
        if (isOtlpEnabled) {
            createAndAddOtlpExporter(args.environmentVarName(), applicationInfo, otlpMetricsUri, emitters);
        }
        else {
            log.info("OTLP endpoint not found, no metrics to OTLP endpoint.");
        }
        if (args.isEnableLogEmitter()) {
            createAndAddLogEmitter(emitters);
        }
        else {
            log.info("Log emitter not enabled, no metrics to standard out log.");
        }

        if (emitters.isEmpty()) {
            log.info("No metrics emitters configured, will not initialize agent.");
        }
        else {
            initializer.initialize();

            // Create a single thread scheduler to send metrics
            @SuppressWarnings("PMD.CloseResource") // no need to close this, see shutdown hook
            ScheduledExecutorService scheduler =
                    Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread thread = new Thread(r);
                        thread.setName("cf-metrics-scheduler");
                        thread.setDaemon(true);
                        return thread;
                    });

            RequestsPerSecond requestsPerSecond = createRequestsPerSecond(args.type());

            MetricsProcessor metricsProcessor = new MetricsProcessor(requestsPerSecond, emitters);

            // Schedule sending of metrics
            ScheduledFuture<?> scheduledAutoscaler = scheduler.scheduleAtFixedRate(metricsProcessor::tick, 30, args.intervalSeconds(), TimeUnit.SECONDS);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown hook triggered, cancelling scheduled task.");
                scheduledAutoscaler.cancel(true);
                scheduler.shutdownNow();
                log.info("Shutdown complete.");
            }));
        }
    }

    private void createAndAddLogEmitter(List<MetricEmitter> emitters) {
        MetricEmitter emitter = new MetricEmitter() {
            @Override
            public void emitMetric(String metricName, int metricValue) {
                log.info("Metric: %s, value: %d", metricName, metricValue);
            }

            @Override
            public String name() {
                return "log-emitter";
            }
        };
        emitters.add(emitter);
    }

    private static RequestsPerSecond createRequestsPerSecond(RequestsPerSecondType type) {
        switch (type) {
            case TOMCAT_MBEAN:
                return new TomcatMBeanRPS();
            case RANDOM:
                return new RandomRPS(10, 100, 100);
            case SPRING_REQUEST:
                return new SpringRequestRPS();
            default:
                throw new IllegalArgumentException("Unsupported RequestsPerSecondType: " + type);
        }
    }

    private static void createAndAddOtlpExporter(String environmentVarName, ApplicationInfo applicationInfo, URI otlpMetricsUri, List<MetricEmitter> emitters) {
        try {
            OtlpRpsExporter otlpExporter = new OtlpRpsExporter(otlpMetricsUri, applicationInfo, environmentVarName);
            emitters.add(otlpExporter);
            log.info("OTLP metrics export enabled to %s", otlpMetricsUri);
        } catch (Exception e) {
            log.error("Cannot create OTLP exporter, will not emit metrics to OTLP endpoint.", e);
        }
    }

    private static void createAndAddCustomMetricsSender(AutoScalerInfo autoScalerInfo, ApplicationInfo applicationInfo, MtlsInfo mtlsInfo, List<MetricEmitter> emitters) {
        CustomMetricsSender customMetricsSender;
        try {
            customMetricsSender = new CustomMetricsSender(autoScalerInfo, applicationInfo, mtlsInfo);
            emitters.add(customMetricsSender);
            log.info("Custom metrics sender enabled to %s", autoScalerInfo.getAuthType() == BASIC ? autoScalerInfo.getUrl() : autoScalerInfo.getUrlMtls());
        } catch (Exception e) {
            log.error("Cannot create CustomMetricsSender, will not emit metrics to cloud foundry custom metrics endpoint.", e);
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
        log.debug("premain: %s", args == null ? "<no args>" : args);

        try {
            CfMetricsAgent cfMetricsExporter = new CfMetricsAgent();
            String[] argsArray = splitAgentArgs(args);

            Arguments parsedArguments = Arguments.parseArgs(argsArray);

            Initializer initializer = createInitializer(parsedArguments.type(), instrumentation);

            cfMetricsExporter.start(parsedArguments, initializer);

        } catch (Exception e) {
            log.error("Error starting CfMetricsAgent.", e);
        }
    }

    private static Initializer createInitializer(RequestsPerSecondType type, Instrumentation instrumentation) {
        if (type == RequestsPerSecondType.SPRING_REQUEST) {
            return () -> {
                log.info("Spring instrumentation enabled");
                SpringRequestRPS.initializeSpringInstrumentation(instrumentation);
            };
        }
        return () -> log.info("Spring instrumentation not enabled");
    }

    @SuppressWarnings("PMD.AvoidImplicitlyRecompilingRegex") // is one time call only
    static String[] splitAgentArgs(String args) {
        return args == null ? new String[]{} : args.split("[=,]");
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        log.info("agentmain: calling premain");
        premain(args, instrumentation);
    }
}