import { cpSync, existsSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");

function syncDir(sourceRelative, targetRelative, label) {
  const source = resolve(root, sourceRelative);
  const target = resolve(root, targetRelative);

  if (!existsSync(source)) {
    console.warn(`[sync-assets] ${label} source not found: ${source}`);
    console.warn(`[sync-assets] keeping existing ${targetRelative} if present`);
    return;
  }

  rmSync(target, { recursive: true, force: true });
  cpSync(source, target, { recursive: true });
  console.log(`[sync-assets] copied ${label}: ${sourceRelative} -> ${targetRelative}`);
}

syncDir("node_modules/@nous-research/ui/dist/fonts", "public/fonts", "fonts");
syncDir("node_modules/@nous-research/ui/dist/assets", "public/ds-assets", "design-system assets");
