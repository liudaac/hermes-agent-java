package com.nousresearch.hermes.harness.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/**
 * Executes Python code in a subprocess with access to tools via JSON-RPC.
 *
 * Protocol:
 * 1. Python script writes JSON to stdout: {"method": "tool", "name": "read_file", "args": {...}}
 * 2. Java reads the JSON, calls the tool, writes the result back to the Python script's stdin
 * 3. Python script reads the result and continues
 * 4. When the script finishes, stdout contains the final output
 *
 * The Python helper module `hermes_bridge` is injected as a preamble.
 */
public class CodeRuntime {
    private static final Logger logger = LoggerFactory.getLogger(CodeRuntime.class);

    private static final int TIMEOUT_SECONDS = 30;
    private static final String PYTHON_BIN = findPython();

    /**
     * The Python bridge code that provides a `tools` object for calling Hermes tools.
     */
    private static final String BRIDGE_PREAMBLE = """
import json, sys, io

class _HermesBridge:
    def __init__(self):
        self._results = {}

    def __getattr__(self, name):
        def _call(**kwargs):
            request = json.dumps({"method": "tool", "name": name, "args": kwargs})
            print("__HERMES_REQUEST__" + request, flush=True)
            response_line = sys.stdin.readline()
            if not response_line:
                raise RuntimeError(f"No response from Hermes for tool {name}")
            response = json.loads(response_line.strip())
            if response.get("error"):
                return response["error"]
            return response.get("result", "")

        return _call

tools = _HermesBridge()

def _hermes_output(value):
    print("__HERMES_OUTPUT__" + json.dumps({"return": str(value) if value is not None else None}), flush=True)
""";

    private final BiFunction<String, Map<String, Object>, String> toolExecutor;

    /**
     * Create a CodeRuntime.
     * @param toolExecutor function: (toolName, args) -> result string
     */
    public CodeRuntime(BiFunction<String, Map<String, Object>, String> toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    /**
     * Execute Python code with tool access.
     */
    public CodeResult execute(String code) {
        long start = System.currentTimeMillis();

        // Write the full script: bridge preamble + user code + output marker
        String fullScript = BRIDGE_PREAMBLE + "\n" + code + "\n_hermes_output(None)\n";

        Path scriptFile = null;
        try {
            scriptFile = Files.createTempFile("hermes_code_", ".py");
            Files.writeString(scriptFile, fullScript, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(PYTHON_BIN, "-u", scriptFile.toString());
            pb.redirectErrorStream(false);

            Process process = pb.start();

            // We need to read stdout and stderr concurrently while the process runs,
            // because the process may block on stdout pipe if we don't read it.
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            String[] returnValue = {null};

            BufferedReader stdoutReader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            BufferedReader stderrReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
            BufferedWriter stdinWriter = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            // Read stderr in a separate thread
            Thread stderrThread = Thread.startVirtualThread(() -> {
                try {
                    String errLine;
                    while ((errLine = stderrReader.readLine()) != null) {
                        stderr.append(errLine).append("\n");
                    }
                } catch (IOException e) {
                    // ignore
                }
            });

            // Read stdout in the main thread (need to process tool requests inline)
            String line;
            while ((line = stdoutReader.readLine()) != null) {
                if (line.startsWith("__HERMES_REQUEST__")) {
                    // Parse tool request
                    String json = line.substring("__HERMES_REQUEST__".length());
                    try {
                        var request = parseJson(json);
                        String toolName = (String) request.get("name");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> args = (Map<String, Object>) request.get("args");
                        String result = toolExecutor.apply(toolName, args);
                        // Send result back
                        String response = jsonEncode(result);
                        stdinWriter.write(response + "\n");
                        stdinWriter.flush();
                    } catch (Exception e) {
                        logger.error("Tool call from code failed: {}", e.getMessage());
                        String errorResp = jsonEncodeError(e.getMessage());
                        stdinWriter.write(errorResp + "\n");
                        stdinWriter.flush();
                    }
                } else if (line.startsWith("__HERMES_OUTPUT__")) {
                    String json = line.substring("__HERMES_OUTPUT__".length());
                    try {
                        var output = parseJson(json);
                        returnValue[0] = (String) output.get("return");
                    } catch (Exception e) {
                        // ignore parse errors
                    }
                } else {
                    stdout.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                long duration = System.currentTimeMillis() - start;
                return CodeResult.failure("Code execution timed out after " + TIMEOUT_SECONDS + "s", -1, duration);
            }

            // Wait for stderr thread to finish
            stderrThread.join(2000);

            int exitCode = process.exitValue();
            long duration = System.currentTimeMillis() - start;

            if (exitCode == 0) {
                return CodeResult.success(stdout.toString().trim(), returnValue[0], duration);
            } else {
                return new CodeResult(stdout.toString().trim(), stderr.toString().trim(),
                    returnValue[0], exitCode, duration, false);
            }

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            logger.error("Code execution failed: {}", e.getMessage());
            return CodeResult.failure(e.getMessage(), -1, duration);
        } finally {
            if (scriptFile != null) {
                try { Files.deleteIfExists(scriptFile); } catch (Exception ignored) {}
            }
        }
    }

    private static String findPython() {
        for (String candidate : new String[]{"python3", "/root/miniconda/bin/python3", "python"}) {
            try {
                Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {}
        }
        return "python3";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
    }

    private String jsonEncode(String result) {
        try {
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("result", result);
            resp.put("error", null);
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(resp);
        } catch (Exception e) {
            return "{\"result\":\"\",\"error\":null}";
        }
    }

    private String jsonEncodeError(String error) {
        try {
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("result", null);
            resp.put("error", error);
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(resp);
        } catch (Exception e) {
            return "{\"result\":null,\"error\":\"unknown\"}";
        }
    }
}
