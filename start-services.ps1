Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RootPath = $PSScriptRoot
$CorePath = Join-Path $RootPath "microservice-core-go"
$PortalPath = Join-Path $RootPath "microservice-portal-java"

function Assert-Directory {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $ServiceName
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "No existe el directorio requerido para ${ServiceName}: $Path"
    }
}

function Resolve-MavenWrapperCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string] $WorkingDirectory
    )

    $MvnwCmd = Join-Path $WorkingDirectory "mvnw.cmd"
    $MvnwUnix = Join-Path $WorkingDirectory "mvnw"

    $MvnwJdk21Ps1 = Join-Path $WorkingDirectory "mvnw-jdk21.ps1"

    if (Test-Path -LiteralPath $MvnwJdk21Ps1 -PathType Leaf) {
        return ".\mvnw-jdk21.ps1 spring-boot:run -Dspring-boot.run.profiles=local"
    }

    if (Test-Path -LiteralPath $MvnwCmd -PathType Leaf) {
        return ".\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local"
    }

    if (Test-Path -LiteralPath $MvnwUnix -PathType Leaf) {
        return "./mvnw spring-boot:run -Dspring-boot.run.profiles=local"
    }

    throw "No se encontró Maven Wrapper en $WorkingDirectory. Agregue mvnw/mvnw.cmd antes de levantar el portal Java."
}

function Start-ServiceWindow {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Title,

        [Parameter(Mandatory = $true)]
        [string] $WorkingDirectory,

        [Parameter(Mandatory = $true)]
        [string] $Command,

        [Parameter(Mandatory = $true)]
        [string] $Port
    )

    $EscapedWorkingDirectory = $WorkingDirectory.Replace("'", "''")
    $EscapedTitle = $Title.Replace("'", "''")
    $EscapedPort = $Port.Replace("'", "''")

    $BootstrapCommand = @"
`$Host.UI.RawUI.WindowTitle = '$EscapedTitle'
Set-Location -LiteralPath '$EscapedWorkingDirectory'
Write-Host '[$EscapedTitle] Levantando servicio en $EscapedPort' -ForegroundColor Cyan
Write-Host '[$EscapedTitle] Directorio: $EscapedWorkingDirectory' -ForegroundColor DarkGray
Write-Host '[$EscapedTitle] Comando: $Command' -ForegroundColor DarkGray
$Command
"@

    Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList @("-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $BootstrapCommand) `
        -WindowStyle Normal | Out-Null
}

Assert-Directory -Path $CorePath -ServiceName "Core Go"
Assert-Directory -Path $PortalPath -ServiceName "Portal Java"

$CoreCommand = "go run cmd/api/main.go"
$PortalCommand = Resolve-MavenWrapperCommand -WorkingDirectory $PortalPath

Write-Host "Arrancando ecosistema local Zona K..." -ForegroundColor Green
Write-Host "Core Go      -> http://localhost:8081" -ForegroundColor Cyan
Write-Host "Portal Java  -> http://localhost:8080" -ForegroundColor Cyan

Start-ServiceWindow `
    -Title "Zona K Core Go :8081" `
    -WorkingDirectory $CorePath `
    -Command $CoreCommand `
    -Port ":8081"

Start-Sleep -Seconds 2

Start-ServiceWindow `
    -Title "Zona K Portal Java :8080" `
    -WorkingDirectory $PortalPath `
    -Command $PortalCommand `
    -Port ":8080"

Write-Host "Procesos de arranque disparados. Revise las ventanas de PowerShell de cada microservicio." -ForegroundColor Green
