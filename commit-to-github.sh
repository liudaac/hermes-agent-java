#!/bin/bash
# 提交资源隔离代码到 GitHub 的脚本

set -e

echo "=== 资源隔离代码提交脚本 ==="
echo ""

# 检查是否在正确的目录
if [ ! -f "pom.xml" ]; then
    echo "错误：请在 hermes-agent-java 项目根目录运行此脚本"
    exit 1
fi

echo "1. 创建必要的目录..."
mkdir -p src/main/java/com/nousresearch/hermes/tenant/sandbox
mkdir -p src/main/java/com/nousresearch/hermes/tenant/metrics
mkdir -p src/main/java/com/nousresearch/hermes/gateway/api
mkdir -p src/test/java/com/nousresearch/hermes/tenant/sandbox

echo ""
echo "2. 检查是否有新文件..."
NEW_FILES=$(git status --porcelain src/main/java/com/nousresearch/hermes/tenant/sandbox/ src/test/java/ 2>/dev/null | wc -l)

echo ""
echo "3. 添加所有修改..."
git add src/main/java/com/nousresearch/hermes/tenant/sandbox/
git add src/main/java/com/nousresearch/hermes/tenant/metrics/ 2>/dev/null || true
git add src/main/java/com/nousresearch/hermes/gateway/api/ 2>/dev/null || true
git add src/test/java/com/nousresearch/hermes/tenant/sandbox/ 2>/dev/null || true

# 检查 TenantContext.java 是否修改
if git diff --cached --name-only | grep -q "TenantContext.java"; then
    echo "   ✓ TenantContext.java 已添加"
fi

echo ""
echo "4. 提交更改..."
git commit -m "feat: implement resource isolation sandbox

Add process and network sandbox for tenant resource isolation:

Process Sandbox:
- Command whitelist/blacklist
- Timeout control using Linux timeout command
- Environment variable sanitization
- Working directory restriction

Network Sandbox:
- URL whitelist/blacklist with wildcard support
- Rate limiting per tenant
- Protocol and port restrictions
- Request timeout control

Additional Features:
- REST API endpoints for sandbox operations
- JMX metrics for monitoring
- Unit and integration tests
- Comprehensive documentation

Security:
- Prevents command injection attacks
- Blocks SSRF via URL filtering
- Isolates tenant resources
- Audit logging for all operations"

echo ""
echo "5. 推送到 GitHub..."
git push origin main

echo ""
echo "✅ 提交完成！"
echo ""
echo "查看提交："
echo "  git log --oneline -3"
echo "  git log -1 --stat"
