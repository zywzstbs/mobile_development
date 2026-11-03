# P58 后端适配：第五轮 PostgreSQL 实测与修复执行方案

日期：2026-11-03。

本轮目标：解决 PostgreSQL 测试环境误判，执行完整验证，根据真实失败结果安排修复，同时保留上一轮静态发现的三个问题。

## 1. PostgreSQL 环境已确认可用

此前“系统没有 psql/postgres/docker，5432 没监听，所以没有可用 PG”的结论不完整。仓库自带真实 PostgreSQL 和测试工具，无需安装系统数据库。

|项目|实际配置|
|---|---|
|PostgreSQL|`.tools/pg/bin`，实际启动版本 17.11|
|JDK|`.tools/jdk-21.0.13+9`|
|Maven|`.tools/apache-maven-3.9.11/bin/mvn`|
|离线依赖仓库|`.tools/m2`|
|测试脚本|`scripts/run-pg-tests.sh`|
|监听|127.0.0.1:55441|
|数据目录|`target/pg-tests/data`|
|Unix socket|`target/pg-tests/sock`|
|服务日志|`target/pg-tests/server.log`|
|测试连接|`jdbc:postgresql://127.0.0.1:55441/postgres?currentSchema=gulupai_test`|
|账号|postgres；脚本初始化的本机专用测试实例使用 trust|
|生命周期|脚本启动实例，运行 Maven，退出时自动停库|

### 正确运行方式

在仓库根目录执行：

```bash
bash scripts/run-pg-tests.sh verify
```

运行指定集成测试：

```bash
bash scripts/run-pg-tests.sh -Dit.test=SupportP15IT,SupportRound3IT,SupportRound4IT,SupportAdminIT verify
```

脚本自动设置 TEST_DB_URL、TEST_DB_USERNAME、TEST_DB_PASSWORD、JAVA_HOME、离线 Maven 仓库及测试 JVM 参数。不要另用默认 5432 的 `./mvnw verify`，然后把连接失败重新认定为“环境不存在”。

沙箱环境需要允许进程启动、监听本机端口以及写入 target；本轮通过执行审批运行了现有脚本。脚本使用固定数据目录，不并发启动多份测试脚本，也不要在库运行时执行 Maven clean 删除 target。

该实例只用于独立测试，不作为生产或长期开发数据库。测试结束后停止是正常行为，不能把停库后的无监听再次判断为配置失败。本轮结束日志已确认 database system is shut down。

### 本轮实际操作

执行命令：

```bash
bash scripts/run-pg-tests.sh verify > target/p58-pg-review.log 2>&1
```

没有安装系统服务，没有改业务代码，没有删除数据库来绕过失败。

## 2. 真实验证结果

|范围|结果|
|---|---|
|连接|Hikari 成功连接 PostgreSQL 17.11，地址 127.0.0.1:55441|
|已有测试 schema|从 V022 成功执行 V023、V024、V025、V026，升级到 V026|
|空库迁移路径|日志中存在从空 schema 成功执行到 V026 的结果|
|单元测试|46，Failures=0，Errors=0，Skipped=0|
|集成测试|215，Failures=8，Errors=171，Skipped=0；其余 36 通过|
|Maven 结果|BUILD FAILURE，退出码 1|
|进程清理|脚本自动停止 PostgreSQL|

“迁移 SQL 能执行”不等于“旧数据业务语义已全部验证”；部分迁移测试仍因旧断言失败，需继续修正并验证内容。

### 失败分组

|类别|数量|实际现象|
|---|---:|---|
|测试清理外键错误|171 Errors|DELETE FROM help_article 被 help_feedback_article_id_fkey 阻止|
|迁移断言过时|5 Failures|仍期望版本 022 或旧迁移数量；实际已到 026|
|求助重开|1 Failure|预期 200，实际 409 / TICKET_REOPEN_WINDOW_CLOSED|
|求助附件读取|1 Failure|上传完成后读取元数据预期 200，实际 403|
|求助回避错误码|1 Failure|预期 RECUSAL_REQUIRED，实际 FORBIDDEN|

证据：`target/p58-pg-review.log`、`target/failsafe-reports/*.txt` 和 XML、`target/pg-tests/server.log`。原始 MockMvc 日志包含测试请求令牌，交付摘要只保留脱敏请求，不复制完整 Authorization 头。

171 个 Errors 是重复的清理失败，不代表发现 171 个不同业务漏洞；也不能因其属于夹具问题就把相应用例算作通过。许多业务断言未执行，修好清理后需要重跑。

## 3. 修复顺序与边界

按 G01 → G02 → G03 → G04 → G05 → G06 → G07 → G08 执行。

- 先修测试清理，恢复后续用例执行能力，再处理真实业务失败。
- 记录现有工作区，不覆盖前四轮修改。
- 本轮已在测试库实际执行到 V026，不改写 V001～V026；需要数据库变更时新增迁移。
- 不通过关闭外键、跳过测试、改变期望为当前错误结果或伪造数据得到通过。
- 保留前四轮文档中尚未完成的全量契约核查、约定响应补齐和真实 Android 联调。

