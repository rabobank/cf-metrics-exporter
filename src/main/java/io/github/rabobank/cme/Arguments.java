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

import io.github.rabobank.cme.rps.RequestsPerSecondType;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

public class Arguments {

    private String metricsEndpoint;
    private boolean debug = false;
    private boolean trace = false;
    private int intervalSeconds = 10;
    private RequestsPerSecondType rpsType = RequestsPerSecondType.SPRING_REQUEST;
    private String environmentVarName;
    private boolean enableLogEmitter = false;
    private boolean disableAgent = false;

    public static String usage() {
        return "Usage: java CfMetricsExporter " +
                "--debug,-d --trace --metricsEndpoint,-m <metricsEndpoint>,--intervalSeconds,-i <intervalSeconds>,--rpsType,-r <tomcat-mbean|spring-request|random>,--environmentVarName,-e <ENV_VAR_NAME>,--enableLogEmitter,--disableAgent";
    }

    public static void print(String message) {
        System.out.println(message);
    }

    public static Arguments parseArgs(String[] args) {

        Queue<String> options = new ArrayDeque<>(Arrays.asList(args));

        Arguments arguments = new Arguments();

        while (!options.isEmpty()) {
            String arg = options.remove();

            if (matches(arg, "-h", "--help", "help")) {
                print(Arguments.usage());
                System.exit(1);
            }

            if (matches(arg, "-d", "--debug", "debug")) {
                arguments.debug = true;
                continue;
            }

            if (matches(arg, "--trace", "trace")) {
                arguments.trace = true;
                continue;
            }

            if (matches(arg, "-m", "--metricsEndpoint", "metricsEndpoint")) {
                arguments.metricsEndpoint = options.remove();
                continue;
            }

            if (matches(arg, "-i", "--intervalSeconds", "intervalSeconds")) {
                String seconds = options.remove();
                arguments.intervalSeconds = Integer.parseInt(seconds);
                continue;
            }

            if (matches(arg, "-r", "--rpsType", "rpsType")) {
                String type = options.remove();
                arguments.rpsType = RequestsPerSecondType.valueOf(type.replace('-', '_').toUpperCase());
                continue;
            }

            if (matches(arg, "-e", "--environmentVarName", "environmentVarName")) {
                arguments.environmentVarName = options.remove();
                continue;
            }

            if (matches(arg, "--enableLogEmitter", "enableLogEmitter")) {
                arguments.enableLogEmitter = true;
                continue;
            }

            if (matches(arg, "--disableAgent", "disableAgent")) {
                arguments.disableAgent = true;
                continue;
            }

            print("WARN: unknown option: " + arg);

        }

        return arguments;
    }

    private static boolean matches(String arg, String... matchers) {
        return Arrays.asList(matchers).contains(arg);
    }

    @Override
    public String toString() {
        return "Arguments{" +
                "metricsEndpoint=" + metricsEndpoint +
                ", intervalSeconds=" + intervalSeconds +
                ", rpsType=" + rpsType +
                ", debug=" + debug +
                ", trace=" + trace +
                ", environmentVarName=" + environmentVarName +
                ", enableLogEmitter=" + enableLogEmitter +
                ", disableAgent=" + disableAgent +
                '}';
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isTrace() {
        return trace;
    }

    public String metricsEndpoint() {
        return metricsEndpoint;
    }

    public int intervalSeconds() {
        return intervalSeconds;
    }

    public RequestsPerSecondType type() {
        return rpsType;
    }

    public String environmentVarName() {
        return environmentVarName;
    }

    public boolean isEnableLogEmitter() {
        return enableLogEmitter;
    }

    public boolean isDisableAgent() {
        return disableAgent;
    }
}

