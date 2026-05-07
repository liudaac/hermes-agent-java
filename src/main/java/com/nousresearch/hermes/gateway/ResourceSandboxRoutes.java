package com.nousresearch.hermes.gateway;

import com.nousresearch.hermes.gateway.api.ResourceSandboxController;
import com.nousresearch.hermes.tenant.core.TenantManager;
import io.javalin.Javalin;

/**
 * 资源沙箱 API 路由配置
 */
public class ResourceSandboxRoutes {

    public static void register(Javalin app, TenantManager tenantManager) {
        ResourceSandboxController controller = new ResourceSandboxController(tenantManager);

        // 进程执行
        app.post("/api/tenants/{tenantId}/exec", controller::executeCommand);

        // 网络请求
        app.get("/api/tenants/{tenantId}/http", controller::httpGet);

        // 资源指标
        app.get("/api/tenants/{tenantId}/metrics", controller::getMetrics);

        // 网络日志
        app.get("/api/tenants/{tenantId}/network/logs", controller::getNetworkLogs);

        // 网络访问测试
        app.post("/api/tenants/{tenantId}/network/test", controller::testNetworkAccess);
    }
}