## 4. G01：修复测试数据清理与隔离（最高执行优先级）

### 定位与原因

`src/test/java/cn/gulupai/server/support/SupportP15IT.java` 的 clearAll，约第 81 行，删除顺序是 help_article 在 help_feedback 之前。

本轮实际错误：

```text
DELETE FROM help_article
violates foreign key constraint help_feedback_article_id_fkey
Key (id)=(help_general) is still referenced from table help_feedback.
```

多个测试类复用了类似列表，静态搜索发现约 24 处相邻的错误顺序。反馈一旦真实写入，清理失败并残留数据，后续类 setup 继续失败。

### 步骤

1. 搜索所有测试清理入口，包括 @BeforeEach、@AfterEach、SQL 清理文件与 CLI 测试。
2. 先删除 help_feedback，再删除 help_article；同时按外键检查其他新增表和关联顺序。
3. 优先抽取共用测试清理工具，避免多个类继续维护不同列表，但不得扩大到生产清理逻辑。
4. 明确 help_content_pack 的测试初始版本，避免类间共享版本状态导致顺序依赖。
5. 清理必须面对“上一条测试中途失败”的数据库状态仍可运行。
6. 不关闭约束或删除数据库对象掩盖错误；若需要清理当前残留，仅限脚本的专用测试库并记录范围。
7. 先跑 SupportP15IT 与 Round3/4，再混合其他模块顺序，验证无污染。

### 验收

创建反馈后清理成功；不同顺序运行测试不报同类外键错误；被阻塞用例重新实际执行。不能只确认单类在空库运行成功。

## 5. G02：求助附件权限参数传反（P1）

### 定位与证据

`src/main/java/cn/gulupai/server/file/FileService.java` 的 canRead，约第 345 行：

```java
ticketAccess.canReadTicket(file.ticketId(), userId)
```

而 `SupportTicketAccess.canReadTicket` 参数顺序是 `(String userId, String ticketId)`。

实际测试：`SupportP15IT.revokedHandlerLosesTicketFileAndTransferReplay`，第 353 行，合法处理人刚完成上传，GET 元数据即返回 403。上传资格入口使用正确顺序，因此出现“可以上传并完成，却读不到文件”的不一致。

### 步骤

1. 改为正确的 userId、ticketId 顺序。
2. 全仓搜索 canReadTicket 所有调用，逐项核对语义，不仅修该行。
3. 检查元数据、下载授权、实际字节下载和回放均复用当前权限。
4. 保留撤权、停用账号、转交失权限制，不能为让合法用例通过直接放行上传者。
5. 可用命名明确的包装对象或更清晰方法名降低同类型参数再次交换的风险，避免无关重构。

### 验收

合法处理人上传后可读；撤权后元数据、下载授权、旧短期 URL 都按规则拒绝；新负责人和提问者按真实权限可读；无额外文件引用或授权副作用。

## 6. G03：JDBC 时间类型转换遗漏（P1）

### 定位与证据

`HelpSupportService.instant(Object)` 仅支持 Instant、OffsetDateTime；one/oneRow 使用 ResultSet.getObject，未统一处理 java.sql.Timestamp。

真实测试 `SupportP15IT.helpPackTicketAcceptReplyResolveReopenAndTransfer` 第 161 行：刚确认解决立即重开，返回 409 / TICKET_REOPEN_WINDOW_CLOSED。数据库 reopen_until 已写入，但驱动返回的 Timestamp 没有被 instant 识别，变成 null，误判过期。

### 步骤

1. 用明确 JDBC 类型读取时间，或统一支持 Timestamp.toInstant；one、oneRow、instant 的处理保持一致。
2. 不将 null 或解析失败替换成当前时间，也不放宽真实重开窗口。
3. 审计相同通用 Map 读取路径中的 createdAt、updatedAt、closedAt、包 updatedAt 等，防止同类错误。
4. 时间比较使用服务端 Clock；准确核对截止瞬间是否允许，不用字符串排序或系统默认时区猜测。
5. 补真实 PostgreSQL 用例，不能仅构造 Instant 入参测试工具方法。

### 验收

刚关闭的工单可按契约重开；超期拒绝；有效偏移时间按瞬间比较；未知/缺失期限不假成功；版本、解决确认及历史一致。

## 7. G04：回避错误码被通用读取检查提前吞掉（P2）

### 定位与证据

`SupportP15IT.courseSupportRequiresCurrentScopeAndRecusal` 第 476 行：期望 RECUSAL_REQUIRED，实际 FORBIDDEN。

检查 HelpSupportService.mutate 与 SupportTicketAccess：通用 requireReadable 可能先因回避返回 false/403，导致具体动作的 requireHandler 无法返回回避错误码。

### 步骤

1. 对照接口契约确认处理动作应返回的回避码；不要直接改测试期望来匹配当前实现。
2. 区分提问者动作、处理者动作、普通读取和成功回放的授权入口。
3. 在仍不泄露其他工单的前提下，让已确认身份和范围的处理者按具体回避规则被拒绝。
4. 不因调整错误码而跳过版本、当前授权或指派检查。

