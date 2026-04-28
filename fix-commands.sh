#!/bin/bash
# 修复 hermes-agent-java 编译错误的完整步骤

cd /Users/liuda17/work/project/opensource/hermes-agent-java

# 1. 修复 TenantManager.java - 删除重复方法
# 编辑 src/main/java/com/nousresearch/hermes/tenant/core/TenantManager.java
# 删除第 248-275 行的重复方法（isRegistered, deleteTenant, suspendTenant, getAllTenants, getTenantConfigPath）

# 2. 修复 TenantController.java - 统一使用 core 包
cat > /tmp/fix_imports.patch << 'EOF'
--- a/src/main/java/com/nousresearch/hermes/gateway/api/TenantController.java
+++ b/src/main/java/com/nousresearch/hermes/gateway/api/TenantController.java
@@ -3,8 +3,8 @@ package com.nousresearch.hermes.gateway.api;
 import com.fasterxml.jackson.databind.ObjectMapper;
 import com.nousresearch.hermes.config.Config;
 import com.nousresearch.hermes.tenant.Tenant;
-import com.nousresearch.hermes.tenant.TenantConfig;
-import com.nousresearch.hermes.tenant.TenantProvisioningRequest;
+import com.nousresearch.hermes.tenant.core.TenantConfig;
+import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
 import com.nousresearch.hermes.tenant.core.TenantContext;
 import com.nousresearch.hermes.tenant.core.TenantManager;
 import com.nousresearch.hermes.tenant.core.TenantSkill;
EOF

# 3. 修复 TenantSessionManager - 添加缺失方法
cat >> src/main/java/com/nousresearch/hermes/tenant/core/TenantSessionManager.java << 'EOF'

    /**
     * 获取活跃会话数量
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }
EOF

# 4. 修复 TenantConfig (core包) - 添加 setter 方法
# 在 src/main/java/com/nousresearch/hermes/tenant/core/TenantConfig.java 中添加：

cat >> src/main/java/com/nousresearch/hermes/tenant/core/TenantConfig.java << 'EOF'

    // Setter methods for compatibility
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public void setQuota(TenantQuota quota) { this.quota = quota; }
    public void setSecurityPolicy(TenantSecurityPolicy policy) { this.securityPolicy = policy; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
EOF

echo "修复命令已准备完成"
