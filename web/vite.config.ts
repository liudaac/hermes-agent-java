import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "path";

const BACKEND = process.env.HERMES_DASHBOARD_URL ?? "http://127.0.0.1:9119";

/**
 * In production the Python `hermes dashboard` server injects a one-shot
 * session token into `index.html` (see `hermes_cli/web_server.py`). The
 * Vite dev server serves its own `index.html`, so unless we forward that
 * token, every protected `/api/*` call 401s.
 *
 * This plugin fetches the running dashboard's `index.html` on each dev page
 * load, scrapes the `window.__HERMES_SESSION_TOKEN__` assignment, and
 * re-injects it into the dev HTML. No-op in production builds.
 */
function hermesDevToken(): Plugin {
  const TOKEN_RE = /window\.__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"/;

  return {
    name: "hermes:dev-session-token",
    apply: "serve",
    async transformIndexHtml() {
      try {
        const res = await fetch(BACKEND, { headers: { accept: "text/html" } });
        const html = await res.text();
        console.log("[hermes] fetch raw response (first 500 chars):\n", html.slice(0, 500));
        const match = html.match(TOKEN_RE);
        if (!match) {
          console.warn(
            `[hermes] Could not find session token in ${BACKEND} — ` +
              `is \`hermes dashboard\` running? /api calls will 401.`,
          );
          return;
        }
        console.log(`[hermes] Session token acquired (${match[1].length} chars)`);
        return [
          {
            tag: "script",
            injectTo: "head",
            children: `window.__HERMES_SESSION_TOKEN__="${match[1]}";`,
          },
        ];
      } catch (err) {
        console.warn(
          `[hermes] Dashboard at ${BACKEND} unreachable — ` +
            `start it with \`hermes dashboard\` or set HERMES_DASHBOARD_URL. ` +
            `(${(err as Error).message})`,
        );
      }
    },
  };
}

export default defineConfig({
  plugins: [react(), tailwindcss(), hermesDevToken()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  build: {
    outDir: "../hermes_cli/web_dist",
    emptyOutDir: true,
    rollupOptions: {
      input: {
        // Legacy combined dashboard (ops + noc). Kept for backward compat.
        main: path.resolve(__dirname, "index.html"),
      },
    },
  },
  server: {
    port: 5174,
    proxy: {
      "/api": BACKEND,
      // When portal dev server is running, route any /portal/* request
      // there. The portal SPA reads the rest of the path on its own.
      "/portal": {
        target: "http://localhost:5175",
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/portal/, ""),
        ws: true,
      },
    },
  },
});
