param(
    [Parameter()] [string] $Tag,
    [Parameter()] [string] $SourceRepository,
    [Parameter()] [switch] $SelfTestBinaryScan
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$officialRemote = 'https://github.com/SagerNet/sing-box.git'
$officialReleaseApi = 'https://api.github.com/repos/SagerNet/sing-box/releases?per_page=30'
$trustedTagCommits = @{
    'v1.13.14' = '25a600db24f7680ad9806ce5427bd0ab8afe1114'
    'v1.13.15' = '3708fa18766cda1f11b77f6ed9c7bd61688f17df'
    'v1.13.16' = '17ec3c71af8ca946dc50bf0d927c39fc77322aec'
    'v1.13.18' = '45ca32dcb966f07f97fc888fe8586e359dbe8405'
    'v1.13.19' = 'b5ebaa1fc0f2b94256180b95468e73ef53caa27d'
}
$trustedPatchHashes = @{
    'v1.13.14' = '4C89FE3A078F5DC68DA351BF04B1B9536D048925266E15332E5D6F2BFAB2ECE2'
    'v1.13.15' = '7C8318A5C9B77BF0BF623FA4D8610FF4190B188B9BD4D6149E0D9BF51E1B0172'
    'v1.13.16' = '7C8318A5C9B77BF0BF623FA4D8610FF4190B188B9BD4D6149E0D9BF51E1B0172'
    'v1.13.18' = '660D190181ED104119A812B7ADC64E5E5C845E08AF8EAEB98860B9714A0EC10F'
    'v1.13.19' = '417EA7700B74CD3F953275E9A23A0B2CEA3F837910AF8EBC7DB3DDF419EDA1E5'
}
$requiredKunBoxNativeMarkers = @{
    'v1.13.18' = @(
        'pre-handshake connection rejected: reason=',
        'kunbox_physical_dial_gate_v1',
        'kunbox_wireguard_physical_gate_v1'
    )
    'v1.13.19' = @(
        'pre-handshake connection rejected: reason=',
        'kunbox_physical_dial_gate_v1',
        'kunbox_wireguard_physical_gate_v1',
        'kunbox root override destination:'
    )
}
$trustedPatchFiles = @{
    'v1.13.14' = @(
        'cmd/internal/build_libbox/main.go',
        'protocol/vless/outbound.go',
        'protocol/vless/outbound_test.go'
    )
    'v1.13.15' = @(
        'cmd/internal/build_libbox/main.go',
        'protocol/vless/outbound.go',
        'protocol/vless/outbound_test.go',
        'route/conn.go',
        'route/conn_packet_lifecycle_test.go'
    )
    'v1.13.16' = @(
        'cmd/internal/build_libbox/main.go',
        'protocol/vless/outbound.go',
        'protocol/vless/outbound_test.go',
        'route/conn.go',
        'route/conn_packet_lifecycle_test.go'
    )
    'v1.13.18' = @(
        'cmd/internal/build_libbox/main.go',
        'common/dialer/default.go',
        'common/dialer/default_parallel_interface.go',
        'common/dialer/physical_budget.go',
        'common/dialer/physical_budget_android.go',
        'common/dialer/physical_budget_other.go',
        'common/dialer/physical_budget_test.go',
        'protocol/direct/outbound.go',
        'protocol/direct/outbound_physical_budget_test.go',
        'protocol/vless/outbound.go',
        'protocol/vless/outbound_test.go',
        'route/conn.go',
        'route/conn_packet_lifecycle_test.go',
        'transport/wireguard/endpoint.go',
        'transport/wireguard/endpoint_physical_budget_test.go'
    )
    'v1.13.19' = @(
        'cmd/internal/build_libbox/main.go',
        'common/dialer/default.go',
        'common/dialer/default_parallel_interface.go',
        'common/dialer/physical_budget.go',
        'common/dialer/physical_budget_android.go',
        'common/dialer/physical_budget_other.go',
        'common/dialer/physical_budget_test.go',
        'constant/proxy.go',
        'daemon/started_service.go',
        'protocol/direct/outbound.go',
        'protocol/direct/outbound_physical_budget_test.go',
        'protocol/group/kunbox_selector_test.go',
        'protocol/group/selector.go',
        'protocol/group/urltest.go',
        'protocol/vless/outbound.go',
        'protocol/vless/outbound_test.go',
        'route/conn.go',
        'route/conn_packet_lifecycle_test.go',
        'route/kunbox_root_destination_test.go',
        'transport/wireguard/endpoint.go',
        'transport/wireguard/endpoint_physical_budget_test.go'
    )
}
$trustedDependencyPatches = @{
    'v1.13.15' = [pscustomobject]@{
        ModulePath = 'github.com/sagernet/sing-tun'
        Version = 'v0.8.12-0.20260727151122-3a09076491df'
        FileName = 'sing-tun-v0.8.12-0.20260727151122-3a09076491df.patch'
        Hash = '19FC1E4FFAA5773BFBADCE1A33D1AF571E11E44BF59A1D18FF136B486DAE9E97'
        RequiredNativeMarker = 'system TCP connection limit reached: active='
        Files = @(
            'stack_mixed.go',
            'stack_mixed_test.go',
            'stack_system.go',
            'stack_system_accept_test.go',
            'stack_system_nat.go'
        )
    }
    'v1.13.16' = [pscustomobject]@{
        ModulePath = 'github.com/sagernet/sing-tun'
        Version = 'v0.8.12-0.20260727151122-3a09076491df'
        FileName = 'sing-tun-v0.8.12-0.20260727151122-3a09076491df.patch'
        Hash = '19FC1E4FFAA5773BFBADCE1A33D1AF571E11E44BF59A1D18FF136B486DAE9E97'
        RequiredNativeMarker = 'system TCP connection limit reached: active='
        Files = @(
            'stack_mixed.go',
            'stack_mixed_test.go',
            'stack_system.go',
            'stack_system_accept_test.go',
            'stack_system_nat.go'
        )
    }
    'v1.13.18' = [pscustomobject]@{
        ModulePath = 'github.com/sagernet/sing-tun'
        Version = 'v0.8.12-0.20260727151122-3a09076491df'
        FileName = 'sing-tun-v0.8.12-0.20260727151122-3a09076491df.patch'
        Hash = '19FC1E4FFAA5773BFBADCE1A33D1AF571E11E44BF59A1D18FF136B486DAE9E97'
        RequiredNativeMarker = 'system TCP connection limit reached: active='
        Files = @(
            'stack_mixed.go',
            'stack_mixed_test.go',
            'stack_system.go',
            'stack_system_accept_test.go',
            'stack_system_nat.go'
        )
    }
    'v1.13.19' = [pscustomobject]@{
        ModulePath = 'github.com/sagernet/sing-tun'
        Version = 'v0.8.12-0.20260810140523-7c73233bd0fb'
        FileName = 'sing-tun-v0.8.12-0.20260810140523-7c73233bd0fb.patch'
        Hash = '19FC1E4FFAA5773BFBADCE1A33D1AF571E11E44BF59A1D18FF136B486DAE9E97'
        RequiredNativeMarker = 'system TCP connection limit reached: active='
        Files = @(
            'stack_mixed.go',
            'stack_mixed_test.go',
            'stack_system.go',
            'stack_system_accept_test.go',
            'stack_system_nat.go'
        )
    }
}
$gomobileVersion = 'v0.1.12'
$backupKeepCount = 3
$requiredAndroidAbis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
$requiredMethods = @()
$forbiddenMethods = @(
    'checkNetworkRecoveryNeeded',
    'closeAllTrackedConnections',
    'closeIdleConnections',
    'getConnectionCount',
    'recoverNetworkAuto',
    'resetAllConnections'
)
$forbiddenNativeMarkers = @(
    [pscustomobject]@{
        Category = 'private VLESS Encryption implementation'
        Pattern = 'github.com/sagernet/sing-box/protocol/vless/encryption'
    },
    [pscustomobject]@{
        Category = 'private VLESS Encryption implementation'
        Pattern = 'parseVLESSClientEncryption'
    },
    [pscustomobject]@{
        Category = 'Tailscale implementation'
        Pattern = 'controlplane.tailscale.com'
    },
    [pscustomobject]@{
        Category = 'Tailscale implementation'
        Pattern = 'Tailscale outbound'
    }
)
$forbiddenNativeImplementationMethods = @(
    'CheckNetworkRecoveryNeeded',
    'CloseAllTrackedConnections',
    'CloseIdleConnections',
    'GetConnectionCount',
    'RecoverNetworkAuto',
    'ResetAllConnections'
)
$forbiddenNativeMarkers += @($forbiddenNativeImplementationMethods | ForEach-Object {
    [pscustomobject]@{ Category = 'private connection recovery implementation'; Pattern = "experimental/libbox.$_" }
})

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$patchesDir = Join-Path $scriptDir 'patches'
$targetAar = Join-Path $repoRoot 'app\libs\libbox.aar'
$gradleWrapper = Join-Path $repoRoot 'gradlew.bat'
$backupDir = $scriptDir
$timestamp = Get-Date -Format 'yyyyMMdd.HHmmss'
$backupAar = Join-Path $backupDir ("libbox.aar.backup-before-replace.$timestamp")
$tempDir = Join-Path $scriptDir 'tmp-sync-kernel-current'
$upstreamDir = Join-Path $tempDir 'upstream-sing-box'
$aarCheckDir = Join-Path $tempDir 'aar-check'
$resolvedTag = $null
$patchFile = $null
$dependencyPatch = $null
$syncSucceeded = $false
$aarReplaced = $false
$syncMutex = $null
$syncMutexAcquired = $false

Add-Type -AssemblyName System.IO.Compression.FileSystem

if ($null -eq ('KunBoxChunkedAsciiScanner' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

public static class KunBoxChunkedAsciiScanner
{
    private sealed class PatternBytes
    {
        public readonly string Text;
        public readonly byte[] Bytes;

        public PatternBytes(string text, byte[] bytes)
        {
            Text = text;
            Bytes = bytes;
        }
    }

    public static Dictionary<string, long> Find(Stream stream, string[] patterns, int bufferSize)
    {
        if (stream == null) throw new ArgumentNullException("stream");
        if (patterns == null) throw new ArgumentNullException("patterns");
        if (bufferSize <= 0) throw new ArgumentOutOfRangeException("bufferSize");

        var buckets = new List<PatternBytes>[256];
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var result = new Dictionary<string, long>(StringComparer.Ordinal);
        var maxPatternLength = 0;

        foreach (var pattern in patterns)
        {
            if (String.IsNullOrEmpty(pattern)) throw new ArgumentException("ASCII patterns must not be empty.", "patterns");
            if (!seen.Add(pattern)) throw new ArgumentException("ASCII patterns must be unique: " + pattern, "patterns");
            foreach (var value in pattern)
            {
                if (value > 0x7f) throw new ArgumentException("Pattern is not ASCII: " + pattern, "patterns");
            }

            var bytes = Encoding.ASCII.GetBytes(pattern);
            var item = new PatternBytes(pattern, bytes);
            var bucket = buckets[bytes[0]];
            if (bucket == null)
            {
                bucket = new List<PatternBytes>();
                buckets[bytes[0]] = bucket;
            }
            bucket.Add(item);
            maxPatternLength = Math.Max(maxPatternLength, bytes.Length);
        }

        if (maxPatternLength == 0) return result;

        var overlapSize = maxPatternLength - 1;
        var buffer = new byte[checked(bufferSize + overlapSize)];
        var carry = 0;
        long consumed = 0;

        while (true)
        {
            var read = stream.Read(buffer, carry, bufferSize);
            if (read == 0) break;

            var count = carry + read;
            var bufferOffset = consumed - carry;
            for (var index = 0; index < count; index++)
            {
                var candidates = buckets[buffer[index]];
                if (candidates == null) continue;

                foreach (var candidate in candidates)
                {
                    if (result.ContainsKey(candidate.Text) || index + candidate.Bytes.Length > count) continue;

                    var matches = true;
                    for (var patternIndex = 1; patternIndex < candidate.Bytes.Length; patternIndex++)
                    {
                        if (buffer[index + patternIndex] == candidate.Bytes[patternIndex]) continue;
                        matches = false;
                        break;
                    }
                    if (matches) result.Add(candidate.Text, bufferOffset + index);
                }
            }

            consumed += read;
            carry = Math.Min(overlapSize, count);
            if (carry > 0) Buffer.BlockCopy(buffer, count - carry, buffer, 0, carry);
        }

        return result;
    }
}
'@
}

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

    $resolvedScriptDir = [System.IO.Path]::GetFullPath($scriptDir).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $resolvedPath = [System.IO.Path]::GetFullPath($Path)
    $scriptPrefix = $resolvedScriptDir + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedPath.StartsWith($scriptPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        Fail "Refuse to remove path outside kernel workspace: $resolvedPath"
    }

    if (Test-Path -LiteralPath $resolvedPath) {
        Remove-Item -LiteralPath $resolvedPath -Recurse -Force
    }
}

function Remove-WorkspaceGarbage {
    Remove-PathIfExists -Path $tempDir
}

function Copy-FileWithRetry {
    param(
        [Parameter(Mandatory = $true)] [string] $Source,
        [Parameter(Mandatory = $true)] [string] $Destination,
        [Parameter()] [int] $Attempts = 10
    )

    $lastError = $null
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            Copy-Item -LiteralPath $Source -Destination $Destination -Force
            return
        }
        catch {
            $lastError = $_.Exception
            if ($attempt -lt $Attempts) {
                Start-Sleep -Milliseconds 300
            }
        }
    }
    throw $lastError
}

