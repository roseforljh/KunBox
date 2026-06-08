param(
    [Parameter()] [string] $Tag
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$officialRemote = 'https://github.com/SagerNet/sing-box.git'
$officialReleaseApi = 'https://api.github.com/repos/SagerNet/sing-box/releases?per_page=30'
$gomobileVersion = 'v0.1.12'
$backupKeepCount = 3
$requiredMethods = @(
    'getKunBoxVersion',
    'resetAllConnections',
    'recoverNetworkAuto',
    'checkNetworkRecoveryNeeded',
    'closeAllTrackedConnections',
    'getConnectionCount',
    'closeIdleConnections'
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$upstreamDir = Join-Path $scriptDir 'upstream-sing-box'
$patchesDir = Join-Path $scriptDir 'patches'
$targetAar = Join-Path $repoRoot 'app\libs\libbox.aar'
$gradleWrapper = Join-Path $repoRoot 'gradlew.bat'
$backupDir = $scriptDir
$timestamp = Get-Date -Format 'yyyyMMdd.HHmmss'
$backupAar = Join-Path $backupDir ("libbox.aar.backup-before-replace.$timestamp")
$tempDir = Join-Path $scriptDir 'tmp-sync-kernel-current'
$resolvedTag = $null
$patchFile = $null
$syncSucceeded = $false

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Write-Stage([string] $name) {
    Write-Host ''
    Write-Host "========== $name ==========" -ForegroundColor Cyan
}

function Fail([string] $message) {
    throw $message
}

function Remove-PathIfExists {
    param(
        [Parameter(Mandatory = $true)] [string] $Path
    )

    if (Test-Path $Path) {
        Remove-Item -Path $Path -Recurse -Force
    }
}

function Remove-WorkspaceGarbage {
    $tempPatterns = @(
        'tmp-sync-kernel-*',
        'tmp-libbox-*-check'
    )

    foreach ($pattern in $tempPatterns) {
        Get-ChildItem -Path $scriptDir -Filter $pattern -Force -ErrorAction SilentlyContinue |
            ForEach-Object {
                Remove-PathIfExists -Path $_.FullName
            }
    }
}

function Trim-OldAarBackups {
    $backups = Get-ChildItem -Path $backupDir -File -Filter 'libbox.aar.backup-before-replace.*' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending

    if (@($backups).Count -le $backupKeepCount) {
        return
    }

    $backups |
        Select-Object -Skip $backupKeepCount |
        ForEach-Object {
            Remove-PathIfExists -Path $_.FullName
        }
}

function Get-PatchTouchedPaths {
    param(
        [Parameter(Mandatory = $true)] [string] $PatchPath
    )

    $paths = New-Object System.Collections.Generic.List[string]
    foreach ($line in Get-Content -Path $PatchPath) {
        if ($line -notmatch '^\+\+\+ b/(.+)$') {
            continue
        }

        $relativePath = $matches[1]
        if ($relativePath -eq '/dev/null') {
            continue
        }

        if (-not $paths.Contains($relativePath)) {
            $paths.Add($relativePath)
        }
    }

    return $paths.ToArray()
}

function Test-GitTrackedPath {
    param(
        [Parameter(Mandatory = $true)] [string] $gitBinary,
        [Parameter(Mandatory = $true)] [string] $RelativePath
    )

    Push-Location $upstreamDir
    try {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & $gitBinary 'ls-files' '--error-unmatch' '--' $RelativePath *> $null
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorActionPreference
        return $exitCode -eq 0
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Pop-Location
    }
}

function Remove-UpstreamGeneratedArtifacts {
    param(
        [Parameter()] [switch] $RemoveBuiltAar,
        [Parameter()] [string[]] $PatchTouchedPaths = @(),
        [Parameter()] [string] $gitBinary
    )

    $pathsToRemove = @(
        'libbox-sources.jar',
        'libbox-legacy-sources.jar',
        'libbox-legacy.aar'
    )

    if ($RemoveBuiltAar.IsPresent) {
        $pathsToRemove += 'libbox.aar'
    }

    foreach ($relativePath in $pathsToRemove) {
        Remove-PathIfExists -Path (Join-Path $upstreamDir $relativePath)
    }

    if ($gitBinary) {
        foreach ($relativePath in $PatchTouchedPaths) {
            $candidatePath = Join-Path $upstreamDir $relativePath
            if ((Test-Path $candidatePath) -and (-not (Test-GitTrackedPath -gitBinary $gitBinary -RelativePath $relativePath))) {
                Remove-PathIfExists -Path $candidatePath
            }
        }
    }
}

function Assert-UpstreamTreeIsClean([string] $gitBinary) {
    $status = (Get-ExternalOutput -FilePath $gitBinary -Arguments @('status', '--porcelain', '--untracked-files=all') -WorkingDirectory $upstreamDir -FailureMessage 'Failed to inspect upstream worktree').Trim()
    if (-not [string]::IsNullOrWhiteSpace($status)) {
        Fail "upstream-sing-box still has leftover tracked or untracked changes after targeted cleanup:`n$status`nRefuse to continue with a dirty cache."
    }
}

function Remove-ProblematicProcessEnvVars {
    foreach ($key in [System.Environment]::GetEnvironmentVariables('Process').Keys) {
        $name = [string] $key
        if ($name.StartsWith('=')) {
            [System.Environment]::SetEnvironmentVariable($name, $null, 'Process')
        }
    }
}

function Get-GitHubReleaseJson([string] $url) {
    try {
        return Invoke-RestMethod -Uri $url -Headers @{
            'Accept' = 'application/vnd.github+json'
            'User-Agent' = 'KunBox-sync-kernel'
        }
    }
    catch {
        Fail "Failed to query official sing-box releases from GitHub: $url`n$($_.Exception.Message)"
    }
}

function Test-StableOfficialTag([string] $value) {
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $false
    }

    if ($value -match '(?i)(alpha|beta|rc)') {
        return $false
    }

    if ($value -notmatch '^v\d+\.\d+\.\d+$') {
        return $false
    }

    return $true
}

function Resolve-TargetTag {
    if (-not [string]::IsNullOrWhiteSpace($Tag)) {
        Write-Host "Target tag override: $Tag"
        return $Tag.Trim()
    }

    Write-Host 'Resolving latest official stable sing-box release...'
    $releases = Get-GitHubReleaseJson -url $officialReleaseApi
    foreach ($release in @($releases)) {
        if ($release.draft -or $release.prerelease) {
            continue
        }

        $candidateTag = [string] $release.tag_name
        if (-not (Test-StableOfficialTag -value $candidateTag)) {
            continue
        }

        Write-Host "Resolved latest official stable tag: $candidateTag"
        return $candidateTag
    }

    Fail 'Unable to resolve the latest official stable sing-box release. GitHub returned no non-prerelease tag that matches v<major>.<minor>.<patch>.'
}

function Resolve-PatchFile([string] $targetTag) {
    $candidate = Join-Path $patchesDir ("kunbox-$targetTag.patch")

    if (Test-Path $candidate) {
        Write-Host "Using exact patch for ${targetTag}: $candidate"
        return $candidate
    }

    $fallbackPatch = Get-ChildItem -Path $patchesDir -File -Filter 'kunbox-*.patch' |
        ForEach-Object {
            if ($_.BaseName -match '^kunbox-v(\d+)\.(\d+)\.(\d+)$') {
                [pscustomobject]@{
                    File = $_
                    Version = [version] "$($matches[1]).$($matches[2]).$($matches[3])"
                }
            }
        } |
        Sort-Object -Property Version -Descending |
        Select-Object -First 1 |
        ForEach-Object { $_.File }

    if ($null -eq $fallbackPatch) {
        Fail "No KunBox patch file found in $patchesDir. Add at least one kunbox-<tag>.patch before syncing this official release."
    }

    Write-Warning "Patch for $targetTag not found, falling back to $($fallbackPatch.Name)."
    return $fallbackPatch.FullName
}

function Format-ProcessArgument([string] $value) {
    if ($null -eq $value) {
        return '""'
    }

    if ($value -eq '') {
        return '""'
    }

    if ($value -notmatch '[\s"]') {
        return $value
    }

    return '"' + ($value -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

function Resolve-CommandPath([string] $commandName, [string] $hint) {
    $command = Get-Command $commandName -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) {
        Fail "$commandName not found. $hint"
    }
    return $command.Source
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)] [string] $FilePath,
        [Parameter()] [string[]] $Arguments = @(),
        [Parameter()] [string] $WorkingDirectory = $repoRoot,
        [Parameter()] [string] $FailureMessage = 'External command failed'
    )

    Push-Location $WorkingDirectory
    try {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & $FilePath @Arguments
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorActionPreference
        if ($exitCode -ne 0) {
            Fail "$FailureMessage. Command: $FilePath $($Arguments -join ' ')"
        }
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Pop-Location
    }
}

