# 咕噜派后端（gulupai-server）

当前部署：`https://124.220.167.12/api/v1`，P62 / V030，299项完整回归通过；学生注册和教师申请已开放。原18服务器已弃用。见 [本次发布与审核说明](deploy/2026-11-04-P62更新验收.md)。下文保留分阶段历史交付记录。

按 `docs/backend` 分阶段实现的课程演示后端。**281 个接口均已实现**；2026-11-02 完成 R01～R08、S01～S06、T01～T06、U01～U02 与 W01 五轮定向修复，并在独立一次性 PostgreSQL 17 上跑通全量 `verify`：**245 项测试通过（46 单元/MVC + 199 真实 PG 集成）**。W01 的加密回执证据校验和恢复事务已完成；**真实/待升级库的 `RECOVERED_EVIDENCE` 存量核查与必要纠错仍待部署前执行**。这只是“后端验证通过”，**Android 联调仍未执行**；2026-11-03 已部署 AWS 公网 IP HTTPS，详见 [部署说明](deploy/README.md)。

- 技术栈：Java 21、Spring Boot 3.5.16、Maven Wrapper、Spring MVC/Security、Spring JDBC、PostgreSQL 17、Flyway。
- 数据库迁移：`V001`～`V022`（身份、课程、教师授权、组队、任务交付、人员治理、合并、成绩、报告归档、画像作品与 AI、正式骰子、留言与聊天、公告通知、帮助与举报；V019 为聊天请求摘要/同步游标/下发区间；V020 失效旧同步 token 并新增 case 级教师授权；V021 为材料回应版本与审核记录；V022 为材料提交时间可信度，V021 回填的最后修改时间只留内部诊断）。第五轮未新增迁移，V001～V022 未修改。
- 协议基座：统一 `{code,message,data}` 信封、严格 JSON、UTC 秒级时间、十进制字符串、1MiB 请求/2MiB 响应/1.75MiB 集合限制、请求关联 ID。
- 身份：BCrypt、access 30 分钟 / refresh 固定 7 天、令牌只存摘要、停用即时生效、登录限流。
- 幂等：`(userId, method, path, Idempotency-Key)`、事务级 advisory lock、规范 JSON 摘要、AES-GCM 加密回放结果、回放重新核权；聊天/留言可撤回资源按当前状态重新投影。
- 课程/教学：字典、真实教学班、规范课表签名、匹配 token、广场与作业、课程码、教师权属/授权/转交。
- 组队：项目 QUICK/DETAILED、岗位、默认看板阶段、正式团队资格、申请/邀请/24h 席位预留、本人接受入组、组队码、招募与工作台/待办。
- 后续模块：岗位协商、文件基础、任务看板、正式提交验收、人员治理、广场合并、教学成绩、报告快照与项目归档均已按阶段接入。

## 快速开始

```bash
cp .env.example .env          # 按本机填写 DB_*，不要提交 .env
export $(grep -v '^#' .env | xargs)

./mvnw test                   # 单元与 MVC 契约测试（不需要数据库）
./mvnw verify                 # 追加真实 PostgreSQL 迁移、路由核验、空库升级与全部阶段集成测试
./mvnw spring-boot:run        # 默认 local profile
curl -s http://localhost:8080/health
```

健康检查示例：

```json
{"code":"OK","message":"成功","data":{"status":"UP","database":"UP","environment":"local","version":"0.1.0-P10","time":"2026-10-31T08:15:00Z"}}
```

## CLI 导入与审核

```bash
# 1) 学校/用户
java -jar target/gulupai-server-0.1.0-P10.jar --spring.profiles.active=cli \
  --app.cli.command=import-users --app.cli.file=admin/fixtures/p01-demo-users.json
# 2) 学期/节次/课程/真实教学班/选课事实
java -jar target/gulupai-server-0.1.0-P10.jar --spring.profiles.active=cli \
  --app.cli.command=import-academic --app.cli.file=admin/fixtures/p02-demo-academic.json
# 3) 教师认领审核
java -jar target/gulupai-server-0.1.0-P10.jar --spring.profiles.active=cli \
  --app.cli.command=review-teacher-claim --app.cli.claim-id=<id> \
  --app.cli.decision=APPROVE --app.cli.reason="核对名单"
# 4) W01 受控恢复历史材料提交时间：回执必须绑定同材料/版本/作者，
#    且解密结果的顶层 responseVersion 与目标版本一致；恢复时间取结果中的 createdAt
java -jar target/gulupai-server-0.1.0-P10.jar --spring.profiles.active=cli \
  --app.cli.command=material-time-recovery --app.cli.material-id=<materialId> \
  --app.cli.response-version=1 --app.cli.receipt-id=<operationReceiptId> --app.cli.dry-run=true
# 5) W01 只读核查已恢复行：VALID / CORRECTION_REQUIRED / UNVERIFIABLE，不写业务数据
java -jar target/gulupai-server-0.1.0-P10.jar --spring.profiles.active=cli \
  --app.cli.command=material-time-recovery-audit
```

