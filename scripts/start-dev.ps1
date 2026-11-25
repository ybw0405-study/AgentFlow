# AgentFlow 一键启动（Windows PowerShell）。
# 顺序：基础设施(docker) → 迁移/授权/种子 → engine-api → Java 三服务 → 前端 dev server。
# 日志：logs/*.log。停止：scripts/stop-dev.ps1。
# -Containers 切换为全容器模式（镜像内构建，不依赖本机 JDK/Maven）。
param([switch]$Containers)
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

Write-Host '==> [2/5] 等待 MySQL / RocketMQ 就绪并落地迁移/授权'
# 不依赖 flyway/grants 容器的退出码：grants 容器在部分机器上因卷挂载路径
# 解析偏差会拿不到 SQL 而 Exit(1)，死等它的退出码会导致脚本卡住。
# 改为等健康检查通过，再从宿主机主动重放授权 SQL（幂等）。
function Wait-Healthy([string] $container, [int] $timeoutSec = 180) {
  $limit = (Get-Date).AddSeconds($timeoutSec)
  do {
    Start-Sleep -Seconds 3
    $state = docker inspect -f '{{.State.Health.Status}}' $container 2>$null
  } until ($state -eq 'healthy' -or (Get-Date) -ge $limit)
  return $state -eq 'healthy'
}
# broker 必须一起等：workflow-service 启动时会连 namesrv 建 producer，
# broker 还没起来的话审计消息发不出去（业务侧只记日志，本地就丢审计）。
foreach ($c in @('agentflow-mysql', 'agentflow-rmq-broker')) {
  if (-not (Wait-Healthy $c)) { throw "$c 未在 3 分钟内 healthy；docker compose logs 查看" }
}

Write-Host '==> [2.5/5] 授权 + 种子数据（幂等）'
# 管道必须按 UTF-8 发送字节给 mysql（PS5.1 默认 ASCII 会毁掉中文），并显式声明 utf8mb4
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Get-Content (Join-Path $root 'db/mysql/grants/least-privilege.sql') -Raw -Encoding UTF8 |
  docker exec -i agentflow-mysql mysql --default-character-set=utf8mb4 -uroot -proot agentflow 2>$null
Get-Content (Join-Path $root 'scripts/seed-demo.sql') -Raw -Encoding UTF8 |
  docker exec -i agentflow-mysql mysql --default-character-set=utf8mb4 -uroot -proot agentflow 2>$null
Write-Host '    grants + seed applied (duplicate key 可忽略)'

# 真实模型（可选）。显式设置 AF_LLM_* 时直接使用；未设置而其厂商密钥已在环境里时按该厂商派生，
# 免得每次手工导出。密钥只留在进程环境，不写入仓库与磁盘；两者都没有时引用真实模型的
# 节点会返回 LLM-NOT_CONFIGURED-001，需显式注入 AF_LLM_*。
# 必须放在 -Containers 分支之前：compose 只在 up 时插值 AF_LLM_*，return 之后再派生
# 会让全容器模式的引擎容器拿到空配置（宿主机模式则继承了本进程环境）。
if ($null -eq $env:AF_LLM_API_KEY -and $env:DEEPSEEK_API_KEY) {
  if ($null -eq $env:AF_LLM_BASE_URL) { $env:AF_LLM_BASE_URL = 'https://api.deepseek.com/v1' }
  $env:AF_LLM_API_KEY = $env:DEEPSEEK_API_KEY
  if ($null -eq $env:AF_LLM_MODEL) { $env:AF_LLM_MODEL = 'deepseek-flash' }
}

