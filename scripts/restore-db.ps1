param(
    [Parameter(Mandatory = $true)]
    [string] $BackupFile,
    [string] $ContainerName = "zona-k-postgres-local",
    [string] $Database = "zona_k_facturacion",
    [string] $User = "zona_k_app",
    [string] $ComposeFile = "docker-compose.local.yml",
    [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot

function Assert-DockerAvailable {
    $null = docker version 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Docker no está disponible. Inicia Docker Desktop e intenta de nuevo."
    }
}

function Resolve-BackupPath {
    param([string] $Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }

    $fromRepo = Join-Path $RepoRoot $Path
    if (Test-Path -LiteralPath $fromRepo -PathType Leaf) {
        return $fromRepo
    }

    $fromCwd = Join-Path (Get-Location) $Path
    if (Test-Path -LiteralPath $fromCwd -PathType Leaf) {
        return $fromCwd
    }

    throw "No se encontró el archivo de backup: $Path"
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
        throw "No existe el contenedor $Name. Levanta el entorno con: docker compose -f $ComposeFile up -d postgres"
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

    Start-Sleep -Seconds 5
}

Assert-DockerAvailable
$backupPath = Resolve-BackupPath -Path $BackupFile
$ComposePath = Join-Path $RepoRoot $ComposeFile
Ensure-PostgresContainer -Name $ContainerName -ComposePath $ComposePath

if (-not $Force) {
    Write-Warning "Esto reemplazará datos en la base '$Database' del contenedor '$ContainerName'."
    Write-Warning "Archivo: $backupPath"
    $answer = Read-Host "¿Continuar? (s/N)"
    if ($answer -notin @("s", "S", "si", "Si", "SI", "y", "Y")) {
        Write-Host "Restauración cancelada."
        exit 0
    }
}

$extension = [System.IO.Path]::GetExtension($backupPath).ToLowerInvariant()
$containerPath = "/tmp/zona_k_restore$extension"

Write-Host "Copiando backup al contenedor..."
docker cp $backupPath "${ContainerName}:${containerPath}"
if ($LASTEXITCODE -ne 0) {
    throw "docker cp falló."
}

try {
    if ($extension -eq ".sql") {
        Write-Host "Restaurando desde SQL..."
        Get-Content -LiteralPath $backupPath -Raw | docker exec -i $ContainerName psql -U $User -d $Database -v ON_ERROR_STOP=1
        if ($LASTEXITCODE -ne 0) {
            throw "psql falló al restaurar el backup SQL."
        }
    }
    else {
        Write-Host "Restaurando desde dump custom (pg_restore)..."
        docker exec $ContainerName pg_restore -U $User -d $Database --clean --if-exists $containerPath
        if ($LASTEXITCODE -ne 0) {
            throw "pg_restore falló. Revisa que la base exista y que el dump sea compatible."
        }
    }
}
finally {
    docker exec $ContainerName rm -f $containerPath | Out-Null
}

Write-Host ""
Write-Host "Restauración completada."
Write-Host "Validación sugerida:"
Write-Host ('  docker exec -it {0} psql -U {1} -d {2} -c "\dt"' -f $ContainerName, $User, $Database)
Write-Host ""
Write-Host "Si el stack completo no está arriba:"
Write-Host "  docker compose -f $ComposeFile up -d"
