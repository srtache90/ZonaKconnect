function Resolve-Jdk21Home {
    param(
        [string] $PortalPath = (Join-Path $PSScriptRoot "..\microservice-portal-java")
    )

    $PortalPath = (Resolve-Path -LiteralPath $PortalPath).Path
    $LocalHomeFile = Join-Path $PortalPath "jdk21.home"

    if ($env:JDK21_HOME -and (Test-Path -LiteralPath (Join-Path $env:JDK21_HOME "bin\java.exe"))) {
        return (Resolve-Path -LiteralPath $env:JDK21_HOME).Path
    }

    if (Test-Path -LiteralPath $LocalHomeFile) {
        $configuredHome = (Get-Content -LiteralPath $LocalHomeFile -ErrorAction Stop | Select-Object -First 1).Trim()
        if ($configuredHome -and (Test-Path -LiteralPath (Join-Path $configuredHome "bin\java.exe"))) {
            return (Resolve-Path -LiteralPath $configuredHome).Path
        }
    }

    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        $currentVersion = & (Join-Path $env:JAVA_HOME "bin\java.exe") -version 2>&1 | Select-Object -First 1
        if ($currentVersion -match 'version "21\.') {
            return (Resolve-Path -LiteralPath $env:JAVA_HOME).Path
        }
    }

    $candidates = New-Object System.Collections.Generic.List[string]

    $androidOpenJdkRoot = "C:\Program Files\Android\openjdk"
    if (Test-Path -LiteralPath $androidOpenJdkRoot) {
        Get-ChildItem -LiteralPath $androidOpenJdkRoot -Directory -Filter "jdk-21*" |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    @(
        "C:\Program Files\Java\jdk-21",
        "C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot",
        "C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot",
        "C:\Java\jdk-21"
    ) | ForEach-Object { $candidates.Add($_) }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        $javaExe = Join-Path $candidate "bin\java.exe"
        if (-not (Test-Path -LiteralPath $javaExe)) {
            continue
        }

        $version = & $javaExe -version 2>&1 | Select-Object -First 1
        if ($version -match 'version "21\.') {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw @"
No se encontro JDK 21.

Opciones:
  1. Definir JDK21_HOME con la ruta del JDK 21.
  2. Crear microservice-portal-java\jdk21.home con la ruta absoluta del JDK 21.
  3. Actualizar JAVA_HOME del sistema a JDK 21.

Ejemplo:
  JDK21_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8
"@
}

if ($MyInvocation.InvocationName -ne '.') {
    Resolve-Jdk21Home @args
}
