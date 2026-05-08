# Hermes 多租户前端快速集成

## 已创建的文件

```
hermes-agent-java/
├── src/main/java/com/nousresearch/hermes/dashboard/
│   ├── MultiTenantDashboardServer.java      # 完整的 Dashboard 多租户服务器
│   └── TenantDashboardIntegration.java      # 快速集成工具类
├── frontend/
│   └── tenant-management.html               # 租户管理前端界面
├── TENANT_INTEGRATION_GUIDE.md              # 详细集成文档
└── QUICK_START.md                           # 本文件
```

## 快速集成步骤

### 方案 A：使用 TenantDashboardIntegration（推荐）

在 `DashboardServer.registerApiRoutes()` 方法末尾添加：

```java
// 在 registerApiRoutes() 方法末尾添加：
// 注册租户管理路由（需要 DashboardServer 有 tenantManager 字段）
TenantDashboardIntegration.registerRoutes(app, tenantManager);
```

然后修改 `DashboardServer` 添加 `TenantManager`：

```java
public class DashboardServer {
    // ... 现有代码 ...
    
    // 添加 TenantManager
    private TenantManager tenantManager;
    
    // 修改构造函数或添加 setter
    public void setTenantManager(TenantManager tenantManager) {
        this.tenantManager = tenantManager;
    }
}
```

### 方案 B：使用 MultiTenantDashboardServer

修改 `DashboardRunner`：

```java
public void start(int port, String host) {
    HermesConfig config = HermesConfig.load();
    TenantManager tenantManager = new TenantManager();
    
    // 使用多租户版本
    server = new MultiTenantDashboardServer(port, host, config, tenantManager);
    
    serverThread = new Thread(() -> {
        try {
            server.start();
            // 注册租户路由
            ((MultiTenantDashboardServer) server).registerTenantRoutes(
                ((MultiTenantDashboardServer) server).getApp()
            );
        } catch (Exception e) {
            logger.error("Dashboard server error: {}", e.getMessage(), e);
        }
    }, "dashboard-server");
    
    // ... 其余代码
}
```

### 方案 C：最小修改（仅添加 API）

在 `DashboardServer` 中添加：

```java
@Override
public void start() {
    super.start();
    
    // 获取 Javalin 实例并注册租户路由
    // 需要修改 DashboardServer 暴露 app 字段
    TenantManager tenantManager = new TenantManager();
    TenantDashboardIntegration.registerRoutes(this.app, tenantManager);
}
```

## 前端界面访问

### 1. 复制前端文件

```bash
cp frontend/tenant-management.html web_dist/
# 或
mkdir -p web_dist
cp frontend/tenant-management.html web_dist/
```

### 2. 添加静态文件路由

在 `registerStaticRoutes()` 中添加：

```java
app.get("/admin/tenants", ctx -> {
    Path page = webDist.resolve("tenant-management.html");
    if (Files.exists(page)) {
        ctx.contentType("text/html");
        ctx.result(Files.readString(page));
    } else {
        ctx.status(404).result("Not found");
    }
});
```

### 3. 访问界面

启动服务后访问：

```
http://localhost:9119/admin/tenants
```

## API 测试

### 创建租户

```bash
curl -X POST http://localhost:9119/api/tenants \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "tenantId": "test-tenant",
    "createdBy": "admin"
  }'
```

### 获取租户列表

```bash
curl http://localhost:9119/api/tenants \
  -H "Authorization: Bearer YOUR_TOKEN"
```

### 获取租户配额

```bash
curl http://localhost:9119/api/tenants/test-tenant/quota \
  -H "Authorization: Bearer YOUR_TOKEN"
```

### 获取使用量

```bash
curl http://localhost:9119/api/tenants/test-tenant/usage \
  -H "Authorization: Bearer YOUR_TOKEN"
```

## 界面功能

### 租户管理 Tab
- 租户列表展示
- 创建/删除租户
- 暂停/恢复租户
- 租户状态实时显示

### 资源监控 Tab
- 配额使用进度条
- 资源统计卡片
- 实时指标展示

### 审计日志 Tab
- 操作记录查询
- 事件类型筛选
- 时间范围过滤

## 注意事项

1. **权限控制**：生产环境需要添加管理员权限验证
2. **数据备份**：定期备份租户数据目录
3. **资源限制**：配置合理的配额防止资源耗尽
4. **日志监控**：关注审计日志排查问题

## 下一步优化

1. 添加租户分组/标签功能
2. 实现租户间数据迁移
3. 添加更多监控指标
4. 集成 Prometheus/Grafana
