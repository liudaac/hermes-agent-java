# Hermes 插件化架构分析与 Java 版移植建议

> 基于原版 Hermes (commit ~v2026.5.29) 插件系统深度分析

---

## 一、原版架构全景

原版从"硬编码 if/elif 链"彻底转向了**声明式插件系统**。核心思想：

> **Host 提供注册表 + 上下文，Plugin 自描述自注册，配置驱动激活。**

### 1.1 插件来源（四层优先级，后覆盖前）

| 优先级 | 来源 | 路径 | 用途 |
|--------|------|------|------|
| 1 | Bundled | `<repo>/plugins/` | 随发行版附带，backend/platform 类自动加载 |
| 2 | User | `~/.hermes/plugins/` | 用户安装，需显式启用 |
| 3 | Project | `./.hermes/plugins/` | 项目级插件（需 `HERMES_ENABLE_PROJECT_PLUGINS=1`）|
| 4 | Pip | `importlib.metadata` entry-points | 第三方 pip 包，组名 `hermes_agent.plugins` |

### 1.2 目录布局（支持两种结构）

```
# Flat — 独立插件
plugins/disk-cleanup/
  plugin.yaml
  __init__.py

# Category — 同类后端聚合
plugins/image_gen/
  openai/
    plugin.yaml
    __init__.py
  fal/
    plugin.yaml
    __init__.py
```

Key 推导规则：`prefix/plugin_dir_name`，如 `image_gen/openai`。

### 1.3 plugin.yaml 清单格式

```yaml
name: discord-platform          # 逻辑名
label: Discord                  # 显示名
kind: platform                  # standalone | backend | exclusive | platform | model-provider
version: 1.0.0
description: "..."
author: NousResearch
requires_env:                   # setup 向导用
  - name: DISCORD_BOT_TOKEN
    description: "Discord bot token"
    prompt: "Discord bot token"
    url: "https://discord.com/developers/applications"
    password: true
optional_env:
  - name: DISCORD_ALLOWED_USERS
    description: "..."
```

### 1.4 插件入口契约

每个插件目录必须有 `__init__.py`，且暴露 `register(ctx)` 函数：

```python
def register(ctx: PluginContext) -> None:
    ctx.register_platform(...)      # 网关平台适配器
    ctx.register_tool(...)          # 工具注册
    ctx.register_hook(...)          # 生命周期钩子
    ctx.register_cli_command(...)   # CLI 子命令
    ctx.register_command(...)       # 会话内 slash 命令
    ctx.register_image_gen_provider(...)   # 图像生成后端
    ctx.register_browser_provider(...)     # 浏览器后端
    ctx.register_tts_provider(...)         # TTS 后端
    ctx.register_transcription_provider(...)  # STT 后端
    ctx.register_web_search_provider(...)     # 搜索后端
    ctx.register_dashboard_auth_provider(...) # Dashboard 鉴权
    ctx.register_context_engine(...)          # 上下文引擎
    ctx.register_auxiliary_task(...)          # 辅助 LLM 任务
```

---

## 二、核心机制详解

### 2.1 发现流程（PluginManager.discover_and_load）

```
扫描 bundled 目录 → 扫描 user 目录 → 扫描 project 目录 → 扫描 entry-points
    ↓
解析 plugin.yaml → 构建 PluginManifest（含 kind/source/key）
    ↓
去重（后覆盖前）→ 按 kind + source 决定加载策略
    ↓
调用 _load_plugin() → import __init__.py → 执行 register(ctx)
```

**加载策略矩阵：**

| kind | source | 加载行为 |
|------|--------|----------|
| `backend` / `platform` | bundled | **自动加载**（随发行版，开箱即用）|
| `exclusive` | any | **跳过**（由专属发现系统处理，如 memory provider）|
| `model-provider` | any | **跳过**（由 `providers/__init__.py` 懒加载）|
| `standalone` / `backend` / `platform` | user/project/entrypoint | **需显式启用**（`plugins.enabled` 配置）|

