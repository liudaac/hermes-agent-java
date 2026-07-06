package com.nousresearch.hermes.skills;

import com.nousresearch.hermes.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers the /learn slash command.
 *
 * <p>Aligned with the original Python Hermes {@code hermes_cli/commands.py} and
 * {@code hermes_cli/cli_commands_mixin.py}.</p>
 *
 * <p>The /learn command:</p>
 * <ol>
 *   <li>Takes the free-text argument after /learn</li>
 *   <li>Builds a standards-guided prompt via {@link LearnPromptBuilder}</li>
 *   <li>Returns the prompt as a message to be injected into the agent's input queue</li>
 * </ol>
 *
 * <p>The live agent then gathers the described sources with its existing tools
 * and authors the skill via skill_create / skill_write_file.</p>
 */
public class LearnCommandRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(LearnCommandRegistrar.class);

    /**
     * Register the /learn slash command with the plugin manager.
     *
     * @param pluginManager the plugin manager to register with
     */
    public static void register(PluginManager pluginManager) {
        pluginManager.trackSlashCommand(
            "learn",
            LearnCommandRegistrar::handleLearn,
            "Learn a reusable skill from anything you describe (dirs, URLs, this chat, notes)",
            "<what to learn from>",
            "hermes-core"
        );
        logger.info("Registered /learn slash command");
    }

    /**
     * Handle the /learn command.
     *
     * @param args the raw command string (e.g. "/learn /path/to/dir focus on auth")
     * @return a Map with the rewritten prompt to inject into the agent's input
     */
    private static Object handleLearn(String args) {
        // Extract everything after the command word
        String userRequest = extractArgs(args);

        String prompt = LearnPromptBuilder.buildLearnPrompt(userRequest);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "inject");
        result.put("message", prompt);
        result.put("ack", userRequest.isBlank()
            ? "Learning a skill from this conversation…"
            : "Learning a skill from what you described…");
        return result;
    }

    /**
     * Extract the argument portion from a slash command string.
     * e.g. "/learn /path/to/dir focus on auth" → "/path/to/dir focus on auth"
     */
    private static String extractArgs(String input) {
        if (input == null || input.isBlank()) return "";
        // Strip leading "/" and the command word
        String trimmed = input.strip();
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx < 0) return "";
        return trimmed.substring(spaceIdx + 1).strip();
    }
}
