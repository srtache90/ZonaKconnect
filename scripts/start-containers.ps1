Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root
docker compose -f docker-compose.local.yml up -d --build
docker compose -f docker-compose.local.yml ps
