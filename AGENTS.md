# AGENTS.md - Your Workspace

## 多租户隔离实施进度

### Phase 1: 租户基础设施 ✅ 已完成

**时间**: 2025-04-25  
**状态**: 编译通过 ✅

#### 已创建组件：

| 组件 | 路径 | 功能 |
|------|------|------|
| TenantContext | `tenant/core/TenantContext.java` | 租户上下文容器，管理所有隔离子组件 |
| TenantManager | `tenant/core/TenantManager.java` | 租户生命周期管理器，支持创建/销毁/清理 |
| TenantProvisioningRequest | `tenant/core/TenantProvisioningRequest.java` | 租户配置请求，Builder 模式 |
| TenantManagerConfig | `tenant/core/TenantManagerConfig.java` | 管理器配置 |

#### 支撑子系统（基础实现）：

| 子系统 | 路径 | 状态 |
|--------|------|------|
| 配额管理 | `tenant/quota/` | ✅ 基础实现 |
| 文件沙箱 | `tenant/sandbox/` | ✅ 基础实现 |
| 安全策略 | `tenant/security/` | ✅ 基础实现 |
| 审计日志 | `tenant/audit/` | ✅ 基础实现 |
| 租户组件 | `tenant/core/` | 🟡 占位实现 |

#### 租户目录结构：
```
~/.hermes/tenants/
├── {tenantId}/
│   ├── config/           # 配置
│   ├── workspace/        # 文件沙箱
│   │   ├── sessions/     # 会话工作区
│   │   ├── uploads/      # 上传文件
│   │   ├── generated/    # 生成文件
│   │   ├── cache/        # 缓存
│   │   └── temp/         # 临时文件
│   ├── memories/         # 记忆
│   ├── sessions/         # 会话持久化
│   ├── skills/           # Skills
│   │   ├── private/      # 私有
│   │   └── installed/    # 安装
│   ├── logs/             # 日志
│   └── state/            # 运行时状态
├── _shared/skills/       # 共享 Skills
└── _system/
    └── tenants.json      # 租户注册表
```

---

### Phase 2: 文件沙箱隔离 ✅ 已完成

**时间**: 2025-04-26  
**状态**: 编译通过 ✅ | 测试通过 ✅ (10/10)

#### 实现内容：

| 功能 | 实现 | 测试 |
|------|------|------|
| 路径遍历防护 | `..`, `~` 检测 | ✅ |
| 符号链接控制 | 可选允许/禁止 | ✅ |
| 硬链接检测 | Unix nlink 检查 | ✅ |
| 目录深度限制 | 可配置最大深度 | ✅ |
| 文件类型检查 | 可执行文件黑名单 | ✅ |
| 文件大小限制 | 单文件 + 总配额 | ✅ |
| 会话工作区 | 隔离的上传/输出/临时目录 | ✅ |
| 存储统计 | 使用量/文件数/目录数 | ✅ |
| POSIX 权限 | Unix 文件权限设置 | ✅ |
| 临时文件管理 | 自动创建和清理 | ✅ |

#### 安全特性：

```
路径验证流程 (11 层检查):
1. 空路径检查
2. 规范化路径
3. 路径遍历检测 (.., ~)
4. 符号链接检查
5. 硬链接检查
6. 目录深度检查
7. 沙箱边界检查
8. 白名单检查
9. 黑名单检查
10. 文件存在性和权限检查
11. 文件类型和大小检查
```

#### 测试覆盖：
- 沙箱内路径允许
- 路径遍历拒绝
- 符号链接控制
- 会话工作区创建/清理
- 存储使用统计
- 禁止文件类型
- 空/Null 路径处理
- 存储配额检查

---

### Phase 3: 配置与记忆隔离 ✅ 已完成

**时间**: 2025-04-26  
**状态**: 编译通过 ✅

#### TenantConfig 实现：

