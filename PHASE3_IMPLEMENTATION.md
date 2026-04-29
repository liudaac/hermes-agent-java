# Phase 3 实现文档 - 可观测性

> 实现时间: 2026-04-29
> 版本: v1.0

## 概述

Phase 3 实现了完整的可观测性体系，包括 JMX 指标暴露、Prometheus 集成和实时监控告警。

## 实现内容

### 1. JMX 指标暴露 (`TenantMetricsMBean` / `TenantMetrics`)

**文件**:
- `src/main/java/com/nousresearch/hermes/tenant/metrics/TenantMetricsMBean.java`
- `src/main/java/com/nousresearch/hermes/tenant/metrics/TenantMetrics.java`

**功能**:
- 内存指标（使用百分比、峰值、分配数）
- 进程指标（执行数、超时数、OOM kill 数）
- 网络指标（请求数、拦截数、按主机统计）
- 存储指标（配额、使用量、文件数）
- Agent 指标（活跃数、总会话数）
- 配额状态（OK/WARNING/CRITICAL）

**JMX 访问**:
```bash
# 使用 jconsole 连接
jconsole

# 使用命令行查看
jmxterm
$>open localhost:9999
$>get com.nousresearch.hermes:type=TenantMetrics,tenant=tenant-123 MemoryUsagePercent
```

### 2. Prometheus 集成

**文件**:
- `monitoring/prometheus/prometheus.yml`
- `monitoring/prometheus/rules/tenant-alerts.yml`

**指标格式**:
```
# HELP hermes_tenant_memory_used_bytes Tenant memory used in bytes
# TYPE hermes_tenant_memory_used_bytes gauge
hermes_tenant_memory_used_bytes{tenant="tenant-123"} 134217728

# HELP hermes_tenant_memory_usage_percent Tenant memory usage percent
# TYPE hermes_tenant_memory_usage_percent gauge
hermes_tenant_memory_usage_percent{tenant="tenant-123"} 0.5234

# HELP hermes_tenant_network_requests_total Total network requests
# TYPE hermes_tenant_network_requests_total counter
hermes_tenant_network_requests_total{tenant="tenant-123"} 1523
```

### 3. 监控告警

**告警规则**:
| 告警名称 | 条件 | 级别 | 冷却期 |
|---------|------|------|--------|
| TenantMemoryHigh | 内存使用 > 80% | warning | 5分钟 |
| TenantMemoryCritical | 内存使用 > 95% | critical | 2分钟 |
| TenantStorageHigh | 存储使用 > 85% | warning | 5分钟 |
| TenantMemoryLeak | 泄漏数 > 10 | warning | 10分钟 |
| TenantTooManyAgents | 活跃 Agent > 100 | warning | 5分钟 |

### 4. Grafana 仪表板

**文件**: `monitoring/grafana/tenant-dashboard.json`

**面板**:
- 租户总数统计
- 活跃 Agent 数
- 内存使用趋势图
- 网络请求速率
- 存储使用仪表盘
- 内存泄漏表格

## 架构

```
┌─────────────────┐
│  TenantContext  │
│  - getMetrics() │
└────────┬────────┘
         │
┌────────▼────────┐
│  TenantMetrics  │◄── JMX MBean
│  - MBean接口    │
│  - Prometheus   │
│  - 告警检测     │
└────────┬────────┘
         │
┌────────▼────────┐
│ MetricsCollector│◄── 定时采集
│  - 30s 间隔     │
│  - 告警触发     │
└─────────────────┘
```

## 使用示例

### 查看指标
```java
TenantContext context = tenantManager.getTenant("tenant-123");
TenantMetrics metrics = context.getMetrics();

// 生成报告
System.out.println(metrics.generateReport());

// 导出 Prometheus 格式
String prometheusMetrics = metrics.exportPrometheusMetrics();
```

### 触发 GC
```java
// 通过 JMX 或代码
metrics.triggerGC();
metrics.compactMemory();
```

## 部署

### Prometheus
```yaml
# docker-compose.yml
version: '3'
services:
  prometheus:
    image: prom/prometheus
    volumes:
      - ./monitoring/prometheus:/etc/prometheus
    ports:
      - "9090:9090"
```

### Grafana
```yaml
  grafana:
    image: grafana/grafana
    ports:
      - "3000:3000"
    volumes:
      - ./monitoring/grafana:/var/lib/grafana/dashboards
```

## 性能

- 指标采集间隔: 30s
- 缓存 TTL: 5s
- 告警冷却期: 5分钟
- 内存开销: ~10KB/tenant

## 相关文件

- `TenantMetricsMBean.java` - JMX 接口定义
- `TenantMetrics.java` - 指标实现
- `MetricsCollector.java` - 全局采集器
- `prometheus.yml` - Prometheus 配置
- `tenant-alerts.yml` - 告警规则
- `tenant-dashboard.json` - Grafana 仪表板
