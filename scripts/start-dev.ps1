# AgentFlow 一键启动（Windows PowerShell）。
# 顺序：基础设施(docker) → 迁移/授权/种子 → engine-api → Java 三服务 → 前端 dev server。
# 日志：logs/*.log。停止：scripts/stop-dev.ps1。
$ErrorActionPreference = 'Continue'
# 说明：保持 Continue 而非 Stop。PowerShell 5.1 会把 native 命令（docker/mysql 等）
# 写到 stderr 的普通警告（如 mysql 的“命令行密码不安全”）当成终止错误，导致脚本中断。
# 真正的失败点在下方用 $LASTEXITCODE 显式检查 + throw 兜底，不依赖 EAP=Stop。
# 清理大小写重复的代理变量：本机若同时存在 HTTPS_PROXY 与 https_proxy，
# .NET 构造进程环境字典时会因大小写冲突让 Start-Process 抛"已添加项"异常。
foreach ($n in @('http_proxy','https_proxy','no_proxy','HTTP_PROXY','HTTPS_PROXY','NO_PROXY','ALL_PROXY','all_proxy')) {
  [Environment]::SetEnvironmentVariable($n, $null, 'Process')
}
$root = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

Write-Host '==> [1/5] 基础设施 (docker compose)'
docker compose -f (Join-Path $root 'ops/docker-compose.infra.yml') up -d
if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed — 请先启动 Docker Desktop' }

Write-Host '==> [2/5] 等待 MySQL 就绪并落地迁移/授权'
# 不依赖 flyway/grants 容器的退出码：grants 容器在部分机器上因卷挂载路径
# 解析偏差会拿不到 SQL 而 Exit(1)，死等它的退出码会导致脚本卡住。
# 改为等 MySQL 健康检查通过，再从宿主机主动重放授权 SQL（幂等）。
$deadline = (Get-Date).AddMinutes(3)
do {
  Start-Sleep -Seconds 3
  $health = docker inspect -f '{{.State.Health.Status}}' agentflow-mysql 2>$null
} until ($health -eq 'healthy' -or (Get-Date) -ge $deadline)
if ($health -ne 'healthy') { throw 'MySQL 未在 3 分钟内就绪；docker compose logs mysql 查看' }

Write-Host '==> [2.5/5] 授权 + 种子数据（幂等）'
# 管道必须按 UTF-8 发送字节给 mysql（PS5.1 默认 ASCII 会毁掉中文），并显式声明 utf8mb4
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Get-Content (Join-Path $root 'db/mysql/grants/least-privilege.sql') -Raw -Encoding UTF8 |
  docker exec -i agentflow-mysql mysql --default-character-set=utf8mb4 -uroot -proot agentflow 2>$null
Get-Content (Join-Path $root 'scripts/seed-demo.sql') -Raw -Encoding UTF8 |
  docker exec -i agentflow-mysql mysql --default-character-set=utf8mb4 -uroot -proot agentflow 2>$null
Write-Host '    grants + seed applied (duplicate key 可忽略)'

# 先停掉上一次的服务：Windows 上运行中的 jar 是独占打开的，spring-boot:repackage 要把
# 旧 jar 改名成 *.jar.original 时会被拒，构建直接失败（旧脚本因此每次重跑都要手动停服）。
Write-Host '==> [2.9/5] 停掉在跑的服务（避免 jar 被占用导致构建失败）'
& (Join-Path $PSScriptRoot 'stop-dev.ps1') -Quiet
# 上次失败可能留下半成品 *.jar.original，repackage 遇到它会再次报错。
Get-ChildItem -Path (Join-Path $root '*/target') -Filter '*.jar.original' -Recurse -ErrorAction SilentlyContinue |
  ForEach-Object {
    try { [System.IO.File]::Delete($_.FullName) } catch { Write-Warning ("清理残留失败：" + $_.FullName) }
  }

Write-Host '==> [3/5] 构建 Java 服务（跳过测试；测试走 verify-all）'
Push-Location $root
# 参数必须逐个加引号：PowerShell 会把 -Dcheckstyle.skip 解析成 -Dcheckstyle + .skip，
# maven 收到 ".skip" 报 Unknown lifecycle phase 直接失败。
mvn -q '-DskipTests' '-Dcheckstyle.skip=true' package
if ($LASTEXITCODE -ne 0) {
  Pop-Location
  throw 'maven package failed（若报 Unable to rename *.jar.original：先 scripts/stop-dev.ps1 或在跑的服务没停干净）'
}
Pop-Location