### 2.2 PluginContext — 插件与 Host 的唯一交互面

`PluginContext` 是**受控门面**，插件无法直接访问 Host 内部。它提供：

1. **注册能力**：工具、平台、provider、钩子、命令
2. **Host 托管 LLM**：`ctx.llm` → `PluginLlm`（插件无需自带 API key）
3. **消息注入**：`ctx.inject_message()`（外部事件驱动对话）
4. **工具调度**：`ctx.dispatch_tool()`（插件 slash 命令调用内置工具）

**关键设计：所有注册都是"加入注册表"，而非直接修改 Host 状态。**

### 2.3 注册表体系（多维度、解耦）

原版不是单一注册表，而是**按领域拆分**的多个注册表：

| 注册表 | 负责 | 查询方 |
|--------|------|--------|
`PlatformRegistry` | 平台适配器 | Gateway 启动时 `create_adapter()` |
`ToolRegistry` | 工具定义 + handler | Agent 工具调用循环 |
`ImageGenRegistry` | 图像生成后端 | `image_generate` 工具 |
`BrowserRegistry` | 浏览器后端 | `browser_*` 工具 |
`TTSRegistry` | TTS 后端 | `text_to_speech` 工具 |
`TranscriptionRegistry` | STT 后端 | `transcribe_audio` 工具 |
`WebSearchRegistry` | 搜索/提取后端 | `web_search` / `web_extract` 工具 |
`VideoGenRegistry` | 视频生成后端 | `video_generate` 工具 |
`DashboardAuthRegistry` | Dashboard 鉴权 | Web Server 启动时 |

**每个注册表模式相同：**
- `register(provider)` — 插件注册
- 按 `name` 查找 — 配置 `provider: xxx` 匹配
- **内置优先** — 同名时内置实现覆盖插件（防恶意替换）

### 2.4 生命周期钩子系统

```python
VALID_HOOKS = {
    "pre_tool_call",          # 工具执行前拦截（可 block）
    "post_tool_call",         # 工具执行后观察
    "transform_tool_result",  # 修改工具返回结果
    "transform_terminal_output",  # 修改终端输出
    "transform_llm_output",   # 修改 LLM 最终输出（如语气转换）
    "pre_llm_call",           # LLM 请求前注入上下文
    "post_llm_call",          # LLM 响应后观察
    "pre_api_request",        # API 请求前修改参数
    "post_api_request",       # API 请求后观察
    "on_session_start",       # 会话开始
    "on_session_end",         # 会话结束
    "on_session_finalize",    # 会话最终化
    "on_session_reset",       # 会话重置
    "subagent_stop",          # 子 Agent 停止
    "pre_gateway_dispatch",   # 网关消息分发前拦截/改写
    "pre_approval_request",   # 审批请求前通知
    "post_approval_response", # 审批响应后通知
}
```

**调用语义：**
- 每个钩子收集所有插件的返回值（非 None）
- 插件异常被捕获，**不会破坏核心循环**
- `pre_tool_call` 支持返回 `{"action": "block", "message": "..."}` 拦截
- `pre_gateway_dispatch` 支持 `{"action": "skip"}` / `{"action": "rewrite", "text": "..."}`

### 2.5 平台适配器注册（以 Discord 为例）

```python
# plugins/platforms/discord/__init__.py
from .adapter import register

# adapter.py 内：
from gateway.platform_registry import platform_registry, PlatformEntry

def register(ctx):
    platform_registry.register(PlatformEntry(
        name="discord",
        label="Discord",
        adapter_factory=lambda cfg: DiscordAdapter(cfg),
        check_fn=lambda: HAS_DISCORD,
        validate_config=lambda cfg: bool(cfg.extra.get("token")),
        required_env=["DISCORD_BOT_TOKEN"],
        emoji="💬",
        platform_hint="You are on Discord. Do not use markdown.",
        allowed_users_env="DISCORD_ALLOWED_USERS",
        allow_all_env="DISCORD_ALLOW_ALL_USERS",
        cron_deliver_env_var="DISCORD_HOME_CHANNEL",
        standalone_sender_fn=standalone_send,  # Cron 跨进程投递
    ))
```

