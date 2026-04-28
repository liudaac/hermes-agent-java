package com.nousresearch.hermes.tenant.tools;

import com.nousresearch.hermes.approval.ApprovalResult;
import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantSkill;
import com.nousresearch.hermes.tenant.core.TenantSkillManager;
import com.nousresearch.hermes.tenant.quota.TenantQuotaManager;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * 租户感知的 Skill 执行工具
 * 
 * 为 Skill 执行提供：
 * - 代码隔离（Python/JS 在租户沙箱执行）
 * - 资源限制（时间、内存、CPU）
 * - 权限检查
 * - 审计日志
 */
public class TenantAwareSkillTool {
    private static final Logger logger = LoggerFactory.getLogger(TenantAwareSkillTool.class);
    
    // 需要沙箱执行的代码块类型
    private static final Set<String> SANDBOXED_LANGUAGES = Set.of("python", "py", "javascript", "js", "bash", "sh");
    
    // Python 危险导入模式
    private static final List<Pattern> PYTHON_DANGEROUS_IMPORTS = List.of(
        Pattern.compile("import\\s+os\\s*\\n"),
        Pattern.compile("import\\s+subprocess"),
        Pattern.compile("import\\s+sys\\s*\\n"),
        Pattern.compile("from\\s+os\\s+import"),
        Pattern.compile("__import__\\s*\\("),
        Pattern.compile("exec\\s*\\("),
        Pattern.compile("compile\\s*\\("),
        Pattern.compile("open\\s*\\(")  // 文件操作需要审查
    );
    
    // JavaScript 危险模式
    private static final List<Pattern> JS_DANGEROUS_PATTERNS = List.of(
        Pattern.compile("require\\s*\\(\\s*['\"]child_process"),
        Pattern.compile("require\\s*\\(\\s*['\"]fs['\"]\\s*\\)"),
        Pattern.compile("process\\.exit"),
        Pattern.compile("eval\\s*\\("),
        Pattern.compile("Function\\s*\\("),
        Pattern.compile("new\\s+Function"),
        Pattern.compile("child_process")
    );
    
    private final TenantContext context;
    private final TenantSkillManager skillManager;
    private final ApprovalSystem approvalSystem;
    private final Path skillSandboxDir;
    private final Path tempDir;
    
    public TenantAwareSkillTool(TenantContext context, TenantSkillManager skillManager) {
        this.context = context;
        this.skillManager = skillManager;
        this.approvalSystem = new ApprovalSystem();
        this.skillSandboxDir = context.getFileSandbox().getSandboxPath().resolve("skills").resolve("execution");
        this.tempDir = skillSandboxDir.resolve("temp");
        
        try {
            Files.createDirectories(skillSandboxDir);
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            logger.error("Tenant {}: Failed to create skill sandbox: {}", 
                context.getTenantId(), e.getMessage());
        }
    }
    
