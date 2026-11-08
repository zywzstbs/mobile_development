<#
.SYNOPSIS
    不启动数据库即可运行的连接串断言，防止 PowerShell 把
    `$databaseName?currentSchema` 当成一个变量名。

.EXAMPLE
    pwsh -File scripts/test-pg-test-url.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'pg-test-url.ps1')

$script:failures = @()

function Assert-Equal([string]$Name, [string]$Expected, [string]$Actual) {
    if ($Expected -ne $Actual) {
        Write-Host "[pg-test-url] FAIL $Name" -ForegroundColor Red
        Write-Host "  expected: $Expected"
        Write-Host "  actual:   $Actual"
        $script:failures += $Name
    }
    else {
        Write-Host "[pg-test-url] ok   $Name" -ForegroundColor Green
    }
}

Assert-Equal '默认端口与库名' `
    'jdbc:postgresql://127.0.0.1:55441/gulupai_test?currentSchema=gulupai_test' `
    (New-PgTestDatabaseUrl -DatabaseName 'gulupai_test' -Port 55441)

Assert-Equal '自定义端口与库名' `
    'jdbc:postgresql://127.0.0.1:6000/other_db?currentSchema=gulupai_test' `
    (New-PgTestDatabaseUrl -DatabaseName 'other_db' -Port 6000)

$url = New-PgTestDatabaseUrl -DatabaseName 'gulupai_test' -Port 55441
if ($url.Contains('$') -or -not $url.EndsWith('?currentSchema=gulupai_test')) {
    Write-Host "[pg-test-url] FAIL 连接串未完全展开或缺少 currentSchema：$url" -ForegroundColor Red
    $script:failures += '展开检查'
}

if ($script:failures.Count -gt 0) {
    Write-Host "[pg-test-url] 失败：$($script:failures -join '、')" -ForegroundColor Red
    exit 1
}
Write-Host "[pg-test-url] 全部通过。" -ForegroundColor Green
exit 0
