#Requires -Version 5.1
<#
  Wrapper de Git para esta máquina: WDAC bloquea libcurl/sh de Git for Windows.
  pull / push / fetch / ls-remote usan alpine/git en Docker.
  El resto (status, commit, diff, log) usa git.exe nativo.
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$GitCliArgs = @()
foreach ($a in $args) {
    if ($GitCliArgs.Count -eq 0 -and $a -eq "--") { continue }
    $GitCliArgs += [string]$a
}

$NativeGit = "C:\Program Files\Git\cmd\git.exe"
$Gh = "C:\Program Files\GitHub CLI\gh.exe"
$Image = "alpine/git"
$MirrorVolume = "zonak-git-mirror"
$NetworkVerbs = @("pull", "push", "fetch", "clone", "ls-remote")

function Get-RepoRoot {
    $dir = (Get-Location).Path
    while ($dir) {
        if (Test-Path (Join-Path $dir ".git")) {
            return $dir
        }
        $parent = Split-Path $dir -Parent
        if ($parent -eq $dir) { break }
        $dir = $parent
    }
    if (Test-Path (Join-Path $PSScriptRoot "..\.git")) {
        return (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
    }
    return (Get-Location).Path
}

function Get-GitHubToken {
    if (-not (Test-Path $Gh)) {
        throw "No está GitHub CLI en $Gh. Inicie sesión con: gh auth login"
    }
    $token = & $Gh auth token 2>$null
    if (-not $token) {
        throw "GitHub CLI no tiene sesión. Ejecute: gh auth login"
    }
    return ([string]$token).Trim()
}

function Get-OriginUrl {
    param([string] $RepoRoot)
    if (Test-Path $NativeGit) {
        $url = & $NativeGit -C $RepoRoot remote get-url origin 2>$null
        if ($LASTEXITCODE -eq 0 -and $url) { return ([string]$url).Trim() }
    }
    return "https://github.com/srtache90/ZonaKconnect.git"
}

function Get-AuthUrl {
    param([string] $OriginUrl, [string] $Token)
    if ($OriginUrl -like "https://*") {
        return ($OriginUrl -replace "^https://", "https://x-access-token:${Token}@")
    }
    return $OriginUrl
}

function Invoke-NativeGit {
    if (-not (Test-Path $NativeGit)) {
        throw "No se encontró Git nativo en $NativeGit"
    }
    $gitArgs = @($args)
    & $NativeGit @gitArgs
    return $LASTEXITCODE
}

function Test-IsNetworkGit {
    param([string[]] $CliArgs)
    foreach ($a in $CliArgs) {
        if ($NetworkVerbs -contains $a.ToLowerInvariant()) {
            return $true
        }
    }
    return $false
}

function Invoke-Docker {
    param([string[]] $DockerArgs)
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & docker @DockerArgs
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    return $code
}

function Ensure-GitImage {
    $null = Invoke-Docker @("image", "inspect", $Image)
}

function Ensure-Mirror {
    param([string] $OriginUrl, [string] $Token)
    $null = Invoke-Docker @("volume", "create", $MirrorVolume)
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $probe = docker run --rm -v "${MirrorVolume}:/mirror" $Image --git-dir=/mirror rev-parse --is-bare-repository 2>$null
    $ErrorActionPreference = $prevEap
    if ($probe -ne "true") {
        Write-Host "Inicializando espejo Git en Docker..."
        $null = Invoke-Docker @("volume", "rm", "-f", $MirrorVolume)
        $null = Invoke-Docker @("volume", "create", $MirrorVolume)
        $auth = Get-AuthUrl -OriginUrl $OriginUrl -Token $Token
        $code = Invoke-Docker @(
            "run", "--rm",
            "-v", "${MirrorVolume}:/mirror",
            $Image, "clone", "--mirror", $auth, "/mirror"
        )
        if ($code -ne 0) {
            throw "No se pudo clonar el espejo de $OriginUrl"
        }
    }
}

function Invoke-MirrorGit {
    param([string[]] $GitArgs)
    $dockerArgs = @(
        "run", "--rm",
        "-v", "${MirrorVolume}:/mirror",
        "-w", "/mirror",
        "-e", "GIT_CONFIG_COUNT=1",
        "-e", "GIT_CONFIG_KEY_0=safe.directory",
        "-e", "GIT_CONFIG_VALUE_0=*",
        $Image
    ) + $GitArgs
    return Invoke-Docker $dockerArgs
}

function Invoke-LocalGit {
    param([string] $RepoRoot, [string[]] $GitArgs)
    $dockerArgs = @(
        "run", "--rm",
        "-v", "${RepoRoot}:/git",
        "-v", "${MirrorVolume}:/mirror",
        "-w", "/git",
        "-e", "GIT_CONFIG_COUNT=1",
        "-e", "GIT_CONFIG_KEY_0=safe.directory",
        "-e", "GIT_CONFIG_VALUE_0=*",
        $Image
    ) + $GitArgs
    return Invoke-Docker $dockerArgs
}

function Invoke-NetworkGit {
    param([string[]] $CliArgs)
    $repo = Get-RepoRoot
    $origin = Get-OriginUrl -RepoRoot $repo
    $token = Get-GitHubToken
    $auth = Get-AuthUrl -OriginUrl $origin -Token $token
    Ensure-GitImage
    Ensure-Mirror -OriginUrl $origin -Token $token

    $verb = ($CliArgs | Where-Object { $NetworkVerbs -contains $_.ToLowerInvariant() } | Select-Object -First 1)
    if (-not $verb) { $verb = $CliArgs[0] }
    $verb = $verb.ToLowerInvariant()

    switch ($verb) {
        "fetch" {
            Write-Host "git fetch (Docker → GitHub)..."
            $code = Invoke-MirrorGit @("fetch", "--prune", $auth, "+refs/heads/*:refs/heads/*")
            if ($code -ne 0) { return $code }
            return Invoke-LocalGit -RepoRoot $repo -GitArgs @("fetch", "/mirror", "+refs/heads/*:refs/remotes/origin/*")
        }
        "pull" {
            Write-Host "git pull (Docker fetch + merge local)..."
            $code = Invoke-MirrorGit @("fetch", "--prune", $auth, "+refs/heads/*:refs/heads/*")
            if ($code -ne 0) { return $code }
            $code = Invoke-LocalGit -RepoRoot $repo -GitArgs @("fetch", "/mirror", "+refs/heads/*:refs/remotes/origin/*")
            if ($code -ne 0) { return $code }
            if ($CliArgs -contains "--rebase" -or $CliArgs -contains "-r") {
                & $NativeGit @("-C", $repo, "rebase", "origin/main")
                return $LASTEXITCODE
            }
            & $NativeGit @("-C", $repo, "merge", "--ff-only", "origin/main")
            return $LASTEXITCODE
        }
        "push" {
            Write-Host "git push (local → Docker → GitHub)..."
            $branch = & $NativeGit -C $repo rev-parse --abbrev-ref HEAD
            if ($LASTEXITCODE -ne 0 -or -not $branch) { $branch = "main" }
            $branch = ([string]$branch).Trim()
            $code = Invoke-LocalGit -RepoRoot $repo -GitArgs @("--git-dir=/mirror", "fetch", "/git", "+refs/heads/${branch}:refs/heads/${branch}")
            if ($code -ne 0) { return $code }
            return Invoke-MirrorGit @("push", $auth, "refs/heads/${branch}:refs/heads/${branch}")
        }
        "ls-remote" {
            return Invoke-MirrorGit @("ls-remote", "--heads", $auth)
        }
        default {
            return Invoke-NativeGit @($CliArgs)
        }
    }
}

if ($GitCliArgs.Count -eq 0) {
    if (Test-Path $NativeGit) {
        & $NativeGit
        exit $LASTEXITCODE
    }
    Write-Host "Uso: git pull | git push | git status"
    exit 1
}

if (Test-IsNetworkGit -CliArgs $GitCliArgs) {
    exit (Invoke-NetworkGit -CliArgs $GitCliArgs)
}

exit (Invoke-NativeGit @GitCliArgs)