| 功能 | 实现 | 说明 |
|------|------|------|
| 分层配置 | 系统默认 + 租户覆盖 | 深度合并配置 |
| 点号路径 | `model.provider` | 嵌套配置访问 |
| 类型安全 | getString/getInt/getBoolean | 类型转换 |
| 环境变量 | `${VAR}` 占位符 | 动态替换 |
| 密钥管理 | secrets.env 文件 | 独立存储敏感信息 |
| POSIX 权限 | 0600 | 密钥文件权限 |
| YAML 持久化 | config.yaml | 人类可读格式 |

#### TenantMemoryManager 实现：

| 功能 | 实现 | 说明 |
|------|------|------|
| 双存储 | MEMORY.md + USER.md | 系统记忆 + 用户画像 |
| 条目分隔 | `§` 符号 | 多条目存储 |
| 标签系统 | Set<String> | 分类标签 |
| 安全扫描 | 威胁模式检测 | 防止提示词注入 |
| 不可见字符 | Unicode 检测 | U+200B-U+202E |
| 去重机制 | 相似度检测 | 前20字符匹配 |
| 自动修剪 | 字符限制 | 最旧条目优先删除 |
| 系统快照 | 冻结提示词 | 会话期间不变 |
| 租户隔离 | 独立目录 | 完全隔离 |

#### 配置继承链：
```
系统默认值
    ↓ (深度合并)
租户配置文件 (config.yaml)
    ↓ (覆盖)
运行时修改
    ↓ (环境变量展开)
最终配置值
```

#### 记忆存储格式：
```markdown
# MEMORY.md
条目内容 1
§
条目内容 2 (带标签)
§
条目内容 3
§
```

---

### Phase 4: Skill 隔离管理 ✅ 已完成

**时间**: 2025-04-26  
**状态**: 编译通过 ✅

#### 实现内容：

| 功能 | 实现 |
|------|------|
| 四层分级 | 私有 > 已安装 > 共享 > 系统 > 内置 |
| 安全扫描 | 代码块提取 + 危险模式检测 |
| 安装管理 | 从 Registry 安装/卸载 |
| 分享机制 | 租户间 Skill 共享 |
| 内存缓存 | 1小时过期策略 |
| SHA-256 签名 | 内容完整性验证 |
| 配额限制 | 私有/安装数量限制 |

#### 存储结构：
```
skills/
├── private/        # 租户私有创建
├── installed/      # 从 Registry 安装
└── index.json      # 本地索引
```

---

## 完整实施路线图

| 阶段 | 模块 | 优先级 | 状态 |
|------|------|--------|------|
| **Phase 1** | 租户基础设施 | 🔴 P0 | ✅ 完成 |
| **Phase 2** | 文件沙箱隔离 | 🔴 P0 | ✅ 完成 |
| **Phase 3** | 配置与记忆隔离 | 🟡 P1 | ✅ 完成 |
| **Phase 4** | Skill 隔离管理 | 🟡 P1 | ✅ 完成 |
| **Phase 5** | 代码执行沙箱 | 🟡 P1 | ⏳ 待开始 |
| **Phase 6** | 工具权限控制 | 🟢 P2 | ⏳ 待开始 |
| **Phase 7** | 资源配额与审计 | 🟢 P2 | ⏳ 待开始 |

---

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                    Multi-Tenant Gateway                      │
├─────────────────────────────────────────────────────────────┤
│  TenantManager                                                │
│  ├── TenantContext (tenant-1)                                │
│  │   ├── TenantConfig                                        │
│  │   ├── TenantFileSandbox                                   │
│  │   ├── TenantMemoryManager                                 │
│  │   ├── TenantSkillManager                                  │
│  │   ├── TenantSessionManager                                │
│  │   ├── TenantToolRegistry                                  │
│  │   ├── TenantQuotaManager                                  │
│  │   └── TenantAIAgent(s)                                    │
│  ├── TenantContext (tenant-2)                                │
│  └── ...                                                      │
└─────────────────────────────────────────────────────────────┘
```

---

This is your workspace. Make it yours.
