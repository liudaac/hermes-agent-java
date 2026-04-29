# 提交到 GitHub 完整指南

## 方法一：在服务器上初始化并推送（推荐）

### 1. 初始化 Git 仓库
```bash
cd /root/hermes-agent-java

# 初始化仓库
git init

# 添加所有文件
git add .

# 提交
git commit -m "feat: implement resource isolation sandbox

Add process and network sandbox for tenant resource isolation:

Process Sandbox:
- Command whitelist/blacklist validation
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
- Integration with TenantContext

Security:
- Prevents command injection attacks
- Blocks SSRF via URL filtering
- Isolates tenant resources
- Audit logging for all operations

Closes compilation errors and adds resource isolation capabilities."
```

### 2. 关联远程仓库
```bash
# 添加远程仓库（替换为你的仓库地址）
git remote add origin https://github.com/yourusername/hermes-agent-java.git

# 或者使用 SSH
git remote add origin git@github.com:yourusername/hermes-agent-java.git
```

### 3. 推送代码
```bash
# 推送主分支
git branch -M main
git push -u origin main
```

## 方法二：从服务器复制到本地再推送

### 1. 打包服务器代码
```bash
# 在服务器上执行
cd /root/hermes-agent-java
tar czvf hermes-update.tar.gz \
  src/main/java/com/nousresearch/hermes/tenant/sandbox/ \
  src/main/java/com/nousresearch/hermes/tenant/metrics/ \
  src/main/java/com/nousresearch/hermes/gateway/api/ \
  src/test/java/com/nousresearch/hermes/tenant/sandbox/ \
  *.md
```

### 2. 下载到本地
```bash
# 在本地执行
scp root@your-server:/root/hermes-agent-java/hermes-update.tar.gz .
tar xzvf hermes-update.tar.gz
```

### 3. 本地提交
```bash
cd /Users/liuda17/work/project/opensource/hermes-agent-java

# 复制文件
cp -r /path/to/extracted/src/main/java/* src/main/java/
cp -r /path/to/extracted/src/test/java/* src/test/java/

# 提交
git add .
git commit -m "feat: implement resource isolation sandbox..."
git push origin main
```

## 方法三：使用 rsync 同步

```bash
# 从本地同步服务器代码
rsync -avz --exclude='.git' \
  root@your-server:/root/hermes-agent-java/ \
  /Users/liuda17/work/project/opensource/hermes-agent-java/

# 然后本地提交
cd /Users/liuda17/work/project/opensource/hermes-agent-java
git add .
git commit -m "feat: implement resource isolation sandbox..."
git push origin main
```

## 验证提交

```bash
# 查看提交历史
git log --oneline -5

# 查看提交详情
git log -1 --stat

# 查看文件列表
git ls-tree -r HEAD --name-only | grep sandbox
```

## 提交信息模板

```
feat: implement resource isolation sandbox

- Add ProcessSandbox for command execution with whitelist/blacklist
- Add NetworkSandbox for URL filtering and rate limiting
- Add TenantMetrics for JMX monitoring
- Add REST API endpoints for sandbox operations
- Add unit and integration tests
- Fix compilation errors in TenantController and TenantManager

Security improvements:
- Prevents command injection via whitelist validation
- Blocks SSRF attacks via URL filtering
- Isolates tenant resources with timeout and memory limits

Files added:
- 12 Java files in tenant/sandbox/
- 3 test files
- 4 documentation files

Files modified:
- TenantContext.java (add sandbox integration)
```

## 注意事项

1. **远程仓库地址**：替换为实际的 GitHub 仓库地址
2. **权限**：确保有推送权限
3. **冲突**：如果有冲突，先拉取最新代码再推送
4. **大文件**：如果代码包很大，考虑分批提交