function Trim-OldAarBackups {
    $backups = Get-ChildItem -Path $backupDir -File -Filter 'libbox.aar.backup-before-replace.*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending

    if (@($backups).Count -le $backupKeepCount) {
        return
    }

    $backups |
        Select-Object -Skip $backupKeepCount |
        ForEach-Object {
            Remove-PathIfExists -Path $_.FullName
        }
}

function Assert-UpstreamTreeIsClean([string] $gitBinary) {
    $status = (Get-ExternalOutput -FilePath $gitBinary -Arguments @('status', '--porcelain', '--untracked-files=all') -WorkingDirectory $upstreamDir -FailureMessage 'Failed to inspect upstream worktree').Trim()
    if (-not [string]::IsNullOrWhiteSpace($status)) {
        Fail "Fresh official sing-box clone is unexpectedly dirty:`n$status`nRefuse to continue."
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

    if (-not (Test-Path $candidate)) {
        Fail "Exact KunBox patch for $targetTag not found: $candidate. Refuse to apply a patch from another sing-box release."
    }

    Write-Host "Using exact patch for ${targetTag}: $candidate"
    return $candidate
}

function Resolve-DependencyPatch([string] $targetTag) {
    $policy = $trustedDependencyPatches[$targetTag]
    if ($null -eq $policy) {
        return $null
    }

    $candidate = Join-Path $patchesDir $policy.FileName
    if (-not (Test-Path -LiteralPath $candidate)) {
        Fail "Dependency patch for $targetTag not found: $candidate"
    }

    $actualHash = (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash
    if ($actualHash -cne $policy.Hash) {
        Fail "Dependency patch SHA256 mismatch for ${targetTag}: $actualHash, expected $($policy.Hash)"
    }

    $changedFiles = @()
    foreach ($line in Get-Content -LiteralPath $candidate -Encoding UTF8) {
        if ($line -match '^diff --git a/(.+) b/(.+)$') {
            if ($matches[1] -cne $matches[2]) {
                Fail "Dependency patch renames files, which is not allowed: $($matches[1]) -> $($matches[2])"
            }
            $changedFiles += $matches[2]
        }
    }
    $actualFiles = @($changedFiles | Sort-Object -Unique)
    $fileDiff = @(Compare-Object -ReferenceObject @($policy.Files) -DifferenceObject $actualFiles -CaseSensitive)
    if ($fileDiff.Count -gt 0) {
        Fail "Dependency patch file set mismatch for ${targetTag}. Expected: $($policy.Files -join ', '); actual: $($actualFiles -join ', ')"
    }

    Write-Host "Dependency patch policy: $($policy.ModulePath)@$($policy.Version), SHA256 and file set verified."
    return [pscustomobject]@{
        Path = $candidate
        Policy = $policy
    }
}

function Assert-MinimalLibboxPatch([string] $PatchPath, [string] $TargetTag) {
    $expectedHash = $trustedPatchHashes[$TargetTag]
    $expectedFiles = @($trustedPatchFiles[$TargetTag])
    if ([string]::IsNullOrWhiteSpace($expectedHash) -or $expectedFiles.Count -eq 0) {
        Fail "No trusted KunBox patch policy is pinned for $TargetTag"
    }

    $actualHash = (Get-FileHash -LiteralPath $PatchPath -Algorithm SHA256).Hash
    if ($actualHash -cne $expectedHash) {
        Fail "KunBox patch SHA256 mismatch for ${TargetTag}: $actualHash, expected $expectedHash"
    }

    $currentPath = $null
    $changedFiles = @()
    $exportedDeclarations = @()
    $removedTailscaleBuildTag = $false
    foreach ($line in Get-Content -Path $PatchPath -Encoding UTF8) {
        if ($line -match '^diff --git a/(.+) b/(.+)$') {
            if ($matches[1] -cne $matches[2]) {
                Fail "KunBox patch renames files, which is not allowed: $($matches[1]) -> $($matches[2])"
            }
            $currentPath = $matches[2]
            $changedFiles += $currentPath
            continue
        }
        if ($currentPath -eq 'cmd/internal/build_libbox/main.go') {
            if ($line -cmatch '^\+.*(with_tailscale|ts_omit_)') {
                Fail 'KunBox patch must not add Tailscale or ts_omit build tags.'
            }
            if ($line -cmatch '^-\s*sharedTags = append\(sharedTags, "with_tailscale"') {
                $removedTailscaleBuildTag = $true
            }
        }
        if ($currentPath -notlike 'experimental/libbox/*') {
            continue
        }
        if ($line -cmatch '^\+(func|type|var|const)\s+([A-Z][A-Za-z0-9_]*)') {
            $exportedDeclarations += "$($matches[1]) $($matches[2])"
        } elseif ($line -cmatch '^\+func\s+\([^)]*\)\s+([A-Z][A-Za-z0-9_]*)') {
            $exportedDeclarations += "method $($matches[1])"
        }
    }

    $actualFiles = @($changedFiles | Sort-Object -Unique)
    $fileDiff = @(Compare-Object -ReferenceObject $expectedFiles -DifferenceObject $actualFiles -CaseSensitive)
    if ($fileDiff.Count -gt 0) {
        Fail "KunBox patch file set mismatch for ${TargetTag}. Expected: $($expectedFiles -join ', '); actual: $($actualFiles -join ', ')"
    }
    if (-not $removedTailscaleBuildTag) {
        Fail 'KunBox patch must remove the official sharedTags append that enables with_tailscale.'
    }

    if (@($exportedDeclarations).Count -gt 0) {
        Fail "KunBox patch must not add exported libbox declarations: $($exportedDeclarations -join ', ')"
    }

    Write-Host "Patch policy: SHA256 and allowed file set verified for $TargetTag."
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

function Resolve-GoPath {
    $command = Get-Command 'go' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $command) {
        return $command.Source
    }
    $bundledGo = Get-ChildItem -LiteralPath (Join-Path $scriptDir 'tools') -Directory -Filter 'go*' `
        -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'go\bin\go.exe' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if ($null -eq $bundledGo) {
        Fail 'go not found. Install Go 1.24+ or provide a bundled toolchain under .kernel-sync-local/tools.'
    }
    return $bundledGo
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
    $preferredVersion = [version] '29.0.14206865'

    function Assert-NdkVersion([string] $path) {
        $resolvedPath = (Resolve-Path $path).Path
        $sourceProperties = Join-Path $resolvedPath 'source.properties'
        $revision = if (Test-Path $sourceProperties) {
            $line = Get-Content -Path $sourceProperties -Encoding UTF8 |
                Where-Object { $_ -match '^Pkg\.Revision\s*=\s*(.+)$' } |
                Select-Object -First 1
            if ($line -and $line -match '^Pkg\.Revision\s*=\s*(.+)$') { $matches[1].Trim() } else { $null }
        } else {
            Split-Path -Leaf $resolvedPath
        }

        $parsedVersion = $null
        if (-not [version]::TryParse($revision, [ref] $parsedVersion) -or $parsedVersion -lt $preferredVersion) {
            Fail "Android NDK $preferredVersion or newer is required. Found '$revision' at $resolvedPath"
        }
        return $resolvedPath
    }

    if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) {
        return Assert-NdkVersion -path $env:ANDROID_NDK_HOME
    }

    $ndkRoot = Join-Path $sdkPath 'ndk'
    if (-not (Test-Path $ndkRoot)) {
        Fail "Android SDK is missing the ndk directory: $ndkRoot"
    }

    $preferredPath = Join-Path $ndkRoot $preferredVersion.ToString()
    if (Test-Path $preferredPath) {
        return Assert-NdkVersion -path $preferredPath
    }

    $fallback = Get-ChildItem -Path $ndkRoot -Directory |
        ForEach-Object {
            $version = $null
            if ([version]::TryParse($_.Name, [ref] $version) -and $version -ge $preferredVersion) {
                [pscustomobject]@{ Directory = $_; Version = $version }
            }
        } |
        Sort-Object Version -Descending |
        Select-Object -First 1
    if ($null -eq $fallback) {
        Fail "No Android NDK found. Install $preferredVersion or newer."
    }

    Write-Warning "Preferred NDK $preferredVersion not found. Falling back to $($fallback.Version)."
    return Assert-NdkVersion -path $fallback.Directory.FullName
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

    $tools = @(
        [pscustomobject]@{
            Name = 'gomobile'
            Path = $gomobilePath
            Package = "github.com/sagernet/gomobile/cmd/gomobile@$gomobileVersion"
        },
        [pscustomobject]@{
            Name = 'gobind'
            Path = $gobindPath
            Package = "github.com/sagernet/gomobile/cmd/gobind@$gomobileVersion"
        }
    )
    $expectedModulePattern = '(?m)^\s*mod\s+github\.com/sagernet/gomobile\s+' +
        [regex]::Escape($gomobileVersion) + '(\s|$)'

    foreach ($tool in $tools) {
        $install = -not (Test-Path $tool.Path)
        if (-not $install) {
            $versionInfo = Get-ExternalOutput -FilePath $goBinary -Arguments @('version', '-m', $tool.Path) -WorkingDirectory $repoRoot -FailureMessage "Failed to inspect $($tool.Name) build metadata"
            $install = $versionInfo -notmatch $expectedModulePattern
            if ($install) {
                Write-Warning "$($tool.Name) is not built from github.com/sagernet/gomobile $gomobileVersion; reinstalling pinned version."
            }
        }
        if ($install) {
            Invoke-External -FilePath $goBinary -Arguments @('install', $tool.Package) -WorkingDirectory $repoRoot -FailureMessage "Failed to install $($tool.Name)"
        }
    }

    if (-not (Test-Path $gomobilePath) -or -not (Test-Path $gobindPath)) {
        Fail 'gomobile or gobind is still missing after install. Check GOPATH/bin and Go permissions.'
    }

    foreach ($tool in $tools) {
        $versionInfo = Get-ExternalOutput -FilePath $goBinary -Arguments @('version', '-m', $tool.Path) -WorkingDirectory $repoRoot -FailureMessage "Failed to verify $($tool.Name) build metadata"
        if ($versionInfo -notmatch $expectedModulePattern) {
            Fail "$($tool.Name) is not pinned to github.com/sagernet/gomobile $gomobileVersion"
        }
    }

}

function Resolve-UpstreamCloneSource([string] $gitBinary) {
    if ([string]::IsNullOrWhiteSpace($SourceRepository)) {
        return [pscustomobject]@{ Path = $officialRemote; IsLocal = $false }
    }

    $sourceCandidate = if ([System.IO.Path]::IsPathRooted($SourceRepository)) {
        $SourceRepository
    } else {
        Join-Path $repoRoot $SourceRepository
    }
    $sourcePath = (Resolve-Path -LiteralPath $sourceCandidate).Path
    if (-not (Test-Path (Join-Path $sourcePath '.git'))) {
        Fail "Local upstream source is not a git worktree: $sourcePath"
    }

    $remoteUrl = (Get-ExternalOutput -FilePath $gitBinary -Arguments @(
            'remote', 'get-url', 'origin'
        ) -WorkingDirectory $sourcePath -FailureMessage 'Failed to read local upstream origin').Trim()
    if ($remoteUrl -notmatch 'SagerNet/sing-box(\.git)?$') {
        Fail "Local upstream source origin is not the official repository: $remoteUrl"
    }

    $expectedCommit = $trustedTagCommits[$resolvedTag]
    if ([string]::IsNullOrWhiteSpace($expectedCommit)) {
        Fail "Local upstream source is not allowed for unpinned tag: $resolvedTag"
    }
    $actualCommit = (Get-ExternalOutput -FilePath $gitBinary -Arguments @(
            'rev-list', '-n', '1', "refs/tags/$resolvedTag"
        ) -WorkingDirectory $sourcePath -FailureMessage "Local upstream source does not contain tag $resolvedTag").Trim()
    if ($actualCommit -cne $expectedCommit) {
        Fail "Local upstream tag $resolvedTag points to $actualCommit, expected $expectedCommit"
    }

    Write-Host "Using pinned local official git source: $sourcePath ($resolvedTag@$actualCommit)"
    return [pscustomobject]@{ Path = $sourcePath; IsLocal = $true }
}

function Prepare-UpstreamTree([string] $gitBinary) {
    Write-Stage 'Stage 1/8: Prepare official upstream'

    Remove-PathIfExists -Path $upstreamDir
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
    $cloneSource = Resolve-UpstreamCloneSource -gitBinary $gitBinary
    $cloneArguments = @('clone', '--branch', $resolvedTag, '--single-branch')
    if (-not $cloneSource.IsLocal) {
        $cloneArguments += @('--depth', '1')
    }
    $cloneArguments += @($cloneSource.Path, $upstreamDir)
    Invoke-External -FilePath $gitBinary -Arguments $cloneArguments -WorkingDirectory $tempDir -FailureMessage 'Failed to clone official sing-box'

    if ($cloneSource.IsLocal) {
        Invoke-External -FilePath $gitBinary -Arguments @(
            'remote', 'set-url', 'origin', $officialRemote
        ) -WorkingDirectory $upstreamDir -FailureMessage 'Failed to normalize cloned upstream origin'
    }

    if (-not (Test-Path (Join-Path $upstreamDir '.git'))) {
        Fail "$upstreamDir exists but is not a git worktree."
    }

    $remoteUrl = (Get-ExternalOutput -FilePath $gitBinary -Arguments @('remote', 'get-url', 'origin') -WorkingDirectory $upstreamDir -FailureMessage 'Failed to read upstream origin').Trim()
    if ($remoteUrl -notmatch 'SagerNet/sing-box(\.git)?$') {
        Fail "upstream-sing-box origin is not the official repository: $remoteUrl"
    }

    $checkedOutTag = (Get-ExternalOutput -FilePath $gitBinary -Arguments @('describe', '--tags', '--exact-match', 'HEAD') -WorkingDirectory $upstreamDir -FailureMessage 'Failed to verify checked out sing-box tag').Trim()
    if ($checkedOutTag -ne $resolvedTag) {
        Fail "Expected official tag $resolvedTag but cloned $checkedOutTag"
    }

    $expectedCommit = $trustedTagCommits[$resolvedTag]
    if ([string]::IsNullOrWhiteSpace($expectedCommit)) {
        Fail "Official tag is not pinned to a trusted commit: $resolvedTag"
    }
    $checkedOutCommit = (Get-ExternalOutput -FilePath $gitBinary -Arguments @('rev-parse', 'HEAD') -WorkingDirectory $upstreamDir -FailureMessage 'Failed to read checked out sing-box commit').Trim()
    $checkedOutTagCommit = (Get-ExternalOutput -FilePath $gitBinary -Arguments @('rev-list', '-n', '1', "refs/tags/$resolvedTag") -WorkingDirectory $upstreamDir -FailureMessage "Failed to resolve cloned tag $resolvedTag").Trim()
    if ($checkedOutCommit -cne $expectedCommit -or $checkedOutTagCommit -cne $expectedCommit) {
        Fail "Official tag/HEAD commit mismatch for ${resolvedTag}: HEAD=$checkedOutCommit, tag=$checkedOutTagCommit, expected=$expectedCommit"
    }
    Write-Host "Upstream commit policy: $resolvedTag@$expectedCommit verified."

    Assert-UpstreamTreeIsClean -gitBinary $gitBinary
}

function Apply-KunBoxPatch([string] $gitBinary) {
    Write-Stage 'Stage 3/8: Apply KunBox patches'

    Write-Host "Using patch: $patchFile"
    $patchName = [System.IO.Path]::GetFileName($patchFile)
    Invoke-External -FilePath $gitBinary -Arguments @('apply', '--check', $patchFile) -WorkingDirectory $upstreamDir -FailureMessage "KunBox patch $patchName does not apply cleanly to official $resolvedTag. Fix the coupling first."
    Invoke-External -FilePath $gitBinary -Arguments @('apply', '--whitespace=nowarn', $patchFile) -WorkingDirectory $upstreamDir -FailureMessage 'Failed to apply KunBox patch'
}

function Get-LiteralOccurrenceCount([string] $Text, [string] $Needle) {
    if ([string]::IsNullOrEmpty($Needle)) {
        return 0
    }
    $count = 0
    $offset = 0
    while ($true) {
        $index = $Text.IndexOf($Needle, $offset, [System.StringComparison]::Ordinal)
        if ($index -lt 0) {
            return $count
        }
        $count++
        $offset = $index + $Needle.Length
    }
}

function Assert-LiteralOccurrence(
    [string] $RelativePath,
    [string] $Needle,
    [int] $ExpectedCount
) {
    $path = Join-Path $upstreamDir $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Fail "Physical dial audit file is missing: $RelativePath"
    }
    $text = [System.IO.File]::ReadAllText($path)
    $actualCount = Get-LiteralOccurrenceCount -Text $text -Needle $Needle
    if ($actualCount -ne $ExpectedCount) {
        Fail "Physical dial audit mismatch in ${RelativePath}: '$Needle' expected=$ExpectedCount actual=$actualCount"
    }
}

function Assert-NoUnbudgetedPhysicalDialSites {
    if (-not $requiredKunBoxNativeMarkers.ContainsKey($resolvedTag)) {
        return
    }
    Assert-LiteralOccurrence 'common/dialer/default.go' 'dialPhysicalConn(ctx' 1
    Assert-LiteralOccurrence 'common/dialer/default.go' 'listenPhysicalPacket(ctx' 1
    Assert-LiteralOccurrence 'common/dialer/default_parallel_interface.go' 'dialPhysicalConn(ctx' 2
    Assert-LiteralOccurrence 'common/dialer/default_parallel_interface.go' 'listenPhysicalPacket(ctx' 2
    Assert-LiteralOccurrence 'protocol/direct/outbound.go' 'dialer.AcquirePhysicalDial(ctx)' 1
    Assert-LiteralOccurrence 'protocol/direct/outbound.go' 'ping.ConnectDestination(' 1
    Assert-LiteralOccurrence 'transport/wireguard/endpoint.go' `
        'newBudgetedWireGuardBind(conn.NewStdNetBind(wgListener.WireGuardControl()))' 1
    Assert-LiteralOccurrence 'common/dialer/physical_budget.go' 'kunbox_physical_dial_gate_v1' 1
    Assert-LiteralOccurrence 'transport/wireguard/endpoint.go' 'kunbox_wireguard_physical_gate_v1' 1
    Assert-LiteralOccurrence 'route/conn.go' 'kunbox_physical_budget_v1 ' 1
    Assert-LiteralOccurrence 'common/dialer/physical_budget_android.go' '//go:build android' 1
    Assert-LiteralOccurrence 'common/dialer/physical_budget_other.go' '//go:build !android' 1

    $directPath = Join-Path $upstreamDir 'protocol/direct/outbound.go'
    $directText = [System.IO.File]::ReadAllText($directPath)
    $helperText = $directText.Substring(
        $directText.IndexOf('func acquireBudgetedDirectRouteDestination(', [System.StringComparison]::Ordinal)
    )
    $acquireIndex = $helperText.IndexOf('dialer.AcquirePhysicalDial(ctx)', [System.StringComparison]::Ordinal)
    $connectIndex = $helperText.IndexOf('connect(dialContext)', [System.StringComparison]::Ordinal)
    if ($acquireIndex -lt 0 -or $connectIndex -lt 0 -or $acquireIndex -gt $connectIndex) {
        Fail 'ICMP physical lease must be acquired before ping.ConnectDestination creates its socket.'
    }
    Write-Host 'Physical dial audit: exact call sites, counts, ordering, build tags and markers verified.'
}

function Apply-DependencyPatch([string] $goBinary, [string] $gitBinary) {
    if ($null -eq $dependencyPatch) {
        return
    }

    $policy = $dependencyPatch.Policy
    $moduleInfo = (Get-ExternalOutput -FilePath $goBinary -Arguments @(
            'list', '-m', '-f', '{{.Version}}|{{.Dir}}', $policy.ModulePath
        ) -WorkingDirectory $upstreamDir -FailureMessage "Failed to resolve dependency $($policy.ModulePath)").Trim()
    $separatorIndex = $moduleInfo.IndexOf('|')
    if ($separatorIndex -le 0) {
        Fail "Unexpected dependency metadata for $($policy.ModulePath): $moduleInfo"
    }
    $actualVersion = $moduleInfo.Substring(0, $separatorIndex)
    $sourceDir = $moduleInfo.Substring($separatorIndex + 1)
    if ($actualVersion -cne $policy.Version) {
        Fail "Dependency version mismatch for $($policy.ModulePath): $actualVersion, expected $($policy.Version)"
    }
    if (-not (Test-Path -LiteralPath $sourceDir)) {
        Fail "Dependency source directory not found: $sourceDir"
    }

    $patchedDir = Join-Path $tempDir 'patched-sing-tun'
    Remove-PathIfExists -Path $patchedDir
    Copy-Item -LiteralPath $sourceDir -Destination $patchedDir -Recurse
    Get-ChildItem -LiteralPath $patchedDir -File -Recurse -Force | ForEach-Object {
        $_.IsReadOnly = $false
    }

    $patchName = [System.IO.Path]::GetFileName($dependencyPatch.Path)
    Invoke-External -FilePath $gitBinary -Arguments @('init', '--quiet') -WorkingDirectory $patchedDir -FailureMessage 'Failed to isolate patched dependency worktree'
    Invoke-External -FilePath $gitBinary -Arguments @(
        'apply', '--check', '--whitespace=error-all', $dependencyPatch.Path
    ) -WorkingDirectory $patchedDir -FailureMessage "Dependency patch $patchName does not apply cleanly"
    Invoke-External -FilePath $gitBinary -Arguments @(
        'apply', '--whitespace=error-all', $dependencyPatch.Path
    ) -WorkingDirectory $patchedDir -FailureMessage "Failed to apply dependency patch $patchName"
    Invoke-External -FilePath $gitBinary -Arguments @(
        'apply', '--reverse', '--check', '--whitespace=error-all', $dependencyPatch.Path
    ) -WorkingDirectory $patchedDir -FailureMessage "Dependency patch $patchName was not applied"
    foreach ($relativePath in $policy.Files) {
        if (-not (Test-Path -LiteralPath (Join-Path $patchedDir $relativePath) -PathType Leaf)) {
            Fail "Dependency patch output is missing: $relativePath"
        }
    }
    Invoke-External -FilePath $goBinary -Arguments @(
        'mod', 'edit', "-replace=$($policy.ModulePath)=$patchedDir"
    ) -WorkingDirectory $upstreamDir -FailureMessage "Failed to activate patched dependency $($policy.ModulePath)"

    $resolvedDir = (Get-ExternalOutput -FilePath $goBinary -Arguments @(
            'list', '-m', '-f', '{{.Dir}}', $policy.ModulePath
        ) -WorkingDirectory $upstreamDir -FailureMessage "Failed to verify patched dependency $($policy.ModulePath)").Trim()
    if (-not [System.IO.Path]::GetFullPath($resolvedDir).Equals(
            [System.IO.Path]::GetFullPath($patchedDir),
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        Fail "Patched dependency replacement is inactive: $resolvedDir"
    }
    Write-Host "Patched dependency activated: $($policy.ModulePath)@$actualVersion"
}

function Assert-GoTestsDiscovered {
    param(
        [Parameter(Mandatory = $true)] [string] $goBinary,
        [Parameter(Mandatory = $true)] [string] $WorkingDirectory,
        [Parameter()] [string[]] $BuildTags = @()
    )

    $testArguments = @('test')
    if ($BuildTags.Count -gt 0) {
        $testArguments += @('-tags', ($BuildTags -join ','))
    }
    $testArguments += @('-list', '^Test', '.')
    $testOutput = Get-ExternalOutput -FilePath $goBinary -Arguments $testArguments `
        -WorkingDirectory $WorkingDirectory -FailureMessage 'Failed to discover patched sing-tun tests'

    $testCount = 0
    $reader = [System.IO.StringReader]::new($testOutput)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            if ($line.StartsWith('Test', [System.StringComparison]::Ordinal)) {
                $testCount++
            }
        }
    }
    finally {
        $reader.Dispose()
    }

    $tagLabel = if ($BuildTags.Count -gt 0) { $BuildTags -join ',' } else { 'default' }
    if ($testCount -lt 1) {
        Fail "Patched sing-tun discovered no tests for build tags '$tagLabel'."
    }
    Write-Host "Patched sing-tun test discovery ($tagLabel): $testCount test(s)."
}

function Run-GoValidation([string] $goBinary) {
    Write-Stage 'Stage 4/8: Validate patched Go packages'

    $env:GOTOOLCHAIN = 'local'
    $packages = @(
        './common/dialer',
        './constant',
        './daemon',
        './protocol/direct',
        './protocol/group',
        './transport/wireguard',
        './protocol/vless',
        './route'
    )
    Invoke-External -FilePath $goBinary -Arguments (@('test') + $packages) -WorkingDirectory $upstreamDir -FailureMessage 'Patched Go package tests failed'
    Invoke-External -FilePath $goBinary -Arguments (@('test', '-race') + $packages) -WorkingDirectory $upstreamDir -FailureMessage 'Patched Go race tests failed'
    Invoke-External -FilePath $goBinary -Arguments (@('vet') + $packages) -WorkingDirectory $upstreamDir -FailureMessage 'Patched Go vet failed'

    if ($null -ne $dependencyPatch) {
        $patchedDependencyDir = Join-Path $tempDir 'patched-sing-tun'
        if (-not (Test-Path -LiteralPath $patchedDependencyDir -PathType Container)) {
            Fail "Patched dependency directory not found: $patchedDependencyDir"
        }
        Assert-GoTestsDiscovered -goBinary $goBinary -WorkingDirectory $patchedDependencyDir
        Assert-GoTestsDiscovered -goBinary $goBinary -WorkingDirectory $patchedDependencyDir `
            -BuildTags @('with_gvisor')
        Invoke-External -FilePath $goBinary -Arguments @('test', '.') -WorkingDirectory $patchedDependencyDir -FailureMessage 'Patched sing-tun tests failed'
        Invoke-External -FilePath $goBinary -Arguments @('test', '-race', '.') -WorkingDirectory $patchedDependencyDir -FailureMessage 'Patched sing-tun race tests failed'
        Invoke-External -FilePath $goBinary -Arguments @('vet', '.') -WorkingDirectory $patchedDependencyDir -FailureMessage 'Patched sing-tun vet failed'
        Invoke-External -FilePath $goBinary -Arguments @('test', '-tags', 'with_gvisor', '.') -WorkingDirectory $patchedDependencyDir -FailureMessage 'Patched sing-tun gVisor tests failed'
        Invoke-External -FilePath $goBinary -Arguments @('test', '-race', '-tags', 'with_gvisor', '.') -WorkingDirectory $patchedDependencyDir -FailureMessage 'Patched sing-tun gVisor race tests failed'
        Invoke-External -FilePath $goBinary -Arguments @('vet', '-tags', 'with_gvisor', '.') -WorkingDirectory $patchedDependencyDir -FailureMessage 'Patched sing-tun gVisor vet failed'
    }
}

function Remove-LibboxBuildArtifacts {
    foreach ($relativePath in @(
        'libbox.aar',
        'libbox-sources.jar',
        'libbox-legacy.aar',
        'libbox-legacy-sources.jar'
    )) {
        Remove-PathIfExists -Path (Join-Path $upstreamDir $relativePath)
    }
}

function Build-LibboxSnapshot {
    param(
        [Parameter(Mandatory = $true)] [string] $goBinary,
        [Parameter(Mandatory = $true)] [string] $OutputPath,
        [Parameter(Mandatory = $true)] [string] $StageName
    )

    Write-Stage $StageName
    $env:GOTOOLCHAIN = 'local'
    Remove-LibboxBuildArtifacts
    Invoke-External -FilePath $goBinary -Arguments @('run', './cmd/internal/build_libbox', '-target', 'android') -WorkingDirectory $upstreamDir -FailureMessage 'Failed to build libbox.aar'

    $builtAar = Join-Path $upstreamDir 'libbox.aar'
    if (-not (Test-Path $builtAar)) {
        Fail "Build completed but $builtAar was not created."
    }

    Remove-PathIfExists -Path $OutputPath
    Copy-Item -LiteralPath $builtAar -Destination $OutputPath -Force
    Remove-LibboxBuildArtifacts
    return $OutputPath
}

function Get-AarJniEntries([string] $AarPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($AarPath)
    try {
        $entries = @($zip.Entries | Where-Object {
                -not $_.FullName.EndsWith('/') -and $_.FullName -match '^jni/[^/]+/[^/]+$'
            })
        if ($entries.Count -eq 0) {
            Fail "AAR contains no JNI entries: $AarPath"
        }
        $emptyEntries = @($entries | Where-Object { $_.Length -le 0 } | ForEach-Object { $_.FullName })
        if ($emptyEntries.Count -gt 0) {
            Fail "AAR contains empty JNI entries: $($emptyEntries -join ', ')"
        }
        return @($entries | ForEach-Object { $_.FullName } | Sort-Object -Unique)
    }
    finally {
        $zip.Dispose()
    }
}

function Invoke-BinaryPatternScannerSelfTest {
    $bufferSize = 8
    $expectedPattern = 'cross-block-hit'
    $missingPattern = 'missing-marker'
    $prefix = '1234567'
    $payload = [System.Text.Encoding]::ASCII.GetBytes($prefix + $expectedPattern + '-tail')
    $stream = New-Object System.IO.MemoryStream(, $payload)
    try {
        $hits = [KunBoxChunkedAsciiScanner]::Find(
            $stream,
            [string[]] @($expectedPattern, $missingPattern),
            $bufferSize
        )
    }
    finally {
        $stream.Dispose()
    }

    if (-not $hits.ContainsKey($expectedPattern) -or $hits[$expectedPattern] -ne $prefix.Length) {
        Fail "Binary pattern scanner self-test missed a cross-block pattern at offset $($prefix.Length)."
    }
    if ($hits.ContainsKey($missingPattern)) {
        Fail 'Binary pattern scanner self-test reported a pattern that is not present.'
    }

    $policyPatterns = [string[]] (@(
            $forbiddenNativeMarkers | ForEach-Object { $_.Pattern }
            $trustedDependencyPatches.Values | ForEach-Object { $_.RequiredNativeMarker }
            $requiredKunBoxNativeMarkers.Values | ForEach-Object { @($_) }
        ) | Sort-Object -Unique)
    $benignPolicyText = (@(
            'xhttp',
            'XHTTP',
            'splithttp',
            'SplitHTTP',
            'vless encryption',
            'VLESS Encryption'
        ) + $forbiddenMethods + $forbiddenNativeImplementationMethods) -join '|'
    $benignPolicyStream = New-Object System.IO.MemoryStream(
        , [System.Text.Encoding]::ASCII.GetBytes($benignPolicyText)
    )
    try {
        $benignPolicyHits = [KunBoxChunkedAsciiScanner]::Find(
            $benignPolicyStream,
            $policyPatterns,
            $bufferSize
        )
    }
    finally {
        $benignPolicyStream.Dispose()
    }
    if ($benignPolicyHits.Count -gt 0) {
        Fail "Native binary policy uses an overly broad marker: $($benignPolicyHits.Keys -join ', ')"
    }

    $specificPolicyStream = New-Object System.IO.MemoryStream(
        , [System.Text.Encoding]::ASCII.GetBytes(($policyPatterns -join '|'))
    )
    try {
        $specificPolicyHits = [KunBoxChunkedAsciiScanner]::Find(
            $specificPolicyStream,
            $policyPatterns,
            $bufferSize
        )
    }
    finally {
        $specificPolicyStream.Dispose()
    }
    $missedPolicyPatterns = @($policyPatterns | Where-Object { -not $specificPolicyHits.ContainsKey($_) })
    if ($missedPolicyPatterns.Count -gt 0) {
        Fail "Native binary policy self-test missed specific markers: $($missedPolicyPatterns -join ', ')"
    }

    Write-Host "Binary pattern scanner self-test passed: cross-block and native policy marker checks succeeded."
}

function Assert-AarNativeBinaryPolicy {
    param(
        [Parameter(Mandatory = $true)] [string] $AarPath,
        [Parameter(Mandatory = $true)] [string[]] $JniEntries
    )

    $allAbis = @($JniEntries | ForEach-Object {
            if ($_ -match '^jni/([^/]+)/') { $matches[1] }
        } | Sort-Object -Unique)
    $libboxEntries = @($JniEntries | Where-Object { $_ -match '^jni/[^/]+/libbox\.so$' } | Sort-Object -Unique)
    $libboxAbis = @($libboxEntries | ForEach-Object {
            if ($_ -match '^jni/([^/]+)/libbox\.so$') { $matches[1] }
        } | Sort-Object -Unique)
    $missingAbis = @($allAbis | Where-Object { $_ -notin $libboxAbis })
    if ($libboxEntries.Count -eq 0 -or $missingAbis.Count -gt 0) {
        Fail "Patched AAR is missing libbox.so for JNI ABIs: $($missingAbis -join ', ')"
    }
    $requiredAbiDiff = @(
        Compare-Object -ReferenceObject $requiredAndroidAbis -DifferenceObject $libboxAbis -CaseSensitive
    )
    if ($requiredAbiDiff.Count -gt 0) {
        Fail "Patched AAR libbox ABI set mismatch. Expected: $($requiredAndroidAbis -join ', '); actual: $($libboxAbis -join ', ')"
    }

    $requiredNativeMarker = if ($null -ne $dependencyPatch) {
        [string] $dependencyPatch.Policy.RequiredNativeMarker
    } else {
        $null
    }
    $requiredKunBoxNativeMarkersForTag = [string[]] @($requiredKunBoxNativeMarkers[$resolvedTag])
    if ($null -ne $dependencyPatch -and [string]::IsNullOrWhiteSpace($requiredNativeMarker)) {
        Fail 'Dependency patch policy is missing its required native marker.'
    }
    $patterns = [string[]] @($forbiddenNativeMarkers | ForEach-Object { $_.Pattern })
    if ($null -ne $requiredNativeMarker) {
        $patterns += $requiredNativeMarker
    }
    $patterns += @($requiredKunBoxNativeMarkersForTag | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $violations = New-Object System.Collections.Generic.List[string]
    $zip = [System.IO.Compression.ZipFile]::OpenRead($AarPath)
    try {
        foreach ($entryName in $libboxEntries) {
            $entry = $zip.GetEntry($entryName)
            if ($null -eq $entry -or $entry.Length -le 0) {
                Fail "AAR entry is missing or empty: $entryName"
            }

            $entryStream = $entry.Open()
            try {
                $hits = [KunBoxChunkedAsciiScanner]::Find($entryStream, $patterns, 65536)
            }
            finally {
                $entryStream.Dispose()
            }

            foreach ($marker in $forbiddenNativeMarkers) {
                if (-not $hits.ContainsKey($marker.Pattern)) {
                    continue
                }
                [void] $violations.Add(
                    "$entryName contains forbidden $($marker.Category) marker '$($marker.Pattern)' at offset 0x$('{0:X}' -f $hits[$marker.Pattern])"
                )
            }
            if ($null -ne $requiredNativeMarker -and -not $hits.ContainsKey($requiredNativeMarker)) {
                [void] $violations.Add(
                    "$entryName is missing required dependency marker '$requiredNativeMarker'"
                )
            }
            foreach ($requiredKunBoxNativeMarker in $requiredKunBoxNativeMarkersForTag) {
                if (-not [string]::IsNullOrWhiteSpace($requiredKunBoxNativeMarker) -and
                    -not $hits.ContainsKey($requiredKunBoxNativeMarker)) {
                    [void] $violations.Add(
                        "$entryName is missing required KunBox marker '$requiredKunBoxNativeMarker'"
                    )
                }
            }
        }
    }
    finally {
        $zip.Dispose()
    }

    if ($violations.Count -gt 0) {
        Fail "Patched AAR native binary policy check failed:`n$($violations -join "`n")"
    }

    $dependencyMarkerStatus = if ($null -ne $requiredNativeMarker) {
        ' Required dependency marker was found in every ABI.'
    } else {
        ''
    }
    Write-Host (
        "AAR native binary policy: scanned $($libboxEntries.Count) ABI libbox.so entries; " +
        'no Tailscale, private VLESS Encryption, or connection recovery implementations found. ' +
        "Official XHTTP remains enabled.$dependencyMarkerStatus"
    )
}

function Get-JavaClassEntries([string] $ClassesJar) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ClassesJar)
    try {
        $entries = @($zip.Entries |
                Where-Object { -not $_.FullName.EndsWith('/') -and $_.FullName.EndsWith('.class') } |
                ForEach-Object { $_.FullName } |
                Sort-Object -Unique)
        if ($entries.Count -eq 0) {
            Fail "classes.jar contains no Java classes: $ClassesJar"
        }
        return $entries
    }
    finally {
        $zip.Dispose()
    }
}

function Get-JavaPublicApi {
    param(
        [Parameter(Mandatory = $true)] [string] $ClassesJar,
        [Parameter(Mandatory = $true)] [string[]] $ClassEntries,
        [Parameter(Mandatory = $true)] [string] $javapBinary,
        [Parameter(Mandatory = $true)] [string] $WorkingDirectory
    )

    $classNames = @($ClassEntries |
            Where-Object { $_ -ne 'module-info.class' -and $_ -notlike 'META-INF/versions/*' } |
            ForEach-Object { $_.Substring(0, $_.Length - '.class'.Length).Replace('/', '.') } |
            Sort-Object -Unique)
    if ($classNames.Count -eq 0) {
        Fail "classes.jar contains no javap-compatible classes: $ClassesJar"
    }

    $api = New-Object System.Collections.Generic.List[string]
    foreach ($className in $classNames) {
        $javapOutput = Get-ExternalOutput -FilePath $javapBinary -Arguments @('-classpath', $ClassesJar, '-public', '-s', $className) -WorkingDirectory $WorkingDirectory -FailureMessage "javap failed for $className"
        $pending = $null
        foreach ($rawLine in ($javapOutput -split "`r?`n")) {
            $line = $rawLine.Trim()
            if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('Compiled from ')) {
                continue
            }
            if ($line -eq '}') {
                if ($null -ne $pending) {
                    [void] $api.Add("$className`t$pending")
                    $pending = $null
                }
                continue
            }
            if ($line.StartsWith('descriptor:')) {
                if ($null -eq $pending) {
                    Fail "Unexpected javap descriptor without declaration for ${className}: $line"
                }
                [void] $api.Add("$className`t$pending`t$line")
                $pending = $null
                continue
            }
            if ($null -ne $pending) {
                [void] $api.Add("$className`t$pending")
            }
            $pending = $line
        }
        if ($null -ne $pending) {
            [void] $api.Add("$className`t$pending")
        }
    }
    return @($api | Sort-Object -Unique)
}

