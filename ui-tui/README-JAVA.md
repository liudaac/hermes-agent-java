# ⚠️ 终端 UI 未适配 Java 版后端

`ui-tui/` 目录下的终端 UI 代码（React + Ink）当前 **无法在 Java 版 Hermes 后端上运行**。

## 原因

ui-tui 的架构假设后端是一个 Python gateway 进程，通过 **JSON-RPC over stdio** 通信：

```
TUI (Node.js/TypeScript)
  ↓ JSON-RPC over stdio
Python Gateway (tui_gateway.entry)
  ↓ 内部调用
Hermes Python Agent
```

Java 版后端没有 `tui_gateway` 模块，也不提供 stdio JSON-RPC 接口。Java 当前的交互入口是 `HermesAgentV2.runInteractive()` —— 一个基于 `Scanner` 的最简命令行 REPL，不具备 ui-tui 所需的以下能力：

| ui-tui 需要 | Java 后端现状 |
|---|---|
| `session.create/resume/close/branch/compress/steer/undo` | 无对应 API |
| `config.get/set` | `ConfigManager` 内部 API，未暴露 |
| `prompt.submit/background/btw` | `interactiveAgent.processMessage()` 单入口 |
| `tools.configure`, `skills.manage` | `ToolRegistry` 内部 API，未暴露 |
| `approval/clarify/sudo/secret` 交互暂停 | `ApprovalSystem` 内部 API，未暴露 |
| `shell.exec`, `terminal.resize` | 无对应接口 |
| `voice.toggle/record` | 无对应接口 |
| 实时事件流 (`message.delta`, `tool.progress`) | 无 stdio 事件推送 |

## 当前 Java 版的交互方式

- **Web Dashboard**: React + Vite，通过 `DashboardServer` HTTP API 访问（主线）
- **Gateway 模式**: 接收 Telegram/Discord/Feishu/QQ 等平台消息
- **REPL 模式**: `HermesAgentV2.runInteractive()` 简易命令行

## 未来可能的适配方向

1. **Stdio Gateway 层**: 在 Java 后端新增一个 `StdioGatewayServer`，从 stdin 读取 JSON-RPC 请求，映射到 Java 内部 API，向 stdout 写回响应/事件。需要实现 ~60 个 RPC 方法及复杂交互流程（如审批暂停/恢复）。
2. **HTTP/WS 传输层**: 扩展 `DashboardServer` 或新建 WebSocket 网关，提供 REST/WS API 供 TUI 调用，替换当前的 stdio 传输。

两种方案的工作量均不小，建议待 Java 后端 API 稳定后再投入开发。

---

*Last updated: 2026-05-27*
