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
import java.util.regex.Pattern;

import static io.github.rabobank.cme.domain.ApplicationInfo.extractApplicationInfo;
import static io.github.rabobank.cme.domain.AutoScalerInfo.AutoScalerAuthType.BASIC;
import static io.github.rabobank.cme.domain.AutoScalerInfo.AutoScalerAuthType.MTLS;
import static io.github.rabobank.cme.domain.AutoScalerInfo.extractMetricsServerInfo;
import static io.github.rabobank.cme.domain.MtlsInfo.INVALID_MTLS_INFO;

public class CfMetricsAgent {

    public static final String CUSTOM_THROUGHPUT_METRIC_NAME = "custom_throughput";

    private static final Pattern AGENT_ARGS_PATTERN = Pattern.compile("[=,]");

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

        boolean cfEnvAvailable = vcapApplicationJson != null && vcapServicesJson != null && cfInstanceIndex != null;

        List<MetricEmitter> emitters = new ArrayList<>();
        ApplicationInfo applicationInfo;
        AutoScalerInfo autoScalerInfo;

        if (cfEnvAvailable) {
            applicationInfo = extractApplicationInfo(vcapApplicationJson, cfInstanceIndex);
            autoScalerInfo = extractMetricsServerInfo(vcapServicesJson);

            MtlsInfo mtlsInfo = getMtlsInfo(autoScalerInfo);

            boolean isAutoScalerAvailable = isAutoScalerAvailable(autoScalerInfo, mtlsInfo);

            URI otlpMetricsUri = fetchOtlpMetricsUriFromEnv();

            if (isAutoScalerAvailable) {
                createAndAddCustomMetricsSender(autoScalerInfo, applicationInfo, mtlsInfo, emitters);
            } else {
                log.info("Auto-scaler basic-auth or mTLS configuration not found, no metrics to auto-scaler.");
            }

            if (otlpMetricsUri != null) {
                createAndAddOtlpExporter(args.environmentVarName(), applicationInfo, otlpMetricsUri, emitters);
            } else {
                log.info("OTLP endpoint not found, no metrics to OTLP endpoint.");
            }
        } else if (args.isEnableLogEmitter()) {
            log.info("CF environment variables not found; activating log emitter only mode.");
        } else {
            log.error("VCAP_APPLICATION,VCAP_SERVICES or CF_INSTANCE_INDEX is not available in env variables: CfMetricsAgent will not be activated.");
            return;
        }

        // Optional log emitter regardless of CF env availability
        if (args.isEnableLogEmitter()) {
            createAndAddLogEmitter(emitters);
        } else {
            log.info("Log emitter not enabled, no metrics to standard out log.");
        }

        if (emitters.isEmpty()) {
            log.info("No metrics emitters configured, will not initialize agent.");
            return;
        }

        initializer.initialize();

        // Create a single thread scheduler to send metrics
        @SuppressWarnings({"PMD.CloseResource", "java:S2095"}) // no need to close this, see shutdown hook
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

    private MtlsInfo getMtlsInfo(AutoScalerInfo autoScalerInfo) {
        if (autoScalerInfo.getAuthType() == MTLS) {
            return CertAndKeyProcessing.initializeMtlsInfo();
        }
        return INVALID_MTLS_INFO;
    }

    private boolean isAutoScalerAvailable(AutoScalerInfo autoScalerInfo, MtlsInfo mtlsInfo) {
        if (autoScalerInfo.getAuthType() == BASIC) {
            return true;
        }
        return autoScalerInfo.getAuthType() == MTLS && mtlsInfo.isValid();
    }

    private URI fetchOtlpMetricsUriFromEnv() {
        String otlpUrl = System.getenv("MANAGEMENT_OTLP_METRICS_EXPORT_URL");
        return parseUriFromUrl(otlpUrl, "Invalid OTLP metrics endpoint URL: %s, OTLP metric sending disabled.");
    }

    private void createAndAddLogEmitter(List<MetricEmitter> emitters) {
        MetricEmitter emitter = new MetricEmitter() {
            @Override
            public void emitMetric(String metricName, int metricValue) {
                log.info("metric: %s, value: %d", metricName, metricValue);
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
     * @param inputUrl the url in String format
     * @param errorMessageWithInputUrlReplacement an error message with a placeholder for the inputUrl, e.g. "Invalid URL: %s"
     * @return null if the given URL is invalid
     */
    private static URI parseUriFromUrl(String inputUrl, String errorMessageWithInputUrlReplacement) {
        if (inputUrl == null || inputUrl.isEmpty()) {
            return null;
        }

        final URI parsedUri;
        try {
            parsedUri = new URI(inputUrl);
        } catch (URISyntaxException e) {
            log.error(errorMessageWithInputUrlReplacement, inputUrl);
            return null;
        }
        return parsedUri;
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

    static String[] splitAgentArgs(String args) {
        return args == null ? new String[]{} : AGENT_ARGS_PATTERN.split(args);
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

    public static void agentmain(String args, Instrumentation instrumentation) {
        log.info("agentmain: calling premain");
        premain(args, instrumentation);
    }
}