**PlatformEntry 设计亮点：**
- `adapter_factory` 而非类引用 — 支持自定义初始化
- `check_fn` + `validate_config` — 依赖检查与配置验证分离
- `is_connected` — 运行时连通性检测
- `standalone_sender_fn` — Cron 独立进程投递（不依赖 Gateway 内存中的 adapter）
- `env_enablement_fn` / `apply_yaml_config_fn` — 环境变量与 YAML 配置的桥接

---

## 三、对 Java 版的移植建议

### 3.1 总体策略

Java 版当前是"传统模块化"（Maven 多模块 + Spring Boot 自动配置），建议**渐进式演进**：

1. **Phase 1**：引入插件目录扫描 + `plugin.yaml` 解析（对标原版发现层）
2. **Phase 2**：将现有内置实现（Feishu、QQBot、OpenAI、Browser 等）迁移为"内置插件"
3. **Phase 3**：开放用户插件目录，支持外部 jar 动态加载

### 3.2 Java 版插件发现机制

```java
// 扫描路径（按优先级）
Path bundledDir = Paths.get(System.getProperty("hermes.home"), "plugins");
Path userDir = Paths.get(System.getProperty("user.home"), ".hermes", "plugins");

// 目录结构同原版（Flat / Category）
plugins/
  feishu/
    plugin.yaml
    Plugin.class / 或独立 jar
  model-providers/
    openai/
      plugin.yaml
      ...
```

**推荐技术方案：**
- **目录插件**：Java 的 `ServiceLoader` 或自定义类加载器
- **Jar 插件**：`URLClassLoader` 隔离加载 + `META-INF/services/` 声明入口
- **配置解析**：Jackson / SnakeYAML 解析 `plugin.yaml`

### 3.3 Java 版注册表设计

```java
// 通用注册表接口
public interface Registry<K, V> {
    void register(K key, V entry);
    void unregister(K key);
    V get(K key);
    List<V> listAll();
}

// 平台适配器注册表
public class PlatformRegistry implements Registry<String, PlatformEntry> {
    private final Map<String, PlatformEntry> entries = new ConcurrentHashMap<>();
    
    public PlatformAdapter createAdapter(String name, PlatformConfig config) {
        PlatformEntry entry = entries.get(name);
        if (entry == null) return null;
        if (!entry.checkFn().get()) {
            log.warn("Platform '{}' requirements not met", entry.getLabel());
            return null;
        }
        return entry.getAdapterFactory().apply(config);
    }
}

// Provider 注册表（图像/浏览器/TTS/搜索等统一模式）
public class ProviderRegistry<T extends NamedProvider> {
    private final Map<String, T> providers = new ConcurrentHashMap<>();
    
    public void register(T provider) {
        // 内置优先：如果已有内置实现，拒绝插件覆盖（或 warn）
        providers.put(provider.getName(), provider);
    }
    
    public T resolve(String name) {
        return providers.get(name);
    }
}
```

### 3.4 Java 版 PluginContext

```java
public interface PluginContext {
    // 工具注册
    void registerTool(ToolDefinition tool, ToolHandler handler);
    
    // 平台注册
    void registerPlatform(PlatformEntry entry);
    
    // Provider 注册（统一接口，按类型分发到各自注册表）
    void registerProvider(ProviderType type, Provider provider);
    
    // 生命周期钩子
    void registerHook(HookType type, HookCallback callback);
    
    // CLI / Slash 命令
    void registerCliCommand(String name, CliCommand command);
    void registerSlashCommand(String name, SlashCommand command);
    
    // Host 托管 LLM（插件无需自带 key）
    PluginLlm getLlm();
    
    // 消息注入（外部驱动对话）
    boolean injectMessage(String content, String role);
    
    // 工具调度
    String dispatchTool(String toolName, Map<String, Object> args);
}
```

