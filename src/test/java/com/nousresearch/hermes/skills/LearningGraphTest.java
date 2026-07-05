package com.nousresearch.hermes.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S5-3: LearningGraphService 测试
 */
class LearningGraphTest {

    private LearningGraphService graph;

    @BeforeEach
    void setUp() {
        graph = new LearningGraphService();
    }

    // ========================================================================
    // 节点管理
    // ========================================================================

    @Nested
    @DisplayName("节点管理")
    class NodeTest {

        @Test
        @DisplayName("添加 skill 节点")
        void addSkill() {
            graph.addSkillNode("web-search", "搜索网页", List.of("search", "web"));
            assertEquals(1, graph.getSkillNodes().size());
            assertEquals("web-search", graph.getSkillNodes().get(0).label());
        }

        @Test
        @DisplayName("添加 memory 节点")
        void addMemory() {
            graph.addMemoryNode("chunk-1", "用户偏好用 Python", "preference");
            assertEquals(1, graph.getMemoryNodes().size());
        }

        @Test
        @DisplayName("混合节点")
        void mixedNodes() {
            graph.addSkillNode("s1", "desc", List.of("tag"));
            graph.addMemoryNode("m1", "content", "cat");
            assertEquals(1, graph.getSkillNodes().size());
            assertEquals(1, graph.getMemoryNodes().size());
        }
    }

    // ========================================================================
    // 边管理
    // ========================================================================

    @Nested
    @DisplayName("边管理")
    class EdgeTest {

        @Test
        @DisplayName("添加边")
        void addEdge() {
            graph.addSkillNode("s1", "d", List.of());
            graph.addMemoryNode("m1", "c", "cat");
            graph.addEdge("skill:s1", "memory:m1", LearningGraphService.EdgeType.USAGE, 0.8);
            assertEquals(1, graph.getEdges().size());
        }

        @Test
        @DisplayName("节点不存在 → 不添加边")
        void nodeNotFound() {
            graph.addEdge("nonexistent", "also-nonexistent", LearningGraphService.EdgeType.USAGE, 1.0);
            assertEquals(0, graph.getEdges().size());
        }

        @Test
        @DisplayName("getNeighbors")
        void neighbors() {
            graph.addSkillNode("s1", "d", List.of());
            graph.addSkillNode("s2", "d", List.of());
            graph.addSkillNode("s3", "d", List.of());
            graph.addEdge("skill:s1", "skill:s2", LearningGraphService.EdgeType.SIMILARITY, 0.5);
            graph.addEdge("skill:s1", "skill:s3", LearningGraphService.EdgeType.DEPENDENCY, 0.9);

            List<LearningGraphService.GraphEdge> neighbors = graph.getNeighbors("skill:s1");
            assertEquals(2, neighbors.size());
        }
    }

    // ========================================================================
    // autoDetectSimilarities
    // ========================================================================

    @Nested
    @DisplayName("autoDetectSimilarities")
    class AutoSimilarityTest {

        @Test
        @DisplayName("共享 tag 的 skill → 自动添加 SIMILARITY 边")
        void sharedTagsAutoDetected() {
            graph.addSkillNode("search-web", "web search", List.of("search", "web"));
            graph.addSkillNode("search-files", "file search", List.of("search", "files"));
            int added = graph.autoDetectSimilarities();
            assertTrue(added > 0);
            assertTrue(graph.getEdges().stream().anyMatch(e -> e.type() == LearningGraphService.EdgeType.SIMILARITY));
        }

        @Test
        @DisplayName("无共享 tag → 不添加")
        void noSharedTags() {
            graph.addSkillNode("coding", "code helper", List.of("code"));
            graph.addSkillNode("writing", "text writer", List.of("write"));
            int added = graph.autoDetectSimilarities();
            assertEquals(0, added);
        }

        @Test
        @DisplayName("名称包含 → 添加（即使 tag 不同）")
        void nameContains() {
            graph.addSkillNode("search", "search", List.of("s1"));
            graph.addSkillNode("search-advanced", "advanced search", List.of("s2"));
            int added = graph.autoDetectSimilarities();
            assertTrue(added > 0); // 名称包含导致 nameBoost
        }

        @Test
        @DisplayName("单个 skill → 无相似度")
        void singleSkill() {
            graph.addSkillNode("alone", "desc", List.of("tag"));
            assertEquals(0, graph.autoDetectSimilarities());
        }
    }

