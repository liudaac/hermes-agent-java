package com.nousresearch.hermes.tenant.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SharedBlackboardTest {

    @TempDir Path tempDir;

    @Test
    void write_and_read() {
        SharedBlackboard board = new SharedBlackboard("test", tempDir.resolve("board.json"));
        board.write("k1", "hello", "agent-a");
        var opt = board.read("k1");
        assertTrue(opt.isPresent());
        assertEquals("hello", opt.get().value);
        assertEquals("agent-a", opt.get().author);
    }

    @Test
    void read_missing_returns_empty() {
        SharedBlackboard board = new SharedBlackboard("test", tempDir.resolve("board.json"));
        assertTrue(board.read("missing").isEmpty());
    }

    @Test
    void list_returns_entries_newest_first() {
        SharedBlackboard board = new SharedBlackboard("test", tempDir.resolve("board.json"));
        board.write("k1", "first", "a");
        board.write("k2", "second", "b");
        var list = board.list();
        assertEquals(2, list.size());
        assertEquals("second", list.get(0).value);
    }

}