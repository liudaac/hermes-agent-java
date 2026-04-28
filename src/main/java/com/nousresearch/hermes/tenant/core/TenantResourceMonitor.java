package com.nousresearch.hermes.tenant.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 租户资源监控器
 */
public class TenantResourceMonitor {
    private static final Logger logger = LoggerFactory.getLogger(TenantResourceMonitor.class);
    
    private final TenantContext context;
    
    public TenantResourceMonitor(TenantContext context) {
        this.context = context;
    }
    
    public void shutdown() {
        // 停止监控
    }
}
