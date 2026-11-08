<#
.SYNOPSIS
    以“进程退出”为准的有界原生进程执行，供 run-pg-tests.ps1 使用。

.DESCRIPTION
    Windows 上不能用管道（`... | Tee-Object`）来判定一个常驻服务是否启动完成：
    postgres.exe 会继承 pg_ctl.exe 的标准输出/错误句柄，父进程即使已经退出，
    管道也不会收到 EOF，PowerShell 会一直等下去（第三轮问题 T3-2 的根因）。

    因此这里统一用 .NET Process：
      * 标准输出/错误重定向到独立文件，用异步复制持续排空管道，避免子进程写满缓冲区阻塞；
      * 等待只看 Process.WaitForExit（即进程本身退出），绝不等待管道 EOF；
      * 超时后结束整个进程树并返回 TimedOut，调用方据此判定“这条命令没有在期限内完成”；
      * 返回 ExitCode / TimedOut / Duration / StdoutPath / StderrPath / ProcessId，
        调用方不需要再读 $LASTEXITCODE，也不会因为后台句柄残留而挂住。

    参数用 ProcessStartInfo.ArgumentList 逐个传递，不拼接命令行字符串，
    因此带空格的路径与参数不会被拆开。

    常驻进程（postgres.exe）用 Start-ResidentProcess 启动：保留 Process 句柄但不等待退出，
    停止时由调用方先核对 PID 归属，再用 Close-ResidentProcess 回收句柄。

    资源生命周期（第四轮 T4-1）：
      * 两个日志流都在启动进程之前打开；第二个流打开失败时，先打开的流由 finally 释放，
        进程根本不会启动，因此日志 I/O 失败不会遗留子进程；
      * 打开日志、启动进程、初始化异步复制、等待、返回在同一个 try/finally 里；
        异常路径只结束本函数刚创建的那个进程及其子进程，并有界等待，绝不按名称或端口批量结束进程；
      * 常驻函数只在完整构造出返回对象后才把所有权交给调用方；失败路径先结束自己启动的进程树，
        再释放日志流与进程句柄，调用方拿不到半成品；
      * 清理里的每一步都单独 try/catch，保证不遮住原始异常（例如日志打开失败的真实错误）。
#>

Set-StrictMode -Version Latest

function New-BoundedProcessStartInfo {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [string]$WorkingDirectory
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    if ($WorkingDirectory) {
        $startInfo.WorkingDirectory = $WorkingDirectory
    }
    foreach ($argument in $ArgumentList) {
        [void]$startInfo.ArgumentList.Add($argument)
    }
    return $startInfo
}

function Open-LogStream {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $parent = [System.IO.Path]::GetDirectoryName($fullPath)
    if ($parent) {
        [void][System.IO.Directory]::CreateDirectory($parent)
    }
    return [System.IO.FileStream]::new(
        $fullPath,
        [System.IO.FileMode]::Create,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::ReadWrite)
}

<#
.SYNOPSIS
    结束本函数自己启动的进程及其子进程，并在有界时间内等待退出。

.DESCRIPTION
    只接受调用方持有的 Process 对象，不按进程名、端口或命令行批量筛选：
    绝不结束不是本次启动的进程。任何失败都吞掉，避免清理异常遮住原始异常。
#>
function Stop-OwnedProcessTree {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [ValidateRange(1, 600000)][int]$BoundedWaitMs = 15000
    )

    try {
        if (-not $Process.HasExited) {
            try { $Process.Kill($true) } catch { }
            [void]$Process.WaitForExit($BoundedWaitMs)
        }
    }
    catch { }
}

<#
.SYNOPSIS
    为已启动的进程创建两个异步输出复制任务。

.DESCRIPTION
    两个任务一起创建：任一初始化失败都视为“启动后失败”，由调用方的 finally 结束进程树。
    自检脚本会在局部作用域覆盖本函数，用来确定性地验证这条清理路径。
