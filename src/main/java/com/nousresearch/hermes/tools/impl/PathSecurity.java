package com.nousresearch.hermes.tools.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared path validation helpers for tool implementations.
 * Mirrors Python tools/path_security.py
 *
 * Extracts the resolve() + relative_to() and .. traversal check
 * patterns previously duplicated across tool implementations.
 */
public class PathSecurity {
    private static final Logger logger = LoggerFactory.getLogger(PathSecurity.class);

    private PathSecurity() {
        // Utility class
    }

    /**
     * Ensure path resolves to a location within root.
     *
     * Returns an error message string if validation fails, or null if the
     * path is safe. Uses Path.normalize() to follow symlinks and normalize
     * .. components.
     *
     * @param path Path to validate
     * @param root Allowed root directory
     * @return Error message or null if safe
     */
    public static String validateWithinDir(Path path, Path root) {
        try {
            Path resolved = path.toAbsolutePath().normalize();
            Path rootResolved = root.toAbsolutePath().normalize();

            // Check if resolved path starts with root
            if (!resolved.startsWith(rootResolved)) {
                return "Path escapes allowed directory: " + resolved;
            }

            return null;
        } catch (Exception e) {
            return "Path validation error: " + e.getMessage();
        }
    }

    /**
     * Ensure path resolves to a location within root (string version).
     *
     * @param pathStr Path string to validate
     * @param rootStr Root directory string
     * @return Error message or null if safe
     */
    public static String validateWithinDir(String pathStr, String rootStr) {
        return validateWithinDir(Paths.get(pathStr), Paths.get(rootStr));
    }

    /**
     * Return true if path string contains .. traversal components.
     *
     * Quick check for obvious traversal attempts before doing full resolution.
     *
     * @param pathStr Path string to check
     * @return true if contains traversal
     */
    public static boolean hasTraversalComponent(String pathStr) {
        if (pathStr == null || pathStr.isEmpty()) {
            return false;
        }

        // Split by path separators
        String[] parts = pathStr.split("[\\\\/]");

        for (String part : parts) {
            if ("..".equals(part)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Validate that path is within allowed directories.
     *
     * @param path Path to validate
     * @param allowedRoots Allowed root directories
     * @return Error message or null if safe
     */
    public static String validateWithinDirs(Path path, Path... allowedRoots) {
        try {
            Path resolved = path.toAbsolutePath().normalize();

            for (Path root : allowedRoots) {
                Path rootResolved = root.toAbsolutePath().normalize();
                if (resolved.startsWith(rootResolved)) {
                    return null;
                }
            }

            return "Path escapes all allowed directories: " + resolved;
        } catch (Exception e) {
            return "Path validation error: " + e.getMessage();
        }
    }

    /**
     * Sanitize a filename to prevent directory traversal.
     *
     * @param filename Filename to sanitize
     * @return Sanitized filename
     */
    public static String sanitizeFilename(String filename) {
        if (filename == null) {
            return null;
        }

        // Remove path separators and traversal components
        return filename
            .replace("..", "_")
            .replace("/", "_")
            .replace("\\", "_")
            .replace(":", "_")
            .trim();
    }

    /**
     * Check if path is absolute.
     *
     * @param pathStr Path string
     * @return true if absolute
     */
    public static boolean isAbsolute(String pathStr) {
        if (pathStr == null || pathStr.isEmpty()) {
            return false;
        }

        // Unix absolute path
        if (pathStr.startsWith("/")) {
            return true;
        }

        // Windows absolute path
        if (pathStr.length() >= 2 && pathStr.charAt(1) == ':') {
            return true;
        }

        // Windows UNC path
        if (pathStr.startsWith("\\\\")) {
            return true;
        }

        return false;
    }

    /**
     * Resolve path relative to base, with security checks.
     *
     * @param base Base directory
     * @param path Path to resolve (may be relative or absolute)
     * @return Resolved path or null if invalid
     */
    public static Path resolveSecure(Path base, String path) {
        try {
            Path resolved;

            if (isAbsolute(path)) {
                // For absolute paths, validate within base
                resolved = Paths.get(path).toAbsolutePath().normalize();
                Path baseResolved = base.toAbsolutePath().normalize();

                if (!resolved.startsWith(baseResolved)) {
                    logger.warn("Absolute path escapes base: {}", path);
                    return null;
                }
            } else {
                // For relative paths, resolve against base
                resolved = base.resolve(path).toAbsolutePath().normalize();
            }

            return resolved;
        } catch (Exception e) {
            logger.error("Error resolving path: {}", path, e);
            return null;
        }
    }
}
