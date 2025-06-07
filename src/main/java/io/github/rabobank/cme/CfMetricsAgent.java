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
import io.github.rabobank.cme.rps.*;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import static io.github.rabobank.cme.domain.ApplicationInfo.extractApplicationInfo;
import static io.github.rabobank.cme.domain.AutoScalerInfo.extractMetricsServerInfo;

public class CfMetricsAgent {

    private static final Logger log = Logger.getLogger(CfMetricsAgent.class);

    public static void main(String[] args) {

        Arguments arguments = Arguments.parseArgs(args);

        if (arguments.isDebug()) {
            Logger.enableDebug();
        }

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

    public void start(Arguments args) {

        if (args.isDebug()) {
            Logger.enableDebug();
        }

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
            log.error("Missing auto-scaler connection information, CfMetricsAgent cannot start.");
            return;
        }

        String cfInstanceCert = System.getenv("CF_INSTANCE_CERT");
        String cfInstanceKey = System.getenv("CF_INSTANCE_KEY");
        String cfSystemCertPath = System.getenv("CF_SYSTEM_CERT_PATH");

        if (cfSystemCertPath == null) {
            log.error("CF_SYSTEM_CERT_PATH is not available in env variables.");
            return;
        }

        List<Path> crtFiles = listAllCrtFiles(cfSystemCertPath);

        if (crtFiles.isEmpty()) {
            log.error("No CA certificates (*.crt files) found in %s, CfMetricsAgent cannot start.", cfSystemCertPath);
            return;
        }

        MtlsInfo mtlsInfo = MtlsInfo.extractMtlsInfo(Path.of(cfInstanceKey), Path.of(cfInstanceCert), crtFiles);

        if (!autoScalerInfo.isBasicAuthConfigured() && !mtlsInfo.isValid()) {
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

        scheduler.scheduleAtFixedRate(customMetricsSender::send, 0, args.intervalSeconds(), java.util.concurrent.TimeUnit.SECONDS);

    }

    private List<Path> listAllCrtFiles(String certPath) {
        if (certPath == null) {
            log.error("cert path is not available, no ca files found");
            return Collections.emptyList();
        }
        Path directory = Path.of(certPath);
        if (!Files.exists(directory)) {
            log.error("Certificate directory does not exist: %s", certPath);
            return Collections.emptyList();
        }
        if (!Files.isDirectory(directory)) {
            log.error("Certificate path is not a directory: %s", certPath);
            return Collections.emptyList();
        }

        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.toString().endsWith(".crt"))
                    .collect(Collectors.toUnmodifiableList());
        } catch (IOException e) {
            log.error("Cannot list certificate files in directory: %s", e.getMessage());
            return Collections.emptyList();
        }
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