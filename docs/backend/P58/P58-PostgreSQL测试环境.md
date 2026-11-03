# P58 PostgreSQL 测试环境

日期：2026-11-03。

仓库自带 PostgreSQL 和测试工具。不能因为系统没有 `psql`、`postgres` 或 Docker，也不能因为 `127.0.0.1:5432` 没有监听，就判断没有可用数据库。

|项目|实际配置|
|---|---|
|PostgreSQL|`.tools/pg/bin`，启动版本 17.11|
|JDK|`.tools/jdk-21.0.13+9`|
|Maven|`.tools/apache-maven-3.9.11/bin/mvn`|
|离线依赖仓库|`.tools/m2`|
|测试脚本|`scripts/run-pg-tests.sh`|
|监听|127.0.0.1:55441|
|数据目录|`target/pg-tests/data`|
|Unix socket|`target/pg-tests/sock`|
|服务日志|`target/pg-tests/server.log`|
|测试连接|`jdbc:postgresql://127.0.0.1:55441/postgres?currentSchema=gulupai_test`|
|账号|postgres。脚本初始化的本机测试实例使用 trust，不把口令写进文档|

在仓库根目录执行：

```bash
bash scripts/run-pg-tests.sh verify
```

脚本设置 `TEST_DB_URL`、`TEST_DB_USERNAME`、`TEST_DB_PASSWORD`、`JAVA_HOME`、离线 Maven 仓库和测试 JVM 参数。不要改用默认 5432 的 `./mvnw verify`，再把连接失败写成环境不存在。

脚本启动实例，跑完 Maven 后停止数据库。停库后的无监听是正常退出，不是配置失败。数据目录固定，不要并发启动多份脚本，也不要在库运行时删除 `target`。

该实例只用于测试，不作为生产或长期开发库。