`--app.cli.dry-run=true` 对恢复命令只校验不写入；`material-time-recovery-audit` 始终只读。`import-academic` 对同摘要批次输出 `[reused]`。真实/待升级库的存量核查与必要纠错须在部署前单独执行，详见[W01 第五轮结果](docs/backend/修复结果/2026-11-02-W01第五轮修复结果.md)。

## 已实现接口

当前实现范围为 AUTH、COURSE、TEACH、TEAM、ROLE、FILE、TASK、DELIVERY、GOV、MERGE、GRADE、REPORT、PROFILE、AI、DICE、BOARD、CHAT、NOTICE、HELP、REPORTING 共 **281** 个接口。281 条路由映射由 `RouteCoverageIT` 核验存在；业务验收以各阶段 IT 与第五轮 245 项 `verify` 为准，**不把路由覆盖等同于逐字段完成**。本轮直接核验的 GOV-01/02/17/18/19/30、TEAM-06/10/25/26、CHAT-01～06、GRADE 结算/发布相关条目已更新证据，其余条目保留原阶段记录。

### AUTH-01～08

登录、刷新、注销、本人资料/画像可见范围、当前用户与 `capabilities`。

### COURSE-01～22

课程名/学期/节次字典、广场/作业、规范课表匹配、广场创建、作业确认；课程码签发/读取/撤销/解析、可信名单确认入课、入课申请/审核。

### TEACH-01～10

教师/助理工作台、班级授权授予/撤销、教师认领、退出负责、课程转交发起/回应、课程进展。

### TEAM-01～26

| 分组 | 接口 |
|---|---|
| 项目 | `POST /projects`、`GET/PATCH /projects/{id}`、`GET /me/projects`、`GET /projects/{id}/members` |
| 招募 | `GET /assignments/{id}/recruitments`、`GET/PUT /projects/{id}/recruitment`、`GET /projects/{id}/invite-candidates` |
| 申请 | `POST /projects/{id}/applications`、`GET /me/applications`、`GET /projects/{id}/applications`、`POST /applications/{id}/decisions`、`POST /applications/{id}/withdrawals` |
| 邀请 | `POST /projects/{id}/invitations`、`GET /me/invitations`、`GET /projects/{id}/invitations`、`GET /invitations/{id}`、`POST /invitations/{id}/responses`、`POST /invitations/{id}/revocations` |
| 组队码 | `GET /projects/{id}/join-code`、`POST /projects/{id}/code-actions`、`POST /project-code-resolutions` |
| 个人总览 | `GET /me/dashboard`、`GET /me/todos`、`GET /me/work-source-projects` |

关键规则：

- 创建项目要求当前学生具备课程资格；创建者同事务占正式团队资格并成为组长；`assignment_team_slot(assignment,user)` 唯一。
- DETAILED 项目岗位总人数必须等于 capacity，`leaderRoleKey` 指向本次岗位；默认生成“默认阶段”。
- 申请只 PENDING，不占正式关系；APPROVE 原子创建 24 小时邀请与 ACTIVE 预留；本人 ACCEPT 才创建 Membership/正式团队资格。
- 容量/岗位同时核对成员与有效预留；接受邀请关闭同作业其他 PENDING 申请/邀请；事务失败不留半成品。
- 项目组队码只存摘要 + AES-GCM 密文；ENABLE/DISABLE/ROTATE 核对项目 revision。
- 项目组 `ownerId` 是组长；workspace 待办从邀请/申请真实状态派生，未声称覆盖后续模块。

