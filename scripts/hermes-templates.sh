#!/usr/bin/env bash
# Hermes Business Templates CLI
# Manage agent / scenario YAML templates against a running Hermes server.
#
# Usage:
#   hermes-templates.sh list
#   hermes-templates.sh list --type agent
#   hermes-templates.sh upload <path-to-yaml> [--type agent|scenario] [--author you@x.com]
#   hermes-templates.sh upload-dir <directory> [--author you@x.com]
#   hermes-templates.sh delete <templateId>
#   hermes-templates.sh validate <path-to-yaml>
#
# Env:
#   HERMES_BASE_URL  default http://127.0.0.1:8080
#   HERMES_TOKEN     optional bearer token

set -euo pipefail

BASE_URL="${HERMES_BASE_URL:-http://127.0.0.1:8080}"
AUTH_HEADER=()
if [[ -n "${HERMES_TOKEN:-}" ]]; then
  AUTH_HEADER=(-H "Authorization: Bearer ${HERMES_TOKEN}")
fi

die() { echo "❌ $*" >&2; exit 1; }
log() { echo "▶ $*"; }

req() {
  local method="$1" path="$2"
  shift 2
  curl -fsS "${AUTH_HEADER[@]}" -X "$method" "$BASE_URL$path" "$@"
}

detect_type() {
  local file="$1"
  if grep -qE "^involved_agents:|^clone_blueprint:|^workflow_timeline:" "$file"; then
    echo scenario
  else
    echo agent
  fi
}

require_python() {
  if ! command -v python3 >/dev/null 2>&1; then
    die "需要 python3 来解析 YAML/JSON"
  fi
}

yaml_to_jsonbody() {
  # File → JSON {"yaml": "...", "author": "..."} body
  require_python
  python3 - "$1" "$2" <<'PY'
import json, sys
path, author = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as fh:
    body = fh.read()
print(json.dumps({"yaml": body, "author": author or ""}))
PY
}

cmd_list() {
  local filter_type=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --type) filter_type="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  log "GET /api/v1/business/user-templates"
  local raw
  raw=$(req GET /api/v1/business/user-templates)
  require_python
  python3 - "$filter_type" <<PY <<<"$raw"
import json, sys
ft = sys.argv[1].strip().lower()
data = json.load(sys.stdin)
items = data.get("items", [])
if ft:
    items = [i for i in items if (i.get("type") or "").lower() == ft]
if not items:
    print("（没有用户上传的模板）")
else:
    print(f"{'TYPE':<10}{'TEMPLATE_ID':<32}{'CATEGORY':<12}{'AUTHOR':<24}{'NAME'}")
    for it in items:
        meta = it.get("meta") or {}
        print(f"{(it.get('type') or '')[:9]:<10}{(it.get('templateId') or '')[:31]:<32}"
              f"{(it.get('category') or '')[:11]:<12}{(meta.get('author') or '')[:23]:<24}"
              f"{(it.get('name') or '')}")
print(f"\\n💡 存储位置: {data.get('root')}")
PY
}

cmd_upload() {
  local file="" type="" author=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --type) type="$2"; shift 2 ;;
      --author) author="$2"; shift 2 ;;
      -*) die "未知选项: $1" ;;
      *) file="$1"; shift ;;
    esac
  done
  [[ -f "$file" ]] || die "找不到文件: $file"
  [[ -z "$type" ]] && type=$(detect_type "$file")
  case "$type" in
    agent) endpoint="/api/v1/business/user-templates/agents" ;;
    scenario) endpoint="/api/v1/business/user-templates/scenarios" ;;
    *) die "type 必须是 agent 或 scenario，得到: $type" ;;
  esac
  log "POST $endpoint ($type · $file)"
  local body
  body=$(yaml_to_jsonbody "$file" "${author:-cli@$(whoami)}")
  local resp
  if ! resp=$(req POST "$endpoint" -H "Content-Type: application/json" --data "$body"); then
    die "上传失败"
  fi
  python3 -c '
import json, sys
data = json.load(sys.stdin)
print(f"✅ 上传成功 · templateId = {data.get(\"templateId\")}")
' <<<"$resp"
}

cmd_upload_dir() {
  local dir="" author=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --author) author="$2"; shift 2 ;;
      *) dir="$1"; shift ;;
    esac
  done
  [[ -d "$dir" ]] || die "找不到目录: $dir"
  local count=0
  while IFS= read -r -d '' f; do
    cmd_upload "$f" --author "${author:-cli@$(whoami)}" || true
    count=$((count+1))
  done < <(find "$dir" -type f \( -name "*.yaml" -o -name "*.yml" \) -print0)
  log "完成 — 处理 $count 个文件"
}

cmd_delete() {
  local id="${1:-}"
  [[ -n "$id" ]] || die "用法: hermes-templates.sh delete <templateId>"
  log "DELETE /api/v1/business/user-templates/$id"
  if req DELETE "/api/v1/business/user-templates/$id" >/dev/null; then
    echo "✅ 已删除: $id"
  else
    die "删除失败"
  fi
}

cmd_validate() {
  local file="${1:-}"
  [[ -f "$file" ]] || die "找不到文件: $file"
  require_python
  python3 - "$file" <<'PY'
import sys
try:
    import yaml
except ImportError:
    print("⚠️ 缺少 PyYAML，跳过结构校验；建议: pip install pyyaml")
    sys.exit(0)

with open(sys.argv[1], encoding="utf-8") as fh:
    data = yaml.safe_load(fh)
if not isinstance(data, dict):
    print("❌ YAML 顶层必须是 mapping")
    sys.exit(2)

required_common = ["template_id", "name", "category"]
missing = [k for k in required_common if not data.get(k)]
is_scenario = any(k in data for k in ("involved_agents", "clone_blueprint", "workflow_timeline"))
if not is_scenario:
    if not isinstance(data.get("skills"), list) or len(data.get("skills", [])) < 3:
        missing.append("skills (≥3)")
    if not data.get("role"):
        missing.append("role")
if missing:
    print("❌ 缺少必填字段:", ", ".join(missing))
    sys.exit(3)

print(f"✅ 模板结构有效 · {'scenario' if is_scenario else 'agent'} · template_id={data['template_id']}")
PY
}

usage() {
  sed -n '2,16p' "$0"
}

case "${1:-}" in
  list)        shift; cmd_list "$@" ;;
  upload)      shift; cmd_upload "$@" ;;
  upload-dir)  shift; cmd_upload_dir "$@" ;;
  delete)      shift; cmd_delete "$@" ;;
  validate)    shift; cmd_validate "$@" ;;
  ""|-h|--help|help) usage ;;
  *) die "未知命令: $1（使用 -h 查看帮助）" ;;
esac
