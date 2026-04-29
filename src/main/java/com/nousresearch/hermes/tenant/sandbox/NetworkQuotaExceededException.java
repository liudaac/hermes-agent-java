package com.nousresearch.hermes.tenant.sandbox;

/**
 * 网络配额超出异常
 */
public class NetworkQuotaExceededException extends NetworkSandboxException {

    public NetworkQuotaExceededException(String message) {
        super(message);
    }

    public NetworkQuotaExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
