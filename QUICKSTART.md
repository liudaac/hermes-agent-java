# Hermes Agent Java - 快速启动指南（租户模式）

## 快速开始

### 1. 编译项目

```bash
cd /root/hermes-agent-java
mvn clean compile -DskipTests
```

### 2. 启动单用户模式（默认租户）

```bash
# 方式1：直接运行
mvn exec:java -Dexec.mainClass="com.nousresearch.hermes.HermesAgentV2"

# 方式2：带租户模式标志
mvn exec:java -Dexec.mainClass="com.nousresearch.hermes.HermesAgentV2" \
  -Dexec.args="--tenant"
```

### 3. 验证租户模式

启动后，你应该看到类似输出：
```
[INFO] Hermes Agent V2 initialized (tenant mode: true)
[INFO] Tenant mode initialized with default tenant
[INFO] Gateway V2 started (tenant mode)
```

### 4. 交互式测试

如果没有配置网关适配器，系统会自动进入交互模式：
```
╔══════════════════════════════════════╗
║      Hermes Agent V2 - Ready         ║
╚══════════════════════════════════════╝
Type 'exit' to quit, 'help' for commands

hermes> 你好
🤖 你好！我是 Hermes Agent，有什么可以帮助你的吗？
```

### 5. 检查租户状态

```bash
# 查看租户列表
curl http://localhost:8080/api/tenants

# 查看默认租户配额
curl http://localhost:8080/api/tenants/default/quota

# 查看系统状态
curl http://localhost:8080/api/status
```

---

## 配置说明

### 单用户模式（默认）
自动创建 `default` 租户，所有数据隔离在：
```
~/.hermes/tenants/default/
├── workspace/    # 工作文件
├── skills/       # 技能文件
├── memories/     # 记忆文件
├── config/       # 配置文件
└── logs/         # 日志文件
```

### 多租户模式
通过 API 创建新租户：
```bash
curl -X POST http://localhost:8080/api/tenants \
  -H "Content-Type: application/json" \
  -d '{"id": "tenant_001", "created_by": "admin"}'
```

---

## 常见问题

**Q: 如何切换回传统模式？**
```bash
# 不带 --tenant 参数启动
mvn exec:java -Dexec.mainClass="com.nousresearch.hermes.HermesAgentV2"
```

**Q: 如何增加配额？**
编辑 `~/.hermes/tenants/default/config/quota.yaml`

**Q: 如何启用更多工具？**
编辑 `~/.hermes/tenants/default/config/security.yaml`

---

## 下一步

查看详细文档：
- [docs/README.md](docs/README.md) - 文档索引
- [docs/REFACTORING.md](docs/REFACTORING.md) - 重构方案与迁移清单
- [docs/RESOURCE_ISOLATION.md](docs/RESOURCE_ISOLATION.md) - 租户资源隔离说明
