param(
    [string] $ContainerName = "zona-k-postgres-local",
    [string] $Database = "zona_k_facturacion",
    [string] $User = "zona_k_app",
    [string] $OutputDir = "",
    [string] $ComposeFile = "docker-compose.local.yml",
    [switch] $SqlFormat
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $RepoRoot "backups\db"
}

function Assert-DockerAvailable {
    $null = docker version 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Docker no está disponible. Inicia Docker Desktop e intenta de nuevo."
    }
}

function Ensure-PostgresContainer {
    param(
        [string] $Name,
        [string] $ComposePath
    )

    $running = docker ps --filter "name=^/${Name}$" --format "{{.Names}}"
    if ($running -eq $Name) {
        return
    }

    $exists = docker ps -a --filter "name=^/${Name}$" --format "{{.Names}}"
    if ($exists -eq $Name) {
        Write-Host "Iniciando contenedor $Name..."
        docker start $Name | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "No se pudo iniciar el contenedor $Name."
        }
        return
    }

    if (-not (Test-Path -LiteralPath $ComposePath -PathType Leaf)) {
        throw "No existe el contenedor $Name ni el compose en: $ComposePath"
    }

    Write-Host "Levantando servicio postgres desde $ComposePath..."
    Push-Location $RepoRoot
    try {
        docker compose -f $ComposeFile up -d postgres | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose up postgres falló."
        }
    }
    finally {
        Pop-Location
    }

    $deadline = (Get-Date).AddMinutes(2)
    while ((Get-Date) -lt $deadline) {
        $health = docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}" $Name 2>$null
        if ($health -eq "healthy" -or $health -eq "none") {
            $running = docker ps --filter "name=^/${Name}$" --format "{{.Names}}"
            if ($running -eq $Name) {
                return
            }
        }
        Start-Sleep -Seconds 2
    }

    throw "Postgres no quedó listo a tiempo. Revisa: docker compose -f $ComposeFile logs postgres"
}

Assert-DockerAvailable
$ComposePath = Join-Path $RepoRoot $ComposeFile
Ensure-PostgresContainer -Name $ContainerName -ComposePath $ComposePath

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if ($SqlFormat) {
    $fileName = "zona_k_facturacion_${stamp}.sql"
    $outputPath = Join-Path $OutputDir $fileName
    Write-Host "Exportando $Database (SQL) -> $outputPath"

    docker exec $ContainerName pg_dump -U $User -d $Database --no-owner --no-acl `
        | Set-Content -LiteralPath $outputPath -Encoding utf8
}
else {
    $fileName = "zona_k_facturacion_${stamp}.dump"
    $outputPath = Join-Path $OutputDir $fileName
    $containerDumpPath = "/tmp/zona_k_backup_${stamp}.dump"

    Write-Host "Exportando $Database (custom) -> $outputPath"
    docker exec $ContainerName pg_dump -U $User -d $Database -Fc -f $containerDumpPath
    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump falló dentro del contenedor."
    }

    docker cp "${ContainerName}:${containerDumpPath}" $outputPath
    if ($LASTEXITCODE -ne 0) {
        throw "No se pudo copiar el dump desde el contenedor."
    }

    docker exec $ContainerName rm -f $containerDumpPath | Out-Null
}

if (-not (Test-Path -LiteralPath $outputPath -PathType Leaf)) {
    throw "No se generó el archivo de backup."
}

$sizeKb = [math]::Round((Get-Item -LiteralPath $outputPath).Length / 1KB, 1)
Write-Host ""
Write-Host "Backup listo:"
Write-Host "  Archivo: $outputPath"
Write-Host "  Tamaño:  ${sizeKb} KB"
Write-Host ""
Write-Host "Para restaurar en otro equipo:"
Write-Host "  .\scripts\restore-db.ps1 -BackupFile `"$outputPath`""
