package com.nousresearch.hermes.tenant.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SharedBlackboardTest {

    @Test
    void write_and_read() {
        SharedBlackboard board = new SharedBlackboard("test");
        board.write("k1", "hello", "agent-a");
        var opt = board.read("k1");
        assertTrue(opt.isPresent());
        assertEquals("hello", opt.get().value);
        assertEquals("agent-a", opt.get().author);
    }

    @Test
    void read_missing_returns_empty() {
        SharedBlackboard board = new SharedBlackboard("test");
        assertTrue(board.read("missing").isEmpty());
    }

    @Test
    void list_returns_entries_newest_first() {
        SharedBlackboard board = new SharedBlackboard("test");
        board.write("k1", "first", "a");
        board.write("k2", "second", "b");
        var list = board.list();
        assertEquals(2, list.size());
        assertEquals("second", list.get(0).value); // newest first
    }

    @Test
    void clear_topic() {
        SharedBlackboard board = new SharedBlackboard("test");
        board.write("k1", "v1", "a", "topic1", 30_000);
        board.write("k2", "v2", "a", "topic2", 30_000);
        board.clear("topic1");
        assertTrue(board.read("k1", "topic1").isEmpty());
        assertTrue(board.read("k2", "topic2").isPresent());
    }

    @Test
    void expired_entries_ignored() throws InterruptedException {
        SharedBlackboard board = new SharedBlackboard("test");
        board.write("k1", "v1", "a", "default", 10); // 10ms TTL
        Thread.sleep(50);
        assertTrue(board.read("k1").isEmpty());
        assertEquals(0, board.size());
    }

    @Test
    void purge_removes_expired() throws InterruptedException {
        SharedBlackboard board = new SharedBlackboard("test");
        board.write("old", "v", "a", "default", 10);
        Thread.sleep(50);
        board.write("new", "v", "a", "default", 30_000);
        board.purgeExpired();
        assertEquals(1, board.size());
    }
}
