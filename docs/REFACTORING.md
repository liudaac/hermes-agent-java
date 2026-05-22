# Hermes Agent Java - 重构与迁移文档

> 本文档合并了原 REFACTORING_PLAN.md、REFACTORING_DELIVERY.md、MIGRATION_CHECKLIST.md、IMPLEMENTATION_SUMMARY.md

---

## 一、重构方案概述

### 核心目标
将系统从"租户/非租户双模式"重构为"全租户模式"，单用户场景使用默认租户。

### 设计原则
1. **全租户模式**：所有操作都在租户上下文中执行
2. **默认租户**：单用户场景自动使用 `default` 租户
3. **向后兼容**：现有 API 保持兼容，内部自动路由到租户系统
4. **渐进迁移**：分阶段替换，确保系统始终可运行

### 关键变更
- 统一 Agent 架构：TenantAwareAIAgent 替换双模式 Agent
- 工具层改造：TenantAwareToolDispatcher 统一处理
- Gateway 升级：GatewayServerV2 支持全租户
- 配置简化：单配置文件，通过 `tenant.enabled` 开关

---

## 二、迁移检查清单

### Phase 1: 统一 Agent 架构
- [x] 创建 TenantAwareAIAgent（统一单用户/租户模式）
- [x] 移除条件判断 `if (tenantMode)`，统一使用租户上下文
- [x] 修改 HermesAgentV2 使用 TenantAwareAIAgent
- [x] 确保默认租户自动创建

### Phase 2: 工具层改造
- [x] 创建 TenantAwareToolDispatcher
- [x] 实现权限检查、配额检查、资源隔离
- [x] 所有工具调用通过 TenantAwareToolDispatcher

### Phase 3: Gateway 升级
- [x] 创建 GatewayServerV2
- [x] 实现 PlatformAdapter 接口
- [x] WebSocket、HTTP、SSE 连接统一处理

### Phase 4: 配置简化
- [x] 统一 HermesConfig 配置类
- [x] 单配置文件，通过 `tenant.enabled` 开关
- [x] 移除 TenantConfig（合并到 HermesConfig）

### Phase 5: 清理与验证
- [x] 删除已废弃类
- [x] 更新测试
- [x] 验证编译和运行

---

## 三、已实现的核心组件

### 租户核心层
- `TenantContext` - 租户上下文（ThreadLocal + InheritableThreadLocal）
- `TenantManager` - 租户管理（CRUD + 配额）
- `TenantQuotaManager` - 配额管理（Token/消息/磁盘）
- `TenantSessionManager` - 会话隔离管理

### 安全与审计
- `TenantAuditLogger` - 审计日志
- `PermissionCheckResult` - 权限检查结果

### 资源隔离
- `ProcessSandbox` - 进程沙箱
- `NetworkSandbox` - 网络沙箱
- `StorageQuotaManager` - 存储配额
- `TenantThreadPool` - 线程池隔离

### Gateway
- `GatewayServerV2` - 多协议 Gateway（HTTP/WebSocket/SSE）
- `PlatformAdapter` - 平台适配器接口
- 统计：`ConnectionStats`、`MessageStats`、`TenantStats`

### 工具层
- `TenantAwareToolDispatcher` - 租户感知工具调度器
- `ToolRegistry` - 工具注册表

---

## 四、向后兼容说明

- 现有单用户 API 不变，内部自动路由到 `default` 租户
- 配置文件格式不变，新增 `tenant.enabled` 选项
- 工具调用方式不变，自动附加租户上下文