#>
function New-ProcessOutputCopyTasks {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory = $true)][System.IO.Stream]$StdoutStream,
        [Parameter(Mandatory = $true)][System.IO.Stream]$StderrStream
    )

    return [pscustomobject]@{
        StdoutCopy = $Process.StandardOutput.BaseStream.CopyToAsync($StdoutStream)
        StderrCopy = $Process.StandardError.BaseStream.CopyToAsync($StderrStream)
    }
}

<#
.SYNOPSIS
    运行一条短命令，并在超时后结束进程树。

.PARAMETER TimeoutSeconds
    等待上限；0 表示不设上限（只用于确定会退出的交互命令，不要用于常驻服务）。

.OUTPUTS
    PSCustomObject：ExitCode、TimedOut、Duration（TimeSpan）、DurationMs、StdoutPath、StderrPath、ProcessId。
#>
function Invoke-BoundedProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$StdoutPath,
        [Parameter(Mandatory = $true)][string]$StderrPath,
        [ValidateRange(0, 86400)][int]$TimeoutSeconds = 0,
        [string]$Description = ''
    )

    $stdoutFull = [System.IO.Path]::GetFullPath($StdoutPath)
    $stderrFull = [System.IO.Path]::GetFullPath($StderrPath)
    $startInfo = New-BoundedProcessStartInfo -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $WorkingDirectory

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $stdoutStream = $null
    $stderrStream = $null
    $stdoutCopy = $null
    $stderrCopy = $null
    $timedOut = $false
    $processStarted = $false
    $handedOff = $false
    $processId = 0
    $exitCode = $null
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

    try {
        # 先打开日志、再启动进程：日志路径不可写、被独占或其实是目录时在这里就失败，
        # 不会出现“进程已经启动、但打开日志抛错、没人负责结束它”的窗口。
        $stdoutStream = Open-LogStream -Path $stdoutFull
        $stderrStream = Open-LogStream -Path $stderrFull

        if (-not $process.Start()) {
            throw "无法启动进程 $FilePath"
        }
        $processStarted = $true
        $processId = $process.Id

        # 两个复制任务一起初始化；任何一步失败都会落到 finally 的进程树清理。
        $copies = New-ProcessOutputCopyTasks -Process $process -StdoutStream $stdoutStream -StderrStream $stderrStream
        $stdoutCopy = $copies.StdoutCopy
        $stderrCopy = $copies.StderrCopy

        if ($TimeoutSeconds -gt 0) {
            if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
                $timedOut = $true
                Stop-OwnedProcessTree -Process $process
            }
        }
        else {
            $process.WaitForExit()
        }

        if (-not $timedOut -and $process.HasExited) {
            $exitCode = $process.ExitCode
        }
        # 只有确认进程已经退出，才认为本次执行可以交给调用方；
        # 否则（例如超时后仍未结束）留给 finally 再尝试一次，绝不把活进程交回去。
        $handedOff = $process.HasExited
    }
    finally {
        $stopwatch.Stop()
        # 异常路径或未完成交接：只结束本函数刚创建的这个进程及其子进程，有界等待。
        if ($processStarted -and -not $handedOff) {
            Stop-OwnedProcessTree -Process $process
        }
        # 进程已退出时复制通常立即结束；若子进程仍持有继承来的句柄（常驻服务场景），
        # 这里只等有限时间就关闭文件，绝不无限等待管道 EOF。
        # 显式包装成数组：两个任务都为空时 $pending 仍是空数组，
        # StrictMode 下访问 Count 不会抛错，清理也就不会遮住原始异常。
        $pending = @(@($stdoutCopy, $stderrCopy) | Where-Object { $null -ne $_ })
        if ($pending.Count -gt 0) {
            try { [void][System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$pending, 2000) } catch { }
        }
        foreach ($stream in @($stdoutStream, $stderrStream)) {
            if ($null -ne $stream) {
                try { $stream.Dispose() } catch { }
            }
        }
        try { $process.Dispose() } catch { }
    }

    return [pscustomobject]@{
        Description = $Description
        ProcessId   = $processId
        ExitCode    = $exitCode
        TimedOut    = $timedOut
        Duration    = $stopwatch.Elapsed
        DurationMs  = [int]$stopwatch.Elapsed.TotalMilliseconds
        StdoutPath  = $stdoutFull
        StderrPath  = $stderrFull
    }
}

