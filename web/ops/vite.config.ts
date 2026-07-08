import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BACKEND = process.env.HERMES_DASHBOARD_URL ?? "http://127.0.0.1:9119";

/**
 * Forward the dashboard-issued session token from the Python backend
 * into the dev HTML, so /api/* calls authenticate. No-op in production.
 */
function hermesDevToken(): Plugin {
  const TOKEN_RE = /window\.__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"/;
  return {
    name: "hermes-ops:dev-session-token",
    apply: "serve",
    async transformIndexHtml() {
      try {
        const res = await fetch(BACKEND, { headers: { accept: "text/html" } });
        const html = await res.text();
        const match = html.match(TOKEN_RE);
        if (!match) {
          console.warn(`[ops] No session token in ${BACKEND} — start \`hermes dashboard\`.`);
          return;
        }
        return [
          {
            tag: "head",
            injectTo: "head",
            children: `window.__HERMES_SESSION_TOKEN__="${match[1]}";`,
          },
        ];
      } catch (err) {
        console.warn(`[ops] Dashboard unreachable (${(err as Error).message}).`);
      }
    },
  };
}

export default defineConfig({
  root: __dirname,
  base: "/ops/",
  plugins: [react(), tailwindcss(), hermesDevToken()],
  build: {
    outDir: path.resolve(__dirname, "../../hermes_cli/web_dist/ops"),
    emptyOutDir: true,
    rollupOptions: {
      input: path.resolve(__dirname, "index.html"),
    },
  },
  server: {
    port: 5176,
    proxy: {
      "/api": BACKEND,
    },
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
      "@hermes/ui": path.resolve(__dirname, "../packages/ui/src"),
      "@hermes/jarvis": path.resolve(__dirname, "../packages/jarvis/src"),
    },
  },
});
