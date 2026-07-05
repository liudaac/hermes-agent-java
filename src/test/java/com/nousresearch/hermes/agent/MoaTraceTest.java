package com.nousresearch.hermes.agent;

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
 * S2-3: MOA trace JSONL 测试
 */
class MoaTraceTest {

    // ========================================================================
    // MoaTraceRecord
    // ========================================================================

    @Nested
    @DisplayName("MoaTraceRecord")
    class RecordTest {

        @Test
        @DisplayName("基本构建")
        void basicBuild() {
            MoaTraceRecord r = MoaTraceRecord.builder("trace-1", "turn-1")
                .agentId("agent-1")
                .role("agent")
                .model("claude-3.5-sonnet")
                .tokens(1500)
                .durationMs(2300)
                .toolCalls(List.of("web_search", "file_read"))
                .metadata(Map.of("channel", "feishu"))
                .build();

            assertEquals("trace-1", r.traceId());
            assertEquals("turn-1", r.turnId());
            assertEquals("agent-1", r.agentId());
            assertEquals("agent", r.role());
            assertEquals("claude-3.5-sonnet", r.model());
            assertEquals(1500, r.tokens());
            assertEquals(2300, r.durationMs());
            assertEquals(2, r.toolCalls().size());
            assertEquals("web_search", r.toolCalls().get(0));
            assertEquals("feishu", r.metadata().get("channel"));
        }

        @Test
        @DisplayName("默认值")
        void defaults() {
            MoaTraceRecord r = MoaTraceRecord.builder("t1", "turn-1").build();
            assertEquals("default", r.agentId());
            assertEquals("agent", r.role());
            assertEquals(0, r.tokens());
            assertEquals(0, r.durationMs());
            assertTrue(r.toolCalls().isEmpty());
            assertTrue(r.metadata().isEmpty());
        }

        @Test
        @DisplayName("null toolCalls → 空列表")
        void nullToolCalls() {
            MoaTraceRecord r = MoaTraceRecord.builder("t1", "turn-1")
                .toolCalls(null)
                .build();
            assertTrue(r.toolCalls().isEmpty());
        }

        @Test
        @DisplayName("null metadata → 空_map")
        void nullMetadata() {
            MoaTraceRecord r = MoaTraceRecord.builder("t1", "turn-1")
                .metadata(null)
                .build();
            assertTrue(r.metadata().isEmpty());
        }

        @Test
        @DisplayName("toolCalls 不可变")
        void toolCallsImmutable() {
            MoaTraceRecord r = MoaTraceRecord.builder("t1", "turn-1")
                .toolCalls(List.of("a", "b"))
                .build();
            assertThrows(UnsupportedOperationException.class, () -> r.toolCalls().add("c"));
        }
    }

    // ========================================================================
    // MoaTraceWriter
    // ========================================================================

    @Nested
    @DisplayName("MoaTraceWriter")
    class WriterTest {

        private Path tempDir;

        @BeforeEach
        void setUp() throws Exception {
            tempDir = Files.createTempDirectory("moa-trace-test");
        }

        @Test
        @DisplayName("enabled=true 时写入 JSONL 文件")
        void writeEnabled() throws Exception {
            MoaTraceWriter writer = new MoaTraceWriter(tempDir, true);
            MoaTraceRecord r = MoaTraceRecord.builder("trace-1", "turn-1")
                .agentId("agent-1")
                .model("gpt-4o")
                .tokens(500)
                .durationMs(100)
                .toolCalls(List.of("web_search"))
                .build();

            writer.write("tenant-1", r);

            Path file = writer.resolveTraceFile("tenant-1");
            assertTrue(Files.exists(file));
            String content = Files.readString(file);
            assertTrue(content.contains("trace-1"));
            assertTrue(content.contains("turn-1"));
            assertTrue(content.contains("agent-1"));
            assertTrue(content.contains("gpt-4o"));
            assertTrue(content.contains("web_search"));
            assertTrue(content.endsWith("\n"));
        }

        @Test
        @DisplayName("enabled=false 时不写文件")
        void writeDisabled() {
            MoaTraceWriter writer = new MoaTraceWriter(tempDir, false);
            MoaTraceRecord r = MoaTraceRecord.builder("t1", "turn-1").build();
            writer.write("tenant-1", r);

            Path file = writer.resolveTraceFile("tenant-1");
            assertFalse(Files.exists(file));
        }

