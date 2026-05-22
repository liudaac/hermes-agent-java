# Hermes Java 自我进化机制对齐文档

## 概述

本文档记录 Java 版本 Hermes 与 Python 原版在自我进化/学习机制上的对齐工作。

## 原版 Python Hermes 的自我进化机制

### 核心概念

原版 Hermes 使用 **Nudge Interval（提示间隔）** 机制实现自我进化：

1. **Memory Nudge（内存提示）**
   - 默认间隔：每 **10 轮用户对话**
   - 触发条件：`memory_nudge_interval > 0` 且存在 `memory` 工具
   - 作用：自动回顾对话，保存用户偏好、个人信息等到 MEMORY.md

2. **Skill Nudge（技能提示）**
   - 默认间隔：每 **10 次工具调用迭代**
   - 触发条件：`skill_nudge_interval > 0` 且存在 `skill_manage` 工具
   - 作用：自动回顾对话，创建或更新可复用的技能

3. **Background Review（后台审查）**
   - 在用户对话结束后异步执行
   - 创建独立的 AIAgent 实例进行审查
   - 使用特定的 Review Prompt 引导分析
   - 结果通过 `💾` 图标反馈给用户

### Python 关键代码位置

```python
# run_agent.py 第 1109 行
self._memory_nudge_interval = mem_config.get("nudge_interval", 10)

# run_agent.py 第 1209 行
self._skill_nudge_interval = skills_config.get("creation_nudge_interval", 10)

# run_agent.py 第 1954 行
def _spawn_background_review(self, messages_snapshot, review_memory=False, review_skills=False)

# Review Prompts 第 1906-1949 行
_MEMORY_REVIEW_PROMPT = "..."
_SKILL_REVIEW_PROMPT = "..."
_COMBINED_REVIEW_PROMPT = "..."
```

## Java 版本对齐实现

### 1. 新增字段（AIAgent.java）

```java
// Nudge intervals for self-improvement (aligned with Python Hermes)
private int memoryNudgeInterval = 10;      // Nudge every 10 user turns
private int skillNudgeInterval = 10;       // Nudge every 10 tool iterations
private int turnsSinceMemory = 0;          // Counter for memory nudge
private int itersSinceSkill = 0;           // Counter for skill nudge
private int userTurnCount = 0;             // Total user turns in session
```

### 2. Review Prompts（与 Python 完全一致）

```java
private static final String MEMORY_REVIEW_PROMPT = "...";
private static final String SKILL_REVIEW_PROMPT = "...";
private static final String COMBINED_REVIEW_PROMPT = "...";
```

### 3. 配置加载（ConfigManager.java）

新增配置项：
```yaml
memory:
  nudge_interval: 10           # Nudge every 10 user turns

skills:
  creation_nudge_interval: 10   # Nudge every 10 tool iterations
```

### 4. 核心方法

#### loadNudgeConfig()
从 ConfigManager 加载 nudge 间隔配置，默认值为 10。

#### hasTool(String toolName)
检查工具是否在可用工具列表中。

#### spawnBackgroundReview()
核心后台审查方法，实现逻辑：
1. 根据触发条件选择合适的 Review Prompt
2. 创建后台线程执行审查
3. 创建独立的 AIAgent 实例（max_iterations=8）
4. 共享 memoryManager 和 skillManager
5. 禁用 review agent 的 nudge 机制（避免递归）
6. 扫描 tool 结果，提取成功操作
7. 向用户显示 `💾` 摘要

### 5. 触发点

在 `processMessage()` 和 `processUserMessage()` 中：

1. **用户消息处理开始时**
   - 增加 `userTurnCount`
   - 检查 memory nudge 触发条件

2. **工具调用后**
   - 增加 `itersSinceSkill`

3. **对话结束后**
   - 检查 skill nudge 触发条件
   - 调用 `spawnBackgroundReview()`

## 对齐状态

| 功能 | Python 原版 | Java 版本 | 状态 |
|------|-------------|-----------|------|
| Memory Nudge Interval | ✅ 默认10 | ✅ 默认10 | ✅ 已对齐 |
| Skill Nudge Interval | ✅ 默认10 | ✅ 默认10 | ✅ 已对齐 |
| 配置加载 | ✅ 从 config.yaml | ✅ 从 ConfigManager | ✅ 已对齐 |
| Review Prompts | ✅ 3个 | ✅ 3个（内容一致） | ✅ 已对齐 |
| 后台线程执行 | ✅ threading.Thread | ✅ Thread (daemon) | ✅ 已对齐 |
| 独立 Review Agent | ✅ AIAgent fork | ✅ AIAgent 实例 | ✅ 已对齐 |
| 工具结果扫描 | ✅ JSON解析 | ✅ JSON解析 | ✅ 已对齐 |
| 用户反馈 (💾) | ✅ 控制台输出 | ✅ System.out.println | ✅ 已对齐 |
| Nudge 禁用（review中） | ✅ interval=0 | ✅ interval=0 | ✅ 已对齐 |

## 差异说明

### 1. 配置路径
- Python: `mem_config.get("nudge_interval", 10)`
- Java: `cfgMgr.getInt("memory.nudge_interval", 10)`

使用点号路径更符合 Java ConfigManager 的设计。

### 2. Review Agent 创建
- Python: 直接构造 AIAgent，设置 `max_iterations=8`
- Java: 加载新配置，通过 `iterationBudget.setMaxIterations(8)` 控制

### 3. 字段可变性
- Python: 所有字段可动态修改
- Java: `memoryManager` 改为非 final，允许 review agent 共享

## 测试建议

1. **配置测试**
   ```bash
   # 验证配置加载
   mvn test -Dtest=ConfigManagerTest
   ```

2. **Nudge 触发测试**
   - 发送 10 条用户消息，验证 memory nudge 触发
   - 执行 10 次工具调用，验证 skill nudge 触发

3. **后台 Review 测试**
   - 触发 combined review（同时满足两个条件）
   - 验证 `💾` 输出和日志

4. **边界测试**
   - interval = 0（禁用 nudge）
   - 工具不存在时的行为
   - 并发安全性

## 文件修改清单

1. ✅ `AIAgent.java` - 核心 nudge 逻辑和后台 review
2. ✅ `IterationBudget.java` - 添加 `setMaxIterations()`
3. ✅ `ConfigManager.java` - 添加 memory/skills 配置

## 编译验证

```bash
cd /root/hermes-agent-java
mvn compile -q
# 结果：编译成功，无错误
```

---

**对齐完成时间**: 2026-04-16
**对齐状态**: ✅ 已完成
**版本**: Java Hermes 与 Python Hermes 自我进化机制功能对齐