function Assert-PatchedLibboxMethods([string] $ClassesJar, [string] $javapBinary) {
    $javapOutput = Get-ExternalOutput -FilePath $javapBinary -Arguments @('-classpath', $ClassesJar, '-public', 'io.nekohasekai.libbox.Libbox') -WorkingDirectory $aarCheckDir -FailureMessage 'javap verification failed for Libbox'

    $missing = @()
    foreach ($method in $requiredMethods) {
        if ($javapOutput -notmatch ([regex]::Escape($method) + '\(')) {
            $missing += $method
        }
    }
    if ($missing.Count -gt 0) {
        Fail "The patched libbox.aar is missing required KunBox methods: $($missing -join ', ')."
    }

    $unexpected = @()
    foreach ($method in $forbiddenMethods) {
        if ($javapOutput -match ([regex]::Escape($method) + '\(')) {
            $unexpected += $method
        }
    }
    if ($unexpected.Count -gt 0) {
        Fail "The patched libbox.aar still exports removed private methods: $($unexpected -join ', ')."
    }
}

function Assert-AarCompatibility {
    param(
        [Parameter(Mandatory = $true)] [string] $OfficialAar,
        [Parameter(Mandatory = $true)] [string] $PatchedAar,
        [Parameter(Mandatory = $true)] [string] $javapBinary
    )

    Write-Stage 'Stage 6/8: Verify AAR API and ABI compatibility'
    Remove-PathIfExists -Path $aarCheckDir
    $officialCheckDir = Join-Path $aarCheckDir 'official'
    $patchedCheckDir = Join-Path $aarCheckDir 'patched'
    New-Item -ItemType Directory -Path $officialCheckDir, $patchedCheckDir -Force | Out-Null

    $officialClassesJar = Join-Path $officialCheckDir 'classes.jar'
    $patchedClassesJar = Join-Path $patchedCheckDir 'classes.jar'
    Extract-ZipEntry -ZipPath $OfficialAar -EntryName 'classes.jar' -DestinationPath $officialClassesJar
    Extract-ZipEntry -ZipPath $PatchedAar -EntryName 'classes.jar' -DestinationPath $patchedClassesJar

    $officialClasses = @(Get-JavaClassEntries -ClassesJar $officialClassesJar)
    $patchedClasses = @(Get-JavaClassEntries -ClassesJar $patchedClassesJar)
    $classDiff = @(Compare-Object -ReferenceObject $officialClasses -DifferenceObject $patchedClasses -CaseSensitive)
    if ($classDiff.Count -gt 0) {
        $removedClasses = @($classDiff | Where-Object { $_.SideIndicator -eq '<=' } | ForEach-Object { $_.InputObject })
        $addedClasses = @($classDiff | Where-Object { $_.SideIndicator -eq '=>' } | ForEach-Object { $_.InputObject })
        Fail "Patched AAR Java class set differs from official AAR. Removed: $($removedClasses -join ', '); added: $($addedClasses -join ', ')"
    }

    $officialApi = @(Get-JavaPublicApi -ClassesJar $officialClassesJar -ClassEntries $officialClasses -javapBinary $javapBinary -WorkingDirectory $officialCheckDir)
    $patchedApi = @(Get-JavaPublicApi -ClassesJar $patchedClassesJar -ClassEntries $patchedClasses -javapBinary $javapBinary -WorkingDirectory $patchedCheckDir)
    $apiDiff = @(Compare-Object -ReferenceObject $officialApi -DifferenceObject $patchedApi -CaseSensitive)
    if ($apiDiff.Count -gt 0) {
        $removedApi = @($apiDiff | Where-Object { $_.SideIndicator -eq '<=' } | ForEach-Object { $_.InputObject })
        $addedApi = @($apiDiff | Where-Object { $_.SideIndicator -eq '=>' } | ForEach-Object { $_.InputObject })
        Fail "Patched AAR public API differs from official AAR. Removed: $($removedApi -join ', '); added: $($addedApi -join ', ')"
    }

    $officialJni = @(Get-AarJniEntries -AarPath $OfficialAar)
    $patchedJni = @(Get-AarJniEntries -AarPath $PatchedAar)
    $jniDiff = @(Compare-Object -ReferenceObject $officialJni -DifferenceObject $patchedJni -CaseSensitive)
    if ($jniDiff.Count -gt 0) {
        $removedJni = @($jniDiff | Where-Object { $_.SideIndicator -eq '<=' } | ForEach-Object { $_.InputObject })
        $addedJni = @($jniDiff | Where-Object { $_.SideIndicator -eq '=>' } | ForEach-Object { $_.InputObject })
        Fail "Patched AAR JNI entry set differs from official AAR. Removed: $($removedJni -join ', '); added: $($addedJni -join ', ')"
    }

    Assert-AarNativeBinaryPolicy -AarPath $PatchedAar -JniEntries $patchedJni
    Assert-PatchedLibboxMethods -ClassesJar $patchedClassesJar -javapBinary $javapBinary
    Write-Host 'AAR public API matches the official baseline.'
    Write-Host "AAR JNI entries: $($patchedJni -join ', ')"
}

