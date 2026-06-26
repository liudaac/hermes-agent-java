package com.nousresearch.hermes.business.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional inputs for a template-clone operation. All fields nullable.
 */
public class CloneRequest {
    public String workspaceId;
    public String workspaceName;
    public String owner;
    public Map<String, Object> extraMetadata = new LinkedHashMap<>();

    /** Internal flag set by the clone service. */
    boolean workspaceCreatedThisCall;

    public static CloneRequest empty() { return new CloneRequest(); }
}
