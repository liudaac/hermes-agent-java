# Hermes Agent Java 部署指南

## 快速开始

### 1. 环境要求

- **Java**: 17 或更高版本
- **Maven**: 3.8+
- **内存**: 最少 2GB，推荐 4GB+
- **磁盘**: 最少 10GB 可用空间
- **操作系统**: Linux (推荐 Ubuntu 22.04), macOS, Windows

### 2. 安装依赖

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install -y openjdk-17-jdk maven python3 nodejs

# macOS
brew install openjdk@17 maven python3 node

# 验证安装
java -version
mvn -version
python3 --version
node --version
```

### 3. 编译项目

```bash
cd /path/to/hermes-agent-java
mvn clean package -DskipTests
```

编译完成后，可执行 JAR 位于 `target/hermes-agent-1.0-SNAPSHOT.jar`

### 4. 配置文件

创建配置文件 `config.json`:

```json
{
  "server": {
    "port": 8080,
    "host": "0.0.0.0"
  },
  "llm": {
    "provider": "openai",
    "model": "gpt-4",
    "apiKey": "${OPENAI_API_KEY}",
    "baseUrl": "https://api.openai.com/v1"
  },
  "storage": {
    "dataDir": "~/.hermes",
    "maxFileSize": "100MB"
  },
  "tenants": {
    "enabled": true,
    "defaultQuota": {
      "maxDailyRequests": 1000,
      "maxDailyTokens": 100000,
      "maxStorageBytes": 1073741824,
      "maxMemoryBytes": 536870912,
      "maxToolCallsPerSession": 50
    }
  },
  "security": {
    "requireApproval": true,
    "sandboxEnabled": true,
    "allowedLanguages": ["python", "javascript"]
  }
}
```

### 5. 启动服务

```bash
# 使用默认配置
java -jar target/hermes-agent-1.0-SNAPSHOT.jar

# 使用自定义配置
java -jar target/hermes-agent-1.0-SNAPSHOT.jar --config config.json

# 后台运行
nohup java -jar target/hermes-agent-1.0-SNAPSHOT.jar > hermes.log 2>&1 &
```

### 6. 验证服务

```bash
# 健康检查
curl http://localhost:8080/health

# 查看 API 文档
curl http://localhost:8080/api/v1/tenants
```

## 生产环境部署

### Docker 部署

#### 1. 创建 Dockerfile

```dockerfile
FROM openjdk:17-jdk-slim

