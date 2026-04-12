package com.nousresearch.hermes;

import com.nousresearch.hermes.agent.IterationBudget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for iteration budget.
 */
public class IterationBudgetTest {
    
    @Test
    void testConsume() {
        IterationBudget budget = new IterationBudget(5);
        
        assertTrue(budget.hasRemaining());
        assertEquals(5, budget.getRemaining());
        
        assertTrue(budget.consume());
        assertEquals(4, budget.getRemaining());
        assertEquals(1, budget.getUsed());
    }
    
    @Test
    void testExhaustBudget() {
        IterationBudget budget = new IterationBudget(2);
        
        assertTrue(budget.consume());
        assertTrue(budget.consume());
        assertFalse(budget.consume()); // Exhausted
        
        assertEquals(0, budget.getRemaining());
        assertFalse(budget.hasRemaining());
    }
    
    @Test
    void testRefund() {
        IterationBudget budget = new IterationBudget(5);
        
        budget.consume();
        budget.consume();
        assertEquals(2, budget.getUsed());
        
        budget.refund();
        assertEquals(1, budget.getUsed());
        assertEquals(4, budget.getRemaining());
    }
    
    @Test
    void testRefundAtZero() {
        IterationBudget budget = new IterationBudget(5);
        
        budget.refund(); // Should not go negative
        assertEquals(0, budget.getUsed());
    }
    
    @Test
    void testReset() {
        IterationBudget budget = new IterationBudget(5);
        
        budget.consume();
        budget.consume();
        assertEquals(2, budget.getUsed());
        
        budget.reset();
        assertEquals(0, budget.getUsed());
        assertEquals(5, budget.getRemaining());
    }
}
