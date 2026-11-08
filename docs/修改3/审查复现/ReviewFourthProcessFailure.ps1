# 历史缺陷复现：返回 1 表示仍有子进程泄漏；仅启动并结束自己的测试子进程。
param([string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path)
$ErrorActionPreference = 'Stop'
. (Join-Path $RepoRoot 'scripts/pg-test-process.ps1')
$probeRoot = Join-Path $RepoRoot ('target/review-process-failure-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($probeRoot)
$childScript = Join-Path $probeRoot 'child.ps1'
@'
param([string]$PidPath)
Set-Content -LiteralPath $PidPath -Value $PID
Start-Sleep -Seconds 30
'@ | Set-Content -LiteralPath $childScript
$pwshExe = (Get-Process -Id $PID).Path
$leaked = $false
foreach ($kind in @('Bounded', 'Resident')) {
    $pidPath = Join-Path $probeRoot ($kind + '.pid')
    try {
        $arguments = @('-NoProfile', '-File', $childScript, $pidPath)
        # 故意把现有目录作为日志文件，确定性模拟日志打开失败。
        if ($kind -eq 'Bounded') {
            $null = Invoke-BoundedProcess -FilePath $pwshExe -ArgumentList $arguments -StdoutPath $probeRoot -StderrPath (Join-Path $probeRoot 'err.log') -TimeoutSeconds 1
        } else {
            $null = Start-ResidentProcess -FilePath $pwshExe -ArgumentList $arguments -StdoutPath $probeRoot -StderrPath (Join-Path $probeRoot 'err.log')
        }
    } catch { Write-Output "$kind threw: $($_.Exception.GetType().Name): $($_.Exception.Message)" }
    Start-Sleep -Seconds 2
    if (Test-Path -LiteralPath $pidPath) {
        $childId = [int](Get-Content -LiteralPath $pidPath -Raw)
        $child = Get-Process -Id $childId -ErrorAction SilentlyContinue
        Write-Output "$kind childAliveAfterFailure=$($null -ne $child) pid=$childId"
        if ($child) {
            $leaked = $true
            if ($child.Path -eq $pwshExe -and $child.StartTime -ge (Get-Item $childScript).CreationTime) {
                $child.Kill($true)
                [void]$child.WaitForExit(5000)
            }
        }
    } else { Write-Output "$kind child did not create PID marker" }
}
if ($leaked) { exit 1 }
exit 0