if ($null -eq $env:AF_INTERNAL_TOKEN) { $env:AF_INTERNAL_TOKEN = 'dev-internal-token' }
if ($null -eq $env:AF_JWT_SECRET) { $env:AF_JWT_SECRET = 'dev-only-secret-key-0123456789abcdef' }
# 本地走明文 HTTP，refresh Cookie 不能带 Secure，否则浏览器不回传
if ($null -eq $env:AF_COOKIE_SECURE) { $env:AF_COOKIE_SECURE = 'false' }
# 本地全量 E2E 一轮会触发数十次执行；产品默认仍是 20 次/60s/用户
if ($null -eq $env:AF_RATE_LIMIT_TRIGGER_MAX) { $env:AF_RATE_LIMIT_TRIGGER_MAX = '300' }

# 真实模型（可选）。显式设置 AF_LLM_* 时直接使用；未设置而其厂商密钥已在环境里时按该厂商派生，
# 免得每次手工导出。密钥只留在进程环境，不写入仓库与磁盘；两者都没有时引擎保持 mock。
if ($null -eq $env:AF_LLM_API_KEY -and $env:DEEPSEEK_API_KEY) {
    if ($null -eq $env:AF_LLM_BASE_URL) { $env:AF_LLM_BASE_URL = 'https://api.deepseek.com/v1' }
    $env:AF_LLM_API_KEY = $env:DEEPSEEK_API_KEY
    if ($null -eq $env:AF_LLM_MODEL) { $env:AF_LLM_MODEL = 'deepseek-flash' }
}

Write-Host '==> [4/5] 启动 engine-api :8000'
$engineDir = Join-Path $root 'engine-api'
$enginePy = Join-Path $engineDir '.venv/Scripts/python.exe'
Start-Process -FilePath $enginePy -WorkingDirectory $engineDir `
  -ArgumentList '-m','uvicorn','app.main:app','--host','127.0.0.1','--port','8000' `
  -RedirectStandardOutput (Join-Path $logs 'engine.out.log') `
  -RedirectStandardError (Join-Path $logs 'engine.err.log') -WindowStyle Hidden

$javaServices = @(
  @{ Name = 'workflow-service'; Jar = 'workflow-service/target/workflow-service-0.1.0.jar'; Port = 8084 },
  @{ Name = 'agent-gateway';    Jar = 'agent-gateway/target/agent-gateway-0.1.0.jar';       Port = 8080 },
  @{ Name = 'audit-service';    Jar = 'audit-service/target/audit-service-0.1.0.jar';       Port = 8086 },
  @{ Name = 'connector-service'; Jar = 'connector-service/target/connector-service-0.1.0.jar'; Port = 8085 }
)
foreach ($svc in $javaServices) {
  Write-Host ("==> [4/5] 启动 {0} :{1}" -f $svc.Name, $svc.Port)
  Start-Process -FilePath 'java' -WorkingDirectory $root `
    -ArgumentList '-jar', $svc.Jar `
    -RedirectStandardOutput (Join-Path $logs ($svc.Name + '.out.log')) `
    -RedirectStandardError (Join-Path $logs ($svc.Name + '.err.log')) -WindowStyle Hidden
}

Write-Host '==> [5/5] 前端 :5174'
Start-Process -FilePath 'cmd.exe' -WorkingDirectory (Join-Path $root 'frontend') `
  -ArgumentList '/c','npm','run','dev' `
  -RedirectStandardOutput (Join-Path $logs 'frontend.out.log') `
  -RedirectStandardError (Join-Path $logs 'frontend.err.log') -WindowStyle Hidden

Write-Host '==> 等待 gateway 就绪'
$gwReady = $false
foreach ($i in 1..60) {
  try {
    Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/v1/auth/login' -Method Post `
      -ContentType 'application/json' -Body '{}' -TimeoutSec 2 -ErrorAction Stop | Out-Null
  } catch {
    if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -in @(400,401,422)) {
      $gwReady = $true; break
    }
  }
  Start-Sleep -Seconds 2
}
if (-not $gwReady) { Write-Warning 'gateway 未在 120s 内就绪；查看 logs/*.log' }

Write-Host ''
Write-Host 'AgentFlow 已启动：'
Write-Host '  前端        http://127.0.0.1:5174  (避开被 DocQA 占用的 5173)'
Write-Host '  网关        http://127.0.0.1:8080'
Write-Host '  演示账号    admin@agentflow.dev / agentflow123（ADMIN）'
Write-Host '              editor@agentflow.dev / agentflow123（EDITOR · Space Alpha）'
Write-Host '  停止        scripts/stop-dev.ps1'