        @Test
        @DisplayName("多 agent 写同一文件")
        void multipleAgentsSameFile() throws Exception {
            MoaTraceWriter writer = new MoaTraceWriter(tempDir, true);

            writer.write("tenant-1", MoaTraceRecord.builder("trace-1", "turn-1")
                .agentId("agent-A").role("agent").build());
            writer.write("tenant-1", MoaTraceRecord.builder("trace-1", "turn-2")
                .agentId("agent-B").role("subagent").build());
            writer.write("tenant-1", MoaTraceRecord.builder("trace-1", "turn-3")
                .agentId("agent-A").role("agent").build());

            Path file = writer.resolveTraceFile("tenant-1");
            List<String> lines = Files.readAllLines(file);
            assertEquals(3, lines.size());
            assertTrue(lines.get(0).contains("agent-A"));
            assertTrue(lines.get(1).contains("agent-B"));
            assertTrue(lines.get(2).contains("agent-A"));
        }

        @Test
        @DisplayName("不同租户写不同文件")
        void differentTenantsDifferentFiles() {
            MoaTraceWriter writer = new MoaTraceWriter(tempDir, true);
            writer.write("tenant-A", MoaTraceRecord.builder("t1", "turn-1").build());
            writer.write("tenant-B", MoaTraceRecord.builder("t2", "turn-1").build());

            Path fileA = writer.resolveTraceFile("tenant-A");
            Path fileB = writer.resolveTraceFile("tenant-B");
            assertNotEquals(fileA, fileB);
            assertTrue(Files.exists(fileA));
            assertTrue(Files.exists(fileB));
        }

        @Test
        @DisplayName("JSONL 每行一条记录")
        void jsonlFormat() throws Exception {
            MoaTraceWriter writer = new MoaTraceWriter(tempDir, true);
            for (int i = 0; i < 5; i++) {
                writer.write("tenant-1", MoaTraceRecord.builder("trace-1", "turn-" + i)
                    .agentId("agent-1")
                    .tokens(i * 100)
                    .build());
            }

            Path file = writer.resolveTraceFile("tenant-1");
            List<String> lines = Files.readAllLines(file);
            assertEquals(5, lines.size());
            // 每行是合法 JSON（能被解析）
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (String line : lines) {
                assertDoesNotThrow(() -> mapper.readTree(line));
            }
        }

        @Test
        @DisplayName("null record 不崩溃")
        void nullRecord() {
            MoaTraceWriter writer = new MoaTraceWriter(tempDir, true);
            assertDoesNotThrow(() -> writer.write("tenant-1", null));
        }

        @Test
        @DisplayName("fromSystemProperties 默认 disabled")
        void fromSystemPropertiesDefault() {
            // 清除系统属性
            System.clearProperty("agent.trace.jsonl.enabled");
            MoaTraceWriter writer = MoaTraceWriter.fromSystemProperties();
            assertFalse(writer.isEnabled());
        }

        @Test
        @DisplayName("fromSystemProperties enabled")
        void fromSystemPropertiesEnabled() {
            System.setProperty("agent.trace.jsonl.enabled", "true");
            System.setProperty("agent.trace.jsonl.dir", tempDir.toString());
            try {
                MoaTraceWriter writer = MoaTraceWriter.fromSystemProperties();
                assertTrue(writer.isEnabled());
            } finally {
                System.clearProperty("agent.trace.jsonl.enabled");
                System.clearProperty("agent.trace.jsonl.dir");
            }
        }

        @Test
        @DisplayName("resolveTraceFile 路径格式")
        void resolveTraceFilePath() {
            MoaTraceWriter writer = new MoaTraceWriter(tempDir, true);
            Path path = writer.resolveTraceFile("acme-corp");
            assertTrue(path.toString().contains("acme-corp"));
            assertTrue(path.toString().endsWith(".jsonl"));
        }

        @Test
        @DisplayName("并发写不交错（线程安全）")
        void concurrentWrite() throws Exception {
            MoaTraceWriter writer = new MoaTraceWriter(tempDir, true);
            int threadCount = 10;
            Thread[] threads = new Thread[threadCount];
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                threads[i] = new Thread(() -> {
                    writer.write("tenant-1", MoaTraceRecord.builder("trace-1", "turn-" + idx)
                        .agentId("agent-" + idx)
                        .build());
                });
            }
            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();

            Path file = writer.resolveTraceFile("tenant-1");
            List<String> lines = Files.readAllLines(file);
            assertEquals(threadCount, lines.size());
        }
    }
}
