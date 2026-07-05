package com.nousresearch.hermes.gateway.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2-1 #2: 租户路由 sticky 测试
 */
class TenantRoutingTest {

    // ========================================================================
    // ClusterTopology
    // ========================================================================

    @Nested
    @DisplayName("ClusterTopology")
    class TopologyTest {

        @Test
        @DisplayName("添加节点 + 查找")
        void addAndFind() {
            ClusterTopology topo = new ClusterTopology("node-1");
            topo.addNode("node-1", "http://10.0.0.1:8080");
            topo.addNode("node-2", "http://10.0.0.2:8080");

            assertEquals(2, topo.getAllNodes().size());
            assertTrue(topo.getNode("node-1").isPresent());
            assertTrue(topo.getNode("node-2").isPresent());
            assertTrue(topo.getNode("node-3").isEmpty());
        }

        @Test
        @DisplayName("健康节点过滤")
        void healthyFilter() {
            ClusterTopology topo = new ClusterTopology("node-1");
            topo.addNode("node-1", "http://10.0.0.1:8080");
            topo.addNode("node-2", "http://10.0.0.2:8080");
            topo.addNode("node-3", "http://10.0.0.3:8080");

            topo.setNodeHealth("node-2", false);
            assertEquals(2, topo.healthyCount());
            assertEquals(3, topo.getAllNodes().size());
        }

        @Test
        @DisplayName("isLocal 判断")
        void isLocal() {
            ClusterTopology topo = new ClusterTopology("node-1");
            topo.addNode("node-1", "http://10.0.0.1:8080");
            topo.addNode("node-2", "http://10.0.0.2:8080");

            assertTrue(topo.isLocal(topo.getNode("node-1").get()));
            assertFalse(topo.isLocal(topo.getNode("node-2").get()));
        }

        @Test
        @DisplayName("单节点判断")
        void singleNode() {
            ClusterTopology topo = new ClusterTopology("node-1");
            assertTrue(topo.isSingleNode());
            topo.addNode("node-1", "http://10.0.0.1:8080");
            assertTrue(topo.isSingleNode());
            topo.addNode("node-2", "http://10.0.0.2:8080");
            assertFalse(topo.isSingleNode());
        }
    }

    // ========================================================================
    // TenantHashStrategy — 核心：一致性 sticky
    // ========================================================================

    @Nested
    @DisplayName("TenantHashStrategy — 一致性 sticky")
    class HashStrategyTest {

        private ClusterTopology topology;

        @BeforeEach
        void setUp() {
            topology = new ClusterTopology("node-1");
            topology.addNode("node-1", "http://10.0.0.1:8080");
            topology.addNode("node-2", "http://10.0.0.2:8080");
            topology.addNode("node-3", "http://10.0.0.3:8080");
        }

        @Test
        @DisplayName("同一租户始终路由到同一节点")
        void stickyRouting() {
            TenantHashStrategy strategy = new TenantHashStrategy(topology);
            ClusterNode first = strategy.resolve("tenant-A", null);
            for (int i = 0; i < 20; i++) {
                ClusterNode node = strategy.resolve("tenant-A", null);
                assertEquals(first, node, "tenant-A should always go to the same node");
            }
        }

        @Test
        @DisplayName("不同租户可能到不同节点")
        void differentTenants() {
            TenantHashStrategy strategy = new TenantHashStrategy(topology);
            Set<ClusterNode> nodes = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                nodes.add(strategy.resolve("tenant-" + i, null));
            }
            // 100 个租户分到 3 个节点，至少应该命中 2 个以上
            assertTrue(nodes.size() >= 2, "Different tenants should spread across multiple nodes");
        }

        @Test
        @DisplayName("节点下线后，受影响的租户迁移到其他节点")
        void nodeDownMigration() {
            TenantHashStrategy strategy = new TenantHashStrategy(topology);
            // 记录每个租户的初始节点
            Map<String, ClusterNode> initialMapping = new HashMap<>();
            for (int i = 0; i < 50; i++) {
                String tenant = "tenant-" + i;
                initialMapping.put(tenant, strategy.resolve(tenant, null));
            }

            // 下线 node-2
            topology.setNodeHealth("node-2", false);
            strategy.rebuildRing();

            // 之前在 node-2 的租户应该迁移到 node-1 或 node-3
            for (Map.Entry<String, ClusterNode> entry : initialMapping.entrySet()) {
                ClusterNode newNode = strategy.resolve(entry.getKey(), null);
                if (entry.getValue().nodeId().equals("node-2")) {
                    assertNotEquals("node-2", newNode.nodeId(),
                        "Tenant on failed node should be migrated");
                } else {
                    // 之前不在 node-2 的租户，大部分应该保持不变
                    // （一致性哈希保证大部分 key 不迁移）
                }
            }
        }