# 全容器模式——jar 由 Dockerfile 的 java-build stage 构建，topic 由 mq-init 预建，
# 不依赖本机 JDK/Maven/Java，也不需要下面的宿主机 jar 构建与服务启动。
if ($Containers) {
  if ($null -eq $env:AF_INTERNAL_TOKEN) { $env:AF_INTERNAL_TOKEN = 'dev-internal-token' }
  if ($null -eq $env:AF_JWT_SECRET) { $env:AF_JWT_SECRET = 'dev-only-secret-key-0123456789abcdef' }
  if ($null -eq $env:AF_COOKIE_SECURE) { $env:AF_COOKIE_SECURE = 'false' }
  if ($null -eq $env:AF_DEMO_ACCOUNTS) { $env:AF_DEMO_ACCOUNTS = 'true' }
  Write-Host '==> [4/5] -Containers：全容器模式，compose 起应用层'
  # 镜像-源码新鲜度预检：防止用过期本地镜像跑新迁移导致 Flyway checksum 冲突。
  # 仅警告（exit 2），不阻断；docker 不可用时跳过。
  python (Join-Path $root 'scripts/check_image_freshness.py') 2>&1 | ForEach-Object { Write-Host $_ }
  docker compose -f (Join-Path $root 'ops/docker-compose.infra.yml') `
                 -f (Join-Path $root 'ops/docker-compose.app.yml') up -d
  if ($LASTEXITCODE -ne 0) { throw 'docker compose up (app) failed' }
  Write-Host '==> 网关 http://127.0.0.1:8080 / 前端 http://127.0.0.1:8081'
  return
}

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
# 本地开发才下发演示账号方便登录页一键填充；生产默认 false
if ($null -eq $env:AF_DEMO_ACCOUNTS) { $env:AF_DEMO_ACCOUNTS = 'true' }
# 本地全量 E2E 一轮会触发数十次执行、十几次登录；产品默认仍是 20/10 次每 60s
if ($null -eq $env:AF_RATE_LIMIT_TRIGGER_MAX) { $env:AF_RATE_LIMIT_TRIGGER_MAX = '300' }
if ($null -eq $env:AF_RATE_LIMIT_LOGIN_MAX) { $env:AF_RATE_LIMIT_LOGIN_MAX = '300' }
# compose 已经把 RocketMQ 起起来了，本地默认走真实审计链路（workflow → MQ → audit）。
# 关着的话 workflow-service 用 NoopAuditPublisher，审计表里根本不会有对应事件。
if ($null -eq $env:AF_MQ_ENABLED) { $env:AF_MQ_ENABLED = 'true' }
if ($null -eq $env:AF_ROCKETMQ_NAMESRV) { $env:AF_ROCKETMQ_NAMESRV = '127.0.0.1:9876' }
# 本地开发默认开启 LLM 逐 token 流式（打字机效果）；引擎侧仍保守默认 false（opt-in），
# 生产部署不经此脚本、行为不变。显式设 AF_LLM_STREAM=false 可关闭。
if ($null -eq $env:AF_LLM_STREAM) { $env:AF_LLM_STREAM = 'true' }

Write-Host '==> [3.5/5] 预建 MQ topic（避免消费端等首条消息才拿到路由）'
# autoCreateTopicEnable 只在「第一次发送」时建 topic；消费端在消息到来前查路由会拿到 17。
# 运维上本来就该预建 topic，这里幂等执行（重复创建只报 already exists，无副作用）。
# 两个坑：
#  1) namesrv 是独立容器，broker 容器内的 127.0.0.1:9876 够不到它，必须走 docker 网络主机名；
#  2) mqadmin 命令失败时退出码仍是 0，不能信退出码——用 topicRoute 输出里有无 brokerDatas 判定。
# HOME=/tmp：命名卷属主问题让 rocketmq 用户建不了 ~/logs/rocketmqlogs，工具日志改写到 /tmp。
# broker 的 TCP 健康检查通过 ≠ 已完成首次 namesrv 注册（注册周期 ~30s），预建带重试窗口。
foreach ($t in @('af-audit-event', 'af-gate-decision')) {
  $ok = $false
  foreach ($i in 1..18) {
    docker exec -e HOME=/tmp agentflow-rmq-broker sh -c "sh mqadmin updateTopic -n rocketmq-namesrv:9876 -c DefaultCluster -t $t" *> $null
    $route = docker exec -e HOME=/tmp agentflow-rmq-broker sh -c "sh mqadmin topicRoute -t $t -n rocketmq-namesrv:9876" 2>$null
    if ($route -match 'brokerDatas') { $ok = $true; break }
    Start-Sleep -Seconds 5
  }
  if (-not $ok) { throw "预建 topic $t 失败：90s 内 namesrv 始终查不到路由（docker logs agentflow-rmq-broker）" }
}

Write-Host '==> [4/5] 启动 engine-api :8000'
$engineDir = Join-Path $root 'engine-api'
$enginePy = Join-Path $engineDir '.venv/Scripts/python.exe'
$engineProc = Start-Process -FilePath $enginePy -WorkingDirectory $engineDir `
  -ArgumentList '-m','uvicorn','app.main:app','--host','127.0.0.1','--port','8000' `
  -RedirectStandardOutput (Join-Path $logs 'engine.out.log') `
  -RedirectStandardError (Join-Path $logs 'engine.err.log') -WindowStyle Hidden -PassThru
$engineProc.Id | Out-File -FilePath (Join-Path $logs 'engine.pid') -Encoding ascii

# jar 名带版本号（spring-boot repackage 产出 <name>-<version>.jar），这里按目录解析，
# 免得每次升版本都要回来改脚本。
function Resolve-ServiceJar([string] $service) {
  $dir = Join-Path $root "$service/target"
  $jar = Get-ChildItem -Path $dir -Filter "$service-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*.original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
  if (-not $jar) { throw "未找到 $service 的可执行 jar（$dir）；先跑 mvn package" }
  return $jar.FullName
}

$javaServices = @(
  @{ Name = 'workflow-service'; Port = 8084 },
  @{ Name = 'agent-gateway'; Port = 8080 },
  @{ Name = 'audit-service'; Port = 8086 },
  @{ Name = 'connector-service'; Port = 8085 }
)
foreach ($svc in $javaServices) {
  $jar = Resolve-ServiceJar $svc.Name
  Write-Host ("==> [4/5] 启动 {0} :{1}" -f $svc.Name, $svc.Port)
  $proc = Start-Process -FilePath 'java' -WorkingDirectory $root `
    -ArgumentList '-jar', $jar `
    -RedirectStandardOutput (Join-Path $logs ($svc.Name + '.out.log')) `
    -RedirectStandardError (Join-Path $logs ($svc.Name + '.err.log')) -WindowStyle Hidden -PassThru
  $proc.Id | Out-File -FilePath (Join-Path $logs ($svc.Name + '.pid')) -Encoding ascii
}

Write-Host '==> [5/5] 前端 :5174'
$feProc = Start-Process -FilePath 'cmd.exe' -WorkingDirectory (Join-Path $root 'frontend') `
  -ArgumentList '/c','npm','run','dev' `
  -RedirectStandardOutput (Join-Path $logs 'frontend.out.log') `
  -RedirectStandardError (Join-Path $logs 'frontend.err.log') -WindowStyle Hidden -PassThru
$feProc.Id | Out-File -FilePath (Join-Path $logs 'frontend.pid') -Encoding ascii

Write-Host '==> 等待全部服务就绪'
# 不能只探 gateway：gateway 的 /health 只连带探 Redis，下游 workflow/audit/connector
# 任意一个启动崩溃时它仍返回 200，旧版脚本会误报「已启动」，登录等接口却 502。
# 逐个探每个服务自己的 /health（Java 依赖不可用返回 503，engine 有独立 readiness）。
$healthTargets = @(
  @{ Name = 'workflow-service';  Url = 'http://127.0.0.1:8084/health' },
  @{ Name = 'agent-gateway';     Url = 'http://127.0.0.1:8080/health' },
  @{ Name = 'audit-service';     Url = 'http://127.0.0.1:8086/health' },
  @{ Name = 'connector-service'; Url = 'http://127.0.0.1:8085/health' },
  @{ Name = 'engine-api';        Url = 'http://127.0.0.1:8000/internal/health/ready' }
)
# 必须带 -UseBasicParsing：Windows PowerShell 5.1 默认走 IE DOM 解析，
# 非交互模式下一律抛错，探测会永远判不成就绪。
function Test-Ready([string] $url) {
  try {
    Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop | Out-Null
    return $true
  } catch {
    return $false
  }
}
$readyTimer = [Diagnostics.Stopwatch]::StartNew()
$notReady = @()
foreach ($t in $healthTargets) {
  $ok = $false
  foreach ($i in 1..40) {
    if (Test-Ready $t.Url) { $ok = $true; break }
    Start-Sleep -Seconds 2
  }
  if (-not $ok) { $notReady += $t.Name }
}
if ($notReady.Count -eq 0) {
  Write-Host ("    全部就绪（{0:N1}s）" -f $readyTimer.Elapsed.TotalSeconds)
} else {
  foreach ($n in $notReady) { Write-Warning ("  {0} 未在 80s 内就绪" -f $n) }
  Write-Warning '部分服务未就绪，登录等功能可能不可用；查看 logs/*.log 中对应服务的启动异常'
}

Write-Host ''
Write-Host 'AgentFlow 已启动：'
Write-Host '  前端        http://127.0.0.1:5174  (避开被 DocQA 占用的 5173)'
Write-Host '  网关        http://127.0.0.1:8080'
Write-Host '  演示账号    admin@agentflow.dev / agentflow123（ADMIN）'
Write-Host '              editor@agentflow.dev / agentflow123（EDITOR · Space Alpha）'
Write-Host '  停止        scripts/stop-dev.ps1'
