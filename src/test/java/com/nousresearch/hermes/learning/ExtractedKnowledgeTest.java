package com.nousresearch.hermes.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the JSON contract used by the structured extraction pipeline.
 * Locks in the schema so prompt drift never breaks parsing silently.
 */
class ExtractedKnowledgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parses_full_payload() throws Exception {
        String json = """
            {
              "facts": [
                {"content": "User prefers Markdown for docs", "confidence": 0.95, "tags": ["preference"]}
              ],
              "user_profile": [
                {"content": "Timezone is Asia/Shanghai", "confidence": 0.88, "tags": ["timezone"]}
              ],
              "skill_hints": [
                {"name": "compile_java",
                 "description": "Run mvn -q compile after edits",
                 "content": "# Compile\\nmvn -q compile",
                 "tags": ["maven"],
                 "confidence": 0.82}
              ],
              "anti_patterns": [
                {"content": "Don't use System.out for logging", "confidence": 0.9}
              ]
            }
            """;

        ExtractedKnowledge ek = MAPPER.readValue(json, ExtractedKnowledge.class);

        assertEquals(1, ek.getFacts().size());
        assertEquals(0.95, ek.getFacts().get(0).getConfidence());
        assertEquals("preference", ek.getFacts().get(0).getTags().get(0));

        assertEquals(1, ek.getUserProfile().size());
        assertEquals("Timezone is Asia/Shanghai", ek.getUserProfile().get(0).getContent());

        assertEquals(1, ek.getSkillHints().size());
        assertEquals("compile_java", ek.getSkillHints().get(0).getName());
        assertEquals(0.82, ek.getSkillHints().get(0).getConfidence());

        assertEquals(1, ek.getAntiPatterns().size());
        assertEquals(4, ek.totalItems());
        assertFalse(ek.isEmpty());
    }

    @Test
    void parses_empty_buckets() throws Exception {
        ExtractedKnowledge ek = MAPPER.readValue("{}", ExtractedKnowledge.class);
        assertTrue(ek.isEmpty());
        assertEquals(0, ek.totalItems());
        assertNotNull(ek.getFacts());
        assertNotNull(ek.getUserProfile());
        assertNotNull(ek.getSkillHints());
        assertNotNull(ek.getAntiPatterns());
    }

    @Test
    void ignores_unknown_fields() throws Exception {
        String json = """
            {"facts":[{"content":"X","confidence":0.8,"unexpected":"ignore_me"}],
             "future_bucket":[{"foo":"bar"}]}
            """;
        ExtractedKnowledge ek = MAPPER.readValue(json, ExtractedKnowledge.class);
        assertEquals(1, ek.getFacts().size());
        assertEquals("X", ek.getFacts().get(0).getContent());
    }

    @Test
    void confidence_filter_drops_low_items() {
        var item1 = new ExtractedKnowledge.KnowledgeItem("high", 0.9);
        var item2 = new ExtractedKnowledge.KnowledgeItem("mid",  0.7);
        var item3 = new ExtractedKnowledge.KnowledgeItem("low",  0.4);
        var filtered = KnowledgeExtractor.filterBy(java.util.List.of(item1, item2, item3), 0.75);
        assertEquals(1, filtered.size());
        assertEquals("high", filtered.get(0).getContent());
    }

    @Test
    void policy_defaults_are_sane() {
        ExtractionPolicy p = ExtractionPolicy.defaults();
        assertTrue(p.getMemoryConfidenceThreshold() >= 0.5 && p.getMemoryConfidenceThreshold() <= 1.0);
        assertTrue(p.getSkillConfidenceThreshold() >= p.getMemoryConfidenceThreshold(),
            "Skill threshold should be at least as strict as memory threshold");
        assertTrue(p.isLlmEnabled());
        assertTrue(p.getMaxItemsPerBucket() > 0);
    }
}
