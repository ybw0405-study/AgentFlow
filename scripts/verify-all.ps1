# AgentFlow 本地全量门禁（对齐 CI）：Java（tests+Checkstyle+ArchUnit）/ Python（ruff+pytest）/ 前端（tsc+eslint+vitest+build）。
param(
  [switch]$SkipJava,
  [switch]$SkipPython,
  [switch]$SkipFrontend,
  [switch]$SkipContract
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$failed = @()

if (-not $SkipJava) {
  Write-Host '==> java: mvn verify'
  Push-Location $root
  mvn -B -ntp verify
  if ($LASTEXITCODE -ne 0) { $failed += 'java' }
  Pop-Location
}

if (-not $SkipPython) {
  Write-Host '==> python: ruff + pytest'
  Push-Location (Join-Path $root 'engine-api')
  $py = '.venv/Scripts/python.exe'
  & $py -m ruff check app tests; if ($LASTEXITCODE -ne 0) { $failed += 'python-ruff' }
  & $py -m ruff format --check app tests; if ($LASTEXITCODE -ne 0) { $failed += 'python-format' }
  & $py -m pytest -q; if ($LASTEXITCODE -ne 0) { $failed += 'python-pytest' }
  Pop-Location
}

if (-not $SkipFrontend) {
  Write-Host '==> frontend: typecheck + lint + test + build'
  Push-Location (Join-Path $root 'frontend')
  if (-not (Test-Path 'node_modules')) { npm ci }
  npm run typecheck; if ($LASTEXITCODE -ne 0) { $failed += 'frontend-typecheck' }
  npm run lint;      if ($LASTEXITCODE -ne 0) { $failed += 'frontend-lint' }
  npm run test;      if ($LASTEXITCODE -ne 0) { $failed += 'frontend-test' }
  npm run build;     if ($LASTEXITCODE -ne 0) { $failed += 'frontend-build' }
  Pop-Location
}

if (-not $SkipContract) {
  Write-Host '==> contract: engine OpenAPI 基线 + Java 路由粗筛'
  Push-Location $root
  $py = Join-Path $root 'engine-api/.venv/Scripts/python.exe'
  & $py 'scripts/dump-openapi.py' | Out-Null
  if ($LASTEXITCODE -ne 0) { $failed += 'engine-openapi-dump' }
  git diff --exit-code -- docs/openapi/engine-api.json
  if ($LASTEXITCODE -ne 0) { $failed += 'engine-openapi-drift' }
  & $py 'scripts/check-java-routes.py'
  if ($LASTEXITCODE -ne 0) { $failed += 'java-route-guard' }
  Pop-Location
}

Write-Host ''
if ($failed.Count) {
  Write-Host "FAILED: $($failed -join ', ')"
  exit 1
}
Write-Host 'ALL GATES GREEN'
exit 0
