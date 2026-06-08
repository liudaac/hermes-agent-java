package com.nousresearch.hermes.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Agent negotiation engine for resolving conflicts between collaborative agents.
 * 
 * <p>When two or more agents disagree on a decision (e.g., which technology to use,
 * which approach to take), the Negotiator facilitates structured resolution:</p>
 * 
 * <ol>
 *   <li><b>Propose</b> — Agent makes a proposal with confidence and reasoning</li>
 *   <li><b>Counter</b> — Other agent responds with accept / reject / counter-proposal</li>
 *   <li><b>Resolve</b> — Agreement, compromise, or escalation</li>
 * </ol>
 */
public class Negotiator {
    private static final Logger logger = LoggerFactory.getLogger(Negotiator.class);
    
    private final TenantBus bus;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    
    // Active negotiations keyed by proposal ID
    private final ConcurrentHashMap<String, Negotiation> activeNegotiations = new ConcurrentHashMap<>();
    
    private static final int MAX_ROUNDS = 3;
    private static final long ROUND_TIMEOUT_MS = 60_000;
    
    public Negotiator(TenantBus bus) {
        this.bus = bus;
    }
    
    /**
     * Start a negotiation between two agents.
     * 
     * @param proposerId the agent making the proposal
     * @param reviewerId the agent reviewing it
     * @param topic what the negotiation is about
     * @param proposal the proposed decision
     * @param confidence how confident the proposer is (0.0-1.0)
     * @param reasoning why the proposer believes this is correct
     */
    public Negotiation startNegotiation(
            String proposerId, String reviewerId,
            String topic, String proposal,
            double confidence, String reasoning) {
        
        Negotiation neg = new Negotiation(
            proposerId, reviewerId, topic, proposal, confidence, reasoning);
        
        activeNegotiations.put(neg.id, neg);
        
        // Send proposal to reviewer
        AgentMessage msg = AgentMessage.builder(proposerId, reviewerId, AgentMessage.Type.PROPOSE)
            .action("review_proposal")
            .payload(Map.of(
                "negotiationId", neg.id,
                "topic", topic,
                "proposal", proposal,
                "confidence", confidence,
                "reasoning", reasoning
            ))
            .timeoutMs(ROUND_TIMEOUT_MS)
            .build();
        
        bus.send(msg);
        
        logger.info("Negotiation started: {} → {} on '{}'", proposerId, reviewerId, topic);
        
        return neg;
    }
    
    /**
     * Handle a counter-proposal or response from the reviewing agent.
     */
    public Negotiator.Result handleResponse(String negotiationId, String responderId,
                                              boolean accepted, String counterProposal,
                                              String reason) {
        Negotiation neg = activeNegotiations.get(negotiationId);
        if (neg == null) {
            return new Negotiator.Result(Negotiation.Resolution.ERROR, "Unknown negotiation: " + negotiationId);
        }
        
        neg.addRound(responderId, accepted, counterProposal, reason);
        
        if (accepted) {
            neg.resolve(Negotiation.Resolution.AGREED, reason);
            activeNegotiations.remove(negotiationId);
            logger.info("Negotiation {} resolved: AGREED", negotiationId);
            return neg.getResult();
        }
        
        if (neg.rounds.size() >= MAX_ROUNDS) {
            // Max rounds reached — escalate
            neg.resolve(Negotiation.Resolution.ESCALATED, "Max rounds exceeded");
            activeNegotiations.remove(negotiationId);
            
            // Send escalation
            AgentMessage escalation = AgentMessage.builder(
                neg.proposerId, "*", AgentMessage.Type.ESCALATE)
                .action("negotiation_escalated")
                .payload(Map.of(
                    "negotiationId", neg.id,
                    "topic", neg.topic,
                    "history", neg.getRoundHistory()
                ))
                .build();
            bus.send(escalation);
            
            logger.warn("Negotiation {} escalated after {} rounds", negotiationId, MAX_ROUNDS);
            return neg.getResult();
        }
        
        // Send counter-proposal back to proposer
        AgentMessage counter = AgentMessage.builder(responderId, neg.proposerId, AgentMessage.Type.PROPOSE)
            .action("counter_proposal")
            .replyTo(negotiationId)
            .payload(Map.of(
                "negotiationId", neg.id,
                "counterProposal", counterProposal,
                "reason", reason
            ))
            .timeoutMs(ROUND_TIMEOUT_MS)
            .build();
        bus.send(counter);
        
        logger.info("Negotiation {} round {}: counter from {}", negotiationId, neg.rounds.size(), responderId);
        
        return new Result(Negotiation.Resolution.ONGOING, "Round " + neg.rounds.size());
    }
    