### P10 报告与归档

- 五栏报告、个人与整体复盘、交付草稿、10 分钟来源预览、不可变交付版本、PROCESS/FINAL/EMPTY 快照。
- ACTIVE→CLOSING 关闭招募并撤销待处理邀请/预留/项目码；允许现有任务完成。恢复后再次收尾会增加 `closingCycle`。
- 归档重验任务、提交、人员变更、正式约定、提案、贡献确认和当前交付/报告来源；EMPTY 仅接受无任务、约定或提交历史的项目。ARCHIVED 不可恢复，组内写入只读。
- 代码已实现；`ReportP10IT` 5 个场景通过，全量 81 个集成测试、38 个单元/MVC 测试通过。

### P11 画像、作品、互评与 AI 参考

- 合作记录、作品版本/撤回、PRIVATE/TEAM/CLASS 授权交集、他人画像与作品读取。
- 三维互评（DELIVERY/COMMUNICATION/REWORK）、本人不能自评、项目归档后 14 天窗口、原始互评仅作者、撤回保留历史。
- 组长确认的客户端 AI 参考；origin 固定 `CLIENT_BYOK_LEADER_CONFIRMED`，拒绝跨项目 source、API 密钥和重复 clientRecordId 异正文。

### P12 正式骰子

- 组长对无主、无提交的普通任务建立 2～20 人骰子会话；MAX/MIN/TARGET，固定 3 轮。
- 全员接受后冻结来源进入投骰；每人每轮一个服务器 SecureRandom 点数，同一人同轮不同幂等键也只返回原点数。
- 收齐前只暴露本人点数与他人 `hasRolled`；收齐后 MAX/MIN 保留并列极值，TARGET 无人命中保留全候选。
- 唯一拟领取人 ACCEPT 只进入 `AWAITING_CLAIM`，本人再调用 TASK-06 claim 才真正写任务负责人；他人先认领使会话来 `INVALIDATED`。

### P13 留言板与团队聊天

- BOARD-01～14：主题/回复、修订历史、撤回占位、作者 RESOLVE/SET_HELPFUL、组长 CLOSE/置顶、关注、单层回复。
- CHAT-01～06：消息序号、clientMessageId 精确核对、after/before/q 查询、120 秒本人撤回、变更流、阅读游标。
- 撤回保留 sequence/占位，清正文、refs、mentions；聊天写入同事务写 notification_outbox 待 P14 投影。

### P14 公告、消息与偏好

- 课程公告列表/预览/发布/详情、SUBSTANTIVE/FORMAT_ONLY 修订、撤权、回执读取、知悉、必要回执、催阅预览/提交。
- 接收快照固定版本分母；后入课可读新公告但不进入旧分母；GET 不写已读；知悉绑定 contentVersion。
- 通知索引与去重、按 ID/上界清提示、偏好默认 IN_APP/MENTIONS/22:00～08:00/Asia/Shanghai。
- notification_outbox worker 处理聊天提及通知；不表示设备推送送达。

### P15 帮助、求助与举报

- HELP-01～16：通用/课程帮助、反馈、CLI 初始文章、支持路由、工单创建/列表/详情/回复/工作台/领取/状态/解决/转交/关闭、7 天重开。
- REPORTING-01～04：主题/回复/聊天内容举报、最少片段核验、独立 O 处理、受理/结论/关闭，不自动删文、移人、扣分。
- 运营能力通过 `operator_grant` 控制；无可用路由返回 503，不生成假单号。

## 配置

