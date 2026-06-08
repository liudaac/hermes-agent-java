package com.nousresearch.hermes.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;

/**
 * DAG-based task orchestrator for multi-agent workflows.
 * Tasks can depend on each other; the orchestrator ensures correct
 * execution order while maximizing parallelism.
 */
public class TaskOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(TaskOrchestrator.class);
    private final TenantBus bus;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, Pipeline> pipelines = new ConcurrentHashMap<>();
    private static final long DEFAULT_STEP_TIMEOUT_MS = 300_000;
    
    public TaskOrchestrator(TenantBus bus) { this.bus = bus; }
    
    public Pipeline orchestrate(String name, List<Step> steps) {
        Pipeline pipe = new Pipeline(name, steps);
        pipelines.put(pipe.id, pipe);
        executor.submit(() -> run(pipe));
        logger.info("Pipeline '{}' started ({} steps)", name, steps.size());
        return pipe;
    }
    
    private void run(Pipeline p) {
        p.status = Pipeline.Status.RUNNING;
        Set<String> done = ConcurrentHashMap.newKeySet();
        Set<String> failed = ConcurrentHashMap.newKeySet();
        try {
            while (p.status == Pipeline.Status.RUNNING) {
                List<Step> ready = new ArrayList<>();
                for (Step s : p.steps)
                    if (!done.contains(s.name) && !failed.contains(s.name)
                        && (s.dependsOn.isEmpty() || done.containsAll(s.dependsOn)))
                        ready.add(s);
                if (ready.isEmpty()) {
                    p.status = failed.isEmpty() ? Pipeline.Status.COMPLETED : Pipeline.Status.PARTIAL;
                    break;
                }
                List<Future<StepResult>> futures = new ArrayList<>();
                for (Step s : ready)
                    futures.add(executor.submit(() -> exec(s, s.timeoutMs > 0 ? s.timeoutMs : DEFAULT_STEP_TIMEOUT_MS)));
                for (int i = 0; i < futures.size(); i++) {
                    StepResult r = futures.get(i).get();
                    p.results.put(r.name, r);
                    if (r.success) {
                        done.add(r.name);
                        for (Step s : p.steps)
                            if (s.dependsOn.contains(r.name) && r.output != null)
                                s.payload.put("_from_" + r.name, r.output);
                    } else {
                        failed.add(r.name);
                        p.errors.add(r.name + ": " + r.error);
                    }
                }
            }
        } catch (Exception e) {
            p.status = Pipeline.Status.FAILED;
            p.errorMessage = e.getMessage();
        }
        logger.info("Pipeline '{}' done: {} (ok={}, fail={})", p.name, p.status, done.size(), failed.size());
    }
    
    private StepResult exec(Step s, long timeout) {
        try {
            AgentMessage reply = bus.sendAndWait(
                AgentMessage.builder(s.pipelineId, s.assignedTo, AgentMessage.Type.REQUEST)
                    .action(s.action).payload(s.payload).timeoutMs(timeout).build(), timeout);
            return new StepResult(s.name, true, reply.getResultText(), reply.getPayload());
        } catch (Exception e) {
            return new StepResult(s.name, false, e.getMessage(), null);
        }
    }
    
    public Pipeline getPipeline(String id) { return pipelines.get(id); }
    public List<Pipeline> listPipelines() { return new ArrayList<>(pipelines.values()); }
    
    public static class Step {
        public final String name, assignedTo, action;
        public final Set<String> dependsOn = new LinkedHashSet<>();
        public Map<String, Object> payload = new HashMap<>();
        public long timeoutMs;
        public String pipelineId;
        public Step(String n, String to, String a) { name=n; assignedTo=to; action=a; }
        public Step dependsOn(String... ns) { Collections.addAll(dependsOn, ns); return this; }
        public Step payload(String k, Object v) { payload.put(k, v); return this; }
        public Step timeoutMs(long ms) { this.timeoutMs = ms; return this; }
    }
    
    public static class StepResult {
        public final String name;
        public final boolean success;
        public final String error;
        public final Map<String, Object> output;
        public StepResult(String n, boolean s, String e, Map<String, Object> o) {
            name=n; success=s; error=e; output=o;
        }
    }
    
    public static class Pipeline {
        public enum Status { PENDING, RUNNING, COMPLETED, PARTIAL, FAILED }
        public final String id = UUID.randomUUID().toString().substring(0, 8);
        public final String name;
        public final List<Step> steps;
        public final Map<String, StepResult> results = new ConcurrentHashMap<>();
        public final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        public volatile Status status = Status.PENDING;
        public String errorMessage;
        Pipeline(String n, List<Step> ss) {
            name=n; steps=new ArrayList<>(ss);
            for (Step s : steps) s.pipelineId = id;
        }
        public double progress() { return steps.isEmpty() ? 1.0 : (double)results.size()/steps.size(); }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",id); m.put("name",name); m.put("status",status.name());
            m.put("progress",progress()); m.put("steps",steps.size()); m.put("done",results.size());
            m.put("errors",new ArrayList<>(errors));
            if (errorMessage!=null) m.put("error",errorMessage);
            return m;
        }
    }
}