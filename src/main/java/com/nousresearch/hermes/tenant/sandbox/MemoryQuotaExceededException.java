package com.nousresearch.hermes.tenant.sandbox;

/**
 * 内存配额超出异常
 */
public class MemoryQuotaExceededException extends RuntimeException {
    
    public MemoryQuotaExceededException(String message) {
        super(message);
    }
    
    public MemoryQuotaExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}