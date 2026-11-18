# 停止本仓库启动的全部进程（保留基础设施容器；加 -Infra 连容器一起停）。
#
# 为什么用 Get-CimInstance 而不是 Get-Process：
#   PowerShell 5.1 的 System.Diagnostics.Process 没有 CommandLine 属性，`$_.CommandLine`
#   恒为 $null，`$null -match 'x'` 恒为 $false —— 旧版脚本会"成功返回"却一个进程都没停，
#   于是 jar 一直被占用，下一次 mvn package 的 repackage 阶段改名失败、整个启动中断。
#
# 为什么按"仓库路径"而不是按模块名匹配：
#   同机还有别的项目跑着 agent-gateway-1.0.1.jar（DocQA）。按 '-agent-gateway' 匹配会把它
#   一起杀掉。这里只认三种归属证据：解释器/脚本路径在仓库内、绝对 jar 路径在仓库内、
#   相对 jar 路径能在仓库根下解析到真实文件。
param(
  [switch]$Infra,
  [switch]$Quiet
)
$root = (Resolve-Path (Split-Path -Parent $PSScriptRoot)).Path

function Test-OwnedByRepo([string]$cmd) {
  if ([string]::IsNullOrWhiteSpace($cmd)) { return $false }
  if ($cmd.IndexOf($root, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
  # 本仓库的 Java 服务是用相对路径 -jar <module>/target/xxx-0.1.0.jar 起的，命令行不含仓库根，
  # 所以把相对路径按仓库根解析，只有解析得到真实文件才算自己的。
  $m = [regex]::Match($cmd, '-jar\s+"?([^"\s]+\.jar)')
  if (-not $m.Success) { return $false }
  $jar = $m.Groups[1].Value
  if ([System.IO.Path]::IsPathRooted($jar)) {
    return $jar.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)
  }
  return (Test-Path -LiteralPath (Join-Path $root $jar))
}

function Stop-RepoProcesses {
  $targets = @(
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
      Where-Object {
        $_.Name -in @('java.exe', 'python.exe', 'node.exe', 'cmd.exe') -and
        (Test-OwnedByRepo $_.CommandLine)
      }
  )
  foreach ($p in $targets) {
    if (-not $Quiet) {
      Write-Host ("  stop {0,-16} pid={1}" -f $p.Name, $p.ProcessId)
    }
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
  }
  return $targets.Count
}

$count = Stop-RepoProcesses

# 杀进程到句柄真正释放之间有几毫秒到几秒的窗口。不等就构建，repackage 仍可能撞上锁。
# 这里轮询确认"已无本仓库进程"，最多 10s，不做无意义的固定 sleep。
$deadline = (Get-Date).AddSeconds(10)
while ((Get-Date) -lt $deadline) {
  $left = @(
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
      Where-Object {
        $_.Name -in @('java.exe', 'python.exe') -and (Test-OwnedByRepo $_.CommandLine)
      }
  )
  if ($left.Count -eq 0) { break }
  Start-Sleep -Milliseconds 300
}

if ($Infra) {
  docker compose -f (Join-Path $root 'ops/docker-compose.infra.yml') down
}
if (-not $Quiet) {
  if ($count -eq 0) { Write-Host 'nothing running.' } else { Write-Host ("stopped ({0} processes)." -f $count) }
}
