package com.nousresearch.hermes.tenant.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GPU 资源管理器
 * 
 * 管理 GPU 资源的分配和监控：
 * - 检测可用 GPU
 * - 按租户分配 GPU
 * - 监控 GPU 使用率
 * - 限制 GPU 显存
 */
public class GpuManager {

    private static final Logger logger = LoggerFactory.getLogger(GpuManager.class);
    
    // GPU 信息缓存
    private final Map<Integer, GpuInfo> gpuInfoMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> tenantGpuAllocation = new ConcurrentHashMap<>();
    
    // GPU 状态
    private volatile boolean initialized = false;
    private volatile int availableGpus = 0;
    
    // 显存限制（MB）
    private static final long DEFAULT_MEMORY_LIMIT_MB = 4096; // 4GB
    
    public GpuManager() {
        initialize();
    }
    
    /**
     * 初始化 GPU 管理器
     */
    private void initialize() {
        try {
            detectGpus();
            initialized = true;
            logger.info("GPU manager initialized. Available GPUs: {}", availableGpus);
        } catch (Exception e) {
            logger.warn("Failed to initialize GPU manager: {}", e.getMessage());
            initialized = false;
        }
    }
    
    /**
     * 检测可用 GPU
     */
    private void detectGpus() throws Exception {
        // 使用 nvidia-smi 检测 GPU
        Process process = new ProcessBuilder("nvidia-smi", "-L").start();
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            
            String line;
            int gpuIndex = 0;
            Pattern pattern = Pattern.compile("GPU (\\d+): (.+) \\(UUID: (.+)\\)");
            
            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    int index = Integer.parseInt(matcher.group(1));
                    String name = matcher.group(2).trim();
                    String uuid = matcher.group(3);
                    
                    GpuInfo info = new GpuInfo(index, name, uuid);
                    gpuInfoMap.put(index, info);
                    availableGpus++;
                }
            }
        }
        
        process.waitFor();
        
        // 获取每个 GPU 的详细信息
        for (GpuInfo info : gpuInfoMap.values()) {
            updateGpuStats(info);
        }
    }
    
    /**
     * 更新 GPU 统计信息
     */
    private void updateGpuStats(GpuInfo info) throws Exception {
        Process process = new ProcessBuilder(
            "nvidia-smi",
            "-i", String.valueOf(info.getIndex()),
            "--query-gpu=memory.total,memory.used,memory.free,utilization.gpu,temperature.gpu",
            "--format=csv,noheader,nounits"
        ).start();
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            
            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.split(",");
                if (parts.length >= 5) {
                    info.setTotalMemoryMB(Long.parseLong(parts[0].trim()));
                    info.setUsedMemoryMB(Long.parseLong(parts[1].trim()));
                    info.setFreeMemoryMB(Long.parseLong(parts[2].trim()));
                    info.setUtilizationPercent(Integer.parseInt(parts[3].trim()));
                    info.setTemperatureCelsius(Integer.parseInt(parts[4].trim()));
                    info.setLastUpdate(System.currentTimeMillis());
                }
            }
        }
        
        process.waitFor();
    }
    
    /**
     * 为租户分配 GPU
     */
    public synchronized Optional<Integer> allocateGpu(String tenantId) {
        if (!initialized || availableGpus == 0) {
            return Optional.empty();
        }
        
        // 检查租户是否已有分配
        if (tenantGpuAllocation.containsKey(tenantId)) {
            return Optional.of(tenantGpuAllocation.get(tenantId));
        }
        
        // 查找最空闲的 GPU
        GpuInfo bestGpu = gpuInfoMap.values().stream()
            .filter(gpu -> !tenantGpuAllocation.containsValue(gpu.getIndex()))
            .min(Comparator.comparingLong(GpuInfo::getUsedMemoryMB))
            .orElse(null);
        
        if (bestGpu != null) {
            tenantGpuAllocation.put(tenantId, bestGpu.getIndex());
            logger.info("Allocated GPU {} to tenant: {}", bestGpu.getIndex(), tenantId);
            return Optional.of(bestGpu.getIndex());
        }
        
        return Optional.empty();
    }
    
    /**
     * 释放租户的 GPU
     */
    public synchronized void releaseGpu(String tenantId) {
        Integer gpuIndex = tenantGpuAllocation.remove(tenantId);
        if (gpuIndex != null) {
            logger.info("Released GPU {} from tenant: {}", gpuIndex, tenantId);
        }
    }
    
    /**
     * 获取租户的 GPU 索引
     */
    public Optional<Integer> getTenantGpu(String tenantId) {
        return Optional.ofNullable(tenantGpuAllocation.get(tenantId));
    }
    
    /**
     * 获取 GPU 信息
     */
    public Optional<GpuInfo> getGpuInfo(int gpuIndex) {
        GpuInfo info = gpuInfoMap.get(gpuIndex);
        if (info != null) {
            try {
                updateGpuStats(info);
            } catch (Exception e) {
                logger.warn("Failed to update GPU stats", e);
            }
        }
        return Optional.ofNullable(info);
    }
    
    /**
     * 获取所有 GPU 信息
     */
    public List<GpuInfo> getAllGpuInfo() {
        for (GpuInfo info : gpuInfoMap.values()) {
            try {
                updateGpuStats(info);
            } catch (Exception e) {
                logger.warn("Failed to update GPU stats", e);
            }
        }
        return new ArrayList<>(gpuInfoMap.values());
    }
    
    /**
     * 设置 GPU 显存限制
     */
    public boolean setMemoryLimit(String tenantId, long memoryLimitMB) {
        Optional<Integer> gpuOpt = getTenantGpu(tenantId);
        if (gpuOpt.isEmpty()) {
            return false;
        }
        
        // 使用 nvidia-smi 设置显存限制（需要 root 权限）
        // 这里仅记录限制，实际执行需要在容器中设置
        logger.info("Set GPU memory limit for tenant {}: {} MB", tenantId, memoryLimitMB);
        return true;
    }
    
    /**
     * 检查 GPU 是否可用
     */
    public boolean isGpuAvailable() {
        return initialized && availableGpus > 0;
    }
    
    /**
     * 获取可用 GPU 数量
     */
    public int getAvailableGpuCount() {
        return availableGpus;
    }
    
    /**
     * 生成 GPU 报告
     */
    public String generateReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== GPU Report ===\n");
        report.append("Available GPUs: ").append(availableGpus).append("\n\n");
        
        for (GpuInfo info : getAllGpuInfo()) {
            report.append("GPU ").append(info.getIndex()).append(": ").append(info.getName()).append("\n");
            report.append("  UUID: ").append(info.getUuid()).append("\n");
            report.append("  Memory: ").append(info.getUsedMemoryMB()).append(" / ")
                  .append(info.getTotalMemoryMB()).append(" MB\n");
            report.append("  Utilization: ").append(info.getUtilizationPercent()).append("%\n");
            report.append("  Temperature: ").append(info.getTemperatureCelsius()).append("°C\n");
            report.append("\n");
        }
        
        report.append("Tenant Allocations:\n");
        for (Map.Entry<String, Integer> entry : tenantGpuAllocation.entrySet()) {
            report.append("  ").append(entry.getKey()).append(" -> GPU ").append(entry.getValue()).append("\n");
        }
        
        return report.toString();
    }
    
    /**
     * GPU 信息类
     */
    public static class GpuInfo {
        private final int index;
        private final String name;
        private final String uuid;
        
        private long totalMemoryMB;
        private long usedMemoryMB;
        private long freeMemoryMB;
        private int utilizationPercent;
        private int temperatureCelsius;
        private long lastUpdate;
        
        public GpuInfo(int index, String name, String uuid) {
            this.index = index;
            this.name = name;
            this.uuid = uuid;
        }
        
        // Getters and setters
        public int getIndex() { return index; }
        public String getName() { return name; }
        public String getUuid() { return uuid; }
        public long getTotalMemoryMB() { return totalMemoryMB; }
        public void setTotalMemoryMB(long totalMemoryMB) { this.totalMemoryMB = totalMemoryMB; }
        public long getUsedMemoryMB() { return usedMemoryMB; }
        public void setUsedMemoryMB(long usedMemoryMB) { this.usedMemoryMB = usedMemoryMB; }
        public long getFreeMemoryMB() { return freeMemoryMB; }
        public void setFreeMemoryMB(long freeMemoryMB) { this.freeMemoryMB = freeMemoryMB; }
        public int getUtilizationPercent() { return utilizationPercent; }
        public void setUtilizationPercent(int utilizationPercent) { this.utilizationPercent = utilizationPercent; }
        public int getTemperatureCelsius() { return temperatureCelsius; }
        public void setTemperatureCelsius(int temperatureCelsius) { this.temperatureCelsius = temperatureCelsius; }
        public long getLastUpdate() { return lastUpdate; }
        public void setLastUpdate(long lastUpdate) { this.lastUpdate = lastUpdate; }
    }
}