function Get-ExternalOutput {
    param(
        [Parameter(Mandatory = $true)] [string] $FilePath,
        [Parameter()] [string[]] $Arguments = @(),
        [Parameter()] [string] $WorkingDirectory = $repoRoot,
        [Parameter()] [string] $FailureMessage = 'External command failed'
    )

    Push-Location $WorkingDirectory
    try {
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = $FilePath
        $startInfo.WorkingDirectory = $WorkingDirectory
        $startInfo.UseShellExecute = $false
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.CreateNoWindow = $true
        $startInfo.Arguments = (($Arguments | ForEach-Object { Format-ProcessArgument $_ }) -join ' ')

        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $startInfo
        [void] $process.Start()

        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()

        $exitCode = $process.ExitCode
        $output = $stdout + $stderr
        if ($exitCode -ne 0) {
            $detail = $output.Trim()
            Fail "$FailureMessage. Command: $FilePath $($Arguments -join ' ')`n$detail"
        }
        return $output
    }
    finally {
        Pop-Location
    }
}

function Resolve-AndroidSdk {
    $candidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    ) | Where-Object { $_ }

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return (Resolve-Path $candidate).Path
        }
    }

    Fail 'Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, or install to %LOCALAPPDATA%\Android\Sdk.'
}

function Resolve-AndroidNdk([string] $sdkPath) {
    $preferredVersion = '29.0.14206865'

    if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) {
        return (Resolve-Path $env:ANDROID_NDK_HOME).Path
    }

    $ndkRoot = Join-Path $sdkPath 'ndk'
    if (-not (Test-Path $ndkRoot)) {
        Fail "Android SDK is missing the ndk directory: $ndkRoot"
    }

    $preferredPath = Join-Path $ndkRoot $preferredVersion
    if (Test-Path $preferredPath) {
        return (Resolve-Path $preferredPath).Path
    }

    $fallback = Get-ChildItem -Path $ndkRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if ($null -eq $fallback) {
        Fail "No Android NDK found. Install $preferredVersion or newer."
    }

    Write-Warning "Preferred NDK $preferredVersion not found. Falling back to $($fallback.Name)."
    return $fallback.FullName
}

