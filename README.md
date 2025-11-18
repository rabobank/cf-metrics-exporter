# cf-metrics-exporter

A JVM agent to send custom metrics to the cloud foundry custom metrics endpoint.

This also counts the container-to-container traffic and can be used for autoscaling.

The purpose of this agent:
* parse autoscaler endpoint info from VCAP_SERVICES
* collect RPS (Requests Per Second) from the application
* send RPS to cloud foundry custom metrics endpoint 

Sends custom metric with name `custom_throughput` and unit: `rps`.
(Cloud Foundry auto scaler has a `throughput` metric.)

The RPS is calculated as an **average** over the configured interval (default 10 seconds).
So the longer the interval, the fewer peaks in RPS over time are reported.

### Spring Boot RPS

Transform these classes with ByteBuddy on SpringBoot API to count request/reply:

    * org.springframework.web.servlet.DispatcherServlet#handle
    * org.springframework.web.reactive.DispatcherHandler#handle

No need to enable Tomcat MBeans via application settings

Works for all Spring Boot servers:
* Netty (WebFlux/Reactor)
* Tomcat (also with virtual threads)
* Undertow

Use `rpsType=spring-request` to enable this feature (default).

### Tomcat RPS

For Tomcat use the JMX MBean and Attribute:
* `Tomcat:type=GlobalRequestProcessor,name="http-nio-8080"` (or similar, depending on your Tomcat configuration)
* `Attribute: requestCount`

Needs explicit application setting: `server.tomcat.mbeanregistry.enabled=true`

Use `rpsType=tomcat-mbean` to enable this feature.

### Random RPS

This is a random RPS generator, useful for testing purposes. It generates a random number of requests per second.

Use `rpsType=random` to enable this feature.

## Metric Emitters

There are three emitters:
* `CustomMetricsSender`: Sends metrics to the cloud foundry custom metrics endpoint.
* `OtlpRpsExporter`: Sends metrics to an OTLP endpoint.
* `LogEmitter`: Logs metrics to the console.

The `CustomMetricsSender` and `OtlpRpsExporter` are enabled based on the presence of the auto-scaler endpoint and the otlp endpoint 
as defined in the standard environment variables `VCAP_SERVICES` and `MANAGEMENT_OTLP_METRICS_EXPORT_URL`.

The `LogEmitter` is enabled based on the presence of the `enableLogEmitter` setting.

## Agent usage

Copy the jar to the CF container and activate it via the `-javaagent` option.

In the `manifest.yml` file, add the following:

    -javaagent:/path/to/cf-metrics-exporter-<version>.jar

with settings:

    -javaagent:/path/to/cf-metrics-exporter-<version>.jar=debug,rpsType=random,intervalSeconds=5

## Agent Settings

The following settings are available:
- `debug`: Enable debug logging. To enable just add `--debug` without value.
- `trace`: Enable trace logging. To enable just add `--trace` without value.
- `rpsType`: Type of RPS to use. Options are `spring-request` (default), `random`, `tomcat-mbean`.
- `intervalSeconds`: Interval in seconds for sending metrics. Default is 10 seconds. Note: the average RPS is calculated for every interval.
- `metricsEndpoint`: Override the App Autoscaler metrics base URL. When provided, it replaces the URL for the selected auth mode (BASIC or mTLS), instead of the value from `VCAP_SERVICES`.
- `environmentVarName`: The name of the environment variable to use to extract the value for the environment (e.g `CF_ENVIRONMENT` where for example `CF_ENVIROMENT=test` in the env settings). 
- `enableLogEmitter`: Enable logging of emitted metrics. Default is false. To enable just add `--enableLogEmitter` without value.
- `disableAgent`: Disable the agent completely. Default is false. To disable just add `--disableAgent` without value.

Authentication overrides (optional):
- `basicUsername` and `basicPassword`: Use BASIC authentication to the App Autoscaler. When both are provided, BASIC auth is used regardless of what `VCAP_SERVICES` reports. `metricsEndpoint` (when set) will be applied to the BASIC URL.
- `cfInstanceKey`, `cfInstanceCert`, `cfSystemCertPath`: Use mTLS with explicit file locations. These flags mirror the Cloud Foundry env vars `CF_INSTANCE_KEY`, `CF_INSTANCE_CERT`, and `CF_SYSTEM_CERT_PATH`. When all three are provided, mTLS is used. `metricsEndpoint` (when set) will be applied to the mTLS URL.

Precedence:
1. BASIC auth overrides (username/password)
2. mTLS overrides (cfInstanceKey/cert/systemCertPath)
3. Defaults from `VCAP_SERVICES` and CF env vars

Run with debug enabled to see stacktraces of exceptions.

## Env variables

The following environment variables are used from within the cloud foundry container:
- `VCAP_APPLICATION`
- `VCAP_SERVICES`
- `CF_INSTANCE_INDEX`
- For mTLS from the platform: `CF_INSTANCE_KEY`, `CF_INSTANCE_CERT`, `CF_SYSTEM_CERT_PATH`

The `VCAP_SERVICES` should contain the custom metrics endpoint and basic auth credentials or mTLS endpoint.

There is a `src/test/resources/test.env` file that can be used to set these variables for local testing.
The `src/test/resources/test-missing-basic-auth.env` can be used to test with mTLS instead of basic auth.

Use via `source src/test/resources/test.env` in your terminal or add as env file in the IDE runner.

# Open Telemetry

The agent will send the RPS metric to an Open Telemetry endpoint if the `MANAGEMENT_OTLP_METRICS_EXPORT_URL` environment variable is set.
It only supports the http protocol and no authentication as of yet. The metric name is `custom_throughput`. The unit is `1/s`.
The attributes are:    
- `cf_application_name`
- `cf_space_name`
- `cf_organization_name`
- `cf_instance_index`
- `environment`

- The `environment` is the value of the system environment as given by the `environmentVarName` variable.

## Build

To build the project, use the following command:

```bash
./mvnw clean package
```

The agent jar will be created in the `target` directory: `target/cf-metrics-exporter-LOCAL-SNAPSHOT.jar`

## Test metrics endpoint

A Wiremock server is included to test the agent. It can be used with basic-auth (port 58080) and mTLS (port 58443).

The certificates for mTLS are generated with the `mtls-certs/mtls-certificate-setup.sh` script.
This script is executed in the compile step of the Maven build.
The certs are in `target/generated-certs`.

Beware: PKCS#1 and PKCS#8 PEM formats are both encountered in practice. This project implements
an minimal pure‑Java parser for unencrypted PKCS#1 ("BEGIN RSA PRIVATE KEY") keys and uses standard
JCA APIs for PKCS#8 ("BEGIN PRIVATE KEY") keys. No external crypto providers are required.

# Notes

This is a "Beta" release, feedback is welcome!