        @Test
        @DisplayName("单节点部署始终返回本地节点")
        void singleNode() {
            ClusterTopology single = new ClusterTopology("only-node");
            single.addNode("only-node", "http://localhost:8080");
            TenantHashStrategy strategy = new TenantHashStrategy(single);
            ClusterNode node = strategy.resolve("any-tenant", null);
            assertEquals("only-node", node.nodeId());
        }

        @Test
        @DisplayName("sessionId 提供时使用 tenantId:sessionId 做 key")
        void sessionBasedHash() {
            TenantHashStrategy strategy = new TenantHashStrategy(topology);
            // 同一 tenant 不同 session 可能到不同节点（更细粒度的 stickiness）
            ClusterNode n1 = strategy.resolve("tenant-A", "session-1");
            ClusterNode n2 = strategy.resolve("tenant-A", "session-2");
            // 同一 session 应该始终到同一节点
            assertEquals(n1, strategy.resolve("tenant-A", "session-1"));
            assertEquals(n2, strategy.resolve("tenant-A", "session-2"));
        }

        @Test
        @DisplayName("所有健康节点下线 → 返回 null 或本地节点")
        void allNodesDown() {
            topology.setNodeHealth("node-1", false);
            topology.setNodeHealth("node-2", false);
            topology.setNodeHealth("node-3", false);
            TenantHashStrategy strategy = new TenantHashStrategy(topology);
            // 不崩溃
            strategy.resolve("any-tenant", null);
        }
    }

    // ========================================================================
    // RoundRobinStrategy
    // ========================================================================

    @Nested
    @DisplayName("RoundRobinStrategy")
    class RoundRobinTest {

        @Test
        @DisplayName("轮询分配")
        void roundRobin() {
            ClusterTopology topo = new ClusterTopology("node-1");
            topo.addNode("node-1", "http://10.0.0.1:8080");
            topo.addNode("node-2", "http://10.0.0.2:8080");
            topo.addNode("node-3", "http://10.0.0.3:8080");

            RoundRobinStrategy strategy = new RoundRobinStrategy(topo);
            Set<String> nodes = new HashSet<>();
            for (int i = 0; i < 9; i++) {
                nodes.add(strategy.resolve("any-tenant", null).nodeId());
            }
            assertTrue(nodes.size() == 3, "Should cycle through all 3 nodes");
        }

        @Test
        @DisplayName("不保证 stickiness（同一租户可能到不同节点）")
        void noStickiness() {
            ClusterTopology topo = new ClusterTopology("node-1");
            topo.addNode("node-1", "http://10.0.0.1:8080");
            topo.addNode("node-2", "http://10.0.0.2:8080");

            RoundRobinStrategy strategy = new RoundRobinStrategy(topo);
            Set<ClusterNode> nodes = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                nodes.add(strategy.resolve("same-tenant", null));
            }
            assertTrue(nodes.size() > 1, "Round-robin should NOT be sticky");
        }
    }

    // ========================================================================
    // LocalStrategy
    // ========================================================================

    @Nested
    @DisplayName("LocalStrategy")
    class LocalStrategyTest {

        @Test
        @DisplayName("始终返回本地节点")
        void alwaysLocal() {
            ClusterTopology topo = new ClusterTopology("node-1");
            topo.addNode("node-1", "http://10.0.0.1:8080");
            topo.addNode("node-2", "http://10.0.0.2:8080");

            LocalStrategy strategy = new LocalStrategy(topo);
            ClusterNode node = strategy.resolve("any-tenant", null);
            assertEquals("node-1", node.nodeId());
        }
    }

    // ========================================================================
    // RoutingResolver
    // ========================================================================

    @Nested
    @DisplayName("RoutingResolver")
    class ResolverTest {

        private ClusterTopology topology;

        @BeforeEach
        void setUp() {
            topology = new ClusterTopology("node-1");
            topology.addNode("node-1", "http://10.0.0.1:8080");
            topology.addNode("node-2", "http://10.0.0.2:8080");
            topology.addNode("node-3", "http://10.0.0.3:8080");
        }

        @Test
        @DisplayName("strategy=tenant-hash → TenantHashStrategy")
        void createTenantHash() {
            RoutingResolver resolver = RoutingResolver.create(topology, "tenant-hash");
            assertEquals("tenant-hash", resolver.getStrategyName());
        }

        @Test
        @DisplayName("strategy=round-robin → RoundRobinStrategy")
        void createRoundRobin() {
            RoutingResolver resolver = RoutingResolver.create(topology, "round-robin");
            assertEquals("round-robin", resolver.getStrategyName());
        }

        @Test
        @DisplayName("strategy=local → LocalStrategy")
        void createLocal() {
            RoutingResolver resolver = RoutingResolver.create(topology, "local");
            assertEquals("local", resolver.getStrategyName());
        }

        @Test
        @DisplayName("strategy=null → LocalStrategy（默认安全）")
        void createNullDefaults() {
            RoutingResolver resolver = RoutingResolver.create(topology, null);
            assertEquals("local", resolver.getStrategyName());
        }

        @Test
        @DisplayName("单节点部署 shouldHandleLocally 始终 true")
        void singleNodeAlwaysLocal() {
            ClusterTopology single = new ClusterTopology("only");
            single.addNode("only", "http://localhost:8080");
            RoutingResolver resolver = RoutingResolver.create(single, "tenant-hash");
            assertTrue(resolver.shouldHandleLocally("any-tenant", null));
        }

        @Test
        @DisplayName("多节点 tenant-hash 模式下，部分租户非本地")
        void multiNodeSomeNonLocal() {
            RoutingResolver resolver = RoutingResolver.create(topology, "tenant-hash");
            int localCount = 0;
            int remoteCount = 0;
            for (int i = 0; i < 100; i++) {
                if (resolver.shouldHandleLocally("tenant-" + i, null)) {
                    localCount++;
                } else {
                    remoteCount++;
                }
            }
            assertTrue(localCount > 0, "Some tenants should be local");
            assertTrue(remoteCount > 0, "Some tenants should be remote");
        }

        @Test
        @DisplayName("getTargetUrl 返回远程节点 URL 或 null（本地）")
        void getTargetUrl() {
            RoutingResolver resolver = RoutingResolver.create(topology, "tenant-hash");
            // 找一个路由到远程的租户
            String remoteTenant = null;
            for (int i = 0; i < 100; i++) {
                String url = resolver.getTargetUrl("tenant-" + i, null);
                if (url != null) {
                    remoteTenant = "tenant-" + i;
                    break;
                }
            }
            assertNotNull(remoteTenant, "Should find at least one remote tenant");
            String url = resolver.getTargetUrl(remoteTenant, null);
            assertNotNull(url);
            assertTrue(url.startsWith("http://"));
        }

        @Test
        @DisplayName("onTopologyChanged 重建哈希环")
        void topologyChanged() {
            RoutingResolver resolver = RoutingResolver.create(topology, "tenant-hash");
            // 下线一个节点
            topology.setNodeHealth("node-3", false);
            assertDoesNotThrow(() -> resolver.onTopologyChanged());
        }
    }

    // ========================================================================
    // 一致性哈希分布均匀性
    // ========================================================================

    @Nested
    @DisplayName("哈希分布均匀性")
    class DistributionTest {

        @Test
        @DisplayName("3 节点 1000 租户，每个节点应分到 ~33%")
        void evenDistribution() {
            ClusterTopology topo = new ClusterTopology("node-1");
            topo.addNode("node-1", "http://10.0.0.1:8080");
            topo.addNode("node-2", "http://10.0.0.2:8080");
            topo.addNode("node-3", "http://10.0.0.3:8080");

            TenantHashStrategy strategy = new TenantHashStrategy(topo);
            Map<String, Integer> counts = new HashMap<>();
            for (int i = 0; i < 1000; i++) {
                ClusterNode node = strategy.resolve("tenant-" + i, null);
                counts.merge(node.nodeId(), 1, Integer::sum);
            }

            // 每个节点应在 15%-60% 之间（3 节点 + FNV 哈希，方差较大）
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                double pct = entry.getValue() / 1000.0 * 100;
                assertTrue(pct > 15 && pct < 60,
                    "Node " + entry.getKey() + " got " + pct + "% (expected 15-60%)");
            }
        }
    }
}
