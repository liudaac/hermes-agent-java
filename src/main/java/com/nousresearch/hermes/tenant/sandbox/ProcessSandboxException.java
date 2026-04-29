package com.nousresearch.hermes.tenant.sandbox;

/**
 * 进程沙箱异常
 */
public class ProcessSandboxException extends RuntimeException {

    public ProcessSandboxException(String message) {
        super(message);
    }

    public ProcessSandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
