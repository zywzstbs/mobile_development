<#
.SYNOPSIS
    构建 Windows 一次性 PostgreSQL 测试库的 JDBC 连接串（纯函数，不依赖数据库）。

.DESCRIPTION
    单独放在这里是为了能被 run-pg-tests.ps1 复用，并被
    test-pg-test-url.ps1 在不启动数据库的情况下断言。
    连接串中的库名必须用 ${DatabaseName} 插值；直接写
    $DatabaseName?currentSchema 会被 PowerShell 当成一个变量名。
#>
function New-PgTestDatabaseUrl {
    param(
        [Parameter(Mandatory)]
        [string]$DatabaseName,
        [Parameter(Mandatory)]
        [int]$Port
    )
    return "jdbc:postgresql://127.0.0.1:$Port/${DatabaseName}?currentSchema=gulupai_test"
}
