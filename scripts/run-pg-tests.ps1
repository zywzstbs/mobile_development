<#
.SYNOPSIS
    在 Windows 上用一次性隔离的 PostgreSQL 实例运行本项目真实数据库测试（默认 `mvnw verify`）。

.DESCRIPTION
    与 Linux 的 scripts/run-pg-tests.sh 对齐，但使用 Windows 原生 PostgreSQL 可执行文件、
    TCP 回环连接和 PowerShell 参数数组（不拼接命令行字符串）。

    生命周期与阶段日志（每阶段打印开始/结束与耗时，并写入运行目录的 stages.log）：
      PRECHECK → INITDB → START_SERVER → WAIT_READY → CREATE_DATABASE → RUN_MAVEN
      → STOP_SERVER → RESTORE_ENV → CLEANUP → EXIT

    行为：
      1. 校验 JDK 21、Maven 3.9+、Windows 原生 initdb.exe/pg_ctl.exe/pg_isready.exe/createdb.exe
         以及 PostgreSQL 版本（本项目测试库要求 17，见 docs/Windows开发.md），缺失时明确失败，
         不会自动安装全局服务。
      2. 只在 `target/pg-tests-windows/<runId>/` 下创建本次运行独占的数据、日志目录，
         不接管任何已有数据库或数据目录。
      3. 只监听 127.0.0.1 并使用显式端口；端口被占用时直接失败，不停止别人的进程，
         也不会悄悄改连已有服务。
      4. 初始化 UTF-8 实例、创建专用测试库 `gulupai_test`，连接串只构建一次，
         导出 TEST_DB_URL/USERNAME/PASSWORD，完成初始化后才启动 Maven。
      5. 使用 trust 认证：该实例是一次性隔离实例、只绑定回环、用完即停；本机其他进程在运行
         窗口内也能连上它，因此不能把本脚本的实例当作生产或共享测试环境配置。
      6. 所有短命令（initdb、pg_ctl、pg_isready、createdb、停止）都经 pg-test-process.ps1 的
         有界执行：等待以“进程退出”为准，绝不等待管道 EOF；超时结束整个进程树并判定该阶段失败。
         这是第三轮问题 T3-2 的修复点——postgres 会继承 pg_ctl 的标准句柄，用管道判定结束会永久阻塞。
      7. pg_ctl start 正常时走标准路径；若日志显示受限环境无法创建继承套接字（错误码 87），
         回退为直接启动 postgres.exe，保留进程句柄但不等待退出，就绪仍由 pg_isready 判定。
      8. WAIT_READY 用有界 pg_isready 探测：单次探测不超过剩余期限，外层期限到期时探测进程本身
         会被结束，不会留下悬空进程。
      9. 从任意当前目录调用都能运行：预检先解析脚本自身所在仓库，Maven 显式使用仓库
         `pom.xml` 启动，并在 finally 中恢复调用者的当前目录。
     10. 保留 Maven 退出码：测试失败时脚本非 0 退出，finally 里的停止/清理不会覆盖它。
     11. finally 只停止本次数据目录对应的实例：先用 postmaster.pid 核对数据目录，再结合进程句柄、
         创建时间、可执行路径与命令行确认 PID 归属；任何一项无法证明就拒绝停止并保留目录。
         不按端口或进程名误停别人的服务。
     12. 数据目录默认删除（-KeepData 保留）。停止或删除失败时保留目录并打印诊断，
         测试结果与清理失败分开报告，清理失败以退出码 3 结束。
     13. 删除前解析绝对路径并确认严格位于本次运行目录内。
     14. 不读取 .env，也不从外部继承业务 DB_URL 当作测试地址。

    退出码：
      0  测试通过且清理完成
      2  环境/预检/起库/建库失败（未进入或未完成 Maven）
      3  Maven 通过，但实例停止或运行目录清理未完成
      其他  Maven 的真实退出码（测试失败时不改写）

.PARAMETER PgBin
    PostgreSQL 的 bin 目录。缺省依次取 $env:PG_BIN、仓库 .tools/pg/bin、PATH 中的 initdb.exe。

.PARAMETER Port
    PostgreSQL 监听端口，默认 55441（与 Linux 脚本一致）。只监听 127.0.0.1。

.PARAMETER MavenArgs
    传给 Maven 的参数数组，默认 @('verify')。例如 @('verify','-Dtest=PlatformAdminIT')。

.PARAMETER JavaHome
    JDK 21 目录。缺省依次取 $env:JAVA_HOME、仓库 .tools/jdk*、PATH 中的 java.exe。

.PARAMETER Maven
    mvn.cmd 的完整路径。缺省依次取 $env:MAVEN_HOME\bin\mvn.cmd、仓库 .tools/apache-maven-*/bin/mvn.cmd、
    仓库 mvnw.cmd、PATH 中的 mvn.cmd。

.PARAMETER StartupTimeoutSeconds
    等待数据库就绪的秒数上限，默认 60。

.PARAMETER StartMode
    启动方式：Auto（默认，先用 pg_ctl，日志显示无法创建继承套接字时回退直启 postgres.exe）、
    PgCtl（只用 pg_ctl，失败即失败）、Direct（直接启动 postgres.exe）。
    Direct 用于验证回退路径，也用于 pg_ctl 在该环境完全不可用的机器。

