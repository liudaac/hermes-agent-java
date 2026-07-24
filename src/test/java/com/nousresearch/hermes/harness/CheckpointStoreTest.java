package com.nousresearch.hermes.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundTrip() {
        var store = new CheckpointStore(tempDir);
        var state = new LoopState(25);
        state.addToHistory(com.nousresearch.hermes.model.ModelMessage.user("hello"));
        state.setLifecycle(LoopState.Lifecycle.PAUSED_APPROVAL);
        state.incrementTurn();

        store.save("test-session-1", state);
        assertTrue(store.exists("test-session-1"));

        var loaded = store.load("test-session-1", 25);
        assertNotNull(loaded);
        assertEquals(1, loaded.historySize());
        assertEquals("hello", loaded.history().get(0).getContent());
        assertEquals(LoopState.Lifecycle.PAUSED_APPROVAL, loaded.lifecycle());
        assertEquals(1, loaded.userTurnCount());
    }

    @Test
    void deleteRemovesCheckpoint() {
        var store = new CheckpointStore(tempDir);
        var state = new LoopState(10);
        store.save("test-session-2", state);
        assertTrue(store.exists("test-session-2"));

        store.delete("test-session-2");
        assertFalse(store.exists("test-session-2"));
    }

    @Test
    void loadNonexistentReturnsNull() {
        var store = new CheckpointStore(tempDir);
        assertNull(store.load("nonexistent", 25));
    }
}
