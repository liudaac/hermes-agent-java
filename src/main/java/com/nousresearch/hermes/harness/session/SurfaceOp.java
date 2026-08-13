package com.nousresearch.hermes.harness.session;

/**
 * Surface operation for a session event.
 *
 * <p>Surface events use one of two ops:</p>
 * <ul>
 *   <li>{@link #APPEND} - the event appends to the surface tail (normal)</li>
 *   <li>{@link #replace(long, long)} - the event replaces surface range [start, end]</li>
 * </ul>
 *
 * <p>Non-surface events have a null surface op.</p>
 */
public sealed interface SurfaceOp permits SurfaceOp.Append, SurfaceOp.Replace {

    /** Append to surface tail. */
    record Append() implements SurfaceOp {}

    /** Replace inclusive surface range [start, end] with this event. */
    record Replace(long start, long end) implements SurfaceOp {}

    /** The standard append singleton. */
    SurfaceOp APPEND = new Append();

    /** Create a replace op. */
    static SurfaceOp replace(long start, long end) {
        return new Replace(start, end);
    }
}
