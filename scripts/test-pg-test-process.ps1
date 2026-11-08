<#
.SYNOPSIS
    pg-test-process.ps1 的自检：验证有界进程执行在退出码、输出捕获、超时结束进程、
    带空格路径/参数、常驻进程句柄，以及日志打开/启动/复制初始化失败后的清理行为。

.DESCRIPTION
    不依赖 PostgreSQL，也不需要管理员权限；在临时目录里跑几条短命令。
    全部通过退出 0，任何一条不通过退出 1。用于证明 run-pg-tests.ps1 依赖的
    “以进程退出为准、超时结束进程树” 的语义没有退化，并覆盖第四轮 T4-1 的异常清理：
    原始错误不被清理异常遮蔽、不遗留子进程、日志流全部释放。

.EXAMPLE
    pwsh -File scripts/test-pg-test-process.ps1
#>
[CmdletBinding()]
param(
    [string]$WorkRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'pg-test-process.ps1')

$failures = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if ($Condition) {
        Write-Host "[pg-test-process] 通过：$Message" -ForegroundColor Green
    }
    else {
        $script:failures++
        Write-Host "[pg-test-process] 失败：$Message" -ForegroundColor Red
    }
}

function Get-LogText {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        return (Get-Content -LiteralPath $Path -Raw)
    }
    return ''
}

# 取最内层异常：PowerShell 会把 .NET 构造/方法错误包成 MethodInvocationException，
# 真实错误（拒绝访问、找不到文件）在 InnerException 里。
function Get-RootException {
    param([Parameter(Mandatory = $true)]$ErrorRecord)
    $exception = $ErrorRecord.Exception
    while ($null -ne $exception.InnerException) {
        $exception = $exception.InnerException
    }
    return $exception
}

function Get-ExceptionTypeName {
    param($Exception)
    if ($null -eq $Exception) {
        return '<none>'
    }
    return $Exception.GetType().Name
}

function Get-ExceptionMessage {
    param($Exception)
    if ($null -eq $Exception) {
        return ''
    }
    return $Exception.Message
}

function Invoke-ExpectedFailure {
    param([Parameter(Mandatory = $true)][scriptblock]$Action)
    try {
        & $Action
        return [pscustomobject]@{ Threw = $false; ErrorRecord = $null; Root = $null }
    }
    catch {
        return [pscustomobject]@{
            Threw       = $true
            ErrorRecord = $_
            Root        = (Get-RootException -ErrorRecord $_)
        }
    }
}

# 探针子进程：先写 PID 标记再长睡，用来判断“是否启动了不该启动的子进程”。
function New-ProbeChildScript {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $path = Join-Path $Directory $Name
    Set-Content -LiteralPath $path -Encoding utf8 -Value @'
param([string]$PidPath)
Set-Content -LiteralPath $PidPath -Value $PID
Start-Sleep -Seconds 30
'@
    return $path
}

function Get-ProbeChildProcessId {
    param(
        [Parameter(Mandatory = $true)][string]$PidPath,
        [int]$TimeoutMs = 2000
    )
    $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
    while ($true) {
        if (Test-Path -LiteralPath $PidPath -PathType Leaf) {
            $raw = Get-Content -LiteralPath $PidPath -Raw -ErrorAction SilentlyContinue
            if ($raw -and $raw.Trim() -match '^\d+$') {
                return [int]$raw.Trim()
            }
        }
        if ((Get-Date) -ge $deadline) {
            return 0
        }
        Start-Sleep -Milliseconds 100
    }
}

# 以 FileShare.None 重新打开日志文件：若 helper 还留着句柄，这里会失败。
function Test-FileIsReleased {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $false
    }
    try {
        $probe = [System.IO.File]::Open(
            $Path,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None)
        $probe.Dispose()
        return $true
    }
    catch {
        return $false
    }
}

