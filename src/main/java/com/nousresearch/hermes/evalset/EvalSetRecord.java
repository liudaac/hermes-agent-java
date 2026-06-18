package com.nousresearch.hermes.evalset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** EvalSet — a collection of test cases for a scenario, used to compare blueprint versions. */
public class EvalSetRecord {
    private String workspaceId;
    private String scenarioId;
    private String evalSetId;
    private String name;
    private String description;
    private List<EvalCase> cases = new ArrayList<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public String getWorkspaceId() { return workspaceId; }
    public EvalSetRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public String getScenarioId() { return scenarioId; }
    public EvalSetRecord setScenarioId(String scenarioId) { this.scenarioId = scenarioId; return this; }
    public String getEvalSetId() { return evalSetId; }
    public EvalSetRecord setEvalSetId(String evalSetId) { this.evalSetId = evalSetId; return this; }
    public String getName() { return name; }
    public EvalSetRecord setName(String name) { this.name = name; return this; }
    public String getDescription() { return description; }
    public EvalSetRecord setDescription(String description) { this.description = description; return this; }
    public List<EvalCase> getCases() { return cases; }
    public EvalSetRecord setCases(List<EvalCase> cases) { this.cases = cases != null ? cases : new ArrayList<>(); return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public EvalSetRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }

    /** Single test case within an eval set. */
    public static class EvalCase {
        private String caseId;
        private String name;
        private String input;
        private String expectedOutput;
        private List<String> successCriteria = new ArrayList<>();

        public String getCaseId() { return caseId; }
        public EvalCase setCaseId(String caseId) { this.caseId = caseId; return this; }
        public String getName() { return name; }
        public EvalCase setName(String name) { this.name = name; return this; }
        public String getInput() { return input; }
        public EvalCase setInput(String input) { this.input = input; return this; }
        public String getExpectedOutput() { return expectedOutput; }
        public EvalCase setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; return this; }
        public List<String> getSuccessCriteria() { return successCriteria; }
        public EvalCase setSuccessCriteria(List<String> successCriteria) { this.successCriteria = successCriteria != null ? successCriteria : new ArrayList<>(); return this; }
    }
}
