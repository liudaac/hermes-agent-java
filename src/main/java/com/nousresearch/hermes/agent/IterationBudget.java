package com.nousresearch.hermes.agent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe iteration counter for an agent.
 * Each agent gets its own budget capped at max_iterations.
 */
public class IterationBudget {
    private final int maxTotal;
    private final AtomicInteger used;
    
    public IterationBudget(int maxTotal) {
        this.maxTotal = maxTotal;
        this.used = new AtomicInteger(0);
    }
    
    /**
     * Try to consume one iteration.
     * @return true if allowed
     */
    public boolean consume() {
        int current = used.get();
        if (current >= maxTotal) {
            return false;
        }
        return used.compareAndSet(current, current + 1) || consume();
    }
    
    /**
     * Give back one iteration.
     */
    public void refund() {
        used.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }
    
    /**
     * Get used iterations.
     */
    public int getUsed() {
        return used.get();
    }
    
    /**
     * Get remaining iterations.
     */
    public int getRemaining() {
        return Math.max(0, maxTotal - used.get());
    }
    
    /**
     * Check if has remaining iterations.
     */
    public boolean hasRemaining() {
        return used.get() < maxTotal;
    }
    
    /**
     * Reset the budget.
     */
    public void reset() {
        used.set(0);
    }
}