### 验收

适用回避的处理动作返回正确错误码且无写入；纯越权仍拒绝；合法提问者不被处理者规则误伤；撤权回放继续被拒绝。

## 8. G05：更新迁移测试断言并保留业务验证（P2）

### 实际失败

- ChatMigrationV019IT：期望迁移数 3，实际 7。
- MaterialTimeMigrationIT：三项失败，包括旧迁移数量和期望最新版本 022。
- EmptyUpgradeIT：期望 022，实际 026。

### 步骤

1. 区分测试目的：只验证某次历史迁移时显式指定目标版本；验证升级到当前最新时使用最新版本来源或与迁移目录一致的断言。
2. 不只把数字机械替换后结束；保留旧聊天、材料时间可信度和备份恢复数据断言。
3. 对 V023～V026 增加针对实际旧数据的验证，不能只验证 history 行数。
4. 覆盖帮助长正文处理、包版本、举报范围、匿名/登录游标主体。
5. 每次新增迁移避免再次修改多个无关测试中的硬编码数字。

### 验收

空库与旧库路径都完成；业务数据不丢失或伪造；历史修复规则仍有效。

## 9. G06：V026 新字段遗漏的直接插入（P1，静态已确认）

位置：

- `src/main/java/cn/gulupai/server/identity/cli/DemoScenarioService.java:239`
- `src/test/java/cn/gulupai/server/merge/MergeP08IT.java:124`

V026 规定 subject_key NOT NULL，USER 时还要求等于 user_id。这两个直接 INSERT 仍未提供 subject_key。当前完整测试被清理错误大量阻塞，不能因为没有走到此处就认为安全。

### 步骤与验收

1. 搜索所有 cursor_snapshot 插入，补 USER 主体字段或复用合适的游标创建入口。
2. 匿名保持 user_id=null、subject_type=ANONYMOUS、subject_key=PUBLIC，不能用默认值混淆。
3. 实际执行升级后的演示初始化及 MergeP08IT。
4. 验证旧游标、过期游标和授权 epoch 检查未被弱化。

## 10. G07：帮助长度边界与旧缓存碰撞（P2，静态已确认）

### G07-A：拼接长度

SupportRequests.ArticleBody 允许 20 条各 500 字的 steps，applyArticleUpdate 使用换行拼接，最多 10019 个普通字符，而 body 为 varchar(10000)。在线编辑缺少导入入口已有的总长处理。

步骤：按契约统一在线编辑与导入的约束；可通过新增迁移扩大兼容列承载合法结构，或在契约允许时明确总长校验。不能截断。补 20×500 的 HTTP 与数据库边界测试，非法请求返回明确业务校验而非数据库 500。

### G07-B：升级包版本碰撞

V024 按当前已发布文章最大 contentVersion + 1 初始化。若旧客户端曾缓存 10，最高文章已撤下，当前最大为 9，升级又得到 10；knownVersion=10 会误判无变化。

步骤：制定可证明的旧缓存失效策略，不仅使用当前发布集合推断历史上限。旧迁移已执行，应通过后续迁移或协议兼容策略处理，不能改 V024。不得引入未经客户端确认的必填参数或改变 version 类型。

验收：缓存后撤下最高版本文章、修改适用版本、再升级均能重新获取正确内容；真实未变请求仍遵守既定返回语义。

## 11. G08：分层回归与交付

推荐先修清理再跑相关类：

```bash
bash scripts/run-pg-tests.sh -Dit.test=SupportP15IT,SupportRound3IT,SupportRound4IT,SupportAdminIT verify
```

随后跑迁移、演示与合并，再全量：

```bash
bash scripts/run-pg-tests.sh verify
```

不要并发运行共用数据目录的脚本。每次查看 failsafe 的实际执行数量、Failures、Errors、Skipped，不把 Surefire 46 通过代替 IT 通过。

交付到 `docs/backend/P58/`：

1. 第五轮修复记录，按 G01～G08 列修改文件、测试、实际结果和剩余项。
2. PG 环境说明，明确使用仓库脚本和 55441，纠正先前“无 PG”的结论。
3. 脱敏的全量结果摘要及必要失败证据。
4. 281 项差异清单，仅对已验证项更新状态。
5. 前四轮待完成事项继续保留，测试通过不自动证明未核验的响应字段已兼容。

## 12. 给执行模型的指令

> 使用仓库现有 scripts/run-pg-tests.sh 启动 PostgreSQL，不再用未启动的默认 5432 判定环境不可用。按 G01～G08 执行：先修测试清理污染，再修附件权限参数顺序、JDBC 时间类型、回避错误码、迁移断言及静态遗漏。保留真实业务断言，不关闭外键、不改已执行迁移、不清空回执制造通过。完成后运行完整 verify 并交付真实执行数量与结果。首次实测为单元测试 46 通过、集成测试 215 中 8 Failures/171 Errors，这个基线不得改写成环境失败或已通过。
