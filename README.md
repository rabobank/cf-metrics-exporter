# cf-metrics-exporter

A JVM agent to send custom metrics to the cloud foundry custom metrics endpoint.

This also counts the container-to-container traffic and can be used for autoscaling.

The purpose of this agent:
* parse autoscaler endpoint info from VCAP_SERVICES
* collect RPS (Requests Per Second) from the application
* send RPS to cloud foundry custom metrics endpoint 

Sends custom metric with name `custom_http_throughput` and unit: `rps`.

The RPS is calculated as an average over the configured interval (default 10 seconds).

### Tomcat RPS

For Tomcat use the JMX MBean and Attribute:
* `Tomcat:type=GlobalRequestProcessor,name="http-nio-8080"` (or similar, depending on your Tomcat configuration)
* `Attribute: requestCount`

Needs explicit application setting: `server.tomcat.mbeanregistry.enabled=true`

Use `rspType=tomcat-mbean` to enable this feature (default).

### Spring Boot RPS 

Transform these classes with ByteBuddy on SpringBoot API to count request/reply:

    * org.springframework.web.servlet.DispatcherServlet#handle
    * org.springframework.web.reactive.DispatcherHandler#handle

No need to enable Tomcat MBeans via application settings

Works for all Spring Boot servers: 
* Netty (WebFlux/Reactor) 
* Tomcat (also with virtual threads)
* Undertow

Use `rspType=spring-request` to enable this feature (default).

### Random RPS

This is a random RPS generator, useful for testing purposes. It generates a random number of requests per second.

Use `rspType=random` to enable this feature.

## Agent usage

Copy the jar to the CF container and activate it via the `-javaagent` option.

In the `manifest.yml` file, add the following:

    -javaagent:/path/to/cf-metrics-exporter-<version>.jar

with settings:

    -javaagent:/path/to/cf-metrics-exporter-<version>.jar=debug,rpsType=random,intervalSeconds=5

## Agent Settings

The following settings are available:
- `debug`: Enable debug logging.
- `rpsType`: Type of RPS to use. Options are `spring-request` (default), `random`, `tomcat-mbean`.
- `intervalSeconds`: Interval in seconds for sending metrics. Default is 10 seconds. Note: the average RPS is calculated for every interval.
- `metricsEndpoint`: The endpoint to send metrics to. Not used currently, will pick it up from VCAP_SERVICES.

## Env variables

The following environment variables are used from within the cloud foundry container:
- VCAP_APPLICATION
- VCAP_SERVICES
- CF_INSTANCE_INDEX

The VCAP_SERVICES should contain the custom metrics endpoint and basic auth credentials. mTLS is not supported.

There is a `src/test/resources/test.env` file that can be used to set these variables for local testing.
Use via `source src/test/resources/test.env` in your terminal or add as env file in the IDE runner.

## Build

To build the project, use the following command:

```bash
./mvnw clean package
```

The agent jar will be created in the `target` directory: `target/cf-metrics-exporter-LOCAL-SNAPSHOT.jar`

# Notes

This is a "Beta" release, feedback is welcome!