### 3.5 Java 版生命周期钩子

```java
public enum HookType {
    PRE_TOOL_CALL,
    POST_TOOL_CALL,
    PRE_LLM_CALL,
    POST_LLM_CALL,
    TRANSFORM_TOOL_RESULT,
    TRANSFORM_LLM_OUTPUT,
    ON_SESSION_START,
    ON_SESSION_END,
    PRE_GATEWAY_DISPATCH
}

public interface HookCallback {
    Object invoke(Map<String, Object> context);
}

// Hook 引擎（核心调用点）
public class HookEngine {
    private final Map<HookType, List<HookCallback>> hooks = new HashMap<>();
    
    public List<Object> invoke(HookType type, Map<String, Object> context) {
        List<Object> results = new ArrayList<>();
        for (HookCallback cb : hooks.getOrDefault(type, List.of())) {
            try {
                Object ret = cb.invoke(context);
                if (ret != null) results.add(ret);
            } catch (Exception e) {
                log.warn("Hook {} callback failed: {}", type, e.getMessage());
                // 不抛异常，不破坏核心循环
            }
        }
        return results;
    }
    
    // 特殊处理：pre_tool_call block 语义
    public Optional<String> checkToolBlocked(String toolName, Map<String, Object> args) {
        List<Object> results = invoke(HookType.PRE_TOOL_CALL, Map.of(
            "toolName", toolName, "args", args
        ));
        for (Object r : results) {
            if (r instanceof Map<?,?> m && "block".equals(m.get("action"))) {
                return Optional.of((String) m.get("message"));
            }
        }
        return Optional.empty();
    }
}
```

### 3.6 Java 版加载策略实现

```java
public class PluginManager {
    
    public void discoverAndLoad() {
        List<PluginManifest> manifests = new ArrayList<>();
        
        // 1. 扫描 bundled
        manifests.addAll(scanDirectory(bundledDir, Source.BUNDLED, 
            Set.of("memory", "context_engine", "model-providers")));
        manifests.addAll(scanDirectory(bundledDir.resolve("platforms"), Source.BUNDLED));
        
        // 2. 扫描 user
        manifests.addAll(scanDirectory(userDir, Source.USER));
        
        // 3. 去重（后覆盖前）
        Map<String, PluginManifest> winners = new LinkedHashMap<>();
        for (PluginManifest m : manifests) {
            winners.put(m.getKey(), m);
        }
        
        // 4. 加载决策
        Set<String> disabled = config.getDisabledPlugins();
        Set<String> enabled = config.getEnabledPlugins(); // null = opt-in默认
        
        for (PluginManifest m : winners.values()) {
            String key = m.getKey();
            
            if (disabled.contains(key)) {
                recordSkipped(key, "disabled");
                continue;
            }
            
            if (m.getKind() == PluginKind.EXCLUSIVE) {
                recordSkipped(key, "exclusive — handled by category discovery");
                continue;
            }
            
            if (m.getSource() == Source.BUNDLED && 
                (m.getKind() == PluginKind.BACKEND || m.getKind() == PluginKind.PLATFORM)) {
                loadPlugin(m);  // 自动加载
                continue;
            }
            
            if (enabled != null && !enabled.contains(key)) {
                recordSkipped(key, "not enabled");
                continue;
            }
            
            loadPlugin(m);
        }
    }
    
    private void loadPlugin(PluginManifest manifest) {
        try {
            // 类加载器隔离（可选）
            ClassLoader pluginLoader = createPluginClassLoader(manifest.getPath());
            Class<?> clazz = pluginLoader.loadClass(manifest.getEntryClass());
            
            // 实例化并调用 register
            Plugin plugin = (Plugin) clazz.getDeclaredConstructor().newInstance();
            PluginContext ctx = new PluginContextImpl(manifest, this);
            plugin.register(ctx);
            
            loadedPlugins.put(manifest.getKey(), new LoadedPlugin(manifest, ctx));
        } catch (Exception e) {
            log.error("Failed to load plugin {}: {}", manifest.getName(), e.getMessage());
            failedPlugins.put(manifest.getKey(), e.getMessage());
        }
    }
}

// 插件契约接口
public interface Plugin {
    void register(PluginContext ctx);
}
```

