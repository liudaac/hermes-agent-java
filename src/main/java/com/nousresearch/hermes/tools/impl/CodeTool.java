package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Code execution and analysis tools.
 * Run Python, JavaScript, Java code and analyze code quality.
 */
public class CodeTool {
    private static final Logger logger = LoggerFactory.getLogger(CodeTool.class);
    private static final long MAX_OUTPUT_CHARS = 10000;
    private static final long EXECUTION_TIMEOUT_MS = 30000;
    
    /**
     * Register code tools.
     */
    public static void register(ToolRegistry registry) {
        // python
        registry.register(new ToolRegistry.Builder()
            .name("python")
            .toolset("code_execution")
            .schema(Map.of(
                "description", "Execute Python code",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "code", Map.of(
                            "type", "string",
                            "description", "Python code to execute"
                        ),
                        "packages", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "Required packages (will try to install)"
                        )
                    ),
                    "required", List.of("code")
                )
            ))
            .handler(CodeTool::executePython)
            .emoji("🐍")
            .build());
        
        // javascript
        registry.register(new ToolRegistry.Builder()
            .name("javascript")
            .toolset("code_execution")
            .schema(Map.of(
                "description", "Execute JavaScript/Node.js code",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "code", Map.of(
                            "type", "string",
                            "description", "JavaScript code to execute"
                        )
                    ),
                    "required", List.of("code")
                )
            ))
            .handler(CodeTool::executeJavaScript)
            .emoji("📜")
            .build());
        
        // java_compile
        registry.register(new ToolRegistry.Builder()
            .name("java_compile")
            .toolset("code_execution")
            .schema(Map.of(
                "description", "Compile and run Java code",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "code", Map.of(
                            "type", "string",
                            "description", "Java code to compile and run"
                        ),
                        "className", Map.of(
                            "type", "string",
                            "description", "Class name (optional, auto-detected)"
                        )
                    ),
                    "required", List.of("code")
                )
            ))
            .handler(CodeTool::executeJava)
            .emoji("☕")
            .build());
        
        // code_review
        registry.register(new ToolRegistry.Builder()
            .name("code_review")
            .toolset("code_execution")
            .schema(Map.of(
                "description", "Review code for issues and improvements",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "code", Map.of(
                            "type", "string",
                            "description", "Code to review"
                        ),
                        "language", Map.of(
                            "type", "string",
                            "description", "Programming language"
                        )
                    ),
                    "required", List.of("code", "language")
                )
            ))
            .handler(CodeTool::reviewCode)
            .emoji("🔍")
            .build());
    }
    
    /**
     * Execute Python code.
     */
    private static String executePython(Map<String, Object> args) {
        String code = (String) args.get("code");
        @SuppressWarnings("unchecked")
        List<String> packages = (List<String>) args.getOrDefault("packages", List.of());
        
        if (code == null || code.trim().isEmpty()) {
            return ToolRegistry.toolError("Code is required");
        }
        
        try {
            // Create temp file
            Path tempDir = Files.createTempDirectory("hermes_python_");
            Path scriptFile = tempDir.resolve("script.py");
            Files.writeString(scriptFile, code, StandardCharsets.UTF_8);
            
            // Install packages if specified
            if (!packages.isEmpty()) {
                for (String pkg : packages) {
                    installPythonPackage(pkg);
                }
            }
            
            // Execute
            ProcessBuilder pb = new ProcessBuilder("python3", scriptFile.toString());
            pb.directory(tempDir.toFile());
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            // Read output with timeout
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                long startTime = System.currentTimeMillis();
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > MAX_OUTPUT_CHARS || 
                        System.currentTimeMillis() - startTime > EXECUTION_TIMEOUT_MS) {
                        output.append("\n... [truncated or timed out]");
                        process.destroyForcibly();
                        break;
                    }
                }
            }
            
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            int exitCode = finished ? process.exitValue() : -1;
            
            // Cleanup
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (Exception e) {}
                });
            
            return ToolRegistry.toolResult(Map.of(
                "language", "python",
                "exit_code", exitCode,
                "output", output.toString(),
                "success", exitCode == 0
            ));
            
        } catch (Exception e) {
            logger.error("Python execution failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute JavaScript code.
     */
    private static String executeJavaScript(Map<String, Object> args) {
        String code = (String) args.get("code");
        
        if (code == null || code.trim().isEmpty()) {
            return ToolRegistry.toolError("Code is required");
        }
        
        
        try {
            // Create temp file
            Path tempDir = Files.createTempDirectory("hermes_js_");
            Path scriptFile = tempDir.resolve("script.js");
            Files.writeString(scriptFile, code, StandardCharsets.UTF_8);
            
            // Execute with node
            ProcessBuilder pb = new ProcessBuilder("node", scriptFile.toString());
            pb.directory(tempDir.toFile());
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > MAX_OUTPUT_CHARS) {
                        output.append("\n... [truncated]");
                        process.destroyForcibly();
                        break;
                    }
                }
            }
            
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            int exitCode = finished ? process.exitValue() : -1;
            
            // Cleanup
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (Exception e) {}
                });
            
            return ToolRegistry.toolResult(Map.of(
                "language", "javascript",
                "exit_code", exitCode,
                "output", output.toString(),
                "success", exitCode == 0
            ));
            
        } catch (Exception e) {
            logger.error("JavaScript execution failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute Java code.
     */
    private static String executeJava(Map<String, Object> args) {
        String code = (String) args.get("code");
        String className = (String) args.get("className");
        
        if (code == null || code.trim().isEmpty()) {
            return ToolRegistry.toolError("Code is required");
        }
        
        try {
            // Auto-detect class name
            if (className == null || className.isEmpty()) {
                className = extractClassName(code);
                if (className == null) {
                    className = "Main";
                    if (!code.contains("class ")) {
                        code = "public class Main {\n" + code + "\n}";
                    }
                }
            }
            
            Path tempDir = Files.createTempDirectory("hermes_java_");
            Path sourceFile = tempDir.resolve(className + ".java");
            Files.writeString(sourceFile, code, StandardCharsets.UTF_8);
            
            // Compile
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return ToolRegistry.toolError("Java compiler not available");
            }
            
            int compileResult = compiler.run(null, null, null, sourceFile.toString());
            if (compileResult != 0) {
                return ToolRegistry.toolResult(Map.of(
                    "language", "java",
                    "exit_code", compileResult,
                    "output", "Compilation failed",
                    "success", false
                ));
            }
            
            // Run
            ProcessBuilder pb = new ProcessBuilder("java", "-cp", tempDir.toString(), className);
            pb.directory(tempDir.toFile());
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > MAX_OUTPUT_CHARS) {
                        output.append("\n... [truncated]");
                        process.destroyForcibly();
                        break;
                    }
                }
            }
            
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            int exitCode = finished ? process.exitValue() : -1;
            
            // Cleanup
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (Exception e) {}
                });
            
            return ToolRegistry.toolResult(Map.of(
                "language", "java",
                "exit_code", exitCode,
                "output", output.toString(),
                "success", exitCode == 0
            ));
            
        } catch (Exception e) {
            logger.error("Java execution failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }
    
    /**
     * Review code.
     */
    private static String reviewCode(Map<String, Object> args) {
        String code = (String) args.get("code");
        String language = (String) args.get("language");
        
        if (code == null || code.trim().isEmpty()) {
            return ToolRegistry.toolError("Code is required");
        }
        
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> suggestions = new ArrayList<>();
        
        if (code.length() > 1000) {
            suggestions.add(Map.of(
                "type", "style",
                "message", "Consider breaking into smaller functions",
                "severity", "info"
            ));
        }
        
        return ToolRegistry.toolResult(Map.of(
            "language", language,
            "issues", issues,
            "suggestions", suggestions,
            "lines", code.split("\\n").length
        ));
    }
    
    // Helper methods
    private static void installPythonPackage(String pkg) {
        try {
            ProcessBuilder pb = new ProcessBuilder("pip3", "install", "-q", pkg);
            pb.inheritIO();
            Process process = pb.start();
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("Failed to install package {}: {}", pkg, e.getMessage());
        }
    }
    
    private static String extractClassName(String code) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("public\\s+class\\s+(\\w+)");
        java.util.regex.Matcher matcher = pattern.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
