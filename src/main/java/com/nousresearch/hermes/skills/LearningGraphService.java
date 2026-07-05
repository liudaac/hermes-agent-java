package com.nousresearch.hermes.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * S5-3: Learning Graph — skill × memory 关系图。
 *
 * <p>对齐原版 agent/learning_graph.py。
 * 节点：SkillNode + MemoryChunkNode
 * 边：USAGE（skill 使用了 memory）/ DEPENDENCY（skill 依赖另一个 skill）/ EVOLUTION（skill 从另一个 skill 进化而来）</p>
 *
 * <p>简化实现：内存图 + BM25 lexical overlap 计算边权重。</p>
 */
public class LearningGraphService {
    private static final Logger logger = LoggerFactory.getLogger(LearningGraphService.class);

    private final Map<String, GraphNode> nodes = new ConcurrentHashMap<>();
    private final List<GraphEdge> edges = Collections.synchronizedList(new ArrayList<>());

    public enum NodeType { SKILL, MEMORY_CHUNK }
    public enum EdgeType { USAGE, DEPENDENCY, EVOLUTION, SIMILARITY }

    /**
     * 添加 skill 节点。
     */
    public void addSkillNode(String skillName, String description, List<String> tags) {
        String nodeId = "skill:" + skillName;
        nodes.put(nodeId, new GraphNode(nodeId, NodeType.SKILL, skillName, description,
            tags != null ? tags : List.of()));
        logger.debug("Added skill node: {}", skillName);
    }

    /**
     * 添加 memory chunk 节点。
     */
    public void addMemoryNode(String chunkId, String content, String category) {
        String nodeId = "memory:" + chunkId;
        nodes.put(nodeId, new GraphNode(nodeId, NodeType.MEMORY_CHUNK, chunkId, content,
            List.of(category != null ? category : "general")));
        logger.debug("Added memory node: {}", chunkId);
    }

    /**
     * 添加边。
     */
    public void addEdge(String fromNodeId, String toNodeId, EdgeType type, double weight) {
        if (!nodes.containsKey(fromNodeId) || !nodes.containsKey(toNodeId)) {
            logger.warn("Cannot add edge: node not found ({} → {})", fromNodeId, toNodeId);
            return;
        }
        edges.add(new GraphEdge(fromNodeId, toNodeId, type, weight));
    }

    /**
     * 自动检测 skill 之间的相似度并添加 SIMILARITY 边。
     */
    public int autoDetectSimilarities() {
        List<GraphNode> skillNodes = nodes.values().stream()
            .filter(n -> n.type() == NodeType.SKILL)
            .toList();

        int added = 0;
        for (int i = 0; i < skillNodes.size(); i++) {
            for (int j = i + 1; j < skillNodes.size(); j++) {
                GraphNode a = skillNodes.get(i);
                GraphNode b = skillNodes.get(j);
                double similarity = computeSimilarity(a, b);
                if (similarity > 0.3) {
                    addEdge(a.id(), b.id(), EdgeType.SIMILARITY, similarity);
                    added++;
                }
            }
        }
        logger.info("Auto-detected {} similarity edges", added);
        return added;
    }

    /**
     * BM25 lexical overlap 简化版：Jaccard 相似度 on tags + 名称包含。
     */
    static double computeSimilarity(GraphNode a, GraphNode b) {
        // Jaccard on tags
        Set<String> tagsA = new HashSet<>(a.tags());
        Set<String> tagsB = new HashSet<>(b.tags());
        Set<String> union = new HashSet<>(tagsA);
        union.addAll(tagsB);
        if (union.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(tagsA);
        intersection.retainAll(tagsB);
        double jaccard = (double) intersection.size() / union.size();

        // 名称包含
        double nameBoost = 0;
        if (a.label().contains(b.label()) || b.label().contains(a.label())) {
            nameBoost = 0.2;
        }

        return Math.min(1.0, jaccard + nameBoost);
    }

    /**
     * 获取节点的所有邻居。
     */
    public List<GraphEdge> getNeighbors(String nodeId) {
        return edges.stream()
            .filter(e -> e.from().equals(nodeId) || e.to().equals(nodeId))
            .toList();
    }

    /**
     * 获取所有 skill 节点。
     */
    public List<GraphNode> getSkillNodes() {
        return nodes.values().stream()
            .filter(n -> n.type() == NodeType.SKILL)
            .toList();
    }

    /**
     * 获取所有 memory 节点。
     */
    public List<GraphNode> getMemoryNodes() {
        return nodes.values().stream()
            .filter(n -> n.type() == NodeType.MEMORY_CHUNK)
            .toList();
    }

    /**
     * 获取所有边。
     */
    public List<GraphEdge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    /**
     * 获取图统计信息。
     */
    public GraphStatistics getStatistics() {
        long skillCount = nodes.values().stream().filter(n -> n.type() == NodeType.SKILL).count();
        long memoryCount = nodes.values().stream().filter(n -> n.type() == NodeType.MEMORY_CHUNK).count();
        Map<EdgeType, Long> edgeByType = edges.stream()
            .collect(Collectors.groupingBy(GraphEdge::type, Collectors.counting()));
        return new GraphStatistics(nodes.size(), skillCount, memoryCount, edges.size(), edgeByType);
    }

    /**
     * 清空图。
     */
    public void clear() {
        nodes.clear();
        edges.clear();
    }

    // ============ 数据类 ============

    public record GraphNode(String id, NodeType type, String label, String content, List<String> tags) {}

    public record GraphEdge(String from, String to, EdgeType type, double weight) {}

    public record GraphStatistics(
        int totalNodes, long skillNodes, long memoryNodes,
        int totalEdges, Map<EdgeType, Long> edgesByType
    ) {}
}
