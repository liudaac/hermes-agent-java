#!/usr/bin/env python3
"""
Safely clean historical test tenant directories from a Hermes home.

Default mode is dry-run. Use --apply to delete candidates.
Only tenant ids matching known test prefixes are eligible. The default tenant and
unknown names are never removed unless this script is edited intentionally.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
from pathlib import Path
from typing import Iterable

TEST_PATTERNS = [
    r"^browser-.*",
    r"^tenant-api-test-.*",
    r"^tenant-action-test-.*",
    r"^tenant-settings-test-.*",
    r"^missing-.*",
    r"^soak-tenant-.*",
    r"^agent-tenant-.*",
    r"^session-tenant-.*",
    r"^suspend-tenant-.*",
    r"^quota-tenant-.*",
    r"^fs-tenant-.*",
    r"^test-tenant-.*",
    r"^test-tenant$",
    r"^team-.*-test.*",
    r"^org-.*-test.*",
    r"^intent-.*-test.*",
]

PROTECTED = {"default", "global", "main", "production", "prod"}


def default_home() -> Path:
    return Path(os.environ.get("HERMES_HOME") or os.path.expanduser("~/.hermes"))


def is_test_tenant(tenant_id: str, patterns: Iterable[str] = TEST_PATTERNS) -> bool:
    if tenant_id in PROTECTED:
        return False
    return any(re.match(pattern, tenant_id) for pattern in patterns)


def discover(hermes_home: Path) -> list[dict]:
    tenants_dir = hermes_home / "tenants"
    if not tenants_dir.exists():
        return []
    candidates = []
    for path in sorted(tenants_dir.iterdir()):
        if not path.is_dir():
            continue
        tenant_id = path.name
        if not is_test_tenant(tenant_id):
            continue
        size = directory_size(path)
        candidates.append({"tenant_id": tenant_id, "path": str(path), "bytes": size})
    return candidates


def directory_size(path: Path) -> int:
    total = 0
    for root, _dirs, files in os.walk(path):
        for name in files:
            try:
                total += (Path(root) / name).stat().st_size
            except OSError:
                pass
    return total


def main() -> int:
    parser = argparse.ArgumentParser(description="Clean historical Hermes test tenants safely")
    parser.add_argument("--home", type=Path, default=default_home(), help="Hermes home; default HERMES_HOME or ~/.hermes")
    parser.add_argument("--apply", action="store_true", help="Actually delete candidates. Default is dry-run.")
    parser.add_argument("--json", action="store_true", help="Emit JSON report")
    args = parser.parse_args()

    candidates = discover(args.home)
    deleted = []
    errors = []

    if args.apply:
        for item in candidates:
            path = Path(item["path"])
            try:
                shutil.rmtree(path)
                deleted.append(item)
            except Exception as exc:  # pragma: no cover - best effort operational script
                errors.append({"tenant_id": item["tenant_id"], "path": item["path"], "error": str(exc)})

    report = {
        "home": str(args.home),
        "mode": "apply" if args.apply else "dry-run",
        "candidate_count": len(candidates),
        "deleted_count": len(deleted),
        "bytes": sum(item["bytes"] for item in candidates),
        "candidates": candidates,
        "deleted": deleted,
        "errors": errors,
    }

    if args.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        print(f"Hermes home: {args.home}")
        print(f"Mode: {report['mode']}")
        print(f"Candidates: {len(candidates)} ({report['bytes']} bytes)")
        for item in candidates[:50]:
            print(f" - {item['tenant_id']}  {item['bytes']} bytes  {item['path']}")
        if len(candidates) > 50:
            print(f" ... {len(candidates) - 50} more")
        if args.apply:
            print(f"Deleted: {len(deleted)}")
            if errors:
                print(f"Errors: {len(errors)}")
        else:
            print("Dry-run only. Re-run with --apply to delete these candidates.")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
