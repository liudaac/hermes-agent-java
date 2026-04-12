package com.nousresearch.hermes;

import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.gateway.GatewayRunner;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
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
        @Override
        public Integer call() {
            System.out.println("Config command - TODO");
            return 0;
        }
    }
}