    public void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
            .name("execute_skill")
            .toolset("skills")
            .schema(Map.of(
                "description", "Execute a skill with tenant isolation",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "skill_name", Map.of("type", "string"),
                        "args", Map.of("type", "object"),
                        "timeout", Map.of("type", "integer", "default", 60),
                        "sandbox", Map.of("type", "boolean", "default", true)
                    ),
                    "required", List.of("skill_name")
                )
            ))
            .handler(this::executeSkill).emoji("🎯").build());
    }
    
    /**
     * 执行 Skill，自动检测并隔离其中的代码
     */
    private String executeSkill(Map<String, Object> args) {
        String skillName = (String) args.get("skill_name");
        @SuppressWarnings("unchecked")
        Map<String, Object> skillArgs = args.containsKey("args") 
            ? (Map<String, Object>) args.get("args") 
            : java.util.Collections.emptyMap();
        int timeout = args.containsKey("timeout") 
            ? ((Number) args.get("timeout")).intValue() 
            : 60;
        boolean useSandbox = args.containsKey("sandbox") 
            ? (Boolean) args.get("sandbox") 
            : true;
        
        // 1. 检查配额
        try {
            context.getQuotaManager().checkToolCallQuota();
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("quota")) {
                return ToolRegistry.toolError(e.getMessage());
            }
            throw e;
        }
        
        // 2. 获取 Skill
        TenantSkill skill = skillManager.getSkill(skillName);
        if (skill == null) {
            return ToolRegistry.toolError("Skill not found: " + skillName);
        }
        
        // 3. 执行前安全检查
        if (useSandbox && containsCodeBlocks(skill.content())) {
            SecurityCheckResult check = checkSkillSecurity(skill);
            if (!check.isSafe()) {
                logger.warn("Tenant {}: Skill {} security check failed: {}", 
                    context.getTenantId(), skillName, check.getReason());
                return ToolRegistry.toolError("Skill security check failed: " + check.getReason());
            }
        }
        
        // 4. 审批（高权限 Skill）
        if (skill.isReadOnly() || skill.tags().contains("dangerous")) {
            ApprovalResult approval = approvalSystem.requestApproval(
                ApprovalSystem.ApprovalType.SKILL_INSTALL,
                "Execute skill: " + skillName, null);
            if (!approval.isApproved()) {
                return ToolRegistry.toolError("Approval denied for skill execution");
            }
        }
        
        // 5. 执行（根据内容决定是否在沙箱中）
        String result;
        if (useSandbox && containsCodeBlocks(skill.content())) {
            result = executeInSandbox(skill, skillArgs, timeout);
        } else {
            result = executeDirectly(skill, skillArgs);
        }
        
        // 6. 审计日志
        context.getAuditLogger().log(
            com.nousresearch.hermes.tenant.audit.AuditEvent.SKILL_EXECUTED,
            Map.of(
                "tenantId", context.getTenantId(),
                "skillId", skill.id(),
                "skillName", skillName,
                "sandboxed", useSandbox,
                "success", !result.contains("\"error\"")
            )
        );
        
        return result;
    }
    
    /**
     * 在沙箱中执行包含代码的 Skill
     */
    private String executeInSandbox(TenantSkill skill, Map<String, Object> args, int timeout) {
        String content = skill.content();
        List<CodeBlock> codeBlocks = extractCodeBlocks(content);
        
        StringBuilder output = new StringBuilder();
        output.append("{\n");
        output.append("  \"skill_execution\": {\n");
        output.append("    \"skill\": \"").append(skill.name()).append("\",\n");
        output.append("    \"sandboxed\": true,\n");
        output.append("    \"results\": [\n");
        
        int blockNum = 0;
        for (CodeBlock block : codeBlocks) {
            blockNum++;
            if (blockNum > 1) output.append(",\n");
            
            String blockResult = executeCodeBlock(block, args, timeout);
            output.append("      ").append(blockResult);
        }
        
        output.append("\n    ]\n");
        output.append("  }\n");
        output.append("}");
        
        return output.toString();
    }
    
    /**
     * 直接执行 Skill（无代码块）
     */
    private String executeDirectly(TenantSkill skill, Map<String, Object> args) {
        // 对于无代码的 Skill，直接返回内容或处理模板
        String content = skill.content();
        
        // 简单的模板替换
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            content = content.replace("{{" + entry.getKey() + "}}", 
                String.valueOf(entry.getValue()));
        }
        
        return ToolRegistry.toolResult(Map.of(
            "skill", skill.name(),
            "content", content,
            "sandboxed", false
        ));
    }
    
    /**
     * 执行单个代码块
     */
    private String executeCodeBlock(CodeBlock block, Map<String, Object> args, int timeout) {
        String code = block.code();
        
        // 替换模板变量
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            code = code.replace("{{" + entry.getKey() + "}}", 
                String.valueOf(entry.getValue()));
        }
        
        try {
            return switch (block.language().toLowerCase()) {
                case "python", "py" -> executePythonSandboxed(code, timeout);
                case "javascript", "js" -> executeJavaScriptSandboxed(code, timeout);
                case "bash", "sh" -> executeBashSandboxed(code, timeout);
                default -> "{\"language\": \"" + block.language() + "\", \"status\": \"unsupported\"}";
            };
        } catch (Exception e) {
            logger.error("Tenant {}: Code block execution failed: {}", 
                context.getTenantId(), e.getMessage());
            return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
    
    /**
     * 在沙箱中执行 Python
     */
    private String executePythonSandboxed(String code, int timeout) throws Exception {
        // 包装代码以限制资源
        String wrappedCode = wrapPythonForSandbox(code);
        
        Path scriptFile = tempDir.resolve("skill_py_" + System.currentTimeMillis() + ".py");
        Files.writeString(scriptFile, wrappedCode);
        
        ProcessBuilder pb = new ProcessBuilder("python3", scriptFile.toString());
        pb.directory(skillSandboxDir.toFile());
        
        // 隔离环境
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("HOME", skillSandboxDir.toString());
        env.put("TMPDIR", tempDir.toString());
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        env.put("PYTHONUNBUFFERED", "1");
        env.put("PYTHONPATH", skillSandboxDir.toString());
        
        return executeProcess(pb, timeout, "python");
    }
    
    /**
     * 在沙箱中执行 JavaScript
     */
    private String executeJavaScriptSandboxed(String code, int timeout) throws Exception {
        String wrappedCode = wrapJavaScriptForSandbox(code);
        
        Path scriptFile = tempDir.resolve("skill_js_" + System.currentTimeMillis() + ".js");
        Files.writeString(scriptFile, wrappedCode);
        
        ProcessBuilder pb = new ProcessBuilder("node", scriptFile.toString());
        pb.directory(skillSandboxDir.toFile());
        
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("HOME", skillSandboxDir.toString());
        env.put("TMPDIR", tempDir.toString());
        
        return executeProcess(pb, timeout, "javascript");
    }
    
    /**
     * 在沙箱中执行 Bash
     */
    private String executeBashSandboxed(String code, int timeout) throws Exception {
        // 限制 Bash 命令
        String restrictedCode = restrictBashCommands(code);
        
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", restrictedCode);
        pb.directory(skillSandboxDir.toFile());
        
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("HOME", skillSandboxDir.toString());
        env.put("TMPDIR", tempDir.toString());
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        
        return executeProcess(pb, timeout, "bash");
    }
    
    /**
     * 执行进程并获取输出
     */
    private String executeProcess(ProcessBuilder pb, int timeout, String language) throws Exception {
        Process process = pb.start();
        
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<String> stdoutFuture = executor.submit(() -> readStream(process.getInputStream()));
        Future<String> stderrFuture = executor.submit(() -> readStream(process.getErrorStream()));
        
        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        
        String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
        String stderr = stderrFuture.get(5, TimeUnit.SECONDS);
        
        executor.shutdownNow();
        
        if (!finished) {
            process.destroyForcibly();
            return "{\"language\": \"" + language + "\", \"status\": \"timeout\", \"timeout\": " + timeout + "}";
        }
        
        int exitCode = process.exitValue();
        
        return "{\"language\": \"" + language + "\", \"exit_code\": " + exitCode + 
               ", \"stdout\": \"" + escapeJson(stdout) + "\", " +
               "\"stderr\": \"" + escapeJson(stderr) + "\"}";
    }
    
    // ============ 安全检查 ============
    
    private SecurityCheckResult checkSkillSecurity(TenantSkill skill) {
        String content = skill.content();
        
        // 提取所有代码块
        List<CodeBlock> blocks = extractCodeBlocks(content);
        
        for (CodeBlock block : blocks) {
            String code = block.code().toLowerCase();
            String lang = block.language().toLowerCase();
            
            // Python 安全检查
            if (lang.equals("python") || lang.equals("py")) {
                for (Pattern pattern : PYTHON_DANGEROUS_IMPORTS) {
                    if (pattern.matcher(code).find()) {
                        return SecurityCheckResult.failed(
                            "Python code contains dangerous import: " + pattern.pattern());
                    }
                }
            }
            
            // JavaScript 安全检查
            if (lang.equals("javascript") || lang.equals("js")) {
                for (Pattern pattern : JS_DANGEROUS_PATTERNS) {
                    if (pattern.matcher(code).find()) {
                        return SecurityCheckResult.failed(
                            "JavaScript code contains dangerous pattern: " + pattern.pattern());
                    }
                }
            }
        }
        
        return SecurityCheckResult.passed();
    }
    
    private boolean containsCodeBlocks(String content) {
        return content.contains("```") || content.contains("~~~");
    }
    
    private List<CodeBlock> extractCodeBlocks(String content) {
        List<CodeBlock> blocks = new ArrayList<>();
        Pattern pattern = Pattern.compile("```(\\w+)?\\n(.*?)```", Pattern.DOTALL);
        var matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String lang = matcher.group(1) != null ? matcher.group(1) : "text";
            String code = matcher.group(2).trim();
            blocks.add(new CodeBlock(lang, code));
        }
        
        return blocks;
    }
    
    // ============ 代码包装 ============
    
    private String wrapPythonForSandbox(String code) {
        return """
            import sys
            import resource
            
            # Resource limits
            try:
                resource.setrlimit(resource.RLIMIT_AS, (512*1024*1024, 512*1024*1024))
                resource.setrlimit(resource.RLIMIT_CPU, (60, 60))
                resource.setrlimit(resource.RLIMIT_NOFILE, (64, 64))
            except:
                pass
            
            # Sandbox directory
            import os
            os.chdir('%s')
            
            # User code
            """.formatted(skillSandboxDir.toString().replace("'", "'\"'\"'"))
            + "\n" + code;
    }
    
    private String wrapJavaScriptForSandbox(String code) {
        return """
            // Sandbox setup
            process.chdir('%s');
            
            // Limit memory (Node.js hint)
            if (global.gc) global.gc();
            
            // User code
            """.formatted(skillSandboxDir.toString().replace("'", "'\"'\"'"))
            + "\n" + code;
    }
    
    private String restrictBashCommands(String code) {
        // 拒绝危险命令
        String[] dangerous = {"rm -rf", "mkfs", "dd if=/dev/zero", "> /dev/sda", "curl.*|.*sh"};
        for (String cmd : dangerous) {
            if (code.matches(".*" + cmd + ".*")) {
                throw new SecurityException("Dangerous bash command detected: " + cmd);
            }
        }
        return code;
    }
    
    // ============ 工具方法 ============
    
    private String readStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\\n");
                if (sb.length() > 10000) {
                    sb.append("... [truncated]");
                    break;
                }
            }
        }
        return sb.toString();
    }
    
    private String escapeJson(String str) {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    // ============ 记录类 ============
    
    private record CodeBlock(String language, String code) {}
    
    private static class SecurityCheckResult {
        private final boolean safe;
        private final String reason;
        
        private SecurityCheckResult(boolean safe, String reason) {
            this.safe = safe;
            this.reason = reason;
        }
        
        static SecurityCheckResult passed() {
            return new SecurityCheckResult(true, null);
        }
        
        static SecurityCheckResult failed(String reason) {
            return new SecurityCheckResult(false, reason);
        }
        
        boolean isSafe() { return safe; }
        String getReason() { return reason; }
    }
}
