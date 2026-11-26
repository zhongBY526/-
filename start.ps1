# W3-W4 一键启动（Windows PowerShell）：后端 + 前端各开一个窗口
# 先启动 pgvector 与 Ollama，见 README「快速启动」第 1、2 步
# 用法：右键「使用 PowerShell 运行」，或在 PowerShell 中执行 .\start.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$backend = Join-Path $root "backed"
$front = Join-Path $root "front"
$envFile = Join-Path $backend ".env"
$envSample = Join-Path $backend ".env.example"
$logDir = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if (-not (Test-Path $envFile)) {
    Copy-Item $envSample $envFile
    Write-Host "[启动失败] 已根据样例生成 backed\.env，请填入真实 DEEPSEEK_API_KEY 后重新运行。" -ForegroundColor Yellow
    exit 1
}

$keyLine = (Get-Content $envFile | Where-Object { $_ -match '^DEEPSEEK_API_KEY=' } | Select-Object -First 1)
if (-not $keyLine -or $keyLine -match '^DEEPSEEK_API_KEY=\s*$' -or $keyLine -match 'sk-your-key') {
    Write-Host "[启动失败] backed\.env 里的 DEEPSEEK_API_KEY 仍是占位值，请填入真实 Key。" -ForegroundColor Yellow
    exit 1
}

foreach ($port in 8080, 5173) {
    $busy = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($busy) {
        Write-Host "[启动失败] 端口 $port 已被占用，请先关闭占用进程。" -ForegroundColor Yellow
        exit 1
    }
}

# 依赖提示（不自动启动，避免误改本地环境）
try {
    Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:11434/api/tags" -TimeoutSec 2 | Out-Null
} catch {
    Write-Host "[提示] Ollama 未运行：上传文档/评测会失败。请先执行 ollama serve 和 ollama pull bge-m3" -ForegroundColor Yellow
}
$pg = docker ps --format '{{.Names}}' 2>$null | Select-String -SimpleMatch 'rag-pgvector'
if (-not $pg) {
    Write-Host "[提示] pgvector 容器未运行：请先执行 docker compose up -d" -ForegroundColor Yellow
}

if (-not (Test-Path (Join-Path $front "node_modules"))) {
    Write-Host "首次运行：安装前端依赖（npm install）..." -ForegroundColor Cyan
    Push-Location $front
    npm install
    Pop-Location
}

Write-Host "启动后端（新窗口）..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$backend'; ./mvnw spring-boot:run"

Start-Sleep -Seconds 2

Write-Host "启动前端（新窗口）..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$front'; npm run dev"

Write-Host ""
Write-Host "后端：http://localhost:8080" -ForegroundColor Green
Write-Host "前端：http://localhost:5173" -ForegroundColor Green
Write-Host "登录账号：admin/admin123 或 demo/demo123" -ForegroundColor Green
Write-Host "停止：分别在两个新窗口按 Ctrl+C" -ForegroundColor Green
