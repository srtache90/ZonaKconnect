param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MavenArgs
)

$ErrorActionPreference = "Stop"
$PortalRoot = $PSScriptRoot
$ResolveScript = Join-Path $PortalRoot "..\scripts\resolve-jdk21.ps1"

if (-not (Test-Path -LiteralPath $ResolveScript)) {
    throw "No se encontro el script de resolucion JDK 21: $ResolveScript"
}

. $ResolveScript
$jdkHome = Resolve-Jdk21Home -PortalPath $PortalRoot

$env:JAVA_HOME = $jdkHome
$env:PATH = "$jdkHome\bin;$env:PATH"

Write-Host "Usando JDK 21 desde: $jdkHome" -ForegroundColor DarkCyan

$mvnw = Join-Path $PortalRoot "mvnw.cmd"
if (-not (Test-Path -LiteralPath $mvnw)) {
    throw "No se encontro Maven Wrapper: $mvnw"
}

& $mvnw @MavenArgs
exit $LASTEXITCODE
