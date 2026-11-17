# 停止本地全部进程（保留基础设施容器；加 -Infra 连容器一起停）。
param([switch]$Infra)
$root = Split-Path -Parent $PSScriptRoot

Get-Process -Name 'java' -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match 'agentflow|workflow-service|agent-gateway|audit-service' } |
  Stop-Process -Force
Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
  Where-Object { $_.CommandLine -match 'uvicorn.*app.main' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Get-Process -Name 'node' -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match 'vite' } |
  Stop-Process -Force

if ($Infra) {
  docker compose -f (Join-Path $root 'ops/docker-compose.infra.yml') down
}
Write-Host 'stopped.'
