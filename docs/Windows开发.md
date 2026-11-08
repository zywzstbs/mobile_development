# Windows 本地开发

核查日期：2026-11-06。后端需要 JDK 21、Maven 3.9+、PostgreSQL 17。Android 的 Java 17 要求不适用于本仓库。

## 本机已验证的测试入口

PowerShell 在仓库根目录执行。以下 JDK 路径是本次发现的本机安装，换电脑时替换为实际 JDK 21 目录；环境变量仅作用于当前终端。

```powershell
$env:JAVA_HOME = 'C:\Users\Administrator\.jdks\jbr-21.0.11'
$env:MAVEN_USER_HOME = Join-Path $PWD '.tools/maven-home'
& "$env:JAVA_HOME\bin\java.exe" -version
.\mvnw.cmd -o '-Dmaven.repo.local=.tools/m2' test
```

当前 Maven 3.9.11 分发和依赖缓存已在 `.tools` 中，以上离线命令可编译并执行测试；新机器缓存不完整时需联网去掉 `-o`。本次结果为 73 项、1 失败、19 错误，原因是 Mockito 在 Windows 下无法 self-attach（见下）与 R7 清库断言不同步，已分别修复，不能作为已通过的快速启动示例。

### Windows 构建的两个注意点

- **每次构建前先删 `target`。** `mvnw.cmd` 复制到 `target/classes` 的资源文件在 Windows 上会带只读属性，第二次构建的资源过滤会报 `FileNotFoundException: ... (拒绝访问。)`。稳妥做法是先 `Remove-Item 'target' -Recurse -Force -ErrorAction SilentlyContinue`。
- **Mockito 需要显式挂 javaagent。** 当前 JDK/Mockito 组合下自附加被拒，`@WebMvcTest` 会报 `MockitoInitializationException: Could not self-attach to current VM`。加上

  ```powershell
  .\mvnw.cmd -o '-Dmaven.repo.local=.tools/m2' '-DargLine=-javaagent:.tools/m2/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar' test
  ```

  即可，`scripts/run-pg-tests.ps1` 已自动带上该参数。

## 数据库和应用启动

Spring 配置使用进程环境变量，**不会自动加载根目录 `.env`**。可在 IDE Run Configuration 中配置变量，或在当前 PowerShell 设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。密码使用私密输入，不放入文档或命令历史。配置项参见根目录 `.env.example` 和 `src/main/resources/application*.yml`。

