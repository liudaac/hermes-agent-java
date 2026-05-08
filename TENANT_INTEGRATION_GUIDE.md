# Hermes 多租户前端集成指南

## 概述

本文档描述如何将多租户管理功能集成到 Hermes Dashboard 中。

## 当前架构问题

```
┌─────────────────┐          ┌─────────────────┐
│   Dashboard     │          │   Tenant        │
│   (单租户)       │   ❌ 无连接 │   Manager       │
│                 │◄────────►│   (多租户API)    │
└─────────────────┘          └─────────────────┘
        │                           │
        ▼                           ▼
┌─────────────────┐          ┌─────────────────┐
│  HermesConfig   │          │ TenantContext   │
│  (单租户配置)    │          │ (租户隔离)       │
└─────────────────┘          └─────────────────┘
```

## 改造方案

### 第一步：修改 DashboardServer

修改 `DashboardServer.java`，添加 `TenantManager` 支持：

```java
public class DashboardServer {
    // 添加 TenantManager
    private final TenantManager tenantManager;
    
    // 修改构造函数
    public DashboardServer(int port, String host, HermesConfig config, 
                          TenantManager tenantManager) {
        this.port = port;
        this.host = host;
        this.config = config;
        this.tenantManager = tenantManager;
        // ...
    }
}
```

### 第二步：使用 MultiTenantDashboardServer

或者直接使用已创建的 `MultiTenantDashboardServer`：

```java
// DashboardRunner.java
public void start(int port, String host) {
    HermesConfig config = HermesConfig.load();
    
    // 创建 TenantManager
    TenantManager tenantManager = new TenantManager();
    
    // 使用多租户版本
    server = new MultiTenantDashboardServer(port, host, config, tenantManager);
    
    // 注册租户路由
    ((MultiTenantDashboardServer) server).registerTenantRoutes(app);
    
    server.start();
}
```

### 第三步：添加静态文件路由

在 `registerStaticRoutes()` 方法中添加租户管理页面：

```java
// 在 DashboardServer.registerStaticRoutes() 中添加：

// 租户管理页面
app.get("/admin/tenants", ctx -> {
    Path tenantPage = webDist.resolve("tenant-management.html");
    if (Files.exists(tenantPage)) {
        ctx.contentType("text/html");
        ctx.result(Files.readString(tenantPage));
    } else {
        ctx.status(404).result("Tenant management page not found");
    }
});
```

### 第四步：修改 DashboardRunner

```java
// DashboardRunner.java
public void start(int port, String host) {
    logger.info("Starting Hermes Dashboard on http://{}:{}", host, port);

    HermesConfig config;
    try {
        config = HermesConfig.load();
    } catch (Exception e) {
        logger.warn("Could not load config, using defaults: {}", e.getMessage());
        config = new HermesConfig();
    }
    
    // 创建 TenantManager
    TenantManager tenantManager = new TenantManager();
    
    // 使用多租户 DashboardServer
    server = new MultiTenantDashboardServer(port, host, config, tenantManager);
    
    serverThread = new Thread(() -> {
        try {
            server.start();
        } catch (Exception e) {
            logger.error("Dashboard server error: {}", e.getMessage(), e);
        }
    }, "dashboard-server");

    // ...
}
```

## API 路由列表

集成后新增的 API 端点：

### 租户管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/tenants` | 获取租户列表 |
| POST | `/api/tenants` | 创建租户 |
| GET | `/api/tenants/{id}` | 获取租户详情 |
| DELETE | `/api/tenants/{id}` | 删除租户 |
| POST | `/api/tenants/{id}/suspend` | 暂停租户 |
| POST | `/api/tenants/{id}/resume` | 恢复租户 |

### 配额管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/tenants/{id}/quota` | 获取配额 |
| PUT | `/api/tenants/{id}/quota` | 更新配额 |
| GET | `/api/tenants/{id}/usage` | 获取使用量 |

### 安全策略

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/tenants/{id}/security` | 获取安全策略 |
| PUT | `/api/tenants/{id}/security` | 更新安全策略 |

### 审计日志

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/tenants/{id}/audit` | 获取审计日志 |

### 资源监控

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/tenants/{id}/metrics` | 获取资源指标 |

### 租户内功能

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/tenants/{id}/config` | 获取租户配置 |
| PUT | `/api/tenants/{id}/config` | 更新租户配置 |
| GET | `/api/tenants/{id}/sessions` | 获取租户会话 |
| GET | `/api/tenants/{id}/skills` | 获取租户技能 |

## 前端界面

访问路径：`http://localhost:9119/admin/tenants`

### 功能模块

1. **租户管理**
   - 租户列表展示
   - 创建/删除租户
   - 暂停/恢复租户
   - 租户详情查看

2. **资源监控**
   - 配额使用进度条
   - 实时资源统计
   - 历史趋势图表

3. **审计日志**
   - 操作记录查询
   - 事件类型筛选
   - 时间范围过滤

## 文件清单

```
hermes-agent-java/
├── src/main/java/com/nousresearch/hermes/dashboard/
│   ├── MultiTenantDashboardServer.java  # 多租户 Dashboard 服务器
│   └── DashboardServer.java             # 原 Dashboard 服务器（需修改）
├── frontend/
│   └── tenant-management.html           # 租户管理前端页面
└── TENANT_INTEGRATION_GUIDE.md          # 本指南
```

## 快速开始

### 1. 编译项目

```bash
cd /root/hermes-agent-java
mvn compile -DskipTests
```

### 2. 复制前端文件

```bash
cp frontend/tenant-management.html web_dist/
```

### 3. 启动服务

```bash
# 方式1：使用默认配置
java -cp target/classes:target/dependency/* \
    com.nousresearch.hermes.dashboard.DashboardRunner

# 方式2：指定端口
java -cp target/classes:target/dependency/* \
    com.nousresearch.hermes.dashboard.DashboardRunner \
    --port 9119 --host 0.0.0.0
```

### 4. 访问界面

打开浏览器访问：`http://localhost:9119/admin/tenants`

## 权限控制

生产环境建议添加权限控制：

```java
// 在 MultiTenantDashboardServer 中添加权限检查
private void checkAdminPermission(Context ctx) {
    String auth = ctx.header("Authorization");
    // 验证是否为管理员
    if (!isAdmin(auth)) {
        ctx.status(403).json(error("Admin permission required"));
        throw new UnauthorizedException();
    }
}
```

## 注意事项

1. **数据隔离**：每个租户的数据存储在独立的目录中
2. **资源限制**：需要配置配额防止资源耗尽
3. **安全性**：生产环境需要添加身份验证
4. **备份策略**：定期备份租户数据

## 后续优化

1. 添加租户分组/标签功能
2. 实现租户间数据迁移
3. 添加更多监控指标
4. 集成 Prometheus/Grafana
5. 添加告警通知功能