    // ========================================================================
    // computeSimilarity
    // ========================================================================

    @Nested
    @DisplayName("computeSimilarity")
    class SimilarityTest {

        @Test
        @DisplayName("完全相同 tag → 高相似度")
        void identicalTags() {
            LearningGraphService.GraphNode a = new LearningGraphService.GraphNode(
                "skill:a", LearningGraphService.NodeType.SKILL, "a", "d", List.of("search", "web"));
            LearningGraphService.GraphNode b = new LearningGraphService.GraphNode(
                "skill:b", LearningGraphService.NodeType.SKILL, "b", "d", List.of("search", "web"));
            double sim = LearningGraphService.computeSimilarity(a, b);
            assertEquals(1.0, sim, 0.01);
        }

        @Test
        @DisplayName("无共享 tag → 低相似度")
        void noSharedTags() {
            LearningGraphService.GraphNode a = new LearningGraphService.GraphNode(
                "skill:a", LearningGraphService.NodeType.SKILL, "a", "d", List.of("code"));
            LearningGraphService.GraphNode b = new LearningGraphService.GraphNode(
                "skill:b", LearningGraphService.NodeType.SKILL, "b", "d", List.of("write"));
            double sim = LearningGraphService.computeSimilarity(a, b);
            assertEquals(0, sim, 0.01);
        }

        @Test
        @DisplayName("部分共享 tag → 中等相似度")
        void partialSharedTags() {
            LearningGraphService.GraphNode a = new LearningGraphService.GraphNode(
                "skill:a", LearningGraphService.NodeType.SKILL, "a", "d", List.of("search", "web"));
            LearningGraphService.GraphNode b = new LearningGraphService.GraphNode(
                "skill:b", LearningGraphService.NodeType.SKILL, "b", "d", List.of("search", "files"));
            double sim = LearningGraphService.computeSimilarity(a, b);
            assertTrue(sim > 0 && sim < 1);
        }

        @Test
        @DisplayName("空 tag → 0")
        void emptyTags() {
            LearningGraphService.GraphNode a = new LearningGraphService.GraphNode(
                "skill:a", LearningGraphService.NodeType.SKILL, "a", "d", List.of());
            LearningGraphService.GraphNode b = new LearningGraphService.GraphNode(
                "skill:b", LearningGraphService.NodeType.SKILL, "b", "d", List.of());
            assertEquals(0, LearningGraphService.computeSimilarity(a, b), 0.01);
        }
    }

    // ========================================================================
    // getStatistics
    // ========================================================================

    @Nested
    @DisplayName("getStatistics")
    class StatisticsTest {

        @Test
        @DisplayName("空图 → 全零")
        void empty() {
            LearningGraphService.GraphStatistics stats = graph.getStatistics();
            assertEquals(0, stats.totalNodes());
            assertEquals(0, stats.skillNodes());
            assertEquals(0, stats.totalEdges());
        }

        @Test
        @DisplayName("正确统计")
        void mixed() {
            graph.addSkillNode("s1", "d", List.of("t"));
            graph.addSkillNode("s2", "d", List.of("t"));
            graph.addMemoryNode("m1", "c", "cat");
            graph.addEdge("skill:s1", "skill:s2", LearningGraphService.EdgeType.SIMILARITY, 0.5);
            graph.addEdge("skill:s1", "memory:m1", LearningGraphService.EdgeType.USAGE, 0.8);

            LearningGraphService.GraphStatistics stats = graph.getStatistics();
            assertEquals(3, stats.totalNodes());
            assertEquals(2, stats.skillNodes());
            assertEquals(1, stats.memoryNodes());
            assertEquals(2, stats.totalEdges());
        }
    }

    // ========================================================================
    // clear
    // ========================================================================

    @Nested
    @DisplayName("clear")
    class ClearTest {

        @Test
        @DisplayName("清空图")
        void clear() {
            graph.addSkillNode("s1", "d", List.of());
            graph.addEdge("skill:s1", "skill:s1", LearningGraphService.EdgeType.SIMILARITY, 1);
            graph.clear();
            assertEquals(0, graph.getSkillNodes().size());
            assertEquals(0, graph.getEdges().size());
        }
    }
}
