# Hermes Agent Java 部署指南

## 目录

1. [系统要求](#系统要求)
2. [快速开始](#快速开始)
3. [配置文件](#配置文件)
4. [生产环境部署](#生产环境部署)
5. [GPU 支持](#gpu-支持)
6. [高可用配置](#高可用配置)
7. [故障排查](#故障排查)

---

## 系统要求

### 最低配置

| 组件 | 要求 |
|------|------|
| JDK | 17+ |
| 内存 | 4GB |
| 磁盘 | 20GB |
| 操作系统 | Linux (推荐 Ubuntu 22.04), macOS, Windows |

### 推荐配置

| 组件 | 要求 |
|------|------|
| JDK | 21 (LTS) |
| 内存 | 16GB |
| 磁盘 | 100GB SSD |
| CPU | 8 核 |
| 操作系统 | Linux (Ubuntu 22.04 LTS) |

### 可选依赖

| 组件 | 用途 | 版本 |
|------|------|------|
| PostgreSQL | 状态持久化 | 15+ |
| Redis | 会话缓存 | 7+ |
| Docker/Podman | 容器化沙箱 | 最新 |
| NVIDIA Container Toolkit | GPU 支持 | 最新 |
| Prometheus | 指标收集 | 2.40+ |
| Grafana | 可视化 | 9.0+ |

---

## 快速开始

### 1. 安装依赖

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install -y openjdk-17-jdk maven python3 nodejs

# macOS
brew install openjdk@17 maven python3 node

# 验证
java -version
mvn -version
```

### 2. 编译项目

```bash
cd /path/to/hermes-agent-java
mvn clean package -DskipTests
```

编译产物位于 `target/hermes-agent-1.0-SNAPSHOT.jar`

### 3. 配置文件

创建 `config.json`:

```json
{
  "server": {
    "port": 8080,
    "host": "0.0.0.0"
  },
  "llm": {
    "provider": "openai",
    "apiKey": "sk-xxx",
    "baseUrl": "https://api.openai.com/v1",
    "model": "gpt-4"
  },
  "tenant": {
    "defaultQuota": {
      "maxTokensPerDay": 100000,
      "maxStorageBytes": 1073741824,
      "maxConcurrentTasks": 4
    }
  }
}
```

### 4. 启动服务

```bash
# 前台启动（CLI 模式）
java -jar target/hermes-agent-*.jar

# Gateway 服务模式
java -jar target/hermes-agent-*.jar gateway start

# 带配置文件
java -jar target/hermes-agent-*.jar gateway start --config config.json
```

启动后访问：
- Dashboard: http://localhost:8080/dashboard
- API: http://localhost:8080/api/v1

### 5. 停止服务

```bash
java -jar target/hermes-agent-*.jar gateway stop
```

---

## 配置文件

### 主要配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` | 8080 | HTTP 服务端口 |
| `server.host` | 0.0.0.0 | 监听地址 |
| `llm.provider` | openai | 模型提供商 |
| `llm.apiKey` | - | API 密钥 |
| `llm.baseUrl` | - | API 地址 |
| `llm.model` | gpt-4 | 默认模型 |
| `tenant.defaultQuota.maxTokensPerDay` | 100000 | 每租户每日 Token 限制 |
| `tenant.defaultQuota.maxStorageBytes` | 1GB | 每租户存储配额 |
| `tenant.defaultQuota.maxConcurrentTasks` | 4 | 每租户并发任务数 |
| `storage.basePath` | ~/.hermes | 数据存储根目录 |
| `auth.enabled` | false | 是否启用认证 |
| `auth.adminPassword` | - | 管理员密码 |

### 环境变量覆盖

任何配置都可通过环境变量覆盖，格式 `HERMES_路径_字段`（大写 + 下划线）：

```bash
export HERMES_SERVER_PORT=9090
export HERMES_LLM_API_KEY=sk-xxx
```

---

## 生产环境部署

### 方式一：Docker 部署

```bash
# 构建镜像
docker build -t hermes-agent-java:latest .

# 运行
docker run -d \
  --name hermes \
  -p 8080:8080 \
  -v ~/.hermes:/root/.hermes \
  -e HERMES_LLM_API_KEY=sk-xxx \
  --restart unless-stopped \
  hermes-agent-java:latest
```

### 方式二：Docker Compose 部署

```yaml
version: "3.8"
services:
  hermes:
    build: .
    ports:
      - "8080:8080"
    volumes:
      - hermes-data:/root/.hermes
    environment:
      - HERMES_LLM_API_KEY=${HERMES_LLM_API_KEY}
      - HERMES_LLM_PROVIDER=openai
      - HERMES_AUTH_ENABLED=true
      - HERMES_AUTH_ADMIN_PASSWORD=${ADMIN_PASSWORD}
    restart: unless-stopped
    depends_on:
      - postgres
      - redis

  postgres:
    image: postgres:15-alpine
    environment:
      - POSTGRES_DB=hermes
      - POSTGRES_USER=hermes
      - POSTGRES_PASSWORD=${DB_PASSWORD}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    volumes:
      - redis-data:/data
    restart: unless-stopped

volumes:
  hermes-data:
  postgres-data:
  redis-data:
```

### 方式三：systemd 部署

```ini
# /etc/systemd/system/hermes.service
[Unit]
Description=Hermes Agent Java Gateway
After=network.target

[Service]
Type=simple
User=hermes
WorkingDirectory=/opt/hermes-agent-java
ExecStart=/usr/bin/java -jar hermes-agent-java.jar gateway start --config config.json
ExecStop=/usr/bin/java -jar hermes-agent-java.jar gateway stop
Restart=on-failure
RestartSec=5
Environment="HERMES_LLM_API_KEY=sk-xxx"

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable hermes
sudo systemctl start hermes
sudo systemctl status hermes
```

### JVM 参数调优

```bash
java -Xms4G -Xmx16G \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -jar hermes-agent-java.jar gateway start
```

---

## GPU 支持

### 前置条件

- NVIDIA GPU + 驱动
- NVIDIA Container Toolkit（Docker 部署时）

### 配置

```bash
# Docker 运行时添加 GPU
docker run -d --gpus all \
  -e HERMES_GPU_ENABLED=true \
  ...
```

本地 JVM 部署需确保本地有可用的 CUDA 环境，通过环境变量启用：

```bash
export HERMES_GPU_ENABLED=true
export CUDA_VISIBLE_DEVICES=0
```

---

## 高可用配置

### 多节点部署架构

```
Nginx/ALB (SSL + 负载均衡)
    │
    ├── Hermes Node 1
    ├── Hermes Node 2
    └── Hermes Node 3
           │
           ├── PostgreSQL (主从)
           ├── Redis Cluster
           └── NFS / 对象存储 (租户文件)
```

### 关键配置

```json
{
  "cluster": {
    "enabled": true,
    "nodeId": "node-1",
    "sessionStore": "redis",
    "stateStore": "postgres"
  },
  "redis": {
    "host": "redis-host",
    "port": 6379
  },
  "postgres": {
    "url": "jdbc:postgresql://db-host:5432/hermes",
    "user": "hermes",
    "password": "xxx"
  }
}
```

### 会话亲和性

建议在负载均衡层配置基于 IP 或 Cookie 的会话亲和性（sticky session），减少跨节点状态同步开销。

---

## 故障排查

### 常见问题

**1. 端口被占用**
```bash
lsof -i :8080
# 或修改配置 server.port
```

**2. 内存不足（OOM）**
- 调大 `-Xmx` 参数
- 减少 `maxConcurrentTasks`
- 检查是否有内存泄漏（`jmap -heap <pid>`）

**3. 模型调用失败**
- 检查 API Key 和 baseUrl
- 确认网络可达
- 查看 `~/.hermes/logs/hermes.log`

**4. 租户沙箱命令执行失败**
- 检查命令是否在白名单内
- 确认工作目录权限
- 查看审计日志：`~/.hermes/tenants/{id}/audit/`

**5. 文件系统持久化损坏**
- `.bak` 文件会自动恢复
- 如仍损坏，从备份恢复 `~/.hermes/` 目录

### 日志位置

```text
~/.hermes/logs/hermes.log          # 主日志
~/.hermes/tenants/{id}/audit/      # 租户审计日志
~/.hermes/trajectories/            # 轨迹数据
```

### 健康检查

```bash
# HTTP 健康检查
curl http://localhost:8080/api/v1/health

# 预期响应
{"status":"ok","version":"1.0.0"}
```

---

*版本: 1.1（合并版）*
