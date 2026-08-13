package com.nousresearch.hermes.harness.session;

import com.nousresearch.hermes.model.ModelMessage;

import java.util.*;

/**
 * Folds session events into the model-visible message history.
 *
 * <p>This is the pure function that derives {@code List<ModelMessage>}
 * from {@code List<SessionEvent>}. It handles:</p>
 * <ul>
 *   <li>Surface events: USER_MESSAGE, ASSISTANT_MESSAGE, TOOL_RESULT</li>
 *   <li>APPEND op: add to surface tail</li>
 *   <li>REPLACE op: replace the shadowed range in-place, preserving position</li>
 *   <li>Non-surface events: skipped (trace/boundary/audit)</li>
 * </ul>
 */
public final class SessionSurfaceFolder {
    private SessionSurfaceFolder() {}

    /**
     * Fold events into model-visible messages.
     *
     * @param events all session events in order (will not be modified)
     * @return unmodifiable list of model messages
     */
    public static List<ModelMessage> fold(List<SessionEvent> events) {
        // Use a list to preserve insertion order; track which seqs are visible
        List<Long> visibleSeqs = new ArrayList<>();
        Map<Long, ModelMessage> seqToMessage = new HashMap<>();

        for (SessionEvent event : events) {
            if (!event.isSurface()) continue;

            SurfaceOp op = event.surfaceOp();
            if (op == null) continue;

            if (op instanceof SurfaceOp.Append) {
                ModelMessage msg = eventToMessage(event);
                if (msg != null) {
                    visibleSeqs.add(event.seq());
                    seqToMessage.put(event.seq(), msg);
                }
            } else if (op instanceof SurfaceOp.Replace r) {
                // Find the position of the first replaced seq
                int insertPos = -1;
                for (int i = 0; i < visibleSeqs.size(); i++) {
                    long seq = visibleSeqs.get(i);
                    if (seq >= r.start() && seq <= r.end()) {
                        if (insertPos == -1) insertPos = i;
                    }
                }
                // Remove all replaced seqs
                Iterator<Long> it = visibleSeqs.iterator();
                while (it.hasNext()) {
                    long seq = it.next();
                    if (seq >= r.start() && seq <= r.end()) {
                        it.remove();
                        seqToMessage.remove(seq);
                    }
                }
                // Insert the replacing event at the position of the first removed seq
                ModelMessage msg = eventToMessage(event);
                if (msg != null) {
                    if (insertPos == -1) {
                        visibleSeqs.add(event.seq());
                    } else {
                        visibleSeqs.add(insertPos, event.seq());
                    }
                    seqToMessage.put(event.seq(), msg);
                }
            }
        }

        // Build the result in visible order
        List<ModelMessage> result = new ArrayList<>(visibleSeqs.size());
        for (long seq : visibleSeqs) {
            ModelMessage msg = seqToMessage.get(seq);
            if (msg != null) result.add(msg);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Convert a surface event to a ModelMessage.
     */
    private static ModelMessage eventToMessage(SessionEvent event) {
        Map<String, Object> data = event.data();
        String content = (String) data.getOrDefault("content", "");
        String role = switch (event.type()) {
            case USER_MESSAGE -> "user";
            case ASSISTANT_MESSAGE -> "assistant";
            case TOOL_RESULT -> "tool";
            default -> null;
        };
        if (role == null) return null;

        String toolCallId = (String) data.get("toolCallId");

        if ("tool".equals(role) && toolCallId != null) {
            return ModelMessage.tool(content, toolCallId);
        }
        return new ModelMessage(role, content);
    }

    /**
     * Get the current surface event seqs (for debugging/inspection).
     */
    public static List<Long> surfaceSeqs(List<SessionEvent> events) {
        List<Long> visibleSeqs = new ArrayList<>();
        for (SessionEvent event : events) {
            if (!event.isSurface() || event.surfaceOp() == null) continue;
            if (event.surfaceOp() instanceof SurfaceOp.Append) {
                visibleSeqs.add(event.seq());
            } else if (event.surfaceOp() instanceof SurfaceOp.Replace r) {
                int insertPos = -1;
                for (int i = 0; i < visibleSeqs.size(); i++) {
                    long seq = visibleSeqs.get(i);
                    if (seq >= r.start() && seq <= r.end()) {
                        if (insertPos == -1) insertPos = i;
                    }
                }
                final long start = r.start();
                final long end = r.end();
                visibleSeqs.removeIf(seq -> seq >= start && seq <= end);
                if (insertPos == -1) {
                    visibleSeqs.add(event.seq());
                } else {
                    visibleSeqs.add(insertPos, event.seq());
                }
            }
        }
        return visibleSeqs;
    }
}
