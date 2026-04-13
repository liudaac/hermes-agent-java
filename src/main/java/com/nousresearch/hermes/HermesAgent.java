package com.nousresearch.hermes;

import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.gateway.GatewayRunner;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * Hermes Agent Java - Main Entry Point
 */
@Command(
    name = "hermes",
    mixinStandardHelpOptions = true,
    version = "hermes 0.1.0",
    description = "Hermes Agent - Self-improving AI agent with tool calling",
    subcommands = {
        HermesAgent.ChatCommand.class,
        HermesAgent.GatewayCommand.class,
        HermesAgent.ConfigCommand.class
    }
)
public class HermesAgent implements Callable<Integer> {

    @Option(names = {"-p", "--profile"}, description = "Use specific profile")
    private String profile;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new HermesAgent())
            .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                System.err.println("Error: " + ex.getMessage());
                if (System.getenv("HERMES_DEBUG") != null) {
                    ex.printStackTrace();
                }
                return 1;
            })
            .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        return new ChatCommand().call();
    }

    @Command(name = "chat", description = "Start interactive chat session")
    static class ChatCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            try {
                HermesConfig config = HermesConfig.load();
                AIAgent agent = new AIAgent(config);
                agent.runInteractive();
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to start chat: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "gateway", description = "Manage messaging gateway")
    static class GatewayCommand implements Callable<Integer> {
        @Parameters(defaultValue = "run")
        private String action;

        @Override
        public Integer call() {
            try {
                HermesConfig config = HermesConfig.load();
                GatewayRunner gateway = new GatewayRunner(config);
                gateway.runForeground();
                return 0;
            } catch (Exception e) {
                System.err.println("Gateway error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "config", description = "Manage configuration")
    static class ConfigCommand implements Callable<Integer> {
        @Option(names = {"--show", "-s"}, description = "Show current configuration")
        private boolean show;

        @Option(names = {"--edit", "-e"}, description = "Open config in editor")
        private boolean edit;

        @Option(names = {"--set"}, description = "Set a configuration value (key=value)")
        private String setValue;

        @Option(names = {"--wizard", "-w"}, description = "Run setup wizard")
        private boolean wizard;

        @Override
        public Integer call() {
            try {
                if (show) {
                    return showConfig();
                } else if (edit) {
                    return editConfig();
                } else if (setValue != null) {
                    return setConfigValue(setValue);
                } else if (wizard) {
                    return runWizard();
                } else {
                    // Default: show config
                    return showConfig();
                }
            } catch (Exception e) {
                System.err.println("Config error: " + e.getMessage());
                if (System.getenv("HERMES_DEBUG") != null) {
                    e.printStackTrace();
                }
                return 1;
            }
        }

        private int showConfig() {
            try {
                HermesConfig config = HermesConfig.load();
                ConfigPrinter printer = new ConfigPrinter();
                printer.print(config);
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to load config: " + e.getMessage());
                return 1;
            }
        }

        private int editConfig() {
            Path configPath = HermesConfig.getConfigPath();
            String editor = System.getenv("EDITOR");
            if (editor == null) {
                editor = System.getProperty("os.name").toLowerCase().contains("win") ? "notepad" : "nano";
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(editor, configPath.toString());
                pb.inheritIO();
                Process process = pb.start();
                process.waitFor();
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to open editor: " + e.getMessage());
                System.err.println("Config file location: " + configPath);
                return 1;
            }
        }

        private int setConfigValue(String keyValue) {
            int eq = keyValue.indexOf('=');
            if (eq < 0) {
                System.err.println("Invalid format. Use: key=value");
                return 1;
            }
            String key = keyValue.substring(0, eq).trim();
            String value = keyValue.substring(eq + 1).trim();
            try {
                HermesConfig config = HermesConfig.load();
                config.set(key, value);
                config.save();
                System.out.println("✓ Set " + key + " = " + value);
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to set config: " + e.getMessage());
                return 1;
            }
        }

        private int runWizard() {
            System.out.println("╔════════════════════════════════════════════════════════╗");
            System.out.println("║              ⚕ Hermes Setup Wizard                    ║");
            System.out.println("╚════════════════════════════════════════════════════════╝");
            System.out.println();
            
            Scanner scanner = new Scanner(System.in);
            HermesConfig config;
            try {
                config = HermesConfig.load();
            } catch (Exception e) {
                System.err.println("Failed to load config: " + e.getMessage());
                return 1;
            }
            
            // Model provider selection
            System.out.println("◆ Model Provider");
            System.out.println("  1. OpenRouter (recommended - access to many models)");
            System.out.println("  2. OpenAI");
            System.out.println("  3. Anthropic");
            System.out.println("  4. Google (Gemini)");
            System.out.println("  5. Custom endpoint");
            System.out.print("\nSelect provider [1-5]: ");
            
            String choice = scanner.nextLine().trim();
            String provider = switch (choice) {
                case "1" -> "openrouter";
                case "2" -> "openai";
                case "3" -> "anthropic";
                case "4" -> "google";
                default -> "custom";
            };
            
            config.set("model.provider", provider);
            
            // API Key
            System.out.print("\nEnter API key: ");
            String apiKey = scanner.nextLine().trim();
            if (!apiKey.isEmpty()) {
                config.setSecret(provider.toUpperCase() + "_API_KEY", apiKey);
            }
            
            // Model selection
            System.out.print("\nEnter model name (e.g., anthropic/claude-sonnet-4): ");
            String model = scanner.nextLine().trim();
            if (!model.isEmpty()) {
                config.set("model.default", model);
            }
            
            // Display settings
            System.out.println("\n◆ Display Settings");
            System.out.print("Personality [kawaii/calm/professional]: ");
            String personality = scanner.nextLine().trim();
            if (!personality.isEmpty()) {
                config.set("display.personality", personality);
            }
            
            try {
                config.save();
                System.out.println("\n✓ Configuration saved!");
                System.out.println("  Config: " + HermesConfig.getConfigPath());
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to save config: " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * Utility class for printing configuration in a nice format.
     */
    static class ConfigPrinter {
        void print(HermesConfig config) {
            System.out.println();
            System.out.println("╔════════════════════════════════════════════════════════╗");
            System.out.println("║              ⚕ Hermes Configuration                    ║");
            System.out.println("╚════════════════════════════════════════════════════════╝");
            System.out.println();
            
            // Paths
            System.out.println("◆ Paths");
            System.out.println("  Config:       " + HermesConfig.getConfigPath());
            System.out.println("  Secrets:      " + HermesConfig.getEnvPath());
            
            // Model
            System.out.println("\n◆ Model");
            System.out.println("  Provider:     " + config.get("model.provider", "not set"));
            System.out.println("  Model:        " + config.get("model.default", "not set"));
            
            // Display
            System.out.println("\n◆ Display");
            System.out.println("  Personality:  " + config.get("display.personality", "kawaii"));
            
            // Tools
            System.out.println("\n◆ Tools");
            System.out.println("  Enabled:      " + String.join(", ", config.getStringList("toolsets")));
            
            System.out.println();
        }
    }
}