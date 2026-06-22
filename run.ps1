$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $root "aft-server\target\aft-server-0.1.0-SNAPSHOT-exec.jar"
$port = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { "51780" }

$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    throw "AFT Studio is already running at http://127.0.0.1:$port (process $($listener.OwningProcess)). Stop it with Ctrl+C before rebuilding."
}

$needsBuild = -not (Test-Path $jar)
if (-not $needsBuild) {
    $jarEntries = & jar tf $jar 2>$null
    $needsBuild = $LASTEXITCODE -ne 0 -or -not ($jarEntries | Select-String '^BOOT-INF/')
}
if (-not $needsBuild) {
    $jarTime = (Get-Item $jar).LastWriteTimeUtc
    $sourceRoots = @(
        "pom.xml", "aft-domain", "aft-openapi", "aft-engine", "aft-server", "aft-web"
    )
    $newerSource = Get-ChildItem ($sourceRoots | ForEach-Object { Join-Path $root $_ }) -Recurse -File |
        Where-Object {
            $_.FullName -notmatch '\\(target|node_modules|dist|\.npm-cache)\\' -and
            $_.LastWriteTimeUtc -gt $jarTime
        } |
        Select-Object -First 1
    $needsBuild = $null -ne $newerSource
}

if ($needsBuild) {
    Write-Host "Source changes detected. Rebuilding AFT Studio..." -ForegroundColor Yellow
    & (Join-Path $root "build.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed with exit code $LASTEXITCODE"
    }
}

$url = "http://127.0.0.1:$port"
Write-Host ""
Write-Host "AFT Studio is starting..." -ForegroundColor Cyan
Write-Host "Open: $url" -ForegroundColor Green
Write-Host "Keep this window open. Press Ctrl+C to stop." -ForegroundColor DarkGray
Write-Host ""

& java -jar $jar "--server.port=$port"