function Replace-Aar([string] $aarPath) {
    Write-Stage 'Stage 7/8: Backup and replace libbox.aar'

    if (-not (Test-Path $targetAar)) {
        Fail "Target AAR not found: $targetAar"
    }

    Invoke-External -FilePath $gradleWrapper -Arguments @('--stop') -WorkingDirectory $repoRoot -FailureMessage 'Failed to stop Gradle before replacing libbox.aar'
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
    Copy-FileWithRetry -Source $targetAar -Destination $backupAar
    $script:aarReplaced = $true
    Copy-FileWithRetry -Source $aarPath -Destination $targetAar

    Write-Host "Backup created: $backupAar"
    Write-Host "Target replaced: $targetAar"
}

function Run-GradleValidation {
    Write-Stage 'Stage 8/8: Run Gradle validation'

    if (-not (Test-Path $gradleWrapper)) {
        Fail "Gradle wrapper not found: $gradleWrapper"
    }

    Invoke-External -FilePath $gradleWrapper -Arguments @('assembleDebug') -WorkingDirectory $repoRoot -FailureMessage 'assembleDebug failed. Fix the Kotlin compat layer or kernel export coupling.'
    Invoke-External -FilePath $gradleWrapper -Arguments @('testDebugUnitTest') -WorkingDirectory $repoRoot -FailureMessage 'testDebugUnitTest failed. Fix the Kotlin compat layer or kernel export coupling.'
    Invoke-External -FilePath $gradleWrapper -Arguments @('detekt') -WorkingDirectory $repoRoot -FailureMessage 'detekt failed. Fix the Kotlin compat layer or kernel export coupling.'
    Invoke-External -FilePath $gradleWrapper -Arguments @('assembleDebugAndroidTest') -WorkingDirectory $repoRoot -FailureMessage 'assembleDebugAndroidTest failed.'
    Assert-PhysicalDialInstrumentationDiscovered
}

