# Gateway Service Mode

Hermes Gateway supports a lightweight service mode with a PID file.

## PID File

By default the gateway writes its PID to:

```text
~/.hermes/gateway.pid
```

The PID file is used to detect whether a gateway process is already running and to stop the service later.

## Start

Service mode starts the gateway in the current JVM process and records the process PID. It is suitable for CLI wrappers, supervisor processes, and systemd-managed deployments.

```bash
java -jar target/hermes-agent-java-*.jar gateway start
```

## Stop

Stop mode reads `~/.hermes/gateway.pid` and sends a termination signal to that process. If the process does not exit within a short grace period, it is forcibly destroyed.

```bash
java -jar target/hermes-agent-java-*.jar gateway stop
```

## Recommended Production Usage

For production, prefer using a process supervisor such as systemd, Docker, or Kubernetes. Service mode provides PID bookkeeping, but it is not a full daemon supervisor by itself.

Example systemd unit:

```ini
[Unit]
Description=Hermes Agent Java Gateway
After=network.target

[Service]
Type=simple
User=hermes
WorkingDirectory=/opt/hermes-agent-java
ExecStart=/usr/bin/java -jar /opt/hermes-agent-java/target/hermes-agent-java.jar gateway start
ExecStop=/usr/bin/java -jar /opt/hermes-agent-java/target/hermes-agent-java.jar gateway stop
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

## Notes

- If the JVM exits normally, the PID file is removed.
- If the process is killed abruptly, the next `start` checks whether the PID is still alive and automatically removes stale or invalid PID files.
- The gateway still handles graceful shutdown by stopping adapters, API server, dashboard, and tenant manager.