### 3.7 内置实现迁移路线图

| 当前模块 | 目标形态 | 说明 |
|----------|----------|------|
| `gateway/platform/feishu` | `plugins/platforms/feishu/` | 平台适配器插件 |
| `gateway/platform/qqbot` | `plugins/platforms/qqbot/` | 平台适配器插件 |
| `agent/transport/OpenAITransport` | `plugins/model-providers/openai/` | 模型 Provider 插件 |
| `agent/tool/BrowserCdpTool` | `plugins/browser/cdp/` | 浏览器后端插件 |
| `agent/tts/` | `plugins/tts/edge/` 等 | TTS 后端插件 |
| `agent/tool/WebSearchTool` | `plugins/web/tavily/` 等 | 搜索后端插件 |
| `ContextCompressor` | 保留 + 支持 `ctx.registerContextEngine()` | 上下文引擎可插拔 |

### 3.8 关键设计原则（复用原版精华）

1. **失败隔离**：插件加载失败、钩子回调异常，只记录日志，不中断 Host
2. **内置优先**：同名 Provider 内置实现优先于插件，防止恶意替换
3. **配置驱动**：`plugins.enabled` / `plugins.disabled` 控制加载，默认 opt-in
4. **懒加载**：Model Provider 不提前加载，首次 `get_provider()` 时才初始化
5. **Standalone Sender**：平台适配器支持独立发送，Cron 等异步任务不依赖 Gateway 进程
6. **Namespace 隔离**：Category 布局避免命名冲突（`image_gen/openai` vs `tts/openai`）
7. **Host 托管 LLM**：插件通过 `ctx.llm` 使用用户配置的模型，不自带 API key

---

## 四、快速启动：Java 版 Phase 1 实现清单

1. [ ] 定义 `PluginManifest` / `PluginKind` / `Source` 数据类
2. [ ] 实现 `PluginYamlParser`（SnakeYAML 解析 plugin.yaml）
3. [ ] 实现 `PluginDirectoryScanner`（支持 Flat + Category 布局）
4. [ ] 定义 `Plugin` 接口（`void register(PluginContext ctx)`）
5. [ ] 实现 `PluginContext` 门面（先支持 `registerTool`、`registerHook`）
6. [ ] 实现 `HookEngine`（收集返回值、异常隔离、block 语义）
7. [ ] 将现有一个工具（如 WebSearch）改造为"内置插件"验证流程
8. [ ] 添加 `PluginManager.discoverAndLoad()` 到 Spring Boot 启动流程

---

## 五、参考文件（原版）

| 文件 | 作用 |
|------|------|
| `hermes_cli/plugins.py` | 插件系统核心（发现、加载、上下文、钩子） |
| `gateway/platform_registry.py` | 平台适配器注册表 |
| `tools/registry.py` | 工具注册表 |
| `agent/image_gen_registry.py` | 图像生成 Provider 注册表 |
| `agent/browser_registry.py` | 浏览器 Provider 注册表 |
| `agent/tts_registry.py` | TTS Provider 注册表 |
| `agent/transcription_registry.py` | STT Provider 注册表 |
| `agent/web_search_registry.py` | 搜索 Provider 注册表 |
| `plugins/platforms/discord/` | 平台插件示例 |
| `plugins/model-providers/deepseek/` | 模型 Provider 插件示例 |
| `plugins/browser/firecrawl/` | 后端插件示例 |