function Extract-ZipEntry {
    param(
        [Parameter(Mandatory = $true)] [string] $ZipPath,
        [Parameter(Mandatory = $true)] [string] $EntryName,
        [Parameter(Mandatory = $true)] [string] $DestinationPath
    )

    $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) {
            Fail "Archive entry $EntryName not found in $ZipPath"
        }

        $parent = Split-Path -Parent $DestinationPath
        if (-not (Test-Path $parent)) {
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
        }

        $entryStream = $entry.Open()
        $outputStream = [System.IO.File]::Create($DestinationPath)
        try {
            $entryStream.CopyTo($outputStream)
        }
        finally {
            $outputStream.Dispose()
            $entryStream.Dispose()
        }
    }
    finally {
        $zip.Dispose()
    }
}

function Ensure-GomobileTools([string] $goBinary, [string] $binDir) {
    Write-Host 'Checking gomobile and gobind...'

    if (-not (Test-Path $binDir)) {
        New-Item -ItemType Directory -Path $binDir -Force | Out-Null
    }

    $env:PATH = "$binDir;$env:PATH"

    $gomobilePath = Join-Path $binDir 'gomobile.exe'
    $gobindPath = Join-Path $binDir 'gobind.exe'

    if (-not (Test-Path $gomobilePath)) {
        Invoke-External -FilePath $goBinary -Arguments @('install', "github.com/sagernet/gomobile/cmd/gomobile@$gomobileVersion") -WorkingDirectory $repoRoot -FailureMessage 'Failed to install gomobile'
    }

    if (-not (Test-Path $gobindPath)) {
        Invoke-External -FilePath $goBinary -Arguments @('install', "github.com/sagernet/gomobile/cmd/gobind@$gomobileVersion") -WorkingDirectory $repoRoot -FailureMessage 'Failed to install gobind'
    }

    if (-not (Test-Path $gomobilePath) -or -not (Test-Path $gobindPath)) {
        Fail 'gomobile or gobind is still missing after install. Check GOPATH/bin and Go permissions.'
    }

}

