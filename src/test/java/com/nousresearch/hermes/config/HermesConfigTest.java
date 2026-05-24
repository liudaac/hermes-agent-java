package com.nousresearch.hermes.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HermesConfigTest {

    @Test
    @DisplayName("Generic getters should support nested dot paths")
    void genericGettersSupportNestedPaths() {
        HermesConfig config = new HermesConfig();

        config.set("gateway.enabled_platforms", "telegram");
        config.set("gateway.telegram.enabled", "false");

        assertEquals("telegram", config.get("gateway.enabled_platforms"));
        assertFalse(config.getBoolean("gateway.telegram.enabled", true));
        assertTrue(config.getBoolean("gateway.discord.enabled", true));
    }
}