.PARAMETER InitdbTimeoutSeconds
    initdb 的秒数上限，默认 300。

.PARAMETER StopTimeoutSeconds
    停止实例的秒数上限，默认 60。

.PARAMETER KeepData
    保留本次运行的数据与日志目录用于排查。

.EXAMPLE
    pwsh -File scripts/run-pg-tests.ps1 -PgBin 'C:\Program Files\PostgreSQL\17\bin'

.EXAMPLE
    pwsh -File scripts/run-pg-tests.ps1 -MavenArgs verify,-Dtest=TestSchemaCleanerTest
#>
[CmdletBinding()]
param(
    [string]$PgBin,
    [ValidateRange(1, 65535)]
    [int]$Port = 55441,
    [string]$JavaHome,
    [string]$Maven,
    [string[]]$MavenArgs = @('verify'),
    [ValidateSet('Auto', 'PgCtl', 'Direct')]
    [string]$StartMode = 'Auto',
    [ValidateRange(1, 600)]
    [int]$StartupTimeoutSeconds = 60,
    [ValidateRange(10, 3600)]
    [int]$InitdbTimeoutSeconds = 300,
    [ValidateRange(5, 600)]
    [int]$StopTimeoutSeconds = 60,
    [switch]$KeepData
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'pg-test-process.ps1')
. (Join-Path $PSScriptRoot 'pg-test-url.ps1')

# ---------------------------------------------------------------------------
# 阶段日志
# ---------------------------------------------------------------------------
$script:stageLines = [System.Collections.Generic.List[string]]::new()
$script:stageLogPath = $null
$script:stageStarted = [DateTime]::UtcNow
$script:stageName = 'BOOT'

function Write-LogLine {
    param([string]$Line)
    $script:stageLines.Add($Line)
    Write-Host "[pg-tests] $Line" -ForegroundColor Cyan
    if ($script:stageLogPath) {
        try { Add-Content -LiteralPath $script:stageLogPath -Value $Line -Encoding utf8 } catch { }
    }
}

function Write-WarnLine {
    param([string]$Line)
    Write-LogLine $Line
}

function Enter-Stage {
    param([string]$Name)
    $script:stageName = $Name
    $script:stageStarted = [DateTime]::UtcNow
    Write-LogLine ("[{0}] 开始" -f $Name)
}

function Exit-Stage {
    param([string]$Name, [string]$Outcome = '成功')
    $elapsed = [int]([DateTime]::UtcNow - $script:stageStarted).TotalMilliseconds
    Write-LogLine ("[{0}] {1}，耗时 {2} ms" -f $Name, $Outcome, $elapsed)
}

function Fail {
    param([string]$Message)
    throw [System.InvalidOperationException]::new($Message)
}

function Read-LogFile {
    param([string]$Path)
    if (-not $Path -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return ''
    }
    # Get-Content -Raw 对空文件返回 $null；统一成空字符串，调用方才能安全 Trim。
    $text = Get-Content -LiteralPath $Path -Raw -ErrorAction SilentlyContinue
    if ($null -eq $text) {
        return ''
    }
    return [string]$text
}