<#
.SYNOPSIS
    启动常驻进程并立即返回（不等待退出，也不等待管道 EOF）。

.OUTPUTS
    PSCustomObject：Process、ProcessId、StdoutPath、StderrPath、StdoutStream、StderrStream、
    StdoutCopy、StderrCopy（两个后台复制任务，Close-ResidentProcess 会做有界等待）。
#>
function Start-ResidentProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$StdoutPath,
        [Parameter(Mandatory = $true)][string]$StderrPath
    )

    $stdoutFull = [System.IO.Path]::GetFullPath($StdoutPath)
    $stderrFull = [System.IO.Path]::GetFullPath($StderrPath)
    $startInfo = New-BoundedProcessStartInfo -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $WorkingDirectory

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $stdoutStream = $null
    $stderrStream = $null
    $stdoutCopy = $null
    $stderrCopy = $null
    $processStarted = $false
    $handedOff = $false

    try {
        # 与短命令一致：日志先打开；第二个流失败时由 finally 释放第一个，进程不会启动。
        $stdoutStream = Open-LogStream -Path $stdoutFull
        $stderrStream = Open-LogStream -Path $stderrFull

        if (-not $process.Start()) {
            throw "无法启动进程 $FilePath"
        }
        $processStarted = $true

        # 必须持续排空输出管道：否则常驻进程写满缓冲区后会阻塞。
        $copies = New-ProcessOutputCopyTasks -Process $process -StdoutStream $stdoutStream -StderrStream $stderrStream
        $stdoutCopy = $copies.StdoutCopy
        $stderrCopy = $copies.StderrCopy

        $resident = [pscustomobject]@{
            Process      = $process
            ProcessId    = $process.Id
            StdoutPath   = $stdoutFull
            StderrPath   = $stderrFull
            StdoutStream = $stdoutStream
            StderrStream = $stderrStream
            StdoutCopy   = $stdoutCopy
            StderrCopy   = $stderrCopy
        }
        # 返回对象已完整构造，所有权在此交给调用方；finally 不再做任何清理。
        $handedOff = $true
        return $resident
    }
    finally {
        if (-not $handedOff) {
            # 失败路径：调用方拿不到半成品，这里负责结束自己启动的进程树并释放全部句柄。
            if ($processStarted) {
                Stop-OwnedProcessTree -Process $process
            }
            $pending = @(@($stdoutCopy, $stderrCopy) | Where-Object { $null -ne $_ })
            if ($pending.Count -gt 0) {
                try { [void][System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$pending, 2000) } catch { }
            }
            foreach ($stream in @($stdoutStream, $stderrStream)) {
                if ($null -ne $stream) {
                    try { $stream.Dispose() } catch { }
                }
            }
            try { $process.Dispose() } catch { }
        }
    }
}

<#
.SYNOPSIS
    回收常驻进程句柄：进程已退出时立即返回，未退出时只等待有限时间，绝不无限阻塞。
#>
function Close-ResidentProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Resident,
        [ValidateRange(0, 600)][int]$TimeoutSeconds = 15
    )

    $process = $Resident.Process
    try {
        if ($process -and -not $process.HasExited) {
            [void]$process.WaitForExit($TimeoutSeconds * 1000)
        }
    }
    catch { }
    # 后台复制任务通常随管道 EOF 立即结束；子进程可能继承句柄继续持有写端，
    # 所以只做有界等待，随后照常关闭日志文件，避免无限等待。
    $pending = @(@($Resident.StdoutCopy, $Resident.StderrCopy) | Where-Object { $null -ne $_ })
    if ($pending.Count -gt 0) {
        try { [void][System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$pending, 2000) } catch { }
    }
    foreach ($stream in @($Resident.StdoutStream, $Resident.StderrStream)) {
        if ($null -ne $stream) {
            try { $stream.Dispose() } catch { }
        }
    }
    try { if ($process) { $process.Dispose() } } catch { }
}