function Prepare-UpstreamTree([string] $gitBinary) {
    Write-Stage 'Stage 1/5: Prepare upstream'

    if (-not (Test-Path $upstreamDir)) {
        Invoke-External -FilePath $gitBinary -Arguments @('clone', '--branch', $resolvedTag, '--single-branch', $officialRemote, $upstreamDir) -WorkingDirectory $scriptDir -FailureMessage 'Failed to clone official sing-box'
    }

    if (-not (Test-Path (Join-Path $upstreamDir '.git'))) {
        Fail "$upstreamDir exists but is not a git worktree."
    }

    $remoteUrl = (Get-ExternalOutput -FilePath $gitBinary -Arguments @('remote', 'get-url', 'origin') -WorkingDirectory $upstreamDir -FailureMessage 'Failed to read upstream origin').Trim()
    if ($remoteUrl -notmatch 'SagerNet/sing-box(\.git)?$') {
        Fail "upstream-sing-box origin is not the official repository: $remoteUrl"
    }

    $patchTouchedPaths = Get-PatchTouchedPaths -PatchPath $patchFile

    Invoke-External -FilePath $gitBinary -Arguments @('fetch', 'origin', '--tags', '--force') -WorkingDirectory $upstreamDir -FailureMessage 'Failed to fetch official tags'
    Invoke-External -FilePath $gitBinary -Arguments @('checkout', '--force', '--detach', $resolvedTag) -WorkingDirectory $upstreamDir -FailureMessage "Failed to checkout $resolvedTag"
    Invoke-External -FilePath $gitBinary -Arguments @('reset', '--hard', $resolvedTag) -WorkingDirectory $upstreamDir -FailureMessage 'Failed to reset upstream worktree'
    Remove-UpstreamGeneratedArtifacts -RemoveBuiltAar -PatchTouchedPaths $patchTouchedPaths -gitBinary $gitBinary
    Assert-UpstreamTreeIsClean -gitBinary $gitBinary
    Write-Host "Using patch: $patchFile"
    $patchName = [System.IO.Path]::GetFileName($patchFile)
    Invoke-External -FilePath $gitBinary -Arguments @('apply', '--check', $patchFile) -WorkingDirectory $upstreamDir -FailureMessage "KunBox patch $patchName does not apply cleanly to official $resolvedTag. Fix the coupling first."
    Invoke-External -FilePath $gitBinary -Arguments @('apply', '--whitespace=nowarn', $patchFile) -WorkingDirectory $upstreamDir -FailureMessage 'Failed to apply KunBox patch'
}

function Build-Libbox([string] $goBinary) {
    Write-Stage 'Stage 2/5: Build libbox.aar'
    $env:GOTOOLCHAIN = 'local'
    Invoke-External -FilePath $goBinary -Arguments @('run', './cmd/internal/build_libbox', '-target', 'android') -WorkingDirectory $upstreamDir -FailureMessage 'Failed to build libbox.aar'

    $builtAar = Join-Path $upstreamDir 'libbox.aar'
    if (-not (Test-Path $builtAar)) {
        Fail "Build completed but $builtAar was not created."
    }

    return $builtAar
}

function Assert-AarMethods([string] $aarPath, [string] $javapBinary) {
    Write-Stage 'Stage 3/5: Verify AAR methods'

    Remove-PathIfExists -Path $tempDir
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

    $classesJar = Join-Path $tempDir 'classes.jar'
    Extract-ZipEntry -ZipPath $aarPath -EntryName 'classes.jar' -DestinationPath $classesJar

    $javapOutput = Get-ExternalOutput -FilePath $javapBinary -Arguments @('-classpath', $classesJar, 'io.nekohasekai.libbox.Libbox') -WorkingDirectory $tempDir -FailureMessage 'javap verification failed'

    $missing = @()
    foreach ($method in $requiredMethods) {
        if ($javapOutput -notmatch ([regex]::Escape($method) + '\(')) {
            $missing += $method
        }
    }

    if ($missing.Count -gt 0) {
        $missingList = $missing -join ', '
        Fail "The new libbox.aar is missing required KunBox methods: $missingList. Fix the kernel export layer and rerun."
    }
}

