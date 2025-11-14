# Change log cf-metrics-exporter

### v0.6.0: November 2025
* Activate agent when only OTLP endpoint is configured or only log emitter is enabled
* Added Log emitter, which logs metrics to stdout, activate with `enableLogEmitter` agent option
* Removed standard INFO RPS sent log messages
* Added `disableAgent` agent option to disable agent completely

### v0.5.0: November 2025
* Remove dependencies for shaded jars to avoid extra dependencies when cf-metrics-exporter is used via pom dependency

### v0.4.0: November 2025
* Add sending RPS to Open Telemetry endpoint
* Update dependencies

### v0.3.0: August 2025
* Release to maven central
* Changed metric name from `custom_http_throughput` to `custom_throughput`
* Update dependencies

### v0.2.0: July 2025
* Add support for mTLS

### v0.1.0: June 2025
* Beta release, feedback welcome!
* Calculate RPS via Tomcat MBean
* Calculate RPS via Spring Request handlers
* Works with basic auth credentials only, mTLS not supported yet

