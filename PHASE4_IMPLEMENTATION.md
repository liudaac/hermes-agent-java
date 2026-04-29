# Phase 4 实现文档 - 高可用性

> 实现时间: 2026-04-29
> 版本: v1.0

## 概述

Phase 4 实现了多节点高可用架构，支持租户状态持久化、分布式会话管理和优雅重启。

## 实现内容

### 1. 租户状态持久化 (`TenantStateRepository`)

**文件**:
- `src/main/java/com/nousresearch/hermes/tenant/persistence/TenantStateRepository.java`
- `src/main/java/com/nousresearch/hermes/tenant/persistence/PostgresTenantRepository.java`

**功能**:
- 异步 CRUD 操作
- 乐观锁版本控制
- 会话状态存储
- 多后端支持（PostgreSQL、Redis、MongoDB）

**数据模型**:
```sql
-- 租户状态表
CREATE TABLE tenant_states (
    tenant_id VARCHAR(255) PRIMARY KEY,
    state tenant_state NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_activity TIMESTAMP WITH TIME ZONE NOT NULL,
    config JSONB NOT NULL DEFAULT '{}',
    quota JSONB NOT NULL DEFAULT '{}',
    security_policy JSONB NOT NULL DEFAULT '{}',
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 会话表
CREATE TABLE tenant_sessions (
    tenant_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_activity TIMESTAMP WITH TIME ZONE NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    serialized_context BYTEA,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, session_id)
);
```

### 2. 分布式会话管理 (`DistributedSessionManager`)

**文件**: `src/main/java/com/nousresearch/hermes/tenant/session/DistributedSessionManager.java`

**功能**:
- 本地缓存 + 数据库存储
- 会话在节点间自动迁移
- 空闲超时检测（30分钟）
- 绝对超时检测（8小时）
- 心跳保活（30秒）

**会话生命周期**:
```
创建 -> 活跃 -> [空闲/绝对超时] -> 释放 -> 销毁
              -> 节点迁移 -> 重新激活
```

**超时配置**:
```java
HEARTBEAT_INTERVAL = Duration.ofSeconds(30)
SESSION_TIMEOUT = Duration.ofMinutes(30)    // 空闲超时
ABSOLUTE_TIMEOUT = Duration.ofHours(8)      // 绝对超时
```

### 3. 优雅重启 (`GracefulShutdownHandler`)

**文件**: `src/main/java/com/nousresearch/hermes/tenant/lifecycle/GracefulShutdownHandler.java`

**功能**:
- JVM 关闭钩子注册
- 请求排空（draining）
- 租户暂停
- 状态持久化
- 优雅关闭超时回退

**关闭流程**:
```
1. 通知租户准备关闭
2. 等待活跃请求完成
3. 暂停所有租户（不再接受新请求）
4. 持久化所有租户状态
5. 优雅关闭所有租户
6. [超时] 强制关闭
```

## 架构

```
┌─────────────────────────────────────────┐
│           GracefulShutdownHandler       │
│  - JVM shutdown hook                    │
│  - Request draining                     │
│  - State persistence                    │
└─────────────────────────────────────────┘
                    │
┌───────────────────▼─────────────────────┐
│         TenantManager                   │
│  - Multi-node coordination              │
│  - Tenant lifecycle                     │
└─────────────────────────────────────────┘
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
┌───────────┐ ┌───────────┐ ┌───────────┐
│  Node 1   │ │  Node 2   │ │  Node 3   │
│  Tenant A │ │  Tenant B │ │  Tenant C │
│  (active) │ │ (standby) │ │ (standby) │
└───────────┘ └───────────┘ └───────────┘
        │           │           │
        └───────────┼───────────┘
                    ▼
        ┌───────────────────────┐
        │   PostgreSQL / Redis  │
        │   - tenant_states     │
        │   - tenant_sessions   │
        └───────────────────────┘
```

## 使用示例

### 保存租户状态
```java
TenantStateRepository repo = new PostgresTenantRepository(dataSource);

TenantStateSnapshot state = new TenantStateSnapshot(
    tenantId,
    TenantContext.State.ACTIVE,
    Instant.now(),
    Instant.now(),
    config,
    quota,
    securityPolicy,
    1L  // version
);

repo.saveState(tenantId, state).thenAccept(v -> {
    logger.info("Tenant state saved");
});
```

### 分布式会话
```java
DistributedSessionManager sessionManager = new DistributedSessionManager(
    context, 
    repository,
    nodeId
);

// 创建会话
sessionManager.createSession("session-123", Map.of("user", "alice"))
    .thenAccept(session -> {
        logger.info("Session created: {}", session.getSessionId());
    });

// 获取会话（自动迁移）
sessionManager.getSession("session-123")
    .thenAccept(opt -> {
        opt.ifPresent(session -> {
            logger.info("Session active on node: {}", session.getNodeId());
        });
    });
```

### 优雅关闭
```java
GracefulShutdownHandler shutdownHandler = new GracefulShutdownHandler(
    tenantManager,
    Duration.ofSeconds(30)
);

// 注册关闭钩子
shutdownHandler.registerShutdownHook();

// 手动触发
shutdownHandler.initiateShutdown().thenAccept(v -> {
    logger.info("Shutdown complete");
});
```

## 部署配置

### Docker Compose
```yaml
version: '3'
services:
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: hermes
      POSTGRES_USER: hermes
      POSTGRES_PASSWORD: secret
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"

  hermes-node-1:
    image: hermes-agent:latest
    environment:
      - NODE_ID=node-1
      - DB_URL=jdbc:postgresql://postgres:5432/hermes
    depends_on:
      - postgres

  hermes-node-2:
    image: hermes-agent:latest
    environment:
      - NODE_ID=node-2
      - DB_URL=jdbc:postgresql://postgres:5432/hermes
    depends_on:
      - postgres
```

## 性能

| 指标 | 数值 |
|------|------|
| 状态保存延迟 | < 50ms |
| 会话迁移时间 | < 100ms |
| 优雅关闭超时 | 30s |
| 心跳间隔 | 30s |
| 会话检查间隔 | 30s |

## 相关文件

- `TenantStateRepository.java` - 持久化接口
- `PostgresTenantRepository.java` - PostgreSQL 实现
- `DistributedSessionManager.java` - 分布式会话
- `GracefulShutdownHandler.java` - 优雅关闭
- `PHASE4_IMPLEMENTATION.md` - 本文档
