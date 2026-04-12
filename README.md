# Hermes Agent Java

Java implementation of Hermes Agent - a self-improving AI agent with tool calling capabilities.

## Project Structure

```
hermes-agent-java/
├── pom.xml                          # Maven configuration
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/nousresearch/hermes/
│   │   │       ├── HermesAgent.java           # Main entry point
│   │   │       ├── agent/
│   │   │       │   ├── AIAgent.java           # Core agent implementation
│   │   │       │   ├── ConversationLoop.java  # Conversation management
│   │   │       │   ├── IterationBudget.java   # Iteration tracking
│   │   │       │   ├── PromptBuilder.java     # System prompt assembly
│   │   │       │   ├── MemoryManager.java     # Memory management
│   │   │       │   └── ContextCompressor.java # Context compression
│   │   │       ├── gateway/
│   │   │       │   ├── GatewayRunner.java     # Gateway entry point
│   │   │       │   ├── GatewayConfig.java     # Gateway configuration
│   │   │       │   └── platforms/             # Platform adapters
│   │   │       │       ├── PlatformAdapter.java
│   │   │       │       ├── TelegramAdapter.java
│   │   │       │       ├── DiscordAdapter.java
│   │   │       │       └── FeishuAdapter.java
│   │   │       ├── tools/
│   │   │       │   ├── ToolRegistry.java      # Tool registration
│   │   │       │   ├── ToolEntry.java         # Tool metadata
│   │   │       │   ├── ToolDispatcher.java    # Tool execution
│   │   │       │   └── impl/                  # Tool implementations
│   │   │       │       ├── WebSearchTool.java
│   │   │       │       ├── TerminalTool.java
│   │   │       │       ├── FileTool.java
│   │   │       │       └── BrowserTool.java
│   │   │       ├── model/
│   │   │       │   ├── ModelClient.java       # LLM client interface
│   │   │       │   ├── OpenAIClient.java      # OpenAI-compatible client
│   │   │       │   ├── ModelMessage.java      # Message types
│   │   │       │   └── ToolCall.java          # Tool call structure
│   │   │       ├── config/
│   │   │       │   ├── HermesConfig.java      # Configuration management
│   │   │       │   └── Constants.java         # Constants
│   │   │       └── util/
│   │   │           ├── SafeWriter.java        # Safe stdio wrapper
│   │   │           └── JsonUtils.java         # JSON utilities
│   │   └── resources/
│   │       └── default-config.yaml
│   └── test/
└── scripts/
    └── install.sh
```

## Features

- **Multi-Model Support**: OpenAI, Anthropic, OpenRouter, and local endpoints
- **Tool System**: 40+ built-in tools with MCP protocol support
- **Memory System**: Persistent memory across sessions
- **Gateway**: Multi-platform messaging (Telegram, Discord, Slack, Feishu)
- **Skills System**: Self-improving skills from experience

## Quick Start

```bash
# Build
mvn clean package

# Run CLI
java -jar target/hermes-agent-java-0.1.0.jar

# Run Gateway
java -jar target/hermes-agent-java-0.1.0.jar gateway
```

## Configuration

Configuration is loaded from `~/.hermes/config.yaml`:

```yaml
model:
  provider: openrouter
  model: anthropic/claude-3.5-sonnet
  api_key: ${OPENROUTER_API_KEY}

tools:
  enabled:
    - web_search
    - terminal
    - file_operations

gateway:
  enabled_platforms:
    - telegram
    - feishu
```

## License

MIT License - See LICENSE file
