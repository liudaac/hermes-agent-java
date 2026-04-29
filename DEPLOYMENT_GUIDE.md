# Hermes Agent Java 部署指南

> 版本: 1.0  
> 更新日期: 2026-04-29

## 目录

1. [系统要求](#系统要求)
2. [快速开始](#快速开始)
3. [生产环境部署](#生产环境部署)
4. [GPU 支持配置](#gpu-支持配置)
5. [监控配置](#监控配置)
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
| 操作系统 | Linux (推荐 Ubuntu 22.04) |

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

### 1. 克隆代码

```bash
git clone https://github.com/liudaac/hermes-agent-java.git
cd hermes-agent-java
```

### 2. 编译

```bash
./mvnw clean package -DskipTests
```

### 3. 运行

```bash
java -jar target/hermes-agent-1.0.0.jar
```

### 4. 验证

```bash
curl http://localhost:8080/health
```

---

## 生产环境部署

### 使用 Docker Compose（推荐）

#### 1. 创建 docker-compose.yml

```yaml
version: '3.8'

services:
  # PostgreSQL
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: hermes
      POSTGRES_USER: hermes
      POSTGRES_PASSWORD: ${DB_PASSWORD:-secret}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U hermes"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - hermes-network

  # Redis (可选，用于会话缓存)
  redis:
    image: redis:7-alpine
    volumes:
      - redis_data:/data
    networks:
      - hermes-network

  # Hermes Agent (Node 1)
  hermes-node-1:
    build: .
    environment:
      - NODE_ID=node-1
      - DB_URL=jdbc:postgresql://postgres:5432/hermes
      - DB_USERNAME=hermes
      - DB_PASSWORD=${DB_PASSWORD:-secret}
      - REDIS_URL=redis://redis:6379
      - JAVA_OPTS=-Xmx4g -XX:+UseG1GC
    ports:
      - "8080:8080"
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock  # 容器沙箱需要
    networks:
      - hermes-network
    deploy:
      resources:
        limits:
          cpus: '4'
          memory: 8G

  # Hermes Agent (Node 2)
  hermes-node-2:
    build: .
    environment:
      - NODE_ID=node-2
      - DB_URL=jdbc:postgresql://postgres:5432/hermes
      - DB_USERNAME=hermes
      - DB_PASSWORD=${DB_PASSWORD:-secret}
      - REDIS_URL=redis://redis:6379
      - JAVA_OPTS=-Xmx4g -XX:+UseG1GC
    ports:
      - "8081:8080"
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    networks:
      - hermes-network
    deploy:
      resources:
        limits:
          cpus: '4'
          memory: 8G

  # Nginx 负载均衡
  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      - hermes-node-1
      - hermes-node-2
    networks:
      - hermes-network

  # Prometheus
  prometheus:
    image: prom/prometheus:v2.47.0
    volumes:
      - ./monitoring/prometheus:/etc/prometheus
      - prometheus_data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
    ports:
      - "9090:9090"
    networks:
      - hermes-network

  # Grafana
  grafana:
    image: grafana/grafana:10.1.0
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_PASSWORD:-admin}
    volumes:
      - ./monitoring/grafana:/var/lib/grafana/dashboards
      - grafana_data:/var/lib/grafana
    ports:
      - "3000:3000"
    networks:
      - hermes-network

volumes:
  postgres_data:
  redis_data:
  prometheus_data:
  grafana_data:

networks:
  hermes-network:
    driver: bridge
```

#### 2. 创建 nginx.conf

```nginx
events {
    worker_connections 1024;
}

http {
    upstream hermes_backend {
        least_conn;
        server hermes-node-1:8080;
        server hermes-node-2:8080;
    }

    server {
        listen 80;

        location / {
            proxy_pass http://hermes_backend;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        }

        location /metrics {
            proxy_pass http://hermes_backend/metrics;
        }
    }
}
```

#### 3. 启动服务

```bash
# 设置环境变量
export DB_PASSWORD=your_secure_password
export GRAFANA_PASSWORD=your_secure_password

# 启动
docker-compose up -d

# 查看状态
docker-compose ps

# 查看日志
docker-compose logs -f hermes-node-1
```

---

## GPU 支持配置

### 1. 安装 NVIDIA 驱动

```bash
# Ubuntu
sudo apt update
sudo apt install -y nvidia-driver-535
sudo reboot
```

### 2. 安装 NVIDIA Container Toolkit

```bash
# 添加仓库
distribution=$(. /etc/os-release;echo $ID$VERSION_ID)
curl -s -L https://nvidia.github.io/nvidia-docker/gpgkey | \
    sudo apt-key add -
curl -s -L https://nvidia.github.io/nvidia-docker/$distribution/nvidia-docker.list | \
    sudo tee /etc/apt/sources.list.d/nvidia-docker.list

# 安装
sudo apt update
sudo apt install -y nvidia-container-toolkit

# 重启 Docker
sudo systemctl restart docker
```

### 3. 验证 GPU 可用

```bash
# 主机上
nvidia-smi

# Docker 容器中
docker run --rm --gpus all nvidia/cuda:12.0-base nvidia-smi
```

### 4. 配置 Hermes 使用 GPU

```yaml
# docker-compose.yml 中 hermes 服务添加
services:
  hermes-node-1:
    # ... 其他配置
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu]
```

---

## 监控配置

### Prometheus 配置

```yaml
# monitoring/prometheus/prometheus.yml
global:
  scrape_interval: 30s

scrape_configs:
  - job_name: 'hermes-nodes'
    static_configs:
      - targets: 
        - 'hermes-node-1:8080'
        - 'hermes-node-2:8080'
    metrics_path: '/metrics'
```

### Grafana 配置

1. 访问 http://localhost:3000
2. 登录（默认: admin/admin）
3. 添加 Prometheus 数据源
   - URL: http://prometheus:9090
4. 导入仪表板
   - 导入 `monitoring/grafana/tenant-dashboard.json`

### 告警配置

```yaml
# monitoring/prometheus/alertmanager.yml
global:
  smtp_smarthost: 'smtp.example.com:587'
  smtp_from: 'alerts@example.com'
  smtp_auth_username: 'alerts@example.com'
  smtp_auth_password: 'your_password'

route:
  receiver: 'email'

receivers:
  - name: 'email'
    email_configs:
      - to: 'admin@example.com'
        subject: 'Hermes Alert: {{ .GroupLabels.alertname }}'
```

---

## 高可用配置

### 数据库高可用

使用 PostgreSQL 主从复制：

```yaml
# docker-compose-ha.yml
services:
  postgres-primary:
    image: bitnami/postgresql-repmgr:15
    environment:
      - POSTGRESQL_POSTGRES_PASSWORD=adminpassword
      - POSTGRESQL_USERNAME=hermes
      - POSTGRESQL_PASSWORD=secret
      - POSTGRESQL_DATABASE=hermes
      - REPMGR_PASSWORD=repmgrpassword
      - REPMGR_PRIMARY_HOST=postgres-primary
      - REPMGR_PRIMARY_PORT=5432
      - REPMGR_PARTNER_NODES=postgres-primary:5432,postgres-standby:5432
      - REPMGR_NODE_NAME=postgres-primary
      - REPMGR_NODE_NETWORK_NAME=postgres-primary
      - REPMGR_PORT_NUMBER=5432
      - REPMGR_CONNECT_TIMEOUT=5
      - REPMGR_RECONNECT_ATTEMPTS=3
      - REPMGR_RECONNECT_INTERVAL=5
    volumes:
      - postgres_primary_data:/bitnami/postgresql

  postgres-standby:
    image: bitnami/postgresql-repmgr:15
    environment:
      - POSTGRESQL_POSTGRES_PASSWORD=adminpassword
      - POSTGRESQL_USERNAME=hermes
      - POSTGRESQL_PASSWORD=secret
      - POSTGRESQL_DATABASE=hermes
      - REPMGR_PASSWORD=repmgrpassword
      - REPMGR_PRIMARY_HOST=postgres-primary
      - REPMGR_PRIMARY_PORT=5432
      - REPMGR_PARTNER_NODES=postgres-primary:5432,postgres-standby:5432
      - REPMGR_NODE_NAME=postgres-standby
      - REPMGR_NODE_NETWORK_NAME=postgres-standby
      - REPMGR_PORT_NUMBER=5432
      - REPMGR_CONNECT_TIMEOUT=5
      - REPMGR_RECONNECT_ATTEMPTS=3
      - REPMGR_RECONNECT_INTERVAL=5
    depends_on:
      - postgres-primary
    volumes:
      - postgres_standby_data:/bitnami/postgresql
```

### 节点故障转移

使用 Kubernetes 或 Docker Swarm 实现自动故障转移：

```yaml
# kubernetes-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hermes-agent
spec:
  replicas: 3
  selector:
    matchLabels:
      app: hermes-agent
  template:
    metadata:
      labels:
        app: hermes-agent
    spec:
      containers:
        - name: hermes-agent
          image: hermes-agent:latest
          ports:
            - containerPort: 8080
          resources:
            requests:
              memory: "4Gi"
              cpu: "2"
            limits:
              memory: "8Gi"
              cpu: "4"
          livenessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /ready
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 5
```

---

## 故障排查

### 常见问题

#### 1. 容器沙箱无法启动

**症状**: `ContainerSandboxException: Docker not available`

**解决**:
```bash
# 检查 Docker 状态
sudo systemctl status docker

# 检查用户权限
sudo usermod -aG docker $USER

# 重启服务
sudo systemctl restart docker
```

#### 2. GPU 无法检测

**症状**: `GpuManager: No GPU detected`

**解决**:
```bash
# 检查驱动
nvidia-smi

# 检查容器 toolkit
nvidia-container-cli info

# 重启 Docker
sudo systemctl restart docker
```

#### 3. 数据库连接失败

**症状**: `Connection refused to PostgreSQL`

**解决**:
```bash
# 检查 PostgreSQL 状态
docker-compose exec postgres pg_isready -U hermes

# 检查日志
docker-compose logs postgres

# 检查网络
docker network inspect hermes-network
```

#### 4. 内存不足

**症状**: `OutOfMemoryError` 或 `MemoryQuotaExceededException`

**解决**:
```bash
# 调整 JVM 参数
export JAVA_OPTS="-Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 调整容器限制
docker-compose up -d --memory=8g
```

### 日志收集

```bash
# 收集所有日志
docker-compose logs > hermes-logs.txt

# 收集特定服务日志
docker-compose logs hermes-node-1 > node1-logs.txt

# 实时日志
docker-compose logs -f --tail=100
```

### 性能调优

#### JVM 参数

```bash
# G1 GC (推荐)
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:InitiatingHeapOccupancyPercent=35

# 堆内存
-Xms4g -Xmx4g

# 直接内存
-XX:MaxDirectMemorySize=2g

# GC 日志
-XX:+PrintGCDetails
-XX:+PrintGCTimeStamps
-Xloggc:/var/log/hermes/gc.log
```

#### 操作系统调优

```bash
# /etc/sysctl.conf
vm.max_map_count=262144
vm.swappiness=1
net.ipv4.tcp_max_syn_backlog=65536
net.core.somaxconn=65535

# 应用配置
sysctl -p
```

---

## 安全建议

### 1. 网络安全

- 使用防火墙限制端口访问
- 启用 TLS/SSL
- 配置网络隔离

### 2. 数据安全

- 定期备份数据库
- 启用数据加密
- 配置访问控制

### 3. 运行时安全

- 使用只读文件系统
- 限制容器权限
- 启用审计日志

---

## 升级指南

### 滚动升级

```bash
# 1. 拉取新镜像
docker-compose pull

# 2. 逐个升级节点
docker-compose stop hermes-node-1
docker-compose up -d hermes-node-1

# 3. 等待健康检查通过
curl http://localhost:8080/health

# 4. 升级下一个节点
docker-compose stop hermes-node-2
docker-compose up -d hermes-node-2
```

### 数据库迁移

```bash
# 备份
docker-compose exec postgres pg_dump -U hermes hermes > backup.sql

# 升级
docker-compose exec postgres psql -U hermes < migration.sql
```

---

## 参考

- [API 文档](API_DOCUMENTATION.md)
- [架构图文档](ARCHITECTURE_DIAGRAMS.md)
- [GitHub 仓库](https://github.com/liudaac/hermes-agent-java)