function Assert-PhysicalDialInstrumentationDiscovered {
    $testApk = Join-Path $repoRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    if (-not (Test-Path -LiteralPath $testApk -PathType Leaf)) {
        Fail "Android instrumentation APK not found: $testApk"
    }
    $buildTools = Get-ChildItem -LiteralPath (Join-Path $androidSdk 'build-tools') -Directory |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $buildTools) {
        Fail 'Android build-tools not found for instrumentation discovery.'
    }
    $dexdump = Join-Path $buildTools.FullName 'dexdump.exe'
    if (-not (Test-Path -LiteralPath $dexdump -PathType Leaf)) {
        Fail "dexdump not found: $dexdump"
    }
    $discoveryDir = Join-Path $tempDir 'instrumentation-discovery'
    Remove-PathIfExists -Path $discoveryDir
    [System.IO.Directory]::CreateDirectory($discoveryDir) | Out-Null
    $foundClass = $false
    $foundTest = $false
    $zip = [System.IO.Compression.ZipFile]::OpenRead($testApk)
    try {
        foreach ($entry in @($zip.Entries | Where-Object { $_.Name -like 'classes*.dex' })) {
            $dexPath = Join-Path $discoveryDir $entry.Name
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dexPath, $true)
            $dump = Get-ExternalOutput -FilePath $dexdump -Arguments @('-d', $dexPath) `
                -WorkingDirectory $discoveryDir -FailureMessage "dexdump failed for $($entry.Name)"
            if ($dump -match 'Lcom/kunk/singbox/reliability/PhysicalDialGateInstrumentationTest;') {
                $foundClass = $true
            }
            if ($dump -match 'failedPhysicalDialsStayWithinBudget') {
                $foundTest = $true
            }
        }
    }
    finally {
        $zip.Dispose()
    }
    if (-not $foundClass -or -not $foundTest) {
        Fail "PhysicalDialGateInstrumentationTest discovery failed: class=$foundClass test=$foundTest"
    }
    Write-Host 'Android instrumentation discovery: PhysicalDialGateInstrumentationTest found.'
}

if ($SelfTestBinaryScan) {
    Invoke-BinaryPatternScannerSelfTest
    foreach ($targetTag in @($trustedPatchHashes.Keys)) {
        $selfTestPatch = Resolve-PatchFile -targetTag $targetTag
        Assert-MinimalLibboxPatch -PatchPath $selfTestPatch -TargetTag $targetTag
        [void] (Resolve-DependencyPatch -targetTag $targetTag)
    }
    exit 0
}

try {
    $syncMutex = [System.Threading.Mutex]::new($false, 'Local\KunBox.SyncKernel')
    try {
        $syncMutexAcquired = $syncMutex.WaitOne(0)
    }
    catch [System.Threading.AbandonedMutexException] {
        $syncMutexAcquired = $true
        Write-Warning 'Recovered abandoned KunBox kernel sync mutex.'
    }
    if (-not $syncMutexAcquired) {
        Fail 'Another KunBox kernel sync is already running.'
    }
    Write-Stage 'Environment check'

    $gitPath = Resolve-CommandPath -commandName 'git' -hint 'Install Git and make sure it is on PATH.'
    $goPath = Resolve-GoPath
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

    $resolvedTag = Resolve-TargetTag
    $patchFile = Resolve-PatchFile -targetTag $resolvedTag
    Assert-MinimalLibboxPatch -PatchPath $patchFile -TargetTag $resolvedTag
    $dependencyPatch = Resolve-DependencyPatch -targetTag $resolvedTag
    Write-Host ("Patch file: {0}" -f $patchFile)

    Ensure-GomobileTools -goBinary $goPath -binDir $gopathBin
    Prepare-UpstreamTree -gitBinary $gitPath
    $officialAar = Build-LibboxSnapshot -goBinary $goPath -OutputPath (Join-Path $tempDir 'official-libbox.aar') -StageName 'Stage 2/8: Build official AAR baseline'
    Assert-UpstreamTreeIsClean -gitBinary $gitPath
    Apply-KunBoxPatch -gitBinary $gitPath
    Assert-NoUnbudgetedPhysicalDialSites
    Apply-DependencyPatch -goBinary $goPath -gitBinary $gitPath
    Run-GoValidation -goBinary $goPath
    $builtAar = Build-LibboxSnapshot -goBinary $goPath -OutputPath (Join-Path $tempDir 'patched-libbox.aar') -StageName 'Stage 5/8: Build patched libbox.aar'
    Assert-AarCompatibility -OfficialAar $officialAar -PatchedAar $builtAar -javapBinary $javapPath
    Replace-Aar -aarPath $builtAar
    Run-GradleValidation
    $aarReplaced = $false
    $syncSucceeded = $true

    Write-Host ''
    Write-Host 'sync-kernel.ps1 completed successfully.'
}
catch {
    $failure = $_.Exception
    if ($aarReplaced -and (Test-Path $backupAar)) {
        try {
            try {
                Invoke-External -FilePath $gradleWrapper -Arguments @('--stop') -WorkingDirectory $repoRoot `
                    -FailureMessage 'Failed to stop Gradle before restoring libbox.aar'
            }
            catch {
                [Console]::Error.WriteLine(
                    "Gradle stop failed before rollback; continuing restore: $($_.Exception.Message)"
                )
            }
            [GC]::Collect()
            [GC]::WaitForPendingFinalizers()
            Copy-FileWithRetry -Source $backupAar -Destination $targetAar
            $aarReplaced = $false
            [Console]::Error.WriteLine("Gradle validation failed; restored previous libbox.aar from $backupAar")
        } catch {
            [Console]::Error.WriteLine("Failed to restore previous libbox.aar: $($_.Exception.Message)")
        }
    }
    [Console]::Error.WriteLine($failure.Message)
    exit 1
}
finally {
    try {
        if ($syncMutexAcquired) {
            Remove-WorkspaceGarbage
            if ($syncSucceeded) {
                Trim-OldAarBackups
            }
        }
    }
    finally {
        if ($syncMutexAcquired) {
            try {
                $syncMutex.ReleaseMutex()
            }
            catch {
                [Console]::Error.WriteLine("Failed to release kernel sync mutex: $($_.Exception.Message)")
            }
            $syncMutexAcquired = $false
        }
        if ($null -ne $syncMutex) {
            $syncMutex.Dispose()
            $syncMutex = $null
        }
    }
}