本地开发库准备好后运行：

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.tools/m2' spring-boot:run
# 另一个终端检查
Invoke-RestMethod http://localhost:8080/health
```

此启动流程本轮未执行（未准备 PostgreSQL）。默认 local profile 会自动应用 Flyway 迁移，先确认 DB_URL 指向本地开发库。

## 集成测试与数据库入口

- `test` 执行单元/MVC 测试；`verify` 还执行真实 PostgreSQL 的 `*IT`，会清理测试数据。必须使用独立测试实例/库，不能使用开发业务库或线上库。
- `scripts/run-pg-tests.ps1` 是 Windows 入口：默认解析仓库根（不依赖调用者 cwd），校验 JDK 21、Maven 3.9+、Windows 原生 `initdb.exe/pg_ctl.exe/pg_isready.exe/createdb.exe` 和 PostgreSQL 17，在 `target/pg-tests-windows/<runId>/` 下初始化一次性 UTF-8 实例（只监听 127.0.0.1，端口占用即失败），创建专用库 `gulupai_test` 并导出 `TEST_DB_URL/TEST_DB_USERNAME/TEST_DB_PASSWORD`，最后运行 `mvnw verify` 并保留其退出码。示例：

  ```powershell
  .\scripts\run-pg-tests.ps1 -PgBin 'C:\Program Files\PostgreSQL\17\bin' -JavaHome $env:JAVA_HOME
  .\scripts\run-pg-tests.ps1 -Port 55442 -MavenArgs @('verify','-Dtest=TestSchemaCleanerTest')
  ```

  参数：`-PgBin`、`-Port`（默认 55441）、`-JavaHome`、`-Maven`、`-MavenArgs`（字符串数组，默认 `verify`）、`-StartupTimeoutSeconds`、`-InitdbTimeoutSeconds`（默认 300）、`-StopTimeoutSeconds`（默认 60）、`-StartMode Auto|PgCtl|Direct`（默认 Auto）、`-KeepData`。脚本不写死本机 JDK 路径，不安装全局服务，不读取 `.env`，失败时非 0 退出。它使用 trust 认证，仅适用于只绑定回环的一次性实例，不能当作生产或共享测试环境配置。
- 脚本按阶段执行并打印 `[STAGE]` 日志（同时写入运行目录 `log/stages.log`）：`PRECHECK → INITDB → START_SERVER → WAIT_READY → CREATE_DATABASE → RUN_MAVEN → STOP_SERVER → RESTORE_ENV → CLEANUP → EXIT`。所有外部命令都经 `scripts/pg-test-process.ps1` 的有界执行（进程退出或超时终止，stdout/stderr 分文件），常驻 `postgres` 只保留句柄、从不等待管道 EOF，因此不会出现“库已起来但脚本卡住”的情况。两个日志流在启动进程之前打开；日志路径不可写、被占用或实为目录时直接失败，异常路径只结束本函数刚启动的进程树并有界等待，不会遗留子进程或日志句柄（第四轮 T4-1）。
- 退出码：未进入 Maven（环境或起库失败）为 **2**；Maven 失败返回 **Maven 原退出码**；Maven 成功但实例停止或目录清理未完成返回 **3**；全部成功为 **0**。停止只针对本次数据目录，需同时满足 `postmaster.pid` 目录、进程句柄、可执行文件路径、启动时间与命令行证据，否则保留目录并报清理失败，不会误杀其它实例。
- `-StartMode Auto` 先走 `pg_ctl start`；只有日志报“继承套接字（错误码 87）”才回退直接启动 `postgres.exe`。注意 PostgreSQL 拒绝以管理员令牌直接启动：普通管理员终端里 `pg_ctl` 可用（它自己用受限令牌启动 postmaster），而直启回退只适用于非管理员账户。
- 凭据文件（`DemoSeedService` 播种的账号口令）由 `PrivateCredentialFiles` 写入：先建私有暂存目录与私有文件再发布，POSIX 下 0700/0600，Windows 下 DACL 只保留当前账户一条允许规则。Windows ACL 操作需要 `WRITE_DAC`，受限令牌（如某些沙箱）下会失败，这是环境限制而不是代码回退。
- `scripts/test-pg-test-process.ps1` 是上面进程助手自检（退出码、超时、空格路径、常驻进程回收，以及 stdout/stderr 日志打开失败、启动失败、启动后复制初始化失败的清理与原始错误保留），不依赖数据库，可单独运行。
- `scripts/run-pg-tests.sh` 仍是 Linux 专用入口：固定依赖 `.tools/pg`、`.tools/jdk-21.0.13+9`、`.tools/apache-maven-3.9.11`，使用 Unix socket。Git Bash 不能把 Linux 可执行文件变成 Windows 程序，Windows 上请用对应的 `.ps1`。
- 本机已用官方 PostgreSQL 17.8 标准发行版跑通完整 `verify`：单元 91、集成 274，0 失败，脚本退出码 0（详见[第四轮问题修复回告](修改3/第四轮问题_修复回告_20261107.md)；第三轮记录保留为历史）。
- CLI 审核身份已改为平台原生实现（`TrustedOsIdentity`）：Linux 读 euid 并校验 `/proc/self/status`，Windows 用进程令牌 SID（`sid:s-1-5-21-...`）。不再读取 `user.name`/`USER`/`USERNAME` 判定身份，伪造这些变量不会获得审核权。
- WSL/Linux 可作为原流程的候选环境，但须重新准备对应 Linux 工具和独立测试库；本机 WSL 启动被拒绝（`Wsl/E_ACCESSDENIED`），本轮未验证。
- `deploy/` 中 shell/systemd/Caddy 资料属于 Linux 服务器部署，不需要因为本地迁移而改为 Windows 服务。
- `target/` 和 `.tools/` 是忽略目录，旧报告不是当前通过证据。`wsl-backslash-file.md` 是迁移前已暂存重命名的文件，本次保留。
