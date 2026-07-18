<#
.SYNOPSIS
采集 Android VPN 客户端的 PSS、CPU、FD、电池和系统状态基准。

.DESCRIPTION
脚本只读取应用与系统状态。默认不会启动、停止应用，也不会重置电池统计。
指定 -ResetBatteryStats 时才会执行 batterystats --reset。

.EXAMPLE
.\tools\benchmark\collect-android.ps1 -Label kunbox-idle -DurationMinutes 480 -IntervalSeconds 15

.EXAMPLE
.\tools\benchmark\collect-android.ps1 -PackageName com.v2ray.ang -Label v2rayng -SampleCount 40
#>
[CmdletBinding()]
param(
    [string]$PackageName = "com.kunk.singbox",
    [string]$Label = "kunbox",
    [ValidateRange(1, 3600)]
    [int]$IntervalSeconds = 15,
    [ValidateRange(1, 10080)]
    [int]$DurationMinutes = 60,
    [ValidateRange(0, 100000)]
    [int]$SampleCount = 0,
    [string]$DeviceSerial = "",
    [string]$AdbExecutable = "",
    [ValidateRange(1, 300)]
    [int]$AdbTimeoutSeconds = 30,
    [string]$OutputRoot = "",
    [switch]$ResetBatteryStats,
    [switch]$LoadFunctionsOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "build\benchmarks"
}

function Resolve-AdbPath {
    if ($AdbExecutable) {
        if (-not (Test-Path -LiteralPath $AdbExecutable)) {
            throw "指定的 adb 不存在：$AdbExecutable"
        }
        return (Resolve-Path -LiteralPath $AdbExecutable).Path
    }

    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:ANDROID_SDK_ROOT) {
        $candidates.Add((Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"))
    }
    if ($env:ANDROID_HOME) {
        $candidates.Add((Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"))
    }
    if ($env:LOCALAPPDATA) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"))
    }

    $localProperties = Join-Path $repoRoot "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties -Encoding UTF8 |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkLine) {
            $sdkDir = $sdkLine.Substring("sdk.dir=".Length).Replace('\:', ':').Replace('\\', '\')
            $candidates.Add((Join-Path $sdkDir "platform-tools\adb.exe"))
        }
    }

    $resolved = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $resolved) {
        throw "未找到 adb，请配置 ANDROID_SDK_ROOT、ANDROID_HOME 或 local.properties。"
    }
    return $resolved
}

$script:AdbPrefix = @()
$script:AdbTimeoutMilliseconds = $AdbTimeoutSeconds * 1000

