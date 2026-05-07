package com.nousresearch.hermes.tenant.sandbox;

/**
 * 网络沙箱异常基类
 */
public class NetworkSandboxException extends Exception {

    public NetworkSandboxException(String message) {
        super(message);
    }

    public NetworkSandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