`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`SERVER_PORT`、`APP_ENV`、`APP_FIXED_INSTANT`、`IDEMPOTENCY_ENCRYPTION_KEY`、`TEST_DB_URL` 等见 `.env.example` 与 `src/main/resources/application*.yml`。Profile：`local`、`test`、`demo`、`cli`。

## 当前阶段边界

- 281 个接口均已实现；第五轮在一次性真实 PostgreSQL 17 上完成迁移、路由、空库升级、恢复与全部阶段 IT，共 **245 项通过**（46 单元/MVC + 199 IT；第一轮 178 项见 [F09](docs/backend/交付记录/F09-总回归与交付.md)，第二轮见 [G00～G06](docs/backend/交付记录/G00-G06-第二轮修复记录.md)，第三轮见 [T01～T06 结果](docs/backend/修复结果/2026-11-02-T01-T06第三轮修复结果.md)，第四轮见 [U01～U02 结果](docs/backend/修复结果/2026-11-02-U01-U02第四轮修复结果.md)，第五轮见 [W01 结果](docs/backend/修复结果/2026-11-02-W01第五轮修复结果.md)）；
- W01 恢复时间改为加密回执中的真实提交结果时间；真实/待升级库的 `RECOVERED_EVIDENCE` 存量核查与必要纠错仍待部署前执行，当前只有只读 `material-time-recovery-audit` 与 SQL 清单；
- 2026-11-03 已部署 AWS 公网 IP HTTPS，健康检查和鉴权边界已验证；已按用户要求导入21个测试账号及演示场景，见 [测试数据](docs/frontend/AWS联调测试数据.md)。Android 联调需前端产物，当前未执行；
- `TEAM-06` 已按本人真实有效交付/确认/独立验收证据返回，不再固定空集；历史离组只保留安全摘要；
- `/me/todos` 与 `/me/dashboard` 已覆盖 P50 的 16 类业务来源，ACTION/WAITING 计数与列表同源；
- 聊天变更游标改为 7 天、绑定本人/项目/授权代次的服务端 token；首次同步只建立高水位；`clientMessageId` 绑定完整请求摘要；
- 成绩结算区分未交、待验收、待确认与交接阻断，教学更正不再绕过程序性 gap；
- 未做 Android 联调；云服务器已部署并完成基础检查。R02-T08/R05-T05 等双连接并发用例、2MiB 压测仍是验证缺口。

修复结果：[R01～R08 修复结果](docs/backend/修复结果/2026-11-02-R01-R08修复结果.md)、[S01～S06 第二轮修复结果](docs/backend/修复结果/2026-11-02-S01-S06第二轮修复结果.md)、[T01～T06 第三轮修复结果](docs/backend/修复结果/2026-11-02-T01-T06第三轮修复结果.md)、[W01 第五轮修复结果](docs/backend/修复结果/2026-11-02-W01第五轮修复结果.md)。审查原文保留在[实现审查记录](docs/backend/审查记录/2026-11-02-实现审查.md)。

阶段记录：[P00](docs/backend/交付记录/P00-工程与契约基础.md)、[P01](docs/backend/交付记录/P01-账号与幂等.md)、[P02](docs/backend/交付记录/P02-课程与作业.md)、[P02B](docs/backend/交付记录/P02B-教师与课程码.md)、[P03](docs/backend/交付记录/P03-项目与组队.md)、[P04](docs/backend/交付记录/P04-岗位协商与项目设置.md)、[P05](docs/backend/交付记录/P05-任务看板与依赖.md)、[P06](docs/backend/交付记录/P06-正式分工提交与验收.md)、[P07](docs/backend/交付记录/P07-人员治理与交接.md)、[P08](docs/backend/交付记录/P08-广场合并.md)、[P09](docs/backend/交付记录/P09-教学与正式成绩.md)、[P10](docs/backend/交付记录/P10-报告与归档.md)、[P11](docs/backend/交付记录/P11-画像作品与互评.md)、[P12](docs/backend/交付记录/P12-正式骰子.md)、[P13](docs/backend/交付记录/P13-留言板与聊天.md)、[P14](docs/backend/交付记录/P14-公告通知与偏好.md)、[P15](docs/backend/交付记录/P15-帮助求助与举报.md)、[P16 验收](docs/backend/交付记录/P16-最终验收与交付.md)。

## 目录

```
src/main/java/cn/gulupai/server/
├── common/        # 信封、校验、安全、幂等、限流、审计、时钟、分页
├── identity/      # AUTH、本人资料、CLI 导入
├── course/        # 课程、广场、作业、课程码、入课申请
├── teaching/      # 教师权属、认领、转交、班级授权
├── team/          # 项目、申请、邀请、席位预留、组队码
├── role/          # 岗位协商
├── file/          # 私有文件与授权传输
├── task/          # 看板、阶段和依赖
├── delivery/      # 正式分工、提交、验收和贡献确认
├── governance/    # 人员事项与交接
├── merge/         # 广场合并
├── grade/         # 教学成绩与复核
└── report/        # 报告、交付版本与项目归档
```
