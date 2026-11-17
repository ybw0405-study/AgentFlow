# AgentFlow 一键启动（Windows PowerShell）。
# 顺序：基础设施(docker) → 迁移/授权/种子 → engine-api → Java 三服务 → 前端 dev server。
# 日志：logs/*.log。停止：scripts/stop-dev.ps1。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

Write-Host '==> [1/5] 基础设施 (docker compose)'
docker compose -f (Join-Path $root 'ops/docker-compose.infra.yml') up -d
if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed — 请先启动 Docker Desktop' }

Write-Host '==> [2/5] 等待 MySQL 迁移与授权完成'
$deadline = (Get-Date).AddMinutes(3)
do {
  Start-Sleep -Seconds 3
  $grants = docker inspect -f '{{.State.ExitCode}}' agentflow-grants 2>$null
  $ready = (Get-Date) -lt $deadline -and $grants -ne '0'
  if (-not $ready) { continue }
} until ($grants -eq '0')
if ($grants -ne '0') { throw 'flyway/grants 未完成；docker compose logs flyway grants 查看' }

Write-Host '==> [2.5/5] 种子数据（幂等）'
Get-Content (Join-Path $root 'scripts/seed-demo.sql') |
  docker exec -i agentflow-mysql mysql -uroot -proot agentflow 2>$null
Write-Host '    seed applied (duplicate key 可忽略)'

Write-Host '==> [3/5] 构建 Java 服务（跳过测试；测试走 verify-all）'
Push-Location $root
mvn -q -DskipTests -Dcheckstyle.skip package
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'maven package failed' }
Pop-Location

$env:AF_INTERNAL_TOKEN = $env:AF_INTERNAL_TOKEN ?? 'dev-internal-token'
$env:AF_JWT_SECRET = $env:AF_JWT_SECRET ?? 'dev-only-secret-key-0123456789abcdef'
# 本地走明文 HTTP，refresh Cookie 不能带 Secure，否则浏览器不回传
$env:AF_COOKIE_SECURE = $env:AF_COOKIE_SECURE ?? 'false'
# 本地全量 E2E 一轮会触发数十次执行；产品默认仍是 20 次/60s/用户
$env:AF_RATE_LIMIT_TRIGGER_MAX = $env:AF_RATE_LIMIT_TRIGGER_MAX ?? '300'

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

Write-Host '==> [5/5] 前端 :5173'
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
Write-Host '  前端        http://127.0.0.1:5173'
Write-Host '  网关        http://127.0.0.1:8080'
Write-Host '  演示账号    admin@agentflow.dev / agentflow123（ADMIN）'
Write-Host '              editor@agentflow.dev / agentflow123（EDITOR · Space Alpha）'
Write-Host '  停止        scripts/stop-dev.ps1'
