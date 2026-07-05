package com.nousresearch.hermes.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S6-2: FineTuneExporter 测试
 */
class FineTuneExporterTest {

    private FineTuneExporter exporter;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        exporter = new FineTuneExporter();
        tempDir = Files.createTempDirectory("finetune-test");
    }

    // ========================================================================
    // ConversationTurn
    // ========================================================================

    @Nested
    @DisplayName("ConversationTurn")
    class TurnTest {

        @Test
        @DisplayName("human / gpt / tool / system 工厂方法")
        void factoryMethods() {
            FineTuneExporter.ConversationTurn h = FineTuneExporter.ConversationTurn.human("hi");
            FineTuneExporter.ConversationTurn g = FineTuneExporter.ConversationTurn.gpt("hello");
            FineTuneExporter.ConversationTurn t = FineTuneExporter.ConversationTurn.tool("result");
            FineTuneExporter.ConversationTurn s = FineTuneExporter.ConversationTurn.system("sys");

            assertEquals("human", h.from());
            assertEquals("gpt", g.from());
            assertEquals("tool", t.from());
            assertEquals("system", s.from());
        }
    }

    // ========================================================================
    // 工厂方法构建样本
    // ========================================================================

    @Nested
    @DisplayName("样本构建")
    class SampleBuilderTest {

        @Test
        @DisplayName("fromConversation 单轮")
        void fromConversation() {
            FineTuneExporter.TrainingSample s = FineTuneExporter.fromConversation("你好", "你好！");
            assertEquals(2, s.conversations().size());
            assertEquals("human", s.conversations().get(0).from());
            assertEquals("gpt", s.conversations().get(1).from());
        }

        @Test
        @DisplayName("fromMultiTurn 多轮")
        void fromMultiTurn() {
            FineTuneExporter.TrainingSample s = FineTuneExporter.fromMultiTurn(
                List.of("问题1", "问题2"),
                List.of("回答1", "回答2")
            );
            assertEquals(4, s.conversations().size());
            assertEquals("human", s.conversations().get(0).from());
            assertEquals("gpt", s.conversations().get(1).from());
            assertEquals("human", s.conversations().get(2).from());
            assertEquals("gpt", s.conversations().get(3).from());
        }

        @Test
        @DisplayName("withToolCall 带 tool 调用")
        void withToolCall() {
            FineTuneExporter.TrainingSample s = FineTuneExporter.withToolCall(
                "搜索天气", "call weather_api", "晴 25°C", "今天晴天 25 度");
            assertEquals(4, s.conversations().size());
            assertEquals("tool", s.conversations().get(2).from());
        }

        @Test
        @DisplayName("fromMultiTurn 不等长 → 取较小")
        void unequalLengths() {
            FineTuneExporter.TrainingSample s = FineTuneExporter.fromMultiTurn(
                List.of("q1", "q2", "q3"),
                List.of("a1")
            );
            assertEquals(2, s.conversations().size());
        }
    }

    // ========================================================================
    // export 文件写入
    // ========================================================================

    @Nested
    @DisplayName("export 文件写入")
    class ExportTest {

        @Test
        @DisplayName("导出单条样本到 JSONL")
        void exportSingle() throws Exception {
            Path file = tempDir.resolve("train.jsonl");
            List<FineTuneExporter.TrainingSample> samples = List.of(
                FineTuneExporter.fromConversation("你好", "你好！")
            );
            exporter.export(samples, file);

            assertTrue(Files.exists(file));
            List<String> lines = Files.readAllLines(file);
            assertEquals(1, lines.size());
            assertTrue(lines.get(0).contains("你好"));
            assertTrue(lines.get(0).contains("conversations"));
        }

        @Test
        @DisplayName("导出多条样本")
        void exportMultiple() throws Exception {
            Path file = tempDir.resolve("train.jsonl");
            List<FineTuneExporter.TrainingSample> samples = List.of(
                FineTuneExporter.fromConversation("q1", "a1"),
                FineTuneExporter.fromConversation("q2", "a2"),
                FineTuneExporter.fromConversation("q3", "a3")
            );
            exporter.export(samples, file);

            List<String> lines = Files.readAllLines(file);
            assertEquals(3, lines.size());
        }

        @Test
        @DisplayName("导出带 metadata")
        void exportWithMetadata() throws Exception {
            Path file = tempDir.resolve("train.jsonl");
            FineTuneExporter.TrainingSample sample = new FineTuneExporter.TrainingSample(
                List.of(FineTuneExporter.ConversationTurn.human("hi"), FineTuneExporter.ConversationTurn.gpt("hello")),
                Map.of("source", "test", "quality", "high")
            );
            exporter.export(List.of(sample), file);

            String content = Files.readString(file);
            assertTrue(content.contains("metadata"));
            assertTrue(content.contains("test"));
        }

        @Test
        @DisplayName("导出到不存在的目录 → 自动创建")
        void exportToNewDir() throws Exception {
            Path file = tempDir.resolve("subdir/train.jsonl");
            exporter.export(List.of(FineTuneExporter.fromConversation("q", "a")), file);
            assertTrue(Files.exists(file));
        }

        @Test
        @DisplayName("空列表导出 → 空文件")
        void exportEmpty() throws Exception {
            Path file = tempDir.resolve("empty.jsonl");
            exporter.export(List.of(), file);
            assertTrue(Files.exists(file));
            assertEquals(0, Files.readAllLines(file).size());
        }
    }

    // ========================================================================
    // computeStats
    // ========================================================================

    @Nested
    @DisplayName("computeStats")
    class StatsTest {

        @Test
        @DisplayName("正确统计")
        void stats() {
            List<FineTuneExporter.TrainingSample> samples = List.of(
                FineTuneExporter.fromConversation("q1", "a1"),
                FineTuneExporter.withToolCall("q2", "call", "result", "a2")
            );
            FineTuneExporter.ExportStats stats = FineTuneExporter.computeStats(samples);
            assertEquals(2, stats.totalSamples());
            assertEquals(6, stats.totalTurns()); // 2 + 4
            assertEquals(2, stats.humanTurns());
            assertEquals(3, stats.gptTurns()); // 1 + 2
            assertEquals(1, stats.toolTurns());
            assertTrue(stats.totalChars() > 0);
        }

        @Test
        @DisplayName("avgTurnsPerSample")
        void avg() {
            List<FineTuneExporter.TrainingSample> samples = List.of(
                FineTuneExporter.fromConversation("q1", "a1"),   // 2 turns
                FineTuneExporter.fromMultiTurn(List.of("a","b"), List.of("c","d")) // 4 turns
            );
            FineTuneExporter.ExportStats stats = FineTuneExporter.computeStats(samples);
            assertEquals(3.0, stats.avgTurnsPerSample(), 0.01); // (2+4)/2
        }

        @Test
        @DisplayName("空列表 → 全零")
        void empty() {
            FineTuneExporter.ExportStats stats = FineTuneExporter.computeStats(List.of());
            assertEquals(0, stats.totalSamples());
            assertEquals(0, stats.totalTurns());
            assertEquals(0, stats.avgTurnsPerSample(), 0.01);
        }
    }
}
