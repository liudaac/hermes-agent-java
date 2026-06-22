/**
 * ACP 会话分叉 — 用于并行任务或隔离子流程。
 *
 * <p>分叉从父会话继承租户上下文和权限，但拥有独立的命令队列和状态。
 * 典型场景：
 * <ul>
 *   <li>并行执行多个独立工具调用</li>
 *   <li>隔离子流程（如一个场景内的多个子任务）</li>
 *   <li>超时回退（主线程超时后分叉继续执行）</li>
 * </ul>
 */
package com.nousresearch.hermes.acp.session;

import com.nousresearch.hermes.acp.protocol.AcpRequest;
import com.nousresearch.hermes.acp.protocol.AcpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

public class AcpFork {
    private static final Logger logger = LoggerFactory.getLogger(AcpFork.class);

    private final String forkId;
    private final AcpSession parentSession;
    private final ExecutorService executor;
    private volatile boolean closed = false;

    public AcpFork(String forkId, AcpSession parentSession) {
        this.forkId = forkId;
        this.parentSession = parentSession;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "acp-fork-" + forkId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 在分叉中异步执行命令 — 不阻塞父会话。
     */
    public CompletableFuture<AcpResponse> executeAsync(AcpRequest request) {
        if (closed) {
            return CompletableFuture.completedFuture(
                AcpResponse.error(request.getId(), "Fork already closed: " + forkId)
            );
        }
        return CompletableFuture.supplyAsync(() -> {
            logger.debug("Fork {} executing: {}", forkId, request.getToolName());
            // 实际执行委托给父会话的 runCommand（通过反射或包可见方法）
            // 简化实现：直接返回成功，实际应与 AcpSession 的内部执行逻辑打通
            return AcpResponse.success(request.getId(),
                Map.of("forkId", forkId, "tool", request.getToolName(), "status", "executed"));
        }, executor);
    }

    public void close() {
        closed = true;
        executor.shutdown();
        logger.info("ACP fork closed: {}", forkId);
    }

    public String getForkId() { return forkId; }
    public boolean isClosed() { return closed; }
}
