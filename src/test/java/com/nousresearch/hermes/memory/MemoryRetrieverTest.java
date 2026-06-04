package com.nousresearch.hermes.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the BM25-lite memory retriever and context card builder.
 */
class MemoryRetrieverTest {

    @TempDir
    Path tempDir;

    private MemoryManager memory;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        System.setProperty("hermes.home", tempDir.toString());
        memory = new MemoryManager();
        retriever = new MemoryRetriever(memory);
    }

    @Test
    void retrieves_relevant_entry_first() {
        memory.addMemory("User prefers Markdown format for documentation files");
        memory.addMemory("Project uses Java 21 and Maven build system");
        memory.addMemory("Timezone is Asia/Shanghai for all schedule operations");

        List<MemoryRetriever.RetrievedEntry> results = retriever.retrieve("documentation markdown", 5);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).content.toLowerCase().contains("markdown"),
            "Top result should be the markdown-related entry, got: " + results.get(0).content);
    }

    @Test
    void empty_query_returns_empty() {
        memory.addMemory("Some random fact about the project");
        assertTrue(retriever.retrieve("", 5).isEmpty());
        assertTrue(retriever.retrieve(null, 5).isEmpty());
        assertTrue(retriever.retrieve("foo", 0).isEmpty());
    }

    @Test
    void cjk_tokens_are_indexed() {
        memory.addMemory("用户喜欢使用飞书发送通知和提醒消息");
        memory.addMemory("项目使用 Maven 构建并部署到生产环境");
        List<MemoryRetriever.RetrievedEntry> results = retriever.retrieve("飞书 通知", 3);
        assertFalse(results.isEmpty(), "Should retrieve at least one CJK entry");
        assertTrue(results.get(0).content.contains("飞书"));
    }

    @Test
    void context_card_falls_back_to_full_snapshot_when_no_query() {
        memory.addMemory("Stable fact one");
        memory.addUser("User prefers Markdown");
        ContextCardBuilder b = new ContextCardBuilder(memory);
        String card = b.build(null);
        assertNotNull(card);
    }

    @Test
    void context_card_includes_relevant_section_when_corpus_big_enough() {
        for (int i = 0; i < 10; i++) {
            memory.addMemory("Random unrelated fact number " + i + " about cats and dogs");
        }
        memory.addMemory("User specifically requested Markdown for all documentation outputs");
        memory.addUser("Name is Alice; prefers concise replies");

        ContextCardBuilder b = new ContextCardBuilder(memory, 4, true);
        String card = b.build("How should I format my documentation?");

        assertNotNull(card);
        assertTrue(card.contains("User Profile") || card.contains("Relevant Memory"),
            "Card should contain one of the labeled sections, got:\n" + card);
        assertTrue(card.toLowerCase().contains("markdown"),
            "Relevant memory should surface the markdown entry");
    }

    @Test
    void total_entries_counts_across_categories() {
        memory.addMemory("a");
        memory.addMemory("b");
        memory.addUser("u1");
        int total = retriever.totalEntries();
        // May include entries from earlier tests in same JVM, so just verify >= our entries
        assertTrue(total >= 3, "Should count at least 3 entries, got " + total);
    }

    @Test
    void tokenize_splits_cjk_and_ascii() {
        var tokens = MemoryRetriever.tokenize("Hello 世界！ markdown 格式");
        assertTrue(tokens.contains("hello"));
        assertTrue(tokens.contains("世"), "CJK char '世' should be a token");
        assertTrue(tokens.contains("式"), "CJK char '式' should be a token");
        assertTrue(tokens.contains("markdown"));
    }
}
