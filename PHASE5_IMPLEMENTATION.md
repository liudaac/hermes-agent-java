# Phase 5 实现文档 - 高级功能

> 实现时间: 2026-04-29
> 版本: v1.0

## 概述

Phase 5 实现了容器化隔离、GPU 资源管理和自动扩缩容功能。

## 实现内容

### 1. 容器化隔离 (ContainerSandbox)

**文件**: `src/main/java/com/nousresearch/hermes/tenant/container/ContainerSandbox.java`

**功能**:
- Docker/Podman/Containerd 支持
- 完全文件系统隔离
- 网络隔离 (--network=none)
- 安全能力限制 (--cap-drop=ALL)
- GPU 透传支持 (--gpus=all)

**使用**:
```java
ContainerSandbox sandbox = new ContainerSandbox(
    context, 
    ContainerSandbox.ContainerRuntime.DOCKER,
    true  // GPU enabled
);

ProcessResult result = sandbox.exec(
    List.of("python3", "train.py"),
    ProcessOptions.builder()
        .maxMemoryMB(4096)
        .maxCpuCores(2.0)
        .gpuEnabled(true)
        .build()
);
```

### 2. GPU 资源管理 (GpuManager)

**文件**: `src/main/java/com/nousresearch/hermes/tenant/gpu/GpuManager.java`

**功能**:
- 自动检测 GPU (nvidia-smi)
- 按租户分配 GPU
- GPU 使用监控 (显存、利用率、温度)
- 显存限制

**使用**:
```java
GpuManager gpuManager = new GpuManager();

// 分配 GPU
Optional<Integer> gpu = gpuManager.allocateGpu("tenant-123");

// 获取 GPU 信息
gpuManager.getGpuInfo(gpu.get()).ifPresent(info -> {
    System.out.println("GPU: " + info.getName());
    System.out.println("Memory: " + info.getUsedMemoryMB() + " / " + info.getTotalMemoryMB());
    System.out.println("Utilization: " + info.getUtilizationPercent() + "%");
});

// 释放 GPU
gpuManager.releaseGpu("tenant-123");
```

### 3. 自动扩缩容 (TenantAutoscaler)

**文件**: `src/main/java/com/nousresearch/hermes/tenant/autoscaler/TenantAutoscaler.java`

**功能**:
- 定时评估租户负载 (默认 1 分钟)
- 基于 CPU/内存/存储使用率决策
- 扩容/缩容冷却期
- 策略可配置

**策略配置**:
```java
TenantAutoscaler.ScalingPolicy policy = new TenantAutoscaler.ScalingPolicy(
    0.8,   // 扩容阈值 (80%)
    0.3,   // 缩容阈值 (30%)
    5,     // 冷却期 (分钟)
    5,     // 最大扩容次数
    3,     // 最大缩容次数
    true   // 启用
);

autoscaler.setScalingPolicy("tenant-123", policy);
```

## 架构

```
┌─────────────────────────────────────────┐
│         TenantAutoscaler                │
│  - 1分钟评估周期                         │
│  - 自动扩缩容决策                         │
└─────────────────────────────────────────┘
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
┌───────────┐ ┌───────────┐ ┌───────────┐
│ Container │ │    GPU    │ │  Metrics  │
│  Sandbox  │ │  Manager  │ │ Collector │
└───────────┘ └───────────┘ └───────────┘
```

## 部署

### GPU 支持
```bash
# 需要 NVIDIA Container Toolkit
distribution=$(. /etc/os-release;echo $ID$VERSION_ID)
curl -s -L https://nvidia.github.io/nvidia-docker/gpgkey | sudo apt-key add -
curl -s -L https://nvidia.github.io/nvidia-docker/$distribution/nvidia-docker.list | \
    sudo tee /etc/apt/sources.list.d/nvidia-docker.list
sudo apt-get update && sudo apt-get install -y nvidia-container-toolkit
sudo systemctl restart docker
```

### 自动扩缩容
```yaml
# application.yml
hermes:
  autoscaler:
    enabled: true
    evaluation-interval: 60s
    default-policy:
      scale-up-threshold: 0.8
      scale-down-threshold: 0.3
      cooldown-minutes: 5
```

## 性能

| 功能 | 开销 |
|------|------|
| 容器启动 | 1-3s |
| GPU 检测 | 100ms |
| 扩缩容评估 | < 10ms |

## 相关文件

- `ContainerSandbox.java` - 容器化沙箱
- `GpuManager.java` - GPU 资源管理
- `TenantAutoscaler.java` - 自动扩缩容
- `PHASE5_IMPLEMENTATION.md` - 本文档
