$scriptPath = Join-Path $PSScriptRoot "collect-android.ps1"
. $scriptPath -LoadFunctionsOnly

Describe "collect-android benchmark helpers" {
    It "enforces the configured timeout for every adb call" {
        $powerShellPath = (Get-Process -Id $PID).Path
        $processResult = Invoke-NativeProcess `
            -FilePath $powerShellPath `
            -ProcessArguments @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 5') `
            -TimeoutMilliseconds 200

        $processResult.TimedOut | Should Be $true
        $timeoutMessage = $null
        try {
            Resolve-AdbProcessOutput `
                -Result $processResult `
                -Arguments @('shell', 'getprop') `
                -TimeoutSeconds 1 | Out-Null
        } catch {
            $timeoutMessage = $_.Exception.Message
        }
        $timeoutMessage | Should Match 'adb 命令超时'
    }

    It "captures native process output before the timeout" {
        $powerShellPath = (Get-Process -Id $PID).Path
        $processResult = Invoke-NativeProcess `
            -FilePath $powerShellPath `
            -ProcessArguments @('-NoProfile', '-NonInteractive', '-Command', 'Write-Output CANARY_NATIVE_OUTPUT') `
            -TimeoutMilliseconds 5000

        $processResult.TimedOut | Should Be $false
        $processResult.ExitCode | Should Be 0
        ($processResult.Output -join "`n") | Should Match 'CANARY_NATIVE_OUTPUT'
    }

    It "treats a failed FD probe as unavailable instead of zero" {
        ConvertFrom-FdProbeOutput @("__KUNBOX_STATUS__1") | Should Be $null
    }

    It "counts FD entries only when the probe succeeds" {
        ConvertFrom-FdProbeOutput @("0", "1", "2", "__KUNBOX_STATUS__0") | Should Be 3
        ConvertFrom-FdProbeOutput @("__KUNBOX_STATUS__0") | Should Be 0
    }

    It "falls back to run-as after the shell FD probe is denied" {
        Mock Invoke-AdbBestEffort {
            param([string[]]$Arguments)
            if ($Arguments -contains "run-as") {
                return @("0", "1", "__KUNBOX_STATUS__0")
            }
            return @("__KUNBOX_STATUS__1")
        }

        Get-FdCount -PidValue 42 | Should Be 2
        Assert-MockCalled Invoke-AdbBestEffort -Times 2
    }

    It "persists every completed sample batch incrementally" {
        $path = Join-Path $TestDrive 'samples.csv'
        Initialize-SampleCsv -Path $path
        $first = [pscustomobject][ordered]@{
            Timestamp = '2026-07-18T00:00:00Z'
            TimestampEpochMs = 1
            SampleIndex = 1
            ProcessName = 'CANARY_PROCESS_MAIN'
            Pid = 10
            PssKb = 100
            CpuPercent = 1.0
            FdCount = 20
        }
        $second = [pscustomobject][ordered]@{
            Timestamp = '2026-07-18T00:00:15Z'
            TimestampEpochMs = 2
            SampleIndex = 2
            ProcessName = 'CANARY_PROCESS_BG'
            Pid = 11
            PssKb = 200
            CpuPercent = 2.0
            FdCount = 30
        }

        Add-SampleCsvRows -Path $path -Rows @($first)
        (Get-Content -LiteralPath $path -Encoding UTF8).Count | Should Be 2
        Add-SampleCsvRows -Path $path -Rows @($second)
        $content = Get-Content -LiteralPath $path -Encoding UTF8

        $content.Count | Should Be 3
        ($content -join "`n") | Should Match 'CANARY_PROCESS_MAIN'
        ($content -join "`n") | Should Match 'CANARY_PROCESS_BG'
    }

    It "anchors duration-based samples to the requested deadline" {
        $schedule = New-BenchmarkSchedule -DurationMinutes 1 -IntervalSeconds 15 -SampleCount 0

        $schedule.SampleCount | Should Be 4
        $schedule.DeadlineSeconds | Should Be 60
        $schedule.WaitForDeadline | Should Be $true
        Test-BenchmarkSlotExpired `
            -ElapsedSeconds 15.05 -TargetSeconds 15 -DeadlineSeconds 60 -IntervalSeconds 15 | Should Be $false
        Test-BenchmarkSlotExpired `
            -ElapsedSeconds 30 -TargetSeconds 15 -DeadlineSeconds 60 -IntervalSeconds 15 | Should Be $true
        Test-BenchmarkSlotExpired `
            -ElapsedSeconds 60 -TargetSeconds 60 -DeadlineSeconds 60 -IntervalSeconds 15 | Should Be $true
    }

    It "anchors fixed sample counts without adding collection time to each interval" {
        $schedule = New-BenchmarkSchedule -DurationMinutes 60 -IntervalSeconds 15 -SampleCount 3

        $schedule.SampleCount | Should Be 3
        $schedule.DeadlineSeconds | Should Be 30
        $schedule.WaitForDeadline | Should Be $false
        Get-RemainingDelayMilliseconds -ElapsedSeconds 2.25 -TargetSeconds 5 | Should Be 2750
        Get-RemainingDelayMilliseconds -ElapsedSeconds 6 -TargetSeconds 5 | Should Be 0
    }
}