if (-not $WorkRoot) {
    $WorkRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('pg-test-process-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
}
[void][System.IO.Directory]::CreateDirectory($WorkRoot)
$pwshExe = (Get-Process -Id $PID).Path

try {
    # -----------------------------------------------------------------------
    # 1. 退出码原样返回
    # -----------------------------------------------------------------------
    $result = Invoke-BoundedProcess -FilePath $env:ComSpec `
        -ArgumentList @('/c', 'exit 7') `
        -TimeoutSeconds 30 `
        -StdoutPath (Join-Path $WorkRoot 'exit.stdout.log') `
        -StderrPath (Join-Path $WorkRoot 'exit.stderr.log') `
        -Description 'exit 7'
    Assert-True ($result.ExitCode -eq 7) "退出码原样返回（期望 7，实际 $($result.ExitCode)）"
    Assert-True (-not $result.TimedOut) '正常退出的命令不标记为超时'
    Assert-True ($result.DurationMs -ge 0) '返回耗时'
    Assert-True (Test-Path -LiteralPath $result.StdoutPath -PathType Leaf) '标准输出文件已创建'
    Assert-True (Test-Path -LiteralPath $result.StderrPath -PathType Leaf) '标准错误文件已创建'

    # -----------------------------------------------------------------------
    # 2. 标准输出与标准错误分别落盘
    # -----------------------------------------------------------------------
    $result = Invoke-BoundedProcess -FilePath $env:ComSpec `
        -ArgumentList @('/c', 'echo hello-stdout & echo hello-stderr 1>&2') `
        -TimeoutSeconds 30 `
        -StdoutPath (Join-Path $WorkRoot 'split.stdout.log') `
        -StderrPath (Join-Path $WorkRoot 'split.stderr.log')
    Assert-True ((Get-LogText $result.StdoutPath) -match 'hello-stdout') '标准输出写入 stdout 文件'
    Assert-True ((Get-LogText $result.StderrPath) -match 'hello-stderr') '标准错误写入 stderr 文件'
    Assert-True ((Get-LogText $result.StdoutPath) -notmatch 'hello-stderr') '标准错误没有混进 stdout 文件'

    # -----------------------------------------------------------------------
    # 3. 超时会结束进程本身，而不是只返回一个标志
    # -----------------------------------------------------------------------
    $result = Invoke-BoundedProcess -FilePath $pwshExe `
        -ArgumentList @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 120') `
        -TimeoutSeconds 2 `
        -StdoutPath (Join-Path $WorkRoot 'timeout.stdout.log') `
        -StderrPath (Join-Path $WorkRoot 'timeout.stderr.log')
    Assert-True ($result.TimedOut) '超过期限的命令标记为 TimedOut'
    Assert-True ($null -eq $result.ExitCode) '超时命令不返回伪造的退出码'
    Assert-True ($result.DurationMs -ge 1900 -and $result.DurationMs -lt 30000) "超时等待在期限附近结束（实际 $($result.DurationMs) ms）"
    $stillRunning = $null -ne (Get-Process -Id $result.ProcessId -ErrorAction SilentlyContinue)
    Assert-True (-not $stillRunning) "超时后进程 $($result.ProcessId) 已被结束"

    # -----------------------------------------------------------------------
    # 4. 带空格的路径与参数不被拆开
    # -----------------------------------------------------------------------
    $spaceDir = Join-Path $WorkRoot 'dir with spaces'
    [void][System.IO.Directory]::CreateDirectory($spaceDir)
    $echoScript = Join-Path $spaceDir 'echo-args.ps1'
    Set-Content -LiteralPath $echoScript -Encoding utf8 -Value @'
param([string]$Value)
Write-Output $Value
'@
    $spacedValue = 'value with spaces and 双引号"inside"'
    $result = Invoke-BoundedProcess -FilePath $pwshExe `
        -ArgumentList @('-NoProfile', '-NonInteractive', '-File', $echoScript, $spacedValue) `
        -TimeoutSeconds 60 `
        -StdoutPath (Join-Path $WorkRoot 'spaces.stdout.log') `
        -StderrPath (Join-Path $WorkRoot 'spaces.stderr.log')
    Assert-True ($result.ExitCode -eq 0) "带空格的脚本路径可以执行（退出码 $($result.ExitCode)）"
    Assert-True ((Get-LogText $result.StdoutPath).Trim() -eq $spacedValue) '带空格的参数原样传入'

    # -----------------------------------------------------------------------
    # 5. 常驻进程：保留句柄、不等待退出，关闭时能回收
    # -----------------------------------------------------------------------
    $resident = Start-ResidentProcess -FilePath $pwshExe `
        -ArgumentList @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 120') `
        -StdoutPath (Join-Path $WorkRoot 'resident.stdout.log') `
        -StderrPath (Join-Path $WorkRoot 'resident.stderr.log')
    Start-Sleep -Milliseconds 300
    $resident.Process.Refresh()
    Assert-True (-not $resident.Process.HasExited) '常驻进程启动后仍在运行'
    Assert-True ($null -ne (Get-Process -Id $resident.ProcessId -ErrorAction SilentlyContinue)) '常驻进程 PID 有效'
    try { $resident.Process.Kill($true) } catch { }
    Close-ResidentProcess -Resident $resident -TimeoutSeconds 15
    Assert-True ($null -eq (Get-Process -Id $resident.ProcessId -ErrorAction SilentlyContinue)) '常驻进程关闭后已退出'

    # -----------------------------------------------------------------------
    # 6. 不存在的可执行文件明确失败，而不是挂住；错误来源可识别、日志流已释放
    # -----------------------------------------------------------------------
    $missingExe = Join-Path $WorkRoot 'does-not-exist.exe'
    $missingStdout = Join-Path $WorkRoot 'missing.stdout.log'
    $missingStderr = Join-Path $WorkRoot 'missing.stderr.log'
    $startFailure = Invoke-ExpectedFailure {
        [void](Invoke-BoundedProcess -FilePath $missingExe `
                -TimeoutSeconds 5 `
                -StdoutPath $missingStdout `
                -StderrPath $missingStderr)
    }
    Assert-True $startFailure.Threw '不存在的可执行文件会抛出错误'
    Assert-True ((Get-ExceptionTypeName -Exception $startFailure.Root) -eq 'Win32Exception') "启动失败保留 Win32 错误来源（实际 $(Get-ExceptionTypeName -Exception $startFailure.Root)）"
    Assert-True ((Get-ExceptionMessage -Exception $startFailure.Root).Contains($missingExe)) '启动失败的错误指明缺失的可执行文件'
    Assert-True (Test-FileIsReleased -Path $missingStdout) '启动失败后 stdout 日志流已释放'
    Assert-True (Test-FileIsReleased -Path $missingStderr) '启动失败后 stderr 日志流已释放'

    # -----------------------------------------------------------------------
    # 7. Invoke-BoundedProcess 失败清理：日志打开失败、启动后复制初始化失败
    # -----------------------------------------------------------------------
    $failRoot = Join-Path $WorkRoot 'failure-bounded'
    [void][System.IO.Directory]::CreateDirectory($failRoot)
    $boundedChild = New-ProbeChildScript -Directory $failRoot -Name 'bounded-child.ps1'

    # 7.1 stdout 打开失败：路径是目录。日志打开在启动之前，子进程不应出现。
    $stdoutDirectory = Join-Path $failRoot 'stdout-is-directory'
    [void][System.IO.Directory]::CreateDirectory($stdoutDirectory)
    $pidMarker = Join-Path $failRoot 'bounded-stdout.pid'
    $stdoutOpenFailure = Invoke-ExpectedFailure {
        [void](Invoke-BoundedProcess -FilePath $pwshExe `
                -ArgumentList @('-NoProfile', '-NonInteractive', '-File', $boundedChild, $pidMarker) `
                -StdoutPath $stdoutDirectory `
                -StderrPath (Join-Path $failRoot 'bounded-stdout.stderr.log') `
                -TimeoutSeconds 30)
    }
    Assert-True $stdoutOpenFailure.Threw 'stdout 打开失败时抛出错误'
    Assert-True ((Get-ExceptionTypeName -Exception $stdoutOpenFailure.Root) -in @('UnauthorizedAccessException', 'IOException')) "stdout 打开失败保留文件错误（实际 $(Get-ExceptionTypeName -Exception $stdoutOpenFailure.Root)）"
    Assert-True ((Get-ExceptionMessage -Exception $stdoutOpenFailure.Root).Contains($stdoutDirectory)) 'stdout 打开失败的错误指明日志路径'
    Assert-True ((Get-ExceptionTypeName -Exception $stdoutOpenFailure.Root) -ne 'PropertyNotFoundException') 'stdout 打开失败没有被 Count 属性错误遮住'
    Assert-True ((Get-ProbeChildProcessId -PidPath $pidMarker -TimeoutMs 2000) -eq 0) 'stdout 打开失败时没有启动子进程'

    # 7.2 stderr 打开失败：先打开的 stdout 流必须释放，子进程不应出现。
    $stderrDirectory = Join-Path $failRoot 'stderr-is-directory'
    [void][System.IO.Directory]::CreateDirectory($stderrDirectory)
    $stderrStdoutLog = Join-Path $failRoot 'bounded-stderr.stdout.log'
    $pidMarker = Join-Path $failRoot 'bounded-stderr.pid'
    $stderrOpenFailure = Invoke-ExpectedFailure {
        [void](Invoke-BoundedProcess -FilePath $pwshExe `
                -ArgumentList @('-NoProfile', '-NonInteractive', '-File', $boundedChild, $pidMarker) `
                -StdoutPath $stderrStdoutLog `
                -StderrPath $stderrDirectory `
                -TimeoutSeconds 30)
    }
    Assert-True $stderrOpenFailure.Threw 'stderr 打开失败时抛出错误'
    Assert-True ((Get-ExceptionTypeName -Exception $stderrOpenFailure.Root) -in @('UnauthorizedAccessException', 'IOException')) "stderr 打开失败保留文件错误（实际 $(Get-ExceptionTypeName -Exception $stderrOpenFailure.Root)）"
    Assert-True ((Get-ExceptionMessage -Exception $stderrOpenFailure.Root).Contains($stderrDirectory)) 'stderr 打开失败的错误指明日志路径'
    Assert-True (Test-FileIsReleased -Path $stderrStdoutLog) 'stderr 打开失败后先打开的 stdout 流已释放'
    Assert-True ((Get-ProbeChildProcessId -PidPath $pidMarker -TimeoutMs 2000) -eq 0) 'stderr 打开失败时没有启动子进程'

    # 7.3 启动后复制初始化失败：局部覆盖复制任务构造函数，确定性触发清理路径。
    $copyPidMarker = Join-Path $failRoot 'bounded-copy.pid'
    $copyObservedPid = Join-Path $failRoot 'bounded-copy-observed.pid'
    $copyStdoutLog = Join-Path $failRoot 'bounded-copy.stdout.log'
    $copyStderrLog = Join-Path $failRoot 'bounded-copy.stderr.log'
    $copyInitFailure = & {
        function New-ProcessOutputCopyTasks {
            param($Process, $StdoutStream, $StderrStream)
            # 抛错前从 Process 对象记录已启动的子进程 PID，不依赖子进程自己写文件的时序。
            [void](Set-Content -LiteralPath $copyObservedPid -Value $Process.Id)
            throw [System.InvalidOperationException]::new('自检注入：短命令复制初始化失败')
        }
        try {
            [void](Invoke-BoundedProcess -FilePath $pwshExe `
                    -ArgumentList @('-NoProfile', '-NonInteractive', '-File', $boundedChild, $copyPidMarker) `
                    -StdoutPath $copyStdoutLog `
                    -StderrPath $copyStderrLog `
                    -TimeoutSeconds 30)
            [pscustomobject]@{ Threw = $false; Root = $null }
        }
        catch {
            [pscustomobject]@{ Threw = $true; Root = (Get-RootException -ErrorRecord $_) }
        }
    }
    Assert-True $copyInitFailure.Threw '复制初始化失败时抛出错误'
    Assert-True ((Get-ExceptionTypeName -Exception $copyInitFailure.Root) -eq 'InvalidOperationException') "复制初始化失败保留原始错误（实际 $(Get-ExceptionTypeName -Exception $copyInitFailure.Root)）"
    Assert-True ((Get-ExceptionMessage -Exception $copyInitFailure.Root).Contains('自检注入')) '复制初始化失败的原始消息未被清理遮住'
    $copyChildId = Get-ProbeChildProcessId -PidPath $copyObservedPid -TimeoutMs 2000
    Assert-True ($copyChildId -gt 0) '复制初始化失败用例的探针子进程确实启动过'
    if ($copyChildId -gt 0) {
        $copyChildAlive = $null -ne (Get-Process -Id $copyChildId -ErrorAction SilentlyContinue)
        Assert-True (-not $copyChildAlive) "复制初始化失败后子进程 $copyChildId 已被结束"
        if ($copyChildAlive) {
            try { (Get-Process -Id $copyChildId).Kill($true) } catch { }
        }
    }
    Assert-True (Test-FileIsReleased -Path $copyStdoutLog) '复制初始化失败后 stdout 日志流已释放'
    Assert-True (Test-FileIsReleased -Path $copyStderrLog) '复制初始化失败后 stderr 日志流已释放'

    # -----------------------------------------------------------------------
    # 8. Start-ResidentProcess 失败清理：日志打开失败、启动失败、复制初始化失败
    # -----------------------------------------------------------------------
    $residentFailRoot = Join-Path $WorkRoot 'failure-resident'
    [void][System.IO.Directory]::CreateDirectory($residentFailRoot)
    $residentChild = New-ProbeChildScript -Directory $residentFailRoot -Name 'resident-child.ps1'

    # 8.1 stdout 打开失败
    $residentStdoutDirectory = Join-Path $residentFailRoot 'stdout-is-directory'
    [void][System.IO.Directory]::CreateDirectory($residentStdoutDirectory)
    $pidMarker = Join-Path $residentFailRoot 'resident-stdout.pid'
    $residentStdoutFailure = Invoke-ExpectedFailure {
        [void](Start-ResidentProcess -FilePath $pwshExe `
                -ArgumentList @('-NoProfile', '-NonInteractive', '-File', $residentChild, $pidMarker) `
                -StdoutPath $residentStdoutDirectory `
                -StderrPath (Join-Path $residentFailRoot 'resident-stdout.stderr.log'))
    }
    Assert-True $residentStdoutFailure.Threw '常驻：stdout 打开失败时抛出错误'
    Assert-True ((Get-ExceptionTypeName -Exception $residentStdoutFailure.Root) -in @('UnauthorizedAccessException', 'IOException')) "常驻：stdout 打开失败保留文件错误（实际 $(Get-ExceptionTypeName -Exception $residentStdoutFailure.Root)）"
    Assert-True ((Get-ExceptionMessage -Exception $residentStdoutFailure.Root).Contains($residentStdoutDirectory)) '常驻：stdout 打开失败的错误指明日志路径'
    Assert-True ((Get-ProbeChildProcessId -PidPath $pidMarker -TimeoutMs 2000) -eq 0) '常驻：stdout 打开失败时没有启动子进程'

    # 8.2 stderr 打开失败
    $residentStderrDirectory = Join-Path $residentFailRoot 'stderr-is-directory'
    [void][System.IO.Directory]::CreateDirectory($residentStderrDirectory)
    $residentStderrStdout = Join-Path $residentFailRoot 'resident-stderr.stdout.log'
    $pidMarker = Join-Path $residentFailRoot 'resident-stderr.pid'
    $residentStderrFailure = Invoke-ExpectedFailure {
        [void](Start-ResidentProcess -FilePath $pwshExe `
                -ArgumentList @('-NoProfile', '-NonInteractive', '-File', $residentChild, $pidMarker) `
                -StdoutPath $residentStderrStdout `
                -StderrPath $residentStderrDirectory)
    }
    Assert-True $residentStderrFailure.Threw '常驻：stderr 打开失败时抛出错误'
    Assert-True ((Get-ExceptionTypeName -Exception $residentStderrFailure.Root) -in @('UnauthorizedAccessException', 'IOException')) "常驻：stderr 打开失败保留文件错误（实际 $(Get-ExceptionTypeName -Exception $residentStderrFailure.Root)）"
    Assert-True ((Get-ExceptionMessage -Exception $residentStderrFailure.Root).Contains($residentStderrDirectory)) '常驻：stderr 打开失败的错误指明日志路径'
    Assert-True (Test-FileIsReleased -Path $residentStderrStdout) '常驻：stderr 打开失败后先打开的 stdout 流已释放'
    Assert-True ((Get-ProbeChildProcessId -PidPath $pidMarker -TimeoutMs 2000) -eq 0) '常驻：stderr 打开失败时没有启动子进程'

    # 8.3 启动失败
    $residentMissingExe = Join-Path $residentFailRoot 'does-not-exist-resident.exe'
    $residentMissingStdout = Join-Path $residentFailRoot 'resident-missing.stdout.log'
    $residentMissingStderr = Join-Path $residentFailRoot 'resident-missing.stderr.log'
    $residentStartFailure = Invoke-ExpectedFailure {
        [void](Start-ResidentProcess -FilePath $residentMissingExe `
                -StdoutPath $residentMissingStdout `
                -StderrPath $residentMissingStderr)
    }
    Assert-True $residentStartFailure.Threw '常驻：不存在的可执行文件会抛出错误'
    Assert-True ((Get-ExceptionTypeName -Exception $residentStartFailure.Root) -eq 'Win32Exception') "常驻：启动失败保留 Win32 错误来源（实际 $(Get-ExceptionTypeName -Exception $residentStartFailure.Root)）"
    Assert-True ((Get-ExceptionMessage -Exception $residentStartFailure.Root).Contains($residentMissingExe)) '常驻：启动失败的错误指明缺失的可执行文件'
    Assert-True (Test-FileIsReleased -Path $residentMissingStdout) '常驻：启动失败后 stdout 日志流已释放'
    Assert-True (Test-FileIsReleased -Path $residentMissingStderr) '常驻：启动失败后 stderr 日志流已释放'

    # 8.4 启动后复制初始化失败
    $residentCopyPidMarker = Join-Path $residentFailRoot 'resident-copy.pid'
    $residentCopyObservedPid = Join-Path $residentFailRoot 'resident-copy-observed.pid'
    $residentCopyStdout = Join-Path $residentFailRoot 'resident-copy.stdout.log'
    $residentCopyStderr = Join-Path $residentFailRoot 'resident-copy.stderr.log'
    $residentCopyFailure = & {
        function New-ProcessOutputCopyTasks {
            param($Process, $StdoutStream, $StderrStream)
            [void](Set-Content -LiteralPath $residentCopyObservedPid -Value $Process.Id)
            throw [System.InvalidOperationException]::new('自检注入：常驻进程复制初始化失败')
        }
        try {
            [void](Start-ResidentProcess -FilePath $pwshExe `
                    -ArgumentList @('-NoProfile', '-NonInteractive', '-File', $residentChild, $residentCopyPidMarker) `
                    -StdoutPath $residentCopyStdout `
                    -StderrPath $residentCopyStderr)
            [pscustomobject]@{ Threw = $false; Root = $null }
        }
        catch {
            [pscustomobject]@{ Threw = $true; Root = (Get-RootException -ErrorRecord $_) }
        }
    }
    Assert-True $residentCopyFailure.Threw '常驻：复制初始化失败时抛出错误'
    Assert-True ((Get-ExceptionTypeName -Exception $residentCopyFailure.Root) -eq 'InvalidOperationException') "常驻：复制初始化失败保留原始错误（实际 $(Get-ExceptionTypeName -Exception $residentCopyFailure.Root)）"
    Assert-True ((Get-ExceptionMessage -Exception $residentCopyFailure.Root).Contains('自检注入')) '常驻：复制初始化失败的原始消息未被清理遮住'
    $residentCopyChildId = Get-ProbeChildProcessId -PidPath $residentCopyObservedPid -TimeoutMs 2000
    Assert-True ($residentCopyChildId -gt 0) '常驻：复制初始化失败用例的探针子进程确实启动过'
    if ($residentCopyChildId -gt 0) {
        $residentCopyChildAlive = $null -ne (Get-Process -Id $residentCopyChildId -ErrorAction SilentlyContinue)
        Assert-True (-not $residentCopyChildAlive) "常驻：复制初始化失败后子进程 $residentCopyChildId 已被结束"
        if ($residentCopyChildAlive) {
            try { (Get-Process -Id $residentCopyChildId).Kill($true) } catch { }
        }
    }
    Assert-True (Test-FileIsReleased -Path $residentCopyStdout) '常驻：复制初始化失败后 stdout 日志流已释放'
    Assert-True (Test-FileIsReleased -Path $residentCopyStderr) '常驻：复制初始化失败后 stderr 日志流已释放'
}
finally {
    if (-not $PSBoundParameters.ContainsKey('WorkRoot')) {
        Remove-Item -LiteralPath $WorkRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($failures -gt 0) {
    Write-Host "[pg-test-process] $failures 项自检未通过。" -ForegroundColor Red
    exit 1
}
Write-Host "[pg-test-process] 全部自检通过。" -ForegroundColor Green
exit 0
