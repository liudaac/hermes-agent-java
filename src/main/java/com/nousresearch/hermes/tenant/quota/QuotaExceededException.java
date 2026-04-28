package com.nousresearch.hermes.tenant.quota;

/**
 * 配额超出异常
 */
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) {
        super(message);
    }
}