function Replace-Aar([string] $aarPath) {
    Write-Stage 'Stage 4/5: Backup and replace libbox.aar'

    if (-not (Test-Path $targetAar)) {
        Fail "Target AAR not found: $targetAar"
    }

    Copy-Item -Path $targetAar -Destination $backupAar -Force
    Copy-Item -Path $aarPath -Destination $targetAar -Force
    Trim-OldAarBackups

    Write-Host "Backup created: $backupAar"
    Write-Host "Target replaced: $targetAar"
}

function Run-GradleValidation {
    Write-Stage 'Stage 5/5: Run Gradle validation'

    if (-not (Test-Path $gradleWrapper)) {
        Fail "Gradle wrapper not found: $gradleWrapper"
    }

    Invoke-External -FilePath $gradleWrapper -Arguments @('assembleDebug') -WorkingDirectory $repoRoot -FailureMessage 'assembleDebug failed. Fix the Kotlin compat layer or kernel export coupling.'
    Invoke-External -FilePath $gradleWrapper -Arguments @('testDebugUnitTest') -WorkingDirectory $repoRoot -FailureMessage 'testDebugUnitTest failed. Fix the Kotlin compat layer or kernel export coupling.'
    Invoke-External -FilePath $gradleWrapper -Arguments @('detekt') -WorkingDirectory $repoRoot -FailureMessage 'detekt failed. Fix the Kotlin compat layer or kernel export coupling.'
}

try {
    Write-Stage 'Environment check'

    $gitPath = Resolve-CommandPath -commandName 'git' -hint 'Install Git and make sure it is on PATH.'
    $goPath = Resolve-CommandPath -commandName 'go' -hint 'Install Go 1.24+ and make sure it is on PATH.'
    $javaPath = Resolve-CommandPath -commandName 'java' -hint 'Install JDK 17 and make sure it is on PATH.'
    $javapPath = Resolve-CommandPath -commandName 'javap' -hint 'Use a JDK that includes javap.'

    $androidSdk = Resolve-AndroidSdk
    $androidNdk = Resolve-AndroidNdk -sdkPath $androidSdk

    $javaVersionOutput = Get-ExternalOutput -FilePath $javaPath -Arguments @('-version') -WorkingDirectory $repoRoot -FailureMessage 'Failed to read Java version'
    if ($javaVersionOutput -notmatch '17') {
        Fail "Java 17 is required. Current output:`n$($javaVersionOutput.Trim())"
    }

    $goWorkspacePath = (Get-ExternalOutput -FilePath $goPath -Arguments @('env', 'GOPATH') -WorkingDirectory $repoRoot -FailureMessage 'Failed to read GOPATH').Trim()
    if ([string]::IsNullOrWhiteSpace($goWorkspacePath)) {
        Fail 'go env GOPATH returned an empty value.'
    }

    $gopathBin = Join-Path $goWorkspacePath 'bin'
    $env:ANDROID_HOME = $androidSdk
    $env:ANDROID_SDK_ROOT = $androidSdk
    $env:ANDROID_NDK_HOME = $androidNdk
    Remove-ProblematicProcessEnvVars

    Write-Host ("Go: {0}" -f (Get-ExternalOutput -FilePath $goPath -Arguments @('version') -WorkingDirectory $repoRoot -FailureMessage 'Failed to read Go version').Trim())
    Write-Host ("Java: {0}" -f $javaVersionOutput.Trim())
    Write-Host ("Android SDK: {0}" -f $androidSdk)
    Write-Host ("Android NDK: {0}" -f $androidNdk)
    Write-Host ("GOPATH/bin: {0}" -f $gopathBin)

    Remove-WorkspaceGarbage
    Trim-OldAarBackups

    $resolvedTag = Resolve-TargetTag
    $patchFile = Resolve-PatchFile -targetTag $resolvedTag
    Write-Host ("Patch file: {0}" -f $patchFile)

    Ensure-GomobileTools -goBinary $goPath -binDir $gopathBin
    Prepare-UpstreamTree -gitBinary $gitPath
    $builtAar = Build-Libbox -goBinary $goPath
    Assert-AarMethods -aarPath $builtAar -javapBinary $javapPath
    Replace-Aar -aarPath $builtAar
    Run-GradleValidation
    Remove-UpstreamGeneratedArtifacts -RemoveBuiltAar
    $syncSucceeded = $true

    Write-Host ''
    Write-Host 'sync-kernel.ps1 completed successfully.'
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
finally {
    Remove-PathIfExists -Path $tempDir
    Remove-WorkspaceGarbage
    if ($syncSucceeded) {
        Trim-OldAarBackups
    }
}