    /**
     * Auto-negotiate: when confidence is high enough, skip negotiation.
     * When low, automatically escalate to human/senior agent.
     */
    public Negotiator.Result autoNegotiate(String proposerId, String topic, 
                                             String proposal, double confidence) {
        if (confidence >= 0.85) {
            return new Result(Negotiation.Resolution.AGREED, 
                "Auto-approved: high confidence (" + percent(confidence) + ")");
        }
        if (confidence < 0.4) {
            return new Result(Negotiation.Resolution.ESCALATED,
                "Auto-escalated: low confidence (" + percent(confidence) + ")");
        }
        // Medium confidence — needs peer review
        return new Result(Negotiation.Resolution.NEEDS_REVIEW,
            "Needs peer review: confidence " + percent(confidence));
    }
    
    private static String percent(double v) {
        return String.format("%.0f%%", v * 100);
    }
    
    /**
     * Get active negotiation count.
     */
    public int activeCount() {
        return activeNegotiations.size();
    }
    
    // ---- Data classes ----
    
    public static class Negotiation {
        public enum Resolution { ONGOING, AGREED, ESCALATED, REJECTED, TIMEOUT, ERROR, NEEDS_REVIEW }
        
        public final String id = UUID.randomUUID().toString().substring(0, 8);
        public final String proposerId;
        public final String reviewerId;
        public final String topic;
        public final String proposal;
        public final double confidence;
        public final String reasoning;
        public final List<Round> rounds = new ArrayList<>();
        private Result result;
        
        Negotiation(String proposerId, String reviewerId, String topic,
                    String proposal, double confidence, String reasoning) {
            this.proposerId = proposerId;
            this.reviewerId = reviewerId;
            this.topic = topic;
            this.proposal = proposal;
            this.confidence = confidence;
            this.reasoning = reasoning;
        }
        
        void addRound(String responderId, boolean accepted, String counterProposal, String reason) {
            rounds.add(new Round(responderId, accepted, counterProposal, reason));
        }
        
        void resolve(Resolution resolution, String reason) {
            this.result = new Negotiator.Result(resolution, reason);
        }
        
        public Result getResult() {
            return result != null ? result : new Negotiator.Result(Resolution.ONGOING, "");
        }
        
        public List<Map<String, Object>> getRoundHistory() {
            List<Map<String, Object>> history = new ArrayList<>();
            for (Round r : rounds) {
                history.add(Map.of(
                    "responder", r.responderId,
                    "accepted", r.accepted,
                    "counter", r.counterProposal != null ? r.counterProposal : "",
                    "reason", r.reason != null ? r.reason : ""
                ));
            }
            return history;
        }
    }
    
    public static class Round {
        public final String responderId;
        public final boolean accepted;
        public final String counterProposal;
        public final String reason;
        
        Round(String responderId, boolean accepted, String counterProposal, String reason) {
            this.responderId = responderId;
            this.accepted = accepted;
            this.counterProposal = counterProposal;
            this.reason = reason;
        }
    }
    
    public static class Result {
        public final Negotiation.Resolution resolution;
        public final String detail;
        
        public Result(Negotiation.Resolution resolution, String detail) {
            this.resolution = resolution;
            this.detail = detail;
        }
        
        public boolean isResolved() {
            return resolution != Negotiation.Resolution.ONGOING;
        }
        
        public boolean needsHuman() {
            return resolution == Negotiation.Resolution.ESCALATED
                || resolution == Negotiation.Resolution.NEEDS_REVIEW;
        }
    }
}