function ConvertTo-NativeArgument {
    param([AllowEmptyString()][string]$Value)

    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') {
        return $Value
    }

    $builder = [Text.StringBuilder]::new()
    [void]$builder.Append('"')
    $backslashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') {
            $backslashes++
            continue
        }
        if ($character -eq '"') {
            if ($backslashes -gt 0) {
                [void]$builder.Append((('\' * ($backslashes * 2 + 1)) -join ''))
            } else {
                [void]$builder.Append('\')
            }
            [void]$builder.Append('"')
            $backslashes = 0
            continue
        }
        if ($backslashes -gt 0) {
            [void]$builder.Append((('\' * $backslashes) -join ''))
            $backslashes = 0
        }
        [void]$builder.Append($character)
    }
    if ($backslashes -gt 0) {
        [void]$builder.Append((('\' * ($backslashes * 2)) -join ''))
    }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function ConvertTo-ProcessOutputLines {
    param([AllowEmptyCollection()][string[]]$Chunks)

    $result = [System.Collections.Generic.List[string]]::new()
    foreach ($chunk in $Chunks) {
        if ([string]::IsNullOrEmpty($chunk)) {
            continue
        }
        $lines = @($chunk -split '\r?\n')
        for ($index = 0; $index -lt $lines.Count; $index++) {
            if ($index -eq $lines.Count - 1 -and $lines[$index] -eq '') {
                continue
            }
            $result.Add($lines[$index])
        }
    }
    return @($result)
}

function Invoke-NativeProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$ProcessArguments,
        [Parameter(Mandatory)][int]$TimeoutMilliseconds
    )

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true
    $startInfo.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    if ($null -ne $startInfo.PSObject.Properties['ArgumentList']) {
        foreach ($argument in $ProcessArguments) {
            [void]$startInfo.ArgumentList.Add($argument)
        }
    } else {
        $startInfo.Arguments = ($ProcessArguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw "无法启动进程：$FilePath"
        }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $timedOut = -not $process.WaitForExit($TimeoutMilliseconds)
        if ($timedOut) {
            try {
                if (-not $process.HasExited) {
                    $process.Kill()
                }
            } catch {
                Write-Verbose "终止超时进程时发生竞态：$($_.Exception.Message)"
            }
            try {
                [void]$process.WaitForExit(5000)
            } catch {
                Write-Verbose "等待超时进程退出失败：$($_.Exception.Message)"
            }
        } else {
            $process.WaitForExit()
        }
        $stdoutText = if ($timedOut -and -not $stdout.IsCompleted) { "" } else { $stdout.GetAwaiter().GetResult() }
        $stderrText = if ($timedOut -and -not $stderr.IsCompleted) { "" } else { $stderr.GetAwaiter().GetResult() }
        return [pscustomobject]@{
            TimedOut = $timedOut
            ExitCode = if ($timedOut) { $null } else { $process.ExitCode }
            Output = ConvertTo-ProcessOutputLines @($stdoutText, $stderrText)
        }
    } finally {
        $process.Dispose()
    }
}

function Resolve-AdbProcessOutput {
    param(
        [Parameter(Mandatory)]$Result,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][int]$TimeoutSeconds
    )

    if ($Result.TimedOut) {
        throw "adb 命令超时（$TimeoutSeconds 秒）：$($Arguments -join ' ')"
    }
    if ($Result.ExitCode -ne 0) {
        throw "adb 命令失败：$($Arguments -join ' ')`n$($Result.Output -join "`n")"
    }
    return @($Result.Output)
}

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $processArguments = @($script:AdbPrefix) + @($Arguments)
    $result = Invoke-NativeProcess `
        -FilePath $script:AdbPath `
        -ProcessArguments $processArguments `
        -TimeoutMilliseconds $script:AdbTimeoutMilliseconds
    return Resolve-AdbProcessOutput `
        -Result $result `
        -Arguments $Arguments `
        -TimeoutSeconds $AdbTimeoutSeconds
}

function Invoke-AdbBestEffort {
    param([Parameter(Mandatory)][string[]]$Arguments)

    try {
        return Invoke-Adb -Arguments $Arguments
    } catch {
        return @("ERROR: $($_.Exception.Message)")
    }
}

function Select-Device {
    $devices = @(
        Invoke-Adb -Arguments @('devices') |
            Select-String -Pattern '^([^\s]+)\s+device$' |
            ForEach-Object { $_.Matches[0].Groups[1].Value }
    )

    if ($DeviceSerial) {
        if ($DeviceSerial -notin $devices) {
            throw "设备 $DeviceSerial 未连接或未授权。"
        }
        $script:AdbPrefix = @('-s', $DeviceSerial)
        return $DeviceSerial
    }
    if ($devices.Count -ne 1) {
        throw "需要且仅允许一个已授权设备，当前数量：$($devices.Count)。可用 -DeviceSerial 指定。"
    }
    $script:AdbPrefix = @('-s', $devices[0])
    return $devices[0]
}

function Save-AdbOutput {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    Invoke-AdbBestEffort -Arguments $Arguments |
        Set-Content -LiteralPath $Path -Encoding UTF8
}

function Get-AppProcesses {
    $result = [System.Collections.Generic.List[object]]::new()
    $lines = Invoke-AdbBestEffort -Arguments @('shell', 'ps', '-A', '-o', 'PID,NAME')
    foreach ($line in $lines) {
        if ($line -match '^\s*(\d+)\s+(\S+)\s*$') {
            $pidValue = [int]$Matches[1]
            $name = $Matches[2]
            if ($name -eq $PackageName -or $name.StartsWith("${PackageName}:")) {
                $result.Add([pscustomobject]@{ Pid = $pidValue; Name = $name })
            }
        }
    }
    if ($result.Count -gt 0) {
        return @($result | Sort-Object Name)
    }

    foreach ($name in @($PackageName, "$PackageName:bg")) {
        $pidText = (Invoke-AdbBestEffort -Arguments @('shell', 'pidof', $name) | Select-Object -First 1).Trim()
        if ($pidText -match '^\d+$') {
            $result.Add([pscustomobject]@{ Pid = [int]$pidText; Name = $name })
        }
    }
    return @($result | Sort-Object Name)
}

function Get-CpuPercentByProcess {
    $result = @{}
    foreach ($line in (Invoke-AdbBestEffort -Arguments @('shell', 'dumpsys', 'cpuinfo'))) {
        if ($line -match '^\s*([0-9.]+)%\s+\d+/(\S+):\s') {
            $name = $Matches[2]
            if ($name -eq $PackageName -or $name.StartsWith("${PackageName}:")) {
                $result[$name] = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
            }
        }
    }
    return $result
}

function Get-PssKb {
    param([int]$PidValue)

    $lines = Invoke-AdbBestEffort -Arguments @('shell', 'dumpsys', 'meminfo', $PidValue.ToString())
    foreach ($line in $lines) {
        if ($line -match 'TOTAL PSS:\s*([0-9,]+)') {
            return [int64]($Matches[1].Replace(',', ''))
        }
    }
    foreach ($line in $lines) {
        if ($line -match '^\s*TOTAL\s+([0-9,]+)\s') {
            return [int64]($Matches[1].Replace(',', ''))
        }
    }
    return $null
}

function ConvertFrom-FdProbeOutput {
    param([AllowEmptyCollection()][object[]]$Lines)

    $textLines = @($Lines | ForEach-Object { [string]$_ })
    if ($textLines.Count -eq 0) {
        return $null
    }

    $statusLine = $textLines[-1]
    if ($statusLine -notmatch '^__KUNBOX_STATUS__(\d+)$' -or [int]$Matches[1] -ne 0) {
        return $null
    }

    $entryCount = 0
    for ($index = 0; $index -lt $textLines.Count - 1; $index++) {
        if (-not [string]::IsNullOrWhiteSpace($textLines[$index])) {
            $entryCount++
        }
    }
    return $entryCount
}

function Get-FdCount {
    param([int]$PidValue)

    $command = 'ls -1A /proc/{0}/fd 2>/dev/null; printf "__KUNBOX_STATUS__%s\n" $?' -f $PidValue
    $value = ConvertFrom-FdProbeOutput (
        Invoke-AdbBestEffort -Arguments @('shell', 'sh', '-c', $command)
    )
    if ($null -ne $value) {
        return $value
    }

    $value = ConvertFrom-FdProbeOutput (
        Invoke-AdbBestEffort -Arguments @('shell', 'run-as', $PackageName, 'sh', '-c', $command)
    )
    if ($null -ne $value) {
        return $value
    }
    return $null
}

function Get-Percentile {
    param(
        [object[]]$Values,
        [ValidateRange(0.0, 1.0)][double]$Percentile
    )

    $numbers = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ } | Sort-Object)
    if ($numbers.Count -eq 0) {
        return $null
    }
    $index = [Math]::Max(0, [Math]::Ceiling($numbers.Count * $Percentile) - 1)
    return $numbers[$index]
}

function Get-HashPrefix {
    param([Parameter(Mandatory)][string]$Value)

    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        $hex = [BitConverter]::ToString($sha256.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()
        return $hex.Substring(0, 12)
    } finally {
        $sha256.Dispose()
    }
}

function Get-DeviceProperty {
    param([Parameter(Mandatory)][string]$Name)

    $value = Invoke-AdbBestEffort -Arguments @('shell', 'getprop', $Name) | Select-Object -First 1
    return ([string]$value).Trim()
}

function New-ProcessSummary {
    param([Parameter(Mandatory)]$Group)

    $rows = @($Group.Group)
    $pss = @($rows | ForEach-Object { $_.PssKb } | Where-Object { $null -ne $_ })
    $cpu = @($rows | ForEach-Object { $_.CpuPercent } | Where-Object { $null -ne $_ })
    $fd = @($rows | ForEach-Object { $_.FdCount } | Where-Object { $null -ne $_ })
    return [ordered]@{
        process_name = $Group.Name
        samples = $rows.Count
        pss_kb_average = if ($pss.Count) { [Math]::Round(($pss | Measure-Object -Average).Average, 2) } else { $null }
        pss_kb_p95 = Get-Percentile -Values $pss -Percentile 0.95
        pss_kb_max = if ($pss.Count) { ($pss | Measure-Object -Maximum).Maximum } else { $null }
        cpu_percent_average = if ($cpu.Count) { [Math]::Round(($cpu | Measure-Object -Average).Average, 2) } else { $null }
        cpu_percent_p95 = Get-Percentile -Values $cpu -Percentile 0.95
        cpu_percent_max = if ($cpu.Count) { ($cpu | Measure-Object -Maximum).Maximum } else { $null }
        fd_first = if ($fd.Count) { $fd[0] } else { $null }
        fd_last = if ($fd.Count) { $fd[-1] } else { $null }
        fd_max = if ($fd.Count) { ($fd | Measure-Object -Maximum).Maximum } else { $null }
    }
}

function New-BenchmarkSchedule {
    param(
        [int]$DurationMinutes,
        [int]$IntervalSeconds,
        [int]$SampleCount
    )

    if ($SampleCount -gt 0) {
        return [pscustomobject]@{
            SampleCount = $SampleCount
            DeadlineSeconds = [long]([Math]::Max(0, $SampleCount - 1) * $IntervalSeconds)
            WaitForDeadline = $false
        }
    }

    $durationSeconds = [long]$DurationMinutes * 60L
    return [pscustomobject]@{
        SampleCount = [int][Math]::Ceiling($durationSeconds / [double]$IntervalSeconds)
        DeadlineSeconds = $durationSeconds
        WaitForDeadline = $true
    }
}

function Get-RemainingDelayMilliseconds {
    param(
        [double]$ElapsedSeconds,
        [double]$TargetSeconds
    )

    return [int][Math]::Ceiling([Math]::Max(0.0, $TargetSeconds - $ElapsedSeconds) * 1000.0)
}

function Test-BenchmarkSlotExpired {
    param(
        [double]$ElapsedSeconds,
        [double]$TargetSeconds,
        [double]$DeadlineSeconds,
        [double]$IntervalSeconds
    )

    return $ElapsedSeconds -ge $DeadlineSeconds -or
        ($TargetSeconds -gt 0.0 -and $ElapsedSeconds - $TargetSeconds -ge $IntervalSeconds)
}

function Initialize-SampleCsv {
    param([Parameter(Mandatory)][string]$Path)

    'Timestamp,TimestampEpochMs,SampleIndex,ProcessName,Pid,PssKb,CpuPercent,FdCount' |
        Set-Content -LiteralPath $Path -Encoding UTF8
}

function Add-SampleCsvRows {
    param(
        [Parameter(Mandatory)][string]$Path,
        [AllowEmptyCollection()][object[]]$Rows
    )

    if (@($Rows).Count -eq 0) {
        return
    }
    $Rows | Export-Csv -LiteralPath $Path -Append -NoTypeInformation -Encoding UTF8
}

if ($LoadFunctionsOnly) {
    return
}

$script:AdbPath = Resolve-AdbPath
$selectedSerial = Select-Device
if (@(Invoke-Adb -Arguments @('shell', 'pm', 'path', $PackageName)).Count -eq 0) {
    throw "设备上未安装 $PackageName。"
}

$safeLabel = $Label -replace '[^A-Za-z0-9._-]', '_'
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$sessionDirectory = Join-Path $OutputRoot "${timestamp}_${safeLabel}"
New-Item -ItemType Directory -Path $sessionDirectory -Force | Out-Null

$schedule = New-BenchmarkSchedule `
    -DurationMinutes $DurationMinutes `
    -IntervalSeconds $IntervalSeconds `
    -SampleCount $SampleCount
$sampleTarget = $schedule.SampleCount

$deviceProps = [ordered]@{
    manufacturer = Get-DeviceProperty -Name 'ro.product.manufacturer'
    model = Get-DeviceProperty -Name 'ro.product.model'
    android_version = Get-DeviceProperty -Name 'ro.build.version.release'
    android_api = Get-DeviceProperty -Name 'ro.build.version.sdk'
}
$appVersion = Invoke-AdbBestEffort -Arguments @('shell', 'dumpsys', 'package', $PackageName) |
    Select-String -Pattern 'versionName=|versionCode=' |
    ForEach-Object { $_.Line.Trim() }
$gitRevision = (& git -C $repoRoot rev-parse HEAD 2>$null | Select-Object -First 1)

$metadata = [ordered]@{
    format_version = 1
    created_at = [DateTimeOffset]::Now.ToString('o')
    label = $Label
    package_name = $PackageName
    device_id = Get-HashPrefix -Value $selectedSerial
    device_properties = $deviceProps
    app_version = @($appVersion)
    interval_seconds = $IntervalSeconds
    requested_duration_minutes = $DurationMinutes
    sample_count = $sampleTarget
    reset_battery_stats = $ResetBatteryStats.IsPresent
    git_revision = $gitRevision
}
$metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $sessionDirectory 'metadata.json') -Encoding UTF8

Save-AdbOutput -Path (Join-Path $sessionDirectory 'batterystats-initial.txt') `
    -Arguments @('shell', 'dumpsys', 'batterystats')
if ($ResetBatteryStats) {
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'batterystats', '--reset') | Out-Null
}
Save-AdbOutput -Path (Join-Path $sessionDirectory 'batterystats-before.txt') `
    -Arguments @('shell', 'dumpsys', 'batterystats')
Save-AdbOutput -Path (Join-Path $sessionDirectory 'power-before.txt') `
    -Arguments @('shell', 'dumpsys', 'power')

$samplesPath = Join-Path $sessionDirectory 'samples.csv'
Initialize-SampleCsv -Path $samplesPath
$rows = [System.Collections.Generic.List[object]]::new()
$collectionClock = [Diagnostics.Stopwatch]::StartNew()
for ($sampleIndex = 1; $sampleIndex -le $sampleTarget; $sampleIndex++) {
    $targetSeconds = [long]($sampleIndex - 1) * $IntervalSeconds
    if ($schedule.WaitForDeadline) {
        $elapsedSeconds = $collectionClock.Elapsed.TotalSeconds
        if ($elapsedSeconds -ge $schedule.DeadlineSeconds) {
            break
        }
        if (Test-BenchmarkSlotExpired `
            -ElapsedSeconds $elapsedSeconds `
            -TargetSeconds $targetSeconds `
            -DeadlineSeconds $schedule.DeadlineSeconds `
            -IntervalSeconds $IntervalSeconds) {
            continue
        }
    }
    $sleepMilliseconds = Get-RemainingDelayMilliseconds `
        -ElapsedSeconds $collectionClock.Elapsed.TotalSeconds `
        -TargetSeconds $targetSeconds
    if ($sleepMilliseconds -gt 0) {
        Start-Sleep -Milliseconds $sleepMilliseconds
    }
    if ($schedule.WaitForDeadline -and $collectionClock.Elapsed.TotalSeconds -ge $schedule.DeadlineSeconds) {
        break
    }

    $cpuByProcess = Get-CpuPercentByProcess
    $processes = Get-AppProcesses
    $capturedAt = [DateTimeOffset]::Now
    $sampleRows = [System.Collections.Generic.List[object]]::new()
    foreach ($process in $processes) {
        $row = [pscustomobject][ordered]@{
            Timestamp = $capturedAt.ToString('o')
            TimestampEpochMs = $capturedAt.ToUnixTimeMilliseconds()
            SampleIndex = $sampleIndex
            ProcessName = $process.Name
            Pid = $process.Pid
            PssKb = Get-PssKb -PidValue $process.Pid
            CpuPercent = $cpuByProcess[$process.Name]
            FdCount = Get-FdCount -PidValue $process.Pid
        }
        $rows.Add($row)
        $sampleRows.Add($row)
    }
    Add-SampleCsvRows -Path $samplesPath -Rows @($sampleRows)

    Write-Progress -Activity "采集 Android 资源基准" -Status "$sampleIndex / $sampleTarget" `
        -PercentComplete ($sampleIndex * 100.0 / $sampleTarget)
}
if ($schedule.WaitForDeadline) {
    $sleepMilliseconds = Get-RemainingDelayMilliseconds `
        -ElapsedSeconds $collectionClock.Elapsed.TotalSeconds `
        -TargetSeconds $schedule.DeadlineSeconds
    if ($sleepMilliseconds -gt 0) {
        Start-Sleep -Milliseconds $sleepMilliseconds
    }
}
$collectionClock.Stop()
Write-Progress -Activity "采集 Android 资源基准" -Completed

Save-AdbOutput -Path (Join-Path $sessionDirectory 'batterystats-after.txt') `
    -Arguments @('shell', 'dumpsys', 'batterystats')
Save-AdbOutput -Path (Join-Path $sessionDirectory 'power-after.txt') `
    -Arguments @('shell', 'dumpsys', 'power')
Save-AdbOutput -Path (Join-Path $sessionDirectory 'connectivity-after.txt') `
    -Arguments @('shell', 'dumpsys', 'connectivity')
Save-AdbOutput -Path (Join-Path $sessionDirectory 'vpn-management-after.txt') `
    -Arguments @('shell', 'dumpsys', 'vpn_management')
Save-AdbOutput -Path (Join-Path $sessionDirectory 'exit-info-after.txt') `
    -Arguments @('shell', 'dumpsys', 'activity', 'exit-info', $PackageName)

$summary = [ordered]@{
    format_version = 1
    label = $Label
    package_name = $PackageName
    collected_rows = $rows.Count
    started_at = if ($rows.Count) { $rows[0].Timestamp } else { $null }
    finished_at = if ($rows.Count) { $rows[-1].Timestamp } else { $null }
    processes = @($rows | Group-Object ProcessName | ForEach-Object { New-ProcessSummary -Group $_ })
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $sessionDirectory 'summary.json') -Encoding UTF8

Write-Host "基准采集完成：$sessionDirectory"