# 安装依赖
RUN apt-get update && apt-get install -y \
    python3 \
    python3-pip \
    nodejs \
    npm \
    git \
    && rm -rf /var/lib/apt/lists/*

# 创建工作目录
WORKDIR /app

# 复制 JAR 文件
COPY target/hermes-agent-1.0-SNAPSHOT.jar app.jar
COPY config.json config.json

# 创建数据目录
RUN mkdir -p /data/.hermes

# 暴露端口
EXPOSE 8080

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar", "--config", "config.json"]
```

#### 2. 构建镜像

```bash
docker build -t hermes-agent:latest .
```

#### 3. 运行容器

```bash
docker run -d \
  --name hermes-agent \
  -p 8080:8080 \
  -v $(pwd)/data:/data/.hermes \
  -e OPENAI_API_KEY=$OPENAI_API_KEY \
  hermes-agent:latest
```

### Kubernetes 部署

#### 1. 创建 ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: hermes-config
data:
  config.json: |
    {
      "server": {
        "port": 8080,
        "host": "0.0.0.0"
      },
      "tenants": {
        "enabled": true
      }
    }
```

#### 2. 创建 Deployment

```yaml
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
        env:
        - name: OPENAI_API_KEY
          valueFrom:
            secretKeyRef:
              name: hermes-secrets
              key: openai-api-key
        volumeMounts:
        - name: config
          mountPath: /app/config.json
          subPath: config.json
        - name: data
          mountPath: /data/.hermes
        resources:
          requests:
            memory: "2Gi"
            cpu: "1000m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
      volumes:
      - name: config
        configMap:
          name: hermes-config
      - name: data
        persistentVolumeClaim:
          claimName: hermes-data-pvc
```

#### 3. 创建 Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: hermes-agent
spec:
  selector:
    app: hermes-agent
  ports:
  - port: 80
    targetPort: 8080
  type: ClusterIP
```

## 多租户配置

### 创建租户

```bash
# 创建企业租户
curl -X POST http://localhost:8080/api/v1/tenants \
  -H "Content-Type: application/json" \
  -d '{
    "tenant_id": "enterprise_001",
    "name": "Enterprise Customer",
    "quota": {
      "max_daily_requests": 100000,
      "max_storage_bytes": 10737418240,
      "max_memory_bytes": 2147483648
    },
    "security": {
      "allow_code_execution": true,
      "allowed_languages": ["python", "javascript", "bash"]
    }
  }'

# 创建个人租户（更严格限制）
curl -X POST http://localhost:8080/api/v1/tenants \
  -H "Content-Type: application/json" \
  -d '{
    "tenant_id": "personal_001",
    "name": "Personal User",
    "quota": {
      "max_daily_requests": 1000,
      "max_storage_bytes": 536870912,
      "max_memory_bytes": 268435456
    },
    "security": {
      "allow_code_execution": false,
      "denied_tools": ["terminal", "execute_bash"]
    }
  }'
```

### 租户隔离验证

```bash
# 检查租户工作目录
ls -la ~/.hermes/tenants/

# 验证沙箱隔离
curl http://localhost:8080/api/v1/tenants/enterprise_001

# 查看资源使用
curl http://localhost:8080/api/v1/tenants/enterprise_001/usage
```

## 监控与日志

### 日志配置

```json
{
  "logging": {
    "level": "INFO",
    "file": "/var/log/hermes/agent.log",
    "maxSize": "100MB",
    "maxFiles": 10
  }
}
```

### 监控指标

服务暴露以下 Prometheus 指标：

```
# 租户指标
hermes_tenant_active_total
hermes_tenant_requests_total
hermes_tenant_quota_usage_percent

# 资源指标
hermes_storage_bytes
hermes_memory_bytes
hermes_cpu_usage_percent
```

### 健康检查

```bash
# HTTP 健康检查
curl http://localhost:8080/health

# Kubernetes 探针
livenessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 5
```

## 故障排查

### 常见问题

#### 1. 编译失败

```bash
# 清理并重新编译
mvn clean
rm -rf ~/.m2/repository/com/nousresearch
mvn clean package
```

#### 2. 端口被占用

```bash
# 查找占用端口的进程
lsof -i :8080

# 或修改配置文件使用其他端口
```

#### 3. 内存不足

```bash
# 增加 JVM 内存
java -Xmx4g -Xms2g -jar target/hermes-agent-1.0-SNAPSHOT.jar
```

#### 4. 权限问题

```bash
# 确保数据目录权限正确
chmod -R 755 ~/.hermes
chown -R $(whoami):$(whoami) ~/.hermes
```

## 安全建议

1. **API 认证**: 生产环境启用 API Key 或 JWT 认证
2. **HTTPS**: 使用反向代理（Nginx/Traefik）启用 HTTPS
3. **网络隔离**: 限制服务仅监听 localhost 或内网 IP
4. **定期备份**: 备份 `~/.hermes/tenants/` 目录
5. **日志审计**: 启用审计日志并定期审查

## 升级指南

### 版本升级

```bash
# 1. 备份数据
cp -r ~/.hermes ~/.hermes.backup.$(date +%Y%m%d)

# 2. 拉取新版本
git pull origin main

# 3. 重新编译
mvn clean package

# 4. 重启服务
pkill -f hermes-agent
java -jar target/hermes-agent-1.0-SNAPSHOT.jar
```

### 数据迁移

```bash
# 导出租户数据
tar -czf hermes-backup.tar.gz ~/.hermes/tenants/

# 在新服务器恢复
tar -xzf hermes-backup.tar.gz -C /
```
