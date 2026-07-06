package com.nousresearch.hermes.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * S6-2: Trajectory → Fine-tune 导出器。
 *
 * <p>对齐原版：将 agent 运行轨迹导出为 ShareGPT 格式训练集。</p>
 *
 * <p>格式：ShareGPT JSON</p>
 * <pre>{@code
 * {
 *   "conversations": [
 *     {"from": "human", "value": "用户的问题"},
 *     {"from": "gpt", "value": "agent 的回答"},
 *     {"from": "tool", "value": "工具返回结果"}
 *   ]
 * }
 * }</pre>
 */
public class FineTuneExporter {
    private static final Logger logger = LoggerFactory.getLogger(FineTuneExporter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /** Compact mapper for JSONL export — one JSON object per line, no pretty printing. */
    private static final ObjectMapper JSONL_MAPPER = new ObjectMapper();

    /**
     * 单条对话记录。
     */
    public record ConversationTurn(String from, String value) {
        public static ConversationTurn human(String v) { return new ConversationTurn("human", v); }
        public static ConversationTurn gpt(String v) { return new ConversationTurn("gpt", v); }
        public static ConversationTurn tool(String v) { return new ConversationTurn("tool", v); }
        public static ConversationTurn system(String v) { return new ConversationTurn("system", v); }
    }

    /**
     * 一条训练样本（一个完整对话）。
     */
    public record TrainingSample(List<ConversationTurn> conversations, Map<String, String> metadata) {
        public TrainingSample(List<ConversationTurn> conversations) {
            this(conversations, Map.of());
        }
    }

    /**
     * 导出训练集到文件。
     *
     * @param samples 训练样本列表
     * @param outputFile 输出文件路径（.jsonl）
     */
    public void export(List<TrainingSample> samples, Path outputFile) throws IOException {
        Files.createDirectories(outputFile.getParent());
        try (var writer = Files.newBufferedWriter(outputFile)) {
            for (TrainingSample sample : samples) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("conversations", sample.conversations().stream()
                    .map(t -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("from", t.from());
                        m.put("value", t.value());
                        return m;
                    })
                    .toList());
                if (!sample.metadata().isEmpty()) {
                    record.put("metadata", sample.metadata());
                }
                writer.write(JSONL_MAPPER.writeValueAsString(record));
                writer.newLine();
            }
        }
        logger.info("Exported {} training samples to {}", samples.size(), outputFile);
    }

    /**
     * 从简单对话列表构建训练样本。
     */
    public static TrainingSample fromConversation(String userMessage, String assistantResponse) {
        return new TrainingSample(List.of(
            ConversationTurn.human(userMessage),
            ConversationTurn.gpt(assistantResponse)
        ));
    }

    /**
     * 从多轮对话构建训练样本。
     */
    public static TrainingSample fromMultiTurn(List<String> userMessages, List<String> assistantResponses) {
        List<ConversationTurn> turns = new ArrayList<>();
        for (int i = 0; i < Math.min(userMessages.size(), assistantResponses.size()); i++) {
            turns.add(ConversationTurn.human(userMessages.get(i)));
            turns.add(ConversationTurn.gpt(assistantResponses.get(i)));
        }
        return new TrainingSample(turns);
    }

    /**
     * 带 tool 调用的训练样本。
     */
    public static TrainingSample withToolCall(String userMessage, String toolCall, String toolResult, String assistantResponse) {
        return new TrainingSample(List.of(
            ConversationTurn.human(userMessage),
            ConversationTurn.gpt(toolCall),
            ConversationTurn.tool(toolResult),
            ConversationTurn.gpt(assistantResponse)
        ));
    }

    /**
     * 统计导出数据概况。
     */
    public static ExportStats computeStats(List<TrainingSample> samples) {
        int totalSamples = samples.size();
        int totalTurns = samples.stream().mapToInt(s -> s.conversations().size()).sum();
        int totalChars = samples.stream()
            .flatMap(s -> s.conversations().stream())
            .mapToInt(t -> t.value().length())
            .sum();
        long humanTurns = samples.stream()
            .flatMap(s -> s.conversations().stream())
            .filter(t -> "human".equals(t.from()))
            .count();
        long gptTurns = samples.stream()
            .flatMap(s -> s.conversations().stream())
            .filter(t -> "gpt".equals(t.from()))
            .count();
        long toolTurns = samples.stream()
            .flatMap(s -> s.conversations().stream())
            .filter(t -> "tool".equals(t.from()))
            .count();
        return new ExportStats(totalSamples, totalTurns, totalChars, humanTurns, gptTurns, toolTurns);
    }

    public record ExportStats(
        int totalSamples, int totalTurns, int totalChars,
        long humanTurns, long gptTurns, long toolTurns
    ) {
        public double avgTurnsPerSample() {
            return totalSamples > 0 ? (double) totalTurns / totalSamples : 0;
        }
    }
}
