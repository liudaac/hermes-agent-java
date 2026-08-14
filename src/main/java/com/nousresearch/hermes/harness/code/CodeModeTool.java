package com.nousresearch.hermes.harness.code;

import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.approval.ToolRisk;
import com.nousresearch.hermes.tools.ToolEntry;

import java.util.List;
import java.util.Map;

/**
 * Tool that allows the model to execute Python code with tool access.
 * The model can write loops, conditionals, and batch operations.
 *
 * Register via: toolRegistry.register(new CodeModeTool(runtime).toToolEntry())
 */
public class CodeModeTool {

    private final CodeRuntime runtime;

    public CodeModeTool(CodeRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Build a ToolEntry for registration.
     */
    public ToolEntry toToolEntry() {
        return new ToolEntry.Builder()
            .name("run_code")
            .toolset("system")
            .schema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "code", Map.of(
                        "type", "string",
                        "description", "Python code to execute. Use `tools.tool_name(args)` to call Hermes tools. Print output to stdout. Return value via _hermes_output(value)."
                    )
                ),
                "required", List.of("code")
            ))
            .handler(args -> {
                String code = (String) args.get("code");
                if (code == null || code.isBlank()) {
                    return "{\"error\": \"code is required\"}";
                }

                CodeResult result = runtime.execute(code);
                String output = result.toToolResult();

                if (output == null || output.isEmpty()) {
                    return "{\"status\": \"ok\", \"output\": \"(no output)\"}";
                }
                return output;
            })
            .description("Execute Python code with access to Hermes tools. Use tools.<name>(**args) to call tools.")
            .emoji("🐍")
            .risk(ToolRisk.MEDIUM)
            .requiresApproval(false)
            .approvalType(ApprovalSystem.ApprovalType.CODE_EXECUTION)
            .build();
    }
}
