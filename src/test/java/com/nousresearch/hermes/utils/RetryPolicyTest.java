package com.nousresearch.hermes.utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void succeedsWithoutRetry() {
        RetryPolicy retry = new RetryPolicy(3, Duration.ZERO, Duration.ZERO, 1.0);
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.execute("success", () -> {
            attempts.incrementAndGet();
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(1, attempts.get());
    }

    @Test
    void retriesUntilSuccess() {
        RetryPolicy retry = new RetryPolicy(3, Duration.ZERO, Duration.ZERO, 1.0);
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.execute("eventual-success", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("temporary");
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void throwsWhenRetriesExhausted() {
        RetryPolicy retry = new RetryPolicy(2, Duration.ZERO, Duration.ZERO, 1.0);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(RetryPolicy.RetryExhaustedException.class, () ->
            retry.execute("always-fails", () -> {
                attempts.incrementAndGet();
                throw new RuntimeException("boom");
            })
        );

        assertEquals(3, attempts.get(), "initial attempt + 2 retries");
    }

    @Test
    void doesNotRetryNonRetryableError() {
        RetryPolicy retry = new RetryPolicy(3, Duration.ZERO, Duration.ZERO, 1.0);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(RetryPolicy.RetryExhaustedException.class, () ->
            retry.executeWithCondition("non-retryable", () -> {
                attempts.incrementAndGet();
                throw new IllegalArgumentException("bad input");
            }, ex -> !(ex instanceof IllegalArgumentException))
        );

        assertEquals(1, attempts.get());
    }
}