function Resolve-FirstDirectory {
    param([string[]]$Candidates)
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Resolve-FirstFile {
    param([string[]]$Candidates)
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Assert-InsideDirectory {
    param([string]$Path, [string]$Parent, [string]$Purpose)
    $resolved = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝$Purpose：$resolved 不在专用目录 $root 内"
    }
}

<#
.SYNOPSIS
    把路径统一成可比较的形式。

.DESCRIPTION
    postgres 记录的 postmaster.pid 与进程命令行里可能是正斜杠（D:/code/...），
    而 PowerShell 解析出来是反斜杠（D:\code\...）。比较前统一分隔符并去掉末尾分隔符，
    避免把“自己的实例”误判成“别人的进程”。
#>
function ConvertTo-NormalizedPath {
    param([string]$Path)
    $full = [System.IO.Path]::GetFullPath($Path)
    return $full.Replace('/', '\').TrimEnd('\')
}

<#
.SYNOPSIS
    只停止“本次数据目录”对应的实例。

.DESCRIPTION
    不按端口、进程名或全局服务列表判断。先读 postmaster.pid，要求其中记录的数据目录与本次
    数据目录一致；再用进程句柄、创建时间（postmaster.pid 第三行）、可执行路径与命令行核对
    PID 归属。无法证明归属时拒绝停止并保留目录，由调用方报告清理失败（退出码 3）。

    停止顺序：pg_ctl stop（有界）→ 进程仍在则结束已核实的进程树（有界等待）。
#>
function Stop-OwnPgInstance {
    param(
        [Parameter(Mandatory = $true)][string]$DataDir,
        [Parameter(Mandatory = $true)][string]$PgCtlExe,
        [Parameter(Mandatory = $true)][string]$PostgresExe,
        [Parameter(Mandatory = $true)][string]$LogDir,
        [ValidateRange(5, 600)][int]$TimeoutSeconds = 60
    )

    $pidFile = Join-Path $DataDir 'postmaster.pid'
    if (-not (Test-Path -LiteralPath $pidFile -PathType Leaf)) {
        return [pscustomobject]@{ Stopped = $true; Reason = '实例未运行（无 postmaster.pid）' }
    }

    $lines = @(Get-Content -LiteralPath $pidFile -TotalCount 8 -ErrorAction SilentlyContinue)
    if ($lines.Count -lt 2) {
        return [pscustomobject]@{ Stopped = $false; Reason = "无法解析 $pidFile，保留数据目录" }
    }

    $serverPid = 0
    if (-not [int]::TryParse($lines[0].Trim(), [ref]$serverPid) -or $serverPid -le 0) {
        return [pscustomobject]@{ Stopped = $false; Reason = "无法解析 $pidFile 中的 PID，保留数据目录" }
    }

    $expectedDir = ConvertTo-NormalizedPath -Path $DataDir
    $recordedDir = ''
    try {
        $recordedDir = ConvertTo-NormalizedPath -Path $lines[1].Trim()
    }
    catch {
        return [pscustomobject]@{ Stopped = $false; Reason = "postmaster.pid 记录的数据目录无法解析，保留数据目录" }
    }
    if (-not [string]::Equals($recordedDir, $expectedDir, [System.StringComparison]::OrdinalIgnoreCase)) {
        return [pscustomobject]@{ Stopped = $false; Reason = "postmaster.pid 属于 $recordedDir，不是本次目录，拒绝停止" }
    }

    $process = Get-Process -Id $serverPid -ErrorAction SilentlyContinue
    if (-not $process) {
        return [pscustomobject]@{ Stopped = $true; Reason = "PID $serverPid 已退出" }
    }

    $problems = [System.Collections.Generic.List[string]]::new()
    $executablePath = ''
    try { $executablePath = [string]$process.Path } catch { $executablePath = '' }
    if (-not $executablePath) {
        $problems.Add('无法读取进程可执行路径')
    }
    elseif (-not [string]::Equals($executablePath, $PostgresExe, [System.StringComparison]::OrdinalIgnoreCase)) {
        $problems.Add("可执行路径 $executablePath 不是 $PostgresExe")
    }

    if ($lines.Count -ge 3) {
        $recordedStart = 0L
        if ([long]::TryParse($lines[2].Trim(), [ref]$recordedStart) -and $recordedStart -gt 0) {
            $expectedStart = [System.DateTimeOffset]::FromUnixTimeSeconds($recordedStart).LocalDateTime
            try {
                $actualStart = $process.StartTime
                if ([math]::Abs(($actualStart - $expectedStart).TotalSeconds) -gt 5) {
                    $problems.Add("进程创建时间 $($actualStart.ToString('s')) 与 postmaster.pid 记录 $($expectedStart.ToString('s')) 不符")
                }
            }
            catch {
                $problems.Add('无法读取进程创建时间')
            }
        }
        else {
            $problems.Add('postmaster.pid 的启动时间无法解析')
        }
    }

    $commandLine = $null
    try {
        $cim = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $serverPid" -ErrorAction Stop
        if ($cim) { $commandLine = [string]$cim.CommandLine }
    }
    catch {
        $commandLine = $null
    }
    if ($commandLine) {
        $normalizedCommandLine = $commandLine.Replace('/', '\')
        if ($normalizedCommandLine.IndexOf($expectedDir, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $problems.Add('进程命令行未引用本次数据目录')
        }
    }
    else {
        Write-WarnLine "[STOP_SERVER] 提示：无法读取 PID $serverPid 的命令行（WMI/CIM 不可用），改用句柄、创建时间与数据目录核对。"
    }

    if ($problems.Count -gt 0) {
        return [pscustomobject]@{ Stopped = $false; Reason = ("拒绝停止 PID {0}：{1}" -f $serverPid, ($problems -join '；')) }
    }

    $stopLog = Join-Path $LogDir 'pg_ctl-stop.log'
    $stop = Invoke-BoundedProcess -FilePath $PgCtlExe `
        -ArgumentList @('-D', $DataDir, '-m', 'immediate', '-w', '-t', "$([math]::Max(5, $TimeoutSeconds - 15))", 'stop') `
        -TimeoutSeconds $TimeoutSeconds `
        -StdoutPath (Join-Path $LogDir 'pg_ctl-stop.stdout.log') `
        -StderrPath $stopLog `
        -Description 'pg_ctl stop'

    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) {
            return [pscustomobject]@{ Stopped = $true; Reason = "pg_ctl stop 已停止 PID $serverPid" }
        }
        Start-Sleep -Milliseconds 200
    }

    # pg_ctl 未能停止（受限环境或实例无响应）：已核实归属，结束该进程树。
    $live = Get-Process -Id $serverPid -ErrorAction SilentlyContinue
    if ($live) {
        Write-WarnLine "[STOP_SERVER] pg_ctl stop 未生效（退出码 $($stop.ExitCode)，超时 $($stop.TimedOut)），改为结束已核实的进程树 PID $serverPid。"
        try { $live.Kill($true) } catch { }
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline) {
            if (-not (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) {
                return [pscustomobject]@{ Stopped = $true; Reason = "已结束进程树 PID $serverPid" }
            }
            Start-Sleep -Milliseconds 200
        }
    }

    return [pscustomobject]@{ Stopped = $false; Reason = "无法停止本次实例（PID $serverPid），保留数据目录与日志 $LogDir" }
}

# ---------------------------------------------------------------------------
# 在修改任何环境变量之前保存现场；预检失败也会经过 finally 恢复。
# ---------------------------------------------------------------------------
$previousEnvironment = @{}
foreach ($name in @('TEST_DB_URL', 'TEST_DB_USERNAME', 'TEST_DB_PASSWORD', 'MAVEN_OPTS', 'JAVA_HOME',
        'MAVEN_USER_HOME')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$mavenExit = $null
$failureMessage = $null
$script:failureStack = $null
$cleanupFailed = $false
$serverStarted = $false
$directPostgresStart = $false
$resident = $null
$dataDir = $null
$logDir = $null
$runRoot = $null
$runParent = $null
$pgCtlExe = $null
$postgresExe = $null
$databaseName = 'gulupai_test'

try {
    # -----------------------------------------------------------------------
    # PRECHECK：解析工具链、检查端口、建立本次运行目录
    # -----------------------------------------------------------------------
    Enter-Stage 'PRECHECK'

    $repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
    Write-LogLine "仓库根目录：$repoRoot"

    $runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + ([guid]::NewGuid().ToString('N').Substring(0, 8))
    $runParent = Join-Path $repoRoot 'target\pg-tests-windows'
    $runRoot = Join-Path $runParent $runId
    $dataDir = Join-Path $runRoot 'data'
    $logDir = Join-Path $runRoot 'log'
    [void][System.IO.Directory]::CreateDirectory($dataDir)
    [void][System.IO.Directory]::CreateDirectory($logDir)
    $script:stageLogPath = Join-Path $logDir 'stages.log'
    foreach ($buffered in $script:stageLines) {
        Add-Content -LiteralPath $script:stageLogPath -Value $buffered -Encoding utf8
    }
    Write-LogLine "本次运行目录：$runRoot"

    $toolsRoot = Join-Path $repoRoot '.tools'
    $jdkCandidates = @(
        $JavaHome,
        $env:JAVA_HOME,
        (Get-ChildItem -LiteralPath $toolsRoot -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object { $_.FullName })
    ) | Where-Object { $_ }
    $jdkRoot = Resolve-FirstDirectory -Candidates $jdkCandidates
    $javaExe = $null
    if ($jdkRoot) {
        $javaExe = Resolve-FirstFile -Candidates @((Join-Path $jdkRoot 'bin\java.exe'))
    }
    if (-not $javaExe) {
        $javaCommand = Get-Command 'java.exe' -ErrorAction SilentlyContinue
        if ($javaCommand) { $javaExe = $javaCommand.Source }
    }
    if (-not $javaExe) {
        Fail "找不到 JDK 21。请安装 JDK 21，或设置 JAVA_HOME，或用 -JavaHome 指定目录。"
    }

    $javaProbe = Invoke-BoundedProcess -FilePath $javaExe -ArgumentList @('-version') -TimeoutSeconds 30 `
        -StdoutPath (Join-Path $logDir 'java-version.stdout.log') `
        -StderrPath (Join-Path $logDir 'java-version.stderr.log') `
        -Description 'java -version'
    $javaVersionOutput = ((Read-LogFile $javaProbe.StdoutPath) + (Read-LogFile $javaProbe.StderrPath))
    if ($javaProbe.TimedOut) {
        Fail "执行 $javaExe -version 超过 30 秒未返回，已结束该进程。"
    }
    if ($javaProbe.ExitCode -ne 0) {
        Fail "无法执行 $javaExe -version（退出码 $($javaProbe.ExitCode)）：$($javaVersionOutput.Trim())"
    }
    if ($javaVersionOutput -notmatch 'version "(?<major>\d+)') {
        Fail "无法解析 JDK 版本：$($javaVersionOutput.Trim())"
    }
    if ([int]$Matches['major'] -ne 21) {
        Fail "本项目要求 JDK 21，当前 $javaExe 版本为 $($javaVersionOutput.Trim())。"
    }
    Write-LogLine "JDK：$javaExe"

    $resolvedJavaHome = $jdkRoot
    if (-not $resolvedJavaHome) {
        $candidateJavaHome = Split-Path -Parent (Split-Path -Parent $javaExe)
        if (Test-Path -LiteralPath (Join-Path $candidateJavaHome 'bin\java.exe') -PathType Leaf) {
            $resolvedJavaHome = $candidateJavaHome
        }
    }
    if ($resolvedJavaHome) {
        # mvnw.cmd 与 Maven 都依赖 JAVA_HOME；仅在本次脚本进程内设置，finally 中恢复。
        $env:JAVA_HOME = $resolvedJavaHome
    }

    $mvnCandidates = @(
        $(if ($Maven) { $Maven }),
        $(if ($env:MAVEN_HOME) { Join-Path $env:MAVEN_HOME 'bin\mvn.cmd' }),
        (Get-ChildItem -LiteralPath $toolsRoot -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'bin\mvn.cmd' }),
        (Join-Path $repoRoot 'mvnw.cmd')
    ) | Where-Object { $_ }
    $mvnExe = Resolve-FirstFile -Candidates $mvnCandidates
    if (-not $mvnExe) {
        $mvnCommand = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
        if ($mvnCommand) { $mvnExe = $mvnCommand.Source }
    }
    if (-not $mvnExe) {
        Fail "找不到 Maven。请安装 Maven 3.9+，或设置 MAVEN_HOME，或用 -Maven 指定 mvn.cmd。"
    }
    if ($mvnExe -like '*mvnw.cmd' -and -not $env:MAVEN_USER_HOME) {
        $mavenUserHome = Join-Path $repoRoot '.tools\maven-home'
        [void][System.IO.Directory]::CreateDirectory($mavenUserHome)
        $env:MAVEN_USER_HOME = $mavenUserHome
        Write-LogLine "使用仓库内 Maven 用户目录：$mavenUserHome"
    }

    $mavenProbe = Invoke-BoundedProcess -FilePath $mvnExe -ArgumentList @('-v') -TimeoutSeconds 120 `
        -StdoutPath (Join-Path $logDir 'maven-version.stdout.log') `
        -StderrPath (Join-Path $logDir 'maven-version.stderr.log') `
        -Description 'mvn -v'
    $mavenVersionOutput = ((Read-LogFile $mavenProbe.StdoutPath) + (Read-LogFile $mavenProbe.StderrPath))
    if ($mavenProbe.TimedOut) {
        Fail "执行 $mvnExe -v 超过 120 秒未返回，已结束该进程。"
    }
    if ($mavenProbe.ExitCode -ne 0) {
        Fail "无法执行 $mvnExe -v（退出码 $($mavenProbe.ExitCode)）：`n$($mavenVersionOutput.Trim())"
    }
    if ($mavenVersionOutput -notmatch 'Apache Maven (?<version>\d+\.\d+\.\d+)') {
        Fail "无法解析 Maven 版本（$mvnExe）：`n$($mavenVersionOutput.Trim())"
    }
    $mavenVersion = [version]$Matches['version']
    if ($mavenVersion -lt [version]'3.9.0') {
        Fail "本项目要求 Maven 3.9+，当前 $mvnExe 版本为 $mavenVersion。"
    }
    Write-LogLine "Maven：$mvnExe（$mavenVersion）"

    $pgBinCandidates = @(
        $PgBin,
        $env:PG_BIN,
        (Join-Path $toolsRoot 'pg\bin')
    ) | Where-Object { $_ }
    $pgRoot = Resolve-FirstDirectory -Candidates $pgBinCandidates
    if (-not $pgRoot) {
        $pgCommand = Get-Command 'initdb.exe' -ErrorAction SilentlyContinue
        if ($pgCommand) { $pgRoot = Split-Path -Parent $pgCommand.Source }
    }
    if (-not $pgRoot) {
        Fail "找不到 Windows 原生 PostgreSQL。请安装 PostgreSQL 17，或用 -PgBin 指定其 bin 目录（例如 'C:\Program Files\PostgreSQL\17\bin'）。"
    }

    $initdbExe = Join-Path $pgRoot 'initdb.exe'
    $pgCtlExe = Join-Path $pgRoot 'pg_ctl.exe'
    $pgIsReadyExe = Join-Path $pgRoot 'pg_isready.exe'
    $createDbExe = Join-Path $pgRoot 'createdb.exe'
    $postgresExe = Join-Path $pgRoot 'postgres.exe'
    foreach ($tool in @($initdbExe, $pgCtlExe, $pgIsReadyExe, $createDbExe, $postgresExe)) {
        if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
            Fail "缺少 Windows 原生 PostgreSQL 工具 $tool。请用 -PgBin 指向完整安装的 PostgreSQL 17 bin 目录。"
        }
    }

    $pgVersionProbe = Invoke-BoundedProcess -FilePath $pgCtlExe -ArgumentList @('--version') -TimeoutSeconds 30 `
        -StdoutPath (Join-Path $logDir 'pg-version.stdout.log') `
        -StderrPath (Join-Path $logDir 'pg-version.stderr.log') `
        -Description 'pg_ctl --version'
    $pgVersionOutput = ((Read-LogFile $pgVersionProbe.StdoutPath) + (Read-LogFile $pgVersionProbe.StderrPath))
    if ($pgVersionProbe.TimedOut) {
        Fail "执行 $pgCtlExe --version 超过 30 秒未返回，已结束该进程。"
    }
    if ($pgVersionProbe.ExitCode -ne 0) {
        Fail "无法执行 $pgCtlExe --version（退出码 $($pgVersionProbe.ExitCode)）：$($pgVersionOutput.Trim())"
    }
    if ($pgVersionOutput -notmatch 'PostgreSQL\)\s+(?<major>\d+)') {
        Fail "无法解析 PostgreSQL 版本：$($pgVersionOutput.Trim())"
    }
    $pgMajor = [int]$Matches['major']
    if ($pgMajor -lt 17) {
        Fail "本项目测试库要求 PostgreSQL 17（见 docs/Windows开发.md），当前 $pgRoot 为 $($pgVersionOutput.Trim())。"
    }
    Write-LogLine "PostgreSQL：$pgRoot（$($pgVersionOutput.Trim())）"

    $portOwners = @()
    if (Get-Command 'Get-NetTCPConnection' -ErrorAction SilentlyContinue) {
        $portOwners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    }
    else {
        try {
            $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
            $probe.Start()
            $probe.Stop()
        }
        catch {
            $portOwners = @('未知进程')
        }
    }
    if ($portOwners.Count -gt 0) {
        $owners = ($portOwners | ForEach-Object { if ($_ -is [string]) { $_ } else { "PID $($_.OwningProcess)" } } | Sort-Object -Unique) -join '、'
        Fail "端口 $Port 已被占用（$owners）。脚本不会停止别人的服务，也不会改连已有数据库；请用 -Port 指定空闲端口。"
    }

    Exit-Stage 'PRECHECK'

    # -----------------------------------------------------------------------
    # INITDB
    # -----------------------------------------------------------------------
    Enter-Stage 'INITDB'
    $initdbLog = Join-Path $logDir 'initdb.log'
    $initdbResult = Invoke-BoundedProcess -FilePath $initdbExe `
        -ArgumentList @('-D', $dataDir, '-U', 'postgres', '-A', 'trust', '-E', 'UTF8', '--locale=C') `
        -TimeoutSeconds $InitdbTimeoutSeconds `
        -StdoutPath (Join-Path $logDir 'initdb.stdout.log') `
        -StderrPath $initdbLog `
        -Description 'initdb'
    if ($initdbResult.TimedOut) {
        Fail "initdb 超过 $InitdbTimeoutSeconds 秒未完成，已结束该进程；日志：$initdbLog"
    }
    if ($initdbResult.ExitCode -ne 0) {
        Fail "initdb 初始化失败（退出码 $($initdbResult.ExitCode)），日志：$initdbLog"
    }
    Exit-Stage 'INITDB'

    # -----------------------------------------------------------------------
    # START_SERVER
    # -----------------------------------------------------------------------
    Enter-Stage 'START_SERVER'
    $pgLog = Join-Path $logDir 'postgres.log'
    $serverOptions = "-p $Port -c listen_addresses=127.0.0.1 -c fsync=off -c max_connections=100"
    $startLog = Join-Path $logDir 'pg_ctl-start.log'
    $useDirectStart = $StartMode -eq 'Direct'
    if ($StartMode -eq 'Direct') {
        Write-LogLine "按 -StartMode Direct 跳过 pg_ctl，直接启动 postgres.exe。"
    }
    else {
        $startResult = Invoke-BoundedProcess -FilePath $pgCtlExe `
            -ArgumentList @('-D', $dataDir, '-l', $pgLog, '-o', $serverOptions, '-w', '-t', "$StartupTimeoutSeconds", 'start') `
            -TimeoutSeconds ($StartupTimeoutSeconds + 30) `
            -StdoutPath (Join-Path $logDir 'pg_ctl-start.stdout.log') `
            -StderrPath $startLog `
            -Description 'pg_ctl start'

        if ($startResult.TimedOut) {
            # 第三轮问题 T3-2 的原始现象：pg_ctl 已经启动数据库，但脚本在管道上永久等待。
            # 现在超时由 helper 结束进程树，这里明确失败，不再静默卡住。
            Fail "pg_ctl start 在 $($StartupTimeoutSeconds + 30) 秒内未返回，已结束该进程；日志：$startLog、$pgLog"
        }
        if ($startResult.ExitCode -eq 0) {
            $serverStarted = $true
            Write-LogLine "pg_ctl start 成功（耗时 $($startResult.DurationMs) ms）"
        }
        else {
            $startOutput = (Read-LogFile $startLog) + (Read-LogFile (Join-Path $logDir 'pg_ctl-start.stdout.log'))
            if ($StartMode -eq 'PgCtl' -or $startOutput -notmatch '继承套接字|inherited socket') {
                Fail "pg_ctl start 失败（退出码 $($startResult.ExitCode)），日志：$startLog、$pgLog"
            }
            # 受限/沙箱环境不允许 pg_ctl 创建继承套接字：改为直接启动 postgres.exe，
            # 就绪由 pg_isready 判断；保留进程句柄但不等待退出，停止时按本次 postmaster.pid 校验后停进程。
            Write-WarnLine "pg_ctl 无法创建继承套接字，改用直接启动 postgres.exe。"
            $useDirectStart = $true
        }
    }

    if ($useDirectStart) {
        $resident = Start-ResidentProcess -FilePath $postgresExe `
            -ArgumentList @('-D', $dataDir, '-p', "$Port", '-c', 'listen_addresses=127.0.0.1',
                '-c', 'fsync=off', '-c', 'max_connections=100') `
            -StdoutPath (Join-Path $logDir 'postgres.stdout.log') `
            -StderrPath $pgLog
        Start-Sleep -Milliseconds 800
        $resident.Process.Refresh()
        if ($resident.Process.HasExited) {
            $tail = (Read-LogFile $pgLog).Trim()
            Fail "直接启动 postgres.exe 失败（退出码 $($resident.Process.ExitCode)）：`n$tail"
        }
        $directPostgresStart = $true
        $serverStarted = $true
        Write-LogLine "已直接启动 postgres.exe（PID $($resident.ProcessId)），等待 pg_isready 就绪。"
    }
    Exit-Stage 'START_SERVER'

    # -----------------------------------------------------------------------
    # WAIT_READY
    # -----------------------------------------------------------------------
    Enter-Stage 'WAIT_READY'
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    $ready = $false
    $attempt = 0
    while ($true) {
        $remainingMs = [int](($deadline - (Get-Date)).TotalMilliseconds)
        if ($remainingMs -le 0) {
            break
        }
        $attempt++
        # 单次探测不超过剩余期限，也不超过 5 秒：探测本身同样是有界进程。
        $probeTimeout = [math]::Max(1, [int][math]::Ceiling([math]::Min(5.0, $remainingMs / 1000.0)))
        $probe = Invoke-BoundedProcess -FilePath $pgIsReadyExe `
            -ArgumentList @('-h', '127.0.0.1', '-p', "$Port", '-q') `
            -TimeoutSeconds $probeTimeout `
            -StdoutPath (Join-Path $logDir 'pg_isready.stdout.log') `
            -StderrPath (Join-Path $logDir 'pg_isready.stderr.log') `
            -Description 'pg_isready'
        if (-not $probe.TimedOut -and $probe.ExitCode -eq 0) {
            $ready = $true
            break
        }
        if ($resident) {
            $resident.Process.Refresh()
            if ($resident.Process.HasExited) {
                Fail "直接启动的 postgres.exe 已退出（退出码 $($resident.Process.ExitCode)），日志：$pgLog"
            }
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) {
        Fail "数据库在 $StartupTimeoutSeconds 秒内未就绪（探测 $attempt 次），诊断日志：$pgLog、$(Join-Path $logDir 'pg_isready.stderr.log')"
    }
    Write-LogLine "数据库已就绪（第 $attempt 次探测）。"
    Exit-Stage 'WAIT_READY'

    # -----------------------------------------------------------------------
    # CREATE_DATABASE
    # -----------------------------------------------------------------------
    Enter-Stage 'CREATE_DATABASE'
    $createdbResult = Invoke-BoundedProcess -FilePath $createDbExe `
        -ArgumentList @('-h', '127.0.0.1', '-p', "$Port", '-U', 'postgres', $databaseName) `
        -TimeoutSeconds 60 `
        -StdoutPath (Join-Path $logDir 'createdb.stdout.log') `
        -StderrPath (Join-Path $logDir 'createdb.stderr.log') `
        -Description 'createdb'
    if ($createdbResult.TimedOut) {
        Fail "创建测试库 $databaseName 超过 60 秒未返回，已结束该进程。"
    }
    if ($createdbResult.ExitCode -ne 0) {
        $createdbError = (Read-LogFile $createdbResult.StderrPath).Trim()
        Fail "创建测试库 $databaseName 失败（退出码 $($createdbResult.ExitCode)）：$createdbError"
    }

    # 连接串只构建一次；字符串里必须用 ${databaseName}，否则 PowerShell 会把
    # `$databaseName?currentSchema` 当成一个变量名，StrictMode 下直接失败。
    $testDbUrl = New-PgTestDatabaseUrl -DatabaseName $databaseName -Port $Port
    Write-LogLine "测试实例已就绪：$testDbUrl"
    Exit-Stage 'CREATE_DATABASE'

    # -----------------------------------------------------------------------
    # RUN_MAVEN
    # -----------------------------------------------------------------------
    Enter-Stage 'RUN_MAVEN'
    $env:TEST_DB_URL = $testDbUrl
    $env:TEST_DB_USERNAME = 'postgres'
    $env:TEST_DB_PASSWORD = ''
    $env:MAVEN_OPTS = ''

    $mavenLocalRepo = Join-Path $repoRoot '.tools\m2'
    $mockitoAgent = Join-Path $mavenLocalRepo 'org\mockito\mockito-core\5.17.0\mockito-core-5.17.0.jar'
    $argumentList = [System.Collections.Generic.List[string]]::new()
    $argumentList.Add('-o')
    $argumentList.Add('-f')
    $argumentList.Add((Join-Path $repoRoot 'pom.xml'))
    $argumentList.Add("-Dmaven.repo.local=$mavenLocalRepo")
    if (Test-Path -LiteralPath $mockitoAgent -PathType Leaf) {
        $agentPath = $mockitoAgent.Replace('\', '/')
        $argumentList.Add("-DargLine=-javaagent:$agentPath")
    }
    else {
        Write-WarnLine "未找到 $mockitoAgent，mvnw 将不使用 mockito javaagent。"
    }
    foreach ($mavenArgument in $MavenArgs) {
        $argumentList.Add($mavenArgument)
    }

    # target 是可丢弃的构建产物：若受限环境遗留了只读位，构建前清掉，保证脚本可重复运行。
    foreach ($outputDirName in @('classes', 'test-classes')) {
        $outputDir = Join-Path $repoRoot (Join-Path 'target' $outputDirName)
        if (Test-Path -LiteralPath $outputDir -PathType Container) {
            Get-ChildItem -LiteralPath $outputDir -Recurse -File -Force -ErrorAction SilentlyContinue |
                Where-Object { $_.IsReadOnly } |
                ForEach-Object {
                    try { $_.IsReadOnly = $false } catch { }
                }
        }
    }

    # Maven Wrapper 与 surefire 的相对路径都按项目根解析：显式切到根目录，
    # 调用结束后恢复调用者原目录（-f 再兜底一次，防止子目录里找 pom 失败）。
    Write-LogLine "运行：$mvnExe $($argumentList -join ' ')"
    $mavenWatch = [System.Diagnostics.Stopwatch]::StartNew()
    Push-Location -LiteralPath $repoRoot
    try {
        & $mvnExe @argumentList
        $mavenExit = $LASTEXITCODE
    }
    finally {
        Pop-Location
        $mavenWatch.Stop()
    }
    Write-LogLine "Maven 退出码：$mavenExit，耗时 $([int]$mavenWatch.Elapsed.TotalSeconds) 秒。"
    Exit-Stage 'RUN_MAVEN' ("退出码 {0}" -f $mavenExit)
}
catch {
    $failureMessage = $_.Exception.Message
    $script:failureStack = $_.ScriptStackTrace
}
finally {
    # -----------------------------------------------------------------------
    # STOP_SERVER
    # -----------------------------------------------------------------------
    try {
        Enter-Stage 'STOP_SERVER'
        $instanceStopped = $true
        $stopReason = '实例未启动'
        if ($dataDir -and $pgCtlExe -and $postgresExe -and (Test-Path -LiteralPath (Join-Path $dataDir 'postmaster.pid') -PathType Leaf)) {
            $stopResult = Stop-OwnPgInstance -DataDir $dataDir -PgCtlExe $pgCtlExe -PostgresExe $postgresExe `
                -LogDir $logDir -TimeoutSeconds $StopTimeoutSeconds
            $instanceStopped = $stopResult.Stopped
            $stopReason = $stopResult.Reason
        }
        if ($resident) {
            Close-ResidentProcess -Resident $resident -TimeoutSeconds 15
            $resident = $null
        }
        if ($instanceStopped) {
            Write-LogLine "实例已停止：$stopReason"
            Exit-Stage 'STOP_SERVER'
        }
        else {
            $cleanupFailed = $true
            Write-LogLine "实例未停止：$stopReason"
            Exit-Stage 'STOP_SERVER' '失败'
        }
    }
    catch {
        $cleanupFailed = $true
        Write-LogLine "[STOP_SERVER] 停止实例时出错：$($_.Exception.Message)"
    }

    # -----------------------------------------------------------------------
    # RESTORE_ENV
    # -----------------------------------------------------------------------
    try {
        Enter-Stage 'RESTORE_ENV'
        foreach ($name in $previousEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
        Exit-Stage 'RESTORE_ENV'
    }
    catch {
        $cleanupFailed = $true
        Write-LogLine "[RESTORE_ENV] 恢复环境变量失败：$($_.Exception.Message)"
    }

    # -----------------------------------------------------------------------
    # CLEANUP
    # -----------------------------------------------------------------------
    try {
        Enter-Stage 'CLEANUP'
        if ($KeepData) {
            Write-LogLine "已保留运行目录：$runRoot"
            Exit-Stage 'CLEANUP' '按 -KeepData 保留'
        }
        elseif ($cleanupFailed) {
            Write-LogLine "清理未完成，保留运行目录：$runRoot"
            Exit-Stage 'CLEANUP' '保留目录'
        }
        elseif ($runRoot -and (Test-Path -LiteralPath $runRoot)) {
            Assert-InsideDirectory -Path $runRoot -Parent $runParent -Purpose '删除运行目录'
            Remove-Item -LiteralPath $runRoot -Recurse -Force
            Write-LogLine "已删除运行目录：$runRoot"
            Exit-Stage 'CLEANUP'
        }
        else {
            Exit-Stage 'CLEANUP' '无目录需要删除'
        }
    }
    catch {
        $cleanupFailed = $true
        Write-LogLine "[CLEANUP] 清理运行目录失败：$($_.Exception.Message)，保留 $runRoot"
    }
}

# ---------------------------------------------------------------------------
# EXIT
# ---------------------------------------------------------------------------
if ($null -eq $mavenExit) {
    $finalExitCode = 2
}
elseif ($mavenExit -eq 0) {
    $finalExitCode = if ($cleanupFailed) { 3 } else { 0 }
}
else {
    $finalExitCode = $mavenExit
}

Enter-Stage 'EXIT'
if ($failureMessage) {
    Write-LogLine "失败：$failureMessage"
    if ($script:failureStack) {
        Write-LogLine "调用栈：$($script:failureStack -replace "`r?`n", ' ← ')"
    }
}
Write-LogLine ("汇总：Maven 退出码 {0}；清理失败 {1}；运行目录 {2}；退出码 {3}" -f `
    $(if ($null -eq $mavenExit) { '未运行' } else { $mavenExit }), $cleanupFailed, $runRoot, $finalExitCode)
if ($finalExitCode -eq 0) {
    Write-Host "[pg-tests] 测试通过。" -ForegroundColor Green
}
elseif ($finalExitCode -eq 3) {
    Write-Host "[pg-tests] 测试通过，但实例停止或目录清理未完成；请检查上方诊断，退出码 3。" -ForegroundColor Yellow
}
elseif ($null -eq $mavenExit) {
    Write-Host "[pg-tests] 未进入 Maven 或未完成初始化，退出码 2。" -ForegroundColor Red
}
else {
    Write-Host "[pg-tests] 测试未通过，退出码 $finalExitCode。" -ForegroundColor Red
    if ($cleanupFailed) {
        Write-Host "[pg-tests] 另外：实例停止或目录清理也未完成。" -ForegroundColor Yellow
    }
}
Exit-Stage 'EXIT'

exit $finalExitCode

