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

    @Test
    void clear_topic() {
        SharedBlackboard board = new SharedBlackboard("test", tempDir.resolve("board.json"));
        board.write("k1", "v1", "a", "topic1", 30_000);
        board.write("k2", "v2", "a", "topic2", 30_000);
        board.clear("topic1");
        assertTrue(board.read("k1", "topic1").isEmpty());
        assertTrue(board.read("k2", "topic2").isPresent());
    }

    @Test
    void expired_entries_ignored() throws InterruptedException {
        SharedBlackboard board = new SharedBlackboard("test", tempDir.resolve("board.json"));
        board.write("k1", "v1", "a", "default", 10);
        Thread.sleep(50);
        assertTrue(board.read("k1").isEmpty());
        assertEquals(0, board.size());
    }

    @Test
    void purge_removes_expired() throws InterruptedException {
        SharedBlackboard board = new SharedBlackboard("test", tempDir.resolve("board.json"));
        board.write("old", "v", "a", "default", 10);
        Thread.sleep(50);
        board.write("new", "v", "a", "default", 30_000);
        board.purgeExpired();
        assertEquals(1, board.size());
    }

    @Test
    void persistence_survives_reload() {
        Path path = tempDir.resolve("board.json");
        SharedBlackboard b1 = new SharedBlackboard("test", path);
        b1.write("k1", "persistent", "agent-a");
        b1.write("k2", "also persistent", "agent-b", "topic1", 60_000);
        b1.save();

        SharedBlackboard b2 = new SharedBlackboard("test", path);
        assertTrue(b2.read("k1").isPresent());
        assertEquals("persistent", b2.read("k1").get().value);
        assertTrue(b2.read("k2", "topic1").isPresent());
        assertEquals(2, b2.size());
    }
}