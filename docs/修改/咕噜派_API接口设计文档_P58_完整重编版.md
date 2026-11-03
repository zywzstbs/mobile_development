# 咕噜派 API 接口设计文档：P58 完整重编版

编写日期：2026-11-02。产品依据：V13 业务设计与 Android P58（`0.59.0-p58`）。业务地址前缀：`/api/v1`。

本文独立组织当前项目的完整接口契约，供客户端与服务器开发人员共同实施。它定义服务器应提供的协议，**不表示这些服务已经部署或完成真实联调**。文中的账号、编号、时间、材料地址及成绩均为虚构示例。原有接口文档保留，不作为阅读本文的前置条件。

路径和字段沿用客户端已经使用的命名；重新梳理接口并不要求客户端换协议版本。本文不创建服务器程序、不改变 App 业务规则，也不把演示数据迁移成正式教学数据。

## 阅读入口

- [1. 范围与系统职责](#scope)
- [2. 身份、资源与访问控制](#access)
- [3. 统一通信规范](#wire)
- [4. 写入、版本、幂等与恢复](#consistency)
- [5. 状态与关键业务约束](#states)
- [6. 公共字段和引用类型](#common)
- [7. 错误处理](#errors)
- [8. 全量接口目录与逐项定义](#operations)
- [9. 完整响应结构字典](#responses)
- [10. 客户端接入与设计缺口](#implementation)
- [11. 部署、数据保留与联调验收](#acceptance)
- [12. 文档依据与维护](#sources)

<a id="scope"></a>

## 1. 范围与系统职责

本版覆盖 20 个业务模块的 281 个 HTTP 方法与路径组合。分页或筛选参数变化不另计接口。每项给出路径参数、查询参数、正文结构、权限、动作约束及响应字典；统一规则同样适用于每项。

|系统|负责事项|不能代替的事实|
|---|---|---|
|Android 客户端|页面、表单、明确确认、账号与数据来源隔离、错误恢复、前台同步、本地缓存与导出|按钮可见性不能证明有权写入；动画不能证明服务器投骰；上传完成提示不能证明文件 READY|
|业务服务器|认证、当前权限、资源编号与版本、服务端时间、唯一约束、事务、状态机、正式快照、路由和访问投影|不能信任客户端自报角色、操作者、分数、课程名单或投票资格|
|学校与课程负责人|核验用户、教师、选课事实、校历与教学规则|课程名称、持码和学生自报资料不能代替可信选课记录|
|文件存储|在授权范围内接受原始字节及短期下载|PUT 返回成功不能代替业务服务器的内容校验及关联授权|
|运维管理工具|受控开户、账号恢复、教师认领复核、真实受理渠道及清理配置|部署管理身份不等同 App 内的班级助理|

以下功能属于本机能力，不增加 HTTP 接口：P58 十款桌面图标选择及默认恢复；P57 像素主题绘制；系统扫码与二维码图片生成/分享；摇动识别和骰子展示动画；本地便签、附件库、报告导出和截止提醒；本人 BYOK AI 草稿及调用额度账本。涉及正式结果时仍调用对应业务接口，例如扫码后解析课程码、投骰后由服务器返回点数、组长明确发布 AI 参考文字。

Debug 本机预览与测试夹具不属于服务器实现。Preview 内部的 `/app-config`、`/schools` 路由没有当前正式客户端调用契约，本版不把它们扩成新增生产服务。学校范围由已核验会话及课程字典确定。

<a id="access"></a>

## 2. 身份、资源与访问控制

资源主线是：`schoolId → termId → squareId → assignmentId → projectId → taskId`。课程广场为真实教学班，作业是一次独立协作范围。自主项目没有 `assignmentId`，不能自动产生课程成绩。`squareNo` 是稳定展示编号；课程加入码、组队码和内部资源 ID 是三种不同数据。

|记号|当前身份或关系|访问边界|
|---|---|---|
|U|已认证用户|只读写本人数据及明确授权资源|
|S|已核验学生|学生操作还需当前课程/项目资格|
|M|当前有效项目成员|该项目协作内容；离组后停止云端正文和引用访问|
|L|当前项目组长|属于学生及项目内关系，不是全局管理角色|
|R|当前有效独立验收人|固定到当前任务/提交版本；不得同时是本次被验收贡献者|
|T|当前负责该课程的教师|人员程序、课程评分及复核；不代组内分工、成果验收或贡献确认|
|A|教师明确授权的班级助理|只限授予的课程、能力和期限；涉及本组时回避|
|O|受控软件维护/受理人员|仅对应帮助、求助或内容举报的当前指派范围|

学生侧“班级管理员”的服务身份值为 `CLASS_ADMIN`；公告发布身份字段使用 `CLASS_ASSISTANT`，两者不可随意互换。兼任教师和助理时，通知发布必须明确本次 `publisherRole`。

班级授权能力为 `PERSONNEL_READ`、`PERSONNEL_HANDLE`、`TEAM_SUMMARY_READ`、`ANNOUNCEMENT_WRITE`、`MATERIAL_REQUEST_HANDLE`、`COURSE_JOIN_CODE_MANAGE`、`COURSE_MEMBERSHIP_REVIEW`。维护帮助、处理求助和举报另需 `HELP_CONTENT_WRITE`、`SUPPORT_HANDLE`、`CONTENT_REPORT_HANDLE` 及真实指派；仅有教师或助理角色不会自动取得软件全站权限。没有助理改分能力，也没有教师全群聊天读取能力。

所有读取、搜索、分页、导出、下载、幂等重放和引用预览都重新检查当前资格。响应中的 `can*`、`capabilities`、`availability` 是界面提示，最终写入必须再次核验。目标组长只读转入所需摘要；普通相关学生只读本人事项及本人材料；完整人员事实、他人补充资料、原始互评、评分更正及私人求助不得加入公开项目或共享报告。撤回内容的旧修订同样受限。

<a id="wire"></a>

## 3. 统一通信规范

### 3.1 地址、Header 与编码

完整地址为 `${API_BASE_URL}/api/v1` 加接口路径。部署时提供真实 HTTPS 主机，不能把文档示例地址当服务地址。测试/正式环境分别管理用户、会话、业务记录、码和文件。

```http
POST /api/v1/tasks/task_example_1/claims HTTP/1.1
Accept: application/json
Content-Type: application/json; charset=utf-8
Authorization: Bearer <accessToken>
Idempotency-Key: <one-operation-uuid>

{"expectedRevision":1,"startNow":false}
```

GET 不发 JSON 正文；无业务字段的动作发 `{}`。除登录、刷新和允许匿名的通用帮助读取外均需 Bearer。写入携带 `Idempotency-Key`，登录/刷新除外；只读 POST 定位/匹配可携带但不得建立成员关系。客户端使用 1～128 位非空白可打印 ASCII 操作标识，建议 UUID。

JSON UTF-8；请求正文上限 1MiB，业务响应上限 2MiB。当前业务 HTTP 使用 `HttpURLConnection` 和 Gson，连接超时 15 秒、读取超时 25 秒，不跟随重定向。Release 仅允许 HTTPS；Debug 的受控 HTTP 联调不构成正式环境降级许可。不要向存储 URL 转发业务 Bearer、Cookie 或业务幂等标识。

### 3.2 成功、失败与数据类型

业务成功均返回 HTTP200 和如下信封，包括创建与动作；不返回 204、裸对象、裸数组或 HTML。

```json
{"code":"OK","message":"成功","data":{"items":[],"nextCursor":null,"hasMore":false,"total":0}}
```

失败使用正确 HTTP 状态，`data` 为 null：

```json
{"code":"RESOURCE_CHANGED","message":"内容已更新，请重新读取后确认","data":null}
```

ID 为不透明 string，1～120 位 `[A-Za-z0-9_-]`，不能按前缀推测身份或课程。bool 使用 JSON boolean；整数不能用字符串、小数或 bool 代替。点数及百分比权重使用最多两位小数的十进制字符串；教学 `q`、`delta`、`b`、`finalGrade` 为整数。`q` 在查询中则是检索字符串，按字段所在位置区分。

未填写的普通文字按模型返回 `""`；允许缺失关系的字段明确返回 null；集合返回 `[]`。只有尚无子记录的结构可以返回 `{}`，不能把任何完整资源缺项解释为空对象。响应字段应显式返回，权限裁剪字段例外；父对象为 null/{} 或集合为空时，其子字段自然不出现。

时间点输出 UTC RFC3339 `YYYY-MM-DDTHH:mm:ssZ`；输入允许有效偏移并按瞬间比较。纯日期 `YYYY-MM-DD`，学校节次 `HH:mm:ss`，个人免打扰 `HH:mm`，时区使用有效 IANA 名称。截止、迟交、到期及撤回窗口由服务器时钟决定。

字段长度按 UTF-16 code unit，和 Android `String.length()` 一致。普通输入按规则去首尾空白；密码和 token 不 trim。未知正文属性返回422 `FIELD_NOT_ALLOWED`；只读字段不得回写。PATCH 省略不修改，只有明确可空字段才允许以 null 清空。

### 3.3 会话设计

服务器从可信名单受控开户；App 无自选教师身份的公开注册接口。密码由成熟密码哈希方案存储，不保存明文。每设备独立会话，accessToken 和 refreshToken 为至少256位随机不透明值，只存安全摘要，不写日志。

accessToken 30分钟；refreshToken 7天固定到期，刷新只更新 accessToken，不延长原会话期限。注销撤销本会话全部令牌，不影响其他设备；恢复账号需核验本人并撤销旧会话，通过已配置私密渠道交付临时口令。同一账号/IP 15分钟内5次登录失败后，限制15分钟并返回429，错误不泄露账号是否存在。

业务401最多刷新一次，再以原正文/原幂等键重试一次；再次401回登录。刷新网络失败不当登录成功。Android 使用 KeyStore/AES-GCM 保存会话，客户端存储保护不替代服务器认证。

### 3.4 列表完整性与分页

`Page<T> = {items:T[], nextCursor:string|null, hasMore:boolean, total:integer}`。`total` 是当前授权和筛选范围的完整数量，不是本页长度。不得截断后伪报 `hasMore=false`。

|列表|返回方式与顺序|
|---|---|
|学期、节次、课程候选、本人项目/待办、成员、任务、岗位、人员业务列表、成绩名册|完整返回，hasMore=false/nextCursor=null/total=唯一条目数。字典按学校顺序；项目/待办updatedAt降序再id；任务创建顺序再id；成绩按稳定studentId|
|同作业招募及发现候选|完整返回，按projectId去重；在完整有效候选上随机，不从第一页抽样；updatedAt降序再id|
|公告、主题、回复、帮助、工单、举报、消息与修订记录|cursor分页，limit默认20、1～100。公告createdAt降序；主题先置顶再updatedAt降序；回复/修订createdAt正序；其余createdAt降序，均以id打破并列|
|邀请对象候选|q 1～80，固定20项每页；projectRevision与expectedProjectRevision一致；source仅APPLICATION/COURSE；userId稳定排序|
|聊天|afterSequence与beforeSequence互斥；默认50、1～100，items始终sequence正序。aroundSequence定位及q查询不能与前后游标混用|

普通 cursor 是绑定本人、范围、筛选和排序的15分钟快照；到期409 `CURSOR_EXPIRED`。首批固定匹配 ID 集合和顺序，续页仍重验权限；失权跳过，不用越权数据补页，也不把后来新记录插入旧快照。参数改变重读首批。

完整集合序列化超过1.75MiB返回413 `COLLECTION_TOO_LARGE`；整体响应不得超过2MiB。项目任务上限200，单作业成绩名册上限200；超出规模需要真实班级范围设计，不允许客户端猜总数。聊天另外使用7天有效的变更游标，见聊天模块。

<a id="consistency"></a>

## 4. 写入、版本、幂等与恢复

### 4.1 版本不是同一类数字

|字段|锁定内容|
|---|---|
|revision / expectedRevision|本接口动作目标的当前对象版本|
|expectedTaskRevision / expectedProjectRevision / expectedSquareRevision / expectedCaseRevision|明确命名的关联资源版本，不能用当前页面另一对象的revision代替|
|agreementVersion / planVersion / responseVersion|约定、交接方案、材料回应的独立业务版本|
|contentVersion / displayRevision|实质正文版与排版版；不等同对象revision|
|sourceKey / expectedSourceKey / basisDigest|固定的来源集合及内容依据，不能仅用时间或条目数量替代|
|closingCycle / roundNo / sequence|收尾轮次、骰子轮次、提交或聊天顺序，各自有独立语义|

现有对象 revision 为1～999999999，首次私有草稿/本人选票等明确允许的情况用0。服务器在同一事务中比较基准、当前权限和状态，成功后生成新版本。GET 后到写入之间仍可能变化；客户端提前读取不是并发锁。

### 4.2 幂等约束

服务器持久保存7天的 `(environment,userId,HTTP方法,规范路径,Idempotency-Key)` 操作绑定：方法、规范路径/查询、规范 JSON 正文及结果。对象键序不影响规范化，同一键不能换意图。相同键和相同请求返回原结果；异请求409 `IDEMPOTENCY_KEY_REUSED`；正在处理409 `OPERATION_IN_PROGRESS`。重放前重新核验当前访问权，不把历史成功结果暴露给失权者。业务唯一性不能只靠短期幂等记录。

聊天另有 `(projectId,userId,clientMessageId)`，求助/举报另有 `(userId,clientRequestId)`。重复相同正文核对原记录，异正文冲突，不能生成第二条。ID 是防重和查询绑定，不是客户端自行签发的正式 messageId/ticketId。

### 4.3 未知结果与明确拒绝

|观察结果|客户端后续动作|
|---|---|
|明确成功且ID/范围/版本/正文符合原请求|采用服务端结果，刷新详情和原列表|
|首次明确业务拒绝，且可确定没有受理|保留填写；读取当前基准，由本人决定是否以新操作重提|
|超时、断线、5xx、空响应、无法解析或不符合契约的“成功”|结果不明，保存原body/key与原范围，停止同页第二次意图；显式用原请求重试或有权GET核对|
|未知之后再收到4xx、同键冲突或进行中|不能据此抹掉原不确定请求；没有“相同内容/更高版本就是本次成功”的推断|
|页面重建、账号/服务器/课程范围改变|不自动POST/PUT；旧回包不能落到新账号或新页面；本机页面状态不宣称永久后台事务队列|

预览 token 绑定本人、作用域、来源版本、固定参数和到期；预览不执行正式动作，确认时再核验。过期/内容变化必须由本人重新预览。不得在失败后自动换 token 并偷偷提交。

### 4.4 必须由服务器原子完成的动作

1. 本人接受邀请：重验同作业唯一团队、当前资格和席位预留，建立成员并结束对应邀请/申请。
2. 人员变更最终执行：人员许可、有效交接方案、接任本人确认、组长接替、目标席位及两个项目版本同时有效后，改变正式关系；中间步骤不能提前移人。
3. 验收与撤回：争用同一个待验收提交版本；先有效者生效，不能一边验收一边恢复成新草稿。
4. 成绩发布：固定学生集合、当前规则/来源/私有草稿和预览结果，追加不可覆盖的个人快照；关联复核只在实际新发布后结案。
5. 通知修订：正文新版本、接收者快照和该版回执基线同事务生成，旧回执保留。
6. 求助转交：合法新接收范围、负责人、状态和历史同时切换；失败时原负责人继续负责。

<a id="states"></a>

## 5. 状态与关键业务约束

|业务|状态集合|生效边界|
|---|---|---|
|课程关系 / 入课申请|NONE/PENDING/ACTIVE/LEFT；PENDING/APPROVED/REJECTED|课程码只定位；可信选课事实下本人确认，或当前有权人员批准后才建立关系；本人不自审|
|教师认领 / 授权 / 转交|PENDING/ACTIVE/REJECTED/RELINQUISHED；ACTIVE/REVOKED/EXPIRED；PENDING/ACCEPTED/DECLINED/CANCELLED|认领申请不授课权；接任前原教师负责；授权每次核期限|
|项目 / 申请 / 邀请|ACTIVE/CLOSING/ARCHIVED；PENDING/APPROVED/REJECTED/WITHDRAWN/CLOSED；PENDING/ACCEPTED/DECLINED/EXPIRED/REVOKED|申请APPROVED只是邀请与预留，本人ACCEPTED才入组；同作业至多一支正式团队|
|任务 / 提交|TODO/IN_PROGRESS/REVIEW_PENDING/DONE/CANCELLED；PENDING/REVIEWED/WITHDRAWN|普通认领可稍后开始，截止不改；提交固定标准和截止，撤回不删除旧序号|
|正式分项|TODO/REVIEW_PENDING/PASSED/RETURNED|逐分项验收，全部PASSED才派生整任务DONE；本人贡献确认另行记录|
|正式约定 / 调整提案|PENDING/CHANGES_REQUESTED/ACTIVE；PENDING/APPLIED/WITHDRAWN/DECLINED|受影响者有效确认后才覆盖相应未验收项；保留无关项的新记录|
|岗位提议、互换、多人方案|PENDING/APPLIED/DECLINED/WITHDRAWN|来源成员、组长、岗位和容量变化导致不可接受；不改任务责任或贡献|
|退出/转组|WAIT_TARGET/WAIT_PERSONNEL/PLAN/WAIT_ACCEPT/READY/COMPLETED/REJECTED/CANCELLED|COMPLETED前原关系不变；接受目标邀请不立即离开原组|
|表决移出|STATEMENT/VOTING/QUALIFICATION_REVIEW/WAIT_PERSONNEL/HANDOVER/COMPLETED/REJECTED/CANCELLED|陈述、资格、人员与交接独立完成，组长不能直接移人|
|人员事项 / 补充材料 / 交接|OPEN/IN_PROGRESS/CLOSED；OPEN/SUBMITTED/RETURNED/ACCEPTED/WITHDRAWN；PENDING/ACCEPTED/RETURNED/CANCELLED|材料处理不自动结案；教师/助理交接不是代替学生接任务|
|广场合并|VOTING/QUALIFICATION_REVIEW/WAIT_FACT/READY/COMPLETED/REJECTED/CANCELLED|双方冻结名单分别严格过半，教学事实与权属核对后执行；旧作业成绩保持分离|
|成绩复核|PENDING/REVIEWING/RESOLVED_MAINTAINED/AWAITING_REVISED_PUBLICATION/RESOLVED_REVISED|拟修订不等于已修订，必须关联实际新成绩快照|
|作品、原始互评、教师文字评价|ACTIVE/WITHDRAWN；评价展示SHOW/HIDE|撤回不改正式课程分；展示授权绑定确切内容版本|
|骰子会话|INVITING/ROLLING/TIE_ROUND/AWAITING_CLAIM/CLAIMED/UNRESOLVED/DECLINED/TERMINATED/EXPIRED/INVALIDATED|接受投骰不等于接受责任；胜者确认后仍调用原claims，成功才CLAIMED|
|主题 / 聊天|OPEN/RESOLVED/CLOSED加withdrawn；聊天本机SENDING/SENT/FAILED/UNKNOWN|撤回保留占位；本机发送状态不能写成服务器业务状态|
|通知|PUBLISHED/REVISED/REVOKED/EXPIRED|阅读、知悉和原业务办理分别记录，按contentVersion隔离|
|私人求助 / 举报|SUBMITTED/IN_PROGRESS/WAITING_USER/ANSWERED/CLOSED；SUBMITTED/IN_PROGRESS/HANDLED/CLOSED|只有提问者确认才resolvedByRequester；举报不自动移人或扣分|
|文件|UPLOADING/READY/FAILED|服务器验证真实字节后才READY，未完成不得正式引用|

正式教学：履约比例 `B = 已验收且确认有效的责任点数 / 有效责任点数 × 100`，通过显式结算形成整数 b；分母0时未具备评分条件，不伪造100或0分。教师逐人给 q（0～100）及有依据的 delta（-100～100）。`G = clamp(roundHalfUp((b×w + q×(100-w))/100) + delta, 0, 100)`，w 为教师明确确认的 processWeight，不设固定50/30/20。来源或规则变化后旧结算/草稿不可直接发布。AI与同学合作体验不自动改分。

五栏协作报告固定为overview（项目概况）、divisionAndProcess（分工与过程）、deliveriesAndAcceptance（交付与验收）、memberWork（成员工作）、retrospective（复盘总结）；共同贡献按本人确认有效份额展示，不把上传者或统筹者算成独占作者。过程/最终/空项目快照分别固定来源与收尾轮次；非空归档同时要求当前有效交付和本轮最终报告，空项目写明原因，不虚构完成率。

<a id="common"></a>

## 6. 公共字段和引用类型

### 6.1 输入上限

|字段族|限制|
|---|---|
|登录|loginId 1～120；password 1～256且不trim；deviceLabel 0～80，省略为Android App|
|名称|显示名1～80、完整课程名1～160、作业/项目名1～120、岗位名1～60、任务标题1～160；QUICK未命名允许null并由服务器提供displayName|
|普通说明|intro/description 0～2000；职责1～1000；理由/意见一般0～1000；拒绝他人、撤销、终止、结案1～500；岗位协商意见最多500|
|队伍与任务|capacity 1～20含组长且满足作业min/max；roles最多20；每项目任务最多200；criteria 1～20条、每条1～500|
|正式分项|units最多20，每项shares最多20；分项及个人份额points为大于0且不超过1000000的最多两位小数字符串（汇总字段允许0），份额总和守恒|
|提交与复盘|summary/workSummary/learning/problems/improvements/coordination最多3000；正式提交summary非空；artifacts最多10；普通提交unitIds=[]|
|聊天与留言|主题title 2～60/content 1～3000；回复/聊天content最多1000；refs≤5、mentions≤20；聊天每秒2条且每分钟30条，主题/回复每分钟10次；有效置顶≤3|
|通知|title 2～60/content 1～5000；知悉期限必须未来且早于expiresAt；每课程有效置顶最多3，P58客户端尚未提供置顶操作|
|帮助与求助|帮助title 1～120，steps≤20且每条≤500；工单title 1～60/content及回复1～3000；图片≤3；每人每日新工单≤5|
|举报|reason及selectedExcerpt各1～1000；每人每日≤10；只提交本人明确选中片段|
|教师评价与互评|教师summary 1～1000；互评comment≤1000、evidenceText≤500；负向观察需要具体事实；不得自评|
|AI项目参考|scene TOPIC_ANALYSIS/TASK_PLAN/DOCUMENT_REVIEW/RECRUITMENT；title 1～80、text 1～16000、model≤120、clientRecordId 1～80的稳定ID|
|检索与码|一般q 0～80，邀请候选1～80；解析每人/IP每分钟20次；服务端码16～128字符、至少128位随机熵|
|文件|单文件1～8388608字节；名称1～120且无路径/控制字符；SHA256有值时64位小写hex；媒体类型与真实签名一致|

### 6.2 ArtifactRef 与 ProofRef

任务、草稿、交付、材料和作品引用须区分两个分支，字段不能混用：

```json
{"kind":"LINK","url":"https://example.invalid/material","title":"示例材料"}
```

```json
{"kind":"FILE","fileId":"file_example_1","title":"示例材料"}
```

LINK 的 url 为有效 http/https 地址，1～2048；FILE 只传当前有权的 READY 文件 ID，不能用短期下载 URL 替代。不能上传 `file://`、content URI、本机私有路径。每次引用与下载均核验所属业务权限；示例有 LINK 不表示 FILE 分支要传 url。课程入课证明的 FILE 与上传用途之间仍有范围契约缺口，见第10节，不能借用人员 caseId 伪造。

### 6.3 受限业务引用与通知路由

协作 `refs` 使用 type/id 及字段表列明的来源定位；限同项目当前可读的任务、提交、交付、报告、主题、回复或聊天消息等白名单。服务器不能因为客户端给出标题或节选就跳过原资源鉴权。来源撤回、删除或失权返回受限占位，预览不泄露旧正文；整理聊天为主题只记录本人新文字和合法引用。

`mentions` 是当前项目成员 ID 列表，不允许任意人员或 @全体。消息中心 `route` 只描述 App 白名单页面及资源 ID，不执行脚本或任意外部地址。`actionRequired` 不等于原待办已经处理。

### 6.4 文件传输与用途

|purpose|必需绑定|读取与客户端现状|
|---|---|---|
|SUPPORT_ATTACHMENT|可选ticketId；新建工单前仅上传本人临时私有文件|当前已接入新建求助及原单图文回复；仅PNG/JPEG/WebP，当前客户端总是计算并发送SHA256|
|DELIVERY_ARTIFACT|projectId，当前项目成员|服务器目标契约；当前一般交付仍以LINK为主|
|MATERIAL_PROOF|caseId，原人员事项本人/有权处理者|不能直接用于课程加入申请，后者没有相同caseId语义|
|WORK_ARTIFACT|本人；如带来源projectId须有源项目资格|目标文件契约；来源许可仍限制作品展示|

通用文件允许JPEG/PNG/WebP、PDF、TXT、Markdown、DOCX，其他类型拒绝；求助用途仅前三种图片。流程为 `FILE-01 → 返回HTTPS URL的PUT字节 → FILE-02 → READY → 业务引用`。上传授权10分钟，下载授权5分钟。FILE-02 必须校验实际大小、类型/签名和已提供摘要；不接受客户端一句“已上传”。

独立存储请求不带业务会话、不跟随重定向、不自动重试。上传 Header 仅允许 Content-Type、Content-MD5 及必要的 x-amz-/x-goog-/x-ms- 授权头；禁止换行，Content-Type与意图一致。下载检查HTTPS、期限、大小、媒体类型及实际图片签名/解码。当前元数据不提供sha256，不能声称客户端下载与原始上传摘要做了比对。文件临时缓存可能被系统回收，当前客户端没有断点续传或永久后台上传队列。

### 6.5 本机二维码载荷与个人 AI 服务边界

加入码二维码的本机载荷为下述URI，四个参数必须齐全且不得重复，长度上限1024；v固定1，kind仅PROJECT_CODE/COURSE_CODE：

```text
grupi://join?v=1&kind=PROJECT_CODE&code=CODE_EXAMPLE_0001&source=PREVIEW
```

source在本机预览为PREVIEW；远程为REMOTE_加规范API地址UTF-8的SHA256小写hex。规范化包括小写scheme/host、移除默认端口与末尾斜线、保证/api/v1后缀。该值只隔离当前数据来源，不是身份签名或入课资格。解析拒绝不匹配来源、未知/重复参数、非grupi://join地址和不支持版本；不自动打开外部网站。二维码像素不传业务服务器，明确核验后只将原code送往TEAM-19或COURSE-16，类型不混用。

个人BYOK请求由App直连本人指定HTTPS服务，地址补齐/chat/completions，使用本人服务商Bearer密钥和model/messages/stream=false/max_tokens；messages为system/user两项。此请求不是/api/v1接口，不带业务会话，也不把密钥或原始调用记录交给项目服务。连接15秒、读取90秒、总调用120秒、响应上限512KiB，不自动重试或跟随重定向；只接受choices[0].finish_reason=stop且message.content为非空字符串，再按四场景结构校验内容。服务商失败显示AI_HTTP_状态等本地错误，不套用业务OK信封。只有组长明确采用后的参考文字经AI-02上传，不能把个人AI调用视为正式任务或教师评分。

<a id="errors"></a>

## 7. 错误处理

|HTTP|业务码或类别|服务端含义与调用方处理|
|---|---|---|
|400|MALFORMED_JSON、VERSION_REQUIRED、IDEMPOTENCY_KEY_REQUIRED|协议结构/控制参数缺失；保留输入，修正请求，不补造当前版本|
|401|INVALID_CREDENTIALS、SESSION_EXPIRED|认证未通过；按单次刷新规则处理|
|403|FORBIDDEN、RECUSAL_REQUIRED、SELF_REVIEW_FORBIDDEN|当前无权或须回避；不换role绕过，不将错误当空集合|
|404|NOT_FOUND|不存在或无权获知存在；不要泄露其他人的资源|
|409|RESOURCE_CHANGED、SOURCE_CHANGED、DRAFT_CONFLICT、PREVIEW_STALE|对象、来源或预览基准过期；由本人重读和确认|
|409|IDEMPOTENCY_KEY_REUSED、CLIENT_MESSAGE_ID_REUSED、CLIENT_REQUEST_ID_REUSED、OPERATION_IN_PROGRESS|操作绑定冲突或尚未终结；保留原请求，不换键重复写入|
|409|CURSOR_EXPIRED、CHAT_SYNC_EXPIRED|重读相应有权列表；聊天变更游标失效后停止展示过期缓存并补齐历史|
|409|ROLE_FULL、ALREADY_IN_ASSIGNMENT_TEAM、INVITATION_EXPIRED、APPLICATION_EXISTS、SQUARE_MERGED|当前席位/同作业关系/课程定位发生业务冲突，回原资源核对|
|409|MESSAGE_WITHDRAW_WINDOW_CLOSED、TICKET_REOPEN_WINDOW_CLOSED|已超过对应动作窗口；保留原记录，不伪造撤回/重开|
|413|PAYLOAD_TOO_LARGE、FILE_TOO_LARGE、COLLECTION_TOO_LARGE|请求/文件/完整返回超限，不截断假装完整|
|422|VALIDATION_FAILED、FIELD_NOT_ALLOWED、FILE_TYPE_NOT_ALLOWED、POINTS_INVALID、STANDARD_NOT_CHECKED|输入/跨字段/用途限制不符；说明具体字段，不能静默截断或接受未知字段|
|409|DEPENDENCY_BLOCKED、AGREEMENT_REQUIRED、SUBMISSION_ALREADY_REVIEWED、TASK_ALREADY_CLAIMED|任务状态或责任前置变化，读取当前记录，不覆盖既成事实|
|409|DICE_ROUND_CHANGED、DICE_EXPIRED、DICE_OUTCOME_CHANGED、TASK_CHANGED|原轮次或来源失效，核对原会话与任务，不重生成点数|
|409|ANNOUNCEMENT_VERSION_CHANGED、ACK_NOT_REQUESTED、GRADE_NOT_READY、UNIT_NOT_ASSIGNED、PROJECT_READ_ONLY、PIN_LIMIT_REACHED|正文/教学/分项/可写范围不满足，保留原输入，不造默认结果|
|409|CODE_INVALID、CODE_DISABLED、CODE_EXPIRED、CODE_REVOKED|定位码失效，不自动替换目标|
|429|RATE_LIMITED、REMINDER_RATE_LIMITED|频率限制，Retry-After为秒数；遵守等待提示，不自动连发|
|503|SUPPORT_ROUTE_UNAVAILABLE、REPORT_ROUTE_UNAVAILABLE、COURSE_REVIEW_UNAVAILABLE|没有真实合法接收者或依赖未配置；不得返回虚假单号或无人队列|
|5xx|服务内部或存储故障|脱敏提示；写入先按未知结果处理|

每项接口列出的典型失败只是测试入口，不是唯一可能失败。业务错误应与当前状态一致。`CONTRACT_ERROR`、`EMPTY_RESPONSE`、`RESPONSE_TOO_LARGE`、本地图片校验及连接地址错误还可能由客户端产生，不能冒充服务器成功响应。失败正文不包含密码、token、存储授权密钥、SQL、堆栈或他人的私人材料。

<a id="operations"></a>

## 8. 全量接口目录与逐项定义

接口编号沿用现有方法/路径标识，便于团队核对，正文和组织按当前业务重新编写。所有相对路径都在`/api/v1`下；通用Header、权限重验、幂等和错误约定见第2～7节。响应结构在第9节完整列出。接口定义是目标契约，不是服务器可调用性清单。

|模块|接口数|范围|
|---|---|---|
|[8.1](#module-1)|8|账号与本人资料|
|[8.2](#module-2)|22|学校学期、课程定位、作业与入课|
|[8.3](#module-3)|26|项目招募、申请邀请与行动概况|
|[8.4](#module-4)|21|岗位定义与成员协商|
|[8.5](#module-5)|10|任务列表、认领、阶段与依赖|
|[8.6](#module-6)|21|正式分工、提交、验收与草稿|
|[8.7](#module-7)|41|人员变更、表决、补充资料与事项交接|
|[8.8](#module-8)|7|重复课程广场合并|
|[8.9](#module-9)|10|教师工作台、班级授权与权属|
|[8.10](#module-10)|24|课程计分、成绩发布、复核与教师评价|
|[8.11](#module-11)|12|复盘、五栏报告、交付发布与归档|
|[8.12](#module-12)|12|画像、作品、合作记录与互评|
|[8.13](#module-13)|2|组长确认的个人 AI 项目参考|
|[8.14](#module-14)|6|正式任务骰子会话|
|[8.15](#module-15)|14|项目留言主题、回复及修订|
|[8.16](#module-16)|6|项目文字聊天与连续阅读游标|
|[8.17](#module-17)|15|课程通知、回执、消息中心与偏好|
|[8.18](#module-18)|16|帮助条目与私人求助|
|[8.19](#module-19)|4|限定片段举报与受理事实|
|[8.20](#module-20)|4|云文件意图、内容确认与短期下载|

<a id="module-1"></a>

### 8.1 账号与本人资料

**当前权限：** U；登录与刷新不要求accessToken。本人画像不允许修改用户身份。

- AUTH-08读取会话确认的本人信息，而不是招募权限。AUTH-02/03读取本人画像及可见范围；AUTH-06/07仅保存本人可编辑字段，首次版本0，后续按当前revision。skills最多12项、每项1～80。
- PROFILE可见性PRIVATE/TEAM/CLASS与作品来源许可取交集，不能借调整可见性获得原本无权的数据。登录/刷新/注销遵守第3节会话规则，注销当前设备会话可安全重复。

当前代码定位：[AccountScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/AccountScreen.java)、[ProfileScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/ProfileScreen.java)、[DataGateway.java](../android-app/app/src/main/java/com/grupi/app/data/DataGateway.java)、[RemoteBackend.java](../android-app/app/src/main/java/com/grupi/app/data/RemoteBackend.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[AUTH-01](#api-auth-01)|`DELETE /auth/sessions/current`|注销当前设备会话|
|[AUTH-02](#api-auth-02)|`GET /me/profile`|读取 · 本人 / 画像|
|[AUTH-03](#api-auth-03)|`GET /me/profile-visibility`|读取 · 本人 / 画像可见范围|
|[AUTH-04](#api-auth-04)|`POST /auth/sessions`|提交 · 认证 / 会话|
|[AUTH-05](#api-auth-05)|`POST /auth/sessions/refresh`|提交 · 认证 / 会话 / 刷新访问令牌|
|[AUTH-06](#api-auth-06)|`PUT /me/profile`|保存 · 本人 / 画像|
|[AUTH-07](#api-auth-07)|`PUT /me/profile-visibility`|保存 · 本人 / 画像可见范围|
|[AUTH-08](#api-auth-08)|`GET /me`|读取 · 本人|

<a id="api-auth-01"></a>

#### AUTH-01 — 注销当前设备会话

`DELETE /api/v1/auth/sessions/current`

权限：U，仅注销当前设备会话。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

无。

请求示例：

```json
{}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_AUTH_01](#response-r-auth-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-auth-02"></a>

#### AUTH-02 — 读取 · 本人 / 画像

`GET /api/v1/me/profile`

权限：U，仅本人画像。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/profile`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_AUTH_02](#response-r-auth-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-auth-03"></a>

#### AUTH-03 — 读取 · 本人 / 画像可见范围

`GET /api/v1/me/profile-visibility`

权限：U，仅本人可见范围。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/profile-visibility`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_AUTH_03](#response-r-auth-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-auth-04"></a>

#### AUTH-04 — 提交 · 认证 / 会话

`POST /api/v1/auth/sessions`

权限：未登录，受控开通账号。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`loginId`|string|必填|受控开通的登录账号；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`password`|string|必填|登录口令，不trim/不记录；遵守本字段类型、统一上限及对应业务条件|
|`deviceLabel`|string|可省略，默认Android App；1～80|本次设备说明；可省略，默认Android App；1～80|

请求示例：

```json
{
  "loginId": "student_example_01",
  "password": "test-password-only",
  "deviceLabel": "Android App"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_AUTH_04](#response-r-auth-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP401 / `INVALID_CREDENTIALS`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-auth-05"></a>

#### AUTH-05 — 提交 · 认证 / 会话 / 刷新访问令牌

`POST /api/v1/auth/sessions/refresh`

权限：有效refreshToken所属会话。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`refreshToken`|string|必填|本设备固定到期刷新令牌；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|

请求示例：

```json
{
  "refreshToken": "test-refresh-token"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_AUTH_04](#response-r-auth-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP401 / `SESSION_EXPIRED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-auth-06"></a>

#### AUTH-06 — 保存 · 本人 / 画像

`PUT /api/v1/me/profile`

权限：U，仅本人画像。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`displayName`|string|必填|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`skills`|string[]|必填|本人技能标签；遵守本字段类型、统一上限及对应业务条件|
|`weeklyHours`|int|必填|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`availability`|string|可省略；文字默认空，集合默认[]|来源是否仍允许本动作的只读原因码；遵守本字段类型、统一上限及对应业务条件|
|`collaborationGoal`|string|必填|协作目标；遵守本字段类型、统一上限及对应业务条件|
|`intro`|string|可省略；文字默认空，集合默认[]|项目简介；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 0,
  "displayName": "示例学生",
  "skills": [
    "Java"
  ],
  "weeklyHours": 4,
  "availability": "周三晚",
  "collaborationGoal": "按时完成课程设计",
  "intro": "负责客户端开发"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_AUTH_02](#response-r-auth-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-auth-07"></a>

#### AUTH-07 — 保存 · 本人 / 画像可见范围

`PUT /api/v1/me/profile-visibility`

权限：U，仅本人可见范围。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`worksAudience`|string|必填|作品可见范围；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaryVisible`|bool|必填|本人是否允许展示已明确授权的教师文字评价；遵守本字段类型、统一上限及对应业务条件|
|`cooperationSummaryVisible`|bool|必填|本人是否允许展示满足匿名阈值的合作汇总；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 0,
  "worksAudience": "PRIVATE",
  "teacherSummaryVisible": false,
  "cooperationSummaryVisible": false
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_AUTH_03](#response-r-auth-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-auth-08"></a>

#### AUTH-08 — 读取 · 本人

`GET /api/v1/me`

权限：U，仅会话本人。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_AUTH_08](#response-r-auth-08)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-2"></a>

### 8.2 学校学期、课程定位、作业与入课

**当前权限：** U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。

- 定位固定schoolId/termId，课程字典ID与自定义全称二选一；完整教师名和规范slots集合共同匹配。时段按星期/节次/周次排序去重，不能仅因一个时段重合就合并课程。创建token绑定本人、原学校学期和规范输入，有效5分钟；canCreate=false不可创建，规范唯一键应防并发重复。
- 学生代建作业PENDING_CONFIRMATION可先协作；当前教师确认新作业后CONFIRMED。确认不是将其成绩并入另一个既有作业。学校校历、节次及真实选课事实由服务器提供，Debug虚构时间不能上线。
- COURSE-13签发/换码按课程版本，每课一有效码；expiresInSeconds默认86400，300～86400。课程转交/合并/换码撤销旧码但不变squareNo。COURSE-14无有效码data=null，只有当前码管理者能看原码。
- COURSE-16只读核验：已有关系返回ACTIVE；可信名单核验后提供5分钟confirmationToken，否则REQUIRES_REVIEW。COURSE-17本人confirm=true后重验码版本/名单版本/权限，原子建立唯一关系，不接受userId代签。
- COURSE-18无可信事实时提交PENDING申请，message可为空且≤500，proofRefs≤3；已有本人PENDING返回原单。有合法受理者才接收。COURSE-19只本人；COURSE-20/21按本课审核授权读取。COURSE-22决定APPROVE/REJECT且reason 1～500，批准须关联核对后的选课事实并原子建立关系，拒绝membership=null。

当前代码定位：[CourseScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/CourseScreen.java)、[CourseCatalogScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/CourseCatalogScreen.java)、[CourseAdmissionScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/CourseAdmissionScreen.java)、[PreviewCourseAdmissions.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewCourseAdmissions.java)、[TermRules.java](../android-app/app/src/main/java/com/grupi/app/data/TermRules.java)、[CatalogRules.java](../android-app/app/src/main/java/com/grupi/app/data/CatalogRules.java)、[QrScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/QrScreen.java)、[JoinQrRules.java](../android-app/app/src/main/java/com/grupi/app/data/JoinQrRules.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[COURSE-01](#api-course-01)|`GET /assignments/{assignmentId}`|读取 · 作业|
|[COURSE-02](#api-course-02)|`GET /course-names`|读取 · 完整课程字典|
|[COURSE-03](#api-course-03)|`GET /schools/{schoolId}/periods`|读取 · 学校 / 节次字典|
|[COURSE-04](#api-course-04)|`GET /schools/{schoolId}/terms`|读取 · 学校 / 学期字典|
|[COURSE-05](#api-course-05)|`GET /squares`|读取 · 课程|
|[COURSE-06](#api-course-06)|`GET /squares/{squareId}`|读取 · 课程|
|[COURSE-07](#api-course-07)|`GET /squares/{squareId}/assignments`|读取 · 课程 / 作业|
|[COURSE-08](#api-course-08)|`POST /assignments/{assignmentId}/confirmations`|提交 · 作业 / 作业确认|
|[COURSE-09](#api-course-09)|`POST /squares`|提交 · 课程|
|[COURSE-10](#api-course-10)|`POST /squares/matches`|提交 · 课程 / 课程匹配|
|[COURSE-11](#api-course-11)|`POST /squares/{squareId}/assignments`|提交 · 课程 / 作业|
|[COURSE-12](#api-course-12)|`POST /square-number-resolutions`|提交 · 课程编号定位|
|[COURSE-13](#api-course-13)|`POST /squares/{squareId}/join-codes`|提交 · 课程 / 课程加入码|
|[COURSE-14](#api-course-14)|`GET /squares/{squareId}/join-codes/current`|读取 · 课程 / 课程加入码 / 当前会话|
|[COURSE-15](#api-course-15)|`POST /square-join-codes/{codeId}/revocations`|提交 · square-join-codes / 撤销|
|[COURSE-16](#api-course-16)|`POST /square-code-resolutions`|提交 · 课程码核验|
|[COURSE-17](#api-course-17)|`POST /squares/{squareId}/membership-confirmations`|提交 · 课程 / 本人入课确认|
|[COURSE-18](#api-course-18)|`POST /squares/{squareId}/join-requests`|提交 · 课程 / 入课申请|
|[COURSE-19](#api-course-19)|`GET /me/square-membership-requests`|读取 · 本人 / 入课申请|
|[COURSE-20](#api-course-20)|`GET /squares/{squareId}/join-requests`|读取 · 课程 / 入课申请|
|[COURSE-21](#api-course-21)|`GET /square-membership-requests/{requestId}`|读取 · 入课申请|
|[COURSE-22](#api-course-22)|`POST /square-membership-requests/{requestId}/decisions`|提交 · 入课申请 / 审核决定|

<a id="api-course-01"></a>

#### COURSE-01 — 读取 · 作业

`GET /api/v1/assignments/{assignmentId}`

权限：U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/assignments/assignment_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_01](#response-r-course-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-02"></a>

#### COURSE-02 — 读取 · 完整课程字典

`GET /api/v1/course-names`

权限：U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`schoolId`|string|必填|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`q`|string|可省略；按本项及通用分页规则|当前授权范围检索文字；教学草稿中另为整数Q；0～80字，当前有权范围检索|

请求正文：无。

请求形态：`GET /api/v1/course-names?schoolId=school_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_02](#response-r-course-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-03"></a>

#### COURSE-03 — 读取 · 学校 / 节次字典

`GET /api/v1/schools/{schoolId}/periods`

权限：U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`schoolId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`termId`|string|必填|所属学期ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求正文：无。

请求形态：`GET /api/v1/schools/school_example_1/periods?termId=term_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_03](#response-r-course-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-04"></a>

#### COURSE-04 — 读取 · 学校 / 学期字典

`GET /api/v1/schools/{schoolId}/terms`

权限：U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`schoolId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/schools/school_example_1/terms`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_04](#response-r-course-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-05"></a>

#### COURSE-05 — 读取 · 课程

`GET /api/v1/squares`

权限：U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`schoolId`|string|必填|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`termId`|string|必填|所属学期ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`q`|string|可省略；按本项及通用分页规则|当前授权范围检索文字；教学草稿中另为整数Q；0～80字，当前有权范围检索|

请求正文：无。

请求形态：`GET /api/v1/squares?schoolId=school_example_1&termId=term_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_05](#response-r-course-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-06"></a>

#### COURSE-06 — 读取 · 课程

`GET /api/v1/squares/{squareId}`

权限：U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/squares/square_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_06](#response-r-course-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-07"></a>

#### COURSE-07 — 读取 · 课程 / 作业

`GET /api/v1/squares/{squareId}/assignments`

权限：U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/squares/square_example_1/assignments`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_07](#response-r-course-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-08"></a>

#### COURSE-08 — 提交 · 作业 / 作业确认

`POST /api/v1/assignments/{assignmentId}/confirmations`

权限：U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：CONFIRM_NEW|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "CONFIRM_NEW",
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_01](#response-r-course-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-09"></a>

#### COURSE-09 — 提交 · 课程

`POST /api/v1/squares`

权限：U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`schoolId`|string|必填|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`termId`|string|必填|所属学期ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`teacherName`|string|必填|任课教师姓名；遵守本字段类型、统一上限及对应业务条件|
|`slots`|object[]|必填|完整上课时段集合；遵守本字段类型、统一上限及对应业务条件|
|`slots[].weekday`|int|父项存在时必填，分支字段例外|星期1～7；遵守本字段类型、统一上限及对应业务条件|
|`slots[].periodId`|string|父项存在时必填，分支字段例外|所属学校学期的稳定节次ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`slots[].weekPattern`|string|父项存在时必填，分支字段例外|ALL/ODD/EVEN/CUSTOM周次模式；遵守本字段类型、统一上限及对应业务条件|
|`slots[].startWeek`|int|父项存在时必填，分支字段例外|起始周次；遵守本字段类型、统一上限及对应业务条件|
|`slots[].endWeek`|int|父项存在时必填，分支字段例外|结束周次；遵守本字段类型、统一上限及对应业务条件|
|`slots[].customWeeks`|int[]|父项存在时必填，分支字段例外|CUSTOM模式明确选择的周次；遵守本字段类型、统一上限及对应业务条件|
|`courseNameId`|string|与customCourseName二选一必填，不得两者同时传|规范完整课程字典ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`candidateCheckToken`|string|必填|当前调用者是否可didateCheckToken；只读提示，最终写入重验权限；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`customCourseName`|string|与courseNameId二选一必填，不得两者同时传|字典外完整课程名，与courseNameId互斥；与courseNameId二选一必填，不得两者同时传|

请求示例：

```json
{
  "schoolId": "school_example_1",
  "termId": "term_example_1",
  "teacherName": "示例教师",
  "slots": [
    {
      "weekday": 3,
      "periodId": "period_example_1",
      "weekPattern": "ALL",
      "startWeek": 1,
      "endWeek": 16,
      "customWeeks": []
    }
  ],
  "courseNameId": "course_name_example_1",
  "candidateCheckToken": "match-token-test"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_06](#response-r-course-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-10"></a>

#### COURSE-10 — 提交 · 课程 / 课程匹配

`POST /api/v1/squares/matches`

权限：U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`schoolId`|string|必填|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`termId`|string|必填|所属学期ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`teacherName`|string|必填|任课教师姓名；遵守本字段类型、统一上限及对应业务条件|
|`slots`|object[]|必填|完整上课时段集合；遵守本字段类型、统一上限及对应业务条件|
|`slots[].weekday`|int|父项存在时必填，分支字段例外|星期1～7；遵守本字段类型、统一上限及对应业务条件|
|`slots[].periodId`|string|父项存在时必填，分支字段例外|所属学校学期的稳定节次ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`slots[].weekPattern`|string|父项存在时必填，分支字段例外|ALL/ODD/EVEN/CUSTOM周次模式；遵守本字段类型、统一上限及对应业务条件|
|`slots[].startWeek`|int|父项存在时必填，分支字段例外|起始周次；遵守本字段类型、统一上限及对应业务条件|
|`slots[].endWeek`|int|父项存在时必填，分支字段例外|结束周次；遵守本字段类型、统一上限及对应业务条件|
|`slots[].customWeeks`|int[]|父项存在时必填，分支字段例外|CUSTOM模式明确选择的周次；遵守本字段类型、统一上限及对应业务条件|
|`courseNameId`|string|与customCourseName二选一必填，不得两者同时传|规范完整课程字典ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`customCourseName`|string|与courseNameId二选一必填，不得两者同时传|字典外完整课程名，与courseNameId互斥；与courseNameId二选一必填，不得两者同时传|

请求示例：

```json
{
  "schoolId": "school_example_1",
  "termId": "term_example_1",
  "teacherName": "示例教师",
  "slots": [
    {
      "weekday": 3,
      "periodId": "period_example_1",
      "weekPattern": "ALL",
      "startWeek": 1,
      "endWeek": 16,
      "customWeeks": []
    }
  ],
  "courseNameId": "course_name_example_1"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_10](#response-r-course-10)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-11"></a>

#### COURSE-11 — 提交 · 课程 / 作业

`POST /api/v1/squares/{squareId}/assignments`

权限：U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`name`|string|必填|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`requirements`|string|必填|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`hardDueAt`|string|必填|原作业硬截止；RFC3339时间点，响应UTC Z，服务器比较有效性；可空条件见模型|
|`minMembers`|int|必填|作业最少成员数；遵守本字段类型、统一上限及对应业务条件|
|`maxMembers`|int|必填|作业最多成员数；遵守本字段类型、统一上限及对应业务条件|
|`sourceReferences`|object[]|可省略默认[]，最多10个|报告固定来源集合；可省略默认[]，最多10个|
|`sourceReferences[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`sourceReferences[].url`|string|父项存在时必填，分支字段例外|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`sourceReferences[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "name": "课程协作应用",
  "requirements": "完成可演示的课程应用",
  "hardDueAt": "2026-11-09T15:59:59Z",
  "minMembers": 2,
  "maxMembers": 6,
  "sourceReferences": []
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_01](#response-r-course-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-12"></a>

#### COURSE-12 — 提交 · 课程编号定位

`POST /api/v1/square-number-resolutions`

权限：U在本人学校范围读取；学生本人申请；T或对应当前授权A核验入课，禁止本人自审。。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`squareNo`|string|必填|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "squareNo": "TEST-2026-001"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_06](#response-r-course-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-13"></a>

#### COURSE-13 — 提交 · 课程 / 课程加入码

`POST /api/v1/squares/{squareId}/join-codes`

权限：T或本课有效COURSE_JOIN_CODE_MANAGE助理。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`expiresInSeconds`|int|可省略，默认86400，范围300～86400|本次课程码有效秒数；可省略，默认86400，范围300～86400|

请求示例：

```json
{
  "expectedRevision": 1,
  "expiresInSeconds": 86400
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_13](#response-r-course-13)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-14"></a>

#### COURSE-14 — 读取 · 课程 / 课程加入码 / 当前会话

`GET /api/v1/squares/{squareId}/join-codes/current`

权限：T或本课有效COURSE_JOIN_CODE_MANAGE助理。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/squares/square_example_1/join-codes/current`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_13](#response-r-course-13)。当前尚无有效记录时`data=null`。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-15"></a>

#### COURSE-15 — 提交 · square-join-codes / 撤销

`POST /api/v1/square-join-codes/{codeId}/revocations`

权限：T或原码本课当前有效码管理者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`codeId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "本次分享结束"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_13](#response-r-course-13)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-16"></a>

#### COURSE-16 — 提交 · 课程码核验

`POST /api/v1/square-code-resolutions`

权限：当前登录学生本人，只读核验。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`code`|string/null|必填|服务端有效码或响应业务码，按父结构区分；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|

请求示例：

```json
{
  "code": "opaque-test-code-0001"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_16](#response-r-course-16)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-17"></a>

#### COURSE-17 — 提交 · 课程 / 本人入课确认

`POST /api/v1/squares/{squareId}/membership-confirmations`

权限：S本人及绑定本人的有效confirmationToken。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`confirmationToken`|string/null|必填|可信课程事实下绑定本人的确认凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`confirm`|bool|必填|本人明确确认，必须true；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "confirmationToken": "test-confirmation-token",
  "confirm": true
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_17](#response-r-course-17)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-18"></a>

#### COURSE-18 — 提交 · 课程 / 入课申请

`POST /api/v1/squares/{squareId}/join-requests`

权限：S本人，原课程范围有合法审核者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`message`|string|可省略默认空字符串|申请/邀请说明或结果提示，按父结构区分；0～500字|
|`proofRefs`|object[]|可省略默认[]，最多3个|本人入课核验资料引用，最多3项；可省略默认[]，最多3个|
|`proofRefs[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`proofRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`proofRefs[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`proofRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "message": "请核对本人的选课关系。",
  "proofRefs": []
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_18](#response-r-course-18)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-19"></a>

#### COURSE-19 — 读取 · 本人 / 入课申请

`GET /api/v1/me/square-membership-requests`

权限：U，仅本人入课申请。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`status`|string/null|可省略；按本项及通用分页规则|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/me/square-membership-requests`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_19](#response-r-course-19)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-20"></a>

#### COURSE-20 — 读取 · 课程 / 入课申请

`GET /api/v1/squares/{squareId}/join-requests`

权限：T或本课有效COURSE_MEMBERSHIP_REVIEW助理。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`status`|string/null|可省略；按本项及通用分页规则|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/squares/square_example_1/join-requests`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_19](#response-r-course-19)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-21"></a>

#### COURSE-21 — 读取 · 入课申请

`GET /api/v1/square-membership-requests/{requestId}`

权限：申请人本人，或本课当前合法审核者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`requestId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/square-membership-requests/request_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_18](#response-r-course-18)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-course-22"></a>

#### COURSE-22 — 提交 · 入课申请 / 审核决定

`POST /api/v1/square-membership-requests/{requestId}/decisions`

权限：T或本课有效审核授权A；禁止本人自审。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`requestId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：APPROVE/REJECT|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "APPROVE",
  "reason": "已核对可信选课记录"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_22](#response-r-course-22)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-3"></a>

### 8.3 项目招募、申请邀请与行动概况

**当前权限：** 项目摘要按可见范围；S本人申请/确认，L管理招募和邀请，M读组内资料。

- 同次作业一人至多一支正式团队；跨作业分别组队。capacity包含组长且计有效邀请/转组预留。QUICK允许暂未命名，DETAILED须名称、简介、完整岗位及组长岗位。
- 申请message非空，详细项目须有效roleSlotId。组长APPROVE产生待本人接受的邀请及席位预留，不能直接入组；REJECT须理由。仅本人ACCEPT/DECLINE，24小时邀请到期后释放预留，接受与唯一成员约束同事务。
- TEAM-10只搜索本组有效申请或同课已有协作范围内的候选，q 1～80、20条每页、固定projectRevision与source=APPLICATION/COURSE；搜索不预占席位。
- TEAM-11组队码仅M展示、L管理；TEAM-22的ENABLE/DISABLE/ROTATE按项目版本。解析码只定位/发起原申请，不证明课程资格，不绕过邀请最终确认。归档或换码使旧码失效。
- TEAM-25/26根据真实业务派生ACTION/WAITING及数量，入口指向白名单资源。消息清已读和普通聊天不会完成原业务待办。

当前代码定位：[ProjectScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/ProjectScreen.java)、[TeamSettingsScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/TeamSettingsScreen.java)、[TodoScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/TodoScreen.java)、[PreviewEngine.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewEngine.java)、[PreviewRecruitment.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewRecruitment.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[TEAM-01](#api-team-01)|`GET /assignments/{assignmentId}/recruitments`|读取 · 作业 / 招募列表|
|[TEAM-02](#api-team-02)|`GET /invitations/{invitationId}`|读取 · 邀请|
|[TEAM-03](#api-team-03)|`GET /me/applications`|读取 · 本人 / 申请|
|[TEAM-04](#api-team-04)|`GET /me/invitations`|读取 · 本人 / 邀请|
|[TEAM-05](#api-team-05)|`GET /me/projects`|读取 · 本人 / 项目|
|[TEAM-06](#api-team-06)|`GET /me/work-source-projects`|读取 · 本人 / 作品来源项目|
|[TEAM-07](#api-team-07)|`GET /projects/{projectId}`|读取 · 项目|
|[TEAM-08](#api-team-08)|`GET /projects/{projectId}/applications`|读取 · 项目 / 申请|
|[TEAM-09](#api-team-09)|`GET /projects/{projectId}/invitations`|读取 · 项目 / 邀请|
|[TEAM-10](#api-team-10)|`GET /projects/{projectId}/invite-candidates`|读取 · 项目 / 邀请候选|
|[TEAM-11](#api-team-11)|`GET /projects/{projectId}/join-code`|读取 · 项目 / 组队码|
|[TEAM-12](#api-team-12)|`GET /projects/{projectId}/members`|读取 · 项目 / 成员|
|[TEAM-13](#api-team-13)|`GET /projects/{projectId}/recruitment`|读取 · 项目 / 招募配置|
|[TEAM-14](#api-team-14)|`PATCH /projects/{projectId}`|修改 · 项目|
|[TEAM-15](#api-team-15)|`POST /applications/{applicationId}/decisions`|提交 · 申请 / 审核决定|
|[TEAM-16](#api-team-16)|`POST /applications/{applicationId}/withdrawals`|提交 · 申请 / 撤回|
|[TEAM-17](#api-team-17)|`POST /invitations/{invitationId}/responses`|提交 · 邀请 / 本人回应|
|[TEAM-18](#api-team-18)|`POST /invitations/{invitationId}/revocations`|提交 · 邀请 / 撤销|
|[TEAM-19](#api-team-19)|`POST /project-code-resolutions`|提交 · 组队码定位|
|[TEAM-20](#api-team-20)|`POST /projects`|提交 · 项目|
|[TEAM-21](#api-team-21)|`POST /projects/{projectId}/applications`|提交 · 项目 / 申请|
|[TEAM-22](#api-team-22)|`POST /projects/{projectId}/code-actions`|提交 · 项目 / 组队码管理|
|[TEAM-23](#api-team-23)|`POST /projects/{projectId}/invitations`|提交 · 项目 / 邀请|
|[TEAM-24](#api-team-24)|`PUT /projects/{projectId}/recruitment`|保存 · 项目 / 招募配置|
|[TEAM-25](#api-team-25)|`GET /me/dashboard`|读取 · 本人 / 行动概况|
|[TEAM-26](#api-team-26)|`GET /me/todos`|读取 · 本人 / 待办与等待进展|

<a id="api-team-01"></a>

#### TEAM-01 — 读取 · 作业 / 招募列表

`GET /api/v1/assignments/{assignmentId}/recruitments`

权限：项目摘要按可见范围；S本人申请/确认，L管理招募和邀请，M读组内资料。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`role`|string|可省略；按本项及通用分页规则|本条读取的角色筛选，不授予身份；招募为岗位名；帮助条目为本人实际身份STUDENT/TEACHER/CLASS_ADMIN，不可通过参数越权|
|`mode`|string|可省略；按本项及通用分页规则|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`professionalDirection`|string|可省略；按本项及通用分页规则|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`q`|string|可省略；按本项及通用分页规则|当前授权范围检索文字；教学草稿中另为整数Q；0～80字，当前有权范围检索|
|`purpose`|string|可省略；按本项及通用分页规则|文件的固定业务用途；遵守本字段类型、统一上限及对应业务条件|

请求正文：无。

请求形态：`GET /api/v1/assignments/assignment_example_1/recruitments`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_01](#response-r-team-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-02"></a>

#### TEAM-02 — 读取 · 邀请

`GET /api/v1/invitations/{invitationId}`

权限：邀请接收者本人或原项目当前组长，按最少投影。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`invitationId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/invitations/invitation_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_02](#response-r-team-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-03"></a>

#### TEAM-03 — 读取 · 本人 / 申请

`GET /api/v1/me/applications`

权限：U，仅本人申请。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/applications`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_03](#response-r-team-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-04"></a>

#### TEAM-04 — 读取 · 本人 / 邀请

`GET /api/v1/me/invitations`

权限：U，仅本人邀请。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/invitations`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_04](#response-r-team-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-05"></a>

#### TEAM-05 — 读取 · 本人 / 项目

`GET /api/v1/me/projects`

权限：U，仅本人有权项目。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/projects`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_05](#response-r-team-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-06"></a>

#### TEAM-06 — 读取 · 本人 / 作品来源项目

`GET /api/v1/me/work-source-projects`

权限：U，仅本人有权作品来源。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/work-source-projects`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_06](#response-r-team-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-07"></a>

#### TEAM-07 — 读取 · 项目

`GET /api/v1/projects/{projectId}`

权限：项目摘要按可见范围；S本人申请/确认，L管理招募和邀请，M读组内资料。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_07](#response-r-team-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-08"></a>

#### TEAM-08 — 读取 · 项目 / 申请

`GET /api/v1/projects/{projectId}/applications`

权限：L，本项目申请。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/applications`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_03](#response-r-team-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-09"></a>

#### TEAM-09 — 读取 · 项目 / 邀请

`GET /api/v1/projects/{projectId}/invitations`

权限：L，本项目邀请。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/invitations`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_04](#response-r-team-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-10"></a>

#### TEAM-10 — 读取 · 项目 / 邀请候选

`GET /api/v1/projects/{projectId}/invite-candidates`

权限：L，本项目当前基准及候选范围。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`q`|string|必填|当前授权范围检索文字；教学草稿中另为整数Q；1～80字|
|`expectedProjectRevision`|int|必填|读取并固定的项目并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/invite-candidates?q=%E7%A4%BA%E4%BE%8B&expectedProjectRevision=1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_10](#response-r-team-10)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-11"></a>

#### TEAM-11 — 读取 · 项目 / 组队码

`GET /api/v1/projects/{projectId}/join-code`

权限：M，原项目组队码。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/join-code`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_11](#response-r-team-11)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-12"></a>

#### TEAM-12 — 读取 · 项目 / 成员

`GET /api/v1/projects/{projectId}/members`

权限：项目摘要按可见范围；S本人申请/确认，L管理招募和邀请，M读组内资料。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/members`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_12](#response-r-team-12)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-13"></a>

#### TEAM-13 — 读取 · 项目 / 招募配置

`GET /api/v1/projects/{projectId}/recruitment`

权限：项目摘要按可见范围；S本人申请/确认，L管理招募和邀请，M读组内资料。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/recruitment`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_13](#response-r-team-13)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-14"></a>

#### TEAM-14 — 修改 · 项目

`PATCH /api/v1/projects/{projectId}`

权限：L，当前可写项目。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`name`|string|必填|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`intro`|string|可省略；文字默认空，集合默认[]|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`discussionTime`|string|可省略；文字默认空，集合默认[]|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`capacity`|int|必填|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "name": "课程协作应用",
  "intro": "负责客户端开发",
  "discussionTime": "周三晚",
  "capacity": 4
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_07](#response-r-team-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-15"></a>

#### TEAM-15 — 提交 · 申请 / 审核决定

`POST /api/v1/applications/{applicationId}/decisions`

权限：L，原项目当前待处理申请。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`applicationId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`decision`|string|必填|本人明确提交的决定；允许值：APPROVE/REJECT|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "按实际协作情况说明",
  "decision": "APPROVE"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_15](#response-r-team-15)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-16"></a>

#### TEAM-16 — 提交 · 申请 / 撤回

`POST /api/v1/applications/{applicationId}/withdrawals`

权限：申请者本人，原待处理申请。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`applicationId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_16](#response-r-team-16)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-17"></a>

#### TEAM-17 — 提交 · 邀请 / 本人回应

`POST /api/v1/invitations/{invitationId}/responses`

权限：邀请接收者本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`invitationId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/DECLINE|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "ACCEPT"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_17](#response-r-team-17)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-18"></a>

#### TEAM-18 — 提交 · 邀请 / 撤销

`POST /api/v1/invitations/{invitationId}/revocations`

权限：L，原项目有效邀请。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`invitationId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_18](#response-r-team-18)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-19"></a>

#### TEAM-19 — 提交 · 组队码定位

`POST /api/v1/project-code-resolutions`

权限：U，只读定位原组队码。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`code`|string/null|必填|服务端有效码或响应业务码，按父结构区分；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|

请求示例：

```json
{
  "code": "TEST-GROUP-CODE-0001"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_19](#response-r-team-19)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-20"></a>

#### TEAM-20 — 提交 · 项目

`POST /api/v1/projects`

权限：S，当前课程/作业有组队资格。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`squareId`|string|必填|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`assignmentId`|string|必填|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`mode`|string|必填|当前业务模式；QUICK/DETAILED；DETAILED的name/intro/roles/leaderRoleKey必填|
|`name`|string|必填|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`intro`|string|可省略；文字默认空，集合默认[]|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`capacity`|int|必填|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|
|`discussionTime`|string|可省略；文字默认空，集合默认[]|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`roles`|object[]|必填|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`roles[].clientKey`|string|父项存在时必填，分支字段例外|本次输入中新增项唯一键，服务器会生成稳定id；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`roles[].roleName`|string|父项存在时必填，分支字段例外|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`roles[].duty`|string|父项存在时必填，分支字段例外|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`roles[].total`|int|父项存在时必填，分支字段例外|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`leaderRoleKey`|string|新建详细项目/快速转详细时必填，指向roles新行clientKey或稳定id|快速转详细时组长选定的稳定岗位键；新建详细项目/快速转详细时必填，指向roles新行clientKey或稳定id|

请求示例：

```json
{
  "squareId": "square_example_1",
  "assignmentId": "assignment_example_1",
  "mode": "DETAILED",
  "name": "课程协作应用",
  "intro": "负责客户端开发",
  "capacity": 4,
  "discussionTime": "周三晚",
  "roles": [
    {
      "clientKey": "client",
      "roleName": "客户端开发",
      "duty": "完成App与联调",
      "total": 2
    },
    {
      "clientKey": "server",
      "roleName": "服务器开发",
      "duty": "完成API与持久化",
      "total": 2
    }
  ],
  "leaderRoleKey": "client"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_20](#response-r-team-20)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-21"></a>

#### TEAM-21 — 提交 · 项目 / 申请

`POST /api/v1/projects/{projectId}/applications`

权限：S本人，当前课程/作业和目标项目允许申请。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`message`|string|必填|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`roleSlotId`|string|DETAILED岗位申请/邀请必填；QUICK省略|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求示例：

```json
{
  "message": "希望参与项目协作",
  "roleSlotId": "role_example_2"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_16](#response-r-team-16)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-22"></a>

#### TEAM-22 — 提交 · 项目 / 组队码管理

`POST /api/v1/projects/{projectId}/code-actions`

权限：L，当前项目码管理。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`action`|string|必填|本人明确执行的动作；允许值：ENABLE/DISABLE/ROTATE|

请求示例：

```json
{
  "expectedRevision": 1,
  "action": "ENABLE"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_11](#response-r-team-11)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-23"></a>

#### TEAM-23 — 提交 · 项目 / 邀请

`POST /api/v1/projects/{projectId}/invitations`

权限：L，当前项目可用席位及合法目标。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedProjectRevision`|int|阶段移动/本人选岗/接受岗位方案/项目参考发布必填；邀请请求App发送当前值，服务器核对|读取并固定的项目并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`recipientId`|string|必填|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message`|string|必填|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`roleSlotId`|string|DETAILED岗位申请/邀请必填；QUICK省略|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求示例：

```json
{
  "expectedProjectRevision": 1,
  "recipientId": "user_example_2",
  "message": "希望参与项目协作",
  "roleSlotId": "role_example_2"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_18](#response-r-team-18)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-24"></a>

#### TEAM-24 — 保存 · 项目 / 招募配置

`PUT /api/v1/projects/{projectId}/recruitment`

权限：L，当前可写项目。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`open`|bool|必填|是否开放招募；遵守本字段类型、统一上限及对应业务条件|
|`description`|string|可省略；文字默认空，集合默认[]|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`professionalDirection`|string|必填|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`weeklyHours`|int|必填|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`acceptBeginners`|bool|必填|是否接受初学者；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "open": true,
  "description": "实现登录与会话",
  "professionalDirection": "软件开发",
  "weeklyHours": 4,
  "acceptBeginners": true
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_07](#response-r-team-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-25"></a>

#### TEAM-25 — 读取 · 本人 / 行动概况

`GET /api/v1/me/dashboard`

权限：U，仅本人行动概况。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/dashboard`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_25](#response-r-team-25)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-team-26"></a>

#### TEAM-26 — 读取 · 本人 / 待办与等待进展

`GET /api/v1/me/todos`

权限：U，仅本人待办/等待进展。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`state`|string|可省略；按本项及通用分页规则|该投影的当前状态；遵守本字段类型、统一上限及对应业务条件|

请求正文：无。

请求形态：`GET /api/v1/me/todos`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_26](#response-r-team-26)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-4"></a>

### 8.4 岗位定义与成员协商

**当前权限：** L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。

- ROLE-20只改本人项目岗位；角色容量与有效成员/预留共同校验。ROLE-21完整保存岗位结构，删除/缩减不能挤掉在岗成员或有效预留。QUICK转DETAILED需completeDetails及leaderRoleKey，不能顺便变任务责任。
- 单人提议、双人互换及2～20人方案冻结相关成员/项目/岗位版本，由涉及的本人分别回应；所有必要ACCEPT齐全才原子生效。DECLINE和WITHDRAW保留历史，不默默换参与人。
- 接受时重验expectedProjectRevision和availability。READY以外的PROJECT_NOT_EDITABLE/LEADER_CHANGED/MEMBERSHIP_CHANGED/ROLE_CHANGED/ROLE_CAPACITY_CONFLICT等不可直接生效；岗位名相同不代表原来源仍有效。

当前代码定位：[RoleScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/RoleScreen.java)、[RoleProposalScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/RoleProposalScreen.java)、[RoleSwapScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/RoleSwapScreen.java)、[RolePlanScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/RolePlanScreen.java)、[PreviewRoles.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewRoles.java)、[PreviewRolePlans.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewRolePlans.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[ROLE-01](#api-role-01)|`GET /me/role-plans`|读取 · 本人 / 多人岗位方案|
|[ROLE-02](#api-role-02)|`GET /me/role-proposals`|读取 · 本人 / 岗位提议|
|[ROLE-03](#api-role-03)|`GET /me/role-swaps`|读取 · 本人 / 双人岗位互换|
|[ROLE-04](#api-role-04)|`GET /projects/{projectId}/role-plans`|读取 · 项目 / 多人岗位方案|
|[ROLE-05](#api-role-05)|`GET /projects/{projectId}/role-proposals`|读取 · 项目 / 岗位提议|
|[ROLE-06](#api-role-06)|`GET /projects/{projectId}/role-slots`|读取 · 项目 / 岗位定义|
|[ROLE-07](#api-role-07)|`GET /projects/{projectId}/role-swaps`|读取 · 项目 / 双人岗位互换|
|[ROLE-08](#api-role-08)|`GET /role-plans/{planId}`|读取 · 多人岗位方案|
|[ROLE-09](#api-role-09)|`GET /role-proposals/{proposalId}`|读取 · 岗位提议|
|[ROLE-10](#api-role-10)|`GET /role-swaps/{swapId}`|读取 · 双人岗位互换|
|[ROLE-11](#api-role-11)|`POST /projects/{projectId}/role-plans`|提交 · 项目 / 多人岗位方案|
|[ROLE-12](#api-role-12)|`POST /projects/{projectId}/role-proposals`|提交 · 项目 / 岗位提议|
|[ROLE-13](#api-role-13)|`POST /projects/{projectId}/role-swaps`|提交 · 项目 / 双人岗位互换|
|[ROLE-14](#api-role-14)|`POST /role-plans/{planId}/responses`|提交 · 多人岗位方案 / 本人回应|
|[ROLE-15](#api-role-15)|`POST /role-plans/{planId}/withdrawals`|提交 · 多人岗位方案 / 撤回|
|[ROLE-16](#api-role-16)|`POST /role-proposals/{proposalId}/responses`|提交 · 岗位提议 / 本人回应|
|[ROLE-17](#api-role-17)|`POST /role-proposals/{proposalId}/withdrawals`|提交 · 岗位提议 / 撤回|
|[ROLE-18](#api-role-18)|`POST /role-swaps/{swapId}/responses`|提交 · 双人岗位互换 / 本人回应|
|[ROLE-19](#api-role-19)|`POST /role-swaps/{swapId}/withdrawals`|提交 · 双人岗位互换 / 撤回|
|[ROLE-20](#api-role-20)|`PUT /projects/{projectId}/me/role`|保存 · 项目 / 本人 / 本人岗位|
|[ROLE-21](#api-role-21)|`PUT /projects/{projectId}/role-slots`|保存 · 项目 / 岗位定义|

<a id="api-role-01"></a>

#### ROLE-01 — 读取 · 本人 / 多人岗位方案

`GET /api/v1/me/role-plans`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/role-plans`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_01](#response-r-role-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-02"></a>

#### ROLE-02 — 读取 · 本人 / 岗位提议

`GET /api/v1/me/role-proposals`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/role-proposals`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_02](#response-r-role-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-03"></a>

#### ROLE-03 — 读取 · 本人 / 双人岗位互换

`GET /api/v1/me/role-swaps`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/role-swaps`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_03](#response-r-role-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-04"></a>

#### ROLE-04 — 读取 · 项目 / 多人岗位方案

`GET /api/v1/projects/{projectId}/role-plans`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/role-plans`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_01](#response-r-role-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-05"></a>

#### ROLE-05 — 读取 · 项目 / 岗位提议

`GET /api/v1/projects/{projectId}/role-proposals`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/role-proposals`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_02](#response-r-role-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-06"></a>

#### ROLE-06 — 读取 · 项目 / 岗位定义

`GET /api/v1/projects/{projectId}/role-slots`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/role-slots`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_06](#response-r-role-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-07"></a>

#### ROLE-07 — 读取 · 项目 / 双人岗位互换

`GET /api/v1/projects/{projectId}/role-swaps`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/role-swaps`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_03](#response-r-role-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-08"></a>

#### ROLE-08 — 读取 · 多人岗位方案

`GET /api/v1/role-plans/{planId}`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`planId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/role-plans/plan_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_08](#response-r-role-08)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-09"></a>

#### ROLE-09 — 读取 · 岗位提议

`GET /api/v1/role-proposals/{proposalId}`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/role-proposals/proposal_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_09](#response-r-role-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-10"></a>

#### ROLE-10 — 读取 · 双人岗位互换

`GET /api/v1/role-swaps/{swapId}`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`swapId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/role-swaps/swap_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_10](#response-r-role-10)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-11"></a>

#### ROLE-11 — 提交 · 项目 / 多人岗位方案

`POST /api/v1/projects/{projectId}/role-plans`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`assignments`|object[]|必填|assignments：所属响应结构中的有序记录集合；遵守本字段类型、统一上限及对应业务条件|
|`assignments[].membershipId`|string|父项存在时必填，分支字段例外|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`assignments[].expectedMembershipRevision`|int|父项存在时必填，分支字段例外|读取并固定的成员关系并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`assignments[].targetRoleSlotId`|string|父项存在时必填，分支字段例外|目标岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "assignments": [
    {
      "membershipId": "membership_example_2",
      "expectedMembershipRevision": 1,
      "targetRoleSlotId": "role_example_2"
    },
    {
      "membershipId": "membership_example_3",
      "expectedMembershipRevision": 1,
      "targetRoleSlotId": "role_example_1"
    }
  ],
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_08](#response-r-role-08)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-12"></a>

#### ROLE-12 — 提交 · 项目 / 岗位提议

`POST /api/v1/projects/{projectId}/role-proposals`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`membershipId`|string|必填|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`expectedMembershipRevision`|int|必填|读取并固定的成员关系并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`roleSlotId`|string|DETAILED岗位申请/邀请必填；QUICK省略|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message`|string|必填|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "membershipId": "membership_example_2",
  "expectedMembershipRevision": 1,
  "roleSlotId": "role_example_2",
  "message": "希望参与项目协作"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_09](#response-r-role-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-13"></a>

#### ROLE-13 — 提交 · 项目 / 双人岗位互换

`POST /api/v1/projects/{projectId}/role-swaps`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`firstMembershipId`|string|必填|双人互换第一条固定成员关系；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`secondMembershipId`|string|必填|双人互换第二条固定成员关系；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`expectedFirstMembershipRevision`|int|必填|读取并固定的第一成员关系并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`expectedSecondMembershipRevision`|int|必填|读取并固定的第二成员关系并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "firstMembershipId": "membership_example_2",
  "secondMembershipId": "membership_example_3",
  "expectedFirstMembershipRevision": 1,
  "expectedSecondMembershipRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_10](#response-r-role-10)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-14"></a>

#### ROLE-14 — 提交 · 多人岗位方案 / 本人回应

`POST /api/v1/role-plans/{planId}/responses`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`planId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/DECLINE|
|`comment`|string|可省略，默认空，岗位/约定回应最多500|本人意见；可省略，默认空，岗位/约定回应最多500|
|`expectedProjectRevision`|int|必填；当前项目版本|读取并固定的项目并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "ACCEPT",
  "comment": "按本次约定执行",
  "expectedProjectRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_08](#response-r-role-08)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-15"></a>

#### ROLE-15 — 提交 · 多人岗位方案 / 撤回

`POST /api/v1/role-plans/{planId}/withdrawals`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`planId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_15](#response-r-role-15)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-16"></a>

#### ROLE-16 — 提交 · 岗位提议 / 本人回应

`POST /api/v1/role-proposals/{proposalId}/responses`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/DECLINE|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`expectedProjectRevision`|int|必填；当前项目版本|读取并固定的项目并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "ACCEPT",
  "reason": "按实际协作情况说明",
  "expectedProjectRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_09](#response-r-role-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-17"></a>

#### ROLE-17 — 提交 · 岗位提议 / 撤回

`POST /api/v1/role-proposals/{proposalId}/withdrawals`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_17](#response-r-role-17)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-18"></a>

#### ROLE-18 — 提交 · 双人岗位互换 / 本人回应

`POST /api/v1/role-swaps/{swapId}/responses`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`swapId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/DECLINE|
|`comment`|string|可省略，默认空，岗位/约定回应最多500|本人意见；可省略，默认空，岗位/约定回应最多500|
|`expectedProjectRevision`|int|必填；当前项目版本|读取并固定的项目并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "ACCEPT",
  "comment": "按本次约定执行",
  "expectedProjectRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_10](#response-r-role-10)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-19"></a>

#### ROLE-19 — 提交 · 双人岗位互换 / 撤回

`POST /api/v1/role-swaps/{swapId}/withdrawals`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`swapId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_19](#response-r-role-19)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-20"></a>

#### ROLE-20 — 保存 · 项目 / 本人 / 本人岗位

`PUT /api/v1/projects/{projectId}/me/role`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`expectedProjectRevision`|int|必填；当前项目版本|读取并固定的项目并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`roleSlotId`|string|DETAILED岗位申请/邀请必填；QUICK省略|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求示例：

```json
{
  "expectedRevision": 1,
  "expectedProjectRevision": 1,
  "roleSlotId": "role_example_2"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_07](#response-r-team-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-role-21"></a>

#### ROLE-21 — 保存 · 项目 / 岗位定义

`PUT /api/v1/projects/{projectId}/role-slots`

权限：L维护岗位并发起计划，当前M仅操作本人岗位及本人的待回应事项。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`roles`|object[]|必填|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`roles[].id`|string|父项存在时必填，分支字段例外|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`roles[].roleName`|string|父项存在时必填，分支字段例外|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`roles[].duty`|string|父项存在时必填，分支字段例外|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`roles[].total`|int|父项存在时必填，分支字段例外|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`completeDetails`|object|QUICK转DETAILED必填{name,intro}；已有DETAILED修改岗位时禁止传|快速项目补全为详细项目的必要内容；QUICK转DETAILED必填{name,intro}；已有DETAILED修改岗位时禁止传|
|`completeDetails.name`|string|父项存在时必填，分支字段例外|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`completeDetails.intro`|string|父项存在时必填，分支字段例外|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`leaderRoleKey`|string|新建详细项目/快速转详细时必填，指向roles新行clientKey或稳定id|快速转详细时组长选定的稳定岗位键；新建详细项目/快速转详细时必填，指向roles新行clientKey或稳定id|

请求示例：

```json
{
  "expectedRevision": 1,
  "roles": [
    {
      "id": "role_example_1",
      "roleName": "客户端开发",
      "duty": "完成App和联调",
      "total": 2
    },
    {
      "id": "role_example_2",
      "roleName": "服务器开发",
      "duty": "实现API和持久化",
      "total": 2
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_ROLE_06](#response-r-role-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-5"></a>

### 8.5 任务列表、认领、阶段与依赖

**当前权限：** M读取本项目；L创建任务/维护阶段与依赖；认领和开始须当前本人合法责任。

- 普通任务由组长创建，需有效截止和逐条标准，初始TODO且未认领。正式教学任务通过assessedPlan/分工约定，不能把普通认领当正式贡献确认。
- TASK-06仅可认领当前无负责人普通TODO；前置满足且无待确认调整，startNow省略默认false。TASK-07只由本人当前责任人或有效正式贡献者开始；稍后开始不会延期或暂停责任。
- TASK-04的阶段修改只带stageId及对应任务/项目基准，阶段ID稳定，不按名称或位置恢复。颜色只改本人看板颜色并限固定调色板，不表示贡献等级。
- TASK-10依赖同项目、无重复/自身/循环；任务开始后不能追加BEFORE_START门槛。完整任务关系需返回，不能只载第一页后误判可开始。

当前代码定位：[TaskScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/TaskScreen.java)、[TaskCreateScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/TaskCreateScreen.java)、[TaskOperationScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/TaskOperationScreen.java)、[BoardScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/BoardScreen.java)、[DependencyEditRules.java](../android-app/app/src/main/java/com/grupi/app/data/DependencyEditRules.java)、[PreviewBoard.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewBoard.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[TASK-01](#api-task-01)|`GET /projects/{projectId}/board`|读取 · 项目 / 任务看板|
|[TASK-02](#api-task-02)|`GET /projects/{projectId}/tasks`|读取 · 项目 / 任务|
|[TASK-03](#api-task-03)|`GET /tasks/{taskId}`|读取 · 任务|
|[TASK-04](#api-task-04)|`PATCH /tasks/{taskId}`|修改 · 任务|
|[TASK-05](#api-task-05)|`POST /projects/{projectId}/tasks`|提交 · 项目 / 任务|
|[TASK-06](#api-task-06)|`POST /tasks/{taskId}/claims`|提交 · 任务 / 认领|
|[TASK-07](#api-task-07)|`POST /tasks/{taskId}/starts`|提交 · 任务 / 开始|
|[TASK-08](#api-task-08)|`PUT /projects/{projectId}/me/color`|保存 · 项目 / 本人 / 本人看板颜色|
|[TASK-09](#api-task-09)|`PUT /projects/{projectId}/stages`|保存 · 项目 / 项目阶段|
|[TASK-10](#api-task-10)|`PUT /tasks/{taskId}/dependencies`|保存 · 任务 / 前置依赖|

<a id="api-task-01"></a>

#### TASK-01 — 读取 · 项目 / 任务看板

`GET /api/v1/projects/{projectId}/board`

权限：M读取本项目；L创建任务/维护阶段与依赖；认领和开始须当前本人合法责任。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/board`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TASK_01](#response-r-task-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-task-02"></a>

#### TASK-02 — 读取 · 项目 / 任务

`GET /api/v1/projects/{projectId}/tasks`

权限：M读取本项目；L创建任务/维护阶段与依赖；认领和开始须当前本人合法责任。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/tasks`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TASK_02](#response-r-task-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-task-03"></a>

#### TASK-03 — 读取 · 任务

`GET /api/v1/tasks/{taskId}`

权限：M读取本项目；L创建任务/维护阶段与依赖；认领和开始须当前本人合法责任。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/tasks/task_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TASK_03](#response-r-task-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-task-04"></a>

#### TASK-04 — 修改 · 任务

`PATCH /api/v1/tasks/{taskId}`

权限：L，当前可写任务/项目。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`expectedProjectRevision`|int|必填；当前项目版本|读取并固定的项目并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`stageId`|string|必填|当前结构固定的stage资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求示例：

```json
{
  "expectedRevision": 1,
  "expectedProjectRevision": 1,
  "stageId": "stage_example_1"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TASK_03](#response-r-task-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-task-05"></a>

#### TASK-05 — 提交 · 项目 / 任务

`POST /api/v1/projects/{projectId}/tasks`

权限：L，当前可写项目。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`title`|string|必填|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`description`|string|可省略；文字默认空，集合默认[]|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`mode`|string|必填|当前业务模式；ORDINARY/ASSESSED，普通dueAt+criteria，正式assessedPlan，禁止把两种结构混为同一交付|
|`stageId`|string|必填|当前结构固定的stage资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`criteria`|object[]|mode=ORDINARY时必填；ASSESSED由assessedPlan确定|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`criteria[].text`|string|父项存在时必填，分支字段例外|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`dependencies`|object[]|mode=ORDINARY时必填；ASSESSED由assessedPlan确定|同项目任务依赖关系；遵守本字段类型、统一上限及对应业务条件|
|`dependencies[].taskId`|string|父项存在时必填，分支字段例外|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`dependencies[].gate`|string|父项存在时必填，分支字段例外|前置门槛种类；遵守本字段类型、统一上限及对应业务条件|
|`dueAt`|string|mode=ORDINARY时必填；ASSESSED由assessedPlan确定|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`assessedPlan`|object|仅mode=ASSESSED时必填|assessedPlan：所属响应结构中的结构化记录；完整正式约定；仅确认后的有效责任才可开始|
|`assessedPlan.ownerId`|string/null|父项存在时必填，分支字段例外|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`assessedPlan.dueAt`|string|父项存在时必填，分支字段例外|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`assessedPlan.units`|object[]|父项存在时必填，分支字段例外|正式责任分项；遵守本字段类型、统一上限及对应业务条件|
|`assessedPlan.units[].clientKey`|string|父项存在时必填，分支字段例外|本次输入中新增项唯一键，服务器会生成稳定id；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`assessedPlan.units[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`assessedPlan.units[].points`|string|父项存在时必填，分支字段例外|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`assessedPlan.units[].shares`|object[]|父项存在时必填，分支字段例外|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`assessedPlan.units[].shares[].userId`|string|父项存在时必填，分支字段例外|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`assessedPlan.units[].shares[].points`|string|父项存在时必填，分支字段例外|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`assessedPlan.units[].shares[].responsibility`|string|父项存在时必填，分支字段例外|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`assessedPlan.units[].criteria`|object[]|父项存在时必填，分支字段例外|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`assessedPlan.units[].criteria[].text`|string|父项存在时必填，分支字段例外|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`assessedPlan.reviewerPolicy`|object|父项存在时必填，分支字段例外|约定的独立验收安排；遵守本字段类型、统一上限及对应业务条件|
|`assessedPlan.reviewerPolicy.userId`|string|父项存在时必填，分支字段例外|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`assessedPlan.reviewerPolicy.kind`|string|父项存在时必填|INDEPENDENT或SELF_CHECK验收方式；独立验收人不参与本任务任何贡献；自检仅参与贡献的当前组长|

请求示例：

```json
{
  "title": "登录页面",
  "description": "实现登录与会话",
  "mode": "ORDINARY",
  "stageId": "stage_example_1",
  "criteria": [
    {
      "text": "可以演示登录和退出"
    }
  ],
  "dependencies": [],
  "dueAt": "2026-11-09T15:59:59Z"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TASK_05](#response-r-task-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-task-06"></a>

#### TASK-06 — 提交 · 任务 / 认领

`POST /api/v1/tasks/{taskId}/claims`

权限：S/M本人，当前可认领的普通任务。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`startNow`|bool|必填|普通任务认领后是否立即开始，省略false；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "startNow": true
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TASK_03](#response-r-task-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-task-07"></a>

#### TASK-07 — 提交 · 任务 / 开始

`POST /api/v1/tasks/{taskId}/starts`

权限：本人当前责任人或有效正式贡献者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TASK_03](#response-r-task-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-task-08"></a>

#### TASK-08 — 保存 · 项目 / 本人 / 本人看板颜色

`PUT /api/v1/projects/{projectId}/me/color`

权限：M，仅本人项目看板颜色。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`color`|string|必填|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|

请求示例：

```json
{
  "expectedRevision": 1,
  "color": "#236B56"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TASK_08](#response-r-task-08)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-task-09"></a>

#### TASK-09 — 保存 · 项目 / 项目阶段

`PUT /api/v1/projects/{projectId}/stages`

权限：L，当前项目阶段定义。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`stages`|object[]|必填|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`stages[].id`|string|父项存在时必填，分支字段例外|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`stages[].name`|string|父项存在时必填，分支字段例外|资源名称；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "stages": [
    {
      "id": "stage_example_1",
      "name": "开发"
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TASK_09](#response-r-task-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-task-10"></a>

#### TASK-10 — 保存 · 任务 / 前置依赖

`PUT /api/v1/tasks/{taskId}/dependencies`

权限：L，当前可修改的同项目任务。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`dependencies`|object[]|必填|同项目任务依赖关系；遵守本字段类型、统一上限及对应业务条件|
|`dependencies[].taskId`|string|父项存在时必填，分支字段例外|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`dependencies[].gate`|string|父项存在时必填，分支字段例外|前置门槛种类；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "dependencies": []
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TASK_03](#response-r-task-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-6"></a>

### 8.6 正式分工、提交、验收与草稿

**当前权限：** M读有权材料；L发起分工；受影响者只签本人；R独立验收；参与贡献的L仅明确SELF_CHECK。

- 分工约定固定责任人、截止、units、shares、逐条标准及验收安排；点数守恒。受影响者ACCEPT或REQUEST_CHANGE，未完整有效确认不ACTIVE，交接续约也不能覆盖已通过贡献。
- 调整提案固定baseUnitRevision，受影响集合由服务器算；仅修改相应未验收项，与未调整项的最新记录合并。意见未解决不能生效；CONTINUE/AGREE确认不是教师仲裁入口。
- 本人云草稿首次0、无记录GET data=null，其他人不代写。提交使用expectedTaskRevision，正式任务同时固定agreementVersion及所选unitIds；普通unitIds=[]。fromDraftRevision只有从该云草稿提交时使用。
- 每次提交是不可覆盖的材料、标准、截止和分项快照。验收逐criterionId核对，可部分通过；退回保留符合/待改结果，新提交另加序号。独立验收禁止被验收贡献者，SELF_CHECK仅参与贡献的L且说明非空。
- DELIVERY-14仅提交者本人撤回仍PENDING的当前版本，和验收争用同revision；保留旧序号、时间、标准及状态历史，成功恢复本人草稿，重交追加新版本。DELIVERY-20只由份额本人对固定acceptanceId确认，不可代签。

当前代码定位：[TaskScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/TaskScreen.java)、[SubmissionReviewScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/SubmissionReviewScreen.java)、[SubmissionWithdrawalScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/SubmissionWithdrawalScreen.java)、[TaskOperationScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/TaskOperationScreen.java)、[PreviewAssessment.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewAssessment.java)、[PreviewChanges.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewChanges.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[DELIVERY-01](#api-delivery-01)|`GET /agreements/{agreementId}`|读取 · 正式约定|
|[DELIVERY-02](#api-delivery-02)|`GET /change-proposals/{proposalId}`|读取 · 内部调整|
|[DELIVERY-03](#api-delivery-03)|`GET /tasks/{taskId}/change-proposals`|读取 · 任务 / 内部调整|
|[DELIVERY-04](#api-delivery-04)|`GET /tasks/{taskId}/me/draft`|读取 · 任务 / 本人 / 私有草稿|
|[DELIVERY-05](#api-delivery-05)|`GET /tasks/{taskId}/submissions`|读取 · 任务 / 提交版本|
|[DELIVERY-06](#api-delivery-06)|`POST /agreements/{agreementId}/responses`|提交 · 正式约定 / 本人回应|
|[DELIVERY-07](#api-delivery-07)|`POST /change-proposals/{proposalId}/opinion-confirmations`|提交 · 内部调整 / 意见确认|
|[DELIVERY-08](#api-delivery-08)|`POST /change-proposals/{proposalId}/opinion-responses`|提交 · 内部调整 / 意见回复|
|[DELIVERY-09](#api-delivery-09)|`POST /change-proposals/{proposalId}/responses`|提交 · 内部调整 / 本人回应|
|[DELIVERY-10](#api-delivery-10)|`POST /change-proposals/{proposalId}/withdrawals`|提交 · 内部调整 / 撤回|
|[DELIVERY-11](#api-delivery-11)|`POST /reviewer-invitations/{invitationId}/responses`|提交 · 独立验收邀请 / 本人回应|
|[DELIVERY-12](#api-delivery-12)|`POST /submissions/{submissionId}/acceptances`|提交 · 提交版本 / 接受/验收|
|[DELIVERY-13](#api-delivery-13)|`POST /submissions/{submissionId}/self-checks`|提交 · 提交版本 / 组长自检|
|[DELIVERY-14](#api-delivery-14)|`POST /submissions/{submissionId}/withdrawals`|提交 · 提交版本 / 撤回|
|[DELIVERY-15](#api-delivery-15)|`POST /tasks/{taskId}/agreements`|提交 · 任务 / 正式约定|
|[DELIVERY-16](#api-delivery-16)|`POST /tasks/{taskId}/change-proposals`|提交 · 任务 / 内部调整|
|[DELIVERY-17](#api-delivery-17)|`POST /tasks/{taskId}/handover-agreement-renewals`|提交 · 任务 / 交接后续约|
|[DELIVERY-18](#api-delivery-18)|`POST /tasks/{taskId}/reviewer-invitations`|提交 · 任务 / 独立验收邀请|
|[DELIVERY-19](#api-delivery-19)|`POST /tasks/{taskId}/submissions`|提交 · 任务 / 提交版本|
|[DELIVERY-20](#api-delivery-20)|`POST /tasks/{taskId}/units/{unitId}/me/contribution-confirmations`|提交 · 正式分项 / 本人 / 本人贡献确认|
|[DELIVERY-21](#api-delivery-21)|`PUT /tasks/{taskId}/me/draft`|保存 · 任务 / 本人 / 私有草稿|

<a id="api-delivery-01"></a>

#### DELIVERY-01 — 读取 · 正式约定

`GET /api/v1/agreements/{agreementId}`

权限：M读有权材料；L发起分工；受影响者只签本人；R独立验收；参与贡献的L仅明确SELF_CHECK。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`agreementId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/agreements/agreement_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_01](#response-r-delivery-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-02"></a>

#### DELIVERY-02 — 读取 · 内部调整

`GET /api/v1/change-proposals/{proposalId}`

权限：M读有权材料；L发起分工；受影响者只签本人；R独立验收；参与贡献的L仅明确SELF_CHECK。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/change-proposals/proposal_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_02](#response-r-delivery-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-03"></a>

#### DELIVERY-03 — 读取 · 任务 / 内部调整

`GET /api/v1/tasks/{taskId}/change-proposals`

权限：M读有权材料；L发起分工；受影响者只签本人；R独立验收；参与贡献的L仅明确SELF_CHECK。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/tasks/task_example_1/change-proposals`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_03](#response-r-delivery-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-04"></a>

#### DELIVERY-04 — 读取 · 任务 / 本人 / 私有草稿

`GET /api/v1/tasks/{taskId}/me/draft`

权限：当前有权责任本人，仅本人私有草稿。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/tasks/task_example_1/me/draft`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_04](#response-r-delivery-04)。当前尚无有效记录时`data=null`。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-05"></a>

#### DELIVERY-05 — 读取 · 任务 / 提交版本

`GET /api/v1/tasks/{taskId}/submissions`

权限：M读有权材料；L发起分工；受影响者只签本人；R独立验收；参与贡献的L仅明确SELF_CHECK。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/tasks/task_example_1/submissions`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_05](#response-r-delivery-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-06"></a>

#### DELIVERY-06 — 提交 · 正式约定 / 本人回应

`POST /api/v1/agreements/{agreementId}/responses`

权限：约定受影响的本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`agreementId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/REQUEST_CHANGE|
|`comment`|string|可省略，默认空，岗位/约定回应最多500|本人意见；可省略，默认空，岗位/约定回应最多500|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "ACCEPT",
  "comment": "按本次约定执行"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_06](#response-r-delivery-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-07"></a>

#### DELIVERY-07 — 提交 · 内部调整 / 意见确认

`POST /api/v1/change-proposals/{proposalId}/opinion-confirmations`

权限：M读有权材料；L发起分工；受影响者只签本人；R独立验收；参与贡献的L仅明确SELF_CHECK。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`opinionId`|string|必填|当前结构固定的opinion资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`decision`|string|必填|本人明确提交的决定；允许值：AGREE/CONTINUE|
|`comment`|string|可省略，默认空，岗位/约定回应最多500|本人意见；可省略，默认空，岗位/约定回应最多500|

请求示例：

```json
{
  "expectedRevision": 1,
  "opinionId": "opinion_example_1",
  "decision": "AGREE",
  "comment": "按本次约定执行"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_07](#response-r-delivery-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-08"></a>

#### DELIVERY-08 — 提交 · 内部调整 / 意见回复

`POST /api/v1/change-proposals/{proposalId}/opinion-responses`

权限：M读有权材料；L发起分工；受影响者只签本人；R独立验收；参与贡献的L仅明确SELF_CHECK。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`opinionId`|string|必填|当前结构固定的opinion资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposalText`|string|必填|proposalText：所属响应结构中的字符串值；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "opinionId": "opinion_example_1",
  "proposalText": "维持已通过项，调整未完成项"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_02](#response-r-delivery-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-09"></a>

#### DELIVERY-09 — 提交 · 内部调整 / 本人回应

`POST /api/v1/change-proposals/{proposalId}/responses`

权限：M读有权材料；L发起分工；受影响者只签本人；R独立验收；参与贡献的L仅明确SELF_CHECK。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/REQUEST_CHANGE|
|`comment`|string|可省略，默认空，岗位/约定回应最多500|本人意见；可省略，默认空，岗位/约定回应最多500|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "ACCEPT",
  "comment": "按本次约定执行"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_09](#response-r-delivery-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-10"></a>

#### DELIVERY-10 — 提交 · 内部调整 / 撤回

`POST /api/v1/change-proposals/{proposalId}/withdrawals`

权限：M读有权材料；L发起分工；受影响者只签本人；R独立验收；参与贡献的L仅明确SELF_CHECK。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_02](#response-r-delivery-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-11"></a>

#### DELIVERY-11 — 提交 · 独立验收邀请 / 本人回应

`POST /api/v1/reviewer-invitations/{invitationId}/responses`

权限：验收邀请接收者本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`invitationId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/DECLINE|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "ACCEPT"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_11](#response-r-delivery-11)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-12"></a>

#### DELIVERY-12 — 提交 · 提交版本 / 接受/验收

`POST /api/v1/submissions/{submissionId}/acceptances`

权限：R，当前独立验收资格且不参与被验收贡献。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`submissionId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`unitIds`|string[]|必填|本人本次选定的正式分项ID集合；遵守本字段类型、统一上限及对应业务条件|
|`checks`|object[]|必填|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`checks[].unitId`|string/null|父项存在时必填，分支字段例外|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`checks[].criterionId`|string|父项存在时必填，分支字段例外|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`checks[].met`|bool|父项存在时必填，分支字段例外|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`returnReasons`|string[]|必填|仍需更正或补充的具体原因；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "unitIds": [],
  "checks": [
    {
      "unitId": null,
      "criterionId": "criterion_example_1",
      "met": true
    }
  ],
  "returnReasons": []
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_12](#response-r-delivery-12)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-13"></a>

#### DELIVERY-13 — 提交 · 提交版本 / 组长自检

`POST /api/v1/submissions/{submissionId}/self-checks`

权限：参与被验收贡献的当前L，明确自检。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`submissionId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`unitIds`|string[]|必填|本人本次选定的正式分项ID集合；遵守本字段类型、统一上限及对应业务条件|
|`checks`|object[]|必填|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`checks[].unitId`|string/null|父项存在时必填，分支字段例外|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`checks[].criterionId`|string|父项存在时必填，分支字段例外|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`checks[].met`|bool|父项存在时必填，分支字段例外|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`returnReasons`|string[]|必填|仍需更正或补充的具体原因；遵守本字段类型、统一上限及对应业务条件|
|`selfCheckReason`|string|必填|参与贡献的组长选择自检的说明；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "unitIds": [],
  "checks": [
    {
      "unitId": null,
      "criterionId": "criterion_example_1",
      "met": true
    }
  ],
  "returnReasons": [],
  "selfCheckReason": "当前没有不参与本项贡献的组员可独立核验，单独记录自检来源"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_12](#response-r-delivery-12)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-14"></a>

#### DELIVERY-14 — 提交 · 提交版本 / 撤回

`POST /api/v1/submissions/{submissionId}/withdrawals`

权限：原提交者本人，当前仍PENDING提交。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`submissionId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_14](#response-r-delivery-14)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-15"></a>

#### DELIVERY-15 — 提交 · 任务 / 正式约定

`POST /api/v1/tasks/{taskId}/agreements`

权限：L，当前任务正式约定。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`baseTaskRevision`|int|必填|本结构固定的baseTaskRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`plan`|object|必填|完整正式约定或岗位调整方案，按模型区分；遵守本字段类型、统一上限及对应业务条件|
|`plan.ownerId`|string/null|父项存在时必填，分支字段例外|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.dueAt`|string|父项存在时必填，分支字段例外|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`plan.units`|object[]|父项存在时必填，分支字段例外|正式责任分项；遵守本字段类型、统一上限及对应业务条件|
|`plan.units[].clientKey`|string|父项存在时必填，分支字段例外|本次输入中新增项唯一键，服务器会生成稳定id；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.units[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`plan.units[].points`|string|父项存在时必填，分支字段例外|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`plan.units[].shares`|object[]|父项存在时必填，分支字段例外|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`plan.units[].shares[].userId`|string|父项存在时必填，分支字段例外|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.units[].shares[].points`|string|父项存在时必填，分支字段例外|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`plan.units[].shares[].responsibility`|string|父项存在时必填，分支字段例外|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`plan.units[].criteria`|object[]|父项存在时必填，分支字段例外|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`plan.units[].criteria[].text`|string|父项存在时必填，分支字段例外|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`plan.reviewerPolicy`|object|父项存在时必填，分支字段例外|约定的独立验收安排；遵守本字段类型、统一上限及对应业务条件|
|`plan.reviewerPolicy.userId`|string|父项存在时必填，分支字段例外|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.reviewerPolicy.kind`|string|父项存在时必填|INDEPENDENT或SELF_CHECK验收方式；独立验收人不参与本任务任何贡献；自检仅参与贡献的当前组长|

请求示例：

```json
{
  "baseTaskRevision": 1,
  "plan": {
    "ownerId": "user_example_1",
    "dueAt": "2026-11-09T15:59:59Z",
    "units": [
      {
        "clientKey": "unit_1",
        "title": "登录页面",
        "points": "10.00",
        "shares": [
          {
            "userId": "user_example_1",
            "points": "10.00",
            "responsibility": "实现登录功能"
          }
        ],
        "criteria": [
          {
            "text": "可以演示登录和退出"
          }
        ]
      }
    ],
    "reviewerPolicy": {
      "userId": "user_example_3",
      "kind": "INDEPENDENT"
    }
  }
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_01](#response-r-delivery-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-16"></a>

#### DELIVERY-16 — 提交 · 任务 / 内部调整

`POST /api/v1/tasks/{taskId}/change-proposals`

权限：M读有权材料；L发起分工；受影响者只签本人；R独立验收；参与贡献的L仅明确SELF_CHECK。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`baseAgreementVersion`|int|正式分项草稿必填，普通省略|提案所基于的约定版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`changes`|object[]|必填|当前有权的旧消息变更投影；遵守本字段类型、统一上限及对应业务条件|
|`changes[].unitId`|string|父项存在时必填，分支字段例外|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`changes[].baseUnitRevision`|int|父项存在时必填，分支字段例外|被调整分项的来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`changes[].fields`|object|父项存在时必填，分支字段例外|该次实际有变化的允许字段；遵守本字段类型、统一上限及对应业务条件|
|`changes[].fields.title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "baseAgreementVersion": 1,
  "changes": [
    {
      "unitId": "unit_example_1",
      "baseUnitRevision": 1,
      "fields": {
        "title": "登录与会话页面"
      }
    }
  ],
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_02](#response-r-delivery-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-17"></a>

#### DELIVERY-17 — 提交 · 任务 / 交接后续约

`POST /api/v1/tasks/{taskId}/handover-agreement-renewals`

权限：L，交接后当前正式任务续约。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_01](#response-r-delivery-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-18"></a>

#### DELIVERY-18 — 提交 · 任务 / 独立验收邀请

`POST /api/v1/tasks/{taskId}/reviewer-invitations`

权限：L，当前任务独立验收安排。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`recipientId`|string|必填|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`expectedTaskRevision`|int|必填；当前任务版本|读取并固定的任务并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "recipientId": "user_example_2",
  "expectedTaskRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_11](#response-r-delivery-11)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-19"></a>

#### DELIVERY-19 — 提交 · 任务 / 提交版本

`POST /api/v1/tasks/{taskId}/submissions`

权限：当前有权提交的责任本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedTaskRevision`|int|必填；当前任务版本|读取并固定的任务并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`unitIds`|string[]|必填|本人本次选定的正式分项ID集合；遵守本字段类型、统一上限及对应业务条件|
|`summary`|string|必填|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`artifacts`|object[]|可省略默认[]，单次最多10个当前有权引用|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`artifacts[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`artifacts[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`agreementVersion`|int|正式分项提交必填，普通省略|本次固定正式约定版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`fromDraftRevision`|int|从云端草稿提交时必填，直接提交可省略|提交所采用的本人云草稿版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`artifacts[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "expectedTaskRevision": 1,
  "unitIds": [],
  "summary": "完成登录与退出",
  "artifacts": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_19](#response-r-delivery-19)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-20"></a>

#### DELIVERY-20 — 提交 · 正式分项 / 本人 / 本人贡献确认

`POST /api/v1/tasks/{taskId}/units/{unitId}/me/contribution-confirmations`

权限：固定分项/验收份额的本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|
|`unitId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`submissionId`|string|必填|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`acceptanceId`|string|必填|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`statement`|string|必填|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "submissionId": "submission_example_1",
  "acceptanceId": "acceptance_example_1",
  "statement": "确认本人实际完成约定份额"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_20](#response-r-delivery-20)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-delivery-21"></a>

#### DELIVERY-21 — 保存 · 任务 / 本人 / 私有草稿

`PUT /api/v1/tasks/{taskId}/me/draft`

权限：当前有权责任本人，仅本人私有草稿。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`unitIds`|string[]|必填|本人本次选定的正式分项ID集合；遵守本字段类型、统一上限及对应业务条件|
|`summary`|string|必填|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`artifacts`|object[]|可省略默认[]，单次最多10个当前有权引用|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`artifacts[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`artifacts[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`baseAgreementVersion`|int|正式分项草稿必填，普通省略|提案所基于的约定版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`expectedTaskRevision`|int|正式提交/验收邀请必填，草稿同步有当前任务基准时传入|读取并固定的任务并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`artifacts[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "expectedRevision": 0,
  "unitIds": [],
  "summary": "完成登录与退出",
  "artifacts": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DELIVERY_04](#response-r-delivery-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-7"></a>

### 8.7 人员变更、表决、补充资料与事项交接

**当前权限：** 相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。

- 退出/转组申请不立即移人。目标组长邀请和本人回应先形成有条件接收，原团队关系保留；取消目标邀请也不能删除原申请历史。人员许可由T处理ALLOW_HANDOVER/RETURN，需安置方案/原因，低于人数下限须明确allowBelowMinimum。
- handover-plan由小组安排，逐项记录真实接任人，必要时nextLeaderId；教师/助理不得代接任务或重分贡献。GOV-22按planVersion由接任本人接受相应itemIds，GOV-21最终重验来源/席位/接受结果后原子执行。
- 表决移出保留被提议人陈述、既有沟通和必要证据；冻结有资格名单，只本人AGREE/DISAGREE/ABSTAIN，法定阈值按冻结分母计算。通过后仍需资格与人员核对、组内交接和最终执行，组长不能直接移除。 本接口设计默认陈述48小时、投票72小时，本轮参数固定进规则快照；赞成严格过半且至少2票，被提议人不投票，资格变化转QUALIFICATION_REVIEW而不偷偷改分母。
- 补资料仅用于人员事项，要求、截止、回应responseVersion及核对分别留记录；RETURN/ACCEPTED不等于事项结案，撤回不物理删除旧版本。相关学生只读本人材料，不能借caseId遍历他人资料。
- 教师间事项交接固定facts、unresolvedQuestions、decisionsNeeded及sourceCaseRevision；未接受前原人负责，RETURN/CANCEL保留历史。接任/收回都核对当前权属和材料状态，不授接收者额外群聊或评分权限。

当前代码定位：[PersonnelScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/PersonnelScreen.java)、[GovernanceScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/GovernanceScreen.java)、[PreviewGovernance.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewGovernance.java)、[PreviewPersonnel.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewPersonnel.java)、[PreviewTransfers.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewTransfers.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[GOV-01](#api-gov-01)|`GET /material-requests/{requestId}`|读取 · 补充资料|
|[GOV-02](#api-gov-02)|`GET /me/material-requests`|读取 · 本人 / 补充资料|
|[GOV-03](#api-gov-03)|`GET /me/membership-history`|读取 · 本人 / 成员关系历史|
|[GOV-04](#api-gov-04)|`GET /me/personnel-handoffs`|读取 · 本人 / 人员事项交接|
|[GOV-05](#api-gov-05)|`GET /member-changes/{changeId}`|读取 · 退出转组|
|[GOV-06](#api-gov-06)|`GET /personnel-cases/{caseId}`|读取 · 人员事项|
|[GOV-07](#api-gov-07)|`GET /personnel-cases/{caseId}/handoff-candidates`|读取 · 人员事项 / 接任教师候选|
|[GOV-08](#api-gov-08)|`GET /personnel-cases/{caseId}/material-recipients`|读取 · 人员事项 / 可补资料人员|
|[GOV-09](#api-gov-09)|`GET /personnel-handoffs/{handoffId}`|读取 · 人员事项交接|
|[GOV-10](#api-gov-10)|`GET /projects/{projectId}/incoming-transfers`|读取 · 项目 / 待转入邀请|
|[GOV-11](#api-gov-11)|`GET /projects/{projectId}/member-changes`|读取 · 项目 / 退出转组|
|[GOV-12](#api-gov-12)|`GET /projects/{projectId}/removal-votes`|读取 · 项目 / 移出表决|
|[GOV-13](#api-gov-13)|`GET /projects/{projectId}/transfer-targets`|读取 · 项目 / 转组目标|
|[GOV-14](#api-gov-14)|`GET /removal-votes/{voteId}`|读取 · 移出表决|
|[GOV-15](#api-gov-15)|`GET /squares/{squareId}/member-changes`|读取 · 课程 / 退出转组|
|[GOV-16](#api-gov-16)|`GET /squares/{squareId}/personnel-cases`|读取 · 课程 / 人员事项|
|[GOV-17](#api-gov-17)|`POST /material-requests/{requestId}/responses`|提交 · 补充资料 / 本人回应|
|[GOV-18](#api-gov-18)|`POST /material-requests/{requestId}/reviews`|提交 · 补充资料 / 材料核验|
|[GOV-19](#api-gov-19)|`POST /material-requests/{requestId}/withdrawals`|撤回待补资料请求|
|[GOV-20](#api-gov-20)|`POST /member-changes/{changeId}/cancellations`|提交 · 退出转组 / 取消|
|[GOV-21](#api-gov-21)|`POST /member-changes/{changeId}/executions`|提交 · 退出转组 / 最终执行|
|[GOV-22](#api-gov-22)|`POST /member-changes/{changeId}/handover-responses`|提交 · 退出转组 / 本人接任回应|
|[GOV-23](#api-gov-23)|`POST /member-changes/{changeId}/personnel-decisions`|提交 · 退出转组 / 人员程序核对|
|[GOV-24](#api-gov-24)|`POST /member-changes/{changeId}/target-cancellations`|提交 · 退出转组 / 撤销目标邀请|
|[GOV-25](#api-gov-25)|`POST /member-changes/{changeId}/target-invitations`|提交 · 退出转组 / 目标邀请|
|[GOV-26](#api-gov-26)|`POST /member-changes/{changeId}/target-responses`|提交 · 退出转组 / 本人目标回应|
|[GOV-27](#api-gov-27)|`POST /personnel-cases/{caseId}/acceptances`|提交 · 人员事项 / 接受/验收|
|[GOV-28](#api-gov-28)|`POST /personnel-cases/{caseId}/closures`|提交 · 人员事项 / 结案/关闭|
|[GOV-29](#api-gov-29)|`POST /personnel-cases/{caseId}/handoffs`|提交 · 人员事项 / 事项交接|
|[GOV-30](#api-gov-30)|`POST /personnel-cases/{caseId}/material-requests`|提交 · 人员事项 / 补充资料|
|[GOV-31](#api-gov-31)|`POST /personnel-cases/{caseId}/recoveries`|提交 · 人员事项 / 收回处理|
|[GOV-32](#api-gov-32)|`POST /personnel-cases/{caseId}/updates`|提交 · 人员事项 / 追加事实记录|
|[GOV-33](#api-gov-33)|`POST /personnel-handoffs/{handoffId}/cancellations`|提交 · 人员事项交接 / 取消|
|[GOV-34](#api-gov-34)|`POST /personnel-handoffs/{handoffId}/responses`|提交 · 人员事项交接 / 本人回应|
|[GOV-35](#api-gov-35)|`POST /projects/{projectId}/member-changes`|提交 · 项目 / 退出转组|
|[GOV-36](#api-gov-36)|`POST /projects/{projectId}/removal-votes`|提交 · 项目 / 移出表决|
|[GOV-37](#api-gov-37)|`POST /removal-votes/{voteId}/cancellations`|提交 · 移出表决 / 取消|
|[GOV-38](#api-gov-38)|`POST /removal-votes/{voteId}/personnel-decisions`|提交 · 移出表决 / 人员程序核对|
|[GOV-39](#api-gov-39)|`POST /removal-votes/{voteId}/statements`|提交 · 移出表决 / 本人陈述|
|[GOV-40](#api-gov-40)|`PUT /member-changes/{changeId}/handover-plan`|保存 · 退出转组 / 组内交接方案|
|[GOV-41](#api-gov-41)|`PUT /removal-votes/{voteId}/me/ballot`|保存 · 移出表决 / 本人 / 本人选票|

<a id="api-gov-01"></a>

#### GOV-01 — 读取 · 补充资料

`GET /api/v1/material-requests/{requestId}`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`requestId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/material-requests/request_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_01](#response-r-gov-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-02"></a>

#### GOV-02 — 读取 · 本人 / 补充资料

`GET /api/v1/me/material-requests`

权限：U，仅本人补资料请求。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/material-requests`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_02](#response-r-gov-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-03"></a>

#### GOV-03 — 读取 · 本人 / 成员关系历史

`GET /api/v1/me/membership-history`

权限：U，仅本人有权成员历史。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/membership-history`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_03](#response-r-gov-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-04"></a>

#### GOV-04 — 读取 · 本人 / 人员事项交接

`GET /api/v1/me/personnel-handoffs`

权限：当前相关教师，仅本人事项交接。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/personnel-handoffs`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_04](#response-r-gov-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-05"></a>

#### GOV-05 — 读取 · 退出转组

`GET /api/v1/member-changes/{changeId}`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`changeId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/member-changes/change_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_05](#response-r-gov-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-06"></a>

#### GOV-06 — 读取 · 人员事项

`GET /api/v1/personnel-cases/{caseId}`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`caseId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/personnel-cases/case_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_06](#response-r-gov-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-07"></a>

#### GOV-07 — 读取 · 人员事项 / 接任教师候选

`GET /api/v1/personnel-cases/{caseId}/handoff-candidates`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`caseId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/personnel-cases/case_example_1/handoff-candidates`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_07](#response-r-gov-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-08"></a>

#### GOV-08 — 读取 · 人员事项 / 可补资料人员

`GET /api/v1/personnel-cases/{caseId}/material-recipients`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`caseId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/personnel-cases/case_example_1/material-recipients`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_07](#response-r-gov-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-09"></a>

#### GOV-09 — 读取 · 人员事项交接

`GET /api/v1/personnel-handoffs/{handoffId}`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`handoffId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/personnel-handoffs/handoff_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_09](#response-r-gov-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-10"></a>

#### GOV-10 — 读取 · 项目 / 待转入邀请

`GET /api/v1/projects/{projectId}/incoming-transfers`

权限：目标项目L，仅转入所需摘要。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/incoming-transfers`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_10](#response-r-gov-10)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-11"></a>

#### GOV-11 — 读取 · 项目 / 退出转组

`GET /api/v1/projects/{projectId}/member-changes`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/member-changes`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_10](#response-r-gov-10)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-12"></a>

#### GOV-12 — 读取 · 项目 / 移出表决

`GET /api/v1/projects/{projectId}/removal-votes`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/removal-votes`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_12](#response-r-gov-12)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-13"></a>

#### GOV-13 — 读取 · 项目 / 转组目标

`GET /api/v1/projects/{projectId}/transfer-targets`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/transfer-targets`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_13](#response-r-gov-13)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-14"></a>

#### GOV-14 — 读取 · 移出表决

`GET /api/v1/removal-votes/{voteId}`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`voteId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/removal-votes/vote_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_14](#response-r-gov-14)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-15"></a>

#### GOV-15 — 读取 · 课程 / 退出转组

`GET /api/v1/squares/{squareId}/member-changes`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/squares/square_example_1/member-changes`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_10](#response-r-gov-10)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-16"></a>

#### GOV-16 — 读取 · 课程 / 人员事项

`GET /api/v1/squares/{squareId}/personnel-cases`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/squares/square_example_1/personnel-cases`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_16](#response-r-gov-16)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-17"></a>

#### GOV-17 — 提交 · 补充资料 / 本人回应

`POST /api/v1/material-requests/{requestId}/responses`

权限：该补资料请求的本人接收者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`requestId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`content`|string|必填|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs`|object[]|可省略默认[]，要求教学依据的动作必须至少一项|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`evidenceRefs[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "content": "补充本人的实际情况",
  "evidenceRefs": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ],
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_01](#response-r-gov-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-18"></a>

#### GOV-18 — 提交 · 补充资料 / 材料核验

`POST /api/v1/material-requests/{requestId}/reviews`

权限：该事项当前有权核验者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`requestId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`responseVersion`|int|必填|本次补资料回应版本；遵守本字段类型、统一上限及对应业务条件|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/RETURN|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "responseVersion": 1,
  "decision": "ACCEPT",
  "reason": "按实际协作情况说明",
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_01](#response-r-gov-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-19"></a>

#### GOV-19 — 撤回待补资料请求

`POST /api/v1/material-requests/{requestId}/withdrawals`

权限：该事项当前有MATERIAL_REQUEST_HANDLE的处理者，撤回资料请求而非抹除本人旧材料。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`requestId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedResponseVersion`|int|必填|本结构固定的expectedResponseVersion版本，不能与对象revision互换；遵守本字段类型、统一上限及对应业务条件|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "expectedResponseVersion": 1,
  "reason": "按实际协作情况说明",
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_01](#response-r-gov-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-20"></a>

#### GOV-20 — 提交 · 退出转组 / 取消

`POST /api/v1/member-changes/{changeId}/cancellations`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`changeId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_05](#response-r-gov-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-21"></a>

#### GOV-21 — 提交 · 退出转组 / 最终执行

`POST /api/v1/member-changes/{changeId}/executions`

权限：原人员变更当前有权执行者；全部前置同时满足。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`changeId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`planVersion`|int|必填|当前交接方案版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|

请求示例：

```json
{
  "expectedRevision": 1,
  "planVersion": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_05](#response-r-gov-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-22"></a>

#### GOV-22 — 提交 · 退出转组 / 本人接任回应

`POST /api/v1/member-changes/{changeId}/handover-responses`

权限：交接条目指定的真实接任本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`changeId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`planVersion`|int|必填|当前交接方案版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`itemIds`|string[]|必填|本人本次明确接受的交接条目ID集合；遵守本字段类型、统一上限及对应业务条件|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/DECLINE|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "planVersion": 1,
  "itemIds": [
    "handover_item_example_1"
  ],
  "decision": "ACCEPT",
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_22](#response-r-gov-22)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-23"></a>

#### GOV-23 — 提交 · 退出转组 / 人员程序核对

`POST /api/v1/member-changes/{changeId}/personnel-decisions`

权限：T，原课程当前人员核对。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`changeId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：ALLOW_HANDOVER/RETURN|
|`placementPlan`|string|必填|人员安置方案；遵守本字段类型、统一上限及对应业务条件|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`allowBelowMinimum`|bool|必填|教师明确允许人员变更后低于最低人数的决定；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "ALLOW_HANDOVER",
  "placementPlan": "完成既有交接后保留有效历史",
  "reason": "按实际协作情况说明",
  "allowBelowMinimum": false
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_05](#response-r-gov-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-24"></a>

#### GOV-24 — 提交 · 退出转组 / 撤销目标邀请

`POST /api/v1/member-changes/{changeId}/target-cancellations`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`changeId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "reason": "按实际协作情况说明",
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_05](#response-r-gov-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-25"></a>

#### GOV-25 — 提交 · 退出转组 / 目标邀请

`POST /api/v1/member-changes/{changeId}/target-invitations`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`changeId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`roleSlotId`|string|DETAILED岗位申请/邀请必填；QUICK省略|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message`|string|必填|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "roleSlotId": "role_example_2",
  "message": "希望参与项目协作",
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_05](#response-r-gov-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-26"></a>

#### GOV-26 — 提交 · 退出转组 / 本人目标回应

`POST /api/v1/member-changes/{changeId}/target-responses`

权限：转组申请者本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`changeId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/DECLINE|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "decision": "ACCEPT",
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_05](#response-r-gov-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-27"></a>

#### GOV-27 — 提交 · 人员事项 / 接受/验收

`POST /api/v1/personnel-cases/{caseId}/acceptances`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`caseId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_06](#response-r-gov-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-28"></a>

#### GOV-28 — 提交 · 人员事项 / 结案/关闭

`POST /api/v1/personnel-cases/{caseId}/closures`

权限：原事项当前有权结案处理者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`caseId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`outcome`|string|必填|outcome：所属响应结构中的字符串值；遵守本字段类型、统一上限及对应业务条件|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "outcome": "人员前置核对已完成",
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_28](#response-r-gov-28)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-29"></a>

#### GOV-29 — 提交 · 人员事项 / 事项交接

`POST /api/v1/personnel-cases/{caseId}/handoffs`

权限：原事项当前负责教师，合法接任教师候选。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`caseId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedCaseRevision`|int|必填|读取并固定的人员事项并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`recipientTeacherId`|string|必填|本次合法接任教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`facts`|string|必填|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`unresolvedQuestions`|string|必填|尚未解决的问题；遵守本字段类型、统一上限及对应业务条件|
|`decisionsNeeded`|string|必填|接收者需决定的内容；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedCaseRevision": 1,
  "recipientTeacherId": "user_example_teacher2",
  "facts": "已核对当前成员关系",
  "unresolvedQuestions": "接任尚未完成",
  "decisionsNeeded": "请核对人员安置"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_09](#response-r-gov-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-30"></a>

#### GOV-30 — 提交 · 人员事项 / 补充资料

`POST /api/v1/personnel-cases/{caseId}/material-requests`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`caseId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedCaseRevision`|int|必填|读取并固定的人员事项并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`recipientId`|string|必填|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`requirements`|string|必填|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`dueAt`|string|必填|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|

请求示例：

```json
{
  "expectedCaseRevision": 1,
  "recipientId": "user_example_2",
  "requirements": "完成可演示的课程应用",
  "dueAt": "2026-11-09T15:59:59Z"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_01](#response-r-gov-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-31"></a>

#### GOV-31 — 提交 · 人员事项 / 收回处理

`POST /api/v1/personnel-cases/{caseId}/recoveries`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`caseId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_06](#response-r-gov-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-32"></a>

#### GOV-32 — 提交 · 人员事项 / 追加事实记录

`POST /api/v1/personnel-cases/{caseId}/updates`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`caseId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`facts`|string|必填|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`nextAction`|string|必填|实际下一步处理方向；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs`|object[]|可省略默认[]，要求教学依据的动作必须至少一项|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`evidenceRefs[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "expectedRevision": 1,
  "facts": "已核对当前成员关系",
  "nextAction": "等待本人补充材料",
  "evidenceRefs": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_32](#response-r-gov-32)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-33"></a>

#### GOV-33 — 提交 · 人员事项交接 / 取消

`POST /api/v1/personnel-handoffs/{handoffId}/cancellations`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`handoffId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "reason": "按实际协作情况说明",
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_09](#response-r-gov-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-34"></a>

#### GOV-34 — 提交 · 人员事项交接 / 本人回应

`POST /api/v1/personnel-handoffs/{handoffId}/responses`

权限：该事项交接接收教师本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`handoffId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/RETURN|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "decision": "ACCEPT",
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_09](#response-r-gov-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-35"></a>

#### GOV-35 — 提交 · 项目 / 退出转组

`POST /api/v1/projects/{projectId}/member-changes`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`kind`|string|必填|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`expectedMembershipRevision`|int|必填|读取并固定的成员关系并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`targetProjectId`|string/null|kind=TRANSFER必填；EXIT省略或null|预选的转入项目，不意味着已入组；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求示例：

```json
{
  "kind": "EXIT",
  "reason": "按实际协作情况说明",
  "expectedMembershipRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_05](#response-r-gov-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-36"></a>

#### GOV-36 — 提交 · 项目 / 移出表决

`POST /api/v1/projects/{projectId}/removal-votes`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`targetUserId`|string|必填|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`facts`|string|必填|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`communicationHistory`|string|必填|与人员变更有关的必要沟通记录；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs`|object[]|可省略默认[]，要求教学依据的动作必须至少一项|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`evidenceRefs[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "targetUserId": "user_example_2",
  "facts": "已核对当前成员关系",
  "communicationHistory": "已与相关本人沟通",
  "evidenceRefs": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_14](#response-r-gov-14)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-37"></a>

#### GOV-37 — 提交 · 移出表决 / 取消

`POST /api/v1/removal-votes/{voteId}/cancellations`

权限：相关学生读本人最少事项；L组织组内交接；T核对人员关系；A在能力范围辅助并本组回避。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`voteId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "reason": "按实际协作情况说明",
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_14](#response-r-gov-14)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-38"></a>

#### GOV-38 — 提交 · 移出表决 / 人员程序核对

`POST /api/v1/removal-votes/{voteId}/personnel-decisions`

权限：T，原课程当前人员核对。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`voteId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：ALLOW_HANDOVER/RETURN|
|`placementPlan`|string|必填|人员安置方案；遵守本字段类型、统一上限及对应业务条件|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`allowBelowMinimum`|bool|必填|教师明确允许人员变更后低于最低人数的决定；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "ALLOW_HANDOVER",
  "placementPlan": "完成既有交接后保留有效历史",
  "reason": "按实际协作情况说明",
  "allowBelowMinimum": false
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_14](#response-r-gov-14)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-39"></a>

#### GOV-39 — 提交 · 移出表决 / 本人陈述

`POST /api/v1/removal-votes/{voteId}/statements`

权限：本次被提议移出者本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`voteId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`statement`|string|必填|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "statement": "确认本人实际完成约定份额",
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_14](#response-r-gov-14)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-40"></a>

#### GOV-40 — 保存 · 退出转组 / 组内交接方案

`PUT /api/v1/member-changes/{changeId}/handover-plan`

权限：原小组L维护；不能代接任本人确认。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`changeId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items`|object[]|必填|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].taskId`|string|父项存在时必填，分支字段例外|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].sourceTaskRevision`|int|父项存在时必填，分支字段例外|冻结的源任务版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].recipientId`|string|父项存在时必填，分支字段例外|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].responsibility`|string|父项存在时必填，分支字段例外|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`nextLeaderId`|string|组长离任的交接方案必填另一当前成员，否则空字符串|组内明确接受的下一任组长ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`notes`|string|可省略；文字默认空，集合默认[]|补充说明；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "items": [
    {
      "taskId": "task_example_1",
      "sourceTaskRevision": 1,
      "recipientId": "user_example_2",
      "responsibility": "接续未完成内容"
    }
  ],
  "nextLeaderId": "",
  "notes": "保留已验收历史"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_22](#response-r-gov-22)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-gov-41"></a>

#### GOV-41 — 保存 · 移出表决 / 本人 / 本人选票

`PUT /api/v1/removal-votes/{voteId}/me/ballot`

权限：本次冻结选民中的本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`voteId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|本人的选票版本，不是表决提案revision；首次选票0；已有选票读取myBallot.revision后保存|
|`choice`|string|必填|本人在冻结资格下的选票；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "choice": "AGREE"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GOV_41](#response-r-gov-41)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-8"></a>

### 8.8 重复课程广场合并

**当前权限：** 有资格课程成员发起/投本人票；当前教师及有权执行者核对教学事实与权属。

- 合并冻结两边有效成员名单，分别要求floor(N/2)+1票赞成，不能合并成一个总分母，也不能仅以已投人数为分母。双方通过后仍需事实、教师归属和未完事项核对。
- assignmentMappings必须显式keepSeparate，已有作业/项目/成绩不因课程同名被混到一起。正式执行保留旧编号定位及合并历史，GET可导向canonical课程，旧范围写入明确SQUARE_MERGED后要求本人重开，不隐式改写请求。

当前代码定位：[MergeScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/MergeScreen.java)、[PreviewSquareMerge.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewSquareMerge.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[MERGE-01](#api-merge-01)|`GET /square-merge-proposals/{proposalId}`|读取 · 课程合并提案|
|[MERGE-02](#api-merge-02)|`GET /squares/{squareId}/merge-proposals`|读取 · 课程 / 课程合并提案|
|[MERGE-03](#api-merge-03)|`POST /square-merge-proposals`|提交 · 课程合并提案|
|[MERGE-04](#api-merge-04)|`POST /square-merge-proposals/{proposalId}/cancellations`|提交 · 课程合并提案 / 取消|
|[MERGE-05](#api-merge-05)|`POST /square-merge-proposals/{proposalId}/executions`|提交 · 课程合并提案 / 最终执行|
|[MERGE-06](#api-merge-06)|`POST /square-merge-proposals/{proposalId}/fact-checks`|提交 · 课程合并提案 / 教学事实核对|
|[MERGE-07](#api-merge-07)|`PUT /square-merge-proposals/{proposalId}/me/ballots`|保存 · 课程合并提案 / 本人 / 本人选票|

<a id="api-merge-01"></a>

#### MERGE-01 — 读取 · 课程合并提案

`GET /api/v1/square-merge-proposals/{proposalId}`

权限：有资格课程成员发起/投本人票；当前教师及有权执行者核对教学事实与权属。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/square-merge-proposals/proposal_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_MERGE_01](#response-r-merge-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-merge-02"></a>

#### MERGE-02 — 读取 · 课程 / 课程合并提案

`GET /api/v1/squares/{squareId}/merge-proposals`

权限：有资格课程成员发起/投本人票；当前教师及有权执行者核对教学事实与权属。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/squares/square_example_1/merge-proposals`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_MERGE_02](#response-r-merge-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-merge-03"></a>

#### MERGE-03 — 提交 · 课程合并提案

`POST /api/v1/square-merge-proposals`

权限：有资格课程成员发起/投本人票；当前教师及有权执行者核对教学事实与权属。。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`sourceSquareId`|string|必填|原课程ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetSquareId`|string|必填|目标课程ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`expectedSourceRevision`|int|必填|读取并固定的来源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`expectedTargetRevision`|int|必填|读取并固定的目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`evidenceRefs`|object[]|可省略默认[]，要求教学依据的动作必须至少一项|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`evidenceRefs[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "sourceSquareId": "square_example_1",
  "targetSquareId": "square_example_2",
  "expectedSourceRevision": 1,
  "expectedTargetRevision": 1,
  "reason": "按实际协作情况说明",
  "evidenceRefs": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_MERGE_01](#response-r-merge-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-merge-04"></a>

#### MERGE-04 — 提交 · 课程合并提案 / 取消

`POST /api/v1/square-merge-proposals/{proposalId}/cancellations`

权限：有资格课程成员发起/投本人票；当前教师及有权执行者核对教学事实与权属。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "reason": "按实际协作情况说明",
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_MERGE_01](#response-r-merge-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-merge-05"></a>

#### MERGE-05 — 提交 · 课程合并提案 / 最终执行

`POST /api/v1/square-merge-proposals/{proposalId}/executions`

权限：有资格课程成员发起/投本人票；当前教师及有权执行者核对教学事实与权属。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_MERGE_01](#response-r-merge-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-merge-06"></a>

#### MERGE-06 — 提交 · 课程合并提案 / 教学事实核对

`POST /api/v1/square-merge-proposals/{proposalId}/fact-checks`

权限：有资格课程成员发起/投本人票；当前教师及有权执行者核对教学事实与权属。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`decision`|string|必填|本人明确提交的决定；允许值：CONFIRM/RETURN|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`assignmentMappings`|object[]|必填|合并课程时明确保持分离的作业映射；遵守本字段类型、统一上限及对应业务条件|
|`assignmentMappings[].fromAssignmentId`|string|父项存在时必填，分支字段例外|原作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`assignmentMappings[].keepSeparate`|bool|父项存在时必填，分支字段例外|作业保持分离，不能合并旧成绩；遵守本字段类型、统一上限及对应业务条件|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "decision": "CONFIRM",
  "reason": "按实际协作情况说明",
  "assignmentMappings": [
    {
      "fromAssignmentId": "assignment_example_1",
      "keepSeparate": true
    }
  ],
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_MERGE_06](#response-r-merge-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-merge-07"></a>

#### MERGE-07 — 保存 · 课程合并提案 / 本人 / 本人选票

`PUT /api/v1/square-merge-proposals/{proposalId}/me/ballots`

权限：本次两侧冻结资格中的本人，分别计票。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`proposalId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`squareId`|string|必填|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`expectedRevision`|int|必填|本人的选票版本，不是表决提案revision；首次选票0；已有选票读取myBallot.revision后保存|
|`choice`|string|必填|本人在冻结资格下的选票；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "squareId": "square_example_1",
  "expectedRevision": 1,
  "choice": "AGREE"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_MERGE_01](#response-r-merge-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-9"></a>

### 8.9 教师工作台、班级授权与权属

**当前权限：** T仅当前负责范围；A仅本人有效授权范围，任何能力都不跨班扩权。

- TEACH-01/02完整返回实际工作台及稳定termId，字典失效要本人重选，不伪装成没有班级。教师目录明确选择有效学期后查询；匹配与创建固定同一学校/学期。
- 教师认领只提交PENDING，由核验事实和管理复核激活，不能由自报姓名取得T。助理授权明确capabilities及expiresAt，未传期限按学期末；撤销/到期立即失效，不能授改分、代验收或全群读取权。
- 教师转交只给已核验可接任教师，由接任本人ACCEPT/DECLINE，接受前原教师负责。离任须处理未完事项与有效授权，转交后原课程码失效；权限变化影响旧预览与迟到写入。

当前代码定位：[TeachingScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/TeachingScreen.java)、[TeachingOwnershipScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/TeachingOwnershipScreen.java)、[CourseWorkspaceScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/CourseWorkspaceScreen.java)、[PreviewWorkspace.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewWorkspace.java)、[TermRules.java](../android-app/app/src/main/java/com/grupi/app/data/TermRules.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[TEACH-01](#api-teach-01)|`GET /me/admin-workspaces`|读取 · 本人 / 本人助理工作台|
|[TEACH-02](#api-teach-02)|`GET /me/teaching-workspaces`|读取 · 本人 / 本人教学工作台|
|[TEACH-03](#api-teach-03)|`GET /squares/{squareId}/admin-grants`|读取 · 课程 / 班级助理授权|
|[TEACH-04](#api-teach-04)|`GET /squares/{squareId}/teaching-progress`|读取 · 课程 / 教学进展|
|[TEACH-05](#api-teach-05)|`POST /admin-grants/{grantId}/revocations`|提交 · 班级助理授权 / 撤销|
|[TEACH-06](#api-teach-06)|`POST /squares/{squareId}/admin-grants`|提交 · 课程 / 班级助理授权|
|[TEACH-07](#api-teach-07)|`POST /squares/{squareId}/teacher-claims`|提交 · 课程 / 教师认领|
|[TEACH-08](#api-teach-08)|`POST /squares/{squareId}/teacher-relinquishments`|提交 · 课程 / 教师离任|
|[TEACH-09](#api-teach-09)|`POST /squares/{squareId}/teacher-transfers`|提交 · 课程 / 教师权属转交|
|[TEACH-10](#api-teach-10)|`POST /teacher-transfers/{transferId}/responses`|提交 · 教师权属转交 / 本人回应|

<a id="api-teach-01"></a>

#### TEACH-01 — 读取 · 本人 / 本人助理工作台

`GET /api/v1/me/admin-workspaces`

权限：A，仅本人当前有效授权课程。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/admin-workspaces`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_COURSE_05](#response-r-course-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-teach-02"></a>

#### TEACH-02 — 读取 · 本人 / 本人教学工作台

`GET /api/v1/me/teaching-workspaces`

权限：T，仅本人当前负责课程。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/teaching-workspaces`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEACH_02](#response-r-teach-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-teach-03"></a>

#### TEACH-03 — 读取 · 课程 / 班级助理授权

`GET /api/v1/squares/{squareId}/admin-grants`

权限：T仅当前负责范围；A仅本人有效授权范围，任何能力都不跨班扩权。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/squares/square_example_1/admin-grants`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEACH_03](#response-r-teach-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-teach-04"></a>

#### TEACH-04 — 读取 · 课程 / 教学进展

`GET /api/v1/squares/{squareId}/teaching-progress`

权限：T仅当前负责范围；A仅本人有效授权范围，任何能力都不跨班扩权。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/squares/square_example_1/teaching-progress`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_TEACH_04](#response-r-teach-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-teach-05"></a>

#### TEACH-05 — 提交 · 班级助理授权 / 撤销

`POST /api/v1/admin-grants/{grantId}/revocations`

权限：T，原课程当前负责教师。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`grantId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEACH_05](#response-r-teach-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-teach-06"></a>

#### TEACH-06 — 提交 · 课程 / 班级助理授权

`POST /api/v1/squares/{squareId}/admin-grants`

权限：T，原课程当前负责教师。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`studentId`|string|必填|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`capabilities`|string[]|必填|本范围当前有效能力集合；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "studentId": "user_example_2",
  "capabilities": [
    "ANNOUNCEMENT_WRITE"
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEACH_05](#response-r-teach-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-teach-07"></a>

#### TEACH-07 — 提交 · 课程 / 教师认领

`POST /api/v1/squares/{squareId}/teacher-claims`

权限：核验名单允许认领该课程的教师本人，申请不授权。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`message`|string|必填|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`proofRefs`|object[]|可省略默认[]，最多3个|本人入课核验资料引用，最多3项；可省略默认[]，最多3个|
|`proofRefs[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`proofRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`proofRefs[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`proofRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "message": "希望参与项目协作",
  "proofRefs": []
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEACH_07](#response-r-teach-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-teach-08"></a>

#### TEACH-08 — 提交 · 课程 / 教师离任

`POST /api/v1/squares/{squareId}/teacher-relinquishments`

权限：原课程当前教师本人，离任前置已满足。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedSquareRevision`|int|必填|读取并固定的课程并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedSquareRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEACH_08](#response-r-teach-08)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-teach-09"></a>

#### TEACH-09 — 提交 · 课程 / 教师权属转交

`POST /api/v1/squares/{squareId}/teacher-transfers`

权限：原课程当前教师，合法接任教师。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedSquareRevision`|int|必填|读取并固定的课程并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`recipientTeacherId`|string|必填|本次合法接任教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求示例：

```json
{
  "expectedSquareRevision": 1,
  "reason": "按实际协作情况说明",
  "recipientTeacherId": "user_example_teacher2"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEACH_09](#response-r-teach-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-teach-10"></a>

#### TEACH-10 — 提交 · 教师权属转交 / 本人回应

`POST /api/v1/teacher-transfers/{transferId}/responses`

权限：转交指定接任教师本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`transferId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/DECLINE|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "ACCEPT"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEACH_09](#response-r-teach-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-10"></a>

### 8.10 课程计分、成绩发布、复核与教师评价

**当前权限：** 学生仅本人；T仅当前负责课程/作业；A没有评分和私人分数读取授权。

- 量规由T明确预览并确认processWeight/说明/理由；B取有效责任份额且显式结算，Q逐人教学判断，Δ需理由及依据。没有有效规则、分母为0、来源/结算/草稿过期时返回未就绪，不造默认成绩。
- GRADE-17教学账本更正固定expectedSourceKey及原任务/分项，保留有理由的采用/排除决定，不改组内原始验收或贡献。GRADE-18按当前来源和ruleVersion结算。
- GRADE-13按明确studentIds/expectedCandidates及可选appealLinks预览，首次或实质变化才发布；GRADE-14使用固定token追加个人成绩快照。同批最多200，不能按预览后变化的列表盲发。
- 复核绑定原gradeSnapshotId，MAINTAIN需说明；REVISE进入AWAITING_REVISED_PUBLICATION，实际发布关联新成绩后才RESOLVED_REVISED。项目归档不终止教师后续评分/复核。
- 教师文字评价绑定sourceGradeSnapshotId，内容修订产生新contentVersion；本人SHOW/HIDE针对确切内容版，作品展示授权不能暴露个人分数、教师私密更正或原始互评。

当前代码定位：[GradesScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/GradesScreen.java)、[GradebookScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/GradebookScreen.java)、[TeacherSummaryScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/TeacherSummaryScreen.java)、[PreviewGrades.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewGrades.java)、[PreviewAssessment.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewAssessment.java)、[GradebookRules.java](../android-app/app/src/main/java/com/grupi/app/data/GradebookRules.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[GRADE-01](#api-grade-01)|`GET /assignments/{assignmentId}/grade-appeals`|读取 · 作业 / 成绩复核|
|[GRADE-02](#api-grade-02)|`GET /assignments/{assignmentId}/gradebook`|读取 · 作业 / 成绩名册|
|[GRADE-03](#api-grade-03)|`GET /assignments/{assignmentId}/grading-rule`|读取 · 作业 / 当前教学规则|
|[GRADE-04](#api-grade-04)|`GET /assignments/{assignmentId}/students/{studentId}/grade-history`|读取 · 作业 / 学生 / 成绩历史|
|[GRADE-05](#api-grade-05)|`GET /assignments/{assignmentId}/students/{studentId}/ledger`|读取 · 作业 / 学生 / 教学账本|
|[GRADE-06](#api-grade-06)|`GET /assignments/{assignmentId}/students/{studentId}/teacher-summary`|读取 · 作业 / 学生 / 教师文字评价|
|[GRADE-07](#api-grade-07)|`GET /grade-appeals/{appealId}`|读取 · 成绩复核|
|[GRADE-08](#api-grade-08)|`GET /grade-snapshots/{snapshotId}`|读取 · 正式成绩快照|
|[GRADE-09](#api-grade-09)|`GET /me/grade-appeals`|读取 · 本人 / 成绩复核|
|[GRADE-10](#api-grade-10)|`GET /me/grades`|读取 · 本人 / 本人成绩|
|[GRADE-11](#api-grade-11)|`GET /me/teacher-summaries`|读取 · 本人 / 教师文字评价|
|[GRADE-12](#api-grade-12)|`GET /teacher-summaries/{summaryId}`|读取 · 教师文字评价|
|[GRADE-13](#api-grade-13)|`POST /assignments/{assignmentId}/grade-publication-previews`|提交 · 作业 / 成绩发布预览|
|[GRADE-14](#api-grade-14)|`POST /assignments/{assignmentId}/grade-publications`|提交 · 作业 / 成绩正式发布|
|[GRADE-15](#api-grade-15)|`POST /assignments/{assignmentId}/grading-rule-previews`|提交 · 作业 / 规则预览|
|[GRADE-16](#api-grade-16)|`POST /assignments/{assignmentId}/grading-rules`|提交 · 作业 / 规则确认|
|[GRADE-17](#api-grade-17)|`POST /assignments/{assignmentId}/students/{studentId}/ledger-corrections`|提交 · 作业 / 学生 / 教学采用/排除|
|[GRADE-18](#api-grade-18)|`POST /assignments/{assignmentId}/students/{studentId}/settlements`|提交 · 作业 / 学生 / 基础分结算|
|[GRADE-19](#api-grade-19)|`POST /grade-appeals/{appealId}/responses`|提交 · 成绩复核 / 本人回应|
|[GRADE-20](#api-grade-20)|`POST /grade-snapshots/{snapshotId}/appeals`|提交 · 正式成绩快照 / 发起成绩复核|
|[GRADE-21](#api-grade-21)|`POST /teacher-summaries/{summaryId}/consents`|提交 · 教师文字评价 / 本人展示授权|
|[GRADE-22](#api-grade-22)|`POST /teacher-summaries/{summaryId}/withdrawals`|提交 · 教师文字评价 / 撤回|
|[GRADE-23](#api-grade-23)|`PUT /assignments/{assignmentId}/students/{studentId}/grade-draft`|保存 · 作业 / 学生 / 个人教学评分草稿|
|[GRADE-24](#api-grade-24)|`PUT /assignments/{assignmentId}/students/{studentId}/teacher-summary`|保存 · 作业 / 学生 / 教师文字评价|

<a id="api-grade-01"></a>

#### GRADE-01 — 读取 · 作业 / 成绩复核

`GET /api/v1/assignments/{assignmentId}/grade-appeals`

权限：学生仅本人；T仅当前负责课程/作业；A没有评分和私人分数读取授权。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/assignments/assignment_example_1/grade-appeals`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_01](#response-r-grade-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-02"></a>

#### GRADE-02 — 读取 · 作业 / 成绩名册

`GET /api/v1/assignments/{assignmentId}/gradebook`

权限：学生仅本人；T仅当前负责课程/作业；A没有评分和私人分数读取授权。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/assignments/assignment_example_1/gradebook`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_02](#response-r-grade-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-03"></a>

#### GRADE-03 — 读取 · 作业 / 当前教学规则

`GET /api/v1/assignments/{assignmentId}/grading-rule`

权限：学生仅本人；T仅当前负责课程/作业；A没有评分和私人分数读取授权。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/assignments/assignment_example_1/grading-rule`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_03](#response-r-grade-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-04"></a>

#### GRADE-04 — 读取 · 作业 / 学生 / 成绩历史

`GET /api/v1/assignments/{assignmentId}/students/{studentId}/grade-history`

权限：学生仅本人；T仅当前负责课程/作业；A没有评分和私人分数读取授权。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|
|`studentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/assignments/assignment_example_1/students/student_example_1/grade-history`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_04](#response-r-grade-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-05"></a>

#### GRADE-05 — 读取 · 作业 / 学生 / 教学账本

`GET /api/v1/assignments/{assignmentId}/students/{studentId}/ledger`

权限：学生仅本人；T仅当前负责课程/作业；A没有评分和私人分数读取授权。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|
|`studentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/assignments/assignment_example_1/students/student_example_1/ledger`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_05](#response-r-grade-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-06"></a>

#### GRADE-06 — 读取 · 作业 / 学生 / 教师文字评价

`GET /api/v1/assignments/{assignmentId}/students/{studentId}/teacher-summary`

权限：学生仅本人；T仅当前负责课程/作业；A没有评分和私人分数读取授权。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|
|`studentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/assignments/assignment_example_1/students/student_example_1/teacher-summary`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_06](#response-r-grade-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-07"></a>

#### GRADE-07 — 读取 · 成绩复核

`GET /api/v1/grade-appeals/{appealId}`

权限：学生仅本人；T仅当前负责课程/作业；A没有评分和私人分数读取授权。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`appealId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/grade-appeals/appeal_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_07](#response-r-grade-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-08"></a>

#### GRADE-08 — 读取 · 正式成绩快照

`GET /api/v1/grade-snapshots/{snapshotId}`

权限：学生仅本人；T仅当前负责课程/作业；A没有评分和私人分数读取授权。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`snapshotId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/grade-snapshots/snapshot_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_08](#response-r-grade-08)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-09"></a>

#### GRADE-09 — 读取 · 本人 / 成绩复核

`GET /api/v1/me/grade-appeals`

权限：学生本人，仅本人复核。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/grade-appeals`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_01](#response-r-grade-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-10"></a>

#### GRADE-10 — 读取 · 本人 / 本人成绩

`GET /api/v1/me/grades`

权限：学生本人，仅本人成绩。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/grades`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_04](#response-r-grade-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-11"></a>

#### GRADE-11 — 读取 · 本人 / 教师文字评价

`GET /api/v1/me/teacher-summaries`

权限：学生本人，仅本人教师文字评价。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/teacher-summaries`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_11](#response-r-grade-11)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-12"></a>

#### GRADE-12 — 读取 · 教师文字评价

`GET /api/v1/teacher-summaries/{summaryId}`

权限：学生仅本人；T仅当前负责课程/作业；A没有评分和私人分数读取授权。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`summaryId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/teacher-summaries/summary_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_06](#response-r-grade-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-13"></a>

#### GRADE-13 — 提交 · 作业 / 成绩发布预览

`POST /api/v1/assignments/{assignmentId}/grade-publication-previews`

权限：T，原作业当前评分权属。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`studentIds`|string[]|必填|明确选择的学生集合；遵守本字段类型、统一上限及对应业务条件|
|`appealLinks`|object[]|修订关联复核时传入，否则[]；每项studentId/appealId|本次成绩发布关联的原复核记录；修订关联复核时传入，否则[]；每项studentId/appealId|
|`appealLinks[].studentId`|string|父项存在时必填，分支字段例外|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`appealLinks[].appealId`|string|父项存在时必填，分支字段例外|当前结构固定的appeal资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`expectedCandidates`|object[]|从成绩名册选择发布时必填并与studentIds一致，逐人发布入口可省略由服务器固定来源|本次明确选择且冻结来源的成绩候选集合；从成绩名册选择发布时必填并与studentIds一致，逐人发布入口可省略由服务器固定来源|
|`expectedCandidates[].studentId`|string|父项存在时必填，分支字段例外|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`expectedCandidates[].classification`|string|父项存在时必填，分支字段例外|FIRST/REVISION/BLOCKED/UNCHANGED；遵守本字段类型、统一上限及对应业务条件|
|`expectedCandidates[].basisDigest`|string|父项存在时必填，分支字段例外|本次固定教学依据摘要；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "studentIds": [
    "user_example_1"
  ],
  "appealLinks": [],
  "expectedCandidates": [
    {
      "studentId": "user_example_1",
      "classification": "FIRST",
      "basisDigest": "basis-test-1"
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_13](#response-r-grade-13)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-14"></a>

#### GRADE-14 — 提交 · 作业 / 成绩正式发布

`POST /api/v1/assignments/{assignmentId}/grade-publications`

权限：T，原作业当前评分权属及绑定预览。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`previewToken`|string|增强通知发布必填；普通简单公告可省略，见发布规则|固定本人/范围/输入/来源及期限的预览凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|

请求示例：

```json
{
  "previewToken": "preview-token-test"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_14](#response-r-grade-14)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-15"></a>

#### GRADE-15 — 提交 · 作业 / 规则预览

`POST /api/v1/assignments/{assignmentId}/grading-rule-previews`

权限：T，原作业当前规则权属。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`processWeight`|string|必填|教师确认的过程权重w（百分数字符串）；0～100的最多两位小数字符串，必须由当前教师确认|
|`description`|string|可省略；文字默认空，集合默认[]|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`expectedAssignmentRevision`|int|必填|读取并固定的作业并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "processWeight": "0.40",
  "description": "实现登录与会话",
  "reason": "按实际协作情况说明",
  "expectedAssignmentRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_15](#response-r-grade-15)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-16"></a>

#### GRADE-16 — 提交 · 作业 / 规则确认

`POST /api/v1/assignments/{assignmentId}/grading-rules`

权限：T，原作业当前规则权属及绑定预览。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`previewToken`|string|增强通知发布必填；普通简单公告可省略，见发布规则|固定本人/范围/输入/来源及期限的预览凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|

请求示例：

```json
{
  "previewToken": "preview-token-test"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_03](#response-r-grade-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-17"></a>

#### GRADE-17 — 提交 · 作业 / 学生 / 教学采用/排除

`POST /api/v1/assignments/{assignmentId}/students/{studentId}/ledger-corrections`

权限：T，原作业当前教学计分权属。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|
|`studentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedSourceKey`|string|必填|必须与当前来源一致的指纹；遵守本字段类型、统一上限及对应业务条件|
|`taskId`|string|必填|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`unitId`|string|必填|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`correction`|object|必填|教师有理由的采用/排除决定；遵守本字段类型、统一上限及对应业务条件|
|`correction.responsibilityPoints`|string|父项存在时必填，分支字段例外|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`correction.acceptedPoints`|string|父项存在时必填，分支字段例外|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`evidenceRefs`|object[]|可省略默认[]，要求教学依据的动作必须至少一项|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`evidenceRefs[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "expectedSourceKey": "source-test-1",
  "taskId": "task_example_1",
  "unitId": "unit_example_1",
  "correction": {
    "responsibilityPoints": "10.00",
    "acceptedPoints": "8.00"
  },
  "reason": "按实际协作情况说明",
  "evidenceRefs": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_17](#response-r-grade-17)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-18"></a>

#### GRADE-18 — 提交 · 作业 / 学生 / 基础分结算

`POST /api/v1/assignments/{assignmentId}/students/{studentId}/settlements`

权限：T，原作业当前计分权属。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|
|`studentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedSourceKey`|string|必填|必须与当前来源一致的指纹；遵守本字段类型、统一上限及对应业务条件|
|`ruleVersion`|int|必填|已确认教学规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|

请求示例：

```json
{
  "expectedSourceKey": "source-test-1",
  "ruleVersion": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_18](#response-r-grade-18)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-19"></a>

#### GRADE-19 — 提交 · 成绩复核 / 本人回应

`POST /api/v1/grade-appeals/{appealId}/responses`

权限：T，原复核所属作业当前权属。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`appealId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：MAINTAIN/REVISE|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`expectedCurrentGradeSnapshotId`|string|必填|当前结构固定的expectedCurrentGradeSnapshot资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "MAINTAIN",
  "reason": "按实际协作情况说明",
  "expectedCurrentGradeSnapshotId": "grade_snapshot_example_1"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_07](#response-r-grade-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-20"></a>

#### GRADE-20 — 提交 · 正式成绩快照 / 发起成绩复核

`POST /api/v1/grade-snapshots/{snapshotId}/appeals`

权限：原正式成绩对应学生本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`snapshotId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`issue`|string|必填|本人提出的复核问题；遵守本字段类型、统一上限及对应业务条件|
|`sourceRefs`|object[]|可省略；文字默认空，集合默认[]|固定来源引用集合；遵守本字段类型、统一上限及对应业务条件|
|`sourceRefs[].type`|string|父项存在时必填，分支字段例外|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`sourceRefs[].id`|string|父项存在时必填，分支字段例外|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`sourceRefs[].version`|int|父项存在时必填，分支字段例外|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs`|object[]|可省略默认[]，要求教学依据的动作必须至少一项|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`evidenceRefs[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "issue": "请核对验收点数",
  "sourceRefs": [],
  "evidenceRefs": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_07](#response-r-grade-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-21"></a>

#### GRADE-21 — 提交 · 教师文字评价 / 本人展示授权

`POST /api/v1/teacher-summaries/{summaryId}/consents`

权限：文字评价对象本人，仅该contentVersion。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`summaryId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`expectedContentVersion`|int|必填|本结构固定的expectedContentVersion版本，不能与对象revision互换；遵守本字段类型、统一上限及对应业务条件|
|`decision`|string|必填|本人明确提交的决定；允许值：SHOW/HIDE|

请求示例：

```json
{
  "expectedRevision": 1,
  "expectedContentVersion": 1,
  "decision": "SHOW"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_06](#response-r-grade-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-22"></a>

#### GRADE-22 — 提交 · 教师文字评价 / 撤回

`POST /api/v1/teacher-summaries/{summaryId}/withdrawals`

权限：原课程当前有权教师。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`summaryId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_06](#response-r-grade-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-23"></a>

#### GRADE-23 — 保存 · 作业 / 学生 / 个人教学评分草稿

`PUT /api/v1/assignments/{assignmentId}/students/{studentId}/grade-draft`

权限：T，原作业当前学生评分权属。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|
|`studentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`q`|int|必填|当前授权范围检索文字；教学草稿中另为整数Q；教学Q为整数0～100|
|`delta`|int|必填|教师有理由的整数教学调整Δ；整数-100～100；非0时deltaReason非空|
|`deltaReason`|string|delta非0时必填1～500；0时可为空|教师教学调整依据；delta非0时必填1～500；0时可为空|
|`evidenceRefs`|object[]|可省略默认[]，要求教学依据的动作必须至少一项|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`evidenceRefs[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "expectedRevision": 0,
  "q": 85,
  "delta": 2,
  "deltaReason": "量规内的专项改进",
  "evidenceRefs": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_23](#response-r-grade-23)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-grade-24"></a>

#### GRADE-24 — 保存 · 作业 / 学生 / 教师文字评价

`PUT /api/v1/assignments/{assignmentId}/students/{studentId}/teacher-summary`

权限：T，原作业当前学生评价权属。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`assignmentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|
|`studentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceGradeSnapshotId`|string|必填|评价所依据的正式成绩快照ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`summary`|string|必填|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 0,
  "sourceGradeSnapshotId": "grade_snapshot_example_1",
  "summary": "完成登录与退出"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_GRADE_06](#response-r-grade-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-11"></a>

### 8.11 复盘、五栏报告、交付发布与归档

**当前权限：** M读有权协作报告并写本人复盘；L写整体总结、交付及生命周期动作。

- 本人复盘不能由组长代填；组长整体说明单独作者和版本。共享报告只记录有权教学/协作事实及确认有效贡献，不包含个人正式成绩、原始互评或教师私密计分更正。缺项保持缺项，不自动零分。
- 交付草稿和预览固定来源材料、版本及缺口，预览10分钟；明确发布生成不可覆盖DeliveryRelease，新版保留旧版。材料缺口回到原任务或草稿，不以空链接占位通过。
- 报告快照PROCESS/FINAL/EMPTY固定结构内容、sourceKey、closingCycle。每次重新进入收尾生成新轮次；恢复ACTIVE不让旧FINAL冒充新轮次最终报告。非空归档须当前有效交付和本轮FINAL，空项目须EMPTY及明确原因。

当前代码定位：[ReportScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/ReportScreen.java)、[PreviewContentPages.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewContentPages.java)、[PreviewProfiles.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewProfiles.java)、[PreviewWorkspace.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewWorkspace.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[REPORT-01](#api-report-01)|`GET /projects/{projectId}/delivery-release-draft`|读取 · 项目 / 交付草稿|
|[REPORT-02](#api-report-02)|`GET /projects/{projectId}/delivery-releases`|读取 · 项目 / 交付版本|
|[REPORT-03](#api-report-03)|`GET /projects/{projectId}/report`|读取 · 项目 / 五栏协作报告|
|[REPORT-04](#api-report-04)|`GET /projects/{projectId}/report-snapshots`|读取 · 项目 / 报告快照|
|[REPORT-05](#api-report-05)|`GET /report-snapshots/{snapshotId}`|读取 · 报告快照|
|[REPORT-06](#api-report-06)|`POST /projects/{projectId}/delivery-release-previews`|提交 · 项目 / 交付预览|
|[REPORT-07](#api-report-07)|`POST /projects/{projectId}/delivery-releases`|提交 · 项目 / 交付版本|
|[REPORT-08](#api-report-08)|`POST /projects/{projectId}/lifecycle-actions`|提交 · 项目 / 项目生命周期|
|[REPORT-09](#api-report-09)|`POST /projects/{projectId}/report-snapshots`|提交 · 项目 / 报告快照|
|[REPORT-10](#api-report-10)|`PUT /projects/{projectId}/delivery-release-draft`|保存 · 项目 / 交付草稿|
|[REPORT-11](#api-report-11)|`PUT /projects/{projectId}/me/reflection`|保存 · 项目 / 本人 / 本人复盘|
|[REPORT-12](#api-report-12)|`PUT /projects/{projectId}/overall-reflection`|保存 · 项目 / 整体总结|

<a id="api-report-01"></a>

#### REPORT-01 — 读取 · 项目 / 交付草稿

`GET /api/v1/projects/{projectId}/delivery-release-draft`

权限：M读有权协作报告并写本人复盘；L写整体总结、交付及生命周期动作。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/delivery-release-draft`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORT_01](#response-r-report-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-report-02"></a>

#### REPORT-02 — 读取 · 项目 / 交付版本

`GET /api/v1/projects/{projectId}/delivery-releases`

权限：M读有权协作报告并写本人复盘；L写整体总结、交付及生命周期动作。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/delivery-releases`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORT_02](#response-r-report-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-report-03"></a>

#### REPORT-03 — 读取 · 项目 / 五栏协作报告

`GET /api/v1/projects/{projectId}/report`

权限：M读有权协作报告并写本人复盘；L写整体总结、交付及生命周期动作。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/report`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORT_03](#response-r-report-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-report-04"></a>

#### REPORT-04 — 读取 · 项目 / 报告快照

`GET /api/v1/projects/{projectId}/report-snapshots`

权限：M读有权协作报告并写本人复盘；L写整体总结、交付及生命周期动作。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/report-snapshots`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORT_04](#response-r-report-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-report-05"></a>

#### REPORT-05 — 读取 · 报告快照

`GET /api/v1/report-snapshots/{snapshotId}`

权限：M读有权协作报告并写本人复盘；L写整体总结、交付及生命周期动作。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`snapshotId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/report-snapshots/snapshot_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORT_05](#response-r-report-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-report-06"></a>

#### REPORT-06 — 提交 · 项目 / 交付预览

`POST /api/v1/projects/{projectId}/delivery-release-previews`

权限：L，当前项目交付预览。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedSourceKey`|string|必填|必须与当前来源一致的指纹；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedSourceKey": "source-test-1"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORT_06](#response-r-report-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-report-07"></a>

#### REPORT-07 — 提交 · 项目 / 交付版本

`POST /api/v1/projects/{projectId}/delivery-releases`

权限：L，当前项目及有效交付预览。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`previewToken`|string|增强通知发布必填；普通简单公告可省略，见发布规则|固定本人/范围/输入/来源及期限的预览凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|

请求示例：

```json
{
  "previewToken": "preview-token-test"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORT_07](#response-r-report-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-report-08"></a>

#### REPORT-08 — 提交 · 项目 / 项目生命周期

`POST /api/v1/projects/{projectId}/lifecycle-actions`

权限：L，当前项目及当前收尾轮次。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`action`|string|必填|本人明确执行的动作；允许值：ENTER_CLOSING/RESTORE_ACTIVE/ARCHIVE|
|`expectedReportId`|string|ARCHIVE非空项目必填本轮最终报告；其他生命周期动作省略|当前结构固定的expectedReport资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`expectedReleaseId`|string|非空归档必填当前有效交付；其他动作可省略|当前结构固定的expectedRelease资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求示例：

```json
{
  "expectedRevision": 3,
  "action": "ARCHIVE",
  "expectedReportId": "report_snapshot_example_1",
  "expectedReleaseId": "delivery_release_example_1"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_TEAM_07](#response-r-team-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-report-09"></a>

#### REPORT-09 — 提交 · 项目 / 报告快照

`POST /api/v1/projects/{projectId}/report-snapshots`

权限：L，当前项目报告来源。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`type`|string|必填|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`name`|string|必填|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`expectedSourceKey`|string|必填|必须与当前来源一致的指纹；遵守本字段类型、统一上限及对应业务条件|
|`closingCycle`|int|必填|项目本次收尾轮次；遵守本字段类型、统一上限及对应业务条件|
|`emptyReason`|string|报告type=EMPTY必填1～1000，其他空字符串|空项目收尾原因；报告type=EMPTY必填1～1000，其他空字符串|

请求示例：

```json
{
  "type": "PROCESS",
  "name": "课程协作应用",
  "expectedSourceKey": "source-test-1",
  "closingCycle": 0,
  "emptyReason": ""
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORT_05](#response-r-report-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-report-10"></a>

#### REPORT-10 — 保存 · 项目 / 交付草稿

`PUT /api/v1/projects/{projectId}/delivery-release-draft`

权限：L，当前项目交付草稿。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`work`|object[]|必填|有权作品投影；遵守本字段类型、统一上限及对应业务条件|
|`work[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`work[].url`|string|父项存在时必填，分支字段例外|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`work[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`report`|object[]|必填|报告材料引用；遵守本字段类型、统一上限及对应业务条件|
|`report[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`report[].url`|string|父项存在时必填，分支字段例外|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`report[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`demo`|object[]|必填|演示材料引用；遵守本字段类型、统一上限及对应业务条件|
|`demo[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`demo[].url`|string|父项存在时必填，分支字段例外|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`demo[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`notes`|string|可省略；文字默认空，集合默认[]|补充说明；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "work": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ],
  "report": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ],
  "demo": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ],
  "notes": "保留已验收历史"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORT_01](#response-r-report-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-report-11"></a>

#### REPORT-11 — 保存 · 项目 / 本人 / 本人复盘

`PUT /api/v1/projects/{projectId}/me/reflection`

权限：M，仅本人复盘。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`workSummary`|string|必填|本人完成情况；遵守本字段类型、统一上限及对应业务条件|
|`learning`|string|必填|本人学习收获；遵守本字段类型、统一上限及对应业务条件|
|`problems`|string|必填|实际遇到的问题；遵守本字段类型、统一上限及对应业务条件|
|`improvements`|string|必填|后续改进；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 0,
  "workSummary": "完成客户端登录页面",
  "learning": "掌握前后端联调",
  "problems": "异常状态覆盖不足",
  "improvements": "补充失败场景验证"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORT_11](#response-r-report-11)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-report-12"></a>

#### REPORT-12 — 保存 · 项目 / 整体总结

`PUT /api/v1/projects/{projectId}/overall-reflection`

权限：L，仅本人作者的整体说明。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`summary`|string|必填|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`coordination`|string|必填|协作说明；遵守本字段类型、统一上限及对应业务条件|
|`improvements`|string|必填|后续改进；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 0,
  "summary": "完成登录与退出",
  "coordination": "按已确认职责协作",
  "improvements": "补充失败场景验证"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORT_12](#response-r-report-12)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-12"></a>

### 8.12 画像、作品、合作记录与互评

**当前权限：** 本人维护画像/作品；他人按可见性与原来源许可读取；原始互评仅作者。

- 作品PRIVATE/TEAM/CLASS范围与实际来源授权取交集，历史版本仅作者维护；撤回作品不删除原任务事实或成绩。共享记录不开放教师私密计分或他人原始互评。
- 互评按真实合作上下文和目标成员，三维唯一完整，不能自评；合作结束后14天可修改，本人可随时撤回但不可撤后重提刷记录。负向观察需具体事实；公开汇总至少3个有效作者且逐维足够观察，否则不显示匿名统计。 三维标识为DELIVERY/COMMUNICATION/REWORK，等级为POSITIVE/NEEDS_IMPROVEMENT/NOT_OBSERVED；不足显示INSUFFICIENT_DATA。

当前代码定位：[ProfileScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/ProfileScreen.java)、[PeerReviewScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/PeerReviewScreen.java)、[PreviewProfiles.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewProfiles.java)、[PeerReviewWriteRules.java](../android-app/app/src/main/java/com/grupi/app/data/PeerReviewWriteRules.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[PROFILE-01](#api-profile-01)|`GET /me/cooperations`|读取 · 本人 / 本人合作记录|
|[PROFILE-02](#api-profile-02)|`GET /me/works`|读取 · 本人 / 作品|
|[PROFILE-03](#api-profile-03)|`GET /peer-reviews/{reviewId}`|读取 · 合作互评|
|[PROFILE-04](#api-profile-04)|`GET /projects/{projectId}/peer-review-context`|读取 · 项目 / 互评资格上下文|
|[PROFILE-05](#api-profile-05)|`GET /users/{userId}/profile`|读取 · 用户 / 画像|
|[PROFILE-06](#api-profile-06)|`GET /works/{workId}`|读取 · 作品|
|[PROFILE-07](#api-profile-07)|`PATCH /me/works/{workId}`|修改 · 本人 / 作品|
|[PROFILE-08](#api-profile-08)|`POST /me/works`|提交 · 本人 / 作品|
|[PROFILE-09](#api-profile-09)|`POST /me/works/{workId}/withdrawals`|提交 · 本人 / 作品 / 撤回|
|[PROFILE-10](#api-profile-10)|`POST /peer-reviews/{reviewId}/withdrawals`|提交 · 合作互评 / 撤回|
|[PROFILE-11](#api-profile-11)|`POST /projects/{projectId}/peer-reviews`|提交 · 项目 / 合作互评|
|[PROFILE-12](#api-profile-12)|`PUT /peer-reviews/{reviewId}`|保存 · 合作互评|

<a id="api-profile-01"></a>

#### PROFILE-01 — 读取 · 本人 / 本人合作记录

`GET /api/v1/me/cooperations`

权限：本人维护画像/作品；他人按可见性与原来源许可读取；原始互评仅作者。。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/cooperations`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_PROFILE_01](#response-r-profile-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-profile-02"></a>

#### PROFILE-02 — 读取 · 本人 / 作品

`GET /api/v1/me/works`

权限：本人维护画像/作品；他人按可见性与原来源许可读取；原始互评仅作者。。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/works`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_PROFILE_02](#response-r-profile-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-profile-03"></a>

#### PROFILE-03 — 读取 · 合作互评

`GET /api/v1/peer-reviews/{reviewId}`

权限：原始互评作者本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`reviewId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/peer-reviews/review_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_PROFILE_03](#response-r-profile-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-profile-04"></a>

#### PROFILE-04 — 读取 · 项目 / 互评资格上下文

`GET /api/v1/projects/{projectId}/peer-review-context`

权限：本人维护画像/作品；他人按可见性与原来源许可读取；原始互评仅作者。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/peer-review-context`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_PROFILE_04](#response-r-profile-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-profile-05"></a>

#### PROFILE-05 — 读取 · 用户 / 画像

`GET /api/v1/users/{userId}/profile`

权限：本人维护画像/作品；他人按可见性与原来源许可读取；原始互评仅作者。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`userId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`contextProjectId`|string|可省略；按本项及通用分页规则|当前结构固定的contextProject资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`contextApplicationId`|string/null|可省略；按本项及通用分页规则|当前结构固定的contextApplication资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求正文：无。

请求形态：`GET /api/v1/users/user_example_1/profile`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_PROFILE_05](#response-r-profile-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-profile-06"></a>

#### PROFILE-06 — 读取 · 作品

`GET /api/v1/works/{workId}`

权限：本人维护画像/作品；他人按可见性与原来源许可读取；原始互评仅作者。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`workId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`contextProjectId`|string|可省略；按本项及通用分页规则|当前结构固定的contextProject资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`contextApplicationId`|string/null|可省略；按本项及通用分页规则|当前结构固定的contextApplication资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求正文：无。

请求形态：`GET /api/v1/works/work_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_PROFILE_06](#response-r-profile-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-profile-07"></a>

#### PROFILE-07 — 修改 · 本人 / 作品

`PATCH /api/v1/me/works/{workId}`

权限：作品作者本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`workId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`title`|string|必填|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`description`|string|可省略；文字默认空，集合默认[]|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`role`|string|必填|作品中本人承担的角色描述；遵守本字段类型、统一上限及对应业务条件|
|`myContribution`|string|必填|当前本人确认有效的贡献份额；遵守本字段类型、统一上限及对应业务条件|
|`sourceProjectId`|string|必填|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`audience`|string|必填|本条可见范围；遵守本字段类型、统一上限及对应业务条件|
|`artifacts`|object[]|可省略默认[]，单次最多10个当前有权引用|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`artifacts[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`artifacts[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`artifacts[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "expectedRevision": 1,
  "title": "登录页面",
  "description": "实现登录与会话",
  "role": "客户端开发",
  "myContribution": "实现登录与会话",
  "sourceProjectId": "project_example_1",
  "audience": "PRIVATE",
  "artifacts": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_PROFILE_06](#response-r-profile-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-profile-08"></a>

#### PROFILE-08 — 提交 · 本人 / 作品

`POST /api/v1/me/works`

权限：本人，来源许可有效。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`title`|string|必填|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`description`|string|可省略；文字默认空，集合默认[]|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`role`|string|必填|作品中本人承担的角色描述；遵守本字段类型、统一上限及对应业务条件|
|`myContribution`|string|必填|当前本人确认有效的贡献份额；遵守本字段类型、统一上限及对应业务条件|
|`sourceProjectId`|string|必填|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`audience`|string|必填|本条可见范围；遵守本字段类型、统一上限及对应业务条件|
|`artifacts`|object[]|可省略默认[]，单次最多10个当前有权引用|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`artifacts[].kind`|string|父项存在时必填，分支字段例外|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`artifacts[].title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`artifacts[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|

请求示例：

```json
{
  "expectedRevision": 0,
  "title": "登录页面",
  "description": "实现登录与会话",
  "role": "客户端开发",
  "myContribution": "实现登录与会话",
  "sourceProjectId": "project_example_1",
  "audience": "PRIVATE",
  "artifacts": [
    {
      "kind": "LINK",
      "url": "https://example.invalid/demo",
      "title": "示例演示材料"
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_PROFILE_06](#response-r-profile-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-profile-09"></a>

#### PROFILE-09 — 提交 · 本人 / 作品 / 撤回

`POST /api/v1/me/works/{workId}/withdrawals`

权限：作品作者本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`workId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_PROFILE_09](#response-r-profile-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-profile-10"></a>

#### PROFILE-10 — 提交 · 合作互评 / 撤回

`POST /api/v1/peer-reviews/{reviewId}/withdrawals`

权限：原互评作者本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`reviewId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "按实际协作情况说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_PROFILE_10](#response-r-profile-10)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-profile-11"></a>

#### PROFILE-11 — 提交 · 项目 / 合作互评

`POST /api/v1/projects/{projectId}/peer-reviews`

权限：符合真实合作条件的本人，不得自评。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`targetUserId`|string|必填|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`observedEnough`|bool|必填|本维度是否有足够实际观察；遵守本字段类型、统一上限及对应业务条件|
|`observations`|object[]|必填|按维度的本人观察；遵守本字段类型、统一上限及对应业务条件|
|`observations[].dimension`|string|父项存在时必填，分支字段例外|互评维度标识；遵守本字段类型、统一上限及对应业务条件|
|`observations[].level`|string|父项存在时必填，分支字段例外|本维度观察等级；遵守本字段类型、统一上限及对应业务条件|
|`observations[].evidenceText`|string|父项存在时必填，分支字段例外|具体观察事实；遵守本字段类型、统一上限及对应业务条件|
|`comment`|string|可省略，默认空，岗位/约定回应最多500|本人意见；可省略，默认空，岗位/约定回应最多500|

请求示例：

```json
{
  "targetUserId": "user_example_2",
  "expectedRevision": 0,
  "observedEnough": true,
  "observations": [
    {
      "dimension": "DELIVERY",
      "level": "POSITIVE",
      "evidenceText": "按验收标准完成成果"
    },
    {
      "dimension": "COMMUNICATION",
      "level": "NOT_OBSERVED",
      "evidenceText": ""
    },
    {
      "dimension": "REWORK",
      "level": "NOT_OBSERVED",
      "evidenceText": ""
    }
  ],
  "comment": "按本次约定执行"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_PROFILE_03](#response-r-profile-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-profile-12"></a>

#### PROFILE-12 — 保存 · 合作互评

`PUT /api/v1/peer-reviews/{reviewId}`

权限：原互评作者本人及有效编辑窗口。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`reviewId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`targetUserId`|string|必填|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`observedEnough`|bool|必填|本维度是否有足够实际观察；遵守本字段类型、统一上限及对应业务条件|
|`observations`|object[]|必填|按维度的本人观察；遵守本字段类型、统一上限及对应业务条件|
|`observations[].dimension`|string|父项存在时必填，分支字段例外|互评维度标识；遵守本字段类型、统一上限及对应业务条件|
|`observations[].level`|string|父项存在时必填，分支字段例外|本维度观察等级；遵守本字段类型、统一上限及对应业务条件|
|`observations[].evidenceText`|string|父项存在时必填，分支字段例外|具体观察事实；遵守本字段类型、统一上限及对应业务条件|
|`comment`|string|可省略，默认空，岗位/约定回应最多500|本人意见；可省略，默认空，岗位/约定回应最多500|

请求示例：

```json
{
  "targetUserId": "user_example_2",
  "expectedRevision": 1,
  "observedEnough": true,
  "observations": [
    {
      "dimension": "DELIVERY",
      "level": "POSITIVE",
      "evidenceText": "按验收标准完成成果"
    },
    {
      "dimension": "COMMUNICATION",
      "level": "NOT_OBSERVED",
      "evidenceText": ""
    },
    {
      "dimension": "REWORK",
      "level": "NOT_OBSERVED",
      "evidenceText": ""
    }
  ],
  "comment": "按本次约定执行"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_PROFILE_03](#response-r-profile-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-13"></a>

### 8.13 组长确认的个人 AI 项目参考

**当前权限：** 当前M读取项目参考；仅L发布本人原项目记录经本人确认的文字。

- 业务服务只收项目参考文字，origin固定CLIENT_BYOK_LEADER_CONFIRMED，sourceProjectId必须等于当前项目；clientRecordId绑定原个人记录，不收服务商密钥/完整调用日志。
- 四类scene只是参考用途，不证明AI正确、教师采纳或正式分工生效；发布参考不会自动认领任务、建立责任或改分。本机AI额度与个人草稿无服务器扣费接口。

当前代码定位：[AiScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/AiScreen.java)、[AiController.java](../android-app/app/src/main/java/com/grupi/app/data/AiController.java)、[AiStore.java](../android-app/app/src/main/java/com/grupi/app/data/AiStore.java)、[PreviewAiReferences.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewAiReferences.java)、[AiHttpClient.java](../android-app/app/src/main/java/com/grupi/app/data/AiHttpClient.java)、[AiTemplates.java](../android-app/app/src/main/java/com/grupi/app/data/AiTemplates.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[AI-01](#api-ai-01)|`GET /projects/{projectId}/ai-references`|读取 · 项目 / 项目AI参考|
|[AI-02](#api-ai-02)|`POST /projects/{projectId}/client-ai-references`|提交 · 项目 / 组长确认的个人AI参考|

<a id="api-ai-01"></a>

#### AI-01 — 读取 · 项目 / 项目AI参考

`GET /api/v1/projects/{projectId}/ai-references`

权限：当前M。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/ai-references`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_AI_01](#response-r-ai-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-ai-02"></a>

#### AI-02 — 提交 · 项目 / 组长确认的个人AI参考

`POST /api/v1/projects/{projectId}/client-ai-references`

权限：L，且为本人当前项目的原AI记录。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedProjectRevision`|int|必填；当前项目版本|读取并固定的项目并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceProjectId`|string|必填|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`clientRecordId`|string|必填|本人原项目AI草稿的稳定记录编号；1～80，字母数字下划线短横线；不当作服务器AI证明|
|`scene`|string|必填|AI参考场景枚举；TOPIC_ANALYSIS/TASK_PLAN/DOCUMENT_REVIEW/RECRUITMENT|
|`model`|string|必填|本人所用AI模型展示名，不含密钥；0～120字符，不接受API密钥|
|`title`|string|必填|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`text`|string|必填|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedProjectRevision": 1,
  "sourceProjectId": "project_example_1",
  "clientRecordId": "client_ai_example_1",
  "scene": "TASK_PLAN",
  "model": "test-model",
  "title": "登录页面",
  "text": "将登录与会话分别验收。"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_AI_02](#response-r-ai-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-14"></a>

### 8.14 正式任务骰子会话

**当前权限：** L建/终止会话；邀请名单中的本人回应；当前轮候选本人投骰；唯一拟领取人本人决定。

- 仅普通无主且无提交的任务；2～20当前成员，MAX/MIN/TARGET及名单冻结，TARGET的targetValue 1～6、其他为null，maxRounds固定3。inviteEndsAt默认15分钟、rollEndsAt默认30分钟，前者早于后者；每任务一有效会话。
- 参与是本人ACCEPT/DECLINE，不传userId代签；全员接受才ROLLING，有人拒绝则终止为DECLINED，不静默剔除继续。投骰不接受value；服务器均匀生成1～6，同人同轮唯一，expectedRevision用于来源核对而不能因别人同轮投骰正常增版就误拒本人首次合法投骰。
- 未收齐仅本人点数及他人hasRolled可见，收齐才揭晓。MAX/MIN并列极值者决胜，TARGET留命中者、无人命中保留候选，最多3轮仍不唯一UNRESOLVED。终止保留已投结果和理由。
- 胜者ACCEPT只确认意愿，再走TASK-06原claims；服务器同事务核对会话与任务并标CLAIMED。成员/任务/组长变化或被占用则INVALIDATED；拒绝不顺延次高人。过期由服务端推进，进程动画不是投骰证据。

当前代码定位：[DiceScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/DiceScreen.java)、[DiceRules.java](../android-app/app/src/main/java/com/grupi/app/data/DiceRules.java)、[PreviewDiceSessions.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewDiceSessions.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[DICE-01](#api-dice-01)|`POST /tasks/{taskId}/dice-sessions`|提交 · 任务 / 正式骰子会话|
|[DICE-02](#api-dice-02)|`GET /dice-sessions/{sessionId}`|读取 · 正式骰子会话|
|[DICE-03](#api-dice-03)|`POST /dice-sessions/{sessionId}/participations`|提交 · 正式骰子会话 / 本人参加决定|
|[DICE-04](#api-dice-04)|`POST /dice-sessions/{sessionId}/rolls`|提交 · 正式骰子会话 / 本人本轮投骰|
|[DICE-05](#api-dice-05)|`POST /dice-sessions/{sessionId}/terminations`|提交 · 正式骰子会话 / 终止会话|
|[DICE-06](#api-dice-06)|`POST /dice-sessions/{sessionId}/outcome-decisions`|提交 · 正式骰子会话 / 拟领取人意愿|

<a id="api-dice-01"></a>

#### DICE-01 — 提交 · 任务 / 正式骰子会话

`POST /api/v1/tasks/{taskId}/dice-sessions`

权限：L，当前普通无主且无提交任务。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`taskId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedTaskRevision`|int|正式提交/验收邀请必填，草稿同步有当前任务基准时传入|读取并固定的任务并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`participantIds`|string[]|必填|骰子冻结参与名单；遵守本字段类型、统一上限及对应业务条件|
|`rule`|string|必填|骰子MAX/MIN/TARGET或本资源规则，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`targetValue`|int/null|TARGET必填1～6，MAX/MIN显式null|TARGET目标1～6；非TARGET为null；TARGET为整数1～6，其他规则为null|
|`maxRounds`|int|必填|骰子轮数上限固定3；遵守本字段类型、统一上限及对应业务条件|
|`inviteEndsAt`|string|必填|骰子参与邀请截止；RFC3339时间点，响应UTC Z，服务器比较有效性；可空条件见模型|
|`rollEndsAt`|string|必填|骰子投掷截止；RFC3339时间点，响应UTC Z，服务器比较有效性；可空条件见模型|

请求示例：

```json
{
  "expectedTaskRevision": 1,
  "participantIds": [
    "user_example_1",
    "user_example_2"
  ],
  "rule": "MAX",
  "targetValue": null,
  "maxRounds": 3,
  "inviteEndsAt": "2026-11-02T08:15:00Z",
  "rollEndsAt": "2026-11-02T08:30:00Z"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DICE_01](#response-r-dice-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-dice-02"></a>

#### DICE-02 — 读取 · 正式骰子会话

`GET /api/v1/dice-sessions/{sessionId}`

权限：当前M，按未揭晓/揭晓权限投影。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`sessionId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/dice-sessions/session_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_DICE_01](#response-r-dice-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-dice-03"></a>

#### DICE-03 — 提交 · 正式骰子会话 / 本人参加决定

`POST /api/v1/dice-sessions/{sessionId}/participations`

权限：冻结邀请名单中的本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`sessionId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/DECLINE|

请求示例：

```json
{
  "expectedRevision": 1,
  "decision": "ACCEPT"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DICE_03](#response-r-dice-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-dice-04"></a>

#### DICE-04 — 提交 · 正式骰子会话 / 本人本轮投骰

`POST /api/v1/dice-sessions/{sessionId}/rolls`

权限：已接受且属于当前轮候选的本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`sessionId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`roundNo`|int|必填|骰子当前轮次；正整数；对应本业务独立版本或轮次，不与资源revision混用|

请求示例：

```json
{
  "expectedRevision": 2,
  "roundNo": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DICE_04](#response-r-dice-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-dice-05"></a>

#### DICE-05 — 提交 · 正式骰子会话 / 终止会话

`POST /api/v1/dice-sessions/{sessionId}/terminations`

权限：当前L，原会话可终止。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`sessionId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 2,
  "reason": "参与人暂时无法继续"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DICE_01](#response-r-dice-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-dice-06"></a>

#### DICE-06 — 提交 · 正式骰子会话 / 拟领取人意愿

`POST /api/v1/dice-sessions/{sessionId}/outcome-decisions`

权限：当前唯一拟领取人本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`sessionId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`decision`|string|必填|本人明确提交的决定；允许值：ACCEPT/DECLINE|

请求示例：

```json
{
  "expectedRevision": 5,
  "decision": "ACCEPT"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_DICE_06](#response-r-dice-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-15"></a>

### 8.15 项目留言主题、回复及修订

**当前权限：** 当前M；项目归档后有权历史读者只读；作者改本人正文，L仅整理和关闭。

- 主题tag为QUESTION/SUGGESTION/MATERIAL/CONCLUSION，正文及mentions/refs受第6节限制。列表搜索只查本项目当前有权正文，撤回原文不进入结果。详情replies仅首批Page，续页走回复接口。
- 回复限OPEN主题，replyToId同主题。一条新增回复更新replyCount/活动时间，不改变主题正文revision，避免并发回复互相冲突；expectedTopicRevision仍须核对主题实质状态。
- 作者可修订/撤回自己的主题或回复，无固定撤回时间窗；保留必要修订和占位，但撤回后普通历史不再泄露原文。组长不能改他人正文，教师/助理不能因管理身份读全文。
- RESOLVE仅作者并说明结论，SET_HELPFUL由作者选本主题回复；作者重开RESOLVED，L说明原因可重开CLOSED，CLOSE仅L。置顶有效最多3个，关注只本人唯一记录。所有动作都不是成果验收或计分。

当前代码定位：[DiscussionScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/DiscussionScreen.java)、[DiscussionRules.java](../android-app/app/src/main/java/com/grupi/app/data/DiscussionRules.java)、[PreviewDiscussions.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewDiscussions.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[BOARD-01](#api-board-01)|`GET /projects/{projectId}/board-topics`|读取 · 项目 / 留言主题|
|[BOARD-02](#api-board-02)|`POST /projects/{projectId}/board-topics`|提交 · 项目 / 留言主题|
|[BOARD-03](#api-board-03)|`GET /board-topics/{topicId}`|读取 · 留言主题|
|[BOARD-04](#api-board-04)|`GET /board-topics/{topicId}/replies`|读取 · 留言主题 / 回复|
|[BOARD-05](#api-board-05)|`POST /board-topics/{topicId}/replies`|提交 · 留言主题 / 回复|
|[BOARD-06](#api-board-06)|`POST /board-topics/{contentId}/revisions`|提交 · 留言主题 / 正文修订|
|[BOARD-07](#api-board-07)|`POST /board-topics/{contentId}/withdrawals`|提交 · 留言主题 / 撤回|
|[BOARD-08](#api-board-08)|`GET /board-topics/{contentId}/revisions`|读取 · 留言主题 / 正文修订|
|[BOARD-09](#api-board-09)|`POST /board-replies/{contentId}/revisions`|提交 · 主题回复 / 正文修订|
|[BOARD-10](#api-board-10)|`POST /board-replies/{contentId}/withdrawals`|提交 · 主题回复 / 撤回|
|[BOARD-11](#api-board-11)|`GET /board-replies/{contentId}/revisions`|读取 · 主题回复 / 正文修订|
|[BOARD-12](#api-board-12)|`POST /board-topics/{topicId}/status-actions`|提交 · 留言主题 / 状态动作|
|[BOARD-13](#api-board-13)|`PUT /board-topics/{topicId}/pin`|保存 · 留言主题 / 主题置顶|
|[BOARD-14](#api-board-14)|`PUT /board-topics/{topicId}/me/following`|保存 · 留言主题 / 本人 / 本人关注|

<a id="api-board-01"></a>

#### BOARD-01 — 读取 · 项目 / 留言主题

`GET /api/v1/projects/{projectId}/board-topics`

权限：当前M；项目归档后有权历史读者只读；作者改本人正文，L仅整理和关闭。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`status`|string|可省略；按本项及通用分页规则|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`q`|string|可省略；按本项及通用分页规则|当前授权范围检索文字；教学草稿中另为整数Q；0～80字，当前有权范围检索|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/board-topics`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_01](#response-r-board-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-02"></a>

#### BOARD-02 — 提交 · 项目 / 留言主题

`POST /api/v1/projects/{projectId}/board-topics`

权限：当前M，项目可写。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`title`|string|必填|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`content`|string|必填|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`tag`|string|必填|主题QUESTION/SUGGESTION/MATERIAL/CONCLUSION标签；遵守本字段类型、统一上限及对应业务条件|
|`refs`|object[]|可省略默认[]，最多5个当前有权业务引用|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`refs[].type`|string|父项存在时必填，分支字段例外|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`refs[].id`|string|父项存在时必填，分支字段例外|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs[].version`|int|父项存在时必填，分支字段例外|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`mentions`|string[]|可省略默认[]，最多20个当前正式成员|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|

请求示例：

```json
{
  "title": "接口字段核对",
  "content": "请核对普通任务提交字段。",
  "tag": "QUESTION",
  "refs": [
    {
      "type": "TASK",
      "id": "task_example_1",
      "version": 1
    }
  ],
  "mentions": []
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_02](#response-r-board-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-03"></a>

#### BOARD-03 — 读取 · 留言主题

`GET /api/v1/board-topics/{topicId}`

权限：当前M；项目归档后有权历史读者只读；作者改本人正文，L仅整理和关闭。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`topicId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/board-topics/topic_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_03](#response-r-board-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-04"></a>

#### BOARD-04 — 读取 · 留言主题 / 回复

`GET /api/v1/board-topics/{topicId}/replies`

权限：当前M；项目归档后有权历史读者只读；作者改本人正文，L仅整理和关闭。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`topicId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/board-topics/topic_example_1/replies`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_04](#response-r-board-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-05"></a>

#### BOARD-05 — 提交 · 留言主题 / 回复

`POST /api/v1/board-topics/{topicId}/replies`

权限：当前M且原主题OPEN。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`topicId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedTopicRevision`|int|必填|读取并固定的主题并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`content`|string|必填|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`replyToId`|string/null|可省略或null，有值须同会话/主题可见对象|同主题被回应回复ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`mentions`|string[]|可省略默认[]，最多20个当前正式成员|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|

请求示例：

```json
{
  "expectedTopicRevision": 1,
  "content": "建议先核对请求字段。",
  "replyToId": null,
  "mentions": []
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_05](#response-r-board-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-06"></a>

#### BOARD-06 — 提交 · 留言主题 / 正文修订

`POST /api/v1/board-topics/{contentId}/revisions`

权限：原主题作者本人，项目可写。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`contentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`content`|string|必填|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必填|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`tag`|string|必填|主题QUESTION/SUGGESTION/MATERIAL/CONCLUSION标签；遵守本字段类型、统一上限及对应业务条件|
|`refs`|object[]|可省略默认[]，最多5个当前有权业务引用|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`refs[].type`|string|父项存在时必填，分支字段例外|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`refs[].id`|string|父项存在时必填，分支字段例外|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs[].version`|int|父项存在时必填，分支字段例外|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "content": "已经重新核对请求字段。",
  "title": "接口字段核对（更新）",
  "tag": "QUESTION",
  "refs": [
    {
      "type": "TASK",
      "id": "task_example_1",
      "version": 1
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_02](#response-r-board-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-07"></a>

#### BOARD-07 — 提交 · 留言主题 / 撤回

`POST /api/v1/board-topics/{contentId}/withdrawals`

权限：原主题作者本人，项目可写。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`contentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "本人更正后重新说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_07](#response-r-board-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-08"></a>

#### BOARD-08 — 读取 · 留言主题 / 正文修订

`GET /api/v1/board-topics/{contentId}/revisions`

权限：当前M；项目归档后有权历史读者只读；作者改本人正文，L仅整理和关闭。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`contentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/board-topics/content_example_1/revisions`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_08](#response-r-board-08)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-09"></a>

#### BOARD-09 — 提交 · 主题回复 / 正文修订

`POST /api/v1/board-replies/{contentId}/revisions`

权限：原回复作者本人，项目可写。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`contentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`content`|string|必填|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "content": "已经重新核对请求字段。"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_05](#response-r-board-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-10"></a>

#### BOARD-10 — 提交 · 主题回复 / 撤回

`POST /api/v1/board-replies/{contentId}/withdrawals`

权限：原回复作者本人，项目可写。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`contentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "本人更正后重新说明"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_10](#response-r-board-10)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-11"></a>

#### BOARD-11 — 读取 · 主题回复 / 正文修订

`GET /api/v1/board-replies/{contentId}/revisions`

权限：当前M；项目归档后有权历史读者只读；作者改本人正文，L仅整理和关闭。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`contentId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/board-replies/content_example_1/revisions`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_08](#response-r-board-08)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-12"></a>

#### BOARD-12 — 提交 · 留言主题 / 状态动作

`POST /api/v1/board-topics/{topicId}/status-actions`

权限：主题作者或L，按具体动作区分。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`topicId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`action`|string|必填|本人明确执行的动作；允许值：RESOLVE/REOPEN/CLOSE/SET_HELPFUL|
|`helpfulReplyId`|string/null|必填|作者选择的同主题有帮助回复；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`note`|string|必填|处理说明；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "action": "RESOLVE",
  "helpfulReplyId": "reply_example_1",
  "note": "已按建议核对完成"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_02](#response-r-board-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-13"></a>

#### BOARD-13 — 保存 · 留言主题 / 主题置顶

`PUT /api/v1/board-topics/{topicId}/pin`

权限：当前L，项目可写。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`topicId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`pinned`|bool|必填|组长是否置顶本主题；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "pinned": true
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_02](#response-r-board-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-board-14"></a>

#### BOARD-14 — 保存 · 留言主题 / 本人 / 本人关注

`PUT /api/v1/board-topics/{topicId}/me/following`

权限：当前M，仅本人关注记录。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`topicId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`following`|bool|必填|本人是否关注主题；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "following": false
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_BOARD_14](#response-r-board-14)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-16"></a>

### 8.16 项目文字聊天与连续阅读游标

**当前权限：** 当前M；归档后有权历史读者只读；发送/撤回/阅读位置仅本人。

- 前台每5秒同步，离页、后台或失权停止；历史/增量均按项目sequence补齐，after/before互斥，q及aroundSequence用于当前有权查找定位。不能只取最新页跳过缺口。
- clientMessageId绑定本人项目和正文，发送后由服务器生成id/sequence/作者/时间；content 1～1000或合法refs至少一项，不能二者都空。未知结果先CHAT-03精确查原ID，明确重试沿原ID，不自动补发草稿。
- 本人发送后120秒内可撤回，保留sequence位置并移除可见正文/refs/mentions。afterChangeCursor同步旧消息修订/撤回，7天有效，每批≤100；按id+revision合并，不新增未读或移动阅读位置。首次给高水位后仍补齐历史；游标失效停过期缓存并重载。
- 读取/保存的sequence只代表连续实际展示位置；服务端校验不超过本人合法下发和最新序号，多设备原子取合法max，不回退。不公开逐人已读、在线或输入状态，不把游标称为阅读理解证明。

当前代码定位：[ChatScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/ChatScreen.java)、[ChatNavigationRules.java](../android-app/app/src/main/java/com/grupi/app/data/ChatNavigationRules.java)、[LocalSearchRules.java](../android-app/app/src/main/java/com/grupi/app/data/LocalSearchRules.java)、[PreviewDiscussions.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewDiscussions.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[CHAT-01](#api-chat-01)|`GET /projects/{projectId}/chat-messages`|读取 · 项目 / 聊天消息|
|[CHAT-02](#api-chat-02)|`POST /projects/{projectId}/chat-messages`|提交 · 项目 / 聊天消息|
|[CHAT-03](#api-chat-03)|`GET /projects/{projectId}/chat-message-resolutions`|读取 · 项目 / 原发送编号核对|
|[CHAT-04](#api-chat-04)|`POST /chat-messages/{messageId}/withdrawals`|提交 · 聊天消息 / 撤回|
|[CHAT-05](#api-chat-05)|`GET /projects/{projectId}/me/read-cursor`|读取 · 项目 / 本人 / 本人连续阅读位置|
|[CHAT-06](#api-chat-06)|`PUT /projects/{projectId}/me/read-cursor`|保存 · 项目 / 本人 / 本人连续阅读位置|

<a id="api-chat-01"></a>

#### CHAT-01 — 读取 · 项目 / 聊天消息

`GET /api/v1/projects/{projectId}/chat-messages`

权限：当前M；归档后有权历史读者只读；发送/撤回/阅读位置仅本人。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`afterSequence`|int|可省略；按本项及通用分页规则|从本项目该序号之后拉增量；非负整数安全范围；查询互斥/连续规则见聊天模块|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认50，1～100|
|`beforeSequence`|int|可省略；按本项及通用分页规则|从本项目该序号之前读历史；非负整数安全范围；查询互斥/连续规则见聊天模块|
|`q`|string|可省略；按本项及通用分页规则|当前授权范围检索文字；教学草稿中另为整数Q；0～80字，当前有权范围检索|
|`aroundSequence`|int|可省略；按本项及通用分页规则|定位当前有权上下文的中心序号；非负整数安全范围；查询互斥/连续规则见聊天模块|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`afterChangeCursor`|string|可省略；按本项及通用分页规则|独立聊天变更续接游标；遵守本字段类型、统一上限及对应业务条件|

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/chat-messages`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_CHAT_01](#response-r-chat-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-chat-02"></a>

#### CHAT-02 — 提交 · 项目 / 聊天消息

`POST /api/v1/projects/{projectId}/chat-messages`

权限：当前M，项目可写，仅本人发送。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`clientMessageId`|string|必填|本人项目内稳定发送编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`content`|string|必填|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`replyToId`|string/null|可省略或null，有值须同会话/主题可见对象|同主题被回应回复ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`mentions`|string[]|可省略默认[]，最多20个当前正式成员|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`refs`|object[]|可省略默认[]，最多5个当前有权业务引用|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`refs[].type`|string|父项存在时必填，分支字段例外|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`refs[].id`|string|父项存在时必填，分支字段例外|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs[].version`|int|父项存在时必填，分支字段例外|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "clientMessageId": "deviceA-msg-0001",
  "content": "登录接口已经准备好，请核对。",
  "replyToId": null,
  "mentions": [],
  "refs": [
    {
      "type": "TASK",
      "id": "task_example_1",
      "version": 1
    }
  ]
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_CHAT_02](#response-r-chat-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-chat-03"></a>

#### CHAT-03 — 读取 · 项目 / 原发送编号核对

`GET /api/v1/projects/{projectId}/chat-message-resolutions`

权限：当前M，仅本人原clientMessageId核对。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`clientMessageId`|string|必填|本人项目内稳定发送编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/chat-message-resolutions?clientMessageId=client_message_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_CHAT_03](#response-r-chat-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-chat-04"></a>

#### CHAT-04 — 提交 · 聊天消息 / 撤回

`POST /api/v1/chat-messages/{messageId}/withdrawals`

权限：原发送者本人，当前有权且120秒内。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`messageId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_CHAT_04](#response-r-chat-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-chat-05"></a>

#### CHAT-05 — 读取 · 项目 / 本人 / 本人连续阅读位置

`GET /api/v1/projects/{projectId}/me/read-cursor`

权限：当前有权用户，仅本人项目阅读位置。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/projects/project_example_1/me/read-cursor`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_CHAT_05](#response-r-chat-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-chat-06"></a>

#### CHAT-06 — 保存 · 项目 / 本人 / 本人连续阅读位置

`PUT /api/v1/projects/{projectId}/me/read-cursor`

权限：当前有权用户，仅本人连续合法展示位置。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`projectId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`sequence`|int|必填|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|

请求示例：

```json
{
  "sequence": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_CHAT_05](#response-r-chat-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-17"></a>

### 8.17 课程通知、回执、消息中心与偏好

**当前权限：** 当前课程读者；T管理负责课程，A须ANNOUNCEMENT_WRITE且只能管理仍有权的本人通知；回执动作仅本人。

- 普通全课程通知可直接提交title/content，服务器仅在发布身份唯一时确定身份；双身份须publisherRole。作业范围、知悉、重要程度等增强发布先NOTICE-03，固定原课程revision与所有预览参数10分钟，不发送。
- NOTICE-05核对通知revision。FORMAT_ONLY只接受边界空白/换行规范化，正文实质、期限或范围变化须SUBSTANTIVE并新建contentVersion/接收快照，旧版回执保留。接收快照分母固定，后入课可读有效通知但不补算旧分母。
- P56范围调整复用03→05：03的expectedRevision为课程版本；05为通知版本，title/content沿原值，previewToken承载新assignmentId/refs/知悉期限。只保留或移除已有refs，原作者/分类/重要程度/失效时间不改；新正文版与快照须递增/更换。
- GET不自动读；reads在实际正文加载后仅本人，acknowledgements要求当前正文版本、有效期限及本人在本版接收快照。重复回原时间，清消息提示不会代替知悉或完成原业务。
- 催阅先预览当前仍有权未确认对象再确认发送，每通知每版24小时最多一次；recordedCount只计站内索引，不称设备送达。消息清理用明确ID集合或固定可见上界，不清后来新事件。
- 本人偏好时区IANA、默认22:00～08:00，IN_APP/SYSTEM及ALL/MENTIONS/MUTE不授系统通知权限；免打扰不绕过，重要通知也受设置。SYSTEM目前是前台拉到事件后的本机显示偏好，没有后台必达推送。

当前代码定位：[NotificationScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/NotificationScreen.java)、[NoticeScopeScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/NoticeScopeScreen.java)、[NotificationPreferencesScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/NotificationPreferencesScreen.java)、[PreviewNotices.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewNotices.java)、[NoticeScopeRules.java](../android-app/app/src/main/java/com/grupi/app/data/NoticeScopeRules.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[NOTICE-01](#api-notice-01)|`GET /squares/{squareId}/announcements`|读取 · 课程 / 课程通知|
|[NOTICE-02](#api-notice-02)|`POST /squares/{squareId}/announcements`|提交 · 课程 / 课程通知|
|[NOTICE-03](#api-notice-03)|`POST /squares/{squareId}/announcement-previews`|提交 · 课程 / 通知范围预览|
|[NOTICE-04](#api-notice-04)|`GET /announcements/{announcementId}`|读取 · 课程通知|
|[NOTICE-05](#api-notice-05)|`POST /announcements/{announcementId}/revisions`|提交 · 课程通知 / 正文修订|
|[NOTICE-06](#api-notice-06)|`POST /announcements/{announcementId}/revocations`|提交 · 课程通知 / 撤销|
|[NOTICE-07](#api-notice-07)|`POST /announcements/{announcementId}/reads`|提交 · 课程通知 / 本人正文已读|
|[NOTICE-08](#api-notice-08)|`POST /announcements/{announcementId}/acknowledgements`|提交 · 课程通知 / 本人知悉|
|[NOTICE-09](#api-notice-09)|`GET /announcements/{announcementId}/receipts`|读取 · 课程通知 / 通知版本回执|
|[NOTICE-10](#api-notice-10)|`POST /announcements/{announcementId}/reminder-previews`|提交 · 课程通知 / 催阅预览|
|[NOTICE-11](#api-notice-11)|`POST /announcements/{announcementId}/reminders`|提交 · 课程通知 / 确认催阅|
|[NOTICE-12](#api-notice-12)|`GET /me/notifications`|读取 · 本人 / 消息索引|
|[NOTICE-13](#api-notice-13)|`POST /me/notifications/read-actions`|提交 · 本人 / 消息索引 / 清消息提示|
|[NOTICE-14](#api-notice-14)|`GET /me/notification-preferences`|读取 · 本人 / 本人通知偏好|
|[NOTICE-15](#api-notice-15)|`PUT /me/notification-preferences`|保存 · 本人 / 本人通知偏好|

<a id="api-notice-01"></a>

#### NOTICE-01 — 读取 · 课程 / 课程通知

`GET /api/v1/squares/{squareId}/announcements`

权限：当前课程读者；T管理负责课程，A须ANNOUNCEMENT_WRITE且只能管理仍有权的本人通知；回执动作仅本人。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`publisherRole`|string|可省略；按本项及通用分页规则|本次明确发布身份TEACHER/CLASS_ASSISTANT；有多个合法发布身份时必填；单一身份可省略由服务器核定|
|`unreadOnly`|bool|可省略；按本项及通用分页规则|unreadOnly：所属响应结构中的状态开关；遵守本字段类型、统一上限及对应业务条件|
|`assignmentId`|string/null|可省略；按本项及通用分页规则|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/squares/square_example_1/announcements`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_01](#response-r-notice-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-02"></a>

#### NOTICE-02 — 提交 · 课程 / 课程通知

`POST /api/v1/squares/{squareId}/announcements`

权限：当前T或本课ANNOUNCEMENT_WRITE助理，明确本次发布身份。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`title`|string|必填|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`content`|string|必填|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`publisherRole`|string|有多个合法发布身份时必填；单一身份可省略由服务器核定|本次明确发布身份TEACHER/CLASS_ASSISTANT；有多个合法发布身份时必填；单一身份可省略由服务器核定|
|`previewToken`|string|增强通知发布必填；普通简单公告可省略，见发布规则|固定本人/范围/输入/来源及期限的预览凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|

请求示例：

```json
{
  "title": "课程联调安排",
  "content": "请在周五前完成联调。",
  "publisherRole": "TEACHER",
  "previewToken": "announcement-preview-test"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_02](#response-r-notice-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-03"></a>

#### NOTICE-03 — 提交 · 课程 / 通知范围预览

`POST /api/v1/squares/{squareId}/announcement-previews`

权限：当前T或本课ANNOUNCEMENT_WRITE助理。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`squareId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`publisherRole`|string|有多个合法发布身份时必填；单一身份可省略由服务器核定|本次明确发布身份TEACHER/CLASS_ASSISTANT；有多个合法发布身份时必填；单一身份可省略由服务器核定|
|`assignmentId`|string/null|必填|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`title`|string|必填|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`content`|string|必填|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`category`|string|必填|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`importance`|string|必填|通知NORMAL/IMPORTANT；遵守本字段类型、统一上限及对应业务条件|
|`requiresAcknowledgement`|bool|必填|是否要求本版知悉；遵守本字段类型、统一上限及对应业务条件|
|`acknowledgeDueAt`|string/null|必填|本版知悉截止，要求知悉时非空；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`expiresAt`|string/null|必填|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`refs`|object[]|可省略默认[]，最多5个当前有权业务引用|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`refs[].type`|string|父项存在时必填，分支字段例外|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`refs[].id`|string|父项存在时必填，分支字段例外|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs[].version`|int|父项存在时必填，分支字段例外|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "publisherRole": "TEACHER",
  "assignmentId": null,
  "title": "联调安排",
  "content": "请在周五前完成联调。",
  "category": "COURSE_INFO",
  "importance": "NORMAL",
  "requiresAcknowledgement": false,
  "acknowledgeDueAt": null,
  "expiresAt": null,
  "refs": []
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_03](#response-r-notice-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-04"></a>

#### NOTICE-04 — 读取 · 课程通知

`GET /api/v1/announcements/{announcementId}`

权限：当前课程读者；T管理负责课程，A须ANNOUNCEMENT_WRITE且只能管理仍有权的本人通知；回执动作仅本人。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`announcementId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/announcements/announcement_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_02](#response-r-notice-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-05"></a>

#### NOTICE-05 — 提交 · 课程通知 / 正文修订

`POST /api/v1/announcements/{announcementId}/revisions`

权限：当前T；或仍有公告权且为原作者的A。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`announcementId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`changeKind`|string|必填|SUBSTANTIVE或FORMAT_ONLY；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必填|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`content`|string|必填|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`changeSummary`|string/null|必填|本次实质修订/范围调整说明；遵守本字段类型、统一上限及对应业务条件|
|`previewToken`|string/null|增强通知发布必填；普通简单公告可省略，见发布规则|固定本人/范围/输入/来源及期限的预览凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|

请求示例：

```json
{
  "expectedRevision": 1,
  "changeKind": "SUBSTANTIVE",
  "title": "联调安排更新",
  "content": "联调时间调整为周六。",
  "changeSummary": "调整时间",
  "previewToken": null
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_02](#response-r-notice-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-06"></a>

#### NOTICE-06 — 提交 · 课程通知 / 撤销

`POST /api/v1/announcements/{announcementId}/revocations`

权限：当前T；或仍有公告权且为原作者的A。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`announcementId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 1,
  "reason": "本通知已被新安排取代"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_02](#response-r-notice-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-07"></a>

#### NOTICE-07 — 提交 · 课程通知 / 本人正文已读

`POST /api/v1/announcements/{announcementId}/reads`

权限：当前有权读者本人，该正文版本。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`announcementId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`contentVersion`|int|必填|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|

请求示例：

```json
{
  "contentVersion": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_07](#response-r-notice-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-08"></a>

#### NOTICE-08 — 提交 · 课程通知 / 本人知悉

`POST /api/v1/announcements/{announcementId}/acknowledgements`

权限：当前接收快照中的本人及有效正文版本。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`announcementId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`contentVersion`|int|必填|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|

请求示例：

```json
{
  "contentVersion": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_07](#response-r-notice-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-09"></a>

#### NOTICE-09 — 读取 · 课程通知 / 通知版本回执

`GET /api/v1/announcements/{announcementId}/receipts`

权限：当前T；或仍有公告权且为原作者的A。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`announcementId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`contentVersion`|int|必填|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`filter`|string|可省略；按本项及通用分页规则|filter：所属响应结构中的字符串值；遵守本字段类型、统一上限及对应业务条件|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/announcements/announcement_example_1/receipts?contentVersion=1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_09](#response-r-notice-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-10"></a>

#### NOTICE-10 — 提交 · 课程通知 / 催阅预览

`POST /api/v1/announcements/{announcementId}/reminder-previews`

权限：当前有权通知管理者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`announcementId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`contentVersion`|int|必填|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|

请求示例：

```json
{
  "expectedRevision": 1,
  "contentVersion": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_10](#response-r-notice-10)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-11"></a>

#### NOTICE-11 — 提交 · 课程通知 / 确认催阅

`POST /api/v1/announcements/{announcementId}/reminders`

权限：当前有权通知管理者及原预览。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`announcementId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`previewToken`|string|增强通知发布必填；普通简单公告可省略，见发布规则|固定本人/范围/输入/来源及期限的预览凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|

请求示例：

```json
{
  "previewToken": "reminder-preview-test"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_11](#response-r-notice-11)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-12"></a>

#### NOTICE-12 — 读取 · 本人 / 消息索引

`GET /api/v1/me/notifications`

权限：U，仅本人有权来源消息。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`category`|string/null|可省略；按本项及通用分页规则|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`unreadOnly`|bool|可省略；按本项及通用分页规则|unreadOnly：所属响应结构中的状态开关；遵守本字段类型、统一上限及对应业务条件|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/me/notifications`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_12](#response-r-notice-12)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-13"></a>

#### NOTICE-13 — 提交 · 本人 / 消息索引 / 清消息提示

`POST /api/v1/me/notifications/read-actions`

权限：U，仅本人固定消息批次。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`notificationIds`|string[]|二选一必填|本人明确选中的消息提示ID集合；与expectedVisibleThroughId二选一，明确选1～100条|
|`expectedVisibleThroughId`|string/null|二选一必填|本次可见消息批次上界，不含后来事件；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求示例：

```json
{
  "notificationIds": [
    "notification_example_1"
  ],
  "expectedVisibleThroughId": null
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_13](#response-r-notice-13)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-14"></a>

#### NOTICE-14 — 读取 · 本人 / 本人通知偏好

`GET /api/v1/me/notification-preferences`

权限：U，仅本人偏好。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/me/notification-preferences`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_14](#response-r-notice-14)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-notice-15"></a>

#### NOTICE-15 — 保存 · 本人 / 本人通知偏好

`PUT /api/v1/me/notification-preferences`

权限：U，仅本人偏好。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`timezone`|string|必填|有效IANA时区；遵守本字段类型、统一上限及对应业务条件|
|`quietHours`|object|必填|本人免打扰设置；遵守本字段类型、统一上限及对应业务条件|
|`quietHours.enabled`|bool|父项存在时必填，分支字段例外|此项是否启用；遵守本字段类型、统一上限及对应业务条件|
|`quietHours.start`|string|父项存在时必填，分支字段例外|个人免打扰开始时刻；遵守本字段类型、统一上限及对应业务条件|
|`quietHours.end`|string|父项存在时必填，分支字段例外|个人免打扰结束时刻；遵守本字段类型、统一上限及对应业务条件|
|`courseNotices`|string|必填|课程通知IN_APP/SYSTEM显示偏好；遵守本字段类型、统一上限及对应业务条件|
|`mentions`|string|可省略默认[]，最多20个当前正式成员|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`supportReplies`|string|必填|私人求助回复IN_APP/SYSTEM显示偏好；遵守本字段类型、统一上限及对应业务条件|
|`chatDefault`|string|必填|聊天ALL/MENTIONS/MUTE偏好；遵守本字段类型、统一上限及对应业务条件|
|`projectChatModes`|object[]|必填|本人逐项目聊天偏好覆盖；遵守本字段类型、统一上限及对应业务条件|
|`projectChatModes[].projectId`|string|父项存在时必填，分支字段例外|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectChatModes[].mode`|string|父项存在时必填，分支字段例外|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "timezone": "Asia/Shanghai",
  "quietHours": {
    "enabled": true,
    "start": "22:00",
    "end": "08:00"
  },
  "courseNotices": "IN_APP",
  "mentions": "IN_APP",
  "supportReplies": "IN_APP",
  "chatDefault": "MENTIONS",
  "projectChatModes": [],
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_NOTICE_14](#response-r-notice-14)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-18"></a>

### 8.18 帮助条目与私人求助

**当前权限：** GENERAL可匿名读；课程帮助按当前资格；本人私单或当前真实指派处理者；维护需明确能力。

- 通用离线包只含GENERAL软件帮助，knownVersion相同返回同version及空articles；课程材料和私人记录不进入包。appVersion为三段数字，条目范围支持精确版或MAJOR.MINOR.*。跳转entry只能是白名单页面。
- HELP-05维护GENERAL需软件HELP_CONTENT_WRITE，COURSE由本课当前T负责。保存expectedRevision，仅允许编辑字段，作者/contentVersion/更新时间由服务器维护；下载副本和内置条目不冒充在线可写源。
- HELP-06读取实际路由，available=false时不提供虚假routeId/接收人；成绩、人员和分工事项回原流程，不另开客服裁决通道。HELP-07固定clientRequestId和最少context，本人READY图片≤3，接收人由服务端真实路由决定。
- HELP-08可按clientRequestId精确核对未知结果，不能只看最新列表就认定未提交。HELP-09限本人/当前指派；回复replyCursor/limit分页，history完整≤200，满额拒绝继续追加而不截断。
- HELP-09补充transferTargets：非提问者的当前指派处理者、未关闭且有合法目标才给候选，最多20、routeId唯一；每项routeId/receiverLabel/category/squareId，范围必须与原单相同。角色本身不授SUPPORT_HANDLE，无候选返回[]。
- HELP-10正文和图片一次固定提交，content1～3000、最多3个READY附件；本人补充不自动ANSWERED，处理者回复后另显式状态动作。图片准备/PUT/completions与回复各有原body/key，PUT成功不是READY，不确定回复不自动另发。
- HELP-12原子确定真实负责人；HELP-13 REQUEST_INFORMATION→WAITING_USER，ANSWERED须已有实际答复。HELP-14仅提问者确认解决，关闭7天内可明确false重开，超窗另建单引用旧单。
- HELP-15只能采用读取到的授权targetRouteId及1～500理由，写入时重验当前资格；同一课程范围事务切负责人并回SUBMITTED，返回transferTargetRouteId精确回显所选路由，不改变原问题、附件或本人解决记录。转交失败原人继续负责；旧处理者不再读新材料。
- HELP-16处理者关闭重复/超范围单需真实nextAction和理由，relatedTicketId必须提问者可读；关闭不冒充用户解决。失权/403只能说明无权，不证明未知回复或转交是否成功。

当前代码定位：[HelpScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/HelpScreen.java)、[SupportScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/SupportScreen.java)、[SupportReplyScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/SupportReplyScreen.java)、[SupportTransferScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/SupportTransferScreen.java)、[SupportImageActivity.java](../android-app/app/src/main/java/com/grupi/app/SupportImageActivity.java)、[PreviewSupport.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewSupport.java)、[SupportTransferRules.java](../android-app/app/src/main/java/com/grupi/app/data/SupportTransferRules.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[HELP-01](#api-help-01)|`GET /help/content-pack`|读取 · 帮助 / 通用离线帮助包|
|[HELP-02](#api-help-02)|`GET /help/articles`|读取 · 帮助 / 帮助条目|
|[HELP-03](#api-help-03)|`GET /help/articles/{articleId}`|读取 · 帮助 / 帮助条目|
|[HELP-04](#api-help-04)|`PUT /help/articles/{articleId}/me/feedback`|保存 · 帮助条目 / 本人 / 本人条目反馈|
|[HELP-05](#api-help-05)|`PUT /help/articles/{articleId}`|保存 · 帮助 / 帮助条目|
|[HELP-06](#api-help-06)|`GET /support/routes`|读取 · 私人求助 / 真实接收渠道|
|[HELP-07](#api-help-07)|`POST /me/support-tickets`|提交 · 本人 / 私人求助工单|
|[HELP-08](#api-help-08)|`GET /me/support-tickets`|读取 · 本人 / 私人求助工单|
|[HELP-09](#api-help-09)|`GET /support-tickets/{ticketId}`|读取 · 私人求助工单|
|[HELP-10](#api-help-10)|`POST /support-tickets/{ticketId}/replies`|提交 · 私人求助工单 / 回复|
|[HELP-11](#api-help-11)|`GET /support-workspace/tickets`|读取 · 受理工作台 / 工单列表|
|[HELP-12](#api-help-12)|`POST /support-tickets/{ticketId}/acceptances`|提交 · 私人求助工单 / 接受/验收|
|[HELP-13](#api-help-13)|`POST /support-tickets/{ticketId}/status-actions`|提交 · 私人求助工单 / 状态动作|
|[HELP-14](#api-help-14)|`POST /support-tickets/{ticketId}/resolution-decisions`|提交 · 私人求助工单 / 本人解决/重开|
|[HELP-15](#api-help-15)|`POST /support-tickets/{ticketId}/transfers`|提交 · 私人求助工单 / 授权转交|
|[HELP-16](#api-help-16)|`POST /support-tickets/{ticketId}/closures`|提交 · 私人求助工单 / 结案/关闭|

<a id="api-help-01"></a>

#### HELP-01 — 读取 · 帮助 / 通用离线帮助包

`GET /api/v1/help/content-pack`

权限：匿名可读GENERAL软件帮助，不含私人/课程内容。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`appVersion`|string|可省略；按本项及通用分页规则|不含debug后缀的三段App版本；遵守本字段类型、统一上限及对应业务条件|
|`knownVersion`|int|可省略；按本项及通用分页规则|本结构固定的knownVersion版本，不能与对象revision互换；遵守本字段类型、统一上限及对应业务条件|

请求正文：无。

请求形态：`GET /api/v1/help/content-pack`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_01](#response-r-help-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-02"></a>

#### HELP-02 — 读取 · 帮助 / 帮助条目

`GET /api/v1/help/articles`

权限：GENERAL可匿名读；课程帮助按当前资格；本人私单或当前真实指派处理者；维护需明确能力。。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`q`|string|可省略；按本项及通用分页规则|当前授权范围检索文字；教学草稿中另为整数Q；0～80字，当前有权范围检索|
|`role`|string|可省略；按本项及通用分页规则|本条读取的角色筛选，不授予身份；招募为岗位名；帮助条目为本人实际身份STUDENT/TEACHER/CLASS_ADMIN，不可通过参数越权|
|`squareId`|string/null|可省略；按本项及通用分页规则|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`appVersion`|string|可省略；按本项及通用分页规则|不含debug后缀的三段App版本；遵守本字段类型、统一上限及对应业务条件|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/help/articles`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_02](#response-r-help-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-03"></a>

#### HELP-03 — 读取 · 帮助 / 帮助条目

`GET /api/v1/help/articles/{articleId}`

权限：GENERAL可匿名读；课程帮助按当前资格；本人私单或当前真实指派处理者；维护需明确能力。。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`articleId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/help/articles/article_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_03](#response-r-help-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-04"></a>

#### HELP-04 — 保存 · 帮助条目 / 本人 / 本人条目反馈

`PUT /api/v1/help/articles/{articleId}/me/feedback`

权限：U，仅本人条目版本反馈。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`articleId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`contentVersion`|int|必填|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`helpful`|bool|必填|本人是否认为本版条目有帮助；遵守本字段类型、统一上限及对应业务条件|
|`reasonCode`|string/null|必填|帮助反馈原因枚举或null；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "contentVersion": 1,
  "helpful": false,
  "reasonCode": "STEPS_OUTDATED"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_04](#response-r-help-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-05"></a>

#### HELP-05 — 保存 · 帮助 / 帮助条目

`PUT /api/v1/help/articles/{articleId}`

权限：GENERAL需HELP_CONTENT_WRITE；COURSE限本课当前T。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`articleId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`article`|object|必填|有权编辑的帮助条目内容；遵守本字段类型、统一上限及对应业务条件|
|`article.title`|string|父项存在时必填，分支字段例外|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`article.category`|string|父项存在时必填，分支字段例外|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`article.roles`|string[]|父项存在时必填，分支字段例外|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`article.scope`|string|父项存在时必填，分支字段例外|通知真实课程/作业范围；遵守本字段类型、统一上限及对应业务条件|
|`article.squareId`|string/null|父项存在时必填，分支字段例外|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`article.appVersionRange`|string|父项存在时必填，分支字段例外|帮助条目适用的精确版本或MAJOR.MINOR.*；遵守本字段类型、统一上限及对应业务条件|
|`article.prerequisites`|string[]|父项存在时必填，分支字段例外|当前操作实际前置条件；遵守本字段类型、统一上限及对应业务条件|
|`article.steps`|string[]|父项存在时必填，分支字段例外|帮助操作步骤集合；遵守本字段类型、统一上限及对应业务条件|
|`article.outcomes`|string[]|父项存在时必填，分支字段例外|按条目的本次合法结果；遵守本字段类型、统一上限及对应业务条件|
|`article.failureAction`|string|父项存在时必填，分支字段例外|本次失败后的允许恢复动作；遵守本字段类型、统一上限及对应业务条件|
|`article.entry`|object|父项存在时必填，分支字段例外|允许打开的白名单App页面；遵守本字段类型、统一上限及对应业务条件|
|`article.entry.type`|string|父项存在时必填，分支字段例外|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`article.entry.id`|string/null|父项存在时必填，分支字段例外|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求示例：

```json
{
  "expectedRevision": 1,
  "article": {
    "title": "为什么扫码后还需要入课核验",
    "category": "JOIN",
    "roles": [
      "STUDENT"
    ],
    "scope": "GENERAL",
    "squareId": null,
    "appVersionRange": "0.42.*",
    "prerequisites": [
      "已登录"
    ],
    "steps": [
      "扫描只能定位课程",
      "查看本人入课申请状态"
    ],
    "outcomes": [
      "资格核验通过后可进入课程"
    ],
    "failureAction": "检查申请原因或向真实受理者求助",
    "entry": {
      "type": "COURSE",
      "id": null
    }
  }
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_05](#response-r-help-05)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-06"></a>

#### HELP-06 — 读取 · 私人求助 / 真实接收渠道

`GET /api/v1/support/routes`

权限：U，按本人类别及原课程范围。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`category`|string|可省略；按本项及通用分页规则|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`squareId`|string/null|可省略；按本项及通用分页规则|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求正文：无。

请求形态：`GET /api/v1/support/routes`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_06](#response-r-help-06)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-07"></a>

#### HELP-07 — 提交 · 本人 / 私人求助工单

`POST /api/v1/me/support-tickets`

权限：U本人及当前真实合法路由。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`clientRequestId`|string|必填|本人环境内稳定求助/举报编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`routeId`|string|必填|服务器配置的真实接收渠道ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`category`|string|必填|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必填|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`content`|string|必填|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`squareId`|string/null|必填|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`context`|object|必填|本人明确选择的最少页面/错误/版本信息；遵守本字段类型、统一上限及对应业务条件|
|`context.pageType`|string|父项存在时必填，分支字段例外|白名单页面类型；遵守本字段类型、统一上限及对应业务条件|
|`context.resourceId`|string/null|父项存在时必填，分支字段例外|该入口当前有权资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`context.errorCode`|string|父项存在时必填，分支字段例外|稳定错误类别，不含完整日志；遵守本字段类型、统一上限及对应业务条件|
|`context.appVersion`|string|父项存在时必填，分支字段例外|不含debug后缀的三段App版本；遵守本字段类型、统一上限及对应业务条件|
|`attachmentFileIds`|string[]|可省略默认[]，最多3个本人READY图片|本次本人READY求助图片ID，最多3项且不重复；可省略默认[]，最多3个本人READY图片|

请求示例：

```json
{
  "clientRequestId": "support-client-0001",
  "routeId": "support_route_example_1",
  "category": "OPERATION",
  "title": "申请入口无法加载",
  "content": "点击后显示NETWORK_ERROR。",
  "squareId": null,
  "context": {
    "pageType": "APPLICATION",
    "resourceId": null,
    "errorCode": "NETWORK_ERROR",
    "appVersion": "0.59.0"
  },
  "attachmentFileIds": []
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_07](#response-r-help-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-08"></a>

#### HELP-08 — 读取 · 本人 / 私人求助工单

`GET /api/v1/me/support-tickets`

权限：U，仅本人私人求助。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`clientRequestId`|string/null|可省略；按本项及通用分页规则|本人环境内稳定求助/举报编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string/null|可省略；按本项及通用分页规则|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/me/support-tickets`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_08](#response-r-help-08)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-09"></a>

#### HELP-09 — 读取 · 私人求助工单

`GET /api/v1/support-tickets/{ticketId}`

权限：提问者本人或当前真实指派处理者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`ticketId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`replyCursor`|string/null|可省略；按本项及通用分页规则|本工单回复续页游标；遵守本字段类型、统一上限及对应业务条件|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/support-tickets/ticket_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_09](#response-r-help-09)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-10"></a>

#### HELP-10 — 提交 · 私人求助工单 / 回复

`POST /api/v1/support-tickets/{ticketId}/replies`

权限：提问者本人或当前真实指派处理者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`ticketId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`content`|string|必填|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`attachmentFileIds`|string[]|可省略默认[]，最多3个本人READY图片|本次本人READY求助图片ID，最多3项且不重复；可省略默认[]，最多3个本人READY图片|

请求示例：

```json
{
  "expectedRevision": 1,
  "content": "重新加载后仍失败。",
  "attachmentFileIds": []
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_10](#response-r-help-10)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-11"></a>

#### HELP-11 — 读取 · 受理工作台 / 工单列表

`GET /api/v1/support-workspace/tickets`

权限：SUPPORT_HANDLE且当前真实指派范围。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`status`|string/null|可省略；按本项及通用分页规则|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/support-workspace/tickets`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_08](#response-r-help-08)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-12"></a>

#### HELP-12 — 提交 · 私人求助工单 / 接受/验收

`POST /api/v1/support-tickets/{ticketId}/acceptances`

权限：原路由当前合法接收者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`ticketId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

请求示例：

```json
{
  "expectedRevision": 1
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_07](#response-r-help-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-13"></a>

#### HELP-13 — 提交 · 私人求助工单 / 状态动作

`POST /api/v1/support-tickets/{ticketId}/status-actions`

权限：当前真实指派处理者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`ticketId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`action`|string|必填|本人明确执行的动作；允许值：REQUEST_INFORMATION/ANSWERED|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "expectedRevision": 2,
  "action": "REQUEST_INFORMATION",
  "reason": "请补充发生问题的页面名称"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_07](#response-r-help-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-14"></a>

#### HELP-14 — 提交 · 私人求助工单 / 本人解决/重开

`POST /api/v1/support-tickets/{ticketId}/resolution-decisions`

权限：仅原提问者本人。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`ticketId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`resolved`|bool|必填|resolved：所属响应结构中的状态开关；遵守本字段类型、统一上限及对应业务条件|
|`note`|string|必填|处理说明；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 3,
  "resolved": true,
  "note": "重新配置网络后恢复"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_07](#response-r-help-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-15"></a>

#### HELP-15 — 提交 · 私人求助工单 / 授权转交

`POST /api/v1/support-tickets/{ticketId}/transfers`

权限：非提问者的当前真实指派处理者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`ticketId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`targetRouteId`|string|必填|本人从授权候选选中的目标路由；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|必填|本次决定的事实理由；1～500字，当前处理者明确填写|

请求示例：

```json
{
  "expectedRevision": 2,
  "targetRouteId": "course_support_route_example_1",
  "reason": "属于课程要求问题"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_15](#response-r-help-15)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-help-16"></a>

#### HELP-16 — 提交 · 私人求助工单 / 结案/关闭

`POST /api/v1/support-tickets/{ticketId}/closures`

权限：当前真实指派处理者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`ticketId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`relatedTicketId`|string/null|可null；有值须提问者本人能读原单|提问者有权读取的关联旧工单ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextAction`|string|必填|实际下一步处理方向；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 2,
  "reason": "与原单重复",
  "relatedTicketId": "ticket_example_original",
  "nextAction": "查看原单进度"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_HELP_07](#response-r-help-07)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-19"></a>

### 8.19 限定片段举报与受理事实

**当前权限：** 举报本人只读本人进度；独立CONTENT_REPORT_HANDLE且当前指派者只读必要证据。

- 只针对本人当前有权选中的BOARD_TOPIC/BOARD_REPLY/CHAT_MESSAGE片段，clientRequestId防重，selectedExcerpt/reason各1～1000；真实受理路由存在才接收。
- 查询不向他人泄露举报者/证据，也不授处理者全群读取权。处理动作ACCEPT/RECORD_RESULT/CLOSE分别进入IN_PROGRESS/HANDLED/CLOSED，note非空，记录事实不自动扣分、移人或代删他人正文。

当前代码定位：[SupportScreen.java](../android-app/app/src/main/java/com/grupi/app/ui/SupportScreen.java)、[PreviewSupport.java](../android-app/app/src/main/java/com/grupi/app/data/PreviewSupport.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[REPORTING-01](#api-reporting-01)|`POST /me/content-reports`|提交 · 本人 / 片段举报|
|[REPORTING-02](#api-reporting-02)|`GET /me/content-reports`|读取 · 本人 / 片段举报|
|[REPORTING-03](#api-reporting-03)|`GET /content-reports/{reportId}`|读取 · 片段举报|
|[REPORTING-04](#api-reporting-04)|`POST /content-reports/{reportId}/handling-actions`|提交 · 片段举报 / 记录处理事实|

<a id="api-reporting-01"></a>

#### REPORTING-01 — 提交 · 本人 / 片段举报

`POST /api/v1/me/content-reports`

权限：当前有权选中来源片段的本人。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`clientRequestId`|string|必填|本人环境内稳定求助/举报编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`source`|object|必填|候选/引用来源类别；遵守本字段类型、统一上限及对应业务条件|
|`source.type`|string|父项存在时必填，分支字段例外|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`source.id`|string|父项存在时必填，分支字段例外|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`source.version`|int|父项存在时必填，分支字段例外|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`selectedExcerpt`|string|必填|本人明确选定的受限原文片段；遵守本字段类型、统一上限及对应业务条件|
|`reason`|string|拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|

请求示例：

```json
{
  "clientRequestId": "report-client-0001",
  "source": {
    "type": "CHAT_MESSAGE",
    "id": "message_example_1",
    "version": 1
  },
  "selectedExcerpt": "用户主动选中的必要片段",
  "reason": "包含不适合公开的个人信息"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORTING_01](#response-r-reporting-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-reporting-02"></a>

#### REPORTING-02 — 读取 · 本人 / 片段举报

`GET /api/v1/me/content-reports`

权限：仅举报者本人。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`clientRequestId`|string/null|可省略；按本项及通用分页规则|本人环境内稳定求助/举报编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`cursor`|string/null|可省略；按本项及通用分页规则|绑定本人/范围/筛选的普通续页游标；可省略；不透明绑定本人/筛选/排序，15分钟有效|
|`limit`|int|可省略；按本项及通用分页规则|本次每页条数；默认20，1～100|

请求正文：无。

请求形态：`GET /api/v1/me/content-reports`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORTING_02](#response-r-reporting-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-reporting-03"></a>

#### REPORTING-03 — 读取 · 片段举报

`GET /api/v1/content-reports/{reportId}`

权限：举报者本人或当前CONTENT_REPORT_HANDLE指派处理者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`reportId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/content-reports/report_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORTING_03](#response-r-reporting-03)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-reporting-04"></a>

#### REPORTING-04 — 提交 · 片段举报 / 记录处理事实

`POST /api/v1/content-reports/{reportId}/handling-actions`

权限：当前CONTENT_REPORT_HANDLE指派处理者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`reportId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`action`|string|必填|本人明确执行的动作；允许值：ACCEPT/RECORD_RESULT/CLOSE|
|`note`|string|必填|处理说明；遵守本字段类型、统一上限及对应业务条件|

请求示例：

```json
{
  "expectedRevision": 1,
  "action": "RECORD_RESULT",
  "note": "已联系相关本人处理展示内容"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_REPORTING_04](#response-r-reporting-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="module-20"></a>

### 8.20 云文件意图、内容确认与短期下载

**当前权限：** 上传由本人明确发起；读取依附当前业务权限；未关联文件仅上传者本人。

- FILE-01按第6节purpose选择唯一合法范围，DELIVERY_ARTIFACT需projectId，MATERIAL_PROOF需caseId，SUPPORT_ATTACHMENT可用原ticketId，WORK_ARTIFACT保持本人和源项目许可。无关范围字段不允许；授权10分钟，仅发必要HTTPS PUT信息。
- FILE-02仅原上传者，expectedRevision/uploadToken绑定原意图，实际字节校验后READY/FAILED；不存在“客户端声明完成即通过”。FILE-03不返回密钥/永久公开URL；FILE-04当前有权且READY才给5分钟下载授权，每次重验权限。
- 未完成无引用意图24小时清理，过期后需本人新申请，不能后台偷偷换意图。当前App完整字节传输仅求助图片；其他用途是待实现的服务器契约，不表示客户端已经接入所有业务文件。

当前代码定位：[CloudFileRules.java](../android-app/app/src/main/java/com/grupi/app/data/CloudFileRules.java)、[BinaryFileTransfer.java](../android-app/app/src/main/java/com/grupi/app/data/BinaryFileTransfer.java)、[SupportImageActivity.java](../android-app/app/src/main/java/com/grupi/app/SupportImageActivity.java)、[SupportFilesUi.java](../android-app/app/src/main/java/com/grupi/app/ui/SupportFilesUi.java)。这些链接是本机客户端证据，不是服务器实现证据。

|编号|方法与路径|用途|
|---|---|---|
|[FILE-01](#api-file-01)|`POST /files/upload-intents`|提交 · 云文件 / 上传意图|
|[FILE-02](#api-file-02)|`POST /files/{fileId}/completions`|提交 · 云文件 / 上传完成校验|
|[FILE-03](#api-file-03)|`GET /files/{fileId}`|读取 · 云文件|
|[FILE-04](#api-file-04)|`POST /files/{fileId}/download-authorizations`|提交 · 云文件 / 短期下载授权|

<a id="api-file-01"></a>

#### FILE-01 — 提交 · 云文件 / 上传意图

`POST /api/v1/files/upload-intents`

权限：本人，满足purpose与原业务范围资格。本项继承以上模块规则及通用并发约束。

Path参数：无。

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`purpose`|string|必填|文件的固定业务用途；遵守本字段类型、统一上限及对应业务条件|
|`fileName`|string|必填|文件名，无路径或控制字符；遵守本字段类型、统一上限及对应业务条件|
|`contentType`|string|必填|声明且须与实际签名一致的媒体类型；image/jpeg,image/png,image/webp,application/pdf,text/plain,text/markdown,application/vnd.openxmlformats-officedocument.wordprocessingml.document；用途同时限制|
|`sizeBytes`|int|必填|实际/声明文件字节数；1～8388608整数；服务端实际核对|
|`sha256`|string/null|可省略；有值必须64位小写hex|原文件字节的64位小写SHA256摘要；可省略；有值为64位小写hex。当前求助客户端始终发送真实摘要|
|`projectId`|string/null|文件用途DELIVERY_ARTIFACT必填；WORK_ARTIFACT带来源项目时核对M；其他用途省略|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`caseId`|string/null|文件用途MATERIAL_PROOF必填当前有权事项；其他用途不传|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ticketId`|string/null|SUPPORT_ATTACHMENT可省略，上传前尚无工单时仅本人临时私有；有值须本人或当前处理者有权；其他用途不传|当前结构固定的ticket资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

请求示例：

```json
{
  "purpose": "SUPPORT_ATTACHMENT",
  "fileName": "screenshot-test.png",
  "contentType": "image/png",
  "sizeBytes": 1024,
  "sha256": null
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_FILE_01](#response-r-file-01)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-file-02"></a>

#### FILE-02 — 提交 · 云文件 / 上传完成校验

`POST /api/v1/files/{fileId}/completions`

权限：原上传者本人及原上传意图。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`fileId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expectedRevision`|int|必填|读取并固定的本接口目标并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`uploadToken`|string|必填|与上传意图绑定的完成校验凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|

请求示例：

```json
{
  "expectedRevision": 1,
  "uploadToken": "file-upload-test"
}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_FILE_02](#response-r-file-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP409 / `RESOURCE_CHANGED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-file-03"></a>

#### FILE-03 — 读取 · 云文件

`GET /api/v1/files/{fileId}`

权限：依附业务的当前合法读者；未关联临时文件仅上传者。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`fileId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

请求正文：无。

请求形态：`GET /api/v1/files/file_example_1`。可选参数按上表添加，变更筛选从首批读取。

成功：HTTP200，`code=OK`，`data`结构为 [R_FILE_02](#response-r-file-02)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP403 / `FORBIDDEN`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="api-file-04"></a>

#### FILE-04 — 提交 · 云文件 / 短期下载授权

`POST /api/v1/files/{fileId}/download-authorizations`

权限：当前合法业务读者且文件READY。本项继承以上模块规则及通用并发约束。

Path参数：

|名称|类型/义务|范围|
|---|---|---|
|`fileId`|string，必填|稳定ID 1～120；必须属于原学校/课程/项目及当前有权业务范围|

Query参数：

无。

JSON正文：

无。

请求示例：

```json
{}
```

成功：HTTP200，`code=OK`，`data`结构为 [R_FILE_04](#response-r-file-04)。必须按实际当前权限投影；不可从历史成功重放获得失权数据。

典型拒绝：HTTP422 / `VALIDATION_FAILED`；此外适用第7节的认证、权限、版本、状态、字段与频率错误。

<a id="responses"></a>

## 9. 完整响应结构字典

以下为174种按字段结构复用的响应投影。字段路径相对于信封`data`；`[]`表示元素结构。字段表定义所有非空分支，示例只演示一个虚构场景，不构成连续执行脚本；空子记录/空集合不展开，不表示没有结构。单一例子不枚举全部状态，状态变化以第5节和模块规则为准。

未计算的可选子记录按明确语义为null或{}；该父项为空时不要求子字段。只有本人、当前处理者或当前合法来源的字段才返回，不能给另一角色完整实体再依赖客户端隐藏。表内条件优先于通用“必有”；文件/外链分支、隐私裁剪和正式/普通任务分支必须分别核对。

|结构|首个场景|使用接口数|
|---|---|---|
|[R_AUTH_01](#response-r-auth-01)|注销当前设备会话|1|
|[R_AUTH_02](#response-r-auth-02)|读取 · 本人 / 画像|2|
|[R_AUTH_03](#response-r-auth-03)|读取 · 本人 / 画像可见范围|2|
|[R_AUTH_04](#response-r-auth-04)|提交 · 认证 / 会话|2|
|[R_AUTH_08](#response-r-auth-08)|读取 · 本人|1|
|[R_COURSE_01](#response-r-course-01)|读取 · 作业|3|
|[R_COURSE_02](#response-r-course-02)|读取 · 完整课程字典|1|
|[R_COURSE_03](#response-r-course-03)|读取 · 学校 / 节次字典|1|
|[R_COURSE_04](#response-r-course-04)|读取 · 学校 / 学期字典|1|
|[R_COURSE_05](#response-r-course-05)|读取 · 课程|2|
|[R_COURSE_06](#response-r-course-06)|读取 · 课程|3|
|[R_COURSE_07](#response-r-course-07)|读取 · 课程 / 作业|1|
|[R_COURSE_10](#response-r-course-10)|提交 · 课程 / 课程匹配|1|
|[R_COURSE_13](#response-r-course-13)|提交 · 课程 / 课程加入码|3|
|[R_COURSE_16](#response-r-course-16)|提交 · 课程码核验|1|
|[R_COURSE_17](#response-r-course-17)|提交 · 课程 / 本人入课确认|1|
|[R_COURSE_18](#response-r-course-18)|提交 · 课程 / 入课申请|2|
|[R_COURSE_19](#response-r-course-19)|读取 · 本人 / 入课申请|2|
|[R_COURSE_22](#response-r-course-22)|提交 · 入课申请 / 审核决定|1|
|[R_TEAM_01](#response-r-team-01)|读取 · 作业 / 招募列表|1|
|[R_TEAM_02](#response-r-team-02)|读取 · 邀请|1|
|[R_TEAM_03](#response-r-team-03)|读取 · 本人 / 申请|2|
|[R_TEAM_04](#response-r-team-04)|读取 · 本人 / 邀请|2|
|[R_TEAM_05](#response-r-team-05)|读取 · 本人 / 项目|1|
|[R_TEAM_06](#response-r-team-06)|读取 · 本人 / 作品来源项目|1|
|[R_TEAM_07](#response-r-team-07)|读取 · 项目|5|
|[R_TEAM_10](#response-r-team-10)|读取 · 项目 / 邀请候选|1|
|[R_TEAM_11](#response-r-team-11)|读取 · 项目 / 组队码|2|
|[R_TEAM_12](#response-r-team-12)|读取 · 项目 / 成员|1|
|[R_TEAM_13](#response-r-team-13)|读取 · 项目 / 招募配置|1|
|[R_TEAM_15](#response-r-team-15)|提交 · 申请 / 审核决定|1|
|[R_TEAM_16](#response-r-team-16)|提交 · 申请 / 撤回|2|
|[R_TEAM_17](#response-r-team-17)|提交 · 邀请 / 本人回应|1|
|[R_TEAM_18](#response-r-team-18)|提交 · 邀请 / 撤销|2|
|[R_TEAM_19](#response-r-team-19)|提交 · 组队码定位|1|
|[R_TEAM_20](#response-r-team-20)|提交 · 项目|1|
|[R_TEAM_25](#response-r-team-25)|读取 · 本人 / 行动概况|1|
|[R_TEAM_26](#response-r-team-26)|读取 · 本人 / 待办与等待进展|1|
|[R_ROLE_01](#response-r-role-01)|读取 · 本人 / 多人岗位方案|2|
|[R_ROLE_02](#response-r-role-02)|读取 · 本人 / 岗位提议|2|
|[R_ROLE_03](#response-r-role-03)|读取 · 本人 / 双人岗位互换|2|
|[R_ROLE_06](#response-r-role-06)|读取 · 项目 / 岗位定义|2|
|[R_ROLE_08](#response-r-role-08)|读取 · 多人岗位方案|3|
|[R_ROLE_09](#response-r-role-09)|读取 · 岗位提议|3|
|[R_ROLE_10](#response-r-role-10)|读取 · 双人岗位互换|3|
|[R_ROLE_15](#response-r-role-15)|提交 · 多人岗位方案 / 撤回|1|
|[R_ROLE_17](#response-r-role-17)|提交 · 岗位提议 / 撤回|1|
|[R_ROLE_19](#response-r-role-19)|提交 · 双人岗位互换 / 撤回|1|
|[R_TASK_01](#response-r-task-01)|读取 · 项目 / 任务看板|1|
|[R_TASK_02](#response-r-task-02)|读取 · 项目 / 任务|1|
|[R_TASK_03](#response-r-task-03)|读取 · 任务|5|
|[R_TASK_05](#response-r-task-05)|提交 · 项目 / 任务|1|
|[R_TASK_08](#response-r-task-08)|保存 · 项目 / 本人 / 本人看板颜色|1|
|[R_TASK_09](#response-r-task-09)|保存 · 项目 / 项目阶段|1|
|[R_DELIVERY_01](#response-r-delivery-01)|读取 · 正式约定|3|
|[R_DELIVERY_02](#response-r-delivery-02)|读取 · 内部调整|4|
|[R_DELIVERY_03](#response-r-delivery-03)|读取 · 任务 / 内部调整|1|
|[R_DELIVERY_04](#response-r-delivery-04)|读取 · 任务 / 本人 / 私有草稿|2|
|[R_DELIVERY_05](#response-r-delivery-05)|读取 · 任务 / 提交版本|1|
|[R_DELIVERY_06](#response-r-delivery-06)|提交 · 正式约定 / 本人回应|1|
|[R_DELIVERY_07](#response-r-delivery-07)|提交 · 内部调整 / 意见确认|1|
|[R_DELIVERY_09](#response-r-delivery-09)|提交 · 内部调整 / 本人回应|1|
|[R_DELIVERY_11](#response-r-delivery-11)|提交 · 独立验收邀请 / 本人回应|2|
|[R_DELIVERY_12](#response-r-delivery-12)|提交 · 提交版本 / 接受/验收|2|
|[R_DELIVERY_14](#response-r-delivery-14)|提交 · 提交版本 / 撤回|1|
|[R_DELIVERY_19](#response-r-delivery-19)|提交 · 任务 / 提交版本|1|
|[R_DELIVERY_20](#response-r-delivery-20)|提交 · 正式分项 / 本人 / 本人贡献确认|1|
|[R_GOV_01](#response-r-gov-01)|读取 · 补充资料|5|
|[R_GOV_02](#response-r-gov-02)|读取 · 本人 / 补充资料|1|
|[R_GOV_03](#response-r-gov-03)|读取 · 本人 / 成员关系历史|1|
|[R_GOV_04](#response-r-gov-04)|读取 · 本人 / 人员事项交接|1|
|[R_GOV_05](#response-r-gov-05)|读取 · 退出转组|8|
|[R_GOV_06](#response-r-gov-06)|读取 · 人员事项|3|
|[R_GOV_07](#response-r-gov-07)|读取 · 人员事项 / 接任教师候选|2|
|[R_GOV_09](#response-r-gov-09)|读取 · 人员事项交接|4|
|[R_GOV_10](#response-r-gov-10)|读取 · 项目 / 待转入邀请|3|
|[R_GOV_12](#response-r-gov-12)|读取 · 项目 / 移出表决|1|
|[R_GOV_13](#response-r-gov-13)|读取 · 项目 / 转组目标|1|
|[R_GOV_14](#response-r-gov-14)|读取 · 移出表决|5|
|[R_GOV_16](#response-r-gov-16)|读取 · 课程 / 人员事项|1|
|[R_GOV_22](#response-r-gov-22)|提交 · 退出转组 / 本人接任回应|2|
|[R_GOV_28](#response-r-gov-28)|提交 · 人员事项 / 结案/关闭|1|
|[R_GOV_32](#response-r-gov-32)|提交 · 人员事项 / 追加事实记录|1|
|[R_GOV_41](#response-r-gov-41)|保存 · 移出表决 / 本人 / 本人选票|1|
|[R_MERGE_01](#response-r-merge-01)|读取 · 课程合并提案|5|
|[R_MERGE_02](#response-r-merge-02)|读取 · 课程 / 课程合并提案|1|
|[R_MERGE_06](#response-r-merge-06)|提交 · 课程合并提案 / 教学事实核对|1|
|[R_TEACH_02](#response-r-teach-02)|读取 · 本人 / 本人教学工作台|1|
|[R_TEACH_03](#response-r-teach-03)|读取 · 课程 / 班级助理授权|1|
|[R_TEACH_04](#response-r-teach-04)|读取 · 课程 / 教学进展|1|
|[R_TEACH_05](#response-r-teach-05)|提交 · 班级助理授权 / 撤销|2|
|[R_TEACH_07](#response-r-teach-07)|提交 · 课程 / 教师认领|1|
|[R_TEACH_08](#response-r-teach-08)|提交 · 课程 / 教师离任|1|
|[R_TEACH_09](#response-r-teach-09)|提交 · 课程 / 教师权属转交|2|
|[R_GRADE_01](#response-r-grade-01)|读取 · 作业 / 成绩复核|2|
|[R_GRADE_02](#response-r-grade-02)|读取 · 作业 / 成绩名册|1|
|[R_GRADE_03](#response-r-grade-03)|读取 · 作业 / 当前教学规则|2|
|[R_GRADE_04](#response-r-grade-04)|读取 · 作业 / 学生 / 成绩历史|2|
|[R_GRADE_05](#response-r-grade-05)|读取 · 作业 / 学生 / 教学账本|1|
|[R_GRADE_06](#response-r-grade-06)|读取 · 作业 / 学生 / 教师文字评价|5|
|[R_GRADE_07](#response-r-grade-07)|读取 · 成绩复核|3|
|[R_GRADE_08](#response-r-grade-08)|读取 · 正式成绩快照|1|
|[R_GRADE_11](#response-r-grade-11)|读取 · 本人 / 教师文字评价|1|
|[R_GRADE_13](#response-r-grade-13)|提交 · 作业 / 成绩发布预览|1|
|[R_GRADE_14](#response-r-grade-14)|提交 · 作业 / 成绩正式发布|1|
|[R_GRADE_15](#response-r-grade-15)|提交 · 作业 / 规则预览|1|
|[R_GRADE_17](#response-r-grade-17)|提交 · 作业 / 学生 / 教学采用/排除|1|
|[R_GRADE_18](#response-r-grade-18)|提交 · 作业 / 学生 / 基础分结算|1|
|[R_GRADE_23](#response-r-grade-23)|保存 · 作业 / 学生 / 个人教学评分草稿|1|
|[R_REPORT_01](#response-r-report-01)|读取 · 项目 / 交付草稿|2|
|[R_REPORT_02](#response-r-report-02)|读取 · 项目 / 交付版本|1|
|[R_REPORT_03](#response-r-report-03)|读取 · 项目 / 五栏协作报告|1|
|[R_REPORT_04](#response-r-report-04)|读取 · 项目 / 报告快照|1|
|[R_REPORT_05](#response-r-report-05)|读取 · 报告快照|2|
|[R_REPORT_06](#response-r-report-06)|提交 · 项目 / 交付预览|1|
|[R_REPORT_07](#response-r-report-07)|提交 · 项目 / 交付版本|1|
|[R_REPORT_11](#response-r-report-11)|保存 · 项目 / 本人 / 本人复盘|1|
|[R_REPORT_12](#response-r-report-12)|保存 · 项目 / 整体总结|1|
|[R_PROFILE_01](#response-r-profile-01)|读取 · 本人 / 本人合作记录|1|
|[R_PROFILE_02](#response-r-profile-02)|读取 · 本人 / 作品|1|
|[R_PROFILE_03](#response-r-profile-03)|读取 · 合作互评|3|
|[R_PROFILE_04](#response-r-profile-04)|读取 · 项目 / 互评资格上下文|1|
|[R_PROFILE_05](#response-r-profile-05)|读取 · 用户 / 画像|1|
|[R_PROFILE_06](#response-r-profile-06)|读取 · 作品|3|
|[R_PROFILE_09](#response-r-profile-09)|提交 · 本人 / 作品 / 撤回|1|
|[R_PROFILE_10](#response-r-profile-10)|提交 · 合作互评 / 撤回|1|
|[R_AI_01](#response-r-ai-01)|读取 · 项目 / 项目AI参考|1|
|[R_AI_02](#response-r-ai-02)|提交 · 项目 / 组长确认的个人AI参考|1|
|[R_DICE_01](#response-r-dice-01)|提交 · 任务 / 正式骰子会话|3|
|[R_DICE_03](#response-r-dice-03)|提交 · 正式骰子会话 / 本人参加决定|1|
|[R_DICE_04](#response-r-dice-04)|提交 · 正式骰子会话 / 本人本轮投骰|1|
|[R_DICE_06](#response-r-dice-06)|提交 · 正式骰子会话 / 拟领取人意愿|1|
|[R_BOARD_01](#response-r-board-01)|读取 · 项目 / 留言主题|1|
|[R_BOARD_02](#response-r-board-02)|提交 · 项目 / 留言主题|4|
|[R_BOARD_03](#response-r-board-03)|读取 · 留言主题|1|
|[R_BOARD_04](#response-r-board-04)|读取 · 留言主题 / 回复|1|
|[R_BOARD_05](#response-r-board-05)|提交 · 留言主题 / 回复|2|
|[R_BOARD_07](#response-r-board-07)|提交 · 留言主题 / 撤回|1|
|[R_BOARD_08](#response-r-board-08)|读取 · 留言主题 / 正文修订|2|
|[R_BOARD_10](#response-r-board-10)|提交 · 主题回复 / 撤回|1|
|[R_BOARD_14](#response-r-board-14)|保存 · 留言主题 / 本人 / 本人关注|1|
|[R_CHAT_01](#response-r-chat-01)|读取 · 项目 / 聊天消息|1|
|[R_CHAT_02](#response-r-chat-02)|提交 · 项目 / 聊天消息|1|
|[R_CHAT_03](#response-r-chat-03)|读取 · 项目 / 原发送编号核对|1|
|[R_CHAT_04](#response-r-chat-04)|提交 · 聊天消息 / 撤回|1|
|[R_CHAT_05](#response-r-chat-05)|读取 · 项目 / 本人 / 本人连续阅读位置|2|
|[R_NOTICE_01](#response-r-notice-01)|读取 · 课程 / 课程通知|1|
|[R_NOTICE_02](#response-r-notice-02)|提交 · 课程 / 课程通知|4|
|[R_NOTICE_03](#response-r-notice-03)|提交 · 课程 / 通知范围预览|1|
|[R_NOTICE_07](#response-r-notice-07)|提交 · 课程通知 / 本人正文已读|2|
|[R_NOTICE_09](#response-r-notice-09)|读取 · 课程通知 / 通知版本回执|1|
|[R_NOTICE_10](#response-r-notice-10)|提交 · 课程通知 / 催阅预览|1|
|[R_NOTICE_11](#response-r-notice-11)|提交 · 课程通知 / 确认催阅|1|
|[R_NOTICE_12](#response-r-notice-12)|读取 · 本人 / 消息索引|1|
|[R_NOTICE_13](#response-r-notice-13)|提交 · 本人 / 消息索引 / 清消息提示|1|
|[R_NOTICE_14](#response-r-notice-14)|读取 · 本人 / 本人通知偏好|2|
|[R_HELP_01](#response-r-help-01)|读取 · 帮助 / 通用离线帮助包|1|
|[R_HELP_02](#response-r-help-02)|读取 · 帮助 / 帮助条目|1|
|[R_HELP_03](#response-r-help-03)|读取 · 帮助 / 帮助条目|1|
|[R_HELP_04](#response-r-help-04)|保存 · 帮助条目 / 本人 / 本人条目反馈|1|
|[R_HELP_05](#response-r-help-05)|保存 · 帮助 / 帮助条目|1|
|[R_HELP_06](#response-r-help-06)|读取 · 私人求助 / 真实接收渠道|1|
|[R_HELP_07](#response-r-help-07)|提交 · 本人 / 私人求助工单|5|
|[R_HELP_08](#response-r-help-08)|读取 · 本人 / 私人求助工单|2|
|[R_HELP_09](#response-r-help-09)|读取 · 私人求助工单|1|
|[R_HELP_10](#response-r-help-10)|提交 · 私人求助工单 / 回复|1|
|[R_HELP_15](#response-r-help-15)|提交 · 私人求助工单 / 授权转交|1|
|[R_REPORTING_01](#response-r-reporting-01)|提交 · 本人 / 片段举报|1|
|[R_REPORTING_02](#response-r-reporting-02)|读取 · 本人 / 片段举报|1|
|[R_REPORTING_03](#response-r-reporting-03)|读取 · 片段举报|1|
|[R_REPORTING_04](#response-r-reporting-04)|提交 · 片段举报 / 记录处理事实|1|
|[R_FILE_01](#response-r-file-01)|提交 · 云文件 / 上传意图|1|
|[R_FILE_02](#response-r-file-02)|提交 · 云文件 / 上传完成校验|2|
|[R_FILE_04](#response-r-file-04)|提交 · 云文件 / 短期下载授权|1|

<a id="response-r-auth-01"></a>

### R_AUTH_01 — 注销当前设备会话

适用接口：[AUTH-01](#api-auth-01)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`revoked`|bool|必有|revoked：所属响应结构中的状态开关；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "revoked": true
  }
}
```

<a id="response-r-auth-02"></a>

### R_AUTH_02 — 读取 · 本人 / 画像

适用接口：[AUTH-02](#api-auth-02)、[AUTH-06](#api-auth-06)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`availability`|string|必有|来源是否仍允许本动作的只读原因码；遵守本字段类型、统一上限及对应业务条件|
|`collaborationGoal`|string|必有|协作目标；遵守本字段类型、统一上限及对应业务条件|
|`displayName`|string|必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`intro`|string|必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`skills`|string[]|必有|本人技能标签；遵守本字段类型、统一上限及对应业务条件|
|`userId`|string|必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`weeklyHours`|int|必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "userId": "user_example_1",
    "displayName": "示例学生",
    "skills": [
      "Java"
    ],
    "weeklyHours": 4,
    "availability": "周三晚",
    "collaborationGoal": "按时完成课程设计",
    "intro": "负责客户端开发",
    "revision": 1
  }
}
```

<a id="response-r-auth-03"></a>

### R_AUTH_03 — 读取 · 本人 / 画像可见范围

适用接口：[AUTH-03](#api-auth-03)、[AUTH-07](#api-auth-07)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`cooperationSummaryVisible`|bool|必有|本人是否允许展示满足匿名阈值的合作汇总；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`teacherSummaryVisible`|bool|必有|本人是否允许展示已明确授权的教师文字评价；遵守本字段类型、统一上限及对应业务条件|
|`userId`|string|必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`worksAudience`|string|必有|作品可见范围；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "userId": "user_example_1",
    "worksAudience": "PRIVATE",
    "teacherSummaryVisible": false,
    "cooperationSummaryVisible": false,
    "revision": 1
  }
}
```

<a id="response-r-auth-04"></a>

### R_AUTH_04 — 提交 · 认证 / 会话

适用接口：[AUTH-04](#api-auth-04)、[AUTH-05](#api-auth-05)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`accessToken`|string|必有|本设备访问令牌；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`expiresIn`|int|必有|accessToken剩余有效秒数；遵守本字段类型、统一上限及对应业务条件|
|`refreshExpiresAt`|string|必有|固定会话到期时间；遵守本字段类型、统一上限及对应业务条件|
|`refreshToken`|string|必有|本设备固定到期刷新令牌；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`tokenType`|string|必有|固定Bearer；遵守本字段类型、统一上限及对应业务条件|
|`user`|object|必有|当前核验用户信息；遵守本字段类型、统一上限及对应业务条件|
|`user.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`user.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`user.identityStatus`|string|父记录存在时必有|服务端核验身份状态；遵守本字段类型、统一上限及对应业务条件|
|`user.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`user.roles`|string[]|父记录存在时必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`user.schoolId`|string|父记录存在时必有|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`user.skills`|string[]|父记录存在时必有|本人技能标签；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "accessToken": "test-access-token",
    "refreshToken": "test-refresh-token",
    "tokenType": "Bearer",
    "expiresIn": 1800,
    "refreshExpiresAt": "2026-11-09T08:00:00Z",
    "user": {
      "id": "user_example_1",
      "displayName": "示例学生",
      "schoolId": "school_example_1",
      "identityStatus": "VERIFIED",
      "roles": [
        "STUDENT"
      ],
      "skills": [
        "Java"
      ],
      "revision": 1
    }
  }
}
```

<a id="response-r-auth-08"></a>

### R_AUTH_08 — 读取 · 本人

适用接口：[AUTH-08](#api-auth-08)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`displayName`|string|必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`identityStatus`|string|必有|服务端核验身份状态；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`roles`|string[]|必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`schoolId`|string|必有|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`skills`|string[]|必有|本人技能标签；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "user_example_1",
    "displayName": "示例学生",
    "schoolId": "school_example_1",
    "identityStatus": "VERIFIED",
    "roles": [
      "STUDENT"
    ],
    "skills": [
      "Java"
    ],
    "revision": 1
  }
}
```

<a id="response-r-course-01"></a>

### R_COURSE_01 — 读取 · 作业

适用接口：[COURSE-01](#api-course-01)、[COURSE-08](#api-course-08)、[COURSE-11](#api-course-11)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`confirmationStatus`|string|必有|作业教师确认状态；遵守本字段类型、统一上限及对应业务条件|
|`gradingEnabled`|bool|必有|本作业是否启用正式教学评价；遵守本字段类型、统一上限及对应业务条件|
|`hardDueAt`|string|必有|原作业硬截止；RFC3339时间点，响应UTC Z，服务器比较有效性；可空条件见模型|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`maxMembers`|int|必有|作业最多成员数；遵守本字段类型、统一上限及对应业务条件|
|`minMembers`|int|必有|作业最少成员数；遵守本字段类型、统一上限及对应业务条件|
|`name`|string|必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`origin`|string|必有|固定CLIENT_BYOK_LEADER_CONFIRMED来源声明；遵守本字段类型、统一上限及对应业务条件|
|`requirements`|string|必有|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceReferences`|object[]|必有|报告固定来源集合；可省略默认[]，最多10个|
|`sourceReferences[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`sourceReferences[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`sourceReferences[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "assignment_example_1",
    "squareId": "square_example_1",
    "name": "课程设计",
    "requirements": "完成可演示的课程协作应用",
    "origin": "TEACHER",
    "confirmationStatus": "CONFIRMED",
    "hardDueAt": "2026-11-09T15:59:59Z",
    "minMembers": 2,
    "maxMembers": 6,
    "gradingEnabled": false,
    "revision": 1,
    "sourceReferences": []
  }
}
```

<a id="response-r-course-02"></a>

### R_COURSE_02 — 读取 · 完整课程字典

适用接口：[COURSE-02](#api-course-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].aliases`|string[]|父记录存在时必有|检索别名，不创建独立同名课程；遵守本字段类型、统一上限及对应业务条件|
|`items[].fullName`|string|父记录存在时必有|规范全称；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "course_name_example_1",
        "fullName": "移动应用开发实践",
        "aliases": [
          "移动应用开发"
        ]
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-course-03"></a>

### R_COURSE_03 — 读取 · 学校 / 节次字典

适用接口：[COURSE-03](#api-course-03)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].endTime`|string|父记录存在时必有|学校节次结束时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].label`|string|父记录存在时必有|本字段的展示标签；遵守本字段类型、统一上限及对应业务条件|
|`items[].schoolId`|string|父记录存在时必有|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].startTime`|string|父记录存在时必有|学校节次开始时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].termId`|string|父记录存在时必有|所属学期ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "period_example_1",
        "schoolId": "school_example_1",
        "termId": "term_example_1",
        "label": "第3—4节",
        "startTime": "10:00:00",
        "endTime": "11:40:00"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-course-04"></a>

### R_COURSE_04 — 读取 · 学校 / 学期字典

适用接口：[COURSE-04](#api-course-04)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].endDate`|string|父记录存在时必有|学期结束纯日期；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].schoolId`|string|父记录存在时必有|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].startDate`|string|父记录存在时必有|学期开始纯日期；遵守本字段类型、统一上限及对应业务条件|
|`items[].timezone`|string|父记录存在时必有|有效IANA时区；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "term_example_1",
        "schoolId": "school_example_1",
        "name": "2026秋季",
        "startDate": "2026-09-01",
        "endDate": "2027-01-31",
        "timezone": "Asia/Shanghai"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-course-05"></a>

### R_COURSE_05 — 读取 · 课程

适用接口：[COURSE-05](#api-course-05)、[TEACH-01](#api-teach-01)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].capabilities`|string[]|父记录存在时必有|本范围当前有效能力集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].fullCourseName`|string|父记录存在时必有|完整课程名；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].myMembershipStatus`|string|父记录存在时必有|本人当前课程/项目关系状态；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].schoolId`|string|父记录存在时必有|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].slots`|object[]|父记录存在时必有|完整上课时段集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].slots[].customWeeks`|int[]|父记录存在时必有|CUSTOM模式明确选择的周次；遵守本字段类型、统一上限及对应业务条件|
|`items[].slots[].endWeek`|int|父记录存在时必有|结束周次；遵守本字段类型、统一上限及对应业务条件|
|`items[].slots[].periodId`|string|父记录存在时必有|所属学校学期的稳定节次ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].slots[].startWeek`|int|父记录存在时必有|起始周次；遵守本字段类型、统一上限及对应业务条件|
|`items[].slots[].weekPattern`|string|父记录存在时必有|ALL/ODD/EVEN/CUSTOM周次模式；遵守本字段类型、统一上限及对应业务条件|
|`items[].slots[].weekday`|int|父记录存在时必有|星期1～7；遵守本字段类型、统一上限及对应业务条件|
|`items[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].squareNo`|string|父记录存在时必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].teacherName`|string|父记录存在时必有|任课教师姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].termId`|string|父记录存在时必有|所属学期ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "square_example_1",
        "squareId": "square_example_1",
        "squareNo": "TEST-2026-001",
        "schoolId": "school_example_1",
        "termId": "term_example_1",
        "fullCourseName": "移动应用开发实践",
        "teacherName": "示例教师",
        "slots": [
          {
            "weekday": 3,
            "periodId": "period_example_1",
            "weekPattern": "ALL",
            "startWeek": 1,
            "endWeek": 16,
            "customWeeks": []
          }
        ],
        "status": "ACTIVE",
        "revision": 1,
        "capabilities": [
          "COURSE_READ"
        ],
        "myMembershipStatus": "ACTIVE"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-course-06"></a>

### R_COURSE_06 — 读取 · 课程

适用接口：[COURSE-06](#api-course-06)、[COURSE-09](#api-course-09)、[COURSE-12](#api-course-12)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`capabilities`|string[]|必有|本范围当前有效能力集合；遵守本字段类型、统一上限及对应业务条件|
|`fullCourseName`|string|必有|完整课程名；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myMembershipStatus`|string|必有|本人当前课程/项目关系状态；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`schoolId`|string|必有|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`slots`|object[]|必有|完整上课时段集合；遵守本字段类型、统一上限及对应业务条件|
|`slots[].customWeeks`|int[]|父记录存在时必有|CUSTOM模式明确选择的周次；遵守本字段类型、统一上限及对应业务条件|
|`slots[].endWeek`|int|父记录存在时必有|结束周次；遵守本字段类型、统一上限及对应业务条件|
|`slots[].periodId`|string|父记录存在时必有|所属学校学期的稳定节次ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`slots[].startWeek`|int|父记录存在时必有|起始周次；遵守本字段类型、统一上限及对应业务条件|
|`slots[].weekPattern`|string|父记录存在时必有|ALL/ODD/EVEN/CUSTOM周次模式；遵守本字段类型、统一上限及对应业务条件|
|`slots[].weekday`|int|父记录存在时必有|星期1～7；遵守本字段类型、统一上限及对应业务条件|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`squareNo`|string|必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`teacherName`|string|必有|任课教师姓名；遵守本字段类型、统一上限及对应业务条件|
|`termId`|string|必有|所属学期ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "square_example_1",
    "squareId": "square_example_1",
    "squareNo": "TEST-2026-001",
    "schoolId": "school_example_1",
    "termId": "term_example_1",
    "fullCourseName": "移动应用开发实践",
    "teacherName": "示例教师",
    "slots": [
      {
        "weekday": 3,
        "periodId": "period_example_1",
        "weekPattern": "ALL",
        "startWeek": 1,
        "endWeek": 16,
        "customWeeks": []
      }
    ],
    "status": "ACTIVE",
    "revision": 1,
    "capabilities": [
      "COURSE_READ"
    ],
    "myMembershipStatus": "ACTIVE"
  }
}
```

<a id="response-r-course-07"></a>

### R_COURSE_07 — 读取 · 课程 / 作业

适用接口：[COURSE-07](#api-course-07)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].confirmationStatus`|string|父记录存在时必有|作业教师确认状态；遵守本字段类型、统一上限及对应业务条件|
|`items[].gradingEnabled`|bool|父记录存在时必有|本作业是否启用正式教学评价；遵守本字段类型、统一上限及对应业务条件|
|`items[].hardDueAt`|string|父记录存在时必有|原作业硬截止；RFC3339时间点，响应UTC Z，服务器比较有效性；可空条件见模型|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].maxMembers`|int|父记录存在时必有|作业最多成员数；遵守本字段类型、统一上限及对应业务条件|
|`items[].minMembers`|int|父记录存在时必有|作业最少成员数；遵守本字段类型、统一上限及对应业务条件|
|`items[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].origin`|string|父记录存在时必有|固定CLIENT_BYOK_LEADER_CONFIRMED来源声明；遵守本字段类型、统一上限及对应业务条件|
|`items[].requirements`|string|父记录存在时必有|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].sourceReferences`|object[]|父记录存在时必有|报告固定来源集合；可省略默认[]，最多10个|
|`items[].sourceReferences[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceReferences[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceReferences[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "assignment_example_1",
        "squareId": "square_example_1",
        "name": "课程设计",
        "requirements": "完成可演示的课程协作应用",
        "origin": "TEACHER",
        "confirmationStatus": "CONFIRMED",
        "hardDueAt": "2026-11-09T15:59:59Z",
        "minMembers": 2,
        "maxMembers": 6,
        "gradingEnabled": false,
        "revision": 1,
        "sourceReferences": []
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-course-10"></a>

### R_COURSE_10 — 提交 · 课程 / 课程匹配

适用接口：[COURSE-10](#api-course-10)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`canCreate`|bool|必有|当前调用者是否可创建；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`candidateCheckToken`|string|必有|当前调用者是否可didateCheckToken；只读提示，最终写入重验权限；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`candidates`|object[]|必有|当前调用者是否可didates；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].differences`|string[]|父记录存在时必有|与规范定位输入的差异；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].matchKind`|string|父记录存在时必有|matchKind：所属响应结构中的字符串值；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square`|object|父记录存在时必有|本次课程广场投影；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.capabilities`|string[]|父记录存在时必有|本范围当前有效能力集合；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.fullCourseName`|string|父记录存在时必有|完整课程名；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`candidates[].square.myMembershipStatus`|string|父记录存在时必有|本人当前课程/项目关系状态；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`candidates[].square.schoolId`|string|父记录存在时必有|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`candidates[].square.slots`|object[]|父记录存在时必有|完整上课时段集合；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.slots[].customWeeks`|int[]|父记录存在时必有|CUSTOM模式明确选择的周次；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.slots[].endWeek`|int|父记录存在时必有|结束周次；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.slots[].periodId`|string|父记录存在时必有|所属学校学期的稳定节次ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`candidates[].square.slots[].startWeek`|int|父记录存在时必有|起始周次；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.slots[].weekPattern`|string|父记录存在时必有|ALL/ODD/EVEN/CUSTOM周次模式；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.slots[].weekday`|int|父记录存在时必有|星期1～7；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`candidates[].square.squareNo`|string|父记录存在时必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.teacherName`|string|父记录存在时必有|任课教师姓名；遵守本字段类型、统一上限及对应业务条件|
|`candidates[].square.termId`|string|父记录存在时必有|所属学期ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`exactMatch`|object/null|必有；允许null|本次范围内的精确课程匹配；没有时null；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.capabilities`|string[]|exactMatch非空时按本课程权限投影返回|本范围当前有效能力集合；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.fullCourseName`|string|exactMatch非空时按本课程权限投影返回|完整课程名；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.id`|string|exactMatch非空时按本课程权限投影返回|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`exactMatch.myMembershipStatus`|string|exactMatch非空时按本课程权限投影返回|本人当前课程/项目关系状态；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.revision`|int|exactMatch非空时按本课程权限投影返回|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`exactMatch.schoolId`|string|exactMatch非空时按本课程权限投影返回|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`exactMatch.slots`|object[]|exactMatch非空时按本课程权限投影返回|完整上课时段集合；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.slots[].customWeeks`|int[]|exactMatch非空时按本课程权限投影返回|CUSTOM模式明确选择的周次；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.slots[].endWeek`|int|exactMatch非空时按本课程权限投影返回|结束周次；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.slots[].periodId`|string|exactMatch非空时按本课程权限投影返回|所属学校学期的稳定节次ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`exactMatch.slots[].startWeek`|int|exactMatch非空时按本课程权限投影返回|起始周次；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.slots[].weekPattern`|string|exactMatch非空时按本课程权限投影返回|ALL/ODD/EVEN/CUSTOM周次模式；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.slots[].weekday`|int|exactMatch非空时按本课程权限投影返回|星期1～7；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.squareId`|string|exactMatch非空时按本课程权限投影返回|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`exactMatch.squareNo`|string|exactMatch非空时按本课程权限投影返回|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.status`|string|exactMatch非空时按本课程权限投影返回|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.teacherName`|string|exactMatch非空时按本课程权限投影返回|任课教师姓名；遵守本字段类型、统一上限及对应业务条件|
|`exactMatch.termId`|string|exactMatch非空时按本课程权限投影返回|所属学期ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`normalizedInput`|object|必有|服务器规范化的原定位输入；遵守本字段类型、统一上限及对应业务条件|
|`normalizedInput.courseNameId`|string|父记录存在时必有|规范完整课程字典ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`normalizedInput.schoolId`|string|父记录存在时必有|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`normalizedInput.slots`|object[]|父记录存在时必有|完整上课时段集合；遵守本字段类型、统一上限及对应业务条件|
|`normalizedInput.slots[].customWeeks`|int[]|父记录存在时必有|CUSTOM模式明确选择的周次；遵守本字段类型、统一上限及对应业务条件|
|`normalizedInput.slots[].endWeek`|int|父记录存在时必有|结束周次；遵守本字段类型、统一上限及对应业务条件|
|`normalizedInput.slots[].periodId`|string|父记录存在时必有|所属学校学期的稳定节次ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`normalizedInput.slots[].startWeek`|int|父记录存在时必有|起始周次；遵守本字段类型、统一上限及对应业务条件|
|`normalizedInput.slots[].weekPattern`|string|父记录存在时必有|ALL/ODD/EVEN/CUSTOM周次模式；遵守本字段类型、统一上限及对应业务条件|
|`normalizedInput.slots[].weekday`|int|父记录存在时必有|星期1～7；遵守本字段类型、统一上限及对应业务条件|
|`normalizedInput.teacherName`|string|父记录存在时必有|任课教师姓名；遵守本字段类型、统一上限及对应业务条件|
|`normalizedInput.termId`|string|父记录存在时必有|所属学期ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "exactMatch": null,
    "candidates": [],
    "normalizedInput": {
      "schoolId": "school_example_1",
      "termId": "term_example_1",
      "courseNameId": "course_name_example_1",
      "teacherName": "示例教师",
      "slots": [
        {
          "weekday": 3,
          "periodId": "period_example_1",
          "weekPattern": "ALL",
          "startWeek": 1,
          "endWeek": 16,
          "customWeeks": []
        }
      ]
    },
    "canCreate": true,
    "candidateCheckToken": "match-token-test",
    "expiresAt": "2026-11-02T08:05:00Z"
  }
}
```

<a id="response-r-course-13"></a>

### R_COURSE_13 — 提交 · 课程 / 课程加入码

适用接口：[COURSE-13](#api-course-13)、[COURSE-14](#api-course-14)、[COURSE-15](#api-course-15)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`code`|string/null|必有；允许null|服务端有效码或响应业务码，按父结构区分；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`issuedBy`|string|必有|签发该码的有权用户ID；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "course_code_example_1",
    "squareId": "square_example_1",
    "code": "opaque-test-code-0001",
    "issuedBy": "user_example_teacher",
    "expiresAt": "2026-11-03T08:00:00Z",
    "status": "ACTIVE",
    "revision": 1
  }
}
```

<a id="response-r-course-16"></a>

### R_COURSE_16 — 提交 · 课程码核验

适用接口：[COURSE-16](#api-course-16)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`canConfirm`|bool|必有|当前调用者是否可本人确认；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`confirmationToken`|string/null|必有；允许null|可信课程事实下绑定本人的确认凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`existingRequestId`|string/null|必有；允许null|本人已存在的待处理入课申请；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`membershipStatus`|string|必有|当前课程成员关系状态；遵守本字段类型、统一上限及对应业务条件|
|`square`|object|必有|本次课程广场投影；遵守本字段类型、统一上限及对应业务条件|
|`square.capabilities`|string[]|父记录存在时必有|本范围当前有效能力集合；遵守本字段类型、统一上限及对应业务条件|
|`square.fullCourseName`|string|父记录存在时必有|完整课程名；遵守本字段类型、统一上限及对应业务条件|
|`square.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`square.myMembershipStatus`|string|父记录存在时必有|本人当前课程/项目关系状态；遵守本字段类型、统一上限及对应业务条件|
|`square.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`square.schoolId`|string|父记录存在时必有|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`square.slots`|object[]|父记录存在时必有|完整上课时段集合；遵守本字段类型、统一上限及对应业务条件|
|`square.slots[].customWeeks`|int[]|父记录存在时必有|CUSTOM模式明确选择的周次；遵守本字段类型、统一上限及对应业务条件|
|`square.slots[].endWeek`|int|父记录存在时必有|结束周次；遵守本字段类型、统一上限及对应业务条件|
|`square.slots[].periodId`|string|父记录存在时必有|所属学校学期的稳定节次ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`square.slots[].startWeek`|int|父记录存在时必有|起始周次；遵守本字段类型、统一上限及对应业务条件|
|`square.slots[].weekPattern`|string|父记录存在时必有|ALL/ODD/EVEN/CUSTOM周次模式；遵守本字段类型、统一上限及对应业务条件|
|`square.slots[].weekday`|int|父记录存在时必有|星期1～7；遵守本字段类型、统一上限及对应业务条件|
|`square.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`square.squareNo`|string|父记录存在时必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`square.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`square.teacherName`|string|父记录存在时必有|任课教师姓名；遵守本字段类型、统一上限及对应业务条件|
|`square.termId`|string|父记录存在时必有|所属学期ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`verificationStatus`|string|必有|可信选课核验结果；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "square": {
      "id": "square_example_1",
      "squareNo": "TEST-2026-001",
      "schoolId": "school_example_1",
      "termId": "term_example_1",
      "fullCourseName": "移动应用开发实践",
      "teacherName": "示例教师",
      "slots": [
        {
          "weekday": 3,
          "periodId": "period_example_1",
          "weekPattern": "ALL",
          "startWeek": 1,
          "endWeek": 16
        }
      ],
      "revision": 1
    },
    "membershipStatus": "NONE",
    "verificationStatus": "REQUIRES_REVIEW",
    "canConfirm": false,
    "existingRequestId": null,
    "confirmationToken": null
  }
}
```

<a id="response-r-course-17"></a>

### R_COURSE_17 — 提交 · 课程 / 本人入课确认

适用接口：[COURSE-17](#api-course-17)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`membershipStatus`|string|必有|当前课程成员关系状态；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`userId`|string|必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "squareId": "square_example_1",
    "userId": "user_example_1",
    "membershipStatus": "ACTIVE",
    "revision": 1
  }
}
```

<a id="response-r-course-18"></a>

### R_COURSE_18 — 提交 · 课程 / 入课申请

适用接口：[COURSE-18](#api-course-18)、[COURSE-21](#api-course-21)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message`|string|必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`proofRefs`|object[]|必有|本人入课核验资料引用，最多3项；可省略默认[]，最多3个|
|`proofRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`proofRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`proofRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`proofRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`reason`|string/null|必有；允许null|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`requesterId`|string|必有|申请/提问者本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "join_request_example_1",
    "squareId": "square_example_1",
    "requesterId": "user_example_1",
    "status": "PENDING",
    "message": "请核对本人的选课关系。",
    "proofRefs": [],
    "reason": null,
    "revision": 1,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-course-19"></a>

### R_COURSE_19 — 读取 · 本人 / 入课申请

适用接口：[COURSE-19](#api-course-19)、[COURSE-20](#api-course-20)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].proofRefs`|object[]|父记录存在时必有|本人入课核验资料引用，最多3项；可省略默认[]，最多3个|
|`items[].proofRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`items[].proofRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`items[].proofRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].proofRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`items[].reason`|string/null|必有；允许null|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].requesterId`|string|父记录存在时必有|申请/提问者本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "join_request_example_1",
        "squareId": "square_example_1",
        "requesterId": "user_example_1",
        "status": "PENDING",
        "message": "请核对本人的选课关系。",
        "proofRefs": [],
        "reason": null,
        "revision": 1,
        "createdAt": "2026-11-02T08:00:00Z"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-course-22"></a>

### R_COURSE_22 — 提交 · 入课申请 / 审核决定

适用接口：[COURSE-22](#api-course-22)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`membership`|object|必有|本次实际关系资源，没有建立时null；遵守本字段类型、统一上限及对应业务条件|
|`membership.color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`membership.colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`membership.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`membership.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`membership.joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`membership.membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`membership.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`membership.projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`membership.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`membership.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`membership.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`membership.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`membership.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`request`|object|必有|本次课程核验申请；遵守本字段类型、统一上限及对应业务条件|
|`request.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`request.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`request.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`request.proofRefs`|object[]|父记录存在时必有|本人入课核验资料引用，最多3项；可省略默认[]，最多3个|
|`request.proofRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`request.proofRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`request.proofRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`request.proofRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`request.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`request.requesterId`|string|父记录存在时必有|申请/提问者本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`request.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`request.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`request.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "request": {
      "id": "join_request_example_1",
      "squareId": "square_example_1",
      "requesterId": "user_example_1",
      "status": "APPROVED",
      "message": "请核对本人的选课关系。",
      "proofRefs": [],
      "reason": "已核对可信选课记录",
      "revision": 2,
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "membership": {
      "squareId": "square_example_1",
      "userId": "user_example_1",
      "status": "ACTIVE",
      "revision": 1
    }
  }
}
```

<a id="response-r-team-01"></a>

### R_TEAM_01 — 读取 · 作业 / 招募列表

适用接口：[TEAM-01](#api-team-01)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].availableSeats`|int|父记录存在时必有|重验后的可用席位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].canApply`|bool|父记录存在时必有|当前调用者是否可申请/应用；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].capacity`|int|父记录存在时必有|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|
|`items[].courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].discussionTime`|string|父记录存在时必有|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`items[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].eligibleForDiscovery`|bool|父记录存在时必有|综合资格及筛选是否进入随机候选池；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ineligibleReason`|string/null|必有；允许null|本次不可参与的实际原因；遵守本字段类型、统一上限及对应业务条件|
|`items[].intro`|string|父记录存在时必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`items[].leaderName`|string|父记录存在时必有|当前组长展示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].memberCount`|int|父记录存在时必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`items[].mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`items[].myApplicationStatus`|string/null|必有；允许null|本人申请状态；遵守本字段类型、统一上限及对应业务条件|
|`items[].myInvitationId`|string/null|必有；允许null|本人的当前待回应邀请ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].recruitment`|object|父记录存在时必有|当前招募配置；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.acceptBeginners`|bool|父记录存在时必有|是否接受初学者；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.open`|bool|父记录存在时必有|是否开放招募；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.professionalDirection`|string|父记录存在时必有|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.weeklyHours`|int|父记录存在时必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`items[].reservedCount`|int|父记录存在时必有|有效邀请/转组预留数；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].roles`|object[]|父记录存在时必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].roles[].reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`items[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].squareNo`|string|父记录存在时必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`items[].stages`|object[]|父记录存在时必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`items[].stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`snapshotKey`|string|必有|固定内容快照键；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "project_example_1",
        "projectId": "project_example_1",
        "squareId": "square_example_1",
        "squareNo": "TEST-2026-001",
        "courseName": "移动应用开发实践",
        "assignmentId": "assignment_example_1",
        "assignmentName": "课程设计",
        "mode": "DETAILED",
        "name": "课程协作应用",
        "displayName": "课程协作应用",
        "intro": "完成课程设计和联调",
        "capacity": 4,
        "ownerId": "user_example_1",
        "leaderName": "示例学生",
        "status": "ACTIVE",
        "dueAt": "2026-11-09T15:59:59Z",
        "discussionTime": "周三晚",
        "recruitment": {
          "open": true,
          "description": "招募服务器和示例队员",
          "professionalDirection": "软件开发",
          "weeklyHours": 4,
          "acceptBeginners": true
        },
        "roles": [
          {
            "id": "role_example_1",
            "roleName": "客户端开发",
            "duty": "完成App和联调",
            "total": 2,
            "filled": 1,
            "reserved": 0
          },
          {
            "id": "role_example_2",
            "roleName": "服务器开发",
            "duty": "完成API和持久化",
            "total": 2,
            "filled": 0,
            "reserved": 0
          }
        ],
        "stages": [
          {
            "id": "stage_example_1",
            "name": "开发"
          }
        ],
        "revision": 1,
        "memberCount": 1,
        "reservedCount": 0,
        "canApply": true,
        "availableSeats": 3,
        "eligibleForDiscovery": true,
        "ineligibleReason": null,
        "myApplicationStatus": null,
        "myInvitationId": null
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1,
    "snapshotKey": "discovery-test-1"
  }
}
```

<a id="response-r-team-02"></a>

### R_TEAM_02 — 读取 · 邀请

适用接口：[TEAM-02](#api-team-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`canAccept`|bool|必有|当前调用者是否可接受；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canRevoke`|bool|必有|当前调用者是否可撤销；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`invitation`|object|必有|invitation：所属响应结构中的结构化记录；遵守本字段类型、统一上限及对应业务条件|
|`invitation.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`invitation.expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`invitation.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitation.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`invitation.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitation.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`invitation.recipientId`|string|父记录存在时必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitation.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`invitation.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitation.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`project`|object|必有|有权项目投影；遵守本字段类型、统一上限及对应业务条件|
|`project.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`project.availableSeats`|int|父记录存在时必有|重验后的可用席位数；遵守本字段类型、统一上限及对应业务条件|
|`project.canApply`|bool|父记录存在时必有|当前调用者是否可申请/应用；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`project.capacity`|int|父记录存在时必有|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|
|`project.closingCycle`|int|父记录存在时必有|项目本次收尾轮次；遵守本字段类型、统一上限及对应业务条件|
|`project.courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`project.discussionTime`|string|父记录存在时必有|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`project.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`project.dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`project.eligibleForDiscovery`|bool|父记录存在时必有|综合资格及筛选是否进入随机候选池；遵守本字段类型、统一上限及对应业务条件|
|`project.historical`|bool|父记录存在时必有|是否为有权历史投影；遵守本字段类型、统一上限及对应业务条件|
|`project.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.ineligibleReason`|string/null|必有；允许null|本次不可参与的实际原因；遵守本字段类型、统一上限及对应业务条件|
|`project.intro`|string|父记录存在时必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`project.leaderName`|string|父记录存在时必有|当前组长展示名；遵守本字段类型、统一上限及对应业务条件|
|`project.memberCount`|int|父记录存在时必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`project.mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`project.myApplicationStatus`|string/null|必有；允许null|本人申请状态；遵守本字段类型、统一上限及对应业务条件|
|`project.myInvitationId`|string/null|必有；允许null|本人的当前待回应邀请ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.myMembership`|object|父记录存在时必有|本人当前成员关系；没有时按模型为空对象；遵守本字段类型、统一上限及对应业务条件|
|`project.myMembership.color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`project.myMembership.colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`project.myMembership.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`project.myMembership.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.myMembership.joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`project.myMembership.membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.myMembership.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.myMembership.projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`project.myMembership.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`project.myMembership.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.myMembership.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`project.myMembership.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.myProjectRole`|string|父记录存在时必有|本人项目内身份；遵守本字段类型、统一上限及对应业务条件|
|`project.name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`project.ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.recruitment`|object|父记录存在时必有|当前招募配置；遵守本字段类型、统一上限及对应业务条件|
|`project.recruitment.acceptBeginners`|bool|父记录存在时必有|是否接受初学者；遵守本字段类型、统一上限及对应业务条件|
|`project.recruitment.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`project.recruitment.open`|bool|父记录存在时必有|是否开放招募；遵守本字段类型、统一上限及对应业务条件|
|`project.recruitment.professionalDirection`|string|父记录存在时必有|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`project.recruitment.weeklyHours`|int|父记录存在时必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`project.reservedCount`|int|父记录存在时必有|有效邀请/转组预留数；遵守本字段类型、统一上限及对应业务条件|
|`project.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`project.roles`|object[]|父记录存在时必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`project.roles[].duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`project.roles[].filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`project.roles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.roles[].reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`project.roles[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`project.roles[].total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`project.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.squareNo`|string|父记录存在时必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`project.stages`|object[]|父记录存在时必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`project.stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`project.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reasonCode`|string/null|必有；允许null|帮助反馈原因枚举或null；遵守本字段类型、统一上限及对应业务条件|
|`recipientName`|string|必有|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`role`|object|必有|作品中本人承担的角色描述；遵守本字段类型、统一上限及对应业务条件|
|`role.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`role.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`role.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`roleName`|string|必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "invitation": {
      "id": "invitation_example_1",
      "projectId": "project_example_1",
      "recipientId": "user_example_2",
      "roleSlotId": "role_example_2",
      "message": "邀请参与项目",
      "status": "PENDING",
      "expiresAt": "2026-11-03T08:00:00Z",
      "reason": "",
      "revision": 1,
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "project": {
      "id": "project_example_1",
      "projectId": "project_example_1",
      "squareId": "square_example_1",
      "squareNo": "TEST-2026-001",
      "courseName": "移动应用开发实践",
      "assignmentId": "assignment_example_1",
      "assignmentName": "课程设计",
      "mode": "DETAILED",
      "name": "课程协作应用",
      "displayName": "课程协作应用",
      "intro": "完成课程设计和联调",
      "capacity": 4,
      "ownerId": "user_example_1",
      "leaderName": "示例学生",
      "status": "ACTIVE",
      "dueAt": "2026-11-09T15:59:59Z",
      "discussionTime": "周三晚",
      "recruitment": {
        "open": true,
        "description": "招募服务器和示例队员",
        "professionalDirection": "软件开发",
        "weeklyHours": 4,
        "acceptBeginners": true
      },
      "roles": [
        {
          "id": "role_example_1",
          "roleName": "客户端开发",
          "duty": "完成App和联调",
          "total": 2,
          "filled": 1,
          "reserved": 0
        },
        {
          "id": "role_example_2",
          "roleName": "服务器开发",
          "duty": "完成API和持久化",
          "total": 2,
          "filled": 0,
          "reserved": 0
        }
      ],
      "stages": [
        {
          "id": "stage_example_1",
          "name": "开发"
        }
      ],
      "revision": 1,
      "memberCount": 1,
      "reservedCount": 0,
      "canApply": true,
      "availableSeats": 3,
      "eligibleForDiscovery": true,
      "ineligibleReason": null,
      "myApplicationStatus": null,
      "myInvitationId": null
    },
    "role": {
      "id": "role_example_2",
      "roleName": "服务器开发",
      "duty": "实现API"
    },
    "recipientName": "示例队员",
    "roleName": "服务器开发",
    "projectId": "project_example_1",
    "canAccept": true,
    "canRevoke": true,
    "reasonCode": null
  }
}
```

<a id="response-r-team-03"></a>

### R_TEAM_03 — 读取 · 本人 / 申请

适用接口：[TEAM-03](#api-team-03)、[TEAM-08](#api-team-08)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].applicantProfile`|object|父记录存在时必有|applicantProfile：所属响应结构中的结构化记录；遵守本字段类型、统一上限及对应业务条件|
|`items[].applicantProfile.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].applicantProfile.skills`|string[]|父记录存在时必有|本人技能标签；遵守本字段类型、统一上限及对应业务条件|
|`items[].applicantProfile.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].application`|object|父记录存在时必有|application：所属响应结构中的结构化记录；遵守本字段类型、统一上限及对应业务条件|
|`items[].application.applicantId`|string|父记录存在时必有|当前结构固定的applicant资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].application.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].application.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].application.invitationId`|string/null|必有；允许null|邀请记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].application.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].application.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].application.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].application.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].application.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].application.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].invitation`|string/null|必有；允许null|invitation：所属响应结构中的业务数据；遵守本字段类型、统一上限及对应业务条件|
|`items[].nextAction`|string|父记录存在时必有|实际下一步处理方向；遵守本字段类型、统一上限及对应业务条件|
|`items[].project`|object|父记录存在时必有|有权项目投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.availableSeats`|int|父记录存在时必有|重验后的可用席位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.canApply`|bool|父记录存在时必有|当前调用者是否可申请/应用；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.capacity`|int|父记录存在时必有|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.closingCycle`|int|父记录存在时必有|项目本次收尾轮次；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.discussionTime`|string|父记录存在时必有|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].project.eligibleForDiscovery`|bool|父记录存在时必有|综合资格及筛选是否进入随机候选池；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.historical`|bool|父记录存在时必有|是否为有权历史投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.ineligibleReason`|string/null|必有；允许null|本次不可参与的实际原因；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.intro`|string|父记录存在时必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.leaderName`|string|父记录存在时必有|当前组长展示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.memberCount`|int|父记录存在时必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myApplicationStatus`|string/null|必有；允许null|本人申请状态；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myInvitationId`|string/null|必有；允许null|本人的当前待回应邀请ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.myMembership`|object|父记录存在时必有|本人当前成员关系；没有时按模型为空对象；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myMembership.color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`items[].project.myMembership.colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].project.myMembership.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myMembership.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.myMembership.joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myMembership.membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.myMembership.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.myMembership.projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myMembership.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].project.myMembership.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.myMembership.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myMembership.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.myProjectRole`|string|父记录存在时必有|本人项目内身份；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.recruitment`|object|父记录存在时必有|当前招募配置；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.recruitment.acceptBeginners`|bool|父记录存在时必有|是否接受初学者；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.recruitment.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.recruitment.open`|bool|父记录存在时必有|是否开放招募；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.recruitment.professionalDirection`|string|父记录存在时必有|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.recruitment.weeklyHours`|int|父记录存在时必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.reservedCount`|int|父记录存在时必有|有效邀请/转组预留数；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].project.roles`|object[]|父记录存在时必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.roles[].duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.roles[].filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.roles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.roles[].reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.roles[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.roles[].total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.squareNo`|string|父记录存在时必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.stages`|object[]|父记录存在时必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "application": {
          "id": "application_example_1",
          "projectId": "project_example_1",
          "applicantId": "user_example_2",
          "roleSlotId": "role_example_2",
          "message": "希望参与服务器开发",
          "status": "PENDING",
          "invitationId": null,
          "reason": "",
          "revision": 1,
          "createdAt": "2026-11-02T08:00:00Z"
        },
        "project": {
          "id": "project_example_1",
          "projectId": "project_example_1",
          "squareId": "square_example_1",
          "squareNo": "TEST-2026-001",
          "courseName": "移动应用开发实践",
          "assignmentId": "assignment_example_1",
          "assignmentName": "课程设计",
          "mode": "DETAILED",
          "name": "课程协作应用",
          "displayName": "课程协作应用",
          "intro": "完成课程设计和联调",
          "capacity": 4,
          "ownerId": "user_example_1",
          "leaderName": "示例学生",
          "status": "ACTIVE",
          "dueAt": "2026-11-09T15:59:59Z",
          "discussionTime": "周三晚",
          "recruitment": {
            "open": true,
            "description": "招募服务器和示例队员",
            "professionalDirection": "软件开发",
            "weeklyHours": 4,
            "acceptBeginners": true
          },
          "roles": [
            {
              "id": "role_example_1",
              "roleName": "客户端开发",
              "duty": "完成App和联调",
              "total": 2,
              "filled": 1,
              "reserved": 0
            },
            {
              "id": "role_example_2",
              "roleName": "服务器开发",
              "duty": "完成API和持久化",
              "total": 2,
              "filled": 0,
              "reserved": 0
            }
          ],
          "stages": [
            {
              "id": "stage_example_1",
              "name": "开发"
            }
          ],
          "revision": 1,
          "memberCount": 1,
          "reservedCount": 0,
          "canApply": true,
          "availableSeats": 3,
          "eligibleForDiscovery": true,
          "ineligibleReason": null,
          "myApplicationStatus": null,
          "myInvitationId": null
        },
        "applicantProfile": {
          "userId": "user_example_2",
          "displayName": "示例队员",
          "skills": [
            "Java"
          ]
        },
        "invitation": null,
        "nextAction": "WAIT_LEADER"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-team-04"></a>

### R_TEAM_04 — 读取 · 本人 / 邀请

适用接口：[TEAM-04](#api-team-04)、[TEAM-09](#api-team-09)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].canAccept`|bool|父记录存在时必有|当前调用者是否可接受；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canRevoke`|bool|父记录存在时必有|当前调用者是否可撤销；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].invitation`|object|父记录存在时必有|invitation：所属响应结构中的结构化记录；遵守本字段类型、统一上限及对应业务条件|
|`items[].invitation.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].invitation.expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].invitation.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].invitation.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].invitation.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].invitation.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].invitation.recipientId`|string|父记录存在时必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].invitation.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].invitation.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].invitation.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].project`|object|父记录存在时必有|有权项目投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.availableSeats`|int|父记录存在时必有|重验后的可用席位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.canApply`|bool|父记录存在时必有|当前调用者是否可申请/应用；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.capacity`|int|父记录存在时必有|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.closingCycle`|int|父记录存在时必有|项目本次收尾轮次；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.discussionTime`|string|父记录存在时必有|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].project.eligibleForDiscovery`|bool|父记录存在时必有|综合资格及筛选是否进入随机候选池；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.historical`|bool|父记录存在时必有|是否为有权历史投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.ineligibleReason`|string/null|必有；允许null|本次不可参与的实际原因；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.intro`|string|父记录存在时必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.leaderName`|string|父记录存在时必有|当前组长展示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.memberCount`|int|父记录存在时必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myApplicationStatus`|string/null|必有；允许null|本人申请状态；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myInvitationId`|string/null|必有；允许null|本人的当前待回应邀请ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.myMembership`|object|父记录存在时必有|本人当前成员关系；没有时按模型为空对象；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myMembership.color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`items[].project.myMembership.colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].project.myMembership.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myMembership.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.myMembership.joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myMembership.membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.myMembership.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.myMembership.projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myMembership.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].project.myMembership.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.myMembership.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.myMembership.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.myProjectRole`|string|父记录存在时必有|本人项目内身份；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.recruitment`|object|父记录存在时必有|当前招募配置；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.recruitment.acceptBeginners`|bool|父记录存在时必有|是否接受初学者；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.recruitment.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.recruitment.open`|bool|父记录存在时必有|是否开放招募；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.recruitment.professionalDirection`|string|父记录存在时必有|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.recruitment.weeklyHours`|int|父记录存在时必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.reservedCount`|int|父记录存在时必有|有效邀请/转组预留数；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].project.roles`|object[]|父记录存在时必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.roles[].duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.roles[].filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.roles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.roles[].reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.roles[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.roles[].total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.squareNo`|string|父记录存在时必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.stages`|object[]|父记录存在时必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].project.stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].project.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].reasonCode`|string/null|必有；允许null|帮助反馈原因枚举或null；遵守本字段类型、统一上限及对应业务条件|
|`items[].recipientName`|string|父记录存在时必有|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].role`|object|父记录存在时必有|作品中本人承担的角色描述；遵守本字段类型、统一上限及对应业务条件|
|`items[].role.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`items[].role.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].role.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "invitation": {
          "id": "invitation_example_1",
          "projectId": "project_example_1",
          "recipientId": "user_example_2",
          "roleSlotId": "role_example_2",
          "message": "邀请参与项目",
          "status": "PENDING",
          "expiresAt": "2026-11-03T08:00:00Z",
          "reason": "",
          "revision": 1,
          "createdAt": "2026-11-02T08:00:00Z"
        },
        "project": {
          "id": "project_example_1",
          "projectId": "project_example_1",
          "squareId": "square_example_1",
          "squareNo": "TEST-2026-001",
          "courseName": "移动应用开发实践",
          "assignmentId": "assignment_example_1",
          "assignmentName": "课程设计",
          "mode": "DETAILED",
          "name": "课程协作应用",
          "displayName": "课程协作应用",
          "intro": "完成课程设计和联调",
          "capacity": 4,
          "ownerId": "user_example_1",
          "leaderName": "示例学生",
          "status": "ACTIVE",
          "dueAt": "2026-11-09T15:59:59Z",
          "discussionTime": "周三晚",
          "recruitment": {
            "open": true,
            "description": "招募服务器和示例队员",
            "professionalDirection": "软件开发",
            "weeklyHours": 4,
            "acceptBeginners": true
          },
          "roles": [
            {
              "id": "role_example_1",
              "roleName": "客户端开发",
              "duty": "完成App和联调",
              "total": 2,
              "filled": 1,
              "reserved": 0
            },
            {
              "id": "role_example_2",
              "roleName": "服务器开发",
              "duty": "完成API和持久化",
              "total": 2,
              "filled": 0,
              "reserved": 0
            }
          ],
          "stages": [
            {
              "id": "stage_example_1",
              "name": "开发"
            }
          ],
          "revision": 1,
          "memberCount": 1,
          "reservedCount": 0,
          "canApply": true,
          "availableSeats": 3,
          "eligibleForDiscovery": true,
          "ineligibleReason": null,
          "myApplicationStatus": null,
          "myInvitationId": null
        },
        "role": {
          "id": "role_example_2",
          "roleName": "服务器开发",
          "duty": "实现API"
        },
        "recipientName": "示例队员",
        "roleName": "服务器开发",
        "projectId": "project_example_1",
        "canAccept": true,
        "canRevoke": true,
        "reasonCode": null
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-team-05"></a>

### R_TEAM_05 — 读取 · 本人 / 项目

适用接口：[TEAM-05](#api-team-05)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].capacity`|int|父记录存在时必有|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|
|`items[].closingCycle`|int|父记录存在时必有|项目本次收尾轮次；遵守本字段类型、统一上限及对应业务条件|
|`items[].courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].discussionTime`|string|父记录存在时必有|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`items[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].historical`|bool|父记录存在时必有|是否为有权历史投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].intro`|string|父记录存在时必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`items[].leaderName`|string|父记录存在时必有|当前组长展示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].memberCount`|int|父记录存在时必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`items[].mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`items[].myMembership`|object|父记录存在时必有|本人当前成员关系；没有时按模型为空对象；遵守本字段类型、统一上限及对应业务条件|
|`items[].myMembership.color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`items[].myMembership.colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].myMembership.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].myMembership.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].myMembership.joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].myMembership.membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].myMembership.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].myMembership.projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`items[].myMembership.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].myMembership.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].myMembership.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].myMembership.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].myProjectRole`|string|父记录存在时必有|本人项目内身份；遵守本字段类型、统一上限及对应业务条件|
|`items[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].recruitment`|object|父记录存在时必有|当前招募配置；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.acceptBeginners`|bool|父记录存在时必有|是否接受初学者；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.open`|bool|父记录存在时必有|是否开放招募；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.professionalDirection`|string|父记录存在时必有|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.weeklyHours`|int|父记录存在时必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`items[].reservedCount`|int|父记录存在时必有|有效邀请/转组预留数；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].roles`|object[]|父记录存在时必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].roles[].reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`items[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].squareNo`|string|父记录存在时必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`items[].stages`|object[]|父记录存在时必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`items[].stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "project_example_1",
        "projectId": "project_example_1",
        "squareId": "square_example_1",
        "squareNo": "TEST-2026-001",
        "courseName": "移动应用开发实践",
        "assignmentId": "assignment_example_1",
        "assignmentName": "课程设计",
        "mode": "DETAILED",
        "name": "课程协作应用",
        "displayName": "课程协作应用",
        "intro": "完成课程设计和联调",
        "capacity": 4,
        "ownerId": "user_example_1",
        "leaderName": "示例学生",
        "status": "ACTIVE",
        "dueAt": "2026-11-09T15:59:59Z",
        "discussionTime": "周三晚",
        "recruitment": {
          "open": true,
          "description": "招募服务器和示例队员",
          "professionalDirection": "软件开发",
          "weeklyHours": 4,
          "acceptBeginners": true
        },
        "roles": [
          {
            "id": "role_example_1",
            "roleName": "客户端开发",
            "duty": "完成App和联调",
            "total": 2,
            "filled": 1,
            "reserved": 0
          },
          {
            "id": "role_example_2",
            "roleName": "服务器开发",
            "duty": "完成API和持久化",
            "total": 2,
            "filled": 0,
            "reserved": 0
          }
        ],
        "stages": [
          {
            "id": "stage_example_1",
            "name": "开发"
          }
        ],
        "revision": 1,
        "memberCount": 1,
        "reservedCount": 0,
        "myProjectRole": "LEADER",
        "myMembership": {
          "id": "membership_example_1",
          "membershipId": "membership_example_1",
          "projectId": "project_example_1",
          "userId": "user_example_1",
          "displayName": "示例学生",
          "roleSlotId": "role_example_1",
          "projectRole": "LEADER",
          "status": "ACTIVE",
          "color": "#236B56",
          "colorRevision": 0,
          "joinedAt": "2026-11-02T08:00:00Z",
          "revision": 1
        },
        "historical": false,
        "closingCycle": 0
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-team-06"></a>

### R_TEAM_06 — 读取 · 本人 / 作品来源项目

适用接口：[TEAM-06](#api-team-06)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].myEvidence`|object|父记录存在时必有|本人受限材料投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].myEvidence.acceptedDeliveries`|int|父记录存在时必有|本人实际有效交付次数；遵守本字段类型、统一上限及对应业务条件|
|`items[].myEvidence.confirmedUnits`|int|父记录存在时必有|本人已确认贡献项数；遵守本字段类型、统一上限及对应业务条件|
|`items[].myEvidence.independentReviews`|int|父记录存在时必有|本人独立验收记录数；遵守本字段类型、统一上限及对应业务条件|
|`items[].myEvidence.lateDeliveries`|int|父记录存在时必有|本人迟交的有效交付数；遵守本字段类型、统一上限及对应业务条件|
|`items[].myEvidence.onTimeDeliveries`|int|父记录存在时必有|本人按有效截止完成的交付数；遵守本字段类型、统一上限及对应业务条件|
|`items[].myEvidence.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "project_example_1",
        "displayName": "课程协作应用",
        "myEvidence": {
          "projectId": "project_example_1",
          "acceptedDeliveries": 1,
          "confirmedUnits": 0,
          "independentReviews": 0,
          "onTimeDeliveries": 1,
          "lateDeliveries": 0
        }
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-team-07"></a>

### R_TEAM_07 — 读取 · 项目

适用接口：[TEAM-07](#api-team-07)、[TEAM-14](#api-team-14)、[TEAM-24](#api-team-24)、[ROLE-20](#api-role-20)、[REPORT-08](#api-report-08)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`assignmentName`|string|必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`capacity`|int|必有|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|
|`closingCycle`|int|必有|项目本次收尾轮次；遵守本字段类型、统一上限及对应业务条件|
|`courseName`|string|必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`discussionTime`|string|必有|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`displayName`|string|必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`dueAt`|string|必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`historical`|bool|必有|是否为有权历史投影；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`intro`|string|必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`leaderName`|string|必有|当前组长展示名；遵守本字段类型、统一上限及对应业务条件|
|`memberCount`|int|必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`mode`|string|必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`myMembership`|object|必有|本人当前成员关系；没有时按模型为空对象；遵守本字段类型、统一上限及对应业务条件|
|`myMembership.color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`myMembership.colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`myMembership.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`myMembership.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myMembership.joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`myMembership.membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myMembership.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myMembership.projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`myMembership.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`myMembership.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myMembership.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`myMembership.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myProjectRole`|string|必有|本人项目内身份；遵守本字段类型、统一上限及对应业务条件|
|`name`|string|必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recruitment`|object|必有|当前招募配置；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.acceptBeginners`|bool|父记录存在时必有|是否接受初学者；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.open`|bool|父记录存在时必有|是否开放招募；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.professionalDirection`|string|父记录存在时必有|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.weeklyHours`|int|父记录存在时必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`reservedCount`|int|必有|有效邀请/转组预留数；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`roles`|object[]|必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`roles[].duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`roles[].filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`roles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`roles[].reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`roles[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`roles[].total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`squareNo`|string|必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`stages`|object[]|必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "project_example_1",
    "projectId": "project_example_1",
    "squareId": "square_example_1",
    "squareNo": "TEST-2026-001",
    "courseName": "移动应用开发实践",
    "assignmentId": "assignment_example_1",
    "assignmentName": "课程设计",
    "mode": "DETAILED",
    "name": "课程协作应用",
    "displayName": "课程协作应用",
    "intro": "完成课程设计和联调",
    "capacity": 4,
    "ownerId": "user_example_1",
    "leaderName": "示例学生",
    "status": "ACTIVE",
    "dueAt": "2026-11-09T15:59:59Z",
    "discussionTime": "周三晚",
    "recruitment": {
      "open": true,
      "description": "招募服务器和示例队员",
      "professionalDirection": "软件开发",
      "weeklyHours": 4,
      "acceptBeginners": true
    },
    "roles": [
      {
        "id": "role_example_1",
        "roleName": "客户端开发",
        "duty": "完成App和联调",
        "total": 2,
        "filled": 1,
        "reserved": 0
      },
      {
        "id": "role_example_2",
        "roleName": "服务器开发",
        "duty": "完成API和持久化",
        "total": 2,
        "filled": 0,
        "reserved": 0
      }
    ],
    "stages": [
      {
        "id": "stage_example_1",
        "name": "开发"
      }
    ],
    "revision": 1,
    "memberCount": 1,
    "reservedCount": 0,
    "myProjectRole": "LEADER",
    "myMembership": {
      "id": "membership_example_1",
      "membershipId": "membership_example_1",
      "projectId": "project_example_1",
      "userId": "user_example_1",
      "displayName": "示例学生",
      "roleSlotId": "role_example_1",
      "projectRole": "LEADER",
      "status": "ACTIVE",
      "color": "#236B56",
      "colorRevision": 0,
      "joinedAt": "2026-11-02T08:00:00Z",
      "revision": 1
    },
    "historical": false,
    "closingCycle": 0
  }
}
```

<a id="response-r-team-10"></a>

### R_TEAM_10 — 读取 · 项目 / 邀请候选

适用接口：[TEAM-10](#api-team-10)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].eligible`|bool|父记录存在时必有|当前是否符合候选资格；遵守本字段类型、统一上限及对应业务条件|
|`items[].reasonCode`|string/null|必有；允许null|帮助反馈原因枚举或null；遵守本字段类型、统一上限及对应业务条件|
|`items[].skills`|string[]|父记录存在时必有|本人技能标签；遵守本字段类型、统一上限及对应业务条件|
|`items[].source`|string|父记录存在时必有|候选/引用来源类别；遵守本字段类型、统一上限及对应业务条件|
|`items[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].userNo`|string|父记录存在时必有|用户展示编号；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`projectRevision`|int|必有|本次项目来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "userId": "user_example_2",
        "displayName": "示例队员",
        "userNo": "STU-TEST-002",
        "skills": [
          "Java"
        ],
        "eligible": true,
        "reasonCode": null,
        "source": "COURSE"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1,
    "projectRevision": 1
  }
}
```

<a id="response-r-team-11"></a>

### R_TEAM_11 — 读取 · 项目 / 组队码

适用接口：[TEAM-11](#api-team-11)、[TEAM-22](#api-team-22)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`canActivate`|bool|必有|当前调用者是否可激活；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canManage`|bool|必有|当前调用者是否可管理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`code`|string/null|必有；允许null|服务端有效码或响应业务码，按父结构区分；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`enabled`|bool|必有|此项是否启用；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectName`|string|必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "projectId": "project_example_1",
    "projectName": "课程协作应用",
    "code": "TEST-GROUP-CODE-0001",
    "canManage": true,
    "canActivate": true,
    "enabled": true,
    "revision": 1
  }
}
```

<a id="response-r-team-12"></a>

### R_TEAM_12 — 读取 · 项目 / 成员

适用接口：[TEAM-12](#api-team-12)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`items[].colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "membership_example_1",
        "membershipId": "membership_example_1",
        "projectId": "project_example_1",
        "userId": "user_example_1",
        "displayName": "示例学生",
        "roleSlotId": "role_example_1",
        "projectRole": "LEADER",
        "status": "ACTIVE",
        "color": "#236B56",
        "colorRevision": 0,
        "joinedAt": "2026-11-02T08:00:00Z",
        "revision": 1
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-team-13"></a>

### R_TEAM_13 — 读取 · 项目 / 招募配置

适用接口：[TEAM-13](#api-team-13)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`assignmentName`|string|必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`availableSeats`|int|必有|重验后的可用席位数；遵守本字段类型、统一上限及对应业务条件|
|`canApply`|bool|必有|当前调用者是否可申请/应用；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`capacity`|int|必有|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|
|`courseName`|string|必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`discussionTime`|string|必有|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`displayName`|string|必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`dueAt`|string|必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`eligibleForDiscovery`|bool|必有|综合资格及筛选是否进入随机候选池；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ineligibleReason`|string/null|必有；允许null|本次不可参与的实际原因；遵守本字段类型、统一上限及对应业务条件|
|`intro`|string|必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`leaderName`|string|必有|当前组长展示名；遵守本字段类型、统一上限及对应业务条件|
|`memberCount`|int|必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`mode`|string|必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`myApplicationStatus`|string/null|必有；允许null|本人申请状态；遵守本字段类型、统一上限及对应业务条件|
|`myInvitationId`|string/null|必有；允许null|本人的当前待回应邀请ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`name`|string|必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recruitment`|object|必有|当前招募配置；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.acceptBeginners`|bool|父记录存在时必有|是否接受初学者；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.open`|bool|父记录存在时必有|是否开放招募；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.professionalDirection`|string|父记录存在时必有|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.weeklyHours`|int|父记录存在时必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`reservedCount`|int|必有|有效邀请/转组预留数；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`roles`|object[]|必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`roles[].duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`roles[].filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`roles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`roles[].reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`roles[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`roles[].total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`squareNo`|string|必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`stages`|object[]|必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "project_example_1",
    "projectId": "project_example_1",
    "squareId": "square_example_1",
    "squareNo": "TEST-2026-001",
    "courseName": "移动应用开发实践",
    "assignmentId": "assignment_example_1",
    "assignmentName": "课程设计",
    "mode": "DETAILED",
    "name": "课程协作应用",
    "displayName": "课程协作应用",
    "intro": "完成课程设计和联调",
    "capacity": 4,
    "ownerId": "user_example_1",
    "leaderName": "示例学生",
    "status": "ACTIVE",
    "dueAt": "2026-11-09T15:59:59Z",
    "discussionTime": "周三晚",
    "recruitment": {
      "open": true,
      "description": "招募服务器和示例队员",
      "professionalDirection": "软件开发",
      "weeklyHours": 4,
      "acceptBeginners": true
    },
    "roles": [
      {
        "id": "role_example_1",
        "roleName": "客户端开发",
        "duty": "完成App和联调",
        "total": 2,
        "filled": 1,
        "reserved": 0
      },
      {
        "id": "role_example_2",
        "roleName": "服务器开发",
        "duty": "完成API和持久化",
        "total": 2,
        "filled": 0,
        "reserved": 0
      }
    ],
    "stages": [
      {
        "id": "stage_example_1",
        "name": "开发"
      }
    ],
    "revision": 1,
    "memberCount": 1,
    "reservedCount": 0,
    "canApply": true,
    "availableSeats": 3,
    "eligibleForDiscovery": true,
    "ineligibleReason": null,
    "myApplicationStatus": null,
    "myInvitationId": null
  }
}
```

<a id="response-r-team-15"></a>

### R_TEAM_15 — 提交 · 申请 / 审核决定

适用接口：[TEAM-15](#api-team-15)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`application`|object|必有|application：所属响应结构中的结构化记录；遵守本字段类型、统一上限及对应业务条件|
|`application.applicantId`|string|父记录存在时必有|当前结构固定的applicant资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`application.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`application.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`application.invitationId`|string/null|必有；允许null|邀请记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`application.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`application.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`application.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`application.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`application.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`application.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`invitation`|object|必有|invitation：所属响应结构中的结构化记录；遵守本字段类型、统一上限及对应业务条件|
|`invitation.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`invitation.expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`invitation.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitation.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`invitation.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitation.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`invitation.recipientId`|string|父记录存在时必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitation.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`invitation.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitation.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "application": {
      "id": "application_example_1",
      "projectId": "project_example_1",
      "applicantId": "user_example_2",
      "roleSlotId": "role_example_2",
      "message": "希望参与服务器开发",
      "status": "APPROVED",
      "invitationId": "invitation_example_1",
      "reason": "按实际协作情况说明",
      "revision": 2,
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "invitation": {
      "id": "invitation_example_1",
      "projectId": "project_example_1",
      "recipientId": "user_example_2",
      "roleSlotId": "role_example_2",
      "message": "邀请参与项目",
      "status": "PENDING",
      "expiresAt": "2026-11-03T08:00:00Z",
      "reason": "",
      "revision": 1,
      "createdAt": "2026-11-02T08:00:00Z"
    }
  }
}
```

<a id="response-r-team-16"></a>

### R_TEAM_16 — 提交 · 申请 / 撤回

适用接口：[TEAM-16](#api-team-16)、[TEAM-21](#api-team-21)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`applicantId`|string|必有|当前结构固定的applicant资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitationId`|string/null|必有；允许null|邀请记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message`|string|必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`roleSlotId`|string|必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "application_example_1",
    "projectId": "project_example_1",
    "applicantId": "user_example_2",
    "roleSlotId": "role_example_2",
    "message": "希望参与服务器开发",
    "status": "WITHDRAWN",
    "invitationId": null,
    "reason": "",
    "revision": 2,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-team-17"></a>

### R_TEAM_17 — 提交 · 邀请 / 本人回应

适用接口：[TEAM-17](#api-team-17)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`invitation`|object|必有|invitation：所属响应结构中的结构化记录；遵守本字段类型、统一上限及对应业务条件|
|`invitation.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`invitation.expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`invitation.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitation.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`invitation.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitation.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`invitation.recipientId`|string|父记录存在时必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitation.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`invitation.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`invitation.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`membership`|object|必有|本次实际关系资源，没有建立时null；遵守本字段类型、统一上限及对应业务条件|
|`membership.color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`membership.colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`membership.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`membership.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`membership.joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`membership.membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`membership.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`membership.projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`membership.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`membership.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`membership.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`membership.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "invitation": {
      "id": "invitation_example_1",
      "projectId": "project_example_1",
      "recipientId": "user_example_2",
      "roleSlotId": "role_example_2",
      "message": "邀请参与项目",
      "status": "ACCEPTED",
      "expiresAt": "2026-11-03T08:00:00Z",
      "reason": "",
      "revision": 2,
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "membership": {
      "id": "membership_example_1",
      "membershipId": "membership_example_1",
      "projectId": "project_example_1",
      "userId": "user_example_2",
      "displayName": "示例学生",
      "roleSlotId": "role_example_1",
      "projectRole": "MEMBER",
      "status": "ACTIVE",
      "color": "#236B56",
      "colorRevision": 0,
      "joinedAt": "2026-11-02T08:00:00Z",
      "revision": 1
    },
    "projectId": "project_example_1"
  }
}
```

<a id="response-r-team-18"></a>

### R_TEAM_18 — 提交 · 邀请 / 撤销

适用接口：[TEAM-18](#api-team-18)、[TEAM-23](#api-team-23)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message`|string|必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`recipientId`|string|必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`roleSlotId`|string|必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "invitation_example_1",
    "projectId": "project_example_1",
    "recipientId": "user_example_2",
    "roleSlotId": "role_example_2",
    "message": "邀请参与项目",
    "status": "REVOKED",
    "expiresAt": "2026-11-03T08:00:00Z",
    "reason": "按实际协作情况说明",
    "revision": 2,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-team-19"></a>

### R_TEAM_19 — 提交 · 组队码定位

适用接口：[TEAM-19](#api-team-19)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`canApply`|bool|必有|当前调用者是否可申请/应用；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reasonCode`|string/null|必有；允许null|帮助反馈原因枚举或null；遵守本字段类型、统一上限及对应业务条件|
|`recruitment`|object|必有|当前招募配置；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recruitment.assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.availableSeats`|int|父记录存在时必有|重验后的可用席位数；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.canApply`|bool|父记录存在时必有|当前调用者是否可申请/应用；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.capacity`|int|父记录存在时必有|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.discussionTime`|string|父记录存在时必有|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`recruitment.eligibleForDiscovery`|bool|父记录存在时必有|综合资格及筛选是否进入随机候选池；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recruitment.ineligibleReason`|string/null|必有；允许null|本次不可参与的实际原因；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.intro`|string|父记录存在时必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.leaderName`|string|父记录存在时必有|当前组长展示名；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.memberCount`|int|父记录存在时必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.myApplicationStatus`|string/null|必有；允许null|本人申请状态；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.myInvitationId`|string/null|必有；允许null|本人的当前待回应邀请ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recruitment.name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recruitment.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recruitment.recruitment`|object|父记录存在时必有|当前招募配置；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.recruitment.acceptBeginners`|bool|父记录存在时必有|是否接受初学者；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.recruitment.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.recruitment.open`|bool|父记录存在时必有|是否开放招募；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.recruitment.professionalDirection`|string|父记录存在时必有|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.recruitment.weeklyHours`|int|父记录存在时必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.reservedCount`|int|父记录存在时必有|有效邀请/转组预留数；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`recruitment.roles`|object[]|父记录存在时必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.roles[].duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.roles[].filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.roles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recruitment.roles[].reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.roles[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.roles[].total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recruitment.squareNo`|string|父记录存在时必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.stages`|object[]|父记录存在时必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recruitment.stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "projectId": "project_example_1",
    "recruitment": {
      "id": "project_example_1",
      "projectId": "project_example_1",
      "squareId": "square_example_1",
      "squareNo": "TEST-2026-001",
      "courseName": "移动应用开发实践",
      "assignmentId": "assignment_example_1",
      "assignmentName": "课程设计",
      "mode": "DETAILED",
      "name": "课程协作应用",
      "displayName": "课程协作应用",
      "intro": "完成课程设计和联调",
      "capacity": 4,
      "ownerId": "user_example_1",
      "leaderName": "示例学生",
      "status": "ACTIVE",
      "dueAt": "2026-11-09T15:59:59Z",
      "discussionTime": "周三晚",
      "recruitment": {
        "open": true,
        "description": "招募服务器和示例队员",
        "professionalDirection": "软件开发",
        "weeklyHours": 4,
        "acceptBeginners": true
      },
      "roles": [
        {
          "id": "role_example_1",
          "roleName": "客户端开发",
          "duty": "完成App和联调",
          "total": 2,
          "filled": 1,
          "reserved": 0
        },
        {
          "id": "role_example_2",
          "roleName": "服务器开发",
          "duty": "完成API和持久化",
          "total": 2,
          "filled": 0,
          "reserved": 0
        }
      ],
      "stages": [
        {
          "id": "stage_example_1",
          "name": "开发"
        }
      ],
      "revision": 1,
      "memberCount": 1,
      "reservedCount": 0,
      "canApply": true,
      "availableSeats": 3,
      "eligibleForDiscovery": true,
      "ineligibleReason": null,
      "myApplicationStatus": null,
      "myInvitationId": null
    },
    "canApply": true,
    "reasonCode": null
  }
}
```

<a id="response-r-team-20"></a>

### R_TEAM_20 — 提交 · 项目

适用接口：[TEAM-20](#api-team-20)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`assignmentName`|string|必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`capacity`|int|必有|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|
|`closingCycle`|int|必有|项目本次收尾轮次；遵守本字段类型、统一上限及对应业务条件|
|`courseName`|string|必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`discussionTime`|string|必有|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`displayName`|string|必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`dueAt`|string|必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`historical`|bool|必有|是否为有权历史投影；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`intro`|string|必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`leaderName`|string|必有|当前组长展示名；遵守本字段类型、统一上限及对应业务条件|
|`memberCount`|int|必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`mode`|string|必有|当前业务模式；QUICK/DETAILED；DETAILED的name/intro/roles/leaderRoleKey必填|
|`myMembership`|object|必有|本人当前成员关系；没有时按模型为空对象；遵守本字段类型、统一上限及对应业务条件|
|`myMembership.color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`myMembership.colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`myMembership.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`myMembership.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myMembership.joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`myMembership.membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myMembership.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myMembership.projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`myMembership.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`myMembership.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myMembership.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`myMembership.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myProjectRole`|string|必有|本人项目内身份；遵守本字段类型、统一上限及对应业务条件|
|`name`|string|必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recruitment`|object|必有|当前招募配置；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.acceptBeginners`|bool|父记录存在时必有|是否接受初学者；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.open`|bool|父记录存在时必有|是否开放招募；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.professionalDirection`|string|父记录存在时必有|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`recruitment.weeklyHours`|int|父记录存在时必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`reservedCount`|int|必有|有效邀请/转组预留数；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`roles`|object[]|必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`roles[].clientKey`|string|父记录存在时必有|本次输入中新增项唯一键，服务器会生成稳定id；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`roles[].duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`roles[].filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`roles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`roles[].reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`roles[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`roles[].total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`squareNo`|string|必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`stages`|object[]|必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "project_example_1",
    "projectId": "project_example_1",
    "squareId": "square_example_1",
    "squareNo": "TEST-2026-001",
    "courseName": "移动应用开发实践",
    "assignmentId": "assignment_example_1",
    "assignmentName": "课程设计",
    "mode": "DETAILED",
    "name": "课程协作应用",
    "displayName": "课程协作应用",
    "intro": "负责客户端开发",
    "capacity": 4,
    "ownerId": "user_example_1",
    "leaderName": "示例学生",
    "status": "ACTIVE",
    "dueAt": "2026-11-09T15:59:59Z",
    "discussionTime": "周三晚",
    "recruitment": {
      "open": true,
      "description": "招募服务器和示例队员",
      "professionalDirection": "软件开发",
      "weeklyHours": 4,
      "acceptBeginners": true
    },
    "roles": [
      {
        "clientKey": "client",
        "roleName": "客户端开发",
        "duty": "完成App与联调",
        "total": 2
      },
      {
        "clientKey": "server",
        "roleName": "服务器开发",
        "duty": "完成API与持久化",
        "total": 2
      }
    ],
    "stages": [
      {
        "id": "stage_example_1",
        "name": "开发"
      }
    ],
    "revision": 1,
    "memberCount": 1,
    "reservedCount": 0,
    "myProjectRole": "LEADER",
    "myMembership": {
      "id": "membership_example_1",
      "membershipId": "membership_example_1",
      "projectId": "project_example_1",
      "userId": "user_example_1",
      "displayName": "示例学生",
      "roleSlotId": "role_example_1",
      "projectRole": "LEADER",
      "status": "ACTIVE",
      "color": "#236B56",
      "colorRevision": 0,
      "joinedAt": "2026-11-02T08:00:00Z",
      "revision": 1
    },
    "historical": false,
    "closingCycle": 0
  }
}
```

<a id="response-r-team-25"></a>

### R_TEAM_25 — 读取 · 本人 / 行动概况

适用接口：[TEAM-25](#api-team-25)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`actionCount`|int|必有|当前待本人处理数量；遵守本字段类型、统一上限及对应业务条件|
|`activeProjectCount`|int|必有|本范围的activeProject数量；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].resourceId`|string/null|必有；允许null|该入口当前有权资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].resourceType`|string|父记录存在时必有|来源资源种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].state`|string|父记录存在时必有|该投影的当前状态；遵守本字段类型、统一上限及对应业务条件|
|`items[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].updatedAt`|string|父记录存在时必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`pendingApplicationCount`|int|必有|本范围的pendingApplication数量；遵守本字段类型、统一上限及对应业务条件|
|`pendingInvitationCount`|int|必有|本范围的pendingInvitation数量；遵守本字段类型、统一上限及对应业务条件|
|`waitingCount`|int|必有|当前等待进展数量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "actionCount": 1,
    "waitingCount": 0,
    "pendingApplicationCount": 0,
    "pendingInvitationCount": 0,
    "activeProjectCount": 1,
    "items": [
      {
        "id": "todo_example_1",
        "type": "TASK",
        "resourceType": "TASK",
        "resourceId": "task_example_1",
        "projectId": "project_example_1",
        "assignmentId": "assignment_example_1",
        "title": "完成登录页面",
        "state": "ACTION",
        "updatedAt": "2026-11-02T08:00:00Z",
        "revision": 1
      }
    ]
  }
}
```

<a id="response-r-team-26"></a>

### R_TEAM_26 — 读取 · 本人 / 待办与等待进展

适用接口：[TEAM-26](#api-team-26)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].resourceId`|string/null|必有；允许null|该入口当前有权资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].resourceType`|string|父记录存在时必有|来源资源种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].state`|string|父记录存在时必有|该投影的当前状态；遵守本字段类型、统一上限及对应业务条件|
|`items[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].updatedAt`|string|父记录存在时必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "todo_example_1",
        "type": "TASK",
        "resourceType": "TASK",
        "resourceId": "task_example_1",
        "projectId": "project_example_1",
        "assignmentId": "assignment_example_1",
        "title": "完成登录页面",
        "state": "ACTION",
        "updatedAt": "2026-11-02T08:00:00Z",
        "revision": 1
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-role-01"></a>

### R_ROLE_01 — 读取 · 本人 / 多人岗位方案

适用接口：[ROLE-01](#api-role-01)、[ROLE-04](#api-role-04)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].availability`|string|父记录存在时必有|来源是否仍允许本动作的只读原因码；遵守本字段类型、统一上限及对应业务条件|
|`items[].canConfirm`|bool|父记录存在时必有|当前调用者是否可本人确认；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canDecline`|bool|父记录存在时必有|当前调用者是否可拒绝；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canOpenProject`|bool|父记录存在时必有|当前调用者是否可打开项目；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canWithdraw`|bool|父记录存在时必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].myConfirmed`|bool|父记录存在时必有|本人对当前固定版本是否确认；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan`|object|父记录存在时必有|完整正式约定或岗位调整方案，按模型区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].plan.confirmations`|object[]|父记录存在时必有|当事人接受记录；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.confirmations[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.confirmations[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.confirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].plan.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].plan.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].plan.parties`|object[]|父记录存在时必有|本方案受影响的固定成员集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.parties[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.parties[].membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].plan.parties[].membershipRevision`|int|父记录存在时必有|本结构固定的membershipRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].plan.parties[].originalRole`|object|父记录存在时必有|固定原岗位快照；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.parties[].originalRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.parties[].originalRole.filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.parties[].originalRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].plan.parties[].originalRole.reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.parties[].originalRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.parties[].originalRole.total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.parties[].targetRole`|object|父记录存在时必有|目标岗位投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.parties[].targetRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.parties[].targetRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].plan.parties[].targetRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.parties[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].plan.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].plan.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].plan.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].plan.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].plan.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].projectRevision`|int|父记录存在时必有|本次项目来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "plan": {
          "id": "role_plan_example_1",
          "projectId": "project_example_1",
          "projectName": "课程协作应用",
          "authorId": "user_example_1",
          "reason": "协商调整岗位",
          "parties": [
            {
              "userId": "user_example_2",
              "displayName": "示例队员",
              "membershipId": "membership_example_2",
              "membershipRevision": 1,
              "originalRole": {
                "id": "role_example_1",
                "roleName": "客户端开发",
                "duty": "完成App和联调",
                "total": 2,
                "filled": 1,
                "reserved": 0
              },
              "targetRole": {
                "id": "role_example_2",
                "roleName": "服务器开发",
                "duty": "实现API"
              }
            },
            {
              "userId": "user_example_3",
              "displayName": "示例队员",
              "membershipId": "membership_example_3",
              "membershipRevision": 1,
              "originalRole": {
                "id": "role_example_2",
                "roleName": "服务器开发",
                "duty": "实现API"
              },
              "targetRole": {
                "id": "role_example_1",
                "roleName": "客户端开发",
                "duty": "完成App和联调"
              }
            }
          ],
          "confirmations": [],
          "status": "PENDING",
          "revision": 1,
          "createdAt": "2026-11-02T08:00:00Z"
        },
        "projectRevision": 1,
        "availability": "READY",
        "myConfirmed": false,
        "canConfirm": true,
        "canDecline": true,
        "canWithdraw": false,
        "canOpenProject": true
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-role-02"></a>

### R_ROLE_02 — 读取 · 本人 / 岗位提议

适用接口：[ROLE-02](#api-role-02)、[ROLE-05](#api-role-05)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].availability`|string|父记录存在时必有|来源是否仍允许本动作的只读原因码；遵守本字段类型、统一上限及对应业务条件|
|`items[].canAccept`|bool|父记录存在时必有|当前调用者是否可接受；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canDecline`|bool|父记录存在时必有|当前调用者是否可拒绝；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canOpenProject`|bool|父记录存在时必有|当前调用者是否可打开项目；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canWithdraw`|bool|父记录存在时必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].currentRole`|object|父记录存在时必有|来源岗位投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].currentRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`items[].currentRole.filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].currentRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].currentRole.reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].currentRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].currentRole.total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`items[].projectRevision`|int|父记录存在时必有|本次项目来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].proposal`|object|父记录存在时必有|岗位提议资源；遵守本字段类型、统一上限及对应业务条件|
|`items[].proposal.authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].proposal.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].proposal.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].proposal.membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].proposal.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].proposal.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].proposal.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].proposal.recipientId`|string|父记录存在时必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].proposal.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].proposal.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].proposal.targetRole`|object|父记录存在时必有|目标岗位投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].proposal.targetRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`items[].proposal.targetRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].proposal.targetRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "proposal": {
          "id": "role_proposal_example_1",
          "projectId": "project_example_1",
          "projectName": "课程协作应用",
          "authorId": "user_example_1",
          "recipientId": "user_example_2",
          "membershipId": "membership_example_2",
          "targetRole": {
            "id": "role_example_2",
            "roleName": "服务器开发",
            "duty": "实现API"
          },
          "message": "调整招募岗位",
          "status": "PENDING",
          "revision": 1,
          "createdAt": "2026-11-02T08:00:00Z"
        },
        "projectRevision": 1,
        "availability": "READY",
        "currentRole": {
          "id": "role_example_1",
          "roleName": "客户端开发",
          "duty": "完成App和联调",
          "total": 2,
          "filled": 1,
          "reserved": 0
        },
        "canAccept": true,
        "canDecline": true,
        "canWithdraw": false,
        "canOpenProject": true
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-role-03"></a>

### R_ROLE_03 — 读取 · 本人 / 双人岗位互换

适用接口：[ROLE-03](#api-role-03)、[ROLE-07](#api-role-07)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].availability`|string|父记录存在时必有|来源是否仍允许本动作的只读原因码；遵守本字段类型、统一上限及对应业务条件|
|`items[].canConfirm`|bool|父记录存在时必有|当前调用者是否可本人确认；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canDecline`|bool|父记录存在时必有|当前调用者是否可拒绝；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canOpenProject`|bool|父记录存在时必有|当前调用者是否可打开项目；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canWithdraw`|bool|父记录存在时必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].myConfirmed`|bool|父记录存在时必有|本人对当前固定版本是否确认；遵守本字段类型、统一上限及对应业务条件|
|`items[].projectRevision`|int|父记录存在时必有|本次项目来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].swap`|object|父记录存在时必有|双人岗位互换资源；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].swap.confirmations`|object[]|父记录存在时必有|当事人接受记录；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.confirmations[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.confirmations[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.confirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].swap.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].swap.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].swap.parties`|object[]|父记录存在时必有|本方案受影响的固定成员集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.parties[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.parties[].membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].swap.parties[].membershipRevision`|int|父记录存在时必有|本结构固定的membershipRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].swap.parties[].originalRole`|object|父记录存在时必有|固定原岗位快照；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.parties[].originalRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.parties[].originalRole.filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.parties[].originalRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].swap.parties[].originalRole.reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.parties[].originalRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.parties[].originalRole.total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.parties[].targetRole`|object|父记录存在时必有|目标岗位投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.parties[].targetRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.parties[].targetRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].swap.parties[].targetRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.parties[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].swap.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].swap.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].swap.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].swap.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].swap.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "swap": {
          "id": "role_swap_example_1",
          "projectId": "project_example_1",
          "projectName": "课程协作应用",
          "authorId": "user_example_1",
          "reason": "协商调整岗位",
          "parties": [
            {
              "userId": "user_example_2",
              "displayName": "示例队员",
              "membershipId": "membership_example_2",
              "membershipRevision": 1,
              "originalRole": {
                "id": "role_example_1",
                "roleName": "客户端开发",
                "duty": "完成App和联调",
                "total": 2,
                "filled": 1,
                "reserved": 0
              },
              "targetRole": {
                "id": "role_example_2",
                "roleName": "服务器开发",
                "duty": "实现API"
              }
            },
            {
              "userId": "user_example_3",
              "displayName": "示例队员",
              "membershipId": "membership_example_3",
              "membershipRevision": 1,
              "originalRole": {
                "id": "role_example_2",
                "roleName": "服务器开发",
                "duty": "实现API"
              },
              "targetRole": {
                "id": "role_example_1",
                "roleName": "客户端开发",
                "duty": "完成App和联调"
              }
            }
          ],
          "confirmations": [],
          "status": "PENDING",
          "revision": 1,
          "createdAt": "2026-11-02T08:00:00Z"
        },
        "projectRevision": 1,
        "availability": "READY",
        "myConfirmed": false,
        "canConfirm": true,
        "canDecline": true,
        "canWithdraw": false,
        "canOpenProject": true
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-role-06"></a>

### R_ROLE_06 — 读取 · 项目 / 岗位定义

适用接口：[ROLE-06](#api-role-06)、[ROLE-21](#api-role-21)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`conversionBlockers`|object[]|必有|快速转详细仍未满足的条件；遵守本字段类型、统一上限及对应业务条件|
|`conversionBlockers[].code`|string/null|必有；允许null|服务端有效码或响应业务码，按父结构区分；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`conversionBlockers[].message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`members`|object[]|必有|按权限可见的正式成员投影；遵守本字段类型、统一上限及对应业务条件|
|`members[].color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`members[].colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`members[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`members[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`members[].joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`members[].membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`members[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`members[].projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`members[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`members[].roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`members[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`members[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project`|object|必有|有权项目投影；遵守本字段类型、统一上限及对应业务条件|
|`project.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`project.capacity`|int|父记录存在时必有|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|
|`project.closingCycle`|int|父记录存在时必有|项目本次收尾轮次；遵守本字段类型、统一上限及对应业务条件|
|`project.courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`project.discussionTime`|string|父记录存在时必有|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`project.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`project.dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`project.historical`|bool|父记录存在时必有|是否为有权历史投影；遵守本字段类型、统一上限及对应业务条件|
|`project.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.intro`|string|父记录存在时必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`project.leaderName`|string|父记录存在时必有|当前组长展示名；遵守本字段类型、统一上限及对应业务条件|
|`project.memberCount`|int|父记录存在时必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`project.mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`project.myMembership`|object|父记录存在时必有|本人当前成员关系；没有时按模型为空对象；遵守本字段类型、统一上限及对应业务条件|
|`project.myMembership.color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`project.myMembership.colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`project.myMembership.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`project.myMembership.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.myMembership.joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`project.myMembership.membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.myMembership.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.myMembership.projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`project.myMembership.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`project.myMembership.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.myMembership.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`project.myMembership.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.myProjectRole`|string|父记录存在时必有|本人项目内身份；遵守本字段类型、统一上限及对应业务条件|
|`project.name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`project.ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.recruitment`|object|父记录存在时必有|当前招募配置；遵守本字段类型、统一上限及对应业务条件|
|`project.recruitment.acceptBeginners`|bool|父记录存在时必有|是否接受初学者；遵守本字段类型、统一上限及对应业务条件|
|`project.recruitment.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`project.recruitment.open`|bool|父记录存在时必有|是否开放招募；遵守本字段类型、统一上限及对应业务条件|
|`project.recruitment.professionalDirection`|string|父记录存在时必有|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`project.recruitment.weeklyHours`|int|父记录存在时必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`project.reservedCount`|int|父记录存在时必有|有效邀请/转组预留数；遵守本字段类型、统一上限及对应业务条件|
|`project.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`project.roles`|object[]|父记录存在时必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`project.roles[].duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`project.roles[].filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`project.roles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.roles[].reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`project.roles[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`project.roles[].total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`project.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.squareNo`|string|父记录存在时必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`project.stages`|object[]|父记录存在时必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`project.stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`project.stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`project.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`roles`|object[]|必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`roles[].duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`roles[].filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`roles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`roles[].reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`roles[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`roles[].total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "project": {
      "id": "project_example_1",
      "projectId": "project_example_1",
      "squareId": "square_example_1",
      "squareNo": "TEST-2026-001",
      "courseName": "移动应用开发实践",
      "assignmentId": "assignment_example_1",
      "assignmentName": "课程设计",
      "mode": "DETAILED",
      "name": "课程协作应用",
      "displayName": "课程协作应用",
      "intro": "完成课程设计和联调",
      "capacity": 4,
      "ownerId": "user_example_1",
      "leaderName": "示例学生",
      "status": "ACTIVE",
      "dueAt": "2026-11-09T15:59:59Z",
      "discussionTime": "周三晚",
      "recruitment": {
        "open": true,
        "description": "招募服务器和示例队员",
        "professionalDirection": "软件开发",
        "weeklyHours": 4,
        "acceptBeginners": true
      },
      "roles": [
        {
          "id": "role_example_1",
          "roleName": "客户端开发",
          "duty": "完成App和联调",
          "total": 2,
          "filled": 1,
          "reserved": 0
        },
        {
          "id": "role_example_2",
          "roleName": "服务器开发",
          "duty": "完成API和持久化",
          "total": 2,
          "filled": 0,
          "reserved": 0
        }
      ],
      "stages": [
        {
          "id": "stage_example_1",
          "name": "开发"
        }
      ],
      "revision": 1,
      "memberCount": 1,
      "reservedCount": 0,
      "myProjectRole": "LEADER",
      "myMembership": {
        "id": "membership_example_1",
        "membershipId": "membership_example_1",
        "projectId": "project_example_1",
        "userId": "user_example_1",
        "displayName": "示例学生",
        "roleSlotId": "role_example_1",
        "projectRole": "LEADER",
        "status": "ACTIVE",
        "color": "#23745B",
        "colorRevision": 0,
        "joinedAt": "2026-11-02T08:00:00Z",
        "revision": 1
      },
      "historical": false,
      "closingCycle": 0
    },
    "roles": [
      {
        "id": "role_example_1",
        "roleName": "客户端开发",
        "duty": "完成App和联调",
        "total": 2,
        "filled": 1,
        "reserved": 0
      },
      {
        "id": "role_example_2",
        "roleName": "服务器开发",
        "duty": "完成API和持久化",
        "total": 2,
        "filled": 0,
        "reserved": 0
      }
    ],
    "members": [
      {
        "id": "membership_example_1",
        "membershipId": "membership_example_1",
        "projectId": "project_example_1",
        "userId": "user_example_1",
        "displayName": "示例学生",
        "roleSlotId": "role_example_1",
        "projectRole": "LEADER",
        "status": "ACTIVE",
        "color": "#23745B",
        "colorRevision": 0,
        "joinedAt": "2026-11-02T08:00:00Z",
        "revision": 1
      }
    ],
    "conversionBlockers": []
  }
}
```

<a id="response-r-role-08"></a>

### R_ROLE_08 — 读取 · 多人岗位方案

适用接口：[ROLE-08](#api-role-08)、[ROLE-11](#api-role-11)、[ROLE-14](#api-role-14)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`availability`|string|必有|来源是否仍允许本动作的只读原因码；遵守本字段类型、统一上限及对应业务条件|
|`canConfirm`|bool|必有|当前调用者是否可本人确认；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canDecline`|bool|必有|当前调用者是否可拒绝；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canOpenProject`|bool|必有|当前调用者是否可打开项目；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canWithdraw`|bool|必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`myConfirmed`|bool|必有|本人对当前固定版本是否确认；遵守本字段类型、统一上限及对应业务条件|
|`plan`|object|必有|完整正式约定或岗位调整方案，按模型区分；遵守本字段类型、统一上限及对应业务条件|
|`plan.authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.confirmations`|object[]|父记录存在时必有|当事人接受记录；遵守本字段类型、统一上限及对应业务条件|
|`plan.confirmations[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`plan.confirmations[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`plan.confirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`plan.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.parties`|object[]|父记录存在时必有|本方案受影响的固定成员集合；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.parties[].membershipRevision`|int|父记录存在时必有|本结构固定的membershipRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`plan.parties[].originalRole`|object|父记录存在时必有|固定原岗位快照；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].originalRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].originalRole.filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].originalRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.parties[].originalRole.reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].originalRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].originalRole.total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].targetRole`|object|父记录存在时必有|目标岗位投影；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].targetRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].targetRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.parties[].targetRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`plan.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`plan.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`plan.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`projectRevision`|int|必有|本次项目来源版本；整数1～999999999；仅明确允许的首次私有记录用0|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "plan": {
      "id": "role_plan_example_1",
      "projectId": "project_example_1",
      "projectName": "课程协作应用",
      "authorId": "user_example_1",
      "reason": "协商调整岗位",
      "parties": [
        {
          "userId": "user_example_2",
          "displayName": "示例队员",
          "membershipId": "membership_example_2",
          "membershipRevision": 1,
          "originalRole": {
            "id": "role_example_1",
            "roleName": "客户端开发",
            "duty": "完成App和联调",
            "total": 2,
            "filled": 1,
            "reserved": 0
          },
          "targetRole": {
            "id": "role_example_2",
            "roleName": "服务器开发",
            "duty": "实现API"
          }
        },
        {
          "userId": "user_example_3",
          "displayName": "示例队员",
          "membershipId": "membership_example_3",
          "membershipRevision": 1,
          "originalRole": {
            "id": "role_example_2",
            "roleName": "服务器开发",
            "duty": "实现API"
          },
          "targetRole": {
            "id": "role_example_1",
            "roleName": "客户端开发",
            "duty": "完成App和联调"
          }
        }
      ],
      "confirmations": [],
      "status": "PENDING",
      "revision": 1,
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "projectRevision": 1,
    "availability": "READY",
    "myConfirmed": false,
    "canConfirm": true,
    "canDecline": true,
    "canWithdraw": false,
    "canOpenProject": true
  }
}
```

<a id="response-r-role-09"></a>

### R_ROLE_09 — 读取 · 岗位提议

适用接口：[ROLE-09](#api-role-09)、[ROLE-12](#api-role-12)、[ROLE-16](#api-role-16)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`availability`|string|必有|来源是否仍允许本动作的只读原因码；遵守本字段类型、统一上限及对应业务条件|
|`canAccept`|bool|必有|当前调用者是否可接受；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canDecline`|bool|必有|当前调用者是否可拒绝；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canOpenProject`|bool|必有|当前调用者是否可打开项目；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canWithdraw`|bool|必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`currentRole`|object|必有|来源岗位投影；遵守本字段类型、统一上限及对应业务条件|
|`currentRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`currentRole.filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`currentRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`currentRole.reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`currentRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`currentRole.total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`projectRevision`|int|必有|本次项目来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`proposal`|object|必有|岗位提议资源；遵守本字段类型、统一上限及对应业务条件|
|`proposal.authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposal.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`proposal.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposal.membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposal.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`proposal.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposal.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`proposal.recipientId`|string|父记录存在时必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposal.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`proposal.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`proposal.targetRole`|object|父记录存在时必有|目标岗位投影；遵守本字段类型、统一上限及对应业务条件|
|`proposal.targetRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`proposal.targetRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposal.targetRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "proposal": {
      "id": "role_proposal_example_1",
      "projectId": "project_example_1",
      "projectName": "课程协作应用",
      "authorId": "user_example_1",
      "recipientId": "user_example_2",
      "membershipId": "membership_example_2",
      "targetRole": {
        "id": "role_example_2",
        "roleName": "服务器开发",
        "duty": "实现API"
      },
      "message": "调整招募岗位",
      "status": "PENDING",
      "revision": 1,
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "projectRevision": 1,
    "availability": "READY",
    "currentRole": {
      "id": "role_example_1",
      "roleName": "客户端开发",
      "duty": "完成App和联调",
      "total": 2,
      "filled": 1,
      "reserved": 0
    },
    "canAccept": true,
    "canDecline": true,
    "canWithdraw": false,
    "canOpenProject": true
  }
}
```

<a id="response-r-role-10"></a>

### R_ROLE_10 — 读取 · 双人岗位互换

适用接口：[ROLE-10](#api-role-10)、[ROLE-13](#api-role-13)、[ROLE-18](#api-role-18)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`availability`|string|必有|来源是否仍允许本动作的只读原因码；遵守本字段类型、统一上限及对应业务条件|
|`canConfirm`|bool|必有|当前调用者是否可本人确认；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canDecline`|bool|必有|当前调用者是否可拒绝；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canOpenProject`|bool|必有|当前调用者是否可打开项目；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canWithdraw`|bool|必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`myConfirmed`|bool|必有|本人对当前固定版本是否确认；遵守本字段类型、统一上限及对应业务条件|
|`projectRevision`|int|必有|本次项目来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`swap`|object|必有|双人岗位互换资源；遵守本字段类型、统一上限及对应业务条件|
|`swap.authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.confirmations`|object[]|父记录存在时必有|当事人接受记录；遵守本字段类型、统一上限及对应业务条件|
|`swap.confirmations[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`swap.confirmations[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`swap.confirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`swap.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.parties`|object[]|父记录存在时必有|本方案受影响的固定成员集合；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.parties[].membershipRevision`|int|父记录存在时必有|本结构固定的membershipRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`swap.parties[].originalRole`|object|父记录存在时必有|固定原岗位快照；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].originalRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].originalRole.filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].originalRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.parties[].originalRole.reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].originalRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].originalRole.total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].targetRole`|object|父记录存在时必有|目标岗位投影；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].targetRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].targetRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.parties[].targetRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`swap.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`swap.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`swap.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "swap": {
      "id": "role_swap_example_1",
      "projectId": "project_example_1",
      "projectName": "课程协作应用",
      "authorId": "user_example_1",
      "reason": "协商调整岗位",
      "parties": [
        {
          "userId": "user_example_2",
          "displayName": "示例队员",
          "membershipId": "membership_example_2",
          "membershipRevision": 1,
          "originalRole": {
            "id": "role_example_1",
            "roleName": "客户端开发",
            "duty": "完成App和联调",
            "total": 2,
            "filled": 1,
            "reserved": 0
          },
          "targetRole": {
            "id": "role_example_2",
            "roleName": "服务器开发",
            "duty": "实现API"
          }
        },
        {
          "userId": "user_example_3",
          "displayName": "示例队员",
          "membershipId": "membership_example_3",
          "membershipRevision": 1,
          "originalRole": {
            "id": "role_example_2",
            "roleName": "服务器开发",
            "duty": "实现API"
          },
          "targetRole": {
            "id": "role_example_1",
            "roleName": "客户端开发",
            "duty": "完成App和联调"
          }
        }
      ],
      "confirmations": [],
      "status": "PENDING",
      "revision": 1,
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "projectRevision": 1,
    "availability": "READY",
    "myConfirmed": false,
    "canConfirm": true,
    "canDecline": true,
    "canWithdraw": false,
    "canOpenProject": true
  }
}
```

<a id="response-r-role-15"></a>

### R_ROLE_15 — 提交 · 多人岗位方案 / 撤回

适用接口：[ROLE-15](#api-role-15)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`availability`|string|必有|来源是否仍允许本动作的只读原因码；遵守本字段类型、统一上限及对应业务条件|
|`canAccept`|bool|必有|当前调用者是否可接受；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canConfirm`|bool|必有|当前调用者是否可本人确认；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canDecline`|bool|必有|当前调用者是否可拒绝；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canOpenProject`|bool|必有|当前调用者是否可打开项目；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canWithdraw`|bool|必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`myConfirmed`|bool|必有|本人对当前固定版本是否确认；遵守本字段类型、统一上限及对应业务条件|
|`plan`|object|必有|完整正式约定或岗位调整方案，按模型区分；遵守本字段类型、统一上限及对应业务条件|
|`plan.authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.confirmations`|object[]|父记录存在时必有|当事人接受记录；遵守本字段类型、统一上限及对应业务条件|
|`plan.confirmations[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`plan.confirmations[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`plan.confirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`plan.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.parties`|object[]|父记录存在时必有|本方案受影响的固定成员集合；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.parties[].membershipRevision`|int|父记录存在时必有|本结构固定的membershipRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`plan.parties[].originalRole`|object|父记录存在时必有|固定原岗位快照；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].originalRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].originalRole.filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].originalRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.parties[].originalRole.reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].originalRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].originalRole.total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].targetRole`|object|父记录存在时必有|目标岗位投影；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].targetRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].targetRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.parties[].targetRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`plan.parties[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`plan.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`plan.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`plan.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`plan.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`projectRevision`|int|必有|本次项目来源版本；整数1～999999999；仅明确允许的首次私有记录用0|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "plan": {
      "id": "role_plan_example_1",
      "projectId": "project_example_1",
      "projectName": "课程协作应用",
      "authorId": "user_example_1",
      "reason": "协商调整岗位",
      "parties": [
        {
          "userId": "user_example_2",
          "displayName": "示例队员",
          "membershipId": "membership_example_2",
          "membershipRevision": 1,
          "originalRole": {
            "id": "role_example_1",
            "roleName": "客户端开发",
            "duty": "完成App和联调",
            "total": 2,
            "filled": 1,
            "reserved": 0
          },
          "targetRole": {
            "id": "role_example_2",
            "roleName": "服务器开发",
            "duty": "实现API"
          }
        },
        {
          "userId": "user_example_3",
          "displayName": "示例队员",
          "membershipId": "membership_example_3",
          "membershipRevision": 1,
          "originalRole": {
            "id": "role_example_2",
            "roleName": "服务器开发",
            "duty": "实现API"
          },
          "targetRole": {
            "id": "role_example_1",
            "roleName": "客户端开发",
            "duty": "完成App和联调"
          }
        }
      ],
      "confirmations": [],
      "status": "WITHDRAWN",
      "revision": 2,
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "projectRevision": 1,
    "availability": "READY",
    "myConfirmed": false,
    "canConfirm": false,
    "canDecline": false,
    "canWithdraw": false,
    "canOpenProject": true,
    "canAccept": false
  }
}
```

<a id="response-r-role-17"></a>

### R_ROLE_17 — 提交 · 岗位提议 / 撤回

适用接口：[ROLE-17](#api-role-17)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`availability`|string|必有|来源是否仍允许本动作的只读原因码；遵守本字段类型、统一上限及对应业务条件|
|`canAccept`|bool|必有|当前调用者是否可接受；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canConfirm`|bool|必有|当前调用者是否可本人确认；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canDecline`|bool|必有|当前调用者是否可拒绝；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canOpenProject`|bool|必有|当前调用者是否可打开项目；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canWithdraw`|bool|必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`currentRole`|object|必有|来源岗位投影；遵守本字段类型、统一上限及对应业务条件|
|`currentRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`currentRole.filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`currentRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`currentRole.reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`currentRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`currentRole.total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`projectRevision`|int|必有|本次项目来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`proposal`|object|必有|岗位提议资源；遵守本字段类型、统一上限及对应业务条件|
|`proposal.authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposal.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`proposal.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposal.membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposal.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`proposal.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposal.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`proposal.recipientId`|string|父记录存在时必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposal.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`proposal.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`proposal.targetRole`|object|父记录存在时必有|目标岗位投影；遵守本字段类型、统一上限及对应业务条件|
|`proposal.targetRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`proposal.targetRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposal.targetRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "proposal": {
      "id": "role_proposal_example_1",
      "projectId": "project_example_1",
      "projectName": "课程协作应用",
      "authorId": "user_example_1",
      "recipientId": "user_example_2",
      "membershipId": "membership_example_2",
      "targetRole": {
        "id": "role_example_2",
        "roleName": "服务器开发",
        "duty": "实现API"
      },
      "message": "调整招募岗位",
      "status": "WITHDRAWN",
      "revision": 2,
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "projectRevision": 1,
    "availability": "READY",
    "currentRole": {
      "id": "role_example_1",
      "roleName": "客户端开发",
      "duty": "完成App和联调",
      "total": 2,
      "filled": 1,
      "reserved": 0
    },
    "canAccept": false,
    "canDecline": false,
    "canWithdraw": false,
    "canOpenProject": true,
    "canConfirm": false
  }
}
```

<a id="response-r-role-19"></a>

### R_ROLE_19 — 提交 · 双人岗位互换 / 撤回

适用接口：[ROLE-19](#api-role-19)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`availability`|string|必有|来源是否仍允许本动作的只读原因码；遵守本字段类型、统一上限及对应业务条件|
|`canAccept`|bool|必有|当前调用者是否可接受；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canConfirm`|bool|必有|当前调用者是否可本人确认；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canDecline`|bool|必有|当前调用者是否可拒绝；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canOpenProject`|bool|必有|当前调用者是否可打开项目；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canWithdraw`|bool|必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`myConfirmed`|bool|必有|本人对当前固定版本是否确认；遵守本字段类型、统一上限及对应业务条件|
|`projectRevision`|int|必有|本次项目来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`swap`|object|必有|双人岗位互换资源；遵守本字段类型、统一上限及对应业务条件|
|`swap.authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.confirmations`|object[]|父记录存在时必有|当事人接受记录；遵守本字段类型、统一上限及对应业务条件|
|`swap.confirmations[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`swap.confirmations[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`swap.confirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`swap.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.parties`|object[]|父记录存在时必有|本方案受影响的固定成员集合；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.parties[].membershipRevision`|int|父记录存在时必有|本结构固定的membershipRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`swap.parties[].originalRole`|object|父记录存在时必有|固定原岗位快照；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].originalRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].originalRole.filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].originalRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.parties[].originalRole.reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].originalRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].originalRole.total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].targetRole`|object|父记录存在时必有|目标岗位投影；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].targetRole.duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].targetRole.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.parties[].targetRole.roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`swap.parties[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`swap.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`swap.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`swap.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`swap.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "swap": {
      "id": "role_swap_example_1",
      "projectId": "project_example_1",
      "projectName": "课程协作应用",
      "authorId": "user_example_1",
      "reason": "协商调整岗位",
      "parties": [
        {
          "userId": "user_example_2",
          "displayName": "示例队员",
          "membershipId": "membership_example_2",
          "membershipRevision": 1,
          "originalRole": {
            "id": "role_example_1",
            "roleName": "客户端开发",
            "duty": "完成App和联调",
            "total": 2,
            "filled": 1,
            "reserved": 0
          },
          "targetRole": {
            "id": "role_example_2",
            "roleName": "服务器开发",
            "duty": "实现API"
          }
        },
        {
          "userId": "user_example_3",
          "displayName": "示例队员",
          "membershipId": "membership_example_3",
          "membershipRevision": 1,
          "originalRole": {
            "id": "role_example_2",
            "roleName": "服务器开发",
            "duty": "实现API"
          },
          "targetRole": {
            "id": "role_example_1",
            "roleName": "客户端开发",
            "duty": "完成App和联调"
          }
        }
      ],
      "confirmations": [],
      "status": "WITHDRAWN",
      "revision": 2,
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "projectRevision": 1,
    "availability": "READY",
    "myConfirmed": false,
    "canConfirm": false,
    "canDecline": false,
    "canWithdraw": false,
    "canOpenProject": true,
    "canAccept": false
  }
}
```

<a id="response-r-task-01"></a>

### R_TASK_01 — 读取 · 项目 / 任务看板

适用接口：[TASK-01](#api-task-01)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`members`|object[]|必有|按权限可见的正式成员投影；遵守本字段类型、统一上限及对应业务条件|
|`members[].color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`members[].colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`members[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`members[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`members[].joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`members[].membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`members[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`members[].projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`members[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`members[].roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`members[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`members[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectRevision`|int|必有|本次项目来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`stages`|object[]|必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`tasks`|object[]|必有|tasks：所属响应结构中的有序记录集合；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].agreementId`|string|本任务mode=ASSESSED时必有|当前有效/待确认正式约定ID；普通任务不据此创建正式责任；需核对原约定与本人确认|
|`tasks[].agreementStatus`|string|本任务mode=ASSESSED时必有|当前正式约定状态；普通任务不据此创建正式责任；需核对原约定与本人确认|
|`tasks[].agreementVersion`|int|本任务mode=ASSESSED时必有|当前正式约定版本；普通任务不据此创建正式责任；需核对原约定与本人确认|
|`tasks[].claimable`|bool|父记录存在时必有|当前任务能否进入认领操作；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].dependencies`|object[]|父记录存在时必有|同项目任务依赖关系；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].dependencies[].gate`|string|父记录存在时必有|前置门槛种类；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].dependencies[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`tasks[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].ownerName`|string|父记录存在时必有|当前责任人显示名，未设置为空或未认领显示文案；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].reviewerAssignment`|object|父记录存在时必有|当前接受的验收人关系；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].reviewerAssignment.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].reviewerAssignment.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].reviewerAssignment.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].reviewerAssignment.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].reviewerAssignment.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`tasks[].stageId`|string|父记录存在时必有|当前结构固定的stage资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units`|object[]|父记录存在时必有|正式责任分项；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].units[].acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].units[].acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].units[].acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`tasks[].units[].acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].units[].acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`tasks[].units[].acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].units[].contributionConfirmations`|object[]|父记录存在时必有|本人对验收份额的确认集合；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].contributionConfirmations[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].units[].contributionConfirmations[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`tasks[].units[].contributionConfirmations[].statement`|string|父记录存在时必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].contributionConfirmations[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].units[].contributionConfirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].units[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].units[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`tasks[].units[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].units[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`tasks[].units[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`tasks[].units[].shares`|object[]|父记录存在时必有|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].shares[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].shares[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].shares[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`tasks[].units[].shares[].responsibility`|string|父记录存在时必有|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`tasks[].units[].shares[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`tasks[].units[].status`|string|正式分项存在时必有|本正式分项的TODO/REVIEW_PENDING/PASSED/RETURNED状态；分项PASSED与整任务DONE分开，任务按全部分项实际结果派生|
|`tasks[].units[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "projectRevision": 1,
    "stages": [
      {
        "id": "stage_example_1",
        "name": "开发"
      }
    ],
    "tasks": [
      {
        "id": "task_example_1",
        "projectId": "project_example_1",
        "title": "登录页面",
        "description": "实现会话登录与退出",
        "mode": "ORDINARY",
        "stageId": "stage_example_1",
        "ownerId": null,
        "ownerName": "未认领",
        "status": "TODO",
        "dueAt": "2026-11-09T15:59:59Z",
        "criteria": [
          {
            "id": "criterion_example_1",
            "text": "可以演示登录和退出"
          }
        ],
        "units": [],
        "dependencies": [],
        "reviewerAssignment": {
          "userId": "user_example_reviewer",
          "displayName": "示例验收人",
          "kind": "INDEPENDENT",
          "status": "ACCEPTED",
          "version": 1
        },
        "revision": 1,
        "claimable": true
      }
    ],
    "members": [
      {
        "id": "membership_example_1",
        "membershipId": "membership_example_1",
        "projectId": "project_example_1",
        "userId": "user_example_1",
        "displayName": "示例学生",
        "roleSlotId": "role_example_1",
        "projectRole": "LEADER",
        "status": "ACTIVE",
        "color": "#236B56",
        "colorRevision": 0,
        "joinedAt": "2026-11-02T08:00:00Z",
        "revision": 1
      }
    ]
  }
}
```

<a id="response-r-task-02"></a>

### R_TASK_02 — 读取 · 项目 / 任务

适用接口：[TASK-02](#api-task-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].agreementId`|string|本任务mode=ASSESSED时必有|当前有效/待确认正式约定ID；普通任务不据此创建正式责任；需核对原约定与本人确认|
|`items[].agreementStatus`|string|本任务mode=ASSESSED时必有|当前正式约定状态；普通任务不据此创建正式责任；需核对原约定与本人确认|
|`items[].agreementVersion`|int|本任务mode=ASSESSED时必有|当前正式约定版本；普通任务不据此创建正式责任；需核对原约定与本人确认|
|`items[].claimable`|bool|父记录存在时必有|当前任务能否进入认领操作；遵守本字段类型、统一上限及对应业务条件|
|`items[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`items[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].dependencies`|object[]|父记录存在时必有|同项目任务依赖关系；遵守本字段类型、统一上限及对应业务条件|
|`items[].dependencies[].gate`|string|父记录存在时必有|前置门槛种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].dependencies[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`items[].ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ownerName`|string|父记录存在时必有|当前责任人显示名，未设置为空或未认领显示文案；遵守本字段类型、统一上限及对应业务条件|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].reviewerAssignment`|object|父记录存在时必有|当前接受的验收人关系；遵守本字段类型、统一上限及对应业务条件|
|`items[].reviewerAssignment.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].reviewerAssignment.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].reviewerAssignment.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].reviewerAssignment.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].reviewerAssignment.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].stageId`|string|父记录存在时必有|当前结构固定的stage资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].units`|object[]|父记录存在时必有|正式责任分项；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].units[].acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].units[].acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].units[].acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].units[].acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].units[].acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].units[].acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].units[].contributionConfirmations`|object[]|父记录存在时必有|本人对验收份额的确认集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].contributionConfirmations[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].units[].contributionConfirmations[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].units[].contributionConfirmations[].statement`|string|父记录存在时必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].contributionConfirmations[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].units[].contributionConfirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].units[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].units[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].units[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].units[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`items[].units[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].units[].shares`|object[]|父记录存在时必有|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].shares[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].shares[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].shares[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`items[].units[].shares[].responsibility`|string|父记录存在时必有|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`items[].units[].shares[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].units[].status`|string|正式分项存在时必有|本正式分项的TODO/REVIEW_PENDING/PASSED/RETURNED状态；分项PASSED与整任务DONE分开，任务按全部分项实际结果派生|
|`items[].units[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "task_example_1",
        "projectId": "project_example_1",
        "title": "登录页面",
        "description": "实现会话登录与退出",
        "mode": "ORDINARY",
        "stageId": "stage_example_1",
        "ownerId": null,
        "ownerName": "未认领",
        "status": "TODO",
        "dueAt": "2026-11-09T15:59:59Z",
        "criteria": [
          {
            "id": "criterion_example_1",
            "text": "可以演示登录和退出"
          }
        ],
        "units": [],
        "dependencies": [],
        "reviewerAssignment": {
          "userId": "user_example_reviewer",
          "displayName": "示例验收人",
          "kind": "INDEPENDENT",
          "status": "ACCEPTED",
          "version": 1
        },
        "revision": 1,
        "claimable": true
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-task-03"></a>

### R_TASK_03 — 读取 · 任务

适用接口：[TASK-03](#api-task-03)、[TASK-04](#api-task-04)、[TASK-06](#api-task-06)、[TASK-07](#api-task-07)、[TASK-10](#api-task-10)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`agreementId`|string|本任务mode=ASSESSED时必有|当前有效/待确认正式约定ID；普通任务不据此创建正式责任；需核对原约定与本人确认|
|`agreementStatus`|string|本任务mode=ASSESSED时必有|当前正式约定状态；普通任务不据此创建正式责任；需核对原约定与本人确认|
|`agreementVersion`|int|本任务mode=ASSESSED时必有|当前正式约定版本；普通任务不据此创建正式责任；需核对原约定与本人确认|
|`claimable`|bool|必有|当前任务能否进入认领操作；遵守本字段类型、统一上限及对应业务条件|
|`criteria`|object[]|必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`dependencies`|object[]|必有|同项目任务依赖关系；遵守本字段类型、统一上限及对应业务条件|
|`dependencies[].gate`|string|父记录存在时必有|前置门槛种类；遵守本字段类型、统一上限及对应业务条件|
|`dependencies[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`description`|string|必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`dueAt`|string|必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`mode`|string|必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ownerName`|string|必有|当前责任人显示名，未设置为空或未认领显示文案；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reviewerAssignment`|object|必有|当前接受的验收人关系；遵守本字段类型、统一上限及对应业务条件|
|`reviewerAssignment.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`reviewerAssignment.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`reviewerAssignment.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`reviewerAssignment.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reviewerAssignment.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`stageId`|string|必有|当前结构固定的stage资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`units`|object[]|必有|正式责任分项；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`units[].acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`units[].acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].contributionConfirmations`|object[]|父记录存在时必有|本人对验收份额的确认集合；遵守本字段类型、统一上限及对应业务条件|
|`units[].contributionConfirmations[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].contributionConfirmations[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`units[].contributionConfirmations[].statement`|string|父记录存在时必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`units[].contributionConfirmations[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].contributionConfirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`units[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`units[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`units[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`units[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`units[].shares`|object[]|父记录存在时必有|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`units[].shares[].responsibility`|string|父记录存在时必有|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].status`|string|正式分项存在时必有|本正式分项的TODO/REVIEW_PENDING/PASSED/RETURNED状态；分项PASSED与整任务DONE分开，任务按全部分项实际结果派生|
|`units[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "task_example_1",
    "projectId": "project_example_1",
    "title": "登录页面",
    "description": "实现会话登录与退出",
    "mode": "ORDINARY",
    "stageId": "stage_example_1",
    "ownerId": null,
    "ownerName": "未认领",
    "status": "TODO",
    "dueAt": "2026-11-09T15:59:59Z",
    "criteria": [
      {
        "id": "criterion_example_1",
        "text": "可以演示登录和退出"
      }
    ],
    "units": [],
    "dependencies": [],
    "reviewerAssignment": {
      "userId": "user_example_reviewer",
      "displayName": "示例验收人",
      "kind": "INDEPENDENT",
      "status": "ACCEPTED",
      "version": 1
    },
    "revision": 1,
    "claimable": true
  }
}
```

<a id="response-r-task-05"></a>

### R_TASK_05 — 提交 · 项目 / 任务

适用接口：[TASK-05](#api-task-05)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`agreementId`|string|本任务mode=ASSESSED时必有|当前有效/待确认正式约定ID；普通任务不据此创建正式责任；需核对原约定与本人确认|
|`agreementStatus`|string|本任务mode=ASSESSED时必有|当前正式约定状态；普通任务不据此创建正式责任；需核对原约定与本人确认|
|`agreementVersion`|int|本任务mode=ASSESSED时必有|当前正式约定版本；普通任务不据此创建正式责任；需核对原约定与本人确认|
|`claimable`|bool|必有|当前任务能否进入认领操作；遵守本字段类型、统一上限及对应业务条件|
|`criteria`|object[]|必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`dependencies`|object[]|必有|同项目任务依赖关系；遵守本字段类型、统一上限及对应业务条件|
|`dependencies[].gate`|string|父记录存在时必有|前置门槛种类；遵守本字段类型、统一上限及对应业务条件|
|`dependencies[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`description`|string|必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`dueAt`|string|必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`mode`|string|必有|当前业务模式；ORDINARY/ASSESSED，普通dueAt+criteria，正式assessedPlan，禁止把两种结构混为同一交付|
|`ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ownerName`|string|必有|当前责任人显示名，未设置为空或未认领显示文案；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reviewerAssignment`|object|必有|当前接受的验收人关系；遵守本字段类型、统一上限及对应业务条件|
|`reviewerAssignment.displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`reviewerAssignment.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`reviewerAssignment.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`reviewerAssignment.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reviewerAssignment.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`stageId`|string|必有|当前结构固定的stage资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`units`|object[]|必有|正式责任分项；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`units[].acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`units[].acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].contributionConfirmations`|object[]|父记录存在时必有|本人对验收份额的确认集合；遵守本字段类型、统一上限及对应业务条件|
|`units[].contributionConfirmations[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].contributionConfirmations[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`units[].contributionConfirmations[].statement`|string|父记录存在时必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`units[].contributionConfirmations[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].contributionConfirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`units[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`units[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`units[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`units[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`units[].shares`|object[]|父记录存在时必有|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`units[].shares[].responsibility`|string|父记录存在时必有|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].status`|string|正式分项存在时必有|本正式分项的TODO/REVIEW_PENDING/PASSED/RETURNED状态；分项PASSED与整任务DONE分开，任务按全部分项实际结果派生|
|`units[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "task_example_1",
    "projectId": "project_example_1",
    "title": "登录页面",
    "description": "实现登录与会话",
    "mode": "ORDINARY",
    "stageId": "stage_example_1",
    "ownerId": null,
    "ownerName": "未认领",
    "status": "TODO",
    "dueAt": "2026-11-09T15:59:59Z",
    "criteria": [
      {
        "text": "可以演示登录和退出"
      }
    ],
    "units": [],
    "dependencies": [],
    "reviewerAssignment": {
      "userId": "user_example_reviewer",
      "displayName": "示例验收人",
      "kind": "INDEPENDENT",
      "status": "ACCEPTED",
      "version": 1
    },
    "revision": 1,
    "claimable": true
  }
}
```

<a id="response-r-task-08"></a>

### R_TASK_08 — 保存 · 项目 / 本人 / 本人看板颜色

适用接口：[TASK-08](#api-task-08)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`color`|string|必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "color": "#236B56",
    "revision": 2
  }
}
```

<a id="response-r-task-09"></a>

### R_TASK_09 — 保存 · 项目 / 项目阶段

适用接口：[TASK-09](#api-task-09)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`projectRevision`|int|必有|本次项目来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`stages`|object[]|必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "projectRevision": 2,
    "stages": [
      {
        "id": "stage_example_1",
        "name": "开发"
      }
    ]
  }
}
```

<a id="response-r-delivery-01"></a>

### R_DELIVERY_01 — 读取 · 正式约定

适用接口：[DELIVERY-01](#api-delivery-01)、[DELIVERY-15](#api-delivery-15)、[DELIVERY-17](#api-delivery-17)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`acknowledgements`|object[]|必有|受影响者对固定版本的本人确认记录；遵守本字段类型、统一上限及对应业务条件|
|`acknowledgements[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`acknowledgements[].decision`|string|父记录存在时必有|本人明确提交的决定；允许值：按本条业务规则列明的决定|
|`acknowledgements[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`affectedUserIds`|string[]|父记录存在时必有|服务器计算的本版受影响成员；按当前任务约定版本与本人确认返回|
|`confirmationSourceValid`|bool|父记录存在时必有|本版成员和确认来源是否仍有效；按当前任务约定版本与本人确认返回|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`memberChangeId`|string|对应详情/交接续约投影存在时返回|统筹交接续约的原人员变更ID；保持原来源、当前成员和本次约定版本|
|`ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ownerName`|string|父记录存在时必有|统筹责任人展示姓名；按当前任务约定版本与本人确认返回|
|`renewalReason`|string|对应详情/交接续约投影存在时返回|交接续约说明；保持原来源、当前成员和本次约定版本|
|`reviewerAcceptanceInherited`|bool|对应详情/交接续约投影存在时返回|既有验收接受是否在当前来源下继承；保持原来源、当前成员和本次约定版本|
|`reviewerPolicy`|object|父记录存在时必有|约定的独立验收安排；遵守本字段类型、统一上限及对应业务条件|
|`reviewerPolicy.displayName`|string|对应详情/交接续约投影存在时返回|验收人展示名；保持原来源、当前成员和本次约定版本|
|`reviewerPolicy.kind`|string|父记录存在时必有|INDEPENDENT/SELF_CHECK；按当前任务约定版本与本人确认返回|
|`reviewerPolicy.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`taskId`|string|必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`totalPoints`|string|父记录存在时必有|各分项正点数合计；按当前任务约定版本与本人确认返回|
|`units`|object[]|父记录存在时必有|正式责任分项；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`units[].acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`units[].acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].clientKey`|string|父记录存在时必有|本次输入中新增项唯一键，服务器会生成稳定id；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].contributionConfirmations`|object[]|父记录存在时必有|本人对验收份额的确认集合；遵守本字段类型、统一上限及对应业务条件|
|`units[].contributionConfirmations[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].contributionConfirmations[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`units[].contributionConfirmations[].statement`|string|父记录存在时必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`units[].contributionConfirmations[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].contributionConfirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`units[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`units[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`units[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`units[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`units[].shares`|object[]|父记录存在时必有|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`units[].shares[].responsibility`|string|父记录存在时必有|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].status`|string|正式分项存在时必有|本正式分项的TODO/REVIEW_PENDING/PASSED/RETURNED状态；分项PASSED与整任务DONE分开，任务按全部分项实际结果派生|
|`units[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`version`|int|必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "agreement_example_1",
    "taskId": "task_example_1",
    "version": 1,
    "status": "PENDING",
    "acknowledgements": [],
    "revision": 1,
    "createdAt": "2026-11-02T08:00:00Z",
    "ownerId": "user_example_1",
    "dueAt": "2026-11-09T15:59:59Z",
    "units": [
      {
        "clientKey": "unit_1",
        "title": "登录页面",
        "points": "10.00",
        "shares": [
          {
            "userId": "user_example_1",
            "points": "10.00",
            "responsibility": "实现登录功能",
            "accepted": false
          }
        ],
        "criteria": [
          {
            "text": "可以演示登录和退出",
            "id": "criterion_example_1_1"
          }
        ],
        "id": "unit_example_1",
        "dueAt": "2026-11-09T15:59:59Z",
        "revision": 1,
        "status": "TODO",
        "contributionConfirmations": [],
        "acceptance": {}
      }
    ],
    "reviewerPolicy": {
      "userId": "user_example_3",
      "kind": "INDEPENDENT"
    },
    "affectedUserIds": [
      "user_example_1"
    ],
    "totalPoints": "10.00",
    "confirmationSourceValid": true,
    "ownerName": "示例组长"
  }
}
```

<a id="response-r-delivery-02"></a>

### R_DELIVERY_02 — 读取 · 内部调整

适用接口：[DELIVERY-02](#api-delivery-02)、[DELIVERY-08](#api-delivery-08)、[DELIVERY-10](#api-delivery-10)、[DELIVERY-16](#api-delivery-16)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`affectedUserIds`|string[]|必有|服务器计算的受影响者集合；遵守本字段类型、统一上限及对应业务条件|
|`baseAgreementVersion`|int|必有|提案所基于的约定版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`canManage`|bool|必有|当前调用者是否可管理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`changes`|object[]|必有|当前有权的旧消息变更投影；遵守本字段类型、统一上限及对应业务条件|
|`changes[].baseUnitRevision`|int|父记录存在时必有|被调整分项的来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`changes[].fields`|object|父记录存在时必有|该次实际有变化的允许字段；遵守本字段类型、统一上限及对应业务条件|
|`changes[].fields.title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`changes[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`confirmations`|object[]|必有|当事人接受记录；遵守本字段类型、统一上限及对应业务条件|
|`confirmations[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`confirmations[].decision`|string|父记录存在时必有|本人明确提交的决定；允许值：按本条业务规则列明的决定|
|`confirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`opinions`|object[]|必有|调整意见、协商回应与确认记录；遵守本字段类型、统一上限及对应业务条件|
|`opinions[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`opinions[].comment`|string|父记录存在时必有|本人意见；可省略，默认空，岗位/约定回应最多500|
|`opinions[].decision`|string|父记录存在时必有|本人明确提交的决定；允许值：按本条业务规则列明的决定|
|`opinions[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`opinions[].proposalText`|string|父记录存在时必有|proposalText：所属响应结构中的字符串值；遵守本字段类型、统一上限及对应业务条件|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceValid`|bool|必有|原业务来源是否仍允许当前动作；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`taskId`|string|必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "proposal_example_1",
    "taskId": "task_example_1",
    "baseAgreementVersion": 1,
    "changes": [
      {
        "unitId": "unit_example_1",
        "baseUnitRevision": 1,
        "fields": {
          "title": "登录与会话页面"
        }
      }
    ],
    "reason": "补充会话处理范围",
    "affectedUserIds": [
      "user_example_1"
    ],
    "confirmations": [],
    "opinions": [],
    "status": "PENDING",
    "revision": 1,
    "sourceValid": true,
    "canManage": true
  }
}
```

<a id="response-r-delivery-03"></a>

### R_DELIVERY_03 — 读取 · 任务 / 内部调整

适用接口：[DELIVERY-03](#api-delivery-03)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].affectedUserIds`|string[]|父记录存在时必有|服务器计算的受影响者集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].baseAgreementVersion`|int|父记录存在时必有|提案所基于的约定版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`items[].canManage`|bool|父记录存在时必有|当前调用者是否可管理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].changes`|object[]|父记录存在时必有|当前有权的旧消息变更投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].changes[].baseUnitRevision`|int|父记录存在时必有|被调整分项的来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].changes[].fields`|object|父记录存在时必有|该次实际有变化的允许字段；遵守本字段类型、统一上限及对应业务条件|
|`items[].changes[].fields.title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].changes[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].confirmations`|object[]|父记录存在时必有|当事人接受记录；遵守本字段类型、统一上限及对应业务条件|
|`items[].confirmations[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].confirmations[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`items[].confirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].opinions`|object[]|父记录存在时必有|调整意见、协商回应与确认记录；遵守本字段类型、统一上限及对应业务条件|
|`items[].opinions[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].opinions[].comment`|string|父记录存在时必有|本人意见；可省略，默认空，岗位/约定回应最多500|
|`items[].opinions[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`items[].opinions[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].opinions[].proposalText`|string|父记录存在时必有|proposalText：所属响应结构中的字符串值；遵守本字段类型、统一上限及对应业务条件|
|`items[].reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].sourceValid`|bool|父记录存在时必有|原业务来源是否仍允许当前动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "proposal_example_1",
        "taskId": "task_example_1",
        "baseAgreementVersion": 1,
        "changes": [
          {
            "unitId": "unit_example_1",
            "baseUnitRevision": 1,
            "fields": {
              "title": "登录与会话页面"
            }
          }
        ],
        "reason": "补充会话处理范围",
        "affectedUserIds": [
          "user_example_1"
        ],
        "confirmations": [],
        "opinions": [],
        "status": "PENDING",
        "revision": 1,
        "sourceValid": true,
        "canManage": true
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-delivery-04"></a>

### R_DELIVERY_04 — 读取 · 任务 / 本人 / 私有草稿

适用接口：[DELIVERY-04](#api-delivery-04)、[DELIVERY-21](#api-delivery-21)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`artifacts`|object[]|必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`artifacts[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`savedAt`|string|必有|服务器保存时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`summary`|string|必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`taskId`|string|必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`unitIds`|string[]|必有|本人本次选定的正式分项ID集合；遵守本字段类型、统一上限及对应业务条件|
|`userId`|string|必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "draft_example_1",
    "taskId": "task_example_1",
    "userId": "user_example_1",
    "summary": "已完成登录页面",
    "artifacts": [
      {
        "kind": "LINK",
        "url": "https://example.invalid/demo",
        "title": "示例演示材料"
      }
    ],
    "unitIds": [],
    "status": "SAVED",
    "savedAt": "2026-11-02T08:00:00Z",
    "revision": 1
  }
}
```

<a id="response-r-delivery-05"></a>

### R_DELIVERY_05 — 读取 · 任务 / 提交版本

适用接口：[DELIVERY-05](#api-delivery-05)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].artifacts`|object[]|父记录存在时必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`items[].artifacts[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`items[].artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`items[].artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`items[].criteriaSnapshot`|object[]|父记录存在时必有|提交时固定的标准快照；遵守本字段类型、统一上限及对应业务条件|
|`items[].criteriaSnapshot[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].criteriaSnapshot[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].dueSnapshot`|string|父记录存在时必有|本次提交固定的截止快照；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].sequence`|int|父记录存在时必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].submittedAt`|string|父记录存在时必有|服务器接收提交时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].submittedBy`|string|父记录存在时必有|本次实际提交人ID；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`items[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].unitIds`|string[]|父记录存在时必有|本人本次选定的正式分项ID集合；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "submission_example_1",
        "taskId": "task_example_1",
        "sequence": 1,
        "submittedBy": "user_example_1",
        "summary": "已完成登录与退出",
        "artifacts": [
          {
            "kind": "LINK",
            "url": "https://example.invalid/demo",
            "title": "示例演示材料"
          }
        ],
        "unitIds": [],
        "submittedAt": "2026-11-02T08:00:00Z",
        "dueSnapshot": "2026-11-09T15:59:59Z",
        "criteriaSnapshot": [
          {
            "id": "criterion_example_1",
            "text": "可以演示登录和退出"
          }
        ],
        "status": "PENDING",
        "revision": 1
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-delivery-06"></a>

### R_DELIVERY_06 — 提交 · 正式约定 / 本人回应

适用接口：[DELIVERY-06](#api-delivery-06)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`acknowledgements`|object[]|必有|受影响者对固定版本的本人确认记录；遵守本字段类型、统一上限及对应业务条件|
|`acknowledgements[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`acknowledgements[].decision`|string|父记录存在时必有|本人明确提交的决定；允许值：ACCEPT/REQUEST_CHANGE|
|`acknowledgements[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`affectedUserIds`|string[]|父记录存在时必有|服务器计算的本版受影响成员；按当前任务约定版本与本人确认返回|
|`confirmationSourceValid`|bool|父记录存在时必有|本版成员和确认来源是否仍有效；按当前任务约定版本与本人确认返回|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`memberChangeId`|string|对应详情/交接续约投影存在时返回|统筹交接续约的原人员变更ID；保持原来源、当前成员和本次约定版本|
|`ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ownerName`|string|父记录存在时必有|统筹责任人展示姓名；按当前任务约定版本与本人确认返回|
|`renewalReason`|string|对应详情/交接续约投影存在时返回|交接续约说明；保持原来源、当前成员和本次约定版本|
|`reviewerAcceptanceInherited`|bool|对应详情/交接续约投影存在时返回|既有验收接受是否在当前来源下继承；保持原来源、当前成员和本次约定版本|
|`reviewerPolicy`|object|父记录存在时必有|约定的独立验收安排；遵守本字段类型、统一上限及对应业务条件|
|`reviewerPolicy.displayName`|string|对应详情/交接续约投影存在时返回|验收人展示名；保持原来源、当前成员和本次约定版本|
|`reviewerPolicy.kind`|string|父记录存在时必有|INDEPENDENT/SELF_CHECK；按当前任务约定版本与本人确认返回|
|`reviewerPolicy.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`taskId`|string|必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`totalPoints`|string|父记录存在时必有|各分项正点数合计；按当前任务约定版本与本人确认返回|
|`units`|object[]|父记录存在时必有|正式责任分项；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`units[].acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`units[].acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`units[].acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].clientKey`|string|父记录存在时必有|本次输入中新增项唯一键，服务器会生成稳定id；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].contributionConfirmations`|object[]|父记录存在时必有|本人对验收份额的确认集合；遵守本字段类型、统一上限及对应业务条件|
|`units[].contributionConfirmations[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].contributionConfirmations[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`units[].contributionConfirmations[].statement`|string|父记录存在时必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`units[].contributionConfirmations[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].contributionConfirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`units[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`units[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`units[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`units[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`units[].shares`|object[]|父记录存在时必有|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`units[].shares[].responsibility`|string|父记录存在时必有|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`units[].shares[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`units[].status`|string|正式分项存在时必有|本正式分项的TODO/REVIEW_PENDING/PASSED/RETURNED状态；分项PASSED与整任务DONE分开，任务按全部分项实际结果派生|
|`units[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`version`|int|必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "agreement_example_1",
    "taskId": "task_example_1",
    "version": 1,
    "status": "ACTIVE",
    "acknowledgements": [],
    "revision": 2,
    "createdAt": "2026-11-02T08:00:00Z",
    "ownerId": "user_example_1",
    "dueAt": "2026-11-09T15:59:59Z",
    "units": [
      {
        "clientKey": "unit_1",
        "title": "登录页面",
        "points": "10.00",
        "shares": [
          {
            "userId": "user_example_1",
            "points": "10.00",
            "responsibility": "实现登录功能",
            "accepted": true
          }
        ],
        "criteria": [
          {
            "text": "可以演示登录和退出",
            "id": "criterion_example_1_1"
          }
        ],
        "id": "unit_example_1",
        "dueAt": "2026-11-09T15:59:59Z",
        "revision": 1,
        "status": "TODO",
        "contributionConfirmations": [],
        "acceptance": {}
      }
    ],
    "reviewerPolicy": {
      "userId": "user_example_3",
      "kind": "INDEPENDENT"
    },
    "affectedUserIds": [
      "user_example_1"
    ],
    "totalPoints": "10.00",
    "confirmationSourceValid": true,
    "ownerName": "示例组长"
  }
}
```

<a id="response-r-delivery-07"></a>

### R_DELIVERY_07 — 提交 · 内部调整 / 意见确认

适用接口：[DELIVERY-07](#api-delivery-07)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`affectedUserIds`|string[]|必有|服务器计算的受影响者集合；遵守本字段类型、统一上限及对应业务条件|
|`baseAgreementVersion`|int|必有|提案所基于的约定版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`canManage`|bool|必有|当前调用者是否可管理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`changes`|object[]|必有|当前有权的旧消息变更投影；遵守本字段类型、统一上限及对应业务条件|
|`changes[].baseUnitRevision`|int|父记录存在时必有|被调整分项的来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`changes[].fields`|object|父记录存在时必有|该次实际有变化的允许字段；遵守本字段类型、统一上限及对应业务条件|
|`changes[].fields.title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`changes[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`confirmations`|object[]|必有|当事人接受记录；遵守本字段类型、统一上限及对应业务条件|
|`confirmations[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`confirmations[].decision`|string|父记录存在时必有|本人明确提交的决定；允许值：AGREE/CONTINUE|
|`confirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`opinions`|object[]|必有|调整意见、协商回应与确认记录；遵守本字段类型、统一上限及对应业务条件|
|`opinions[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`opinions[].comment`|string|父记录存在时必有|本人意见；可省略，默认空，岗位/约定回应最多500|
|`opinions[].decision`|string|父记录存在时必有|本人明确提交的决定；允许值：AGREE/CONTINUE|
|`opinions[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`opinions[].proposalText`|string|父记录存在时必有|proposalText：所属响应结构中的字符串值；遵守本字段类型、统一上限及对应业务条件|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceValid`|bool|必有|原业务来源是否仍允许当前动作；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`taskId`|string|必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "proposal_example_1",
    "taskId": "task_example_1",
    "baseAgreementVersion": 1,
    "changes": [
      {
        "unitId": "unit_example_1",
        "baseUnitRevision": 1,
        "fields": {
          "title": "登录与会话页面"
        }
      }
    ],
    "reason": "补充会话处理范围",
    "affectedUserIds": [
      "user_example_1"
    ],
    "confirmations": [],
    "opinions": [
      {
        "id": "opinion_example_1",
        "authorId": "user_example_1",
        "comment": "补充协商说明",
        "proposalText": "",
        "decision": "AGREE"
      }
    ],
    "status": "PENDING",
    "revision": 2,
    "sourceValid": true,
    "canManage": true
  }
}
```

<a id="response-r-delivery-09"></a>

### R_DELIVERY_09 — 提交 · 内部调整 / 本人回应

适用接口：[DELIVERY-09](#api-delivery-09)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`affectedUserIds`|string[]|必有|服务器计算的受影响者集合；遵守本字段类型、统一上限及对应业务条件|
|`baseAgreementVersion`|int|必有|提案所基于的约定版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`canManage`|bool|必有|当前调用者是否可管理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`changes`|object[]|必有|当前有权的旧消息变更投影；遵守本字段类型、统一上限及对应业务条件|
|`changes[].baseUnitRevision`|int|父记录存在时必有|被调整分项的来源版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`changes[].fields`|object|父记录存在时必有|该次实际有变化的允许字段；遵守本字段类型、统一上限及对应业务条件|
|`changes[].fields.title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`changes[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`confirmations`|object[]|必有|当事人接受记录；遵守本字段类型、统一上限及对应业务条件|
|`confirmations[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`confirmations[].decision`|string|父记录存在时必有|本人明确提交的决定；允许值：ACCEPT/REQUEST_CHANGE|
|`confirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`opinions`|object[]|必有|调整意见、协商回应与确认记录；遵守本字段类型、统一上限及对应业务条件|
|`opinions[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`opinions[].comment`|string|父记录存在时必有|本人意见；可省略，默认空，岗位/约定回应最多500|
|`opinions[].decision`|string|父记录存在时必有|本人明确提交的决定；允许值：ACCEPT/REQUEST_CHANGE|
|`opinions[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`opinions[].proposalText`|string|父记录存在时必有|proposalText：所属响应结构中的字符串值；遵守本字段类型、统一上限及对应业务条件|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceValid`|bool|必有|原业务来源是否仍允许当前动作；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`taskId`|string|必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "proposal_example_1",
    "taskId": "task_example_1",
    "baseAgreementVersion": 1,
    "changes": [
      {
        "unitId": "unit_example_1",
        "baseUnitRevision": 1,
        "fields": {
          "title": "登录与会话页面"
        }
      }
    ],
    "reason": "补充会话处理范围",
    "affectedUserIds": [
      "user_example_1"
    ],
    "confirmations": [
      {
        "userId": "user_example_1",
        "decision": "ACCEPT",
        "at": "2026-11-02T08:00:00Z"
      }
    ],
    "opinions": [],
    "status": "APPLIED",
    "revision": 2,
    "sourceValid": true,
    "canManage": true
  }
}
```

<a id="response-r-delivery-11"></a>

### R_DELIVERY_11 — 提交 · 独立验收邀请 / 本人回应

适用接口：[DELIVERY-11](#api-delivery-11)、[DELIVERY-18](#api-delivery-18)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`arrangementVersion`|int|必有|本结构固定的arrangementVersion版本，不能与对象revision互换；遵守本字段类型、统一上限及对应业务条件|
|`expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recipientId`|string|必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`taskId`|string|必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "reviewer_invitation_example_1",
    "taskId": "task_example_1",
    "recipientId": "user_example_reviewer",
    "status": "PENDING",
    "arrangementVersion": 1,
    "revision": 2,
    "expiresAt": "2026-11-03T08:00:00Z"
  }
}
```

<a id="response-r-delivery-12"></a>

### R_DELIVERY_12 — 提交 · 提交版本 / 接受/验收

适用接口：[DELIVERY-12](#api-delivery-12)、[DELIVERY-13](#api-delivery-13)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`acceptance`|object|必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；允许值：按本条业务规则列明的决定|
|`acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission`|object|必有|本次不可覆盖提交投影；遵守本字段类型、统一上限及对应业务条件|
|`submission.acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`submission.acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`submission.acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`submission.acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission.acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`submission.acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission.acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`submission.acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission.acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`submission.acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`submission.acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission.acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`submission.acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission.artifacts`|object[]|父记录存在时必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`submission.artifacts[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`submission.artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`submission.artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`submission.artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`submission.criteriaSnapshot`|object[]|父记录存在时必有|提交时固定的标准快照；遵守本字段类型、统一上限及对应业务条件|
|`submission.criteriaSnapshot[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission.criteriaSnapshot[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`submission.dueSnapshot`|string|父记录存在时必有|本次提交固定的截止快照；遵守本字段类型、统一上限及对应业务条件|
|`submission.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`submission.sequence`|int|父记录存在时必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|
|`submission.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`submission.submittedAt`|string|父记录存在时必有|服务器接收提交时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`submission.submittedBy`|string|父记录存在时必有|本次实际提交人ID；遵守本字段类型、统一上限及对应业务条件|
|`submission.summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`submission.taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission.unitIds`|string[]|父记录存在时必有|本人本次选定的正式分项ID集合；遵守本字段类型、统一上限及对应业务条件|
|`taskRevision`|int|必有|本次任务版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`taskStatus`|string|必有|本次任务实际状态；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "acceptance": {
      "id": "acceptance_example_1",
      "submissionId": "submission_example_1",
      "reviewerId": "user_example_reviewer",
      "kind": "INDEPENDENT",
      "checks": [
        {
          "unitId": null,
          "criterionId": "criterion_example_1",
          "met": true
        }
      ],
      "reason": "",
      "decision": "PASSED",
      "acceptedAt": "2026-11-02T08:00:00Z"
    },
    "submission": {
      "id": "submission_example_1",
      "taskId": "task_example_1",
      "sequence": 1,
      "submittedBy": "user_example_1",
      "summary": "已完成登录与退出",
      "artifacts": [
        {
          "kind": "LINK",
          "url": "https://example.invalid/demo",
          "title": "示例演示材料"
        }
      ],
      "unitIds": [],
      "submittedAt": "2026-11-02T08:00:00Z",
      "dueSnapshot": "2026-11-09T15:59:59Z",
      "criteriaSnapshot": [
        {
          "id": "criterion_example_1",
          "text": "可以演示登录和退出"
        }
      ],
      "status": "REVIEWED",
      "revision": 2,
      "acceptance": {
        "id": "acceptance_example_1",
        "submissionId": "submission_example_1",
        "reviewerId": "user_example_reviewer",
        "kind": "INDEPENDENT",
        "checks": [
          {
            "unitId": null,
            "criterionId": "criterion_example_1",
            "met": true
          }
        ],
        "reason": "",
        "decision": "PASSED",
        "acceptedAt": "2026-11-02T08:00:00Z"
      }
    },
    "taskStatus": "DONE",
    "taskRevision": 2
  }
}
```

<a id="response-r-delivery-14"></a>

### R_DELIVERY_14 — 提交 · 提交版本 / 撤回

适用接口：[DELIVERY-14](#api-delivery-14)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`draft`|object|必有|本人私有草稿投影；遵守本字段类型、统一上限及对应业务条件|
|`draft.artifacts`|object[]|父记录存在时必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`draft.artifacts[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`draft.artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`draft.artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`draft.artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`draft.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`draft.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`draft.savedAt`|string|父记录存在时必有|服务器保存时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`draft.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`draft.summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`draft.taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`draft.unitIds`|string[]|父记录存在时必有|本人本次选定的正式分项ID集合；遵守本字段类型、统一上限及对应业务条件|
|`draft.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission`|object|必有|本次不可覆盖提交投影；遵守本字段类型、统一上限及对应业务条件|
|`submission.artifacts`|object[]|父记录存在时必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`submission.artifacts[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`submission.artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`submission.artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`submission.artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`submission.criteriaSnapshot`|object[]|父记录存在时必有|提交时固定的标准快照；遵守本字段类型、统一上限及对应业务条件|
|`submission.criteriaSnapshot[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission.criteriaSnapshot[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`submission.dueSnapshot`|string|父记录存在时必有|本次提交固定的截止快照；遵守本字段类型、统一上限及对应业务条件|
|`submission.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`submission.sequence`|int|父记录存在时必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|
|`submission.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`submission.submittedAt`|string|父记录存在时必有|服务器接收提交时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`submission.submittedBy`|string|父记录存在时必有|本次实际提交人ID；遵守本字段类型、统一上限及对应业务条件|
|`submission.summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`submission.taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`submission.unitIds`|string[]|父记录存在时必有|本人本次选定的正式分项ID集合；遵守本字段类型、统一上限及对应业务条件|
|`taskRevision`|int|必有|本次任务版本；整数1～999999999；仅明确允许的首次私有记录用0|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "submission": {
      "id": "submission_example_1",
      "taskId": "task_example_1",
      "sequence": 1,
      "submittedBy": "user_example_1",
      "summary": "已完成登录与退出",
      "artifacts": [
        {
          "kind": "LINK",
          "url": "https://example.invalid/demo",
          "title": "示例演示材料"
        }
      ],
      "unitIds": [],
      "submittedAt": "2026-11-02T08:00:00Z",
      "dueSnapshot": "2026-11-09T15:59:59Z",
      "criteriaSnapshot": [
        {
          "id": "criterion_example_1",
          "text": "可以演示登录和退出"
        }
      ],
      "status": "WITHDRAWN",
      "revision": 2
    },
    "draft": {
      "id": "draft_example_1",
      "taskId": "task_example_1",
      "userId": "user_example_1",
      "summary": "已完成登录页面",
      "artifacts": [
        {
          "kind": "LINK",
          "url": "https://example.invalid/demo",
          "title": "示例演示材料"
        }
      ],
      "unitIds": [],
      "status": "SAVED",
      "savedAt": "2026-11-02T08:00:00Z",
      "revision": 1
    },
    "taskRevision": 2
  }
}
```

<a id="response-r-delivery-19"></a>

### R_DELIVERY_19 — 提交 · 任务 / 提交版本

适用接口：[DELIVERY-19](#api-delivery-19)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`artifacts`|object[]|必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`artifacts[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`criteriaSnapshot`|object[]|必有|提交时固定的标准快照；遵守本字段类型、统一上限及对应业务条件|
|`criteriaSnapshot[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`criteriaSnapshot[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`dueSnapshot`|string|必有|本次提交固定的截止快照；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sequence`|int|必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`submittedAt`|string|必有|服务器接收提交时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`submittedBy`|string|必有|本次实际提交人ID；遵守本字段类型、统一上限及对应业务条件|
|`summary`|string|必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`taskId`|string|必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`unitIds`|string[]|必有|本人本次选定的正式分项ID集合；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "submission_example_1",
    "taskId": "task_example_1",
    "sequence": 1,
    "submittedBy": "user_example_1",
    "summary": "完成登录与退出",
    "artifacts": [
      {
        "kind": "LINK",
        "url": "https://example.invalid/demo",
        "title": "示例演示材料"
      }
    ],
    "unitIds": [],
    "submittedAt": "2026-11-02T08:00:00Z",
    "dueSnapshot": "2026-11-09T15:59:59Z",
    "criteriaSnapshot": [
      {
        "id": "criterion_example_1",
        "text": "可以演示登录和退出"
      }
    ],
    "status": "PENDING",
    "revision": 1
  }
}
```

<a id="response-r-delivery-20"></a>

### R_DELIVERY_20 — 提交 · 正式分项 / 本人 / 本人贡献确认

适用接口：[DELIVERY-20](#api-delivery-20)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`acceptanceId`|string|必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`statement`|string|必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`submissionId`|string|必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`userId`|string|必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "userId": "user_example_1",
    "submissionId": "submission_example_1",
    "acceptanceId": "acceptance_example_1",
    "statement": "确认本人实际完成约定份额",
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-gov-01"></a>

### R_GOV_01 — 读取 · 补充资料

适用接口：[GOV-01](#api-gov-01)、[GOV-17](#api-gov-17)、[GOV-18](#api-gov-18)、[GOV-19](#api-gov-19)、[GOV-30](#api-gov-30)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`canRespond`|bool|必有|当前调用者是否可本人回应；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canReview`|bool|必有|当前调用者是否可核验；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canWithdraw`|bool|必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`caseId`|string|必有|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`caseTitle`|string|必有|人员事项显示标题；遵守本字段类型、统一上限及对应业务条件|
|`dueAt`|string|必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`history`|object[]|父记录存在时必有|处理历史，核对与撤回分开记录；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`history[].action`|string|撤回事件必有|撤回请求事件为WITHDRAW；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`history[].at`|string|核对事件必有|服务器核对时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`history[].decision`|string|核对事件必有|ACCEPT/RETURN；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`history[].reason`|string|核对事件必有|核对理由；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`history[].responseVersion`|int|核对事件必有|本次核对的回应版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`history[].reviewedBy`|string|核对事件必有|实际核对处理者ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recipientId`|string|必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recipientName`|string|必有|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`requirements`|string|必有|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`responseVersion`|int|必有|本次补资料回应版本；遵守本字段类型、统一上限及对应业务条件|
|`responses`|object[]|父记录存在时必有|历次本人回应，旧版不可覆盖；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].authorId`|string|父记录存在时必有|回应者本人ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].content`|string|父记录存在时必有|本人提交正文；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].evidenceRefs`|object[]|父记录存在时必有|本人明确附带材料；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].evidenceRefs[].kind`|string|父记录存在时必有|LINK/FILE；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].evidenceRefs[].title`|string|父记录存在时必有|材料展示名；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].evidenceRefs[].url`|string|仅LINK必有|http/https材料链接；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].late`|bool|父记录存在时必有|按原截止是否迟交；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].review`|object|本版本已有核对时返回|对该回应版本的核对记录；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].review.at`|string|父记录存在时必有|服务器核对时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].review.decision`|string|父记录存在时必有|ACCEPT/RETURN；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].review.reason`|string|父记录存在时必有|核对理由；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].review.responseVersion`|int|父记录存在时必有|本次核对的回应版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].review.reviewedBy`|string|父记录存在时必有|实际核对处理者ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].submittedAt`|string|父记录存在时必有|实际提交时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`responses[].version`|int|父记录存在时必有|回应不可覆盖版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "material_request_example_1",
    "caseId": "case_example_1",
    "recipientId": "user_example_2",
    "recipientName": "示例队员",
    "requirements": "补充本人说明",
    "dueAt": "2026-11-09T15:59:59Z",
    "status": "OPEN",
    "responseVersion": 0,
    "responses": [],
    "caseTitle": "成员关系核对",
    "canRespond": true,
    "canReview": false,
    "canWithdraw": false,
    "revision": 1,
    "history": []
  }
}
```

<a id="response-r-gov-02"></a>

### R_GOV_02 — 读取 · 本人 / 补充资料

适用接口：[GOV-02](#api-gov-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].canRespond`|bool|父记录存在时必有|当前调用者是否可本人回应；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canReview`|bool|父记录存在时必有|当前调用者是否可核验；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canWithdraw`|bool|父记录存在时必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].caseId`|string|父记录存在时必有|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].caseTitle`|string|父记录存在时必有|人员事项显示标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].history`|object[]|父记录存在时必有|处理历史，核对与撤回分开记录；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].history[].action`|string|撤回事件必有|撤回请求事件为WITHDRAW；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].history[].at`|string|核对事件必有|服务器核对时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].history[].decision`|string|核对事件必有|ACCEPT/RETURN；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].history[].reason`|string|核对事件必有|核对理由；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].history[].responseVersion`|int|核对事件必有|本次核对的回应版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].history[].reviewedBy`|string|核对事件必有|实际核对处理者ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].recipientId`|string|父记录存在时必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].recipientName`|string|父记录存在时必有|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].requirements`|string|父记录存在时必有|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`items[].responseVersion`|int|父记录存在时必有|本次补资料回应版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].responses`|object[]|父记录存在时必有|历次本人回应，旧版不可覆盖；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].authorId`|string|父记录存在时必有|回应者本人ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].content`|string|父记录存在时必有|本人提交正文；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].evidenceRefs`|object[]|父记录存在时必有|本人明确附带材料；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].evidenceRefs[].kind`|string|父记录存在时必有|LINK/FILE；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].evidenceRefs[].title`|string|父记录存在时必有|材料展示名；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].evidenceRefs[].url`|string|仅LINK必有|http/https材料链接；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].late`|bool|父记录存在时必有|按原截止是否迟交；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].review`|object|本版本已有核对时返回|对该回应版本的核对记录；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].review.at`|string|父记录存在时必有|服务器核对时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].review.decision`|string|父记录存在时必有|ACCEPT/RETURN；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].review.reason`|string|父记录存在时必有|核对理由；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].review.responseVersion`|int|父记录存在时必有|本次核对的回应版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].review.reviewedBy`|string|父记录存在时必有|实际核对处理者ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].submittedAt`|string|父记录存在时必有|实际提交时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].responses[].version`|int|父记录存在时必有|回应不可覆盖版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "material_request_example_1",
        "caseId": "case_example_1",
        "recipientId": "user_example_2",
        "recipientName": "示例队员",
        "requirements": "补充本人说明",
        "dueAt": "2026-11-09T15:59:59Z",
        "status": "OPEN",
        "responseVersion": 0,
        "responses": [],
        "caseTitle": "成员关系核对",
        "canRespond": true,
        "canReview": false,
        "canWithdraw": false,
        "revision": 1,
        "history": []
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-gov-03"></a>

### R_GOV_03 — 读取 · 本人 / 成员关系历史

适用接口：[GOV-03](#api-gov-03)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].color`|string|父记录存在时必有|固定成员调色板中的#RRGGBB；#236B56/#355F9B/#79518C/#9C4F2E/#466E39/#82565E/#6A5C37/#3F6C73，按大写比较|
|`items[].colorRevision`|int|父记录存在时必有|本结构固定的colorRevision版本，不能与对象revision互换；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].joinedAt`|string|父记录存在时必有|服务器正式入组时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].membershipId`|string|父记录存在时必有|正式成员关系ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].projectRole`|string|父记录存在时必有|LEADER/MEMBER；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`votes`|object[]|必有|votes：所属响应结构中的有序记录集合；遵守本字段类型、统一上限及对应业务条件|
|`votes[].canCancel`|bool|父记录存在时必有|当前调用者是否可取消；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`votes[].canStatement`|bool|父记录存在时必有|当前调用者是否可提交陈述；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`votes[].canVote`|bool|父记录存在时必有|当前调用者是否可投票；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`votes[].communicationHistory`|string|仅本事项当前有权投影返回|与人员变更有关的必要沟通记录；遵守本字段类型、统一上限及对应业务条件|
|`votes[].facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`votes[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`votes[].myBallot`|object|父记录存在时必有|本人选票，不暴露其他人的私有意见；遵守本字段类型、统一上限及对应业务条件|
|`votes[].myBallot.at`|string|移出表决有记录时返回|本人选票时间；按当前权限及该版本业务范围返回|
|`votes[].myBallot.choice`|string|父记录存在时必有|仅本人的AGREE/DISAGREE/ABSTAIN选票；按当前权限及该版本业务范围返回|
|`votes[].myBallot.revision`|int|父记录存在时必有|本人选票版本，首次保存expectedRevision=0；按当前权限及该版本业务范围返回|
|`votes[].myBallot.squareId`|string|合并表决有记录时返回|本人所属课程侧；按当前权限及该版本业务范围返回|
|`votes[].myBallot.userId`|string|本人已有选票时返回|已投票的本人用户ID；不返回他人的选票记录|
|`votes[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`votes[].requiredYes`|int|父记录存在时必有|本条程序规定的通过票数；遵守本字段类型、统一上限及对应业务条件|
|`votes[].result`|string/null|必有；未结算null|表决结算PASSED/NOT_PASSED，未结算null；遵守本字段类型、统一上限及对应业务条件|
|`votes[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`votes[].rulesSnapshot`|object|父记录存在时必有|本报告固定的规则来源；遵守本字段类型、统一上限及对应业务条件|
|`votes[].rulesSnapshot.majority`|string|父记录存在时必有|是否达到本侧法定阈值；遵守本字段类型、统一上限及对应业务条件|
|`votes[].rulesSnapshot.minimumYes`|int|父记录存在时必有|该冻结名单严格过半所需票数；遵守本字段类型、统一上限及对应业务条件|
|`votes[].statement`|string|父记录存在时必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`votes[].statementEndsAt`|string|父记录存在时必有|本人陈述截止；遵守本字段类型、统一上限及对应业务条件|
|`votes[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`votes[].targetUserId`|string|父记录存在时必有|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`votes[].voteEndsAt`|string|父记录存在时必有|本次投票截止；遵守本字段类型、统一上限及对应业务条件|
|`votes[].yesCount`|int|父记录存在时必有|当前赞成票数；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "membership_example_1",
        "membershipId": "membership_example_1",
        "projectId": "project_example_1",
        "userId": "user_example_1",
        "displayName": "示例学生",
        "roleSlotId": "role_example_1",
        "projectRole": "LEADER",
        "status": "ACTIVE",
        "color": "#236B56",
        "colorRevision": 0,
        "joinedAt": "2026-11-02T08:00:00Z",
        "revision": 1
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1,
    "votes": []
  }
}
```

<a id="response-r-gov-04"></a>

### R_GOV_04 — 读取 · 本人 / 人员事项交接

适用接口：[GOV-04](#api-gov-04)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].canCancel`|bool|父记录存在时必有|当前调用者是否可取消；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canRespond`|bool|父记录存在时必有|当前调用者是否可本人回应；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].caseId`|string|父记录存在时必有|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].decisionsNeeded`|string|父记录存在时必有|接收者需决定的内容；遵守本字段类型、统一上限及对应业务条件|
|`items[].facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].recipientName`|string|父记录存在时必有|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].recipientTeacherId`|string|父记录存在时必有|本次合法接任教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].sourceCaseRevision`|int|父记录存在时必有|交接固定的原事项版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].sourceName`|string|父记录存在时必有|原来源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceUserId`|string|父记录存在时必有|原资料所属用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].sourceValid`|bool|父记录存在时必有|原业务来源是否仍允许当前动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].unresolvedQuestions`|string|父记录存在时必有|尚未解决的问题；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "personnel_handoff_example_1",
        "caseId": "case_example_1",
        "sourceUserId": "user_example_assistant",
        "recipientTeacherId": "user_example_teacher",
        "sourceCaseRevision": 1,
        "facts": "已核对当前成员关系",
        "unresolvedQuestions": "接任安排尚未完成",
        "decisionsNeeded": "请核对人员安置",
        "status": "PENDING",
        "sourceName": "示例助理",
        "recipientName": "示例教师",
        "sourceValid": true,
        "canRespond": true,
        "canCancel": false,
        "revision": 1
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-gov-05"></a>

### R_GOV_05 — 读取 · 退出转组

适用接口：[GOV-05](#api-gov-05)、[GOV-20](#api-gov-20)、[GOV-21](#api-gov-21)、[GOV-23](#api-gov-23)、[GOV-24](#api-gov-24)、[GOV-25](#api-gov-25)、[GOV-26](#api-gov-26)、[GOV-35](#api-gov-35)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`canCancelTarget`|bool|必有|当前调用者是否可取消目标邀请；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canDecide`|bool|必有|当前调用者是否可作出决定；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canInviteTarget`|bool|必有|当前调用者是否可邀请目标；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canManage`|bool|必有|当前调用者是否可管理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canRespondTarget`|bool|必有|当前调用者是否可回应目标邀请；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`handoverPlan`|object|仅本事项当前有权投影返回|组内制定且逐项本人接受的交接方案；遵守本字段类型、统一上限及对应业务条件|
|`handoverPlan.items`|object[]|父记录存在时必有|全部未完责任及必要组长接替事项；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].accepted`|bool|父记录存在时必有|接任本人是否接受当前版本；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].dueAt`|string|非LEADER条目必有|原任务截止；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].id`|string|父记录存在时必有|服务器生成的交接条目ID；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].kind`|string|父记录存在时必有|TASK/FORMAL_OWNER/LEADER；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].reason`|string|拒绝回应时返回|本人拒绝理由；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].recipientId`|string|父记录存在时必有|实际接任人本人ID；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].recipientMembershipId`|string|父记录存在时必有|接任者当前留组成员关系ID；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].recipientName`|string|父记录存在时必有|实际接任人姓名；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].respondedAt`|string|已回应时返回|本人回应时间；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].responsibility`|string|父记录存在时必有|本条明确接续责任；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].sourceTaskRevision`|int|非LEADER条目必有|原任务基准版本；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].taskId`|string|非LEADER条目必有|固定任务ID；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].taskTitle`|string|非LEADER条目必有|原任务标题；按当前权限及该版本业务范围返回|
|`handoverPlan.notes`|string|父记录存在时必有|组长安排说明；按当前权限及该版本业务范围返回|
|`handoverPlan.sourceLeaderId`|string|父记录存在时必有|方案来源组长ID；按当前权限及该版本业务范围返回|
|`handoverPlan.targetMembershipId`|string|父记录存在时必有|原待变更成员关系ID；按当前权限及该版本业务范围返回|
|`handoverPlan.version`|int|父记录存在时必有|固定交接方案版本；按当前权限及该版本业务范围返回|
|`history`|object[]|仅本事项当前有权投影返回|按当前权限返回的处理/版本历史；遵守本字段类型、统一上限及对应业务条件|
|`history[].action`|string|仅本事项当前有权投影返回|本人明确执行的动作；允许值：按本条业务规则列明的动作|
|`history[].actorId`|string|仅本事项当前有权投影返回|本事件实际操作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`history[].at`|string|仅本事项当前有权投影返回|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`history[].reason`|string|仅本事项当前有权投影返回|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`initiatorId`|string|必有|当前结构固定的initiator资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`kind`|string|必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`outstandingTasks`|object[]|仅本事项当前有权投影返回|与本次交接有关的未完责任；遵守本字段类型、统一上限及对应业务条件|
|`outstandingTasks[].id`|string|仅本事项当前有权投影返回|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`outstandingTasks[].revision`|int|仅本事项当前有权投影返回|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`outstandingTasks[].title`|string|仅本事项当前有权投影返回|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`personnelDecision`|object|必有|当前有权教师的人员前置核对，不替代组内接受；遵守本字段类型、统一上限及对应业务条件|
|`personnelDecision.allowBelowMinimum`|bool|父记录存在时必有|是否明确允许低于人数下限；按当前权限及该版本业务范围返回|
|`personnelDecision.at`|string|父记录存在时必有|服务器核对时间；按当前权限及该版本业务范围返回|
|`personnelDecision.decision`|string|父记录存在时必有|ALLOW_HANDOVER/RETURN；按当前权限及该版本业务范围返回|
|`personnelDecision.placementPlan`|string|父记录存在时必有|人员安置说明；按当前权限及该版本业务范围返回|
|`personnelDecision.reason`|string|父记录存在时必有|人员核对理由；按当前权限及该版本业务范围返回|
|`personnelDecision.teacherId`|string|父记录存在时必有|实际作人员核对的当前教师ID；按当前权限及该版本业务范围返回|
|`planValid`|bool|必有|当前交接方案及接受是否仍有效；遵守本字段类型、统一上限及对应业务条件|
|`planVersion`|int|必有|当前交接方案版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceProjectId`|string|必有|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targetInvitation`|object|必有|有条件目标接收邀请；遵守本字段类型、统一上限及对应业务条件|
|`targetInvitation.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`targetInvitation.expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`targetInvitation.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetInvitation.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`targetInvitation.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetInvitation.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`targetInvitation.recipientId`|string|父记录存在时必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetInvitation.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`targetInvitation.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetInvitation.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targetProjectId`|string/null|必有；允许null|预选的转入项目，不意味着已入组；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetProjectName`|string|必有|targetProjectName：所属响应结构中的字符串值；遵守本字段类型、统一上限及对应业务条件|
|`targetUserId`|string|必有|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetValid`|bool|必有|目标项目与席位当前是否仍有效；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "member_change_example_1",
    "projectId": "project_example_1",
    "sourceProjectId": "project_example_1",
    "squareId": "square_example_1",
    "assignmentId": "assignment_example_1",
    "targetUserId": "user_example_2",
    "kind": "EXIT",
    "reason": "调整个人安排",
    "status": "WAIT_PERSONNEL",
    "targetProjectId": null,
    "planVersion": 0,
    "handoverPlan": {},
    "personnelDecision": {},
    "history": [],
    "revision": 1,
    "createdAt": "2026-11-02T08:00:00Z",
    "initiatorId": "user_example_2",
    "planValid": false,
    "canDecide": true,
    "canManage": false,
    "outstandingTasks": [],
    "targetInvitation": {},
    "targetValid": false,
    "canInviteTarget": false,
    "canCancelTarget": false,
    "canRespondTarget": false,
    "targetProjectName": ""
  }
}
```

<a id="response-r-gov-06"></a>

### R_GOV_06 — 读取 · 人员事项

适用接口：[GOV-06](#api-gov-06)、[GOV-27](#api-gov-27)、[GOV-31](#api-gov-31)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`canHandle`|bool|必有|当前调用者是否可处理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canManageMaterials`|bool|必有|当前调用者是否可处理材料；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canRecover`|bool|必有|当前调用者是否可收回处理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`handoffs`|object[]|必有|有权人员事项交接列表；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].canCancel`|bool|父记录存在时必有|当前调用者是否可取消；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].canRespond`|bool|父记录存在时必有|当前调用者是否可本人回应；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].caseId`|string|父记录存在时必有|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`handoffs[].decisionsNeeded`|string|父记录存在时必有|接收者需决定的内容；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`handoffs[].recipientName`|string|父记录存在时必有|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].recipientTeacherId`|string|父记录存在时必有|本次合法接任教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`handoffs[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`handoffs[].sourceCaseRevision`|int|父记录存在时必有|交接固定的原事项版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`handoffs[].sourceName`|string|父记录存在时必有|原来源名称；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].sourceUserId`|string|父记录存在时必有|原资料所属用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`handoffs[].sourceValid`|bool|父记录存在时必有|原业务来源是否仍允许当前动作；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].unresolvedQuestions`|string|父记录存在时必有|尚未解决的问题；遵守本字段类型、统一上限及对应业务条件|
|`history`|object[]|仅本事项当前有权投影返回|按当前权限返回的处理/版本历史；遵守本字段类型、统一上限及对应业务条件|
|`history[].action`|string|仅本事项当前有权投影返回|本人明确执行的动作；允许值：按本条业务规则列明的动作|
|`history[].actorId`|string|仅本事项当前有权投影返回|本事件实际操作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`history[].at`|string|仅本事项当前有权投影返回|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`history[].reason`|string|仅本事项当前有权投影返回|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`materialRequests`|object[]|仅本事项当前有权投影返回|有权补资料请求列表；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].canRespond`|bool|仅本事项当前有权投影返回|当前调用者是否可本人回应；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].canReview`|bool|仅本事项当前有权投影返回|当前调用者是否可核验；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].canWithdraw`|bool|仅本事项当前有权投影返回|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].caseId`|string|仅本事项当前有权投影返回|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`materialRequests[].caseTitle`|string|仅本事项当前有权投影返回|人员事项显示标题；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].dueAt`|string|仅本事项当前有权投影返回|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`materialRequests[].history`|object[]|父记录存在时必有|处理历史，核对与撤回分开记录；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].action`|string|撤回事件必有|撤回请求事件为WITHDRAW；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].at`|string|核对事件必有|服务器核对时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].decision`|string|核对事件必有|ACCEPT/RETURN；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].reason`|string|核对事件必有|核对理由；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].responseVersion`|int|核对事件必有|本次核对的回应版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].reviewedBy`|string|核对事件必有|实际核对处理者ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].id`|string|仅本事项当前有权投影返回|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`materialRequests[].recipientId`|string|仅本事项当前有权投影返回|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`materialRequests[].recipientName`|string|仅本事项当前有权投影返回|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].requirements`|string|仅本事项当前有权投影返回|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].responseVersion`|int|仅本事项当前有权投影返回|本次补资料回应版本；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].responses`|object[]|父记录存在时必有|历次本人回应，旧版不可覆盖；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].authorId`|string|父记录存在时必有|回应者本人ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].content`|string|父记录存在时必有|本人提交正文；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs`|object[]|父记录存在时必有|本人明确附带材料；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs[].kind`|string|父记录存在时必有|LINK/FILE；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs[].title`|string|父记录存在时必有|材料展示名；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs[].url`|string|仅LINK必有|http/https材料链接；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].late`|bool|父记录存在时必有|按原截止是否迟交；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review`|object|本版本已有核对时返回|对该回应版本的核对记录；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.at`|string|父记录存在时必有|服务器核对时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.decision`|string|父记录存在时必有|ACCEPT/RETURN；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.reason`|string|父记录存在时必有|核对理由；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.responseVersion`|int|父记录存在时必有|本次核对的回应版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.reviewedBy`|string|父记录存在时必有|实际核对处理者ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].submittedAt`|string|父记录存在时必有|实际提交时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].version`|int|父记录存在时必有|回应不可覆盖版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].revision`|int|仅本事项当前有权投影返回|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`materialRequests[].status`|string|仅本事项当前有权投影返回|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ownerName`|string|必有|当前责任人显示名，未设置为空或未认领显示文案；遵守本字段类型、统一上限及对应业务条件|
|`personnelActionPending`|bool|必有|是否仍有原人员动作未完成；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`subjectUserId`|string|必有|当前结构固定的subjectUser资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`type`|string|必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "case_example_1",
    "squareId": "square_example_1",
    "assignmentId": "assignment_example_1",
    "projectId": "project_example_1",
    "subjectUserId": "user_example_2",
    "type": "MEMBERSHIP",
    "title": "成员关系核对",
    "status": "OPEN",
    "ownerId": "",
    "ownerName": "",
    "facts": "",
    "history": [],
    "personnelActionPending": true,
    "materialRequests": [],
    "handoffs": [],
    "canHandle": true,
    "canManageMaterials": false,
    "canRecover": false,
    "revision": 1
  }
}
```

<a id="response-r-gov-07"></a>

### R_GOV_07 — 读取 · 人员事项 / 接任教师候选

适用接口：[GOV-07](#api-gov-07)、[GOV-08](#api-gov-08)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].eligible`|bool|父记录存在时必有|当前是否符合候选资格；遵守本字段类型、统一上限及对应业务条件|
|`items[].schoolId`|string|父记录存在时必有|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "userId": "user_example_teacher",
        "displayName": "示例教师",
        "schoolId": "school_example_1",
        "eligible": true
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-gov-09"></a>

### R_GOV_09 — 读取 · 人员事项交接

适用接口：[GOV-09](#api-gov-09)、[GOV-29](#api-gov-29)、[GOV-33](#api-gov-33)、[GOV-34](#api-gov-34)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`canCancel`|bool|必有|当前调用者是否可取消；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canRespond`|bool|必有|当前调用者是否可本人回应；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`caseId`|string|必有|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`decisionsNeeded`|string|必有|接收者需决定的内容；遵守本字段类型、统一上限及对应业务条件|
|`facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`recipientName`|string|必有|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`recipientTeacherId`|string|必有|本次合法接任教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceCaseRevision`|int|必有|交接固定的原事项版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceName`|string|必有|原来源名称；遵守本字段类型、统一上限及对应业务条件|
|`sourceUserId`|string|必有|原资料所属用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`sourceValid`|bool|必有|原业务来源是否仍允许当前动作；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`unresolvedQuestions`|string|必有|尚未解决的问题；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "personnel_handoff_example_1",
    "caseId": "case_example_1",
    "sourceUserId": "user_example_assistant",
    "recipientTeacherId": "user_example_teacher",
    "sourceCaseRevision": 1,
    "facts": "已核对当前成员关系",
    "unresolvedQuestions": "接任安排尚未完成",
    "decisionsNeeded": "请核对人员安置",
    "status": "PENDING",
    "sourceName": "示例助理",
    "recipientName": "示例教师",
    "sourceValid": true,
    "canRespond": true,
    "canCancel": false,
    "revision": 1
  }
}
```

<a id="response-r-gov-10"></a>

### R_GOV_10 — 读取 · 项目 / 待转入邀请

适用接口：[GOV-10](#api-gov-10)、[GOV-11](#api-gov-11)、[GOV-15](#api-gov-15)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].canCancelTarget`|bool|父记录存在时必有|当前调用者是否可取消目标邀请；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canDecide`|bool|父记录存在时必有|当前调用者是否可作出决定；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canInviteTarget`|bool|父记录存在时必有|当前调用者是否可邀请目标；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canManage`|bool|父记录存在时必有|当前调用者是否可管理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canRespondTarget`|bool|父记录存在时必有|当前调用者是否可回应目标邀请；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].handoverPlan`|object|仅本事项当前有权投影返回|组内制定且逐项本人接受的交接方案；遵守本字段类型、统一上限及对应业务条件|
|`items[].handoverPlan.items`|object[]|父记录存在时必有|全部未完责任及必要组长接替事项；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].accepted`|bool|父记录存在时必有|接任本人是否接受当前版本；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].dueAt`|string|非LEADER条目必有|原任务截止；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].id`|string|父记录存在时必有|服务器生成的交接条目ID；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].kind`|string|父记录存在时必有|TASK/FORMAL_OWNER/LEADER；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].reason`|string|拒绝回应时返回|本人拒绝理由；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].recipientId`|string|父记录存在时必有|实际接任人本人ID；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].recipientMembershipId`|string|父记录存在时必有|接任者当前留组成员关系ID；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].recipientName`|string|父记录存在时必有|实际接任人姓名；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].respondedAt`|string|已回应时返回|本人回应时间；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].responsibility`|string|父记录存在时必有|本条明确接续责任；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].sourceTaskRevision`|int|非LEADER条目必有|原任务基准版本；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].taskId`|string|非LEADER条目必有|固定任务ID；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.items[].taskTitle`|string|非LEADER条目必有|原任务标题；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.notes`|string|父记录存在时必有|组长安排说明；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.sourceLeaderId`|string|父记录存在时必有|方案来源组长ID；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.targetMembershipId`|string|父记录存在时必有|原待变更成员关系ID；按当前权限及该版本业务范围返回|
|`items[].handoverPlan.version`|int|父记录存在时必有|固定交接方案版本；按当前权限及该版本业务范围返回|
|`items[].history`|object[]|仅本事项当前有权投影返回|按当前权限返回的处理/版本历史；遵守本字段类型、统一上限及对应业务条件|
|`items[].history[].action`|string|仅本事项当前有权投影返回|本人明确执行的动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].history[].actorId`|string|仅本事项当前有权投影返回|本事件实际操作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].history[].at`|string|仅本事项当前有权投影返回|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].history[].reason`|string|仅本事项当前有权投影返回|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].initiatorId`|string|父记录存在时必有|当前结构固定的initiator资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].outstandingTasks`|object[]|仅本事项当前有权投影返回|与本次交接有关的未完责任；遵守本字段类型、统一上限及对应业务条件|
|`items[].outstandingTasks[].id`|string|仅本事项当前有权投影返回|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].outstandingTasks[].revision`|int|仅本事项当前有权投影返回|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].outstandingTasks[].title`|string|仅本事项当前有权投影返回|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].personnelDecision`|object|父记录存在时必有|当前有权教师的人员前置核对，不替代组内接受；遵守本字段类型、统一上限及对应业务条件|
|`items[].personnelDecision.allowBelowMinimum`|bool|父记录存在时必有|是否明确允许低于人数下限；按当前权限及该版本业务范围返回|
|`items[].personnelDecision.at`|string|父记录存在时必有|服务器核对时间；按当前权限及该版本业务范围返回|
|`items[].personnelDecision.decision`|string|父记录存在时必有|ALLOW_HANDOVER/RETURN；按当前权限及该版本业务范围返回|
|`items[].personnelDecision.placementPlan`|string|父记录存在时必有|人员安置说明；按当前权限及该版本业务范围返回|
|`items[].personnelDecision.reason`|string|父记录存在时必有|人员核对理由；按当前权限及该版本业务范围返回|
|`items[].personnelDecision.teacherId`|string|父记录存在时必有|实际作人员核对的当前教师ID；按当前权限及该版本业务范围返回|
|`items[].planValid`|bool|父记录存在时必有|当前交接方案及接受是否仍有效；遵守本字段类型、统一上限及对应业务条件|
|`items[].planVersion`|int|父记录存在时必有|当前交接方案版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].sourceProjectId`|string|父记录存在时必有|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].targetInvitation`|object|父记录存在时必有|有条件目标接收邀请；遵守本字段类型、统一上限及对应业务条件|
|`items[].targetInvitation.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].targetInvitation.expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].targetInvitation.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].targetInvitation.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].targetInvitation.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].targetInvitation.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].targetInvitation.recipientId`|string|父记录存在时必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].targetInvitation.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].targetInvitation.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].targetInvitation.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].targetProjectId`|string/null|必有；允许null|预选的转入项目，不意味着已入组；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].targetProjectName`|string|父记录存在时必有|targetProjectName：所属响应结构中的字符串值；遵守本字段类型、统一上限及对应业务条件|
|`items[].targetUserId`|string|父记录存在时必有|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].targetValid`|bool|父记录存在时必有|目标项目与席位当前是否仍有效；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "member_change_example_1",
        "projectId": "project_example_1",
        "sourceProjectId": "project_example_1",
        "squareId": "square_example_1",
        "assignmentId": "assignment_example_1",
        "targetUserId": "user_example_2",
        "kind": "EXIT",
        "reason": "调整个人安排",
        "status": "WAIT_PERSONNEL",
        "targetProjectId": null,
        "planVersion": 0,
        "handoverPlan": {},
        "personnelDecision": {},
        "history": [],
        "revision": 1,
        "createdAt": "2026-11-02T08:00:00Z",
        "initiatorId": "user_example_2",
        "planValid": false,
        "canDecide": true,
        "canManage": false,
        "outstandingTasks": [],
        "targetInvitation": {},
        "targetValid": false,
        "canInviteTarget": false,
        "canCancelTarget": false,
        "canRespondTarget": false,
        "targetProjectName": ""
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-gov-12"></a>

### R_GOV_12 — 读取 · 项目 / 移出表决

适用接口：[GOV-12](#api-gov-12)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].canCancel`|bool|父记录存在时必有|当前调用者是否可取消；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canStatement`|bool|父记录存在时必有|当前调用者是否可提交陈述；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canVote`|bool|父记录存在时必有|当前调用者是否可投票；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].communicationHistory`|string|仅本事项当前有权投影返回|与人员变更有关的必要沟通记录；遵守本字段类型、统一上限及对应业务条件|
|`items[].facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].myBallot`|object|父记录存在时必有|本人选票，不暴露其他人的私有意见；遵守本字段类型、统一上限及对应业务条件|
|`items[].myBallot.at`|string|移出表决有记录时返回|本人选票时间；按当前权限及该版本业务范围返回|
|`items[].myBallot.choice`|string|父记录存在时必有|仅本人的AGREE/DISAGREE/ABSTAIN选票；按当前权限及该版本业务范围返回|
|`items[].myBallot.revision`|int|父记录存在时必有|本人选票版本，首次保存expectedRevision=0；按当前权限及该版本业务范围返回|
|`items[].myBallot.squareId`|string|合并表决有记录时返回|本人所属课程侧；按当前权限及该版本业务范围返回|
|`items[].myBallot.userId`|string|本人已有选票时返回|已投票的本人用户ID；不返回他人的选票记录|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].requiredYes`|int|父记录存在时必有|本条程序规定的通过票数；遵守本字段类型、统一上限及对应业务条件|
|`items[].result`|string/null|必有；未结算null|表决结算PASSED/NOT_PASSED，未结算null；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].rulesSnapshot`|object|父记录存在时必有|本报告固定的规则来源；遵守本字段类型、统一上限及对应业务条件|
|`items[].rulesSnapshot.majority`|string|父记录存在时必有|是否达到本侧法定阈值；遵守本字段类型、统一上限及对应业务条件|
|`items[].rulesSnapshot.minimumYes`|int|父记录存在时必有|该冻结名单严格过半所需票数；遵守本字段类型、统一上限及对应业务条件|
|`items[].statement`|string|父记录存在时必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`items[].statementEndsAt`|string|父记录存在时必有|本人陈述截止；遵守本字段类型、统一上限及对应业务条件|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].targetUserId`|string|父记录存在时必有|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].voteEndsAt`|string|父记录存在时必有|本次投票截止；遵守本字段类型、统一上限及对应业务条件|
|`items[].yesCount`|int|父记录存在时必有|当前赞成票数；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "vote_example_1",
        "projectId": "project_example_1",
        "targetUserId": "user_example_2",
        "facts": "请核对实际合作情况",
        "communicationHistory": "已在组内沟通",
        "status": "STATEMENT",
        "statementEndsAt": "2026-11-04T08:00:00Z",
        "voteEndsAt": "2026-11-07T08:00:00Z",
        "rulesSnapshot": {
          "minimumYes": 2,
          "majority": "STRICT_MAJORITY"
        },
        "statement": "",
        "myBallot": {},
        "result": null,
        "revision": 1,
        "canVote": true,
        "canStatement": false,
        "canCancel": true,
        "yesCount": 0,
        "requiredYes": 2
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-gov-13"></a>

### R_GOV_13 — 读取 · 项目 / 转组目标

适用接口：[GOV-13](#api-gov-13)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].availableSeats`|int|父记录存在时必有|重验后的可用席位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].canApply`|bool|父记录存在时必有|当前调用者是否可申请/应用；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].capacity`|int|父记录存在时必有|项目人数上限，含组长；遵守本字段类型、统一上限及对应业务条件|
|`items[].courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].discussionTime`|string|父记录存在时必有|协作时间说明；遵守本字段类型、统一上限及对应业务条件|
|`items[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].eligibleForDiscovery`|bool|父记录存在时必有|综合资格及筛选是否进入随机候选池；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ineligibleReason`|string/null|必有；允许null|本次不可参与的实际原因；遵守本字段类型、统一上限及对应业务条件|
|`items[].intro`|string|父记录存在时必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`items[].leaderName`|string|父记录存在时必有|当前组长展示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].memberCount`|int|父记录存在时必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`items[].mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`items[].myApplicationStatus`|string/null|必有；允许null|本人申请状态；遵守本字段类型、统一上限及对应业务条件|
|`items[].myInvitationId`|string/null|必有；允许null|本人的当前待回应邀请ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].recruitment`|object|父记录存在时必有|当前招募配置；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.acceptBeginners`|bool|父记录存在时必有|是否接受初学者；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.open`|bool|父记录存在时必有|是否开放招募；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.professionalDirection`|string|父记录存在时必有|招募专业方向；遵守本字段类型、统一上限及对应业务条件|
|`items[].recruitment.weeklyHours`|int|父记录存在时必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`items[].reservedCount`|int|父记录存在时必有|有效邀请/转组预留数；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].roles`|object[]|父记录存在时必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].duty`|string|父记录存在时必有|岗位职责；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].filled`|int|父记录存在时必有|已占用岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].roles[].reserved`|int|父记录存在时必有|有效预留岗位数；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].roleName`|string|父记录存在时必有|项目岗位显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles[].total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`items[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].squareNo`|string|父记录存在时必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`items[].stages`|object[]|父记录存在时必有|项目阶段定义；遵守本字段类型、统一上限及对应业务条件|
|`items[].stages[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].stages[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "project_example_1",
        "projectId": "project_example_1",
        "squareId": "square_example_1",
        "squareNo": "TEST-2026-001",
        "courseName": "移动应用开发实践",
        "assignmentId": "assignment_example_1",
        "assignmentName": "课程设计",
        "mode": "DETAILED",
        "name": "课程协作应用",
        "displayName": "课程协作应用",
        "intro": "完成课程设计和联调",
        "capacity": 4,
        "ownerId": "user_example_1",
        "leaderName": "示例学生",
        "status": "ACTIVE",
        "dueAt": "2026-11-09T15:59:59Z",
        "discussionTime": "周三晚",
        "recruitment": {
          "open": true,
          "description": "招募服务器和示例队员",
          "professionalDirection": "软件开发",
          "weeklyHours": 4,
          "acceptBeginners": true
        },
        "roles": [
          {
            "id": "role_example_1",
            "roleName": "客户端开发",
            "duty": "完成App和联调",
            "total": 2,
            "filled": 1,
            "reserved": 0
          },
          {
            "id": "role_example_2",
            "roleName": "服务器开发",
            "duty": "完成API和持久化",
            "total": 2,
            "filled": 0,
            "reserved": 0
          }
        ],
        "stages": [
          {
            "id": "stage_example_1",
            "name": "开发"
          }
        ],
        "revision": 1,
        "memberCount": 1,
        "reservedCount": 0,
        "canApply": true,
        "availableSeats": 3,
        "eligibleForDiscovery": true,
        "ineligibleReason": null,
        "myApplicationStatus": null,
        "myInvitationId": null
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-gov-14"></a>

### R_GOV_14 — 读取 · 移出表决

适用接口：[GOV-14](#api-gov-14)、[GOV-36](#api-gov-36)、[GOV-37](#api-gov-37)、[GOV-38](#api-gov-38)、[GOV-39](#api-gov-39)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`canCancel`|bool|必有|当前调用者是否可取消；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canStatement`|bool|必有|当前调用者是否可提交陈述；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canVote`|bool|必有|当前调用者是否可投票；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`communicationHistory`|string|仅本事项当前有权投影返回|与人员变更有关的必要沟通记录；遵守本字段类型、统一上限及对应业务条件|
|`facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myBallot`|object|必有|本人选票，不暴露其他人的私有意见；遵守本字段类型、统一上限及对应业务条件|
|`myBallot.at`|string|移出表决有记录时返回|本人选票时间；按当前权限及该版本业务范围返回|
|`myBallot.choice`|string|父记录存在时必有|仅本人的AGREE/DISAGREE/ABSTAIN选票；按当前权限及该版本业务范围返回|
|`myBallot.revision`|int|父记录存在时必有|本人选票版本，首次保存expectedRevision=0；按当前权限及该版本业务范围返回|
|`myBallot.squareId`|string|合并表决有记录时返回|本人所属课程侧；按当前权限及该版本业务范围返回|
|`myBallot.userId`|string|本人已有选票时返回|已投票的本人用户ID；不返回他人的选票记录|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`requiredYes`|int|必有|本条程序规定的通过票数；遵守本字段类型、统一上限及对应业务条件|
|`result`|string/null|必有；未结算null|表决结算PASSED/NOT_PASSED，未结算null；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`rulesSnapshot`|object|必有|本报告固定的规则来源；遵守本字段类型、统一上限及对应业务条件|
|`rulesSnapshot.majority`|string|父记录存在时必有|是否达到本侧法定阈值；遵守本字段类型、统一上限及对应业务条件|
|`rulesSnapshot.minimumYes`|int|父记录存在时必有|该冻结名单严格过半所需票数；遵守本字段类型、统一上限及对应业务条件|
|`statement`|string|必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`statementEndsAt`|string|必有|本人陈述截止；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targetUserId`|string|必有|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`voteEndsAt`|string|必有|本次投票截止；遵守本字段类型、统一上限及对应业务条件|
|`yesCount`|int|必有|当前赞成票数；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "vote_example_1",
    "projectId": "project_example_1",
    "targetUserId": "user_example_2",
    "facts": "请核对实际合作情况",
    "communicationHistory": "已在组内沟通",
    "status": "STATEMENT",
    "statementEndsAt": "2026-11-04T08:00:00Z",
    "voteEndsAt": "2026-11-07T08:00:00Z",
    "rulesSnapshot": {
      "minimumYes": 2,
      "majority": "STRICT_MAJORITY"
    },
    "statement": "",
    "myBallot": {},
    "result": null,
    "revision": 1,
    "canVote": true,
    "canStatement": false,
    "canCancel": true,
    "yesCount": 0,
    "requiredYes": 2
  }
}
```

<a id="response-r-gov-16"></a>

### R_GOV_16 — 读取 · 课程 / 人员事项

适用接口：[GOV-16](#api-gov-16)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].canHandle`|bool|父记录存在时必有|当前调用者是否可处理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canManageMaterials`|bool|父记录存在时必有|当前调用者是否可处理材料；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canRecover`|bool|父记录存在时必有|当前调用者是否可收回处理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].handoffs`|object[]|父记录存在时必有|有权人员事项交接列表；遵守本字段类型、统一上限及对应业务条件|
|`items[].handoffs[].canCancel`|bool|父记录存在时必有|当前调用者是否可取消；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].handoffs[].canRespond`|bool|父记录存在时必有|当前调用者是否可本人回应；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].handoffs[].caseId`|string|父记录存在时必有|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].handoffs[].decisionsNeeded`|string|父记录存在时必有|接收者需决定的内容；遵守本字段类型、统一上限及对应业务条件|
|`items[].handoffs[].facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].handoffs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].handoffs[].recipientName`|string|父记录存在时必有|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].handoffs[].recipientTeacherId`|string|父记录存在时必有|本次合法接任教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].handoffs[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].handoffs[].sourceCaseRevision`|int|父记录存在时必有|交接固定的原事项版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].handoffs[].sourceName`|string|父记录存在时必有|原来源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].handoffs[].sourceUserId`|string|父记录存在时必有|原资料所属用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].handoffs[].sourceValid`|bool|父记录存在时必有|原业务来源是否仍允许当前动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].handoffs[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].handoffs[].unresolvedQuestions`|string|父记录存在时必有|尚未解决的问题；遵守本字段类型、统一上限及对应业务条件|
|`items[].history`|object[]|仅本事项当前有权投影返回|按当前权限返回的处理/版本历史；遵守本字段类型、统一上限及对应业务条件|
|`items[].history[].action`|string|仅本事项当前有权投影返回|本人明确执行的动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].history[].actorId`|string|仅本事项当前有权投影返回|本事件实际操作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].history[].at`|string|仅本事项当前有权投影返回|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].history[].reason`|string|仅本事项当前有权投影返回|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].materialRequests`|object[]|仅本事项当前有权投影返回|有权补资料请求列表；遵守本字段类型、统一上限及对应业务条件|
|`items[].materialRequests[].canRespond`|bool|仅本事项当前有权投影返回|当前调用者是否可本人回应；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].materialRequests[].canReview`|bool|仅本事项当前有权投影返回|当前调用者是否可核验；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].materialRequests[].canWithdraw`|bool|仅本事项当前有权投影返回|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].materialRequests[].caseId`|string|仅本事项当前有权投影返回|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].materialRequests[].caseTitle`|string|仅本事项当前有权投影返回|人员事项显示标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].materialRequests[].dueAt`|string|仅本事项当前有权投影返回|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].materialRequests[].history`|object[]|父记录存在时必有|处理历史，核对与撤回分开记录；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].history[].action`|string|撤回事件必有|撤回请求事件为WITHDRAW；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].history[].at`|string|核对事件必有|服务器核对时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].history[].decision`|string|核对事件必有|ACCEPT/RETURN；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].history[].reason`|string|核对事件必有|核对理由；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].history[].responseVersion`|int|核对事件必有|本次核对的回应版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].history[].reviewedBy`|string|核对事件必有|实际核对处理者ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].id`|string|仅本事项当前有权投影返回|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].materialRequests[].recipientId`|string|仅本事项当前有权投影返回|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].materialRequests[].recipientName`|string|仅本事项当前有权投影返回|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].materialRequests[].requirements`|string|仅本事项当前有权投影返回|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`items[].materialRequests[].responseVersion`|int|仅本事项当前有权投影返回|本次补资料回应版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].materialRequests[].responses`|object[]|父记录存在时必有|历次本人回应，旧版不可覆盖；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].authorId`|string|父记录存在时必有|回应者本人ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].content`|string|父记录存在时必有|本人提交正文；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].evidenceRefs`|object[]|父记录存在时必有|本人明确附带材料；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].evidenceRefs[].kind`|string|父记录存在时必有|LINK/FILE；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].evidenceRefs[].title`|string|父记录存在时必有|材料展示名；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].evidenceRefs[].url`|string|仅LINK必有|http/https材料链接；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].late`|bool|父记录存在时必有|按原截止是否迟交；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].review`|object|本版本已有核对时返回|对该回应版本的核对记录；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].review.at`|string|父记录存在时必有|服务器核对时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].review.decision`|string|父记录存在时必有|ACCEPT/RETURN；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].review.reason`|string|父记录存在时必有|核对理由；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].review.responseVersion`|int|父记录存在时必有|本次核对的回应版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].review.reviewedBy`|string|父记录存在时必有|实际核对处理者ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].submittedAt`|string|父记录存在时必有|实际提交时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].responses[].version`|int|父记录存在时必有|回应不可覆盖版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`items[].materialRequests[].revision`|int|仅本事项当前有权投影返回|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].materialRequests[].status`|string|仅本事项当前有权投影返回|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ownerName`|string|父记录存在时必有|当前责任人显示名，未设置为空或未认领显示文案；遵守本字段类型、统一上限及对应业务条件|
|`items[].personnelActionPending`|bool|父记录存在时必有|是否仍有原人员动作未完成；遵守本字段类型、统一上限及对应业务条件|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].subjectUserId`|string|父记录存在时必有|当前结构固定的subjectUser资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "case_example_1",
        "squareId": "square_example_1",
        "assignmentId": "assignment_example_1",
        "projectId": "project_example_1",
        "subjectUserId": "user_example_2",
        "type": "MEMBERSHIP",
        "title": "成员关系核对",
        "status": "OPEN",
        "ownerId": "",
        "ownerName": "",
        "facts": "",
        "history": [],
        "personnelActionPending": true,
        "materialRequests": [],
        "handoffs": [],
        "canHandle": true,
        "canManageMaterials": false,
        "canRecover": false,
        "revision": 1
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-gov-22"></a>

### R_GOV_22 — 提交 · 退出转组 / 本人接任回应

适用接口：[GOV-22](#api-gov-22)、[GOV-40](#api-gov-40)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`canCancelTarget`|bool|必有|当前调用者是否可取消目标邀请；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canDecide`|bool|必有|当前调用者是否可作出决定；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canInviteTarget`|bool|必有|当前调用者是否可邀请目标；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canManage`|bool|必有|当前调用者是否可管理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canRespondTarget`|bool|必有|当前调用者是否可回应目标邀请；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`handoverPlan`|object|仅本事项当前有权投影返回|组内制定且逐项本人接受的交接方案；遵守本字段类型、统一上限及对应业务条件|
|`handoverPlan.items`|object[]|仅本事项当前有权投影返回|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`handoverPlan.items[].accepted`|bool|父记录存在时必有|接任本人是否接受当前版本；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].dueAt`|string|非LEADER条目必有|原任务截止；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].id`|string|父记录存在时必有|服务器生成的交接条目ID；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].kind`|string|父记录存在时必有|TASK/FORMAL_OWNER/LEADER；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].reason`|string|拒绝回应时返回|本人拒绝理由；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].recipientId`|string|父记录存在时必有|实际接任人本人ID；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].recipientMembershipId`|string|父记录存在时必有|接任者当前留组成员关系ID；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].recipientName`|string|父记录存在时必有|实际接任人姓名；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].respondedAt`|string|已回应时返回|本人回应时间；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].responsibility`|string|父记录存在时必有|本条明确接续责任；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].sourceTaskRevision`|int|非LEADER条目必有|原任务基准版本；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].taskId`|string|非LEADER条目必有|固定任务ID；按当前权限及该版本业务范围返回|
|`handoverPlan.items[].taskTitle`|string|非LEADER条目必有|原任务标题；按当前权限及该版本业务范围返回|
|`handoverPlan.notes`|string|仅本事项当前有权投影返回|补充说明；遵守本字段类型、统一上限及对应业务条件|
|`handoverPlan.sourceLeaderId`|string|父记录存在时必有|方案来源组长ID；按当前权限及该版本业务范围返回|
|`handoverPlan.targetMembershipId`|string|父记录存在时必有|原待变更成员关系ID；按当前权限及该版本业务范围返回|
|`handoverPlan.version`|int|仅本事项当前有权投影返回|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`history`|object[]|仅本事项当前有权投影返回|按当前权限返回的处理/版本历史；遵守本字段类型、统一上限及对应业务条件|
|`history[].action`|string|仅本事项当前有权投影返回|本人明确执行的动作；允许值：按本条业务规则列明的动作|
|`history[].actorId`|string|仅本事项当前有权投影返回|本事件实际操作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`history[].at`|string|仅本事项当前有权投影返回|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`history[].reason`|string|仅本事项当前有权投影返回|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`initiatorId`|string|必有|当前结构固定的initiator资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`kind`|string|必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`outstandingTasks`|object[]|仅本事项当前有权投影返回|与本次交接有关的未完责任；遵守本字段类型、统一上限及对应业务条件|
|`outstandingTasks[].id`|string|仅本事项当前有权投影返回|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`outstandingTasks[].revision`|int|仅本事项当前有权投影返回|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`outstandingTasks[].title`|string|仅本事项当前有权投影返回|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`personnelDecision`|object|必有|当前有权教师的人员前置核对，不替代组内接受；遵守本字段类型、统一上限及对应业务条件|
|`personnelDecision.allowBelowMinimum`|bool|父记录存在时必有|是否明确允许低于人数下限；按当前权限及该版本业务范围返回|
|`personnelDecision.at`|string|父记录存在时必有|服务器核对时间；按当前权限及该版本业务范围返回|
|`personnelDecision.decision`|string|父记录存在时必有|ALLOW_HANDOVER/RETURN；按当前权限及该版本业务范围返回|
|`personnelDecision.placementPlan`|string|父记录存在时必有|人员安置说明；按当前权限及该版本业务范围返回|
|`personnelDecision.reason`|string|父记录存在时必有|人员核对理由；按当前权限及该版本业务范围返回|
|`personnelDecision.teacherId`|string|父记录存在时必有|实际作人员核对的当前教师ID；按当前权限及该版本业务范围返回|
|`planValid`|bool|必有|当前交接方案及接受是否仍有效；遵守本字段类型、统一上限及对应业务条件|
|`planVersion`|int|必有|当前交接方案版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceProjectId`|string|必有|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targetInvitation`|object|必有|有条件目标接收邀请；遵守本字段类型、统一上限及对应业务条件|
|`targetInvitation.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`targetInvitation.expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`targetInvitation.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetInvitation.message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`targetInvitation.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetInvitation.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`targetInvitation.recipientId`|string|父记录存在时必有|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetInvitation.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`targetInvitation.roleSlotId`|string|父记录存在时必有|项目岗位ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetInvitation.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targetProjectId`|string/null|必有；允许null|预选的转入项目，不意味着已入组；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetProjectName`|string|必有|targetProjectName：所属响应结构中的字符串值；遵守本字段类型、统一上限及对应业务条件|
|`targetUserId`|string|必有|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targetValid`|bool|必有|目标项目与席位当前是否仍有效；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "member_change_example_1",
    "projectId": "project_example_1",
    "sourceProjectId": "project_example_1",
    "squareId": "square_example_1",
    "assignmentId": "assignment_example_1",
    "targetUserId": "user_example_2",
    "kind": "EXIT",
    "reason": "按实际协作情况说明",
    "status": "READY",
    "targetProjectId": null,
    "planVersion": 1,
    "handoverPlan": {
      "version": 1,
      "items": [
        {
          "id": "handover_item_example_1",
          "taskId": "task_example_1",
          "sourceTaskRevision": 1,
          "recipientId": "user_example_2",
          "responsibility": "接续未完成内容"
        }
      ],
      "notes": "保留有效历史"
    },
    "personnelDecision": {},
    "history": [],
    "revision": 2,
    "createdAt": "2026-11-02T08:00:00Z",
    "initiatorId": "user_example_2",
    "planValid": false,
    "canDecide": true,
    "canManage": false,
    "outstandingTasks": [],
    "targetInvitation": {},
    "targetValid": false,
    "canInviteTarget": false,
    "canCancelTarget": false,
    "canRespondTarget": false,
    "targetProjectName": ""
  }
}
```

<a id="response-r-gov-28"></a>

### R_GOV_28 — 提交 · 人员事项 / 结案/关闭

适用接口：[GOV-28](#api-gov-28)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`canHandle`|bool|必有|当前调用者是否可处理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canManageMaterials`|bool|必有|当前调用者是否可处理材料；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canRecover`|bool|必有|当前调用者是否可收回处理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`closeReason`|string/null|必有；允许null|有权关闭的真实原因，未关闭null；遵守本字段类型、统一上限及对应业务条件|
|`facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`handoffs`|object[]|必有|有权人员事项交接列表；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].canCancel`|bool|父记录存在时必有|当前调用者是否可取消；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].canRespond`|bool|父记录存在时必有|当前调用者是否可本人回应；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].caseId`|string|父记录存在时必有|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`handoffs[].decisionsNeeded`|string|父记录存在时必有|接收者需决定的内容；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`handoffs[].recipientName`|string|父记录存在时必有|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].recipientTeacherId`|string|父记录存在时必有|本次合法接任教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`handoffs[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`handoffs[].sourceCaseRevision`|int|父记录存在时必有|交接固定的原事项版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`handoffs[].sourceName`|string|父记录存在时必有|原来源名称；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].sourceUserId`|string|父记录存在时必有|原资料所属用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`handoffs[].sourceValid`|bool|父记录存在时必有|原业务来源是否仍允许当前动作；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].unresolvedQuestions`|string|父记录存在时必有|尚未解决的问题；遵守本字段类型、统一上限及对应业务条件|
|`history`|object[]|仅本事项当前有权投影返回|按当前权限返回的处理/版本历史；遵守本字段类型、统一上限及对应业务条件|
|`history[].action`|string|仅本事项当前有权投影返回|本人明确执行的动作；允许值：按本条业务规则列明的动作|
|`history[].actorId`|string|仅本事项当前有权投影返回|本事件实际操作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`history[].at`|string|仅本事项当前有权投影返回|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`history[].reason`|string|仅本事项当前有权投影返回|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`materialRequests`|object[]|仅本事项当前有权投影返回|有权补资料请求列表；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].canRespond`|bool|仅本事项当前有权投影返回|当前调用者是否可本人回应；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].canReview`|bool|仅本事项当前有权投影返回|当前调用者是否可核验；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].canWithdraw`|bool|仅本事项当前有权投影返回|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].caseId`|string|仅本事项当前有权投影返回|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`materialRequests[].caseTitle`|string|仅本事项当前有权投影返回|人员事项显示标题；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].dueAt`|string|仅本事项当前有权投影返回|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`materialRequests[].history`|object[]|父记录存在时必有|处理历史，核对与撤回分开记录；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].action`|string|撤回事件必有|撤回请求事件为WITHDRAW；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].at`|string|核对事件必有|服务器核对时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].decision`|string|核对事件必有|ACCEPT/RETURN；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].reason`|string|核对事件必有|核对理由；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].responseVersion`|int|核对事件必有|本次核对的回应版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].reviewedBy`|string|核对事件必有|实际核对处理者ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].id`|string|仅本事项当前有权投影返回|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`materialRequests[].recipientId`|string|仅本事项当前有权投影返回|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`materialRequests[].recipientName`|string|仅本事项当前有权投影返回|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].requirements`|string|仅本事项当前有权投影返回|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].responseVersion`|int|仅本事项当前有权投影返回|本次补资料回应版本；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].responses`|object[]|父记录存在时必有|历次本人回应，旧版不可覆盖；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].authorId`|string|父记录存在时必有|回应者本人ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].content`|string|父记录存在时必有|本人提交正文；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs`|object[]|父记录存在时必有|本人明确附带材料；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs[].kind`|string|父记录存在时必有|LINK/FILE；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs[].title`|string|父记录存在时必有|材料展示名；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs[].url`|string|仅LINK必有|http/https材料链接；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].late`|bool|父记录存在时必有|按原截止是否迟交；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review`|object|本版本已有核对时返回|对该回应版本的核对记录；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.at`|string|父记录存在时必有|服务器核对时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.decision`|string|父记录存在时必有|ACCEPT/RETURN；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.reason`|string|父记录存在时必有|核对理由；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.responseVersion`|int|父记录存在时必有|本次核对的回应版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.reviewedBy`|string|父记录存在时必有|实际核对处理者ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].submittedAt`|string|父记录存在时必有|实际提交时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].version`|int|父记录存在时必有|回应不可覆盖版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].revision`|int|仅本事项当前有权投影返回|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`materialRequests[].status`|string|仅本事项当前有权投影返回|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`outcome`|string|必有|outcome：所属响应结构中的字符串值；遵守本字段类型、统一上限及对应业务条件|
|`ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ownerName`|string|必有|当前责任人显示名，未设置为空或未认领显示文案；遵守本字段类型、统一上限及对应业务条件|
|`personnelActionPending`|bool|必有|是否仍有原人员动作未完成；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`subjectUserId`|string|必有|当前结构固定的subjectUser资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`type`|string|必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "case_example_1",
    "squareId": "square_example_1",
    "assignmentId": "assignment_example_1",
    "projectId": "project_example_1",
    "subjectUserId": "user_example_2",
    "type": "MEMBERSHIP",
    "title": "成员关系核对",
    "status": "CLOSED",
    "ownerId": "",
    "ownerName": "",
    "facts": "",
    "history": [],
    "personnelActionPending": true,
    "materialRequests": [],
    "handoffs": [],
    "canHandle": false,
    "canManageMaterials": false,
    "canRecover": false,
    "revision": 2,
    "outcome": "人员前置核对已完成",
    "closeReason": "按实际协作情况说明"
  }
}
```

<a id="response-r-gov-32"></a>

### R_GOV_32 — 提交 · 人员事项 / 追加事实记录

适用接口：[GOV-32](#api-gov-32)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`canHandle`|bool|必有|当前调用者是否可处理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canManageMaterials`|bool|必有|当前调用者是否可处理材料；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canRecover`|bool|必有|当前调用者是否可收回处理；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`handoffs`|object[]|必有|有权人员事项交接列表；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].canCancel`|bool|父记录存在时必有|当前调用者是否可取消；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].canRespond`|bool|父记录存在时必有|当前调用者是否可本人回应；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].caseId`|string|父记录存在时必有|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`handoffs[].decisionsNeeded`|string|父记录存在时必有|接收者需决定的内容；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`handoffs[].recipientName`|string|父记录存在时必有|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].recipientTeacherId`|string|父记录存在时必有|本次合法接任教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`handoffs[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`handoffs[].sourceCaseRevision`|int|父记录存在时必有|交接固定的原事项版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`handoffs[].sourceName`|string|父记录存在时必有|原来源名称；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].sourceUserId`|string|父记录存在时必有|原资料所属用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`handoffs[].sourceValid`|bool|父记录存在时必有|原业务来源是否仍允许当前动作；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`handoffs[].unresolvedQuestions`|string|父记录存在时必有|尚未解决的问题；遵守本字段类型、统一上限及对应业务条件|
|`history`|object[]|仅本事项当前有权投影返回|按当前权限返回的处理/版本历史；遵守本字段类型、统一上限及对应业务条件|
|`history[].action`|string|仅本事项当前有权投影返回|本人明确执行的动作；遵守本字段类型、统一上限及对应业务条件|
|`history[].actorId`|string|仅本事项当前有权投影返回|本事件实际操作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`history[].at`|string|仅本事项当前有权投影返回|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`history[].authorId`|string|仅本事项当前有权投影返回|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`history[].authorName`|string|仅本事项当前有权投影返回|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`history[].evidenceRefs`|object[]|仅本事项当前有权投影返回|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`history[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`history[].evidenceRefs[].kind`|string|父记录存在时必有|LINK/FILE；按当前权限及该版本业务范围返回|
|`history[].evidenceRefs[].title`|string|父记录存在时必有|本人填写的引用标题；按当前权限及该版本业务范围返回|
|`history[].evidenceRefs[].url`|string|仅LINK必有|外链材料地址；按当前权限及该版本业务范围返回|
|`history[].facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`history[].nextAction`|string|仅本事项当前有权投影返回|实际下一步处理方向；遵守本字段类型、统一上限及对应业务条件|
|`history[].reason`|string|仅本事项当前有权投影返回|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`materialRequests`|object[]|仅本事项当前有权投影返回|有权补资料请求列表；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].canRespond`|bool|仅本事项当前有权投影返回|当前调用者是否可本人回应；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].canReview`|bool|仅本事项当前有权投影返回|当前调用者是否可核验；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].canWithdraw`|bool|仅本事项当前有权投影返回|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].caseId`|string|仅本事项当前有权投影返回|当前结构固定的case资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`materialRequests[].caseTitle`|string|仅本事项当前有权投影返回|人员事项显示标题；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].dueAt`|string|仅本事项当前有权投影返回|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`materialRequests[].history`|object[]|父记录存在时必有|处理历史，核对与撤回分开记录；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].action`|string|撤回事件必有|撤回请求事件为WITHDRAW；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].at`|string|核对事件必有|服务器核对时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].decision`|string|核对事件必有|ACCEPT/RETURN；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].reason`|string|核对事件必有|核对理由；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].responseVersion`|int|核对事件必有|本次核对的回应版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].history[].reviewedBy`|string|核对事件必有|实际核对处理者ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].id`|string|仅本事项当前有权投影返回|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`materialRequests[].recipientId`|string|仅本事项当前有权投影返回|本条实际收件人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`materialRequests[].recipientName`|string|仅本事项当前有权投影返回|收件人姓名；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].requirements`|string|仅本事项当前有权投影返回|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].responseVersion`|int|仅本事项当前有权投影返回|本次补资料回应版本；遵守本字段类型、统一上限及对应业务条件|
|`materialRequests[].responses`|object[]|父记录存在时必有|历次本人回应，旧版不可覆盖；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].authorId`|string|父记录存在时必有|回应者本人ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].content`|string|父记录存在时必有|本人提交正文；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs`|object[]|父记录存在时必有|本人明确附带材料；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs[].kind`|string|父记录存在时必有|LINK/FILE；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs[].title`|string|父记录存在时必有|材料展示名；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].evidenceRefs[].url`|string|仅LINK必有|http/https材料链接；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].late`|bool|父记录存在时必有|按原截止是否迟交；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review`|object|本版本已有核对时返回|对该回应版本的核对记录；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.at`|string|父记录存在时必有|服务器核对时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.decision`|string|父记录存在时必有|ACCEPT/RETURN；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.reason`|string|父记录存在时必有|核对理由；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.responseVersion`|int|父记录存在时必有|本次核对的回应版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].review.reviewedBy`|string|父记录存在时必有|实际核对处理者ID；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].submittedAt`|string|父记录存在时必有|实际提交时间；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].responses[].version`|int|父记录存在时必有|回应不可覆盖版本；固定当前事项/回应版本，仅按本人或当前处理授权投影|
|`materialRequests[].revision`|int|仅本事项当前有权投影返回|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`materialRequests[].status`|string|仅本事项当前有权投影返回|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`ownerId`|string/null|必有；允许null|当前负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ownerName`|string|必有|当前责任人显示名，未设置为空或未认领显示文案；遵守本字段类型、统一上限及对应业务条件|
|`personnelActionPending`|bool|必有|是否仍有原人员动作未完成；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`subjectUserId`|string|必有|当前结构固定的subjectUser资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`type`|string|必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "case_example_1",
    "squareId": "square_example_1",
    "assignmentId": "assignment_example_1",
    "projectId": "project_example_1",
    "subjectUserId": "user_example_2",
    "type": "MEMBERSHIP",
    "title": "成员关系核对",
    "status": "OPEN",
    "ownerId": "",
    "ownerName": "",
    "facts": "已核对当前成员关系",
    "history": [
      {
        "authorId": "user_example_teacher",
        "authorName": "示例教师",
        "facts": "已核对当前成员关系",
        "nextAction": "等待本人补充材料",
        "evidenceRefs": [
          {
            "kind": "LINK",
            "url": "https://example.invalid/demo",
            "title": "示例演示材料"
          }
        ],
        "at": "2026-11-02T08:00:00Z"
      }
    ],
    "personnelActionPending": true,
    "materialRequests": [],
    "handoffs": [],
    "canHandle": true,
    "canManageMaterials": false,
    "canRecover": false,
    "revision": 2
  }
}
```

<a id="response-r-gov-41"></a>

### R_GOV_41 — 保存 · 移出表决 / 本人 / 本人选票

适用接口：[GOV-41](#api-gov-41)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`canCancel`|bool|必有|当前调用者是否可取消；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canStatement`|bool|必有|当前调用者是否可提交陈述；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canVote`|bool|必有|当前调用者是否可投票；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`communicationHistory`|string|仅本事项当前有权投影返回|与人员变更有关的必要沟通记录；遵守本字段类型、统一上限及对应业务条件|
|`facts`|string|仅本事项当前有权投影返回|人员事项事实，限有权投影；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myBallot`|object|必有|本人选票，不暴露其他人的私有意见；遵守本字段类型、统一上限及对应业务条件|
|`myBallot.at`|string|移出表决有记录时返回|本人选票时间；按当前权限及该版本业务范围返回|
|`myBallot.choice`|string|父记录存在时必有|本人在冻结资格下的选票；遵守本字段类型、统一上限及对应业务条件|
|`myBallot.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`myBallot.squareId`|string|合并表决有记录时返回|本人所属课程侧；按当前权限及该版本业务范围返回|
|`myBallot.userId`|string|本人已有选票时返回|已投票的本人用户ID；不返回他人的选票记录|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`requiredYes`|int|必有|本条程序规定的通过票数；遵守本字段类型、统一上限及对应业务条件|
|`result`|string/null|必有；未结算null|表决结算PASSED/NOT_PASSED，未结算null；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`rulesSnapshot`|object|必有|本报告固定的规则来源；遵守本字段类型、统一上限及对应业务条件|
|`rulesSnapshot.majority`|string|父记录存在时必有|是否达到本侧法定阈值；遵守本字段类型、统一上限及对应业务条件|
|`rulesSnapshot.minimumYes`|int|父记录存在时必有|该冻结名单严格过半所需票数；遵守本字段类型、统一上限及对应业务条件|
|`statement`|string|必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`statementEndsAt`|string|必有|本人陈述截止；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targetUserId`|string|必有|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`voteEndsAt`|string|必有|本次投票截止；遵守本字段类型、统一上限及对应业务条件|
|`yesCount`|int|必有|当前赞成票数；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "vote_example_1",
    "projectId": "project_example_1",
    "targetUserId": "user_example_2",
    "facts": "请核对实际合作情况",
    "communicationHistory": "已在组内沟通",
    "status": "VOTING",
    "statementEndsAt": "2026-11-04T08:00:00Z",
    "voteEndsAt": "2026-11-07T08:00:00Z",
    "rulesSnapshot": {
      "minimumYes": 2,
      "majority": "STRICT_MAJORITY"
    },
    "statement": "",
    "myBallot": {
      "userId": "user_example_1",
      "choice": "AGREE",
      "revision": 2,
      "at": "2026-11-05T08:01:00Z"
    },
    "result": null,
    "revision": 3,
    "canVote": true,
    "canStatement": false,
    "canCancel": true,
    "yesCount": 1,
    "requiredYes": 2
  }
}
```

<a id="response-r-merge-01"></a>

### R_MERGE_01 — 读取 · 课程合并提案

适用接口：[MERGE-01](#api-merge-01)、[MERGE-03](#api-merge-03)、[MERGE-04](#api-merge-04)、[MERGE-05](#api-merge-05)、[MERGE-07](#api-merge-07)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentMappings`|string[]|必有|合并课程时明确保持分离的作业映射；遵守本字段类型、统一上限及对应业务条件|
|`canCheck`|bool|必有|当前调用者是否可核对；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canExecute`|bool|必有|当前调用者是否可最终执行；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs`|object[]|必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sides`|object[]|必有|两个课程侧独立的冻结资格和计票；遵守本字段类型、统一上限及对应业务条件|
|`sides[].abstain`|int|父记录存在时必有|弃权票数；按当前权限及该版本业务范围返回|
|`sides[].agreeCount`|int|父记录存在时必有|当前有效同意数；遵守本字段类型、统一上限及对应业务条件|
|`sides[].disagree`|int|父记录存在时必有|反对票数；按当前权限及该版本业务范围返回|
|`sides[].eligibleCount`|int|父记录存在时必有|该课程侧冻结分母人数；遵守本字段类型、统一上限及对应业务条件|
|`sides[].myBallot`|object|父记录存在时必有|本人选票，不暴露其他人的私有意见；遵守本字段类型、统一上限及对应业务条件|
|`sides[].myBallot.at`|string|移出表决有记录时返回|本人选票时间；按当前权限及该版本业务范围返回|
|`sides[].myBallot.choice`|string|父记录存在时必有|仅本人的AGREE/DISAGREE/ABSTAIN选票；按当前权限及该版本业务范围返回|
|`sides[].myBallot.revision`|int|父记录存在时必有|本人选票版本，首次保存expectedRevision=0；按当前权限及该版本业务范围返回|
|`sides[].myBallot.squareId`|string|合并表决有记录时返回|本人所属课程侧；按当前权限及该版本业务范围返回|
|`sides[].myBallot.updatedAt`|string|本人已有选票时返回|本人合并选票服务端更新时间；不返回他人的选票记录|
|`sides[].passed`|bool|父记录存在时必有|本课程侧是否严格过半；按当前权限及该版本业务范围返回|
|`sides[].requiredCount`|int|父记录存在时必有|当前所需确认人数；遵守本字段类型、统一上限及对应业务条件|
|`sides[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`sides[].unvoted`|int|父记录存在时必有|未投票人数；按当前权限及该版本业务范围返回|
|`sourceAssignments`|object[]|必有|来源作业清单，不能丢历史；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].confirmationStatus`|string|父记录存在时必有|作业教师确认状态；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].gradingEnabled`|bool|父记录存在时必有|本作业是否启用正式教学评价；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].hardDueAt`|string|父记录存在时必有|原作业硬截止；RFC3339时间点，响应UTC Z，服务器比较有效性；可空条件见模型|
|`sourceAssignments[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`sourceAssignments[].maxMembers`|int|父记录存在时必有|作业最多成员数；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].minMembers`|int|父记录存在时必有|作业最少成员数；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].origin`|string|父记录存在时必有|固定CLIENT_BYOK_LEADER_CONFIRMED来源声明；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].requirements`|string|父记录存在时必有|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceAssignments[].sourceReferences`|object[]|父记录存在时必有|报告固定来源集合；可省略默认[]，最多10个|
|`sourceAssignments[].sourceReferences[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].sourceReferences[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].sourceReferences[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`sourceSquareId`|string|必有|原课程ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targetSquareId`|string|必有|目标课程ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "merge_example_1",
    "sourceSquareId": "square_example_1",
    "targetSquareId": "square_example_2",
    "reason": "课程信息指向同一班级",
    "evidenceRefs": [],
    "status": "VOTING",
    "sides": [
      {
        "squareId": "square_example_1",
        "agreeCount": 3,
        "requiredCount": 3,
        "myBallot": {},
        "eligibleCount": 4
      },
      {
        "squareId": "square_example_2",
        "agreeCount": 3,
        "requiredCount": 3,
        "myBallot": {},
        "eligibleCount": 4
      }
    ],
    "sourceAssignments": [
      {
        "id": "assignment_example_1",
        "squareId": "square_example_1",
        "name": "课程设计",
        "requirements": "完成可演示的课程协作应用",
        "origin": "TEACHER",
        "confirmationStatus": "CONFIRMED",
        "hardDueAt": "2026-11-09T15:59:59Z",
        "minMembers": 2,
        "maxMembers": 6,
        "gradingEnabled": false,
        "revision": 1,
        "sourceReferences": []
      }
    ],
    "assignmentMappings": [],
    "canCheck": true,
    "canExecute": false,
    "revision": 1
  }
}
```

<a id="response-r-merge-02"></a>

### R_MERGE_02 — 读取 · 课程 / 课程合并提案

适用接口：[MERGE-02](#api-merge-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentMappings`|string[]|父记录存在时必有|合并课程时明确保持分离的作业映射；遵守本字段类型、统一上限及对应业务条件|
|`items[].canCheck`|bool|父记录存在时必有|当前调用者是否可核对；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canExecute`|bool|父记录存在时必有|当前调用者是否可最终执行；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].evidenceRefs`|object[]|父记录存在时必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`items[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`items[].evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`items[].evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].sides`|object[]|父记录存在时必有|两个课程侧独立的冻结资格和计票；遵守本字段类型、统一上限及对应业务条件|
|`items[].sides[].abstain`|int|父记录存在时必有|弃权票数；按当前权限及该版本业务范围返回|
|`items[].sides[].agreeCount`|int|父记录存在时必有|当前有效同意数；遵守本字段类型、统一上限及对应业务条件|
|`items[].sides[].disagree`|int|父记录存在时必有|反对票数；按当前权限及该版本业务范围返回|
|`items[].sides[].eligibleCount`|int|父记录存在时必有|该课程侧冻结分母人数；遵守本字段类型、统一上限及对应业务条件|
|`items[].sides[].myBallot`|object|父记录存在时必有|本人选票，不暴露其他人的私有意见；遵守本字段类型、统一上限及对应业务条件|
|`items[].sides[].myBallot.at`|string|移出表决有记录时返回|本人选票时间；按当前权限及该版本业务范围返回|
|`items[].sides[].myBallot.choice`|string|父记录存在时必有|仅本人的AGREE/DISAGREE/ABSTAIN选票；按当前权限及该版本业务范围返回|
|`items[].sides[].myBallot.revision`|int|父记录存在时必有|本人选票版本，首次保存expectedRevision=0；按当前权限及该版本业务范围返回|
|`items[].sides[].myBallot.squareId`|string|合并表决有记录时返回|本人所属课程侧；按当前权限及该版本业务范围返回|
|`items[].sides[].myBallot.updatedAt`|string|本人已有选票时返回|本人合并选票服务端更新时间；不返回他人的选票记录|
|`items[].sides[].passed`|bool|父记录存在时必有|本课程侧是否严格过半；按当前权限及该版本业务范围返回|
|`items[].sides[].requiredCount`|int|父记录存在时必有|当前所需确认人数；遵守本字段类型、统一上限及对应业务条件|
|`items[].sides[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].sides[].unvoted`|int|父记录存在时必有|未投票人数；按当前权限及该版本业务范围返回|
|`items[].sourceAssignments`|object[]|父记录存在时必有|来源作业清单，不能丢历史；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceAssignments[].confirmationStatus`|string|父记录存在时必有|作业教师确认状态；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceAssignments[].gradingEnabled`|bool|父记录存在时必有|本作业是否启用正式教学评价；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceAssignments[].hardDueAt`|string|父记录存在时必有|原作业硬截止；RFC3339时间点，响应UTC Z，服务器比较有效性；可空条件见模型|
|`items[].sourceAssignments[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].sourceAssignments[].maxMembers`|int|父记录存在时必有|作业最多成员数；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceAssignments[].minMembers`|int|父记录存在时必有|作业最少成员数；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceAssignments[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceAssignments[].origin`|string|父记录存在时必有|固定CLIENT_BYOK_LEADER_CONFIRMED来源声明；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceAssignments[].requirements`|string|父记录存在时必有|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceAssignments[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].sourceAssignments[].sourceReferences`|object[]|父记录存在时必有|报告固定来源集合；可省略默认[]，最多10个|
|`items[].sourceAssignments[].sourceReferences[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceAssignments[].sourceReferences[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceAssignments[].sourceReferences[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceAssignments[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].sourceSquareId`|string|父记录存在时必有|原课程ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].targetSquareId`|string|父记录存在时必有|目标课程ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "merge_example_1",
        "sourceSquareId": "square_example_1",
        "targetSquareId": "square_example_2",
        "reason": "课程信息指向同一班级",
        "evidenceRefs": [],
        "status": "VOTING",
        "sides": [
          {
            "squareId": "square_example_1",
            "agreeCount": 3,
            "requiredCount": 3,
            "myBallot": {},
            "eligibleCount": 4
          },
          {
            "squareId": "square_example_2",
            "agreeCount": 3,
            "requiredCount": 3,
            "myBallot": {},
            "eligibleCount": 4
          }
        ],
        "sourceAssignments": [
          {
            "id": "assignment_example_1",
            "squareId": "square_example_1",
            "name": "课程设计",
            "requirements": "完成可演示的课程协作应用",
            "origin": "TEACHER",
            "confirmationStatus": "CONFIRMED",
            "hardDueAt": "2026-11-09T15:59:59Z",
            "minMembers": 2,
            "maxMembers": 6,
            "gradingEnabled": false,
            "revision": 1,
            "sourceReferences": []
          }
        ],
        "assignmentMappings": [],
        "canCheck": true,
        "canExecute": false,
        "revision": 1
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-merge-06"></a>

### R_MERGE_06 — 提交 · 课程合并提案 / 教学事实核对

适用接口：[MERGE-06](#api-merge-06)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentMappings`|object[]|必有|合并课程时明确保持分离的作业映射；遵守本字段类型、统一上限及对应业务条件|
|`assignmentMappings[].fromAssignmentId`|string|父记录存在时必有|原作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`assignmentMappings[].keepSeparate`|bool|父记录存在时必有|作业保持分离，不能合并旧成绩；遵守本字段类型、统一上限及对应业务条件|
|`canCheck`|bool|必有|当前调用者是否可核对；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canExecute`|bool|必有|当前调用者是否可最终执行；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs`|object[]|必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sides`|object[]|必有|两个课程侧独立的冻结资格和计票；遵守本字段类型、统一上限及对应业务条件|
|`sides[].abstain`|int|父记录存在时必有|弃权票数；按当前权限及该版本业务范围返回|
|`sides[].agreeCount`|int|父记录存在时必有|当前有效同意数；遵守本字段类型、统一上限及对应业务条件|
|`sides[].disagree`|int|父记录存在时必有|反对票数；按当前权限及该版本业务范围返回|
|`sides[].eligibleCount`|int|父记录存在时必有|该课程侧冻结分母人数；遵守本字段类型、统一上限及对应业务条件|
|`sides[].myBallot`|object|父记录存在时必有|本人选票，不暴露其他人的私有意见；遵守本字段类型、统一上限及对应业务条件|
|`sides[].myBallot.at`|string|移出表决有记录时返回|本人选票时间；按当前权限及该版本业务范围返回|
|`sides[].myBallot.choice`|string|父记录存在时必有|仅本人的AGREE/DISAGREE/ABSTAIN选票；按当前权限及该版本业务范围返回|
|`sides[].myBallot.revision`|int|父记录存在时必有|本人选票版本，首次保存expectedRevision=0；按当前权限及该版本业务范围返回|
|`sides[].myBallot.squareId`|string|合并表决有记录时返回|本人所属课程侧；按当前权限及该版本业务范围返回|
|`sides[].myBallot.updatedAt`|string|本人已有选票时返回|本人合并选票服务端更新时间；不返回他人的选票记录|
|`sides[].passed`|bool|父记录存在时必有|本课程侧是否严格过半；按当前权限及该版本业务范围返回|
|`sides[].requiredCount`|int|父记录存在时必有|当前所需确认人数；遵守本字段类型、统一上限及对应业务条件|
|`sides[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`sides[].unvoted`|int|父记录存在时必有|未投票人数；按当前权限及该版本业务范围返回|
|`sourceAssignments`|object[]|必有|来源作业清单，不能丢历史；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].confirmationStatus`|string|父记录存在时必有|作业教师确认状态；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].gradingEnabled`|bool|父记录存在时必有|本作业是否启用正式教学评价；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].hardDueAt`|string|父记录存在时必有|原作业硬截止；RFC3339时间点，响应UTC Z，服务器比较有效性；可空条件见模型|
|`sourceAssignments[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`sourceAssignments[].maxMembers`|int|父记录存在时必有|作业最多成员数；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].minMembers`|int|父记录存在时必有|作业最少成员数；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].origin`|string|父记录存在时必有|固定CLIENT_BYOK_LEADER_CONFIRMED来源声明；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].requirements`|string|父记录存在时必有|作业要求或补资料要求；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceAssignments[].sourceReferences`|object[]|父记录存在时必有|报告固定来源集合；可省略默认[]，最多10个|
|`sourceAssignments[].sourceReferences[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].sourceReferences[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].sourceReferences[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`sourceAssignments[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`sourceSquareId`|string|必有|原课程ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targetSquareId`|string|必有|目标课程ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "merge_example_1",
    "sourceSquareId": "square_example_1",
    "targetSquareId": "square_example_2",
    "reason": "按实际协作情况说明",
    "evidenceRefs": [],
    "status": "READY",
    "sides": [
      {
        "squareId": "square_example_1",
        "agreeCount": 3,
        "requiredCount": 3,
        "myBallot": {},
        "eligibleCount": 4
      },
      {
        "squareId": "square_example_2",
        "agreeCount": 3,
        "requiredCount": 3,
        "myBallot": {},
        "eligibleCount": 4
      }
    ],
    "sourceAssignments": [
      {
        "id": "assignment_example_1",
        "squareId": "square_example_1",
        "name": "课程设计",
        "requirements": "完成可演示的课程协作应用",
        "origin": "TEACHER",
        "confirmationStatus": "CONFIRMED",
        "hardDueAt": "2026-11-09T15:59:59Z",
        "minMembers": 2,
        "maxMembers": 6,
        "gradingEnabled": false,
        "revision": 1,
        "sourceReferences": []
      }
    ],
    "assignmentMappings": [
      {
        "fromAssignmentId": "assignment_example_1",
        "keepSeparate": true
      }
    ],
    "canCheck": true,
    "canExecute": false,
    "revision": 2
  }
}
```

<a id="response-r-teach-02"></a>

### R_TEACH_02 — 读取 · 本人 / 本人教学工作台

适用接口：[TEACH-02](#api-teach-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].capabilities`|string[]|父记录存在时必有|本范围当前有效能力集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].claimStatus`|string|父记录存在时必有|原任务认领NOT_REQUESTED/PENDING_ORIGINAL_CLAIM/SUCCEEDED/FAILED；遵守本字段类型、统一上限及对应业务条件|
|`items[].fullCourseName`|string|父记录存在时必有|完整课程名；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].myMembershipStatus`|string|父记录存在时必有|本人当前课程/项目关系状态；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].schoolId`|string|父记录存在时必有|所属学校ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].slots`|object[]|父记录存在时必有|完整上课时段集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].slots[].customWeeks`|int[]|父记录存在时必有|CUSTOM模式明确选择的周次；遵守本字段类型、统一上限及对应业务条件|
|`items[].slots[].endWeek`|int|父记录存在时必有|结束周次；遵守本字段类型、统一上限及对应业务条件|
|`items[].slots[].periodId`|string|父记录存在时必有|所属学校学期的稳定节次ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].slots[].startWeek`|int|父记录存在时必有|起始周次；遵守本字段类型、统一上限及对应业务条件|
|`items[].slots[].weekPattern`|string|父记录存在时必有|ALL/ODD/EVEN/CUSTOM周次模式；遵守本字段类型、统一上限及对应业务条件|
|`items[].slots[].weekday`|int|父记录存在时必有|星期1～7；遵守本字段类型、统一上限及对应业务条件|
|`items[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].squareNo`|string|父记录存在时必有|稳定课程展示编号；遵守本字段类型、统一上限及对应业务条件|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].teacherName`|string|父记录存在时必有|任课教师姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].termId`|string|父记录存在时必有|所属学期ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`pendingTransfers`|object[]|必有|指定给本人的教师接任邀请；遵守本字段类型、统一上限及对应业务条件|
|`pendingTransfers[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`pendingTransfers[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`pendingTransfers[].reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`pendingTransfers[].recipientTeacherId`|string|父记录存在时必有|本次合法接任教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`pendingTransfers[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`pendingTransfers[].sourceTeacherId`|string|父记录存在时必有|原固定负责教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`pendingTransfers[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`pendingTransfers[].squareRevision`|int|父记录存在时必有|本次课程版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`pendingTransfers[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "square_example_1",
        "squareId": "square_example_1",
        "squareNo": "TEST-2026-001",
        "schoolId": "school_example_1",
        "termId": "term_example_1",
        "fullCourseName": "移动应用开发实践",
        "teacherName": "示例教师",
        "slots": [
          {
            "weekday": 3,
            "periodId": "period_example_1",
            "weekPattern": "ALL",
            "startWeek": 1,
            "endWeek": 16,
            "customWeeks": []
          }
        ],
        "status": "ACTIVE",
        "revision": 1,
        "capabilities": [
          "COURSE_READ",
          "ANNOUNCEMENT_WRITE"
        ],
        "myMembershipStatus": "ACTIVE",
        "claimStatus": "ACTIVE"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1,
    "pendingTransfers": []
  }
}
```

<a id="response-r-teach-03"></a>

### R_TEACH_03 — 读取 · 课程 / 班级助理授权

适用接口：[TEACH-03](#api-teach-03)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].active`|bool|父记录存在时必有|此授权当前是否有效；遵守本字段类型、统一上限及对应业务条件|
|`items[].capabilities`|string[]|父记录存在时必有|本范围当前有效能力集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].grantedBy`|string|父记录存在时必有|实际授予班级能力的教师ID；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "grant_example_1",
        "squareId": "square_example_1",
        "studentId": "user_example_2",
        "displayName": "示例队员",
        "capabilities": [
          "ANNOUNCEMENT_WRITE"
        ],
        "status": "ACTIVE",
        "grantedBy": "user_example_teacher",
        "expiresAt": "2027-02-03T15:59:59Z",
        "revision": 1,
        "active": true
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-teach-04"></a>

### R_TEACH_04 — 读取 · 课程 / 教学进展

适用接口：[TEACH-04](#api-teach-04)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].counts`|object|父记录存在时必有|同一真实业务逻辑派生的分类数量；遵守本字段类型、统一上限及对应业务条件|
|`items[].counts.DONE`|int|父记录存在时必有|DONE：所属响应结构中的整数值；遵守本字段类型、统一上限及对应业务条件|
|`items[].counts.IN_PROGRESS`|int|父记录存在时必有|IN_PROGRESS：所属响应结构中的整数值；遵守本字段类型、统一上限及对应业务条件|
|`items[].counts.REVIEW_PENDING`|int|父记录存在时必有|REVIEW_PENDING：所属响应结构中的整数值；遵守本字段类型、统一上限及对应业务条件|
|`items[].counts.TODO`|int|父记录存在时必有|TODO：所属响应结构中的整数值；遵守本字段类型、统一上限及对应业务条件|
|`items[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].memberCount`|int|父记录存在时必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "project_example_1",
        "projectId": "project_example_1",
        "displayName": "课程协作应用",
        "assignmentId": "assignment_example_1",
        "assignmentName": "课程设计",
        "memberCount": 4,
        "counts": {
          "TODO": 1,
          "IN_PROGRESS": 0,
          "REVIEW_PENDING": 0,
          "DONE": 0
        },
        "dueAt": "2026-11-09T15:59:59Z",
        "status": "ACTIVE"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-teach-05"></a>

### R_TEACH_05 — 提交 · 班级助理授权 / 撤销

适用接口：[TEACH-05](#api-teach-05)、[TEACH-06](#api-teach-06)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`active`|bool|必有|此授权当前是否有效；遵守本字段类型、统一上限及对应业务条件|
|`capabilities`|string[]|必有|本范围当前有效能力集合；遵守本字段类型、统一上限及对应业务条件|
|`displayName`|string|必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`grantedBy`|string|必有|实际授予班级能力的教师ID；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`studentId`|string|必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "grant_example_1",
    "squareId": "square_example_1",
    "studentId": "user_example_2",
    "displayName": "示例队员",
    "capabilities": [
      "ANNOUNCEMENT_WRITE"
    ],
    "status": "REVOKED",
    "grantedBy": "user_example_teacher",
    "expiresAt": "2027-02-03T15:59:59Z",
    "revision": 2,
    "active": false
  }
}
```

<a id="response-r-teach-07"></a>

### R_TEACH_07 — 提交 · 课程 / 教师认领

适用接口：[TEACH-07](#api-teach-07)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message`|string|必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`proofRefs`|object[]|必有|本人入课核验资料引用，最多3项；可省略默认[]，最多3个|
|`proofRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`proofRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`proofRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`proofRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`teacherId`|string|必有|当前负责教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "claim_example_1",
    "squareId": "square_example_1",
    "teacherId": "user_example_teacher",
    "status": "PENDING",
    "message": "希望参与项目协作",
    "proofRefs": [],
    "revision": 1,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-teach-08"></a>

### R_TEACH_08 — 提交 · 课程 / 教师离任

适用接口：[TEACH-08](#api-teach-08)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`teacherId`|string|必有|当前负责教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "squareId": "square_example_1",
    "teacherId": "user_example_teacher",
    "status": "RELINQUISHED",
    "revision": 2
  }
}
```

<a id="response-r-teach-09"></a>

### R_TEACH_09 — 提交 · 课程 / 教师权属转交

适用接口：[TEACH-09](#api-teach-09)、[TEACH-10](#api-teach-10)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`recipientTeacherId`|string|必有|本次合法接任教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceTeacherId`|string|必有|原固定负责教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`squareRevision`|int|必有|本次课程版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "teacher_transfer_example_1",
    "squareId": "square_example_1",
    "sourceTeacherId": "user_example_teacher",
    "recipientTeacherId": "user_example_teacher2",
    "reason": "按实际协作情况说明",
    "status": "PENDING",
    "squareRevision": 1,
    "revision": 1,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-grade-01"></a>

### R_GRADE_01 — 读取 · 作业 / 成绩复核

适用接口：[GRADE-01](#api-grade-01)、[GRADE-09](#api-grade-09)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].currentGradeSnapshotId`|string|父记录存在时必有|当前实际发布的正式成绩快照；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].evidenceRefs`|object[]|父记录存在时必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`items[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`items[].evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`items[].evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`items[].gradeSnapshotId`|string|父记录存在时必有|不可覆盖的个人成绩快照ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].issue`|string|父记录存在时必有|本人提出的复核问题；遵守本字段类型、统一上限及对应业务条件|
|`items[].resolvedGradeSnapshotId`|string/null|必有；允许null|已修订复核实际关联的新成绩快照；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].responses`|object[]|父记录存在时必有|该业务有权回应/补充记录；遵守本字段类型、统一上限及对应业务条件|
|`items[].responses[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].responses[].content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`items[].responses[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].responses[].evidenceRefs`|object[]|父记录存在时必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`items[].responses[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`items[].responses[].evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`items[].responses[].evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].responses[].evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`items[].responses[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].sourceRefs`|object[]|父记录存在时必有|固定来源引用集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceRefs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].sourceRefs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceRefs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].studentName`|string|父记录存在时必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "appeal_example_1",
        "gradeSnapshotId": "grade_snapshot_example_1",
        "assignmentId": "assignment_example_1",
        "studentId": "user_example_1",
        "studentName": "示例学生",
        "issue": "请核对本人验收点数",
        "sourceRefs": [],
        "evidenceRefs": [
          {
            "kind": "LINK",
            "url": "https://example.invalid/demo",
            "title": "示例演示材料"
          }
        ],
        "status": "PENDING",
        "responses": [],
        "currentGradeSnapshotId": "grade_snapshot_example_1",
        "resolvedGradeSnapshotId": null,
        "revision": 1,
        "createdAt": "2026-11-02T08:00:00Z"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-grade-02"></a>

### R_GRADE_02 — 读取 · 作业 / 成绩名册

适用接口：[GRADE-02](#api-grade-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].b`|int|父记录存在时必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`items[].basisDigest`|string|父记录存在时必有|本次固定教学依据摘要；遵守本字段类型、统一上限及对应业务条件|
|`items[].blockers`|string[]|父记录存在时必有|不能生效/发布/归档的具体缺口；遵守本字段类型、统一上限及对应业务条件|
|`items[].classification`|string|父记录存在时必有|FIRST/REVISION/BLOCKED/UNCHANGED；遵守本字段类型、统一上限及对应业务条件|
|`items[].delta`|int|父记录存在时必有|教师有理由的整数教学调整Δ；整数-100～100；非0时deltaReason非空|
|`items[].draft`|object|父记录存在时必有|本人私有草稿投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].draft.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].draft.delta`|int|父记录存在时必有|教师有理由的整数教学调整Δ；整数-100～100；非0时deltaReason非空|
|`items[].draft.deltaReason`|string|父记录存在时必有|教师教学调整依据；delta非0时必填1～500；0时可为空|
|`items[].draft.evidenceRefs`|object[]|父记录存在时必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`items[].draft.evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`items[].draft.evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`items[].draft.evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].draft.evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`items[].draft.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].draft.q`|int|父记录存在时必有|当前授权范围检索文字；教学草稿中另为整数Q；教学Q为整数0～100|
|`items[].draft.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].draft.ruleVersion`|int|父记录存在时必有|已确认教学规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`items[].draft.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`items[].draft.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].finalGrade`|int|父记录存在时必有|本快照最终整数成绩0～100；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`items[].ledger`|object|父记录存在时必有|教学计分来源账本投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledger.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].ledger.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledger.basisStatus`|string|父记录存在时必有|当前计分依据是否有效；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledger.gaps`|string[]|父记录存在时必有|教学计分待核对原因字符串集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledger.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].ledger.rows`|object[]|父记录存在时必有|本结构的有序行；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledger.rows[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledger.rows[].acceptanceKind`|string|父记录存在时必有|验收来源INDEPENDENT/SELF_CHECK或空字符串；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledger.rows[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledger.rows[].acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].ledger.rows[].contributionConfirmed`|bool|父记录存在时必有|本份额是否已有有效本人确认；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledger.rows[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`items[].ledger.rows[].signed`|bool|父记录存在时必有|本人对当前业务版本是否有效确认；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledger.rows[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledger.rows[].taskTitle`|string|父记录存在时必有|任务标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledger.rows[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledger.rows[].unitTitle`|string|父记录存在时必有|分项标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledger.settlement`|object|父记录存在时必有|当前显式结算投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledger.settlement.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].ledger.settlement.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledger.settlement.b`|int|父记录存在时必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`items[].ledger.settlement.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledger.settlement.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].ledger.settlement.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].ledger.settlement.ruleVersion`|int|父记录存在时必有|已确认教学规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`items[].ledger.settlement.settledAt`|string|父记录存在时必有|本次显式结算的服务端时间；UTC RFC3339时间点|
|`items[].ledger.settlement.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；与当前计分来源指纹相同才有效|
|`items[].ledger.settlement.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledger.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledger.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].previous`|object|父记录存在时必有|前一有效版本或关系投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].previous.assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.b`|int|父记录存在时必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`items[].previous.basisDigest`|string|父记录存在时必有|本次固定教学依据摘要；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.delta`|int|父记录存在时必有|教师有理由的整数教学调整Δ；整数-100～100；非0时deltaReason非空|
|`items[].previous.deltaReason`|string|父记录存在时必有|教师教学调整依据；delta非0时必填1～500；0时可为空|
|`items[].previous.evidenceRefs`|object[]|父记录存在时必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`items[].previous.evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`items[].previous.evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`items[].previous.evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`items[].previous.finalGrade`|int|父记录存在时必有|本快照最终整数成绩0～100；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`items[].previous.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].previous.ledgerSnapshot`|object|父记录存在时必有|本成绩固定的账本来源；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ledgerSnapshot.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].previous.ledgerSnapshot.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].previous.ledgerSnapshot.basisStatus`|string|父记录存在时必有|当前计分依据是否有效；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ledgerSnapshot.gaps`|string[]|父记录存在时必有|教学计分待核对原因字符串集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ledgerSnapshot.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].previous.ledgerSnapshot.rows`|object[]|父记录存在时必有|本结构的有序行；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ledgerSnapshot.rows[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].previous.ledgerSnapshot.rows[].acceptanceKind`|string|父记录存在时必有|验收来源INDEPENDENT/SELF_CHECK或空字符串；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ledgerSnapshot.rows[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ledgerSnapshot.rows[].acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].previous.ledgerSnapshot.rows[].contributionConfirmed`|bool|父记录存在时必有|本份额是否已有有效本人确认；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ledgerSnapshot.rows[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`items[].previous.ledgerSnapshot.rows[].signed`|bool|父记录存在时必有|本人对当前业务版本是否有效确认；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ledgerSnapshot.rows[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].previous.ledgerSnapshot.rows[].taskTitle`|string|父记录存在时必有|任务标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ledgerSnapshot.rows[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].previous.ledgerSnapshot.rows[].unitTitle`|string|父记录存在时必有|分项标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ledgerSnapshot.settlement`|object|父记录存在时必有|当前显式结算投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ledgerSnapshot.settlement.acceptedPoints`|string|必有；无记录子对象按模型可{}，集合[]|本人有效验收点数；0～1000000，最多2位小数的十进制字符串，份额/合计约束同时成立|
|`items[].previous.ledgerSnapshot.settlement.assignmentId`|string|必有；无记录子对象按模型可{}，集合[]|本次作业ID；非空不透明string，资源ID1～120；可null/可空条件见本条说明；不能按ID猜权限|
|`items[].previous.ledgerSnapshot.settlement.b`|int|必有；无记录子对象按模型可{}，集合[]|服务器结算的履约比例整数0～100，未就绪null；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`items[].previous.ledgerSnapshot.settlement.id`|string|必有；无记录子对象按模型可{}，集合[]|服务器生成的稳定资源ID；非空不透明string，资源ID1～120；可null/可空条件见本条说明；不能按ID猜权限|
|`items[].previous.ledgerSnapshot.settlement.responsibilityPoints`|string|必有；无记录子对象按模型可{}，集合[]|本人有效责任点数；0～1000000，最多2位小数的十进制字符串，份额/合计约束同时成立|
|`items[].previous.ledgerSnapshot.settlement.revision`|int|必有；无记录子对象按模型可{}，集合[]|资源并发版本；服务器维护且单调增加；0～999999999整数；仅首次私有草稿/本人选票等明确情形可0，现有对象必须读取正版本|
|`items[].previous.ledgerSnapshot.settlement.ruleVersion`|int|必有；无记录子对象按模型可{}，集合[]|本次采用的有效评分规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`items[].previous.ledgerSnapshot.settlement.settledAt`|string|必有；无记录子对象按模型可{}，集合[]|本次显式结算的服务端时间；UTC RFC3339时间点|
|`items[].previous.ledgerSnapshot.settlement.sourceKey`|string|必有；无记录子对象按模型可{}，集合[]|业务来源内容摘要，不能由客户端伪造；与当前计分来源指纹相同才有效|
|`items[].previous.ledgerSnapshot.settlement.studentId`|string|必有；无记录子对象按模型可{}，集合[]|本次作业学生ID；非空不透明string，资源ID1～120；可null/可空条件见本条说明；不能按ID猜权限|
|`items[].previous.ledgerSnapshot.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ledgerSnapshot.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].previous.publishedAt`|string|父记录存在时必有|服务器正式发布时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].previous.publishedBy`|string|父记录存在时必有|实际发布人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.q`|int|父记录存在时必有|当前授权范围检索文字；教学草稿中另为整数Q；教学Q为整数0～100|
|`items[].previous.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].previous.ruleSnapshot`|object|父记录存在时必有|本成绩固定的教师规则；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ruleSnapshot.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].previous.ruleSnapshot.confirmedAt`|string|父记录存在时必有|服务器本人确认时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ruleSnapshot.confirmedBy`|string|父记录存在时必有|实际确认人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ruleSnapshot.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ruleSnapshot.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].previous.ruleSnapshot.processWeight`|string|父记录存在时必有|教师确认的过程权重w（百分数字符串）；0～100的最多两位小数字符串，必须由当前教师确认|
|`items[].previous.ruleSnapshot.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].previous.ruleSnapshot.rounding`|string|父记录存在时必有|固定HALF_UP；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.ruleSnapshot.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].previous.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].previous.studentName`|string|父记录存在时必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].previous.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].q`|int|父记录存在时必有|当前授权范围检索文字；教学草稿中另为整数Q；教学Q为整数0～100|
|`items[].rule`|object|父记录存在时必有|骰子MAX/MIN/TARGET或本资源规则，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].rule.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].rule.confirmedAt`|string|父记录存在时必有|服务器本人确认时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].rule.confirmedBy`|string|父记录存在时必有|实际确认人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`items[].rule.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].rule.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].rule.processWeight`|string|父记录存在时必有|教师确认的过程权重w（百分数字符串）；0～100的最多两位小数字符串，必须由当前教师确认|
|`items[].rule.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].rule.rounding`|string|父记录存在时必有|固定HALF_UP；遵守本字段类型、统一上限及对应业务条件|
|`items[].rule.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].studentName`|string|父记录存在时必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "studentId": "user_example_1",
        "studentName": "示例学生",
        "assignmentId": "assignment_example_1",
        "classification": "FIRST",
        "basisDigest": "basis-test-1",
        "blockers": [],
        "b": 80,
        "q": 85,
        "delta": 2,
        "finalGrade": 85,
        "rule": {
          "id": "grade_rule_example_1",
          "assignmentId": "assignment_example_1",
          "version": 1,
          "processWeight": "0.40",
          "description": "履约与成果评价",
          "rounding": "HALF_UP",
          "confirmedBy": "user_example_teacher",
          "confirmedAt": "2026-11-02T08:00:00Z",
          "revision": 1
        },
        "ledger": {
          "assignmentId": "assignment_example_1",
          "studentId": "user_example_1",
          "responsibilityPoints": "10.00",
          "acceptedPoints": "8.00",
          "basisStatus": "READY",
          "sourceKey": "source-test-1",
          "rows": [
            {
              "taskId": "task_example_1",
              "taskTitle": "登录页面",
              "unitId": "unit_example_1",
              "unitTitle": "登录页面",
              "points": "10.00",
              "acceptedPoints": "8.00",
              "signed": true,
              "accepted": true,
              "contributionConfirmed": true,
              "acceptanceId": "acceptance_example_1",
              "acceptanceKind": "INDEPENDENT"
            }
          ],
          "gaps": [],
          "settlement": {}
        },
        "draft": {
          "id": "grade_draft_example_1",
          "assignmentId": "assignment_example_1",
          "studentId": "user_example_1",
          "q": 85,
          "delta": 2,
          "deltaReason": "课程量规内的专项改进",
          "evidenceRefs": [
            {
              "kind": "LINK",
              "url": "https://example.invalid/demo",
              "title": "示例演示材料"
            }
          ],
          "sourceKey": "source-test-1",
          "ruleVersion": 1,
          "revision": 1
        },
        "previous": {}
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-grade-03"></a>

### R_GRADE_03 — 读取 · 作业 / 当前教学规则

适用接口：[GRADE-03](#api-grade-03)、[GRADE-16](#api-grade-16)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`confirmedAt`|string|必有|服务器本人确认时间；遵守本字段类型、统一上限及对应业务条件|
|`confirmedBy`|string|必有|实际确认人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`description`|string|必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`processWeight`|string|必有|教师确认的过程权重w（百分数字符串）；0～100的最多两位小数字符串，必须由当前教师确认|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`rounding`|string|必有|固定HALF_UP；遵守本字段类型、统一上限及对应业务条件|
|`version`|int|必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "grade_rule_example_1",
    "assignmentId": "assignment_example_1",
    "version": 1,
    "processWeight": "0.40",
    "description": "履约与成果评价",
    "rounding": "HALF_UP",
    "confirmedBy": "user_example_teacher",
    "confirmedAt": "2026-11-02T08:00:00Z",
    "revision": 1
  }
}
```

<a id="response-r-grade-04"></a>

### R_GRADE_04 — 读取 · 作业 / 学生 / 成绩历史

适用接口：[GRADE-04](#api-grade-04)、[GRADE-10](#api-grade-10)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].b`|int|父记录存在时必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`items[].basisDigest`|string|父记录存在时必有|本次固定教学依据摘要；遵守本字段类型、统一上限及对应业务条件|
|`items[].delta`|int|父记录存在时必有|教师有理由的整数教学调整Δ；整数-100～100；非0时deltaReason非空|
|`items[].deltaReason`|string|父记录存在时必有|教师教学调整依据；delta非0时必填1～500；0时可为空|
|`items[].evidenceRefs`|object[]|父记录存在时必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`items[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`items[].evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`items[].evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`items[].finalGrade`|int|父记录存在时必有|本快照最终整数成绩0～100；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledgerSnapshot`|object|父记录存在时必有|本成绩固定的账本来源；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledgerSnapshot.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].ledgerSnapshot.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledgerSnapshot.basisStatus`|string|父记录存在时必有|当前计分依据是否有效；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledgerSnapshot.gaps`|string[]|父记录存在时必有|教学计分待核对原因字符串集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledgerSnapshot.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].ledgerSnapshot.rows`|object[]|父记录存在时必有|本结构的有序行；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledgerSnapshot.rows[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledgerSnapshot.rows[].acceptanceKind`|string|父记录存在时必有|验收来源INDEPENDENT/SELF_CHECK或空字符串；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledgerSnapshot.rows[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledgerSnapshot.rows[].acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].ledgerSnapshot.rows[].contributionConfirmed`|bool|父记录存在时必有|本份额是否已有有效本人确认；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledgerSnapshot.rows[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`items[].ledgerSnapshot.rows[].signed`|bool|父记录存在时必有|本人对当前业务版本是否有效确认；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledgerSnapshot.rows[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledgerSnapshot.rows[].taskTitle`|string|父记录存在时必有|任务标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledgerSnapshot.rows[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledgerSnapshot.rows[].unitTitle`|string|父记录存在时必有|分项标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledgerSnapshot.settlement`|object|父记录存在时必有|当前显式结算投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledgerSnapshot.settlement.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].ledgerSnapshot.settlement.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledgerSnapshot.settlement.b`|int|父记录存在时必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`items[].ledgerSnapshot.settlement.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledgerSnapshot.settlement.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].ledgerSnapshot.settlement.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].ledgerSnapshot.settlement.ruleVersion`|int|父记录存在时必有|已确认教学规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`items[].ledgerSnapshot.settlement.settledAt`|string|父记录存在时必有|本次显式结算的服务端时间；UTC RFC3339时间点|
|`items[].ledgerSnapshot.settlement.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；与当前计分来源指纹相同才有效|
|`items[].ledgerSnapshot.settlement.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ledgerSnapshot.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`items[].ledgerSnapshot.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].publishedAt`|string|父记录存在时必有|服务器正式发布时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].publishedBy`|string|父记录存在时必有|实际发布人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`items[].q`|int|父记录存在时必有|当前授权范围检索文字；教学草稿中另为整数Q；教学Q为整数0～100|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].ruleSnapshot`|object|父记录存在时必有|本成绩固定的教师规则；遵守本字段类型、统一上限及对应业务条件|
|`items[].ruleSnapshot.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ruleSnapshot.confirmedAt`|string|父记录存在时必有|服务器本人确认时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].ruleSnapshot.confirmedBy`|string|父记录存在时必有|实际确认人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`items[].ruleSnapshot.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].ruleSnapshot.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].ruleSnapshot.processWeight`|string|父记录存在时必有|教师确认的过程权重w（百分数字符串）；0～100的最多两位小数字符串，必须由当前教师确认|
|`items[].ruleSnapshot.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].ruleSnapshot.rounding`|string|父记录存在时必有|固定HALF_UP；遵守本字段类型、统一上限及对应业务条件|
|`items[].ruleSnapshot.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].studentName`|string|父记录存在时必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "grade_snapshot_example_1",
        "assignmentId": "assignment_example_1",
        "assignmentName": "课程设计",
        "squareId": "square_example_1",
        "studentId": "user_example_1",
        "studentName": "示例学生",
        "version": 1,
        "b": 80,
        "q": 85,
        "delta": 2,
        "finalGrade": 85,
        "ruleSnapshot": {
          "id": "grade_rule_example_1",
          "assignmentId": "assignment_example_1",
          "version": 1,
          "processWeight": "0.40",
          "description": "履约与成果评价",
          "rounding": "HALF_UP",
          "confirmedBy": "user_example_teacher",
          "confirmedAt": "2026-11-02T08:00:00Z",
          "revision": 1
        },
        "ledgerSnapshot": {
          "assignmentId": "assignment_example_1",
          "studentId": "user_example_1",
          "responsibilityPoints": "10.00",
          "acceptedPoints": "8.00",
          "basisStatus": "READY",
          "sourceKey": "source-test-1",
          "rows": [
            {
              "taskId": "task_example_1",
              "taskTitle": "登录页面",
              "unitId": "unit_example_1",
              "unitTitle": "登录页面",
              "points": "10.00",
              "acceptedPoints": "8.00",
              "signed": true,
              "accepted": true,
              "contributionConfirmed": true,
              "acceptanceId": "acceptance_example_1",
              "acceptanceKind": "INDEPENDENT"
            }
          ],
          "gaps": [],
          "settlement": {}
        },
        "evidenceRefs": [
          {
            "kind": "LINK",
            "url": "https://example.invalid/demo",
            "title": "示例演示材料"
          }
        ],
        "deltaReason": "课程量规内的专项改进",
        "basisDigest": "basis-test-1",
        "publishedBy": "user_example_teacher",
        "publishedAt": "2026-11-02T08:00:00Z",
        "revision": 1
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-grade-05"></a>

### R_GRADE_05 — 读取 · 作业 / 学生 / 教学账本

适用接口：[GRADE-05](#api-grade-05)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`acceptedPoints`|string|必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`basisStatus`|string|必有|当前计分依据是否有效；遵守本字段类型、统一上限及对应业务条件|
|`gaps`|string[]|必有|教学计分待核对原因字符串集合；遵守本字段类型、统一上限及对应业务条件|
|`responsibilityPoints`|string|必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`rows`|object[]|必有|本结构的有序行；遵守本字段类型、统一上限及对应业务条件|
|`rows[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].acceptanceKind`|string|父记录存在时必有|验收来源INDEPENDENT/SELF_CHECK或空字符串；遵守本字段类型、统一上限及对应业务条件|
|`rows[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`rows[].acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`rows[].contributionConfirmed`|bool|父记录存在时必有|本份额是否已有有效本人确认；遵守本字段类型、统一上限及对应业务条件|
|`rows[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`rows[].signed`|bool|父记录存在时必有|本人对当前业务版本是否有效确认；遵守本字段类型、统一上限及对应业务条件|
|`rows[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].taskTitle`|string|父记录存在时必有|任务标题；遵守本字段类型、统一上限及对应业务条件|
|`rows[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].unitTitle`|string|父记录存在时必有|分项标题；遵守本字段类型、统一上限及对应业务条件|
|`settlement`|object|必有|当前显式结算投影；遵守本字段类型、统一上限及对应业务条件|
|`settlement.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`settlement.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`settlement.b`|int|父记录存在时必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`settlement.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`settlement.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`settlement.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`settlement.ruleVersion`|int|父记录存在时必有|已确认教学规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`settlement.settledAt`|string|父记录存在时必有|本次显式结算的服务端时间；UTC RFC3339时间点|
|`settlement.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；与当前计分来源指纹相同才有效|
|`settlement.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`sourceKey`|string|必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`studentId`|string|必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "assignmentId": "assignment_example_1",
    "studentId": "user_example_1",
    "responsibilityPoints": "10.00",
    "acceptedPoints": "8.00",
    "basisStatus": "READY",
    "sourceKey": "source-test-1",
    "rows": [
      {
        "taskId": "task_example_1",
        "taskTitle": "登录页面",
        "unitId": "unit_example_1",
        "unitTitle": "登录页面",
        "points": "10.00",
        "acceptedPoints": "8.00",
        "signed": true,
        "accepted": true,
        "contributionConfirmed": true,
        "acceptanceId": "acceptance_example_1",
        "acceptanceKind": "INDEPENDENT"
      }
    ],
    "gaps": [],
    "settlement": {}
  }
}
```

<a id="response-r-grade-06"></a>

### R_GRADE_06 — 读取 · 作业 / 学生 / 教师文字评价

适用接口：[GRADE-06](#api-grade-06)、[GRADE-12](#api-grade-12)、[GRADE-21](#api-grade-21)、[GRADE-22](#api-grade-22)、[GRADE-24](#api-grade-24)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentName`|string|必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`canConsent`|bool|必有|当前调用者是否可授权展示；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canEdit`|bool|必有|当前调用者是否可编辑；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canWithdraw`|bool|必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`profileVisible`|bool|必有|当前查看者是否可见画像；遵守本字段类型、统一上限及对应业务条件|
|`sourceCurrent`|bool|必有|来源是否仍为当前基准；遵守本字段类型、统一上限及对应业务条件|
|`sourceGradeSnapshotId`|string|必有|评价所依据的正式成绩快照ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`studentName`|string|必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`summary`|object|必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`summary.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`summary.assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`summary.consent`|object|仅评价对象本人读取|本人对确切评价内容版的展示授权；遵守本字段类型、统一上限及对应业务条件|
|`summary.consent.contentVersion`|int|仅评价对象本人读取|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`summary.consent.decision`|string|仅评价对象本人读取|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`summary.courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`summary.history`|object[]|父记录存在时必有|按当前权限返回的处理/版本历史；遵守本字段类型、统一上限及对应业务条件|
|`summary.history[].action`|string|父记录存在时必有|本人明确执行的动作；遵守本字段类型、统一上限及对应业务条件|
|`summary.history[].actorId`|string|父记录存在时必有|本事件实际操作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`summary.history[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`summary.history[].reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`summary.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`summary.publishedAt`|string|父记录存在时必有|服务器正式发布时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`summary.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`summary.sourceGradeSnapshotId`|string|父记录存在时必有|评价所依据的正式成绩快照ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`summary.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`summary.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`summary.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`summary.studentName`|string|父记录存在时必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`summary.summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`summary.teacherId`|string|父记录存在时必有|当前负责教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`summary.teacherName`|string|父记录存在时必有|任课教师姓名；遵守本字段类型、统一上限及对应业务条件|
|`summary.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`summary.versions`|object[]|父记录存在时必有|内容历史版本，仅作者或有权处理者；遵守本字段类型、统一上限及对应业务条件|
|`summary.versions[].content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`summary.versions[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`summary.versions[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`summary.versions[].updatedAt`|string|父记录存在时必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`summary.versions[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "summary": {
      "id": "teacher_summary_example_1",
      "assignmentId": "assignment_example_1",
      "studentId": "user_example_1",
      "summary": "能够按标准完成客户端工作",
      "sourceGradeSnapshotId": "grade_snapshot_example_1",
      "version": 1,
      "status": "ACTIVE",
      "history": [],
      "revision": 1,
      "studentName": "示例学生",
      "courseName": "移动应用开发实践",
      "assignmentName": "课程设计",
      "teacherId": "user_example_teacher",
      "teacherName": "示例教师",
      "squareId": "square_example_1",
      "versions": [],
      "publishedAt": "2026-11-02T08:00:00Z"
    },
    "sourceGradeSnapshotId": "grade_snapshot_example_1",
    "canEdit": true,
    "canConsent": false,
    "studentName": "示例学生",
    "assignmentName": "课程设计",
    "sourceCurrent": true,
    "canWithdraw": true,
    "profileVisible": false
  }
}
```

<a id="response-r-grade-07"></a>

### R_GRADE_07 — 读取 · 成绩复核

适用接口：[GRADE-07](#api-grade-07)、[GRADE-19](#api-grade-19)、[GRADE-20](#api-grade-20)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`currentGradeSnapshotId`|string|必有|当前实际发布的正式成绩快照；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`evidenceRefs`|object[]|必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`gradeSnapshotId`|string|必有|不可覆盖的个人成绩快照ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`issue`|string|必有|本人提出的复核问题；遵守本字段类型、统一上限及对应业务条件|
|`resolvedGradeSnapshotId`|string/null|必有；允许null|已修订复核实际关联的新成绩快照；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`responses`|object[]|必有|该业务有权回应/补充记录；遵守本字段类型、统一上限及对应业务条件|
|`responses[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`responses[].content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`responses[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`responses[].evidenceRefs`|object[]|父记录存在时必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`responses[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`responses[].evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`responses[].evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`responses[].evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`responses[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceRefs`|object[]|必有|固定来源引用集合；遵守本字段类型、统一上限及对应业务条件|
|`sourceRefs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`sourceRefs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`sourceRefs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`studentId`|string|必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`studentName`|string|必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "appeal_example_1",
    "gradeSnapshotId": "grade_snapshot_example_1",
    "assignmentId": "assignment_example_1",
    "studentId": "user_example_1",
    "studentName": "示例学生",
    "issue": "请核对本人验收点数",
    "sourceRefs": [],
    "evidenceRefs": [
      {
        "kind": "LINK",
        "url": "https://example.invalid/demo",
        "title": "示例演示材料"
      }
    ],
    "status": "PENDING",
    "responses": [],
    "currentGradeSnapshotId": "grade_snapshot_example_1",
    "resolvedGradeSnapshotId": null,
    "revision": 1,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-grade-08"></a>

### R_GRADE_08 — 读取 · 正式成绩快照

适用接口：[GRADE-08](#api-grade-08)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`assignmentName`|string|必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`b`|int|必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`basisDigest`|string|必有|本次固定教学依据摘要；遵守本字段类型、统一上限及对应业务条件|
|`delta`|int|必有|教师有理由的整数教学调整Δ；整数-100～100；非0时deltaReason非空|
|`deltaReason`|string|必有|教师教学调整依据；delta非0时必填1～500；0时可为空|
|`evidenceRefs`|object[]|必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`finalGrade`|int|必有|本快照最终整数成绩0～100；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ledgerSnapshot`|object|必有|本成绩固定的账本来源；遵守本字段类型、统一上限及对应业务条件|
|`ledgerSnapshot.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`ledgerSnapshot.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ledgerSnapshot.basisStatus`|string|父记录存在时必有|当前计分依据是否有效；遵守本字段类型、统一上限及对应业务条件|
|`ledgerSnapshot.gaps`|string[]|父记录存在时必有|教学计分待核对原因字符串集合；遵守本字段类型、统一上限及对应业务条件|
|`ledgerSnapshot.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`ledgerSnapshot.rows`|object[]|父记录存在时必有|本结构的有序行；遵守本字段类型、统一上限及对应业务条件|
|`ledgerSnapshot.rows[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ledgerSnapshot.rows[].acceptanceKind`|string|父记录存在时必有|验收来源INDEPENDENT/SELF_CHECK或空字符串；遵守本字段类型、统一上限及对应业务条件|
|`ledgerSnapshot.rows[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`ledgerSnapshot.rows[].acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`ledgerSnapshot.rows[].contributionConfirmed`|bool|父记录存在时必有|本份额是否已有有效本人确认；遵守本字段类型、统一上限及对应业务条件|
|`ledgerSnapshot.rows[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`ledgerSnapshot.rows[].signed`|bool|父记录存在时必有|本人对当前业务版本是否有效确认；遵守本字段类型、统一上限及对应业务条件|
|`ledgerSnapshot.rows[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ledgerSnapshot.rows[].taskTitle`|string|父记录存在时必有|任务标题；遵守本字段类型、统一上限及对应业务条件|
|`ledgerSnapshot.rows[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ledgerSnapshot.rows[].unitTitle`|string|父记录存在时必有|分项标题；遵守本字段类型、统一上限及对应业务条件|
|`ledgerSnapshot.settlement`|object|父记录存在时必有|当前显式结算投影；遵守本字段类型、统一上限及对应业务条件|
|`ledgerSnapshot.settlement.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`ledgerSnapshot.settlement.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ledgerSnapshot.settlement.b`|int|父记录存在时必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`ledgerSnapshot.settlement.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ledgerSnapshot.settlement.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`ledgerSnapshot.settlement.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`ledgerSnapshot.settlement.ruleVersion`|int|父记录存在时必有|已确认教学规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`ledgerSnapshot.settlement.settledAt`|string|父记录存在时必有|本次显式结算的服务端时间；UTC RFC3339时间点|
|`ledgerSnapshot.settlement.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；与当前计分来源指纹相同才有效|
|`ledgerSnapshot.settlement.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ledgerSnapshot.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`ledgerSnapshot.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`publishedAt`|string|必有|服务器正式发布时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`publishedBy`|string|必有|实际发布人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`q`|int|必有|当前授权范围检索文字；教学草稿中另为整数Q；教学Q为整数0～100|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`ruleSnapshot`|object|必有|本成绩固定的教师规则；遵守本字段类型、统一上限及对应业务条件|
|`ruleSnapshot.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ruleSnapshot.confirmedAt`|string|父记录存在时必有|服务器本人确认时间；遵守本字段类型、统一上限及对应业务条件|
|`ruleSnapshot.confirmedBy`|string|父记录存在时必有|实际确认人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`ruleSnapshot.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`ruleSnapshot.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ruleSnapshot.processWeight`|string|父记录存在时必有|教师确认的过程权重w（百分数字符串）；0～100的最多两位小数字符串，必须由当前教师确认|
|`ruleSnapshot.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`ruleSnapshot.rounding`|string|父记录存在时必有|固定HALF_UP；遵守本字段类型、统一上限及对应业务条件|
|`ruleSnapshot.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`studentId`|string|必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`studentName`|string|必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`version`|int|必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "grade_snapshot_example_1",
    "assignmentId": "assignment_example_1",
    "assignmentName": "课程设计",
    "squareId": "square_example_1",
    "studentId": "user_example_1",
    "studentName": "示例学生",
    "version": 1,
    "b": 80,
    "q": 85,
    "delta": 2,
    "finalGrade": 85,
    "ruleSnapshot": {
      "id": "grade_rule_example_1",
      "assignmentId": "assignment_example_1",
      "version": 1,
      "processWeight": "0.40",
      "description": "履约与成果评价",
      "rounding": "HALF_UP",
      "confirmedBy": "user_example_teacher",
      "confirmedAt": "2026-11-02T08:00:00Z",
      "revision": 1
    },
    "ledgerSnapshot": {
      "assignmentId": "assignment_example_1",
      "studentId": "user_example_1",
      "responsibilityPoints": "10.00",
      "acceptedPoints": "8.00",
      "basisStatus": "READY",
      "sourceKey": "source-test-1",
      "rows": [
        {
          "taskId": "task_example_1",
          "taskTitle": "登录页面",
          "unitId": "unit_example_1",
          "unitTitle": "登录页面",
          "points": "10.00",
          "acceptedPoints": "8.00",
          "signed": true,
          "accepted": true,
          "contributionConfirmed": true,
          "acceptanceId": "acceptance_example_1",
          "acceptanceKind": "INDEPENDENT"
        }
      ],
      "gaps": [],
      "settlement": {}
    },
    "evidenceRefs": [
      {
        "kind": "LINK",
        "url": "https://example.invalid/demo",
        "title": "示例演示材料"
      }
    ],
    "deltaReason": "课程量规内的专项改进",
    "basisDigest": "basis-test-1",
    "publishedBy": "user_example_teacher",
    "publishedAt": "2026-11-02T08:00:00Z",
    "revision": 1
  }
}
```

<a id="response-r-grade-11"></a>

### R_GRADE_11 — 读取 · 本人 / 教师文字评价

适用接口：[GRADE-11](#api-grade-11)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].canConsent`|bool|父记录存在时必有|当前调用者是否可授权展示；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canEdit`|bool|父记录存在时必有|当前调用者是否可编辑；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].canWithdraw`|bool|父记录存在时必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].profileVisible`|bool|父记录存在时必有|当前查看者是否可见画像；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceCurrent`|bool|父记录存在时必有|来源是否仍为当前基准；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceGradeSnapshotId`|string|父记录存在时必有|评价所依据的正式成绩快照ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].studentName`|string|父记录存在时必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary`|object|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].summary.assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.consent`|object|仅评价对象本人读取|本人对确切评价内容版的展示授权；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.consent.contentVersion`|int|仅评价对象本人读取|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`items[].summary.consent.decision`|string|仅评价对象本人读取|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.history`|object[]|父记录存在时必有|按当前权限返回的处理/版本历史；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.history[].action`|string|父记录存在时必有|本人明确执行的动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.history[].actorId`|string|父记录存在时必有|本事件实际操作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].summary.history[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.history[].reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].summary.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].summary.publishedAt`|string|父记录存在时必有|服务器正式发布时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].summary.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].summary.sourceGradeSnapshotId`|string|父记录存在时必有|评价所依据的正式成绩快照ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].summary.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].summary.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].summary.studentName`|string|父记录存在时必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.teacherId`|string|父记录存在时必有|当前负责教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].summary.teacherName`|string|父记录存在时必有|任课教师姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.versions`|object[]|父记录存在时必有|内容历史版本，仅作者或有权处理者；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.versions[].content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.versions[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].summary.versions[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].summary.versions[].updatedAt`|string|父记录存在时必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].summary.versions[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "summary": {
          "id": "teacher_summary_example_1",
          "assignmentId": "assignment_example_1",
          "studentId": "user_example_1",
          "summary": "能够按标准完成客户端工作",
          "sourceGradeSnapshotId": "grade_snapshot_example_1",
          "version": 1,
          "status": "ACTIVE",
          "consent": {
            "decision": "HIDE",
            "contentVersion": 1
          },
          "history": [],
          "revision": 1,
          "studentName": "示例学生",
          "courseName": "移动应用开发实践",
          "assignmentName": "课程设计",
          "teacherId": "user_example_teacher",
          "teacherName": "示例教师",
          "squareId": "square_example_1",
          "versions": [],
          "publishedAt": "2026-11-02T08:00:00Z"
        },
        "sourceGradeSnapshotId": "grade_snapshot_example_1",
        "canEdit": false,
        "canConsent": true,
        "studentName": "示例学生",
        "assignmentName": "课程设计",
        "sourceCurrent": true,
        "canWithdraw": false,
        "profileVisible": false
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-grade-13"></a>

### R_GRADE_13 — 提交 · 作业 / 成绩发布预览

适用接口：[GRADE-13](#api-grade-13)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`previewToken`|string|必有|固定本人/范围/输入/来源及期限的预览凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`rows`|object[]|必有|本结构的有序行；遵守本字段类型、统一上限及对应业务条件|
|`rows[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].b`|int|父记录存在时必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`rows[].basisDigest`|string|父记录存在时必有|本次固定教学依据摘要；遵守本字段类型、统一上限及对应业务条件|
|`rows[].blockers`|string[]|父记录存在时必有|不能生效/发布/归档的具体缺口；遵守本字段类型、统一上限及对应业务条件|
|`rows[].classification`|string|父记录存在时必有|FIRST/REVISION/BLOCKED/UNCHANGED；遵守本字段类型、统一上限及对应业务条件|
|`rows[].delta`|int|父记录存在时必有|教师有理由的整数教学调整Δ；整数-100～100；非0时deltaReason非空|
|`rows[].draft`|object|父记录存在时必有|本人私有草稿投影；遵守本字段类型、统一上限及对应业务条件|
|`rows[].draft.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].draft.delta`|int|父记录存在时必有|教师有理由的整数教学调整Δ；整数-100～100；非0时deltaReason非空|
|`rows[].draft.deltaReason`|string|父记录存在时必有|教师教学调整依据；delta非0时必填1～500；0时可为空|
|`rows[].draft.evidenceRefs`|object[]|父记录存在时必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`rows[].draft.evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`rows[].draft.evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`rows[].draft.evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`rows[].draft.evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`rows[].draft.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].draft.q`|int|父记录存在时必有|当前授权范围检索文字；教学草稿中另为整数Q；教学Q为整数0～100|
|`rows[].draft.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`rows[].draft.ruleVersion`|int|父记录存在时必有|已确认教学规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`rows[].draft.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`rows[].draft.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].finalGrade`|int|父记录存在时必有|本快照最终整数成绩0～100；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`rows[].ledger`|object|父记录存在时必有|教学计分来源账本投影；遵守本字段类型、统一上限及对应业务条件|
|`rows[].ledger.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`rows[].ledger.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].ledger.basisStatus`|string|父记录存在时必有|当前计分依据是否有效；遵守本字段类型、统一上限及对应业务条件|
|`rows[].ledger.gaps`|string[]|父记录存在时必有|教学计分待核对原因字符串集合；遵守本字段类型、统一上限及对应业务条件|
|`rows[].ledger.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`rows[].ledger.rows`|object[]|父记录存在时必有|本结构的有序行；遵守本字段类型、统一上限及对应业务条件|
|`rows[].ledger.rows[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].ledger.rows[].acceptanceKind`|string|父记录存在时必有|验收来源INDEPENDENT/SELF_CHECK或空字符串；遵守本字段类型、统一上限及对应业务条件|
|`rows[].ledger.rows[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`rows[].ledger.rows[].acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`rows[].ledger.rows[].contributionConfirmed`|bool|父记录存在时必有|本份额是否已有有效本人确认；遵守本字段类型、统一上限及对应业务条件|
|`rows[].ledger.rows[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`rows[].ledger.rows[].signed`|bool|父记录存在时必有|本人对当前业务版本是否有效确认；遵守本字段类型、统一上限及对应业务条件|
|`rows[].ledger.rows[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].ledger.rows[].taskTitle`|string|父记录存在时必有|任务标题；遵守本字段类型、统一上限及对应业务条件|
|`rows[].ledger.rows[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].ledger.rows[].unitTitle`|string|父记录存在时必有|分项标题；遵守本字段类型、统一上限及对应业务条件|
|`rows[].ledger.settlement`|object|父记录存在时必有|当前显式结算投影；遵守本字段类型、统一上限及对应业务条件|
|`rows[].ledger.settlement.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`rows[].ledger.settlement.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].ledger.settlement.b`|int|父记录存在时必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`rows[].ledger.settlement.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].ledger.settlement.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`rows[].ledger.settlement.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`rows[].ledger.settlement.ruleVersion`|int|父记录存在时必有|已确认教学规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`rows[].ledger.settlement.settledAt`|string|父记录存在时必有|本次显式结算的服务端时间；UTC RFC3339时间点|
|`rows[].ledger.settlement.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；与当前计分来源指纹相同才有效|
|`rows[].ledger.settlement.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].ledger.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`rows[].ledger.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].previous`|object|父记录存在时必有|前一有效版本或关系投影；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].previous.assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.b`|int|父记录存在时必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`rows[].previous.basisDigest`|string|父记录存在时必有|本次固定教学依据摘要；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.delta`|int|父记录存在时必有|教师有理由的整数教学调整Δ；整数-100～100；非0时deltaReason非空|
|`rows[].previous.deltaReason`|string|父记录存在时必有|教师教学调整依据；delta非0时必填1～500；0时可为空|
|`rows[].previous.evidenceRefs`|object[]|父记录存在时必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`rows[].previous.evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`rows[].previous.evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`rows[].previous.evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`rows[].previous.finalGrade`|int|父记录存在时必有|本快照最终整数成绩0～100；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`rows[].previous.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].previous.ledgerSnapshot`|object|父记录存在时必有|本成绩固定的账本来源；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ledgerSnapshot.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`rows[].previous.ledgerSnapshot.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].previous.ledgerSnapshot.basisStatus`|string|父记录存在时必有|当前计分依据是否有效；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ledgerSnapshot.gaps`|string[]|父记录存在时必有|教学计分待核对原因字符串集合；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ledgerSnapshot.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`rows[].previous.ledgerSnapshot.rows`|object[]|父记录存在时必有|本结构的有序行；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ledgerSnapshot.rows[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].previous.ledgerSnapshot.rows[].acceptanceKind`|string|父记录存在时必有|验收来源INDEPENDENT/SELF_CHECK或空字符串；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ledgerSnapshot.rows[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ledgerSnapshot.rows[].acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`rows[].previous.ledgerSnapshot.rows[].contributionConfirmed`|bool|父记录存在时必有|本份额是否已有有效本人确认；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ledgerSnapshot.rows[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`rows[].previous.ledgerSnapshot.rows[].signed`|bool|父记录存在时必有|本人对当前业务版本是否有效确认；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ledgerSnapshot.rows[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].previous.ledgerSnapshot.rows[].taskTitle`|string|父记录存在时必有|任务标题；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ledgerSnapshot.rows[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].previous.ledgerSnapshot.rows[].unitTitle`|string|父记录存在时必有|分项标题；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ledgerSnapshot.settlement`|object|父记录存在时必有|当前显式结算投影；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ledgerSnapshot.settlement.acceptedPoints`|string|必有；无记录子对象按模型可{}，集合[]|本人有效验收点数；0～1000000，最多2位小数的十进制字符串，份额/合计约束同时成立|
|`rows[].previous.ledgerSnapshot.settlement.assignmentId`|string|必有；无记录子对象按模型可{}，集合[]|本次作业ID；非空不透明string，资源ID1～120；可null/可空条件见本条说明；不能按ID猜权限|
|`rows[].previous.ledgerSnapshot.settlement.b`|int|必有；无记录子对象按模型可{}，集合[]|服务器结算的履约比例整数0～100，未就绪null；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`rows[].previous.ledgerSnapshot.settlement.id`|string|必有；无记录子对象按模型可{}，集合[]|服务器生成的稳定资源ID；非空不透明string，资源ID1～120；可null/可空条件见本条说明；不能按ID猜权限|
|`rows[].previous.ledgerSnapshot.settlement.responsibilityPoints`|string|必有；无记录子对象按模型可{}，集合[]|本人有效责任点数；0～1000000，最多2位小数的十进制字符串，份额/合计约束同时成立|
|`rows[].previous.ledgerSnapshot.settlement.revision`|int|必有；无记录子对象按模型可{}，集合[]|资源并发版本；服务器维护且单调增加；0～999999999整数；仅首次私有草稿/本人选票等明确情形可0，现有对象必须读取正版本|
|`rows[].previous.ledgerSnapshot.settlement.ruleVersion`|int|必有；无记录子对象按模型可{}，集合[]|本次采用的有效评分规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`rows[].previous.ledgerSnapshot.settlement.settledAt`|string|必有；无记录子对象按模型可{}，集合[]|本次显式结算的服务端时间；UTC RFC3339时间点|
|`rows[].previous.ledgerSnapshot.settlement.sourceKey`|string|必有；无记录子对象按模型可{}，集合[]|业务来源内容摘要，不能由客户端伪造；与当前计分来源指纹相同才有效|
|`rows[].previous.ledgerSnapshot.settlement.studentId`|string|必有；无记录子对象按模型可{}，集合[]|本次作业学生ID；非空不透明string，资源ID1～120；可null/可空条件见本条说明；不能按ID猜权限|
|`rows[].previous.ledgerSnapshot.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ledgerSnapshot.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].previous.publishedAt`|string|父记录存在时必有|服务器正式发布时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`rows[].previous.publishedBy`|string|父记录存在时必有|实际发布人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.q`|int|父记录存在时必有|当前授权范围检索文字；教学草稿中另为整数Q；教学Q为整数0～100|
|`rows[].previous.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`rows[].previous.ruleSnapshot`|object|父记录存在时必有|本成绩固定的教师规则；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ruleSnapshot.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].previous.ruleSnapshot.confirmedAt`|string|父记录存在时必有|服务器本人确认时间；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ruleSnapshot.confirmedBy`|string|父记录存在时必有|实际确认人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ruleSnapshot.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ruleSnapshot.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].previous.ruleSnapshot.processWeight`|string|父记录存在时必有|教师确认的过程权重w（百分数字符串）；0～100的最多两位小数字符串，必须由当前教师确认|
|`rows[].previous.ruleSnapshot.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`rows[].previous.ruleSnapshot.rounding`|string|父记录存在时必有|固定HALF_UP；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.ruleSnapshot.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].previous.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].previous.studentName`|string|父记录存在时必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`rows[].previous.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`rows[].q`|int|父记录存在时必有|当前授权范围检索文字；教学草稿中另为整数Q；教学Q为整数0～100|
|`rows[].rule`|object|父记录存在时必有|骰子MAX/MIN/TARGET或本资源规则，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`rows[].rule.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].rule.confirmedAt`|string|父记录存在时必有|服务器本人确认时间；遵守本字段类型、统一上限及对应业务条件|
|`rows[].rule.confirmedBy`|string|父记录存在时必有|实际确认人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`rows[].rule.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`rows[].rule.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].rule.processWeight`|string|父记录存在时必有|教师确认的过程权重w（百分数字符串）；0～100的最多两位小数字符串，必须由当前教师确认|
|`rows[].rule.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`rows[].rule.rounding`|string|父记录存在时必有|固定HALF_UP；遵守本字段类型、统一上限及对应业务条件|
|`rows[].rule.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`rows[].studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rows[].studentName`|string|父记录存在时必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`studentIds`|string[]|必有|明确选择的学生集合；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "previewToken": "grade-preview-test",
    "expiresAt": "2026-11-02T08:10:00Z",
    "rows": [
      {
        "studentId": "user_example_1",
        "studentName": "示例学生",
        "assignmentId": "assignment_example_1",
        "classification": "FIRST",
        "basisDigest": "basis-test-1",
        "blockers": [],
        "b": 80,
        "q": 85,
        "delta": 2,
        "finalGrade": 85,
        "rule": {
          "id": "grade_rule_example_1",
          "assignmentId": "assignment_example_1",
          "version": 1,
          "processWeight": "0.40",
          "description": "履约与成果评价",
          "rounding": "HALF_UP",
          "confirmedBy": "user_example_teacher",
          "confirmedAt": "2026-11-02T08:00:00Z",
          "revision": 1
        },
        "ledger": {
          "assignmentId": "assignment_example_1",
          "studentId": "user_example_1",
          "responsibilityPoints": "10.00",
          "acceptedPoints": "8.00",
          "basisStatus": "READY",
          "sourceKey": "source-test-1",
          "rows": [
            {
              "taskId": "task_example_1",
              "taskTitle": "登录页面",
              "unitId": "unit_example_1",
              "unitTitle": "登录页面",
              "points": "10.00",
              "acceptedPoints": "8.00",
              "signed": true,
              "accepted": true,
              "contributionConfirmed": true,
              "acceptanceId": "acceptance_example_1",
              "acceptanceKind": "INDEPENDENT"
            }
          ],
          "gaps": [],
          "settlement": {}
        },
        "draft": {
          "id": "grade_draft_example_1",
          "assignmentId": "assignment_example_1",
          "studentId": "user_example_1",
          "q": 85,
          "delta": 2,
          "deltaReason": "课程量规内的专项改进",
          "evidenceRefs": [
            {
              "kind": "LINK",
              "url": "https://example.invalid/demo",
              "title": "示例演示材料"
            }
          ],
          "sourceKey": "source-test-1",
          "ruleVersion": 1,
          "revision": 1
        },
        "previous": {}
      }
    ],
    "studentIds": [
      "user_example_1"
    ]
  }
}
```

<a id="response-r-grade-14"></a>

### R_GRADE_14 — 提交 · 作业 / 成绩正式发布

适用接口：[GRADE-14](#api-grade-14)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`grades`|object[]|必有|grades：所属响应结构中的有序记录集合；遵守本字段类型、统一上限及对应业务条件|
|`grades[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`grades[].b`|int|父记录存在时必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`grades[].basisDigest`|string|父记录存在时必有|本次固定教学依据摘要；遵守本字段类型、统一上限及对应业务条件|
|`grades[].delta`|int|父记录存在时必有|教师有理由的整数教学调整Δ；整数-100～100；非0时deltaReason非空|
|`grades[].deltaReason`|string|父记录存在时必有|教师教学调整依据；delta非0时必填1～500；0时可为空|
|`grades[].evidenceRefs`|object[]|父记录存在时必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`grades[].evidenceRefs[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`grades[].evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`grades[].evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`grades[].evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`grades[].finalGrade`|int|父记录存在时必有|本快照最终整数成绩0～100；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`grades[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].ledgerSnapshot`|object|父记录存在时必有|本成绩固定的账本来源；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ledgerSnapshot.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`grades[].ledgerSnapshot.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].ledgerSnapshot.basisStatus`|string|父记录存在时必有|当前计分依据是否有效；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ledgerSnapshot.gaps`|string[]|父记录存在时必有|教学计分待核对原因字符串集合；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ledgerSnapshot.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`grades[].ledgerSnapshot.rows`|object[]|父记录存在时必有|本结构的有序行；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ledgerSnapshot.rows[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].ledgerSnapshot.rows[].acceptanceKind`|string|父记录存在时必有|验收来源INDEPENDENT/SELF_CHECK或空字符串；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ledgerSnapshot.rows[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ledgerSnapshot.rows[].acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`grades[].ledgerSnapshot.rows[].contributionConfirmed`|bool|父记录存在时必有|本份额是否已有有效本人确认；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ledgerSnapshot.rows[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`grades[].ledgerSnapshot.rows[].signed`|bool|父记录存在时必有|本人对当前业务版本是否有效确认；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ledgerSnapshot.rows[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].ledgerSnapshot.rows[].taskTitle`|string|父记录存在时必有|任务标题；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ledgerSnapshot.rows[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].ledgerSnapshot.rows[].unitTitle`|string|父记录存在时必有|分项标题；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ledgerSnapshot.settlement`|object|父记录存在时必有|当前显式结算投影；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ledgerSnapshot.settlement.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`grades[].ledgerSnapshot.settlement.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].ledgerSnapshot.settlement.b`|int|父记录存在时必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`grades[].ledgerSnapshot.settlement.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].ledgerSnapshot.settlement.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`grades[].ledgerSnapshot.settlement.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`grades[].ledgerSnapshot.settlement.ruleVersion`|int|父记录存在时必有|已确认教学规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`grades[].ledgerSnapshot.settlement.settledAt`|string|父记录存在时必有|本次显式结算的服务端时间；UTC RFC3339时间点|
|`grades[].ledgerSnapshot.settlement.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；与当前计分来源指纹相同才有效|
|`grades[].ledgerSnapshot.settlement.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].ledgerSnapshot.sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ledgerSnapshot.studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].publishedAt`|string|父记录存在时必有|服务器正式发布时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`grades[].publishedBy`|string|父记录存在时必有|实际发布人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`grades[].q`|int|父记录存在时必有|当前授权范围检索文字；教学草稿中另为整数Q；教学Q为整数0～100|
|`grades[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`grades[].ruleSnapshot`|object|父记录存在时必有|本成绩固定的教师规则；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ruleSnapshot.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].ruleSnapshot.confirmedAt`|string|父记录存在时必有|服务器本人确认时间；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ruleSnapshot.confirmedBy`|string|父记录存在时必有|实际确认人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ruleSnapshot.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ruleSnapshot.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].ruleSnapshot.processWeight`|string|父记录存在时必有|教师确认的过程权重w（百分数字符串）；0～100的最多两位小数字符串，必须由当前教师确认|
|`grades[].ruleSnapshot.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`grades[].ruleSnapshot.rounding`|string|父记录存在时必有|固定HALF_UP；遵守本字段类型、统一上限及对应业务条件|
|`grades[].ruleSnapshot.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`grades[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`grades[].studentName`|string|父记录存在时必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`grades[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`publicationId`|string|必有|当前结构固定的publication资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "publicationId": "publication_example_1",
    "grades": [
      {
        "id": "grade_snapshot_example_1",
        "assignmentId": "assignment_example_1",
        "assignmentName": "课程设计",
        "squareId": "square_example_1",
        "studentId": "user_example_1",
        "studentName": "示例学生",
        "version": 1,
        "b": 80,
        "q": 85,
        "delta": 2,
        "finalGrade": 85,
        "ruleSnapshot": {
          "id": "grade_rule_example_1",
          "assignmentId": "assignment_example_1",
          "version": 1,
          "processWeight": "0.40",
          "description": "履约与成果评价",
          "rounding": "HALF_UP",
          "confirmedBy": "user_example_teacher",
          "confirmedAt": "2026-11-02T08:00:00Z",
          "revision": 1
        },
        "ledgerSnapshot": {
          "assignmentId": "assignment_example_1",
          "studentId": "user_example_1",
          "responsibilityPoints": "10.00",
          "acceptedPoints": "8.00",
          "basisStatus": "READY",
          "sourceKey": "source-test-1",
          "rows": [
            {
              "taskId": "task_example_1",
              "taskTitle": "登录页面",
              "unitId": "unit_example_1",
              "unitTitle": "登录页面",
              "points": "10.00",
              "acceptedPoints": "8.00",
              "signed": true,
              "accepted": true,
              "contributionConfirmed": true,
              "acceptanceId": "acceptance_example_1",
              "acceptanceKind": "INDEPENDENT"
            }
          ],
          "gaps": [],
          "settlement": {}
        },
        "evidenceRefs": [
          {
            "kind": "LINK",
            "url": "https://example.invalid/demo",
            "title": "示例演示材料"
          }
        ],
        "deltaReason": "课程量规内的专项改进",
        "basisDigest": "basis-test-1",
        "publishedBy": "user_example_teacher",
        "publishedAt": "2026-11-02T08:00:00Z",
        "revision": 1
      }
    ]
  }
}
```

<a id="response-r-grade-15"></a>

### R_GRADE_15 — 提交 · 作业 / 规则预览

适用接口：[GRADE-15](#api-grade-15)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`impact`|object|必有|impact：所属响应结构中的结构化记录；遵守本字段类型、统一上限及对应业务条件|
|`impact.affectedStudentCount`|int|父记录存在时必有|本范围的affectedStudent数量；遵守本字段类型、统一上限及对应业务条件|
|`previewToken`|string|必有|固定本人/范围/输入/来源及期限的预览凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`rule`|object|必有|骰子MAX/MIN/TARGET或本资源规则，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`rule.assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rule.confirmedAt`|string|父记录存在时必有|服务器本人确认时间；遵守本字段类型、统一上限及对应业务条件|
|`rule.confirmedBy`|string|父记录存在时必有|实际确认人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`rule.description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`rule.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rule.processWeight`|string|父记录存在时必有|教师确认的过程权重w（百分数字符串）；0～100的最多两位小数字符串，必须由当前教师确认|
|`rule.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`rule.rounding`|string|父记录存在时必有|固定HALF_UP；遵守本字段类型、统一上限及对应业务条件|
|`rule.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "previewToken": "rule-preview-test",
    "expiresAt": "2026-11-02T08:10:00Z",
    "assignmentId": "assignment_example_1",
    "rule": {
      "processWeight": "0.40",
      "description": "履约与成果评价"
    },
    "impact": {
      "affectedStudentCount": 1
    }
  }
}
```

<a id="response-r-grade-17"></a>

### R_GRADE_17 — 提交 · 作业 / 学生 / 教学采用/排除

适用接口：[GRADE-17](#api-grade-17)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`correction`|object|必有|教师有理由的采用/排除决定；遵守本字段类型、统一上限及对应业务条件|
|`correction.acceptedPoints`|string|父记录存在时必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`correction.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`evidenceRefs`|object[]|必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`studentId`|string|必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`taskId`|string|必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`unitId`|string|必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "ledger_correction_example_1",
    "assignmentId": "assignment_example_1",
    "studentId": "user_example_1",
    "taskId": "task_example_1",
    "unitId": "unit_example_1",
    "correction": {
      "responsibilityPoints": "10.00",
      "acceptedPoints": "8.00"
    },
    "reason": "按实际协作情况说明",
    "evidenceRefs": [
      {
        "kind": "LINK",
        "url": "https://example.invalid/demo",
        "title": "示例演示材料"
      }
    ],
    "authorId": "user_example_teacher",
    "revision": 1,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-grade-18"></a>

### R_GRADE_18 — 提交 · 作业 / 学生 / 基础分结算

适用接口：[GRADE-18](#api-grade-18)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`acceptedPoints`|string|必有|已验收且本人确认的有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`b`|int|必有|显式结算的整数履约比例B；0～100整数；只有未就绪投影允许null，正式成绩必须有依据|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`responsibilityPoints`|string|必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`ruleVersion`|int|必有|已确认教学规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`settledAt`|string|必有|显式结算时间；遵守本字段类型、统一上限及对应业务条件|
|`sourceKey`|string|必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`studentId`|string|必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "settlement_example_1",
    "assignmentId": "assignment_example_1",
    "studentId": "user_example_1",
    "sourceKey": "source-test-1",
    "ruleVersion": 1,
    "b": 80,
    "responsibilityPoints": "10.00",
    "acceptedPoints": "8.00",
    "revision": 1,
    "settledAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-grade-23"></a>

### R_GRADE_23 — 保存 · 作业 / 学生 / 个人教学评分草稿

适用接口：[GRADE-23](#api-grade-23)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assignmentId`|string|必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`delta`|int|必有|教师有理由的整数教学调整Δ；整数-100～100；非0时deltaReason非空|
|`deltaReason`|string|必有|教师教学调整依据；delta非0时必填1～500；0时可为空|
|`evidenceRefs`|object[]|必有|本人明确附带的证据引用；可省略默认[]，要求教学依据的动作必须至少一项|
|`evidenceRefs[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`evidenceRefs[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`evidenceRefs[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`evidenceRefs[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`q`|int|必有|当前授权范围检索文字；教学草稿中另为整数Q；教学Q为整数0～100|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`ruleVersion`|int|必有|已确认教学规则版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`sourceKey`|string|必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`studentId`|string|必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "grade_draft_example_1",
    "assignmentId": "assignment_example_1",
    "studentId": "user_example_1",
    "q": 85,
    "delta": 2,
    "deltaReason": "量规内的专项改进",
    "evidenceRefs": [
      {
        "kind": "LINK",
        "url": "https://example.invalid/demo",
        "title": "示例演示材料"
      }
    ],
    "sourceKey": "source-test-1",
    "ruleVersion": 1,
    "revision": 1
  }
}
```

<a id="response-r-report-01"></a>

### R_REPORT_01 — 读取 · 项目 / 交付草稿

适用接口：[REPORT-01](#api-report-01)、[REPORT-10](#api-report-10)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`draft`|object|必有|本人私有草稿投影；遵守本字段类型、统一上限及对应业务条件|
|`draft.demo`|object[]|父记录存在时必有|演示材料引用；遵守本字段类型、统一上限及对应业务条件|
|`draft.demo[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`draft.demo[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`draft.demo[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`draft.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`draft.notes`|string|父记录存在时必有|补充说明；遵守本字段类型、统一上限及对应业务条件|
|`draft.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`draft.report`|object[]|父记录存在时必有|报告材料引用；遵守本字段类型、统一上限及对应业务条件|
|`draft.report[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`draft.report[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`draft.report[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`draft.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`draft.work`|object[]|父记录存在时必有|有权作品投影；遵守本字段类型、统一上限及对应业务条件|
|`draft.work[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`draft.work[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`draft.work[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`gaps`|object[]|必有|当前材料或来源缺项；遵守本字段类型、统一上限及对应业务条件|
|`gaps[].code`|string/null|必有；允许null|服务端有效码或响应业务码，按父结构区分；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`gaps[].message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`gaps[].resourceId`|string/null|必有；允许null|该入口当前有权资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`gaps[].section`|string|父记录存在时必有|报告固定栏目；遵守本字段类型、统一上限及对应业务条件|
|`readiness`|object[]|必有|当前是否具备正式评分条件；遵守本字段类型、统一上限及对应业务条件|
|`readiness[].code`|string/null|必有；允许null|服务端有效码或响应业务码，按父结构区分；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`readiness[].message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`sourceKey`|string|必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "draft": {
      "id": "delivery_draft_example_1",
      "projectId": "project_example_1",
      "work": [
        {
          "kind": "LINK",
          "url": "https://example.invalid/demo",
          "title": "示例演示材料"
        }
      ],
      "report": [
        {
          "kind": "LINK",
          "url": "https://example.invalid/demo",
          "title": "示例演示材料"
        }
      ],
      "demo": [
        {
          "kind": "LINK",
          "url": "https://example.invalid/demo",
          "title": "示例演示材料"
        }
      ],
      "notes": "课程交付材料",
      "revision": 1
    },
    "sourceKey": "delivery-source-test",
    "readiness": [],
    "gaps": []
  }
}
```

<a id="response-r-report-02"></a>

### R_REPORT_02 — 读取 · 项目 / 交付版本

适用接口：[REPORT-02](#api-report-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].artifacts`|object[]|父记录存在时必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`items[].artifacts[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`items[].artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`items[].artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`items[].confirmedAt`|string|父记录存在时必有|服务器本人确认时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].confirmedBy`|string|父记录存在时必有|实际确认人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`items[].demo`|object[]|父记录存在时必有|演示材料引用；遵守本字段类型、统一上限及对应业务条件|
|`items[].demo[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].demo[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].demo[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].report`|object[]|父记录存在时必有|报告材料引用；遵守本字段类型、统一上限及对应业务条件|
|`items[].report[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].report[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].report[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`items[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].work`|object[]|父记录存在时必有|有权作品投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].work[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].work[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].work[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "delivery_release_example_1",
        "projectId": "project_example_1",
        "version": 1,
        "sourceKey": "delivery-source-test",
        "work": [
          {
            "kind": "LINK",
            "url": "https://example.invalid/demo",
            "title": "示例演示材料"
          }
        ],
        "report": [
          {
            "kind": "LINK",
            "url": "https://example.invalid/demo",
            "title": "示例演示材料"
          }
        ],
        "demo": [
          {
            "kind": "LINK",
            "url": "https://example.invalid/demo",
            "title": "示例演示材料"
          }
        ],
        "artifacts": [
          {
            "kind": "LINK",
            "url": "https://example.invalid/demo",
            "title": "示例演示材料"
          }
        ],
        "confirmedBy": "user_example_1",
        "confirmedAt": "2026-11-02T08:00:00Z",
        "revision": 1
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-report-03"></a>

### R_REPORT_03 — 读取 · 项目 / 五栏协作报告

适用接口：[REPORT-03](#api-report-03)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`closingCycle`|int|必有|项目本次收尾轮次；遵守本字段类型、统一上限及对应业务条件|
|`data`|object|必有|本接口业务主体；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance`|object[]|父记录存在时必有|成果验收栏目；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`data.deliveriesAndAcceptance[].acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`data.deliveriesAndAcceptance[].acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].artifacts`|object[]|父记录存在时必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`data.deliveriesAndAcceptance[].artifacts[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`data.deliveriesAndAcceptance[].artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`data.deliveriesAndAcceptance[].artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`data.deliveriesAndAcceptance[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].reviews`|object[]|父记录存在时必有|该业务核对记录；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].reviews[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].reviews[].reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].reviews[].unitResults`|object[]|父记录存在时必有|unitResults：所属响应结构中的有序记录集合；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].reviews[].unitResults[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].reviews[].unitResults[].reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`data.deliveriesAndAcceptance[].reviews[].unitResults[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].sequence`|int|父记录存在时必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|
|`data.deliveriesAndAcceptance[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].submittedAt`|string|父记录存在时必有|服务器接收提交时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`data.deliveriesAndAcceptance[].submittedByName`|string|父记录存在时必有|实际提交者姓名；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].taskTitle`|string|父记录存在时必有|任务标题；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot`|object[]|父记录存在时必有|固定交付项快照；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations`|object[]|父记录存在时必有|本人对验收份额的确认集合；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].statement`|string|父记录存在时必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].shares`|object[]|父记录存在时必有|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].responsibility`|string|父记录存在时必有|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].status`|string|正式分项存在时必有|本正式分项的TODO/REVIEW_PENDING/PASSED/RETURNED状态；分项PASSED与整任务DONE分开，任务按全部分项实际结果派生|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess`|object[]|父记录存在时必有|分工过程栏目；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`data.divisionAndProcess[].mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].ownerName`|string|父记录存在时必有|当前责任人显示名，未设置为空或未认领显示文案；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`data.divisionAndProcess[].shares`|object[]|父记录存在时必有|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].shares[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].shares[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].shares[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`data.divisionAndProcess[].shares[].responsibility`|string|父记录存在时必有|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].shares[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.divisionAndProcess[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.divisionAndProcess[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork`|object[]|父记录存在时必有|本人工作记录栏目；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork[].completedResponsibleTasks`|int|父记录存在时必有|本人统筹且已完成的任务数量；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork[].currentMember`|bool|父记录存在时必有|现在是否仍为正式成员；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork[].formalContribution`|object|父记录存在时必有|正式份额责任与本人确认点数；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork[].formalContribution.confirmedPoints`|string|父记录存在时必有|已验收且本人已确认贡献的点数字符串；0～1000000，最多2位小数的十进制字符串，份额/合计约束同时成立|
|`data.memberWork[].formalContribution.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`data.memberWork[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork[].responsibleTasks`|int|父记录存在时必有|本人统筹任务数量，不等同独占贡献；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.overview`|object|父记录存在时必有|项目概况；遵守本字段类型、统一上限及对应业务条件|
|`data.overview.assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`data.overview.courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`data.overview.memberCount`|int|父记录存在时必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`data.overview.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`data.overview.taskCount`|int|父记录存在时必有|本范围的task数量；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective`|object|父记录存在时必有|复盘栏目；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members`|object[]|父记录存在时必有|按权限可见的正式成员投影；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members[].authorName`|string|父记录存在时必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members[].improvements`|string|父记录存在时必有|后续改进；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members[].learning`|string|父记录存在时必有|本人学习收获；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members[].missing`|bool|父记录存在时必有|本人是否尚未填写复盘；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members[].problems`|string|父记录存在时必有|实际遇到的问题；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.retrospective.members[].workSummary`|string|父记录存在时必有|本人完成情况；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.overall`|object|父记录存在时必有|组长整体说明，未填写{}；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.overall.coordination`|string|父记录存在时必有|协作说明；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.overall.improvements`|string|父记录存在时必有|后续改进；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.overall.summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`gaps`|object[]|必有|当前材料或来源缺项；遵守本字段类型、统一上限及对应业务条件|
|`gaps[].code`|string/null|必有；允许null|服务端有效码或响应业务码，按父结构区分；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`gaps[].message`|string|父记录存在时必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`gaps[].resourceId`|string/null|必有；允许null|该入口当前有权资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`gaps[].section`|string|父记录存在时必有|报告固定栏目；遵守本字段类型、统一上限及对应业务条件|
|`sourceKey`|string|必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "sourceKey": "report-source-test",
    "closingCycle": 1,
    "data": {
      "overview": {
        "projectName": "课程协作应用",
        "courseName": "移动应用开发实践",
        "assignmentName": "课程设计",
        "taskCount": 1,
        "memberCount": 1
      },
      "divisionAndProcess": [
        {
          "taskId": "task_example_1",
          "title": "登录页面",
          "ownerName": "示例学生",
          "mode": "ORDINARY",
          "status": "DONE",
          "dueAt": "2026-11-09T15:59:59Z",
          "revision": 2,
          "shares": []
        }
      ],
      "deliveriesAndAcceptance": [
        {
          "taskTitle": "登录页面",
          "submissionId": "submission_example_1",
          "sequence": 1,
          "submittedByName": "示例学生",
          "summary": "完成登录与退出",
          "submittedAt": "2026-11-02T08:00:00Z",
          "status": "REVIEWED",
          "artifacts": [
            {
              "kind": "LINK",
              "url": "https://example.invalid/demo",
              "title": "示例演示材料"
            }
          ],
          "acceptance": {
            "id": "acceptance_example_1",
            "submissionId": "submission_example_1",
            "reviewerId": "user_example_reviewer",
            "kind": "INDEPENDENT",
            "checks": [
              {
                "unitId": null,
                "criterionId": "criterion_example_1",
                "met": true
              }
            ],
            "reason": "",
            "decision": "PASSED",
            "acceptedAt": "2026-11-02T08:00:00Z"
          },
          "reviews": [],
          "unitsSnapshot": [],
          "criteria": [
            {
              "id": "criterion_example_1",
              "text": "可以演示登录和退出"
            }
          ]
        }
      ],
      "memberWork": [
        {
          "userId": "user_example_1",
          "name": "示例学生",
          "currentMember": true,
          "responsibleTasks": 1,
          "completedResponsibleTasks": 1,
          "formalContribution": {
            "responsibilityPoints": "0.00",
            "confirmedPoints": "0.00"
          }
        }
      ],
      "retrospective": {
        "members": [
          {
            "userId": "user_example_1",
            "authorName": "示例学生",
            "missing": false,
            "workSummary": "完成客户端",
            "learning": "掌握接口联调",
            "problems": "错误状态处理",
            "improvements": "补充状态示例"
          }
        ],
        "overall": {
          "summary": "完成主要链路",
          "coordination": "按职责协作",
          "improvements": "提前冻结契约"
        }
      }
    },
    "gaps": []
  }
}
```

<a id="response-r-report-04"></a>

### R_REPORT_04 — 读取 · 项目 / 报告快照

适用接口：[REPORT-04](#api-report-04)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].closingCycle`|int|父记录存在时必有|项目本次收尾轮次；遵守本字段类型、统一上限及对应业务条件|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].createdBy`|string|父记录存在时必有|会话确定的创建人ID；遵守本字段类型、统一上限及对应业务条件|
|`items[].data`|object|父记录存在时必有|本接口业务主体；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance`|object[]|父记录存在时必有|成果验收栏目；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].data.deliveriesAndAcceptance[].acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].data.deliveriesAndAcceptance[].acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].artifacts`|object[]|父记录存在时必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`items[].data.deliveriesAndAcceptance[].artifacts[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`items[].data.deliveriesAndAcceptance[].artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`items[].data.deliveriesAndAcceptance[].artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`items[].data.deliveriesAndAcceptance[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].reviews`|object[]|父记录存在时必有|该业务核对记录；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].reviews[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].reviews[].reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].reviews[].unitResults`|object[]|父记录存在时必有|unitResults：所属响应结构中的有序记录集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].reviews[].unitResults[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].reviews[].unitResults[].reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].data.deliveriesAndAcceptance[].reviews[].unitResults[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].sequence`|int|父记录存在时必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|
|`items[].data.deliveriesAndAcceptance[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].submittedAt`|string|父记录存在时必有|服务器接收提交时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].data.deliveriesAndAcceptance[].submittedByName`|string|父记录存在时必有|实际提交者姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].taskTitle`|string|父记录存在时必有|任务标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot`|object[]|父记录存在时必有|固定交付项快照；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations`|object[]|父记录存在时必有|本人对验收份额的确认集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].statement`|string|父记录存在时必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].shares`|object[]|父记录存在时必有|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].responsibility`|string|父记录存在时必有|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].status`|string|正式分项存在时必有|本正式分项的TODO/REVIEW_PENDING/PASSED/RETURNED状态；分项PASSED与整任务DONE分开，任务按全部分项实际结果派生|
|`items[].data.deliveriesAndAcceptance[].unitsSnapshot[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.divisionAndProcess`|object[]|父记录存在时必有|分工过程栏目；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.divisionAndProcess[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].data.divisionAndProcess[].mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.divisionAndProcess[].ownerName`|string|父记录存在时必有|当前责任人显示名，未设置为空或未认领显示文案；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.divisionAndProcess[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].data.divisionAndProcess[].shares`|object[]|父记录存在时必有|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.divisionAndProcess[].shares[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.divisionAndProcess[].shares[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.divisionAndProcess[].shares[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`items[].data.divisionAndProcess[].shares[].responsibility`|string|父记录存在时必有|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.divisionAndProcess[].shares[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.divisionAndProcess[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.divisionAndProcess[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.divisionAndProcess[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.memberWork`|object[]|父记录存在时必有|本人工作记录栏目；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.memberWork[].completedResponsibleTasks`|int|父记录存在时必有|本人统筹且已完成的任务数量；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.memberWork[].currentMember`|bool|父记录存在时必有|现在是否仍为正式成员；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.memberWork[].formalContribution`|object|父记录存在时必有|正式份额责任与本人确认点数；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.memberWork[].formalContribution.confirmedPoints`|string|父记录存在时必有|已验收且本人已确认贡献的点数字符串；0～1000000，最多2位小数的十进制字符串，份额/合计约束同时成立|
|`items[].data.memberWork[].formalContribution.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`items[].data.memberWork[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.memberWork[].responsibleTasks`|int|父记录存在时必有|本人统筹任务数量，不等同独占贡献；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.memberWork[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.overview`|object|父记录存在时必有|项目概况；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.overview.assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.overview.courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.overview.memberCount`|int|父记录存在时必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.overview.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.overview.taskCount`|int|父记录存在时必有|本范围的task数量；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.retrospective`|object|父记录存在时必有|复盘栏目；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.retrospective.members`|object[]|父记录存在时必有|按权限可见的正式成员投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.retrospective.members[].authorName`|string|父记录存在时必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.retrospective.members[].improvements`|string|父记录存在时必有|后续改进；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.retrospective.members[].learning`|string|父记录存在时必有|本人学习收获；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.retrospective.members[].missing`|bool|父记录存在时必有|本人是否尚未填写复盘；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.retrospective.members[].problems`|string|父记录存在时必有|实际遇到的问题；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.retrospective.members[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].data.retrospective.members[].workSummary`|string|父记录存在时必有|本人完成情况；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.retrospective.overall`|object|父记录存在时必有|组长整体说明，未填写{}；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.retrospective.overall.coordination`|string|父记录存在时必有|协作说明；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.retrospective.overall.improvements`|string|父记录存在时必有|后续改进；遵守本字段类型、统一上限及对应业务条件|
|`items[].data.retrospective.overall.summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`items[].emptyReason`|string|父记录存在时必有|空项目收尾原因；报告type=EMPTY必填1～1000，其他空字符串|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].sourceKey`|string|父记录存在时必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`items[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "report_snapshot_example_1",
        "projectId": "project_example_1",
        "type": "FINAL",
        "name": "课程设计最终报告",
        "sourceKey": "report-source-test",
        "closingCycle": 1,
        "data": {
          "overview": {
            "projectName": "课程协作应用",
            "courseName": "移动应用开发实践",
            "assignmentName": "课程设计",
            "taskCount": 1,
            "memberCount": 1
          },
          "divisionAndProcess": [
            {
              "taskId": "task_example_1",
              "title": "登录页面",
              "ownerName": "示例学生",
              "mode": "ORDINARY",
              "status": "DONE",
              "dueAt": "2026-11-09T15:59:59Z",
              "revision": 2,
              "shares": []
            }
          ],
          "deliveriesAndAcceptance": [
            {
              "taskTitle": "登录页面",
              "submissionId": "submission_example_1",
              "sequence": 1,
              "submittedByName": "示例学生",
              "summary": "完成登录与退出",
              "submittedAt": "2026-11-02T08:00:00Z",
              "status": "REVIEWED",
              "artifacts": [
                {
                  "kind": "LINK",
                  "url": "https://example.invalid/demo",
                  "title": "示例演示材料"
                }
              ],
              "acceptance": {
                "id": "acceptance_example_1",
                "submissionId": "submission_example_1",
                "reviewerId": "user_example_reviewer",
                "kind": "INDEPENDENT",
                "checks": [
                  {
                    "unitId": null,
                    "criterionId": "criterion_example_1",
                    "met": true
                  }
                ],
                "reason": "",
                "decision": "PASSED",
                "acceptedAt": "2026-11-02T08:00:00Z"
              },
              "reviews": [],
              "unitsSnapshot": [],
              "criteria": [
                {
                  "id": "criterion_example_1",
                  "text": "可以演示登录和退出"
                }
              ]
            }
          ],
          "memberWork": [
            {
              "userId": "user_example_1",
              "name": "示例学生",
              "currentMember": true,
              "responsibleTasks": 1,
              "completedResponsibleTasks": 1,
              "formalContribution": {
                "responsibilityPoints": "0.00",
                "confirmedPoints": "0.00"
              }
            }
          ],
          "retrospective": {
            "members": [
              {
                "userId": "user_example_1",
                "authorName": "示例学生",
                "missing": false,
                "workSummary": "完成客户端",
                "learning": "掌握接口联调",
                "problems": "错误状态处理",
                "improvements": "补充状态示例"
              }
            ],
            "overall": {
              "summary": "完成主要链路",
              "coordination": "按职责协作",
              "improvements": "提前冻结契约"
            }
          }
        },
        "emptyReason": "",
        "createdBy": "user_example_1",
        "createdAt": "2026-11-02T08:00:00Z",
        "revision": 1
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-report-05"></a>

### R_REPORT_05 — 读取 · 报告快照

适用接口：[REPORT-05](#api-report-05)、[REPORT-09](#api-report-09)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`closingCycle`|int|必有|项目本次收尾轮次；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`createdBy`|string|必有|会话确定的创建人ID；遵守本字段类型、统一上限及对应业务条件|
|`data`|object|必有|本接口业务主体；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance`|object[]|父记录存在时必有|成果验收栏目；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`data.deliveriesAndAcceptance[].acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`data.deliveriesAndAcceptance[].acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].artifacts`|object[]|父记录存在时必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`data.deliveriesAndAcceptance[].artifacts[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`data.deliveriesAndAcceptance[].artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`data.deliveriesAndAcceptance[].artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`data.deliveriesAndAcceptance[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].reviews`|object[]|父记录存在时必有|该业务核对记录；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].reviews[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].reviews[].reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].reviews[].unitResults`|object[]|父记录存在时必有|unitResults：所属响应结构中的有序记录集合；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].reviews[].unitResults[].decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].reviews[].unitResults[].reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`data.deliveriesAndAcceptance[].reviews[].unitResults[].unitId`|string|父记录存在时必有|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].sequence`|int|父记录存在时必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|
|`data.deliveriesAndAcceptance[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].submittedAt`|string|父记录存在时必有|服务器接收提交时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`data.deliveriesAndAcceptance[].submittedByName`|string|父记录存在时必有|实际提交者姓名；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].taskTitle`|string|父记录存在时必有|任务标题；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot`|object[]|父记录存在时必有|固定交付项快照；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance`|object|父记录存在时必有|固定本次提交的验收结果；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.acceptedAt`|string|父记录存在时必有|服务器接受时间；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.checks`|object[]|父记录存在时必有|本次逐条核对结果；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.checks[].criterionId`|string|父记录存在时必有|固定的验收标准ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.checks[].met`|bool|父记录存在时必有|此标准是否符合；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.checks[].unitId`|string/null|必有；允许null|正式分项ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.reviewerId`|string|父记录存在时必有|本次实际验收人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].acceptance.submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations`|object[]|父记录存在时必有|本人对验收份额的确认集合；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].acceptanceId`|string|父记录存在时必有|本人份额确认对应的验收记录ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].statement`|string|父记录存在时必有|被提议人本人陈述；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].contributionConfirmations[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].criteria`|object[]|父记录存在时必有|逐条验收标准；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].criteria[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].criteria[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].shares`|object[]|父记录存在时必有|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].responsibility`|string|父记录存在时必有|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].shares[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].status`|string|正式分项存在时必有|本正式分项的TODO/REVIEW_PENDING/PASSED/RETURNED状态；分项PASSED与整任务DONE分开，任务按全部分项实际结果派生|
|`data.deliveriesAndAcceptance[].unitsSnapshot[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess`|object[]|父记录存在时必有|分工过程栏目；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].dueAt`|string|父记录存在时必有|当前有效截止时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`data.divisionAndProcess[].mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].ownerName`|string|父记录存在时必有|当前责任人显示名，未设置为空或未认领显示文案；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`data.divisionAndProcess[].shares`|object[]|父记录存在时必有|各参与人的有效责任份额；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].shares[].accepted`|bool|父记录存在时必有|本人是否明确接受；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].shares[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].shares[].points`|string|父记录存在时必有|最多两位小数的责任点数；正式分项/份额为正十进制字符串，0&lt;points≤1000000，最多2位小数，合计守恒|
|`data.divisionAndProcess[].shares[].responsibility`|string|父记录存在时必有|本人责任内容；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].shares[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.divisionAndProcess[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`data.divisionAndProcess[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.divisionAndProcess[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork`|object[]|父记录存在时必有|本人工作记录栏目；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork[].completedResponsibleTasks`|int|父记录存在时必有|本人统筹且已完成的任务数量；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork[].currentMember`|bool|父记录存在时必有|现在是否仍为正式成员；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork[].formalContribution`|object|父记录存在时必有|正式份额责任与本人确认点数；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork[].formalContribution.confirmedPoints`|string|父记录存在时必有|已验收且本人已确认贡献的点数字符串；0～1000000，最多2位小数的十进制字符串，份额/合计约束同时成立|
|`data.memberWork[].formalContribution.responsibilityPoints`|string|父记录存在时必有|本人有效责任点数；0～1000000的十进制字符串，最多2位小数；份额合计须守恒|
|`data.memberWork[].name`|string|父记录存在时必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork[].responsibleTasks`|int|父记录存在时必有|本人统筹任务数量，不等同独占贡献；遵守本字段类型、统一上限及对应业务条件|
|`data.memberWork[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.overview`|object|父记录存在时必有|项目概况；遵守本字段类型、统一上限及对应业务条件|
|`data.overview.assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`data.overview.courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`data.overview.memberCount`|int|父记录存在时必有|当前正式成员数；遵守本字段类型、统一上限及对应业务条件|
|`data.overview.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`data.overview.taskCount`|int|父记录存在时必有|本范围的task数量；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective`|object|父记录存在时必有|复盘栏目；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members`|object[]|父记录存在时必有|按权限可见的正式成员投影；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members[].authorName`|string|父记录存在时必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members[].improvements`|string|父记录存在时必有|后续改进；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members[].learning`|string|父记录存在时必有|本人学习收获；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members[].missing`|bool|父记录存在时必有|本人是否尚未填写复盘；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members[].problems`|string|父记录存在时必有|实际遇到的问题；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.members[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`data.retrospective.members[].workSummary`|string|父记录存在时必有|本人完成情况；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.overall`|object|父记录存在时必有|组长整体说明，未填写{}；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.overall.coordination`|string|父记录存在时必有|协作说明；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.overall.improvements`|string|父记录存在时必有|后续改进；遵守本字段类型、统一上限及对应业务条件|
|`data.retrospective.overall.summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`emptyReason`|string|必有|空项目收尾原因；报告type=EMPTY必填1～1000，其他空字符串|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`name`|string|必有|资源名称；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceKey`|string|必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`type`|string|必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "report_snapshot_example_1",
    "projectId": "project_example_1",
    "type": "FINAL",
    "name": "课程设计最终报告",
    "sourceKey": "report-source-test",
    "closingCycle": 1,
    "data": {
      "overview": {
        "projectName": "课程协作应用",
        "courseName": "移动应用开发实践",
        "assignmentName": "课程设计",
        "taskCount": 1,
        "memberCount": 1
      },
      "divisionAndProcess": [
        {
          "taskId": "task_example_1",
          "title": "登录页面",
          "ownerName": "示例学生",
          "mode": "ORDINARY",
          "status": "DONE",
          "dueAt": "2026-11-09T15:59:59Z",
          "revision": 2,
          "shares": []
        }
      ],
      "deliveriesAndAcceptance": [
        {
          "taskTitle": "登录页面",
          "submissionId": "submission_example_1",
          "sequence": 1,
          "submittedByName": "示例学生",
          "summary": "完成登录与退出",
          "submittedAt": "2026-11-02T08:00:00Z",
          "status": "REVIEWED",
          "artifacts": [
            {
              "kind": "LINK",
              "url": "https://example.invalid/demo",
              "title": "示例演示材料"
            }
          ],
          "acceptance": {
            "id": "acceptance_example_1",
            "submissionId": "submission_example_1",
            "reviewerId": "user_example_reviewer",
            "kind": "INDEPENDENT",
            "checks": [
              {
                "unitId": null,
                "criterionId": "criterion_example_1",
                "met": true
              }
            ],
            "reason": "",
            "decision": "PASSED",
            "acceptedAt": "2026-11-02T08:00:00Z"
          },
          "reviews": [],
          "unitsSnapshot": [],
          "criteria": [
            {
              "id": "criterion_example_1",
              "text": "可以演示登录和退出"
            }
          ]
        }
      ],
      "memberWork": [
        {
          "userId": "user_example_1",
          "name": "示例学生",
          "currentMember": true,
          "responsibleTasks": 1,
          "completedResponsibleTasks": 1,
          "formalContribution": {
            "responsibilityPoints": "0.00",
            "confirmedPoints": "0.00"
          }
        }
      ],
      "retrospective": {
        "members": [
          {
            "userId": "user_example_1",
            "authorName": "示例学生",
            "missing": false,
            "workSummary": "完成客户端",
            "learning": "掌握接口联调",
            "problems": "错误状态处理",
            "improvements": "补充状态示例"
          }
        ],
        "overall": {
          "summary": "完成主要链路",
          "coordination": "按职责协作",
          "improvements": "提前冻结契约"
        }
      }
    },
    "emptyReason": "",
    "createdBy": "user_example_1",
    "createdAt": "2026-11-02T08:00:00Z",
    "revision": 1
  }
}
```

<a id="response-r-report-06"></a>

### R_REPORT_06 — 提交 · 项目 / 交付预览

适用接口：[REPORT-06](#api-report-06)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`draft`|object|必有|本人私有草稿投影；遵守本字段类型、统一上限及对应业务条件|
|`draft.demo`|object[]|父记录存在时必有|演示材料引用；遵守本字段类型、统一上限及对应业务条件|
|`draft.demo[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`draft.demo[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`draft.demo[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`draft.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`draft.notes`|string|父记录存在时必有|补充说明；遵守本字段类型、统一上限及对应业务条件|
|`draft.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`draft.report`|object[]|父记录存在时必有|报告材料引用；遵守本字段类型、统一上限及对应业务条件|
|`draft.report[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`draft.report[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`draft.report[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`draft.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`draft.work`|object[]|父记录存在时必有|有权作品投影；遵守本字段类型、统一上限及对应业务条件|
|`draft.work[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`draft.work[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`draft.work[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`previewToken`|string|必有|固定本人/范围/输入/来源及期限的预览凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`sourceKey`|string|必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`sources`|object[]|必有|固定来源集合；遵守本字段类型、统一上限及对应业务条件|
|`sources[].submissionId`|string|父记录存在时必有|不可覆盖的提交版本ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`sources[].taskId`|string|父记录存在时必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "previewToken": "delivery-preview-test",
    "expiresAt": "2026-11-02T08:10:00Z",
    "sourceKey": "delivery-source-test",
    "draft": {
      "id": "delivery_draft_example_1",
      "projectId": "project_example_1",
      "work": [
        {
          "kind": "LINK",
          "url": "https://example.invalid/demo",
          "title": "示例演示材料"
        }
      ],
      "report": [
        {
          "kind": "LINK",
          "url": "https://example.invalid/demo",
          "title": "示例演示材料"
        }
      ],
      "demo": [
        {
          "kind": "LINK",
          "url": "https://example.invalid/demo",
          "title": "示例演示材料"
        }
      ],
      "notes": "课程交付材料",
      "revision": 1
    },
    "sources": [
      {
        "taskId": "task_example_1",
        "submissionId": "submission_example_1"
      }
    ]
  }
}
```

<a id="response-r-report-07"></a>

### R_REPORT_07 — 提交 · 项目 / 交付版本

适用接口：[REPORT-07](#api-report-07)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`artifacts`|object[]|必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`artifacts[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`confirmedAt`|string|必有|服务器本人确认时间；遵守本字段类型、统一上限及对应业务条件|
|`confirmedBy`|string|必有|实际确认人的会话ID；遵守本字段类型、统一上限及对应业务条件|
|`demo`|object[]|必有|演示材料引用；遵守本字段类型、统一上限及对应业务条件|
|`demo[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`demo[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`demo[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`report`|object[]|必有|报告材料引用；遵守本字段类型、统一上限及对应业务条件|
|`report[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`report[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`report[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sourceKey`|string|必有|服务器计算的来源集合指纹；遵守本字段类型、统一上限及对应业务条件|
|`version`|int|必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`work`|object[]|必有|有权作品投影；遵守本字段类型、统一上限及对应业务条件|
|`work[].kind`|string|父记录存在时必有|当前子结构的种类；遵守本字段类型、统一上限及对应业务条件|
|`work[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`work[].url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "delivery_release_example_1",
    "projectId": "project_example_1",
    "version": 1,
    "sourceKey": "delivery-source-test",
    "work": [
      {
        "kind": "LINK",
        "url": "https://example.invalid/demo",
        "title": "示例演示材料"
      }
    ],
    "report": [
      {
        "kind": "LINK",
        "url": "https://example.invalid/demo",
        "title": "示例演示材料"
      }
    ],
    "demo": [
      {
        "kind": "LINK",
        "url": "https://example.invalid/demo",
        "title": "示例演示材料"
      }
    ],
    "artifacts": [
      {
        "kind": "LINK",
        "url": "https://example.invalid/demo",
        "title": "示例演示材料"
      }
    ],
    "confirmedBy": "user_example_1",
    "confirmedAt": "2026-11-02T08:00:00Z",
    "revision": 1
  }
}
```

<a id="response-r-report-11"></a>

### R_REPORT_11 — 保存 · 项目 / 本人 / 本人复盘

适用接口：[REPORT-11](#api-report-11)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`authorName`|string|必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`improvements`|string|必有|后续改进；遵守本字段类型、统一上限及对应业务条件|
|`learning`|string|必有|本人学习收获；遵守本字段类型、统一上限及对应业务条件|
|`problems`|string|必有|实际遇到的问题；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`userId`|string|必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`workSummary`|string|必有|本人完成情况；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "reflection_example_1",
    "projectId": "project_example_1",
    "userId": "user_example_1",
    "authorName": "示例学生",
    "workSummary": "完成客户端登录页面",
    "learning": "掌握前后端联调",
    "problems": "异常状态覆盖不足",
    "improvements": "补充失败场景验证",
    "revision": 1,
    "updatedAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-report-12"></a>

### R_REPORT_12 — 保存 · 项目 / 整体总结

适用接口：[REPORT-12](#api-report-12)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`coordination`|string|必有|协作说明；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`improvements`|string|必有|后续改进；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`summary`|string|必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "overall_example_1",
    "projectId": "project_example_1",
    "authorId": "user_example_1",
    "summary": "完成登录与退出",
    "coordination": "按已确认职责协作",
    "improvements": "补充失败场景验证",
    "revision": 1,
    "updatedAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-profile-01"></a>

### R_PROFILE_01 — 读取 · 本人 / 本人合作记录

适用接口：[PROFILE-01](#api-profile-01)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].canReview`|bool|父记录存在时必有|当前调用者是否可核验；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`items[].endedAt`|string|父记录存在时必有|实际合作结束时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].members`|object[]|父记录存在时必有|按权限可见的正式成员投影；遵守本字段类型、统一上限及对应业务条件|
|`items[].members[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].members[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].reviewEndsAt`|string|父记录存在时必有|互评编辑窗口截止；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "projectId": "project_example_1",
        "projectName": "课程协作应用",
        "endedAt": "2026-11-02T08:00:00Z",
        "members": [
          {
            "userId": "user_example_2",
            "displayName": "示例队员"
          }
        ],
        "canReview": true,
        "reviewEndsAt": "2026-11-16T08:00:00Z"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-profile-02"></a>

### R_PROFILE_02 — 读取 · 本人 / 作品

适用接口：[PROFILE-02](#api-profile-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].artifacts`|object[]|父记录存在时必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`items[].artifacts[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`items[].artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`items[].artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`items[].audience`|string|父记录存在时必有|本条可见范围；遵守本字段类型、统一上限及对应业务条件|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].myContribution`|string|父记录存在时必有|当前本人确认有效的贡献份额；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].role`|string|父记录存在时必有|作品中本人承担的角色描述；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceProjectId`|string|父记录存在时必有|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].versions`|object[]|仅作者有权读取|内容历史版本，仅作者或有权处理者；遵守本字段类型、统一上限及对应业务条件|
|`items[].versions[].content`|string|仅作者有权读取|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`items[].versions[].revision`|int|仅作者有权读取|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].versions[].title`|string|仅作者有权读取|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].versions[].updatedAt`|string|仅作者有权读取|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].versions[].version`|int|仅作者有权读取|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "work_example_1",
        "userId": "user_example_1",
        "title": "登录页面演示",
        "description": "课程项目成果",
        "role": "客户端开发",
        "myContribution": "完成登录与会话",
        "sourceProjectId": "project_example_1",
        "artifacts": [
          {
            "kind": "LINK",
            "url": "https://example.invalid/demo",
            "title": "示例演示材料"
          }
        ],
        "audience": "PRIVATE",
        "status": "ACTIVE",
        "versions": [],
        "revision": 1,
        "createdAt": "2026-11-02T08:00:00Z"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-profile-03"></a>

### R_PROFILE_03 — 读取 · 合作互评

适用接口：[PROFILE-03](#api-profile-03)、[PROFILE-11](#api-profile-11)、[PROFILE-12](#api-profile-12)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`canEdit`|bool|必有|当前调用者是否可编辑；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canWithdraw`|bool|必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`comment`|string|必有|本人意见；可省略，默认空，岗位/约定回应最多500|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`observations`|object[]|必有|按维度的本人观察；遵守本字段类型、统一上限及对应业务条件|
|`observations[].dimension`|string|父记录存在时必有|互评维度标识；遵守本字段类型、统一上限及对应业务条件|
|`observations[].evidenceText`|string|父记录存在时必有|具体观察事实；遵守本字段类型、统一上限及对应业务条件|
|`observations[].level`|string|父记录存在时必有|本维度观察等级；遵守本字段类型、统一上限及对应业务条件|
|`observedEnough`|bool|必有|本维度是否有足够实际观察；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectName`|string|必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targetName`|string|必有|被评价者显示名；遵守本字段类型、统一上限及对应业务条件|
|`targetUserId`|string|必有|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`versions`|object[]|仅作者有权读取|内容历史版本，仅作者或有权处理者；遵守本字段类型、统一上限及对应业务条件|
|`versions[].content`|string|仅作者有权读取|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`versions[].revision`|int|仅作者有权读取|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`versions[].title`|string|仅作者有权读取|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`versions[].updatedAt`|string|仅作者有权读取|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`versions[].version`|int|仅作者有权读取|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "peer_review_example_1",
    "projectId": "project_example_1",
    "projectName": "课程协作应用",
    "authorId": "user_example_1",
    "targetUserId": "user_example_2",
    "targetName": "示例队员",
    "observedEnough": true,
    "observations": [
      {
        "dimension": "DELIVERY",
        "level": "POSITIVE",
        "evidenceText": "按验收标准完成成果"
      },
      {
        "dimension": "COMMUNICATION",
        "level": "NOT_OBSERVED",
        "evidenceText": ""
      },
      {
        "dimension": "REWORK",
        "level": "NOT_OBSERVED",
        "evidenceText": ""
      }
    ],
    "comment": "仅评价实际合作事实",
    "status": "ACTIVE",
    "canEdit": true,
    "canWithdraw": true,
    "versions": [],
    "revision": 1,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-profile-04"></a>

### R_PROFILE_04 — 读取 · 项目 / 互评资格上下文

适用接口：[PROFILE-04](#api-profile-04)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectName`|string|必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`targets`|object[]|必有|当前合法目标集合；遵守本字段类型、统一上限及对应业务条件|
|`targets[].canReview`|bool|父记录存在时必有|当前调用者是否可核验；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`targets[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview`|object|父记录存在时必有|本人原始互评投影；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targets[].myReview.canEdit`|bool|父记录存在时必有|当前调用者是否可编辑；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.canWithdraw`|bool|父记录存在时必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.comment`|string|父记录存在时必有|本人意见；可省略，默认空，岗位/约定回应最多500|
|`targets[].myReview.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`targets[].myReview.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targets[].myReview.observations`|object[]|父记录存在时必有|按维度的本人观察；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.observations[].dimension`|string|父记录存在时必有|互评维度标识；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.observations[].evidenceText`|string|父记录存在时必有|具体观察事实；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.observations[].level`|string|父记录存在时必有|本维度观察等级；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.observedEnough`|bool|父记录存在时必有|本维度是否有足够实际观察；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targets[].myReview.projectName`|string|父记录存在时必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`targets[].myReview.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.targetName`|string|父记录存在时必有|被评价者显示名；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.targetUserId`|string|父记录存在时必有|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`targets[].myReview.versions`|object[]|仅作者有权读取|内容历史版本，仅作者或有权处理者；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.versions[].content`|string|仅作者有权读取|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.versions[].revision`|int|仅作者有权读取|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`targets[].myReview.versions[].title`|string|仅作者有权读取|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`targets[].myReview.versions[].updatedAt`|string|仅作者有权读取|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`targets[].myReview.versions[].version`|int|仅作者有权读取|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`targets[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "projectId": "project_example_1",
    "projectName": "课程协作应用",
    "targets": [
      {
        "userId": "user_example_2",
        "displayName": "示例队员",
        "canReview": true,
        "myReview": {}
      }
    ]
  }
}
```

<a id="response-r-profile-05"></a>

### R_PROFILE_05 — 读取 · 用户 / 画像

适用接口：[PROFILE-05](#api-profile-05)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`availability`|string|必有|来源是否仍允许本动作的只读原因码；遵守本字段类型、统一上限及对应业务条件|
|`collaborationGoal`|string|必有|协作目标；遵守本字段类型、统一上限及对应业务条件|
|`cooperationSummaries`|object[]|必有|满足匿名阈值的合作维度汇总；遵守本字段类型、统一上限及对应业务条件|
|`cooperationSummaries[].dimension`|string|父记录存在时必有|互评维度标识；遵守本字段类型、统一上限及对应业务条件|
|`cooperationSummaries[].needsImprovement`|string/null|必有；允许null|有具体事实的待改进观察数量；遵守本字段类型、统一上限及对应业务条件|
|`cooperationSummaries[].positive`|string/null|必有；允许null|正向观察数量；遵守本字段类型、统一上限及对应业务条件|
|`cooperationSummaries[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`cooperationSummaries[].validAuthors`|int|父记录存在时必有|有效不同评价作者数；遵守本字段类型、统一上限及对应业务条件|
|`cooperationSummaryVisible`|bool|必有|本人是否允许展示满足匿名阈值的合作汇总；遵守本字段类型、统一上限及对应业务条件|
|`displayName`|string|必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`intro`|string|必有|项目简介；遵守本字段类型、统一上限及对应业务条件|
|`isSelf`|bool|必有|目标是否为本人；遵守本字段类型、统一上限及对应业务条件|
|`processSummary`|object|必有|教师对过程依据的说明；遵守本字段类型、统一上限及对应业务条件|
|`processSummary.acceptedDeliveries`|int|父记录存在时必有|本人已验收交付次数；按当前权限及该版本业务范围返回|
|`processSummary.confirmedUnits`|int|父记录存在时必有|本人有效确认分项数；按当前权限及该版本业务范围返回|
|`processSummary.independentReviews`|int|父记录存在时必有|本人独立验收记录数；按当前权限及该版本业务范围返回|
|`processSummary.lateDeliveries`|int|父记录存在时必有|按固定截止统计的迟交次数；按当前权限及该版本业务范围返回|
|`processSummary.onTimeDeliveries`|int|父记录存在时必有|按固定截止统计的按时交付次数；按当前权限及该版本业务范围返回|
|`processSummary.projectId`|string|父记录存在时必有|本次有权合作项目ID；按当前权限及该版本业务范围返回|
|`skills`|string[]|必有|本人技能标签；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries`|object[]|必有|teacherSummaries：所属响应结构中的有序记录集合；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].assignmentId`|string|父记录存在时必有|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`teacherSummaries[].assignmentName`|string|父记录存在时必有|本次作业名称；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].consent`|object|父记录存在时必有|本人对确切评价内容版的展示授权；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].consent.contentVersion`|int|父记录存在时必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`teacherSummaries[].consent.decision`|string|父记录存在时必有|本人明确提交的决定；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].courseName`|string|父记录存在时必有|完整课程展示名称；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].history`|object[]|父记录存在时必有|按当前权限返回的处理/版本历史；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].history[].action`|string|父记录存在时必有|本人明确执行的动作；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].history[].actorId`|string|父记录存在时必有|本事件实际操作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`teacherSummaries[].history[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].history[].reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`teacherSummaries[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`teacherSummaries[].publishedAt`|string|父记录存在时必有|服务器正式发布时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`teacherSummaries[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`teacherSummaries[].sourceGradeSnapshotId`|string|父记录存在时必有|评价所依据的正式成绩快照ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`teacherSummaries[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`teacherSummaries[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].studentId`|string|父记录存在时必有|学生本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`teacherSummaries[].studentName`|string|父记录存在时必有|本人/当前范围的学生显示名；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].summary`|string|父记录存在时必有|本次业务说明/提交摘要；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].teacherId`|string|父记录存在时必有|当前负责教师ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`teacherSummaries[].teacherName`|string|父记录存在时必有|任课教师姓名；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].versions`|object[]|仅作者有权读取|内容历史版本，仅作者或有权处理者；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].versions[].content`|string|仅作者有权读取|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].versions[].revision`|int|仅作者有权读取|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`teacherSummaries[].versions[].title`|string|仅作者有权读取|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaries[].versions[].updatedAt`|string|仅作者有权读取|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`teacherSummaries[].versions[].version`|int|仅作者有权读取|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`teacherSummaryStatus`|string|必有|AVAILABLE/NOT_AVAILABLE；遵守本字段类型、统一上限及对应业务条件|
|`userId`|string|必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`weeklyHours`|int|必有|每周可投入小时数；遵守本字段类型、统一上限及对应业务条件|
|`works`|object[]|必有|有权作品集合；遵守本字段类型、统一上限及对应业务条件|
|`works[].artifacts`|object[]|父记录存在时必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`works[].artifacts[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`works[].artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`works[].artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`works[].artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`works[].audience`|string|父记录存在时必有|本条可见范围；遵守本字段类型、统一上限及对应业务条件|
|`works[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`works[].description`|string|父记录存在时必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`works[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`works[].myContribution`|string|父记录存在时必有|当前本人确认有效的贡献份额；遵守本字段类型、统一上限及对应业务条件|
|`works[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`works[].role`|string|父记录存在时必有|作品中本人承担的角色描述；遵守本字段类型、统一上限及对应业务条件|
|`works[].sourceProjectId`|string|父记录存在时必有|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`works[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`works[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`works[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`works[].versions`|object[]|仅作者有权读取|内容历史版本，仅作者或有权处理者；遵守本字段类型、统一上限及对应业务条件|
|`works[].versions[].content`|string|仅作者有权读取|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`works[].versions[].revision`|int|仅作者有权读取|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`works[].versions[].title`|string|仅作者有权读取|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`works[].versions[].updatedAt`|string|仅作者有权读取|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`works[].versions[].version`|int|仅作者有权读取|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "userId": "user_example_2",
    "displayName": "示例队员",
    "skills": [
      "Java"
    ],
    "weeklyHours": 4,
    "availability": "周三晚",
    "collaborationGoal": "完成课程设计",
    "intro": "负责服务器开发",
    "works": [],
    "processSummary": {},
    "cooperationSummaries": [],
    "cooperationSummaryVisible": false,
    "teacherSummaryStatus": "NOT_AVAILABLE",
    "teacherSummaries": [],
    "isSelf": false
  }
}
```

<a id="response-r-profile-06"></a>

### R_PROFILE_06 — 读取 · 作品

适用接口：[PROFILE-06](#api-profile-06)、[PROFILE-07](#api-profile-07)、[PROFILE-08](#api-profile-08)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`artifacts`|object[]|必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`artifacts[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`audience`|string|必有|本条可见范围；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`description`|string|必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myContribution`|string|必有|当前本人确认有效的贡献份额；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`role`|string|必有|作品中本人承担的角色描述；遵守本字段类型、统一上限及对应业务条件|
|`sourceProjectId`|string|必有|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`userId`|string|必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`versions`|object[]|仅作者有权读取|内容历史版本，仅作者或有权处理者；遵守本字段类型、统一上限及对应业务条件|
|`versions[].content`|string|仅作者有权读取|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`versions[].revision`|int|仅作者有权读取|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`versions[].title`|string|仅作者有权读取|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`versions[].updatedAt`|string|仅作者有权读取|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`versions[].version`|int|仅作者有权读取|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "work_example_1",
    "userId": "user_example_1",
    "title": "登录页面演示",
    "description": "课程项目成果",
    "role": "客户端开发",
    "myContribution": "完成登录与会话",
    "sourceProjectId": "project_example_1",
    "artifacts": [
      {
        "kind": "LINK",
        "url": "https://example.invalid/demo",
        "title": "示例演示材料"
      }
    ],
    "audience": "PRIVATE",
    "status": "ACTIVE",
    "versions": [],
    "revision": 1,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-profile-09"></a>

### R_PROFILE_09 — 提交 · 本人 / 作品 / 撤回

适用接口：[PROFILE-09](#api-profile-09)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`artifacts`|object[]|必有|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`artifacts[].fileId`|string|仅kind=FILE时必有|当前有权的READY文件ID；LINK分支不传；课程证明FILE范围缺口见第10节|
|`artifacts[].kind`|string|父记录存在时必有|当前子结构的种类；LINK使用url；FILE使用fileId，二者互斥；title必有|
|`artifacts[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`artifacts[].url`|string|仅kind=LINK时必有|材料LINK或短期传输地址，按所属分支区分；有效http/https，1～2048；FILE分支不传|
|`audience`|string|必有|本条可见范围；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`description`|string|必有|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`myContribution`|string|必有|当前本人确认有效的贡献份额；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`role`|string|必有|作品中本人承担的角色描述；遵守本字段类型、统一上限及对应业务条件|
|`sourceProjectId`|string|必有|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`userId`|string|必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`versions`|object[]|仅作者有权读取|内容历史版本，仅作者或有权处理者；遵守本字段类型、统一上限及对应业务条件|
|`versions[].artifacts`|object[]|仅作者有权读取|成果材料引用，LINK与FILE分支见第6.2节；可省略默认[]，单次最多10个当前有权引用|
|`versions[].artifacts[].fileId`|string|仅FILE必有|当前有权READY文件ID；按当前权限及该版本业务范围返回|
|`versions[].artifacts[].kind`|string|父记录存在时必有|LINK/FILE；按当前权限及该版本业务范围返回|
|`versions[].artifacts[].title`|string|父记录存在时必有|本人填写的引用标题；按当前权限及该版本业务范围返回|
|`versions[].artifacts[].url`|string|仅LINK必有|外链材料地址；按当前权限及该版本业务范围返回|
|`versions[].audience`|string|仅作者有权读取|本条可见范围；遵守本字段类型、统一上限及对应业务条件|
|`versions[].content`|string|仅作者有权读取|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`versions[].createdAt`|string|仅作者有权读取|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`versions[].description`|string|仅作者有权读取|说明文字；遵守本字段类型、统一上限及对应业务条件|
|`versions[].id`|string|仅作者有权读取|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`versions[].myContribution`|string|仅作者有权读取|当前本人确认有效的贡献份额；遵守本字段类型、统一上限及对应业务条件|
|`versions[].revision`|int|仅作者有权读取|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`versions[].role`|string|仅作者有权读取|作品中本人承担的角色描述；遵守本字段类型、统一上限及对应业务条件|
|`versions[].sourceProjectId`|string|仅作者有权读取|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`versions[].status`|string|仅作者有权读取|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`versions[].title`|string|仅作者有权读取|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`versions[].updatedAt`|string|仅作者有权读取|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`versions[].userId`|string|仅作者有权读取|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`versions[].version`|int|仅作者有权读取|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "work_example_1",
    "userId": "user_example_1",
    "title": "登录页面演示",
    "description": "课程项目成果",
    "role": "客户端开发",
    "myContribution": "完成登录与会话",
    "sourceProjectId": "project_example_1",
    "artifacts": [
      {
        "kind": "LINK",
        "url": "https://example.invalid/demo",
        "title": "示例演示材料"
      }
    ],
    "audience": "PRIVATE",
    "status": "WITHDRAWN",
    "versions": [
      {
        "id": "work_example_1",
        "userId": "user_example_1",
        "title": "登录页面演示",
        "description": "课程项目成果",
        "role": "客户端开发",
        "myContribution": "完成登录与会话",
        "sourceProjectId": "project_example_1",
        "artifacts": [
          {
            "kind": "LINK",
            "url": "https://example.invalid/demo",
            "title": "示例演示材料"
          }
        ],
        "audience": "PRIVATE",
        "status": "ACTIVE",
        "revision": 1,
        "createdAt": "2026-11-02T08:00:00Z"
      }
    ],
    "revision": 2,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-profile-10"></a>

### R_PROFILE_10 — 提交 · 合作互评 / 撤回

适用接口：[PROFILE-10](#api-profile-10)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`canEdit`|bool|必有|当前调用者是否可编辑；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`canWithdraw`|bool|必有|当前调用者是否可撤回；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`comment`|string|必有|本人意见；可省略，默认空，岗位/约定回应最多500|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`observations`|object[]|必有|按维度的本人观察；遵守本字段类型、统一上限及对应业务条件|
|`observations[].dimension`|string|父记录存在时必有|互评维度标识；遵守本字段类型、统一上限及对应业务条件|
|`observations[].evidenceText`|string|父记录存在时必有|具体观察事实；遵守本字段类型、统一上限及对应业务条件|
|`observations[].level`|string|父记录存在时必有|本维度观察等级；遵守本字段类型、统一上限及对应业务条件|
|`observedEnough`|bool|必有|本维度是否有足够实际观察；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectName`|string|必有|项目展示名称；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targetName`|string|必有|被评价者显示名；遵守本字段类型、统一上限及对应业务条件|
|`targetUserId`|string|必有|本次明确影响的另一用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`versions`|object[]|仅作者有权读取|内容历史版本，仅作者或有权处理者；遵守本字段类型、统一上限及对应业务条件|
|`versions[].content`|string|仅作者有权读取|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`versions[].revision`|int|仅作者有权读取|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`versions[].title`|string|仅作者有权读取|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`versions[].updatedAt`|string|仅作者有权读取|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`versions[].version`|int|仅作者有权读取|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`withdrawalReason`|string|必有|本人撤回理由；遵守本字段类型、统一上限及对应业务条件|
|`withdrawnAt`|string|必有|服务器撤回时间；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "peer_review_example_1",
    "projectId": "project_example_1",
    "projectName": "课程协作应用",
    "authorId": "user_example_1",
    "targetUserId": "user_example_2",
    "targetName": "示例队员",
    "observedEnough": true,
    "observations": [
      {
        "dimension": "DELIVERY",
        "level": "POSITIVE",
        "evidenceText": "按验收标准完成成果"
      },
      {
        "dimension": "COMMUNICATION",
        "level": "NOT_OBSERVED",
        "evidenceText": ""
      },
      {
        "dimension": "REWORK",
        "level": "NOT_OBSERVED",
        "evidenceText": ""
      }
    ],
    "comment": "仅评价实际合作事实",
    "status": "WITHDRAWN",
    "canEdit": false,
    "canWithdraw": false,
    "versions": [],
    "revision": 2,
    "createdAt": "2026-11-02T08:00:00Z",
    "withdrawalReason": "按实际协作情况说明",
    "withdrawnAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-ai-01"></a>

### R_AI_01 — 读取 · 项目 / 项目AI参考

适用接口：[AI-01](#api-ai-01)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].clientRecordId`|string|父记录存在时必有|本人原项目AI草稿的稳定记录编号；1～80，字母数字下划线短横线；不当作服务器AI证明|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].createdBy`|string|父记录存在时必有|会话确定的创建人ID；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].model`|string|父记录存在时必有|本人所用AI模型展示名，不含密钥；0～120字符，不接受API密钥|
|`items[].origin`|string|父记录存在时必有|固定CLIENT_BYOK_LEADER_CONFIRMED来源声明；遵守本字段类型、统一上限及对应业务条件|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].scene`|string|父记录存在时必有|AI参考场景枚举；TOPIC_ANALYSIS/TASK_PLAN/DOCUMENT_REVIEW/RECRUITMENT|
|`items[].sourceProjectId`|string|父记录存在时必有|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].text`|string|父记录存在时必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`items[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "ai_reference_example_1",
        "projectId": "project_example_1",
        "sourceProjectId": "project_example_1",
        "clientRecordId": "client_ai_example_1",
        "scene": "TASK_PLAN",
        "model": "test-model",
        "title": "任务拆解参考",
        "text": "将登录与会话分别验收。",
        "createdBy": "user_example_1",
        "revision": 1,
        "createdAt": "2026-11-02T08:00:00Z",
        "origin": "CLIENT_BYOK_LEADER_CONFIRMED",
        "authorId": "user_example_1"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-ai-02"></a>

### R_AI_02 — 提交 · 项目 / 组长确认的个人AI参考

适用接口：[AI-02](#api-ai-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`clientRecordId`|string|必有|本人原项目AI草稿的稳定记录编号；1～80，字母数字下划线短横线；不当作服务器AI证明|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`createdBy`|string|必有|会话确定的创建人ID；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`model`|string|必有|本人所用AI模型展示名，不含密钥；0～120字符，不接受API密钥|
|`origin`|string|必有|固定CLIENT_BYOK_LEADER_CONFIRMED来源声明；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`scene`|string|必有|AI参考场景枚举；TOPIC_ANALYSIS/TASK_PLAN/DOCUMENT_REVIEW/RECRUITMENT|
|`sourceProjectId`|string|必有|原来源项目ID，不可暗换项目；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`text`|string|必有|标准或参考文字；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "ai_reference_example_1",
    "projectId": "project_example_1",
    "sourceProjectId": "project_example_1",
    "clientRecordId": "client_ai_example_1",
    "scene": "TASK_PLAN",
    "model": "test-model",
    "title": "登录页面",
    "text": "将登录与会话分别验收。",
    "createdBy": "user_example_1",
    "revision": 1,
    "createdAt": "2026-11-02T08:00:00Z",
    "origin": "CLIENT_BYOK_LEADER_CONFIRMED",
    "authorId": "user_example_1"
  }
}
```

<a id="response-r-dice-01"></a>

### R_DICE_01 — 提交 · 任务 / 正式骰子会话

适用接口：[DICE-01](#api-dice-01)、[DICE-02](#api-dice-02)、[DICE-05](#api-dice-05)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`claimStatus`|string|必有|原任务认领NOT_REQUESTED/PENDING_ORIGINAL_CLAIM/SUCCEEDED/FAILED；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`inviteEndsAt`|string|必有|骰子参与邀请截止；RFC3339时间点，响应UTC Z，服务器比较有效性；可空条件见模型|
|`maxRounds`|int|必有|骰子轮数上限固定3；遵守本字段类型、统一上限及对应业务条件|
|`outcomeDecision`|string/null|必有；允许null|拟领取人本人的领取意愿；遵守本字段类型、统一上限及对应业务条件|
|`participants`|object[]|必有|会话参与者状态；遵守本字段类型、统一上限及对应业务条件|
|`participants[].activeInRound`|bool|父记录存在时必有|该参与人是否属于当前轮候选；遵守本字段类型、统一上限及对应业务条件|
|`participants[].decision`|string|父记录存在时必有|本人明确提交的决定；允许值：按本条业务规则列明的决定|
|`participants[].hasRolled`|bool|父记录存在时必有|本人是否已投本轮，不泄露未揭晓他人点数；遵守本字段类型、统一上限及对应业务条件|
|`participants[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposedOwnerId`|string/null|必有；允许null|唯一拟领取人ID，尚不是任务负责人；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`rollEndsAt`|string|必有|骰子投掷截止；RFC3339时间点，响应UTC Z，服务器比较有效性；可空条件见模型|
|`roundNo`|int|必有|骰子当前轮次；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`rounds`|object[]|必有|已允许揭晓的轮次记录；遵守本字段类型、统一上限及对应业务条件|
|`rounds[].candidateIds`|string[]|父记录存在时必有|当前调用者是否可didateIds；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`rounds[].rolls`|object[]|父记录存在时必有|本轮可见的投骰记录；遵守本字段类型、统一上限及对应业务条件|
|`rounds[].rolls[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`rounds[].rolls[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rounds[].rolls[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rounds[].rolls[].value`|int|父记录存在时必有|服务端生成的骰子点数1～6；遵守本字段类型、统一上限及对应业务条件|
|`rounds[].roundNo`|int|父记录存在时必有|骰子当前轮次；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`rounds[].winnerId`|string/null|必有；允许null|唯一胜者ID；没有时null；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rule`|string|必有|骰子MAX/MIN/TARGET或本资源规则，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targetValue`|int/null|必有；允许null|TARGET目标1～6；非TARGET为null；TARGET为整数1～6，其他规则为null|
|`taskId`|string|必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`taskRevision`|int|必有|本次任务版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`terminationReason`|string/null|必有；允许null|组长实际终止理由；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "dice_example_1",
    "projectId": "project_example_1",
    "taskId": "task_example_1",
    "taskRevision": 1,
    "revision": 1,
    "rule": "MAX",
    "targetValue": null,
    "maxRounds": 3,
    "roundNo": 1,
    "status": "INVITING",
    "inviteEndsAt": "2026-11-02T08:15:00Z",
    "rollEndsAt": "2026-11-02T08:30:00Z",
    "participants": [
      {
        "userId": "user_example_1",
        "decision": "PENDING",
        "activeInRound": true,
        "hasRolled": false
      },
      {
        "userId": "user_example_2",
        "decision": "PENDING",
        "activeInRound": true,
        "hasRolled": false
      }
    ],
    "rounds": [],
    "proposedOwnerId": null,
    "outcomeDecision": null,
    "claimStatus": "NOT_REQUESTED",
    "terminationReason": null,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-dice-03"></a>

### R_DICE_03 — 提交 · 正式骰子会话 / 本人参加决定

适用接口：[DICE-03](#api-dice-03)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`claimStatus`|string|必有|原任务认领NOT_REQUESTED/PENDING_ORIGINAL_CLAIM/SUCCEEDED/FAILED；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`inviteEndsAt`|string|必有|骰子参与邀请截止；RFC3339时间点，响应UTC Z，服务器比较有效性；可空条件见模型|
|`maxRounds`|int|必有|骰子轮数上限固定3；遵守本字段类型、统一上限及对应业务条件|
|`outcomeDecision`|string/null|必有；允许null|拟领取人本人的领取意愿；遵守本字段类型、统一上限及对应业务条件|
|`participants`|object[]|必有|会话参与者状态；遵守本字段类型、统一上限及对应业务条件|
|`participants[].activeInRound`|bool|父记录存在时必有|该参与人是否属于当前轮候选；遵守本字段类型、统一上限及对应业务条件|
|`participants[].decision`|string|父记录存在时必有|本人明确提交的决定；允许值：ACCEPT/DECLINE|
|`participants[].hasRolled`|bool|父记录存在时必有|本人是否已投本轮，不泄露未揭晓他人点数；遵守本字段类型、统一上限及对应业务条件|
|`participants[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`proposedOwnerId`|string/null|必有；允许null|唯一拟领取人ID，尚不是任务负责人；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`rollEndsAt`|string|必有|骰子投掷截止；RFC3339时间点，响应UTC Z，服务器比较有效性；可空条件见模型|
|`roundNo`|int|必有|骰子当前轮次；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`rounds`|object[]|必有|已允许揭晓的轮次记录；遵守本字段类型、统一上限及对应业务条件|
|`rounds[].candidateIds`|string[]|父记录存在时必有|当前调用者是否可didateIds；只读提示，最终写入重验权限；遵守本字段类型、统一上限及对应业务条件|
|`rounds[].rolls`|object[]|父记录存在时必有|本轮可见的投骰记录；遵守本字段类型、统一上限及对应业务条件|
|`rounds[].rolls[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`rounds[].rolls[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rounds[].rolls[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rounds[].rolls[].value`|int|父记录存在时必有|服务端生成的骰子点数1～6；遵守本字段类型、统一上限及对应业务条件|
|`rounds[].roundNo`|int|父记录存在时必有|骰子当前轮次；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`rounds[].winnerId`|string/null|必有；允许null|唯一胜者ID；没有时null；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`rule`|string|必有|骰子MAX/MIN/TARGET或本资源规则，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`targetValue`|int/null|必有；允许null|TARGET目标1～6；非TARGET为null；TARGET为整数1～6，其他规则为null|
|`taskId`|string|必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`taskRevision`|int|必有|本次任务版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`terminationReason`|string/null|必有；允许null|组长实际终止理由；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "dice_example_1",
    "projectId": "project_example_1",
    "taskId": "task_example_1",
    "taskRevision": 1,
    "revision": 2,
    "rule": "MAX",
    "targetValue": null,
    "maxRounds": 3,
    "roundNo": 1,
    "status": "INVITING",
    "inviteEndsAt": "2026-11-02T08:15:00Z",
    "rollEndsAt": "2026-11-02T08:30:00Z",
    "participants": [
      {
        "userId": "user_example_1",
        "decision": "ACCEPT",
        "activeInRound": true,
        "hasRolled": false
      },
      {
        "userId": "user_example_2",
        "decision": "PENDING",
        "activeInRound": true,
        "hasRolled": false
      }
    ],
    "rounds": [],
    "proposedOwnerId": null,
    "outcomeDecision": null,
    "claimStatus": "NOT_REQUESTED",
    "terminationReason": null,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-dice-04"></a>

### R_DICE_04 — 提交 · 正式骰子会话 / 本人本轮投骰

适用接口：[DICE-04](#api-dice-04)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`roll`|object|必有|本次本人投骰结果；遵守本字段类型、统一上限及对应业务条件|
|`roll.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`roll.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`roll.roundNo`|int|父记录存在时必有|骰子当前轮次；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`roll.sessionId`|string|父记录存在时必有|当前结构固定的session资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`roll.userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`roll.value`|int|父记录存在时必有|服务端生成的骰子点数1～6；遵守本字段类型、统一上限及对应业务条件|
|`roundComplete`|bool|必有|本轮是否收齐可揭晓；遵守本字段类型、统一上限及对应业务条件|
|`sessionRevision`|int|必有|骰子会话当前版本；整数1～999999999；仅明确允许的首次私有记录用0|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "roll": {
      "id": "roll_example_1",
      "sessionId": "dice_example_1",
      "roundNo": 1,
      "userId": "user_example_1",
      "value": 4,
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "sessionRevision": 3,
    "roundComplete": false
  }
}
```

<a id="response-r-dice-06"></a>

### R_DICE_06 — 提交 · 正式骰子会话 / 拟领取人意愿

适用接口：[DICE-06](#api-dice-06)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`claimStatus`|string|必有|原任务认领NOT_REQUESTED/PENDING_ORIGINAL_CLAIM/SUCCEEDED/FAILED；遵守本字段类型、统一上限及对应业务条件|
|`expectedTaskRevision`|int|必有|读取并固定的任务并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`outcomeDecision`|string/null|必有；允许null|拟领取人本人的领取意愿；遵守本字段类型、统一上限及对应业务条件|
|`proposedOwnerId`|string/null|必有；允许null|唯一拟领取人ID，尚不是任务负责人；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sessionId`|string|必有|当前结构固定的session资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`taskId`|string|必有|任务ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "sessionId": "dice_example_1",
    "revision": 6,
    "status": "AWAITING_CLAIM",
    "proposedOwnerId": "user_example_1",
    "outcomeDecision": "ACCEPT",
    "claimStatus": "PENDING_ORIGINAL_CLAIM",
    "taskId": "task_example_1",
    "expectedTaskRevision": 1
  }
}
```

<a id="response-r-board-01"></a>

### R_BOARD_01 — 读取 · 项目 / 留言主题

适用接口：[BOARD-01](#api-board-01)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].authorName`|string|父记录存在时必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].closeReason`|string/null|必有；允许null|有权关闭的真实原因，未关闭null；遵守本字段类型、统一上限及对应业务条件|
|`items[].content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].following`|bool|父记录存在时必有|本人是否关注主题；遵守本字段类型、统一上限及对应业务条件|
|`items[].helpfulReplyId`|string/null|必有；允许null|作者选择的同主题有帮助回复；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].mentions`|string[]|父记录存在时必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`items[].pinned`|bool|父记录存在时必有|组长是否置顶本主题；遵守本字段类型、统一上限及对应业务条件|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].refs`|object[]|父记录存在时必有|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`items[].refs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].refs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].refs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].replyCount`|int|父记录存在时必有|主题当前回复数量；遵守本字段类型、统一上限及对应业务条件|
|`items[].resolutionNote`|string/null|必有；允许null|提问者本人解决说明，未解决可null；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].tag`|string|父记录存在时必有|主题QUESTION/SUGGESTION/MATERIAL/CONCLUSION标签；遵守本字段类型、统一上限及对应业务条件|
|`items[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].updatedAt`|string|父记录存在时必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].withdrawn`|bool|父记录存在时必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "topic_example_1",
        "projectId": "project_example_1",
        "authorId": "user_example_1",
        "authorName": "示例学生",
        "title": "接口字段核对",
        "content": "请核对普通任务提交字段。",
        "tag": "QUESTION",
        "refs": [
          {
            "type": "TASK",
            "id": "task_example_1",
            "version": 1
          }
        ],
        "mentions": [],
        "status": "OPEN",
        "revision": 1,
        "withdrawn": false,
        "replyCount": 0,
        "helpfulReplyId": null,
        "resolutionNote": null,
        "closeReason": null,
        "pinned": false,
        "following": true,
        "createdAt": "2026-11-02T08:00:00Z",
        "updatedAt": "2026-11-02T08:00:00Z"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-board-02"></a>

### R_BOARD_02 — 提交 · 项目 / 留言主题

适用接口：[BOARD-02](#api-board-02)、[BOARD-06](#api-board-06)、[BOARD-12](#api-board-12)、[BOARD-13](#api-board-13)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`authorName`|string|必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`closeReason`|string/null|必有；允许null|有权关闭的真实原因，未关闭null；遵守本字段类型、统一上限及对应业务条件|
|`content`|string|必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`following`|bool|必有|本人是否关注主题；遵守本字段类型、统一上限及对应业务条件|
|`helpfulReplyId`|string/null|必有；允许null|作者选择的同主题有帮助回复；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`mentions`|string[]|必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`pinned`|bool|必有|组长是否置顶本主题；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs`|object[]|必有|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`refs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`refs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`replyCount`|int|必有|主题当前回复数量；遵守本字段类型、统一上限及对应业务条件|
|`resolutionNote`|string/null|必有；允许null|提问者本人解决说明，未解决可null；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`tag`|string|必有|主题QUESTION/SUGGESTION/MATERIAL/CONCLUSION标签；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`withdrawn`|bool|必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "topic_example_1",
    "projectId": "project_example_1",
    "authorId": "user_example_1",
    "authorName": "示例学生",
    "title": "接口字段核对",
    "content": "请核对普通任务提交字段。",
    "tag": "QUESTION",
    "refs": [
      {
        "type": "TASK",
        "id": "task_example_1",
        "version": 1
      }
    ],
    "mentions": [],
    "status": "OPEN",
    "revision": 1,
    "withdrawn": false,
    "replyCount": 0,
    "helpfulReplyId": null,
    "resolutionNote": null,
    "closeReason": null,
    "pinned": false,
    "following": true,
    "createdAt": "2026-11-02T08:00:00Z",
    "updatedAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-board-03"></a>

### R_BOARD_03 — 读取 · 留言主题

适用接口：[BOARD-03](#api-board-03)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`authorName`|string|必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`closeReason`|string/null|必有；允许null|有权关闭的真实原因，未关闭null；遵守本字段类型、统一上限及对应业务条件|
|`content`|string|必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`following`|bool|必有|本人是否关注主题；遵守本字段类型、统一上限及对应业务条件|
|`helpfulReplyId`|string/null|必有；允许null|作者选择的同主题有帮助回复；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`mentions`|string[]|必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`pinned`|bool|必有|组长是否置顶本主题；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs`|object[]|必有|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`refs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`refs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`replies`|object|必有|本资源有权回复分页集合；遵守本字段类型、统一上限及对应业务条件|
|`replies.hasMore`|bool|父记录存在时必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`replies.items`|object[]|父记录存在时必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`replies.items[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`replies.items[].authorName`|string|父记录存在时必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`replies.items[].content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`replies.items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`replies.items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`replies.items[].mentions`|string[]|父记录存在时必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`replies.items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`replies.items[].replyToId`|string/null|必有；允许null|同主题被回应回复ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`replies.items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`replies.items[].topicId`|string|父记录存在时必有|当前结构固定的topic资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`replies.items[].updatedAt`|string|父记录存在时必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`replies.items[].withdrawn`|bool|父记录存在时必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|
|`replies.nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`replies.total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`replyCount`|int|必有|主题当前回复数量；遵守本字段类型、统一上限及对应业务条件|
|`resolutionNote`|string/null|必有；允许null|提问者本人解决说明，未解决可null；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`tag`|string|必有|主题QUESTION/SUGGESTION/MATERIAL/CONCLUSION标签；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`withdrawn`|bool|必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "topic_example_1",
    "projectId": "project_example_1",
    "authorId": "user_example_1",
    "authorName": "示例学生",
    "title": "接口字段核对",
    "content": "请核对普通任务提交字段。",
    "tag": "QUESTION",
    "refs": [
      {
        "type": "TASK",
        "id": "task_example_1",
        "version": 1
      }
    ],
    "mentions": [],
    "status": "OPEN",
    "revision": 1,
    "withdrawn": false,
    "replyCount": 1,
    "helpfulReplyId": null,
    "resolutionNote": null,
    "closeReason": null,
    "pinned": false,
    "following": true,
    "createdAt": "2026-11-02T08:00:00Z",
    "updatedAt": "2026-11-02T08:00:00Z",
    "replies": {
      "items": [
        {
          "id": "reply_example_1",
          "topicId": "topic_example_1",
          "projectId": "project_example_1",
          "authorId": "user_example_1",
          "authorName": "示例学生",
          "content": "建议先核对请求字段。",
          "replyToId": null,
          "mentions": [],
          "revision": 1,
          "withdrawn": false,
          "createdAt": "2026-11-02T08:00:00Z",
          "updatedAt": "2026-11-02T08:00:00Z"
        }
      ],
      "nextCursor": null,
      "hasMore": false,
      "total": 1
    }
  }
}
```

<a id="response-r-board-04"></a>

### R_BOARD_04 — 读取 · 留言主题 / 回复

适用接口：[BOARD-04](#api-board-04)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].authorName`|string|父记录存在时必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].mentions`|string[]|父记录存在时必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].replyToId`|string/null|必有；允许null|同主题被回应回复ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].topicId`|string|父记录存在时必有|当前结构固定的topic资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].updatedAt`|string|父记录存在时必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].withdrawn`|bool|父记录存在时必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "reply_example_1",
        "topicId": "topic_example_1",
        "projectId": "project_example_1",
        "authorId": "user_example_1",
        "authorName": "示例学生",
        "content": "建议先核对请求字段。",
        "replyToId": null,
        "mentions": [],
        "revision": 1,
        "withdrawn": false,
        "createdAt": "2026-11-02T08:00:00Z",
        "updatedAt": "2026-11-02T08:00:00Z"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-board-05"></a>

### R_BOARD_05 — 提交 · 留言主题 / 回复

适用接口：[BOARD-05](#api-board-05)、[BOARD-09](#api-board-09)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`authorName`|string|必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`content`|string|必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`mentions`|string[]|必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`replyToId`|string/null|必有；允许null|同主题被回应回复ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`topicId`|string|必有|当前结构固定的topic资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`withdrawn`|bool|必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "reply_example_1",
    "topicId": "topic_example_1",
    "projectId": "project_example_1",
    "authorId": "user_example_1",
    "authorName": "示例学生",
    "content": "建议先核对请求字段。",
    "replyToId": null,
    "mentions": [],
    "revision": 1,
    "withdrawn": false,
    "createdAt": "2026-11-02T08:00:00Z",
    "updatedAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-board-07"></a>

### R_BOARD_07 — 提交 · 留言主题 / 撤回

适用接口：[BOARD-07](#api-board-07)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`authorName`|string|必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`closeReason`|string/null|必有；允许null|有权关闭的真实原因，未关闭null；遵守本字段类型、统一上限及对应业务条件|
|`content`|string/null|必有；允许null|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`following`|bool|必有|本人是否关注主题；遵守本字段类型、统一上限及对应业务条件|
|`helpfulReplyId`|string/null|必有；允许null|作者选择的同主题有帮助回复；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`mentions`|string[]|必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`pinned`|bool|必有|组长是否置顶本主题；遵守本字段类型、统一上限及对应业务条件|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs`|object[]|必有|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`refs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`refs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`replyCount`|int|必有|主题当前回复数量；遵守本字段类型、统一上限及对应业务条件|
|`resolutionNote`|string/null|必有；允许null|提问者本人解决说明，未解决可null；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`tag`|string|必有|主题QUESTION/SUGGESTION/MATERIAL/CONCLUSION标签；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`withdrawn`|bool|必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "topic_example_1",
    "projectId": "project_example_1",
    "authorId": "user_example_1",
    "authorName": "示例学生",
    "title": "接口字段核对",
    "content": null,
    "tag": "QUESTION",
    "refs": [
      {
        "type": "TASK",
        "id": "task_example_1",
        "version": 1
      }
    ],
    "mentions": [],
    "status": "OPEN",
    "revision": 2,
    "withdrawn": true,
    "replyCount": 0,
    "helpfulReplyId": null,
    "resolutionNote": null,
    "closeReason": null,
    "pinned": false,
    "following": true,
    "createdAt": "2026-11-02T08:00:00Z",
    "updatedAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-board-08"></a>

### R_BOARD_08 — 读取 · 留言主题 / 正文修订

适用接口：[BOARD-08](#api-board-08)、[BOARD-11](#api-board-11)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`items[].editedAt`|string|父记录存在时必有|服务器正文修改时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].editorId`|string|父记录存在时必有|本次实际修订人的会话ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "version": 1,
        "content": "请核对普通任务提交字段。",
        "editedAt": "2026-11-02T08:00:00Z",
        "editorId": "user_example_1"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-board-10"></a>

### R_BOARD_10 — 提交 · 主题回复 / 撤回

适用接口：[BOARD-10](#api-board-10)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`authorName`|string|必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`content`|string/null|必有；允许null|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`mentions`|string[]|必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`replyToId`|string/null|必有；允许null|同主题被回应回复ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`topicId`|string|必有|当前结构固定的topic资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`withdrawn`|bool|必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "reply_example_1",
    "topicId": "topic_example_1",
    "projectId": "project_example_1",
    "authorId": "user_example_1",
    "authorName": "示例学生",
    "content": null,
    "replyToId": null,
    "mentions": [],
    "revision": 2,
    "withdrawn": true,
    "createdAt": "2026-11-02T08:00:00Z",
    "updatedAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-board-14"></a>

### R_BOARD_14 — 保存 · 留言主题 / 本人 / 本人关注

适用接口：[BOARD-14](#api-board-14)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`following`|bool|必有|本人是否关注主题；遵守本字段类型、统一上限及对应业务条件|
|`topicId`|string|必有|当前结构固定的topic资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "topicId": "topic_example_1",
    "following": false
  }
}
```

<a id="response-r-chat-01"></a>

### R_CHAT_01 — 读取 · 项目 / 聊天消息

适用接口：[CHAT-01](#api-chat-01)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`changes`|object[]|必有|当前有权的旧消息变更投影；遵守本字段类型、统一上限及对应业务条件|
|`changes[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`changes[].authorName`|string|父记录存在时必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`changes[].clientMessageId`|string|父记录存在时必有|本人项目内稳定发送编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`changes[].content`|string/null|必有；允许null|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`changes[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`changes[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`changes[].mentions`|string[]|父记录存在时必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`changes[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`changes[].refs`|object[]|父记录存在时必有|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`changes[].refs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`changes[].refs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`changes[].refs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`changes[].replyToId`|string/null|必有；允许null|同主题被回应回复ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`changes[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`changes[].sequence`|int|父记录存在时必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|
|`changes[].withdrawn`|bool|父记录存在时必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`hasMoreChanges`|bool|必有|是否仍有未读变更批次；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].authorName`|string|父记录存在时必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].clientMessageId`|string|父记录存在时必有|本人项目内稳定发送编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].mentions`|string[]|父记录存在时必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`items[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].refs`|object[]|父记录存在时必有|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`items[].refs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].refs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].refs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].replyToId`|string/null|必有；允许null|同主题被回应回复ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].sequence`|int|父记录存在时必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|
|`items[].withdrawn`|bool|父记录存在时必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|
|`latestSequence`|int|必有|本项目当前最新聊天序号；遵守本字段类型、统一上限及对应业务条件|
|`nextAfterSequence`|int|必有|本批连续返回末端序号；遵守本字段类型、统一上限及对应业务条件|
|`nextChangeCursor`|string|必有|下一批聊天变更游标；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "message_example_1",
        "projectId": "project_example_1",
        "clientMessageId": "deviceA-msg-0001",
        "sequence": 2,
        "authorId": "user_example_1",
        "authorName": "示例学生",
        "content": "登录接口已经准备好，请核对。",
        "replyToId": null,
        "mentions": [],
        "refs": [
          {
            "type": "TASK",
            "id": "task_example_1",
            "version": 1
          }
        ],
        "revision": 1,
        "withdrawn": false,
        "createdAt": "2026-11-02T08:00:00Z"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "nextAfterSequence": 2,
    "latestSequence": 2,
    "changes": [
      {
        "id": "message_old_example_1",
        "projectId": "project_example_1",
        "clientMessageId": "deviceA-old-0001",
        "sequence": 1,
        "authorId": "user_example_1",
        "authorName": "示例学生",
        "content": null,
        "replyToId": null,
        "mentions": [],
        "refs": [],
        "revision": 2,
        "withdrawn": true,
        "createdAt": "2026-11-02T08:00:00Z"
      }
    ],
    "nextChangeCursor": "chat-change-test-3",
    "hasMoreChanges": false
  }
}
```

<a id="response-r-chat-02"></a>

### R_CHAT_02 — 提交 · 项目 / 聊天消息

适用接口：[CHAT-02](#api-chat-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`authorName`|string|必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`clientMessageId`|string|必有|本人项目内稳定发送编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`content`|string|必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`mentions`|string[]|必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs`|object[]|必有|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`refs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`refs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`replyToId`|string/null|必有；允许null|同主题被回应回复ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sequence`|int|必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|
|`withdrawn`|bool|必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "message_example_1",
    "projectId": "project_example_1",
    "clientMessageId": "deviceA-msg-0001",
    "sequence": 1,
    "authorId": "user_example_1",
    "authorName": "示例学生",
    "content": "登录接口已经准备好，请核对。",
    "replyToId": null,
    "mentions": [],
    "refs": [
      {
        "type": "TASK",
        "id": "task_example_1",
        "version": 1
      }
    ],
    "revision": 1,
    "withdrawn": false,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-chat-03"></a>

### R_CHAT_03 — 读取 · 项目 / 原发送编号核对

适用接口：[CHAT-03](#api-chat-03)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`found`|bool|必有|按原客户端编号是否找到有权记录；遵守本字段类型、统一上限及对应业务条件|
|`message`|object|必有|申请/邀请说明或结果提示，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`message.authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message.authorName`|string|父记录存在时必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`message.clientMessageId`|string|父记录存在时必有|本人项目内稳定发送编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message.content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`message.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`message.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message.mentions`|string[]|父记录存在时必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`message.projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message.refs`|object[]|父记录存在时必有|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`message.refs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message.refs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`message.refs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`message.replyToId`|string/null|必有；允许null|同主题被回应回复ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`message.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`message.sequence`|int|父记录存在时必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|
|`message.withdrawn`|bool|父记录存在时必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "found": true,
    "message": {
      "id": "message_example_1",
      "projectId": "project_example_1",
      "clientMessageId": "deviceA-msg-0001",
      "sequence": 1,
      "authorId": "user_example_1",
      "authorName": "示例学生",
      "content": "登录接口已经准备好，请核对。",
      "replyToId": null,
      "mentions": [],
      "refs": [
        {
          "type": "TASK",
          "id": "task_example_1",
          "version": 1
        }
      ],
      "revision": 1,
      "withdrawn": false,
      "createdAt": "2026-11-02T08:00:00Z"
    }
  }
}
```

<a id="response-r-chat-04"></a>

### R_CHAT_04 — 提交 · 聊天消息 / 撤回

适用接口：[CHAT-04](#api-chat-04)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`authorName`|string|必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`clientMessageId`|string|必有|本人项目内稳定发送编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`content`|string/null|必有；允许null|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`mentions`|string[]|必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs`|object[]|必有|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`refs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`refs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`replyToId`|string/null|必有；允许null|同主题被回应回复ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sequence`|int|必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|
|`withdrawn`|bool|必有|是否已撤回；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "message_example_1",
    "projectId": "project_example_1",
    "clientMessageId": "deviceA-msg-0001",
    "sequence": 1,
    "authorId": "user_example_1",
    "authorName": "示例学生",
    "content": null,
    "replyToId": null,
    "mentions": [],
    "refs": [],
    "revision": 2,
    "withdrawn": true,
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-chat-05"></a>

### R_CHAT_05 — 读取 · 项目 / 本人 / 本人连续阅读位置

适用接口：[CHAT-05](#api-chat-05)、[CHAT-06](#api-chat-06)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`projectId`|string|必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sequence`|int|必有|本资源顺序号，提交/聊天分别单调增长；非负安全整数，不超过9007199254740991；按聊天互斥/连续下发规则；提交sequence从1单调增长|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "projectId": "project_example_1",
    "sequence": 0,
    "revision": 1
  }
}
```

<a id="response-r-notice-01"></a>

### R_NOTICE_01 — 读取 · 课程 / 课程通知

适用接口：[NOTICE-01](#api-notice-01)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].acknowledgeDueAt`|string/null|必有；允许null|本版知悉截止，要求知悉时非空；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].assignmentId`|string/null|必有；允许null|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].authorName`|string|父记录存在时必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`items[].category`|string|父记录存在时必有|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`items[].changeSummary`|string/null|必有；允许null|本次实质修订/范围调整说明；遵守本字段类型、统一上限及对应业务条件|
|`items[].content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`items[].contentVersion`|int|父记录存在时必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].displayRevision`|int|父记录存在时必有|排版版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].importance`|string|父记录存在时必有|通知NORMAL/IMPORTANT；遵守本字段类型、统一上限及对应业务条件|
|`items[].myReceipt`|object|父记录存在时必有|本人本正文版阅读与知悉状态；遵守本字段类型、统一上限及对应业务条件|
|`items[].myReceipt.acknowledgedAt`|string/null|必有；允许null|本人该正文版知悉时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].myReceipt.contentVersion`|int|父记录存在时必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`items[].myReceipt.readAt`|string/null|必有；允许null|本人该版实际已读记录时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].publisherRole`|string|父记录存在时必有|本次明确发布身份TEACHER/CLASS_ASSISTANT；有多个合法发布身份时必填；单一身份可省略由服务器核定|
|`items[].recipientCount`|int|父记录存在时必有|通知本正文版冻结接收者分母；遵守本字段类型、统一上限及对应业务条件|
|`items[].recipientSnapshotId`|string|父记录存在时必有|通知本正文版接收集合快照ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].refs`|object[]|父记录存在时必有|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`items[].refs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].refs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].refs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].requiresAcknowledgement`|bool|父记录存在时必有|是否要求本版知悉；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "announcement_example_1",
        "squareId": "square_example_1",
        "authorId": "user_example_teacher",
        "authorName": "示例教师",
        "title": "联调安排",
        "content": "请在周五前完成联调。",
        "createdAt": "2026-11-02T08:00:00Z",
        "revision": 1,
        "assignmentId": null,
        "publisherRole": "TEACHER",
        "category": "COURSE_INFO",
        "importance": "NORMAL",
        "contentVersion": 1,
        "displayRevision": 1,
        "recipientSnapshotId": "recipients_example_1",
        "recipientCount": 2,
        "requiresAcknowledgement": false,
        "acknowledgeDueAt": null,
        "expiresAt": null,
        "status": "PUBLISHED",
        "changeSummary": null,
        "myReceipt": {
          "contentVersion": 1,
          "readAt": null,
          "acknowledgedAt": null
        },
        "refs": []
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-notice-02"></a>

### R_NOTICE_02 — 提交 · 课程 / 课程通知

适用接口：[NOTICE-02](#api-notice-02)、[NOTICE-04](#api-notice-04)、[NOTICE-05](#api-notice-05)、[NOTICE-06](#api-notice-06)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`acknowledgeDueAt`|string/null|必有；允许null|本版知悉截止，要求知悉时非空；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`assignmentId`|string/null|必有；允许null|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`authorId`|string|必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`authorName`|string|必有|实际作者显示姓名；遵守本字段类型、统一上限及对应业务条件|
|`category`|string|必有|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`changeSummary`|string/null|必有；允许null|本次实质修订/范围调整说明；遵守本字段类型、统一上限及对应业务条件|
|`content`|string|必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`contentVersion`|int|必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`displayRevision`|int|必有|排版版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`importance`|string|必有|通知NORMAL/IMPORTANT；遵守本字段类型、统一上限及对应业务条件|
|`myReceipt`|object|必有|本人本正文版阅读与知悉状态；遵守本字段类型、统一上限及对应业务条件|
|`myReceipt.acknowledgedAt`|string/null|必有；允许null|本人该正文版知悉时间；遵守本字段类型、统一上限及对应业务条件|
|`myReceipt.contentVersion`|int|父记录存在时必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`myReceipt.readAt`|string/null|必有；允许null|本人该版实际已读记录时间；遵守本字段类型、统一上限及对应业务条件|
|`publisherRole`|string|必有|本次明确发布身份TEACHER/CLASS_ASSISTANT；有多个合法发布身份时必填；单一身份可省略由服务器核定|
|`recipientCount`|int|必有|通知本正文版冻结接收者分母；遵守本字段类型、统一上限及对应业务条件|
|`recipientSnapshotId`|string|必有|通知本正文版接收集合快照ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs`|object[]|必有|受限业务引用集合；可省略默认[]，最多5个当前有权业务引用|
|`refs[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`refs[].type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`refs[].version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`requiresAcknowledgement`|bool|必有|是否要求本版知悉；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string|必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "announcement_example_1",
    "squareId": "square_example_1",
    "authorId": "user_example_teacher",
    "authorName": "示例教师",
    "title": "课程联调安排",
    "content": "请在周五前完成联调。",
    "createdAt": "2026-11-02T08:00:00Z",
    "revision": 1,
    "assignmentId": null,
    "publisherRole": "TEACHER",
    "category": "COURSE_INFO",
    "importance": "NORMAL",
    "contentVersion": 1,
    "displayRevision": 1,
    "recipientSnapshotId": "recipients_example_1",
    "recipientCount": 2,
    "requiresAcknowledgement": false,
    "acknowledgeDueAt": null,
    "expiresAt": null,
    "status": "PUBLISHED",
    "changeSummary": null,
    "myReceipt": {
      "contentVersion": 1,
      "readAt": null,
      "acknowledgedAt": null
    },
    "refs": []
  }
}
```

<a id="response-r-notice-03"></a>

### R_NOTICE_03 — 提交 · 课程 / 通知范围预览

适用接口：[NOTICE-03](#api-notice-03)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`previewToken`|string|必有|固定本人/范围/输入/来源及期限的预览凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`publisherRole`|string|必有|本次明确发布身份TEACHER/CLASS_ASSISTANT；有多个合法发布身份时必填；单一身份可省略由服务器核定|
|`recipientCount`|int|必有|通知本正文版冻结接收者分母；遵守本字段类型、统一上限及对应业务条件|
|`scope`|object|必有|通知真实课程/作业范围；遵守本字段类型、统一上限及对应业务条件|
|`scope.assignmentId`|string/null|必有；允许null|本次作业ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`scope.squareId`|string|父记录存在时必有|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "previewToken": "announcement-preview-test",
    "expiresAt": "2026-11-02T08:10:00Z",
    "publisherRole": "TEACHER",
    "recipientCount": 2,
    "scope": {
      "squareId": "square_example_1",
      "assignmentId": null
    }
  }
}
```

<a id="response-r-notice-07"></a>

### R_NOTICE_07 — 提交 · 课程通知 / 本人正文已读

适用接口：[NOTICE-07](#api-notice-07)、[NOTICE-08](#api-notice-08)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`acknowledgedAt`|string/null|必有；允许null|本人该正文版知悉时间；遵守本字段类型、统一上限及对应业务条件|
|`announcementId`|string|必有|当前结构固定的announcement资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`contentVersion`|int|必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`readAt`|string/null|必有；允许null|本人该版实际已读记录时间；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "announcementId": "announcement_example_1",
    "contentVersion": 1,
    "readAt": "2026-11-02T08:00:00Z",
    "acknowledgedAt": null
  }
}
```

<a id="response-r-notice-09"></a>

### R_NOTICE_09 — 读取 · 课程通知 / 通知版本回执

适用接口：[NOTICE-09](#api-notice-09)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`acknowledgedCount`|int|必有|本版接收快照内知悉人数；遵守本字段类型、统一上限及对应业务条件|
|`contentVersion`|int|必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].acknowledgedAt`|string/null|必有；允许null|本人该正文版知悉时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].displayName`|string|父记录存在时必有|服务器供展示的名称；遵守本字段类型、统一上限及对应业务条件|
|`items[].readAt`|string/null|必有；允许null|本人该版实际已读记录时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].userId`|string|父记录存在时必有|用户ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`readCount`|int|必有|本版接收快照内已读人数；遵守本字段类型、统一上限及对应业务条件|
|`recipientCount`|int|必有|通知本正文版冻结接收者分母；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "contentVersion": 1,
    "recipientCount": 2,
    "readCount": 1,
    "acknowledgedCount": 0,
    "items": [
      {
        "userId": "user_example_1",
        "displayName": "示例学生",
        "readAt": "2026-11-02T08:00:00Z",
        "acknowledgedAt": null
      }
    ],
    "nextCursor": null,
    "hasMore": false
  }
}
```

<a id="response-r-notice-10"></a>

### R_NOTICE_10 — 提交 · 课程通知 / 催阅预览

适用接口：[NOTICE-10](#api-notice-10)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`contentVersion`|int|必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`previewToken`|string|必有|固定本人/范围/输入/来源及期限的预览凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|
|`recipientCount`|int|必有|通知本正文版冻结接收者分母；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "previewToken": "reminder-preview-test",
    "contentVersion": 1,
    "recipientCount": 1,
    "expiresAt": "2026-11-02T08:10:00Z"
  }
}
```

<a id="response-r-notice-11"></a>

### R_NOTICE_11 — 提交 · 课程通知 / 确认催阅

适用接口：[NOTICE-11](#api-notice-11)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`announcementId`|string|必有|当前结构固定的announcement资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`contentVersion`|int|必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`deduplicated`|bool|必有|是否返回原已存在记录而没有新建；遵守本字段类型、统一上限及对应业务条件|
|`recordedCount`|int|必有|本次去重站内索引数，不是设备送达数；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "announcementId": "announcement_example_1",
    "contentVersion": 1,
    "recordedCount": 1,
    "deduplicated": false
  }
}
```

<a id="response-r-notice-12"></a>

### R_NOTICE_12 — 读取 · 本人 / 消息索引

适用接口：[NOTICE-12](#api-notice-12)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].actionRequired`|bool|父记录存在时必有|是否仍需原业务动作；不代表已处理；遵守本字段类型、统一上限及对应业务条件|
|`items[].category`|string|父记录存在时必有|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].readAt`|string/null|必有；允许null|本人该版实际已读记录时间；遵守本字段类型、统一上限及对应业务条件|
|`items[].route`|object|父记录存在时必有|App白名单入口及当前资源定位；遵守本字段类型、统一上限及对应业务条件|
|`items[].route.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].route.type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceId`|string|父记录存在时必有|原来源稳定ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].sourceType`|string|父记录存在时必有|引用/事件的原资源类型；遵守本字段类型、统一上限及对应业务条件|
|`items[].sourceVersion`|int|父记录存在时必有|引用的原内容版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "notification_example_1",
        "sourceType": "ANNOUNCEMENT",
        "sourceId": "announcement_example_1",
        "sourceVersion": 1,
        "category": "COURSE_NOTICE",
        "title": "联调安排",
        "createdAt": "2026-11-02T08:00:00Z",
        "readAt": null,
        "actionRequired": false,
        "route": {
          "type": "ANNOUNCEMENT",
          "id": "announcement_example_1"
        }
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-notice-13"></a>

### R_NOTICE_13 — 提交 · 本人 / 消息索引 / 清消息提示

适用接口：[NOTICE-13](#api-notice-13)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`readAt`|string/null|必有；允许null|本人该版实际已读记录时间；遵守本字段类型、统一上限及对应业务条件|
|`updatedIds`|string[]|必有|本次实际更新的索引ID；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "updatedIds": [
      "notification_example_1"
    ],
    "readAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-notice-14"></a>

### R_NOTICE_14 — 读取 · 本人 / 本人通知偏好

适用接口：[NOTICE-14](#api-notice-14)、[NOTICE-15](#api-notice-15)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`chatDefault`|string|必有|聊天ALL/MENTIONS/MUTE偏好；遵守本字段类型、统一上限及对应业务条件|
|`courseNotices`|string|必有|课程通知IN_APP/SYSTEM显示偏好；遵守本字段类型、统一上限及对应业务条件|
|`mentions`|string|必有|当前项目成员的明确提及ID；可省略默认[]，最多20个当前正式成员|
|`projectChatModes`|object[]|必有|本人逐项目聊天偏好覆盖；遵守本字段类型、统一上限及对应业务条件|
|`projectChatModes[].mode`|string|父记录存在时必有|当前业务模式；遵守本字段类型、统一上限及对应业务条件|
|`projectChatModes[].projectId`|string|父记录存在时必有|项目ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`quietHours`|object|必有|本人免打扰设置；遵守本字段类型、统一上限及对应业务条件|
|`quietHours.enabled`|bool|父记录存在时必有|此项是否启用；遵守本字段类型、统一上限及对应业务条件|
|`quietHours.end`|string|父记录存在时必有|个人免打扰结束时刻；遵守本字段类型、统一上限及对应业务条件|
|`quietHours.start`|string|父记录存在时必有|个人免打扰开始时刻；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`supportReplies`|string|必有|私人求助回复IN_APP/SYSTEM显示偏好；遵守本字段类型、统一上限及对应业务条件|
|`timezone`|string|必有|有效IANA时区；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "revision": 1,
    "timezone": "Asia/Shanghai",
    "quietHours": {
      "enabled": true,
      "start": "22:00",
      "end": "08:00"
    },
    "courseNotices": "IN_APP",
    "mentions": "IN_APP",
    "supportReplies": "IN_APP",
    "chatDefault": "MENTIONS",
    "projectChatModes": []
  }
}
```

<a id="response-r-help-01"></a>

### R_HELP_01 — 读取 · 帮助 / 通用离线帮助包

适用接口：[HELP-01](#api-help-01)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`articles`|object[]|必有|当前适用帮助内容集合；遵守本字段类型、统一上限及对应业务条件|
|`articles[].appVersionRange`|string|父记录存在时必有|帮助条目适用的精确版本或MAJOR.MINOR.*；遵守本字段类型、统一上限及对应业务条件|
|`articles[].category`|string|父记录存在时必有|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`articles[].contentVersion`|int|父记录存在时必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`articles[].entry`|object|父记录存在时必有|允许打开的白名单App页面；遵守本字段类型、统一上限及对应业务条件|
|`articles[].entry.id`|string/null|必有；允许null|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`articles[].entry.type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`articles[].failureAction`|string|父记录存在时必有|本次失败后的允许恢复动作；遵守本字段类型、统一上限及对应业务条件|
|`articles[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`articles[].maintainer`|string|父记录存在时必有|真实维护者展示名；遵守本字段类型、统一上限及对应业务条件|
|`articles[].outcomes`|string[]|父记录存在时必有|按条目的本次合法结果；遵守本字段类型、统一上限及对应业务条件|
|`articles[].prerequisites`|string[]|父记录存在时必有|当前操作实际前置条件；遵守本字段类型、统一上限及对应业务条件|
|`articles[].roles`|string[]|父记录存在时必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`articles[].scope`|string|父记录存在时必有|通知真实课程/作业范围；遵守本字段类型、统一上限及对应业务条件|
|`articles[].squareId`|string/null|必有；允许null|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`articles[].steps`|string[]|父记录存在时必有|帮助操作步骤集合；遵守本字段类型、统一上限及对应业务条件|
|`articles[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`articles[].updatedAt`|string|父记录存在时必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`version`|int|必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "version": 1,
    "updatedAt": "2026-11-02T08:00:00Z",
    "articles": [
      {
        "id": "help_example_1",
        "title": "为什么扫码后还需要入课核验",
        "category": "JOIN",
        "roles": [
          "STUDENT"
        ],
        "scope": "GENERAL",
        "squareId": null,
        "appVersionRange": "0.42.*",
        "contentVersion": 1,
        "updatedAt": "2026-11-02T08:00:00Z",
        "maintainer": "软件流程维护者",
        "prerequisites": [
          "已登录"
        ],
        "steps": [
          "扫描只能定位课程",
          "查看本人入课申请状态"
        ],
        "outcomes": [
          "资格核验通过后可进入课程"
        ],
        "failureAction": "检查申请原因或向真实受理者求助",
        "entry": {
          "type": "COURSE",
          "id": null
        }
      }
    ]
  }
}
```

<a id="response-r-help-02"></a>

### R_HELP_02 — 读取 · 帮助 / 帮助条目

适用接口：[HELP-02](#api-help-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].appVersionRange`|string|父记录存在时必有|帮助条目适用的精确版本或MAJOR.MINOR.*；遵守本字段类型、统一上限及对应业务条件|
|`items[].category`|string|父记录存在时必有|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`items[].contentVersion`|int|父记录存在时必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`items[].entry`|object|父记录存在时必有|允许打开的白名单App页面；遵守本字段类型、统一上限及对应业务条件|
|`items[].entry.id`|string/null|必有；允许null|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].entry.type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].failureAction`|string|父记录存在时必有|本次失败后的允许恢复动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].maintainer`|string|父记录存在时必有|真实维护者展示名；遵守本字段类型、统一上限及对应业务条件|
|`items[].outcomes`|string[]|父记录存在时必有|按条目的本次合法结果；遵守本字段类型、统一上限及对应业务条件|
|`items[].prerequisites`|string[]|父记录存在时必有|当前操作实际前置条件；遵守本字段类型、统一上限及对应业务条件|
|`items[].roles`|string[]|父记录存在时必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`items[].scope`|string|父记录存在时必有|通知真实课程/作业范围；遵守本字段类型、统一上限及对应业务条件|
|`items[].squareId`|string/null|必有；允许null|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].steps`|string[]|父记录存在时必有|帮助操作步骤集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].updatedAt`|string|父记录存在时必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "help_example_1",
        "title": "为什么扫码后还需要入课核验",
        "category": "JOIN",
        "roles": [
          "STUDENT"
        ],
        "scope": "GENERAL",
        "squareId": null,
        "appVersionRange": "0.42.*",
        "contentVersion": 1,
        "updatedAt": "2026-11-02T08:00:00Z",
        "maintainer": "软件流程维护者",
        "prerequisites": [
          "已登录"
        ],
        "steps": [
          "扫描只能定位课程",
          "查看本人入课申请状态"
        ],
        "outcomes": [
          "资格核验通过后可进入课程"
        ],
        "failureAction": "检查申请原因或向真实受理者求助",
        "entry": {
          "type": "COURSE",
          "id": null
        }
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-help-03"></a>

### R_HELP_03 — 读取 · 帮助 / 帮助条目

适用接口：[HELP-03](#api-help-03)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`appVersionRange`|string|必有|帮助条目适用的精确版本或MAJOR.MINOR.*；遵守本字段类型、统一上限及对应业务条件|
|`category`|string|必有|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`contentVersion`|int|必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`entry`|object|必有|允许打开的白名单App页面；遵守本字段类型、统一上限及对应业务条件|
|`entry.id`|string/null|必有；允许null|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`entry.type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`failureAction`|string|必有|本次失败后的允许恢复动作；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`maintainer`|string|必有|真实维护者展示名；遵守本字段类型、统一上限及对应业务条件|
|`outcomes`|string[]|必有|按条目的本次合法结果；遵守本字段类型、统一上限及对应业务条件|
|`prerequisites`|string[]|必有|当前操作实际前置条件；遵守本字段类型、统一上限及对应业务条件|
|`roles`|string[]|必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`scope`|string|必有|通知真实课程/作业范围；遵守本字段类型、统一上限及对应业务条件|
|`squareId`|string/null|必有；允许null|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`steps`|string[]|必有|帮助操作步骤集合；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "help_example_1",
    "title": "为什么扫码后还需要入课核验",
    "category": "JOIN",
    "roles": [
      "STUDENT"
    ],
    "scope": "GENERAL",
    "squareId": null,
    "appVersionRange": "0.42.*",
    "contentVersion": 1,
    "updatedAt": "2026-11-02T08:00:00Z",
    "maintainer": "软件流程维护者",
    "prerequisites": [
      "已登录"
    ],
    "steps": [
      "扫描只能定位课程",
      "查看本人入课申请状态"
    ],
    "outcomes": [
      "资格核验通过后可进入课程"
    ],
    "failureAction": "检查申请原因或向真实受理者求助",
    "entry": {
      "type": "COURSE",
      "id": null
    }
  }
}
```

<a id="response-r-help-04"></a>

### R_HELP_04 — 保存 · 帮助条目 / 本人 / 本人条目反馈

适用接口：[HELP-04](#api-help-04)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`articleId`|string|必有|当前结构固定的article资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`contentVersion`|int|必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`helpful`|bool|必有|本人是否认为本版条目有帮助；遵守本字段类型、统一上限及对应业务条件|
|`reasonCode`|string/null|必有；允许null|帮助反馈原因枚举或null；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "articleId": "help_example_1",
    "contentVersion": 1,
    "helpful": false,
    "reasonCode": "STEPS_OUTDATED"
  }
}
```

<a id="response-r-help-05"></a>

### R_HELP_05 — 保存 · 帮助 / 帮助条目

适用接口：[HELP-05](#api-help-05)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`appVersionRange`|string|必有|帮助条目适用的精确版本或MAJOR.MINOR.*；遵守本字段类型、统一上限及对应业务条件|
|`category`|string|必有|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`contentVersion`|int|必有|实质正文版本；正整数；对应本业务独立版本或轮次，不与资源revision混用|
|`entry`|object|必有|允许打开的白名单App页面；遵守本字段类型、统一上限及对应业务条件|
|`entry.id`|string/null|必有；允许null|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`entry.type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`failureAction`|string|必有|本次失败后的允许恢复动作；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`maintainer`|string|必有|真实维护者展示名；遵守本字段类型、统一上限及对应业务条件|
|`outcomes`|string[]|必有|按条目的本次合法结果；遵守本字段类型、统一上限及对应业务条件|
|`prerequisites`|string[]|必有|当前操作实际前置条件；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`roles`|string[]|必有|身份集合或项目岗位定义，按父结构区分；遵守本字段类型、统一上限及对应业务条件|
|`scope`|string|必有|通知真实课程/作业范围；遵守本字段类型、统一上限及对应业务条件|
|`squareId`|string/null|必有；允许null|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`steps`|string[]|必有|帮助操作步骤集合；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "help_example_1",
    "title": "为什么扫码后还需要入课核验",
    "category": "JOIN",
    "roles": [
      "STUDENT"
    ],
    "scope": "GENERAL",
    "squareId": null,
    "appVersionRange": "0.42.*",
    "contentVersion": 2,
    "updatedAt": "2026-11-02T08:00:00Z",
    "maintainer": "软件流程维护者",
    "prerequisites": [
      "已登录"
    ],
    "steps": [
      "扫描只能定位课程",
      "查看本人入课申请状态"
    ],
    "outcomes": [
      "资格核验通过后可进入课程"
    ],
    "failureAction": "检查申请原因或向真实受理者求助",
    "entry": {
      "type": "COURSE",
      "id": null
    },
    "revision": 2
  }
}
```

<a id="response-r-help-06"></a>

### R_HELP_06 — 读取 · 私人求助 / 真实接收渠道

适用接口：[HELP-06](#api-help-06)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`available`|bool|必有|当前渠道是否真实可用；遵守本字段类型、统一上限及对应业务条件|
|`category`|string|必有|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`receiverLabel`|string|必有|真实接收渠道显示名，不代表已受理；遵守本字段类型、统一上限及对应业务条件|
|`requiresSquare`|bool|必有|本类求助是否必须绑定原课程；遵守本字段类型、统一上限及对应业务条件|
|`routeId`|string|必有|服务器配置的真实接收渠道ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "available": true,
    "routeId": "support_route_example_1",
    "category": "OPERATION",
    "receiverLabel": "项目软件问题受理组",
    "requiresSquare": false
  }
}
```

<a id="response-r-help-07"></a>

### R_HELP_07 — 提交 · 本人 / 私人求助工单

适用接口：[HELP-07](#api-help-07)、[HELP-12](#api-help-12)、[HELP-13](#api-help-13)、[HELP-14](#api-help-14)、[HELP-16](#api-help-16)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assigneeId`|string/null|必有；允许null|服务端当前真实指派负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`attachmentFileIds`|string[]|必有|本次本人READY求助图片ID，最多3项且不重复；可省略默认[]，最多3个本人READY图片|
|`category`|string|必有|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`clientRequestId`|string|必有|本人环境内稳定求助/举报编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`content`|string|必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`context`|object|必有|本人明确选择的最少页面/错误/版本信息；遵守本字段类型、统一上限及对应业务条件|
|`context.appVersion`|string|父记录存在时必有|不含debug后缀的三段App版本；遵守本字段类型、统一上限及对应业务条件|
|`context.errorCode`|string|父记录存在时必有|稳定错误类别，不含完整日志；遵守本字段类型、统一上限及对应业务条件|
|`context.pageType`|string|父记录存在时必有|白名单页面类型；遵守本字段类型、统一上限及对应业务条件|
|`context.resourceId`|string/null|必有；允许null|该入口当前有权资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`requesterId`|string|必有|申请/提问者本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`resolutionNote`|string/null|必有；允许null|提问者本人解决说明，未解决可null；遵守本字段类型、统一上限及对应业务条件|
|`resolvedByRequester`|bool|必有|仅提问者明确确认解决才true；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string/null|必有；允许null|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "ticket_example_1",
    "clientRequestId": "support-client-0001",
    "requesterId": "user_example_1",
    "category": "OPERATION",
    "title": "申请入口无法加载",
    "content": "点击后显示NETWORK_ERROR。",
    "squareId": null,
    "context": {
      "pageType": "APPLICATION",
      "resourceId": null,
      "errorCode": "NETWORK_ERROR",
      "appVersion": "0.59.0"
    },
    "attachmentFileIds": [],
    "status": "SUBMITTED",
    "assigneeId": "support_receiver_example_1",
    "resolvedByRequester": false,
    "resolutionNote": null,
    "revision": 1,
    "createdAt": "2026-11-02T08:00:00Z",
    "updatedAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-help-08"></a>

### R_HELP_08 — 读取 · 本人 / 私人求助工单

适用接口：[HELP-08](#api-help-08)、[HELP-11](#api-help-11)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].assigneeId`|string/null|必有；允许null|服务端当前真实指派负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].attachmentFileIds`|string[]|父记录存在时必有|本次本人READY求助图片ID，最多3项且不重复；可省略默认[]，最多3个本人READY图片|
|`items[].category`|string|父记录存在时必有|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`items[].clientRequestId`|string|父记录存在时必有|本人环境内稳定求助/举报编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`items[].context`|object|父记录存在时必有|本人明确选择的最少页面/错误/版本信息；遵守本字段类型、统一上限及对应业务条件|
|`items[].context.appVersion`|string|父记录存在时必有|不含debug后缀的三段App版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].context.errorCode`|string|父记录存在时必有|稳定错误类别，不含完整日志；遵守本字段类型、统一上限及对应业务条件|
|`items[].context.pageType`|string|父记录存在时必有|白名单页面类型；遵守本字段类型、统一上限及对应业务条件|
|`items[].context.resourceId`|string/null|必有；允许null|该入口当前有权资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].requesterId`|string|父记录存在时必有|申请/提问者本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].resolutionNote`|string/null|必有；允许null|提问者本人解决说明，未解决可null；遵守本字段类型、统一上限及对应业务条件|
|`items[].resolvedByRequester`|bool|父记录存在时必有|仅提问者明确确认解决才true；遵守本字段类型、统一上限及对应业务条件|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].squareId`|string/null|必有；允许null|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`items[].title`|string|父记录存在时必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`items[].updatedAt`|string|父记录存在时必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "ticket_example_1",
        "clientRequestId": "support-client-0001",
        "requesterId": "user_example_1",
        "category": "OPERATION",
        "title": "申请入口无法加载",
        "content": "点击后显示NETWORK_ERROR。",
        "squareId": null,
        "context": {
          "pageType": "APPLICATION",
          "resourceId": null,
          "errorCode": "NETWORK_ERROR",
          "appVersion": "0.59.0"
        },
        "attachmentFileIds": [],
        "status": "SUBMITTED",
        "assigneeId": "support_receiver_example_1",
        "resolvedByRequester": false,
        "resolutionNote": null,
        "revision": 1,
        "createdAt": "2026-11-02T08:00:00Z",
        "updatedAt": "2026-11-02T08:00:00Z"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-help-09"></a>

### R_HELP_09 — 读取 · 私人求助工单

适用接口：[HELP-09](#api-help-09)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assigneeId`|string/null|必有；允许null|服务端当前真实指派负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`attachmentFileIds`|string[]|必有|本次本人READY求助图片ID，最多3项且不重复；可省略默认[]，最多3个本人READY图片|
|`category`|string|必有|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`clientRequestId`|string|必有|本人环境内稳定求助/举报编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`content`|string|必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`context`|object|必有|本人明确选择的最少页面/错误/版本信息；遵守本字段类型、统一上限及对应业务条件|
|`context.appVersion`|string|父记录存在时必有|不含debug后缀的三段App版本；遵守本字段类型、统一上限及对应业务条件|
|`context.errorCode`|string|父记录存在时必有|稳定错误类别，不含完整日志；遵守本字段类型、统一上限及对应业务条件|
|`context.pageType`|string|父记录存在时必有|白名单页面类型；遵守本字段类型、统一上限及对应业务条件|
|`context.resourceId`|string/null|必有；允许null|该入口当前有权资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`history`|object[]|必有|按当前权限返回的处理/版本历史；遵守本字段类型、统一上限及对应业务条件|
|`history[].action`|string|父记录存在时必有|本人明确执行的动作；允许值：按本条业务规则列明的动作|
|`history[].actorId`|string|父记录存在时必有|本事件实际操作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`history[].at`|string|父记录存在时必有|本事件服务端时间；遵守本字段类型、统一上限及对应业务条件|
|`history[].reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`replies`|object|必有|本资源有权回复分页集合；遵守本字段类型、统一上限及对应业务条件|
|`replies.hasMore`|bool|父记录存在时必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`replies.items`|object[]|父记录存在时必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`replies.items[].attachmentFileIds`|string[]|父记录存在时必有|本次本人READY求助图片ID，最多3项且不重复；可省略默认[]，最多3个本人READY图片|
|`replies.items[].authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`replies.items[].content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`replies.items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`replies.items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`replies.items[].ticketId`|string|父记录存在时必有|当前结构固定的ticket资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`replies.nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`replies.total`|int|父记录存在时必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|
|`requesterId`|string|必有|申请/提问者本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`resolutionNote`|string/null|必有；允许null|提问者本人解决说明，未解决可null；遵守本字段类型、统一上限及对应业务条件|
|`resolvedByRequester`|bool|必有|仅提问者明确确认解决才true；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string/null|必有；允许null|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`transferTargets`|object[]|必有；空候选返回[]|本单当前授权转交渠道，最多20且routeId唯一；只向非提问者的当前有权处理者给合法候选；写入时再次核验|
|`transferTargets[].category`|string|父项存在时必有|OPERATION或COURSE_REQUIREMENT；只向非提问者的当前有权处理者给合法候选；写入时再次核验|
|`transferTargets[].receiverLabel`|string|父项存在时必有|真实渠道显示名称，1～160；只向非提问者的当前有权处理者给合法候选；写入时再次核验|
|`transferTargets[].routeId`|string|父项存在时必有|真实可接收路由ID；只向非提问者的当前有权处理者给合法候选；写入时再次核验|
|`transferTargets[].squareId`|string/null|父项存在时必有|必须与原单课程范围相同；null只允许OPERATION；只向非提问者的当前有权处理者给合法候选；写入时再次核验|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "ticket_example_1",
    "clientRequestId": "support-client-0001",
    "requesterId": "user_example_1",
    "category": "OPERATION",
    "title": "申请入口无法加载",
    "content": "点击后显示NETWORK_ERROR。",
    "squareId": null,
    "context": {
      "pageType": "APPLICATION",
      "resourceId": null,
      "errorCode": "NETWORK_ERROR",
      "appVersion": "0.59.0"
    },
    "attachmentFileIds": [],
    "status": "SUBMITTED",
    "assigneeId": "support_receiver_example_1",
    "resolvedByRequester": false,
    "resolutionNote": null,
    "revision": 1,
    "createdAt": "2026-11-02T08:00:00Z",
    "updatedAt": "2026-11-02T08:00:00Z",
    "replies": {
      "items": [
        {
          "id": "support_reply_example_1",
          "ticketId": "ticket_example_1",
          "authorId": "support_receiver_example_1",
          "content": "请保留输入并重新加载。",
          "attachmentFileIds": [],
          "createdAt": "2026-11-02T08:00:00Z"
        }
      ],
      "nextCursor": null,
      "hasMore": false,
      "total": 1
    },
    "history": [],
    "transferTargets": []
  }
}
```

<a id="response-r-help-10"></a>

### R_HELP_10 — 提交 · 私人求助工单 / 回复

适用接口：[HELP-10](#api-help-10)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`reply`|object|必有|本次实际创建的回复；遵守本字段类型、统一上限及对应业务条件|
|`reply.attachmentFileIds`|string[]|父记录存在时必有|本次本人READY求助图片ID，最多3项且不重复；可省略默认[]，最多3个本人READY图片|
|`reply.authorId`|string|父记录存在时必有|会话确定的原作者ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reply.content`|string|父记录存在时必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`reply.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`reply.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reply.ticketId`|string|父记录存在时必有|当前结构固定的ticket资源ID，须核验实际归属；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`ticketRevision`|int|必有|本次工单实际版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`ticketStatus`|string|必有|本次工单实际状态；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "reply": {
      "id": "support_reply_example_2",
      "ticketId": "ticket_example_1",
      "authorId": "user_example_1",
      "content": "重新加载后仍失败。",
      "attachmentFileIds": [],
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "ticketRevision": 2,
    "ticketStatus": "IN_PROGRESS"
  }
}
```

<a id="response-r-help-15"></a>

### R_HELP_15 — 提交 · 私人求助工单 / 授权转交

适用接口：[HELP-15](#api-help-15)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`assigneeId`|string/null|必有；允许null|服务端当前真实指派负责人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`attachmentFileIds`|string[]|必有|本次本人READY求助图片ID，最多3项且不重复；可省略默认[]，最多3个本人READY图片|
|`category`|string|必有|所属模块类别；遵守本字段类型、统一上限及对应业务条件|
|`clientRequestId`|string|必有|本人环境内稳定求助/举报编号，重试不换；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`content`|string|必有|文字正文，撤回投影可null；遵守本字段类型、统一上限及对应业务条件|
|`context`|object|必有|本人明确选择的最少页面/错误/版本信息；遵守本字段类型、统一上限及对应业务条件|
|`context.appVersion`|string|父记录存在时必有|不含debug后缀的三段App版本；遵守本字段类型、统一上限及对应业务条件|
|`context.errorCode`|string|父记录存在时必有|稳定错误类别，不含完整日志；遵守本字段类型、统一上限及对应业务条件|
|`context.pageType`|string|父记录存在时必有|白名单页面类型；遵守本字段类型、统一上限及对应业务条件|
|`context.resourceId`|string/null|必有；允许null|该入口当前有权资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`requesterId`|string|必有|申请/提问者本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`resolutionNote`|string/null|必有；允许null|提问者本人解决说明，未解决可null；遵守本字段类型、统一上限及对应业务条件|
|`resolvedByRequester`|bool|必有|仅提问者明确确认解决才true；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`squareId`|string/null|必有；允许null|所属课程广场ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`title`|string|必有|当前资源标题；遵守本字段类型、统一上限及对应业务条件|
|`transferTargetRouteId`|string|必有|成功时精确回显本次所选targetRouteId；不得用较高revision或新负责人替代本字段核对|
|`updatedAt`|string|必有|服务器最近有效更新时间；UTC RFC3339时间点；是否允许null见类型和所属业务|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "ticket_example_1",
    "clientRequestId": "support-client-0001",
    "requesterId": "user_example_1",
    "category": "OPERATION",
    "title": "申请入口无法加载",
    "content": "点击后显示NETWORK_ERROR。",
    "squareId": null,
    "context": {
      "pageType": "APPLICATION",
      "resourceId": null,
      "errorCode": "NETWORK_ERROR",
      "appVersion": "0.59.0"
    },
    "attachmentFileIds": [],
    "status": "SUBMITTED",
    "assigneeId": "user_example_teacher",
    "resolvedByRequester": false,
    "resolutionNote": null,
    "revision": 3,
    "createdAt": "2026-11-02T08:00:00Z",
    "updatedAt": "2026-11-02T08:00:00Z",
    "transferTargetRouteId": "course_support_route_example_1"
  }
}
```

<a id="response-r-reporting-01"></a>

### R_REPORTING_01 — 提交 · 本人 / 片段举报

适用接口：[REPORTING-01](#api-reporting-01)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`receiverLabel`|string|必有|真实接收渠道显示名，不代表已受理；遵守本字段类型、统一上限及对应业务条件|
|`reporterId`|string|必有|举报者本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`source`|object|必有|候选/引用来源类别；遵守本字段类型、统一上限及对应业务条件|
|`source.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`source.type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`source.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "content_report_example_1",
    "reporterId": "user_example_1",
    "source": {
      "type": "CHAT_MESSAGE",
      "id": "message_example_1",
      "version": 1
    },
    "reason": "包含不适合公开的个人信息",
    "status": "SUBMITTED",
    "revision": 1,
    "receiverLabel": "项目指定内容受理者",
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-reporting-02"></a>

### R_REPORTING_02 — 读取 · 本人 / 片段举报

适用接口：[REPORTING-02](#api-reporting-02)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`hasMore`|bool|必有|是否还有后续页；遵守本字段类型、统一上限及对应业务条件|
|`items`|object[]|必有|有序条目集合；遵守本字段类型、统一上限及对应业务条件|
|`items[].createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`items[].id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].reason`|string|父记录存在时必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`items[].receiverLabel`|string|父记录存在时必有|真实接收渠道显示名，不代表已受理；遵守本字段类型、统一上限及对应业务条件|
|`items[].reporterId`|string|父记录存在时必有|举报者本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`items[].source`|object|父记录存在时必有|候选/引用来源类别；遵守本字段类型、统一上限及对应业务条件|
|`items[].source.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`items[].source.type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`items[].source.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`items[].status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`nextCursor`|string/null|必有；允许null|绑定当前范围的下一页游标；结束null；遵守本字段类型、统一上限及对应业务条件|
|`total`|int|必有|完整数量；岗位子项中为该岗位容量；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "items": [
      {
        "id": "content_report_example_1",
        "reporterId": "user_example_1",
        "source": {
          "type": "CHAT_MESSAGE",
          "id": "message_example_1",
          "version": 1
        },
        "reason": "包含不适合公开的个人信息",
        "status": "SUBMITTED",
        "revision": 1,
        "receiverLabel": "项目指定内容受理者",
        "createdAt": "2026-11-02T08:00:00Z"
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "total": 1
  }
}
```

<a id="response-r-reporting-03"></a>

### R_REPORTING_03 — 读取 · 片段举报

适用接口：[REPORTING-03](#api-reporting-03)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`handlingNote`|string/null|必有；允许null|实际受理处理说明；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`receiverLabel`|string|必有|真实接收渠道显示名，不代表已受理；遵守本字段类型、统一上限及对应业务条件|
|`reporterId`|string|必有|举报者本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`selectedExcerpt`|string|必有|本人明确选定的受限原文片段；遵守本字段类型、统一上限及对应业务条件|
|`source`|object|必有|候选/引用来源类别；遵守本字段类型、统一上限及对应业务条件|
|`source.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`source.type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`source.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "content_report_example_1",
    "reporterId": "user_example_1",
    "source": {
      "type": "CHAT_MESSAGE",
      "id": "message_example_1",
      "version": 1
    },
    "reason": "包含不适合公开的个人信息",
    "status": "SUBMITTED",
    "revision": 1,
    "receiverLabel": "项目指定内容受理者",
    "createdAt": "2026-11-02T08:00:00Z",
    "selectedExcerpt": "用户主动选中的必要片段",
    "handlingNote": null
  }
}
```

<a id="response-r-reporting-04"></a>

### R_REPORTING_04 — 提交 · 片段举报 / 记录处理事实

适用接口：[REPORTING-04](#api-reporting-04)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`handlingNote`|string/null|必有；允许null|实际受理处理说明；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`reason`|string|必有|本次决定的事实理由；拒绝/撤销/终止/关闭/教学调整需1～500非空，其余可省略为空|
|`receiverLabel`|string|必有|真实接收渠道显示名，不代表已受理；遵守本字段类型、统一上限及对应业务条件|
|`reporterId`|string|必有|举报者本人ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`source`|object|必有|候选/引用来源类别；遵守本字段类型、统一上限及对应业务条件|
|`source.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`source.type`|string|父记录存在时必有|当前来源/路由种类；遵守本字段类型、统一上限及对应业务条件|
|`source.version`|int|父记录存在时必有|独立业务版本；遵守本字段类型、统一上限及对应业务条件|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "content_report_example_1",
    "reporterId": "user_example_1",
    "source": {
      "type": "CHAT_MESSAGE",
      "id": "message_example_1",
      "version": 1
    },
    "reason": "包含不适合公开的个人信息",
    "status": "HANDLED",
    "revision": 2,
    "receiverLabel": "项目指定内容受理者",
    "createdAt": "2026-11-02T08:00:00Z",
    "handlingNote": "已联系相关本人处理展示内容"
  }
}
```

<a id="response-r-file-01"></a>

### R_FILE_01 — 提交 · 云文件 / 上传意图

适用接口：[FILE-01](#api-file-01)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`file`|object|必有|云文件元数据；遵守本字段类型、统一上限及对应业务条件|
|`file.contentType`|string|父记录存在时必有|声明且须与实际签名一致的媒体类型；image/jpeg,image/png,image/webp,application/pdf,text/plain,text/markdown,application/vnd.openxmlformats-officedocument.wordprocessingml.document；用途同时限制|
|`file.createdAt`|string|父记录存在时必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`file.createdBy`|string|父记录存在时必有|会话确定的创建人ID；遵守本字段类型、统一上限及对应业务条件|
|`file.fileName`|string|父记录存在时必有|文件名，无路径或控制字符；遵守本字段类型、统一上限及对应业务条件|
|`file.id`|string|父记录存在时必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`file.purpose`|string|父记录存在时必有|文件的固定业务用途；遵守本字段类型、统一上限及对应业务条件|
|`file.revision`|int|父记录存在时必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`file.sizeBytes`|int|父记录存在时必有|实际/声明文件字节数；1～8388608整数；服务端实际核对|
|`file.status`|string|父记录存在时必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|
|`upload`|object|必有|短期存储上传授权；遵守本字段类型、统一上限及对应业务条件|
|`upload.expiresAt`|string|必有|当前码/token/授权的到期时间；具体可空规则见模块；非空未来UTC时间点；上传授权10分钟|
|`upload.headers`|object|父记录存在时必有|存储授权所必需的白名单Header；遵守本字段类型、统一上限及对应业务条件|
|`upload.headers.Content-Type`|string|父记录存在时必有|必须与原上传意图一致的媒体类型；遵守本字段类型、统一上限及对应业务条件|
|`upload.method`|string|父记录存在时必有|本授权的HTTP方法，上传固定PUT；遵守本字段类型、统一上限及对应业务条件|
|`upload.url`|string|父记录存在时必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|
|`uploadToken`|string|必有|与上传意图绑定的完成校验凭据；服务端签发的不透明值，禁止日志泄露；业务有效期见第3/4/9节|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "file": {
      "id": "file_example_1",
      "purpose": "SUPPORT_ATTACHMENT",
      "fileName": "screenshot-test.png",
      "contentType": "image/png",
      "sizeBytes": 1024,
      "status": "UPLOADING",
      "revision": 1,
      "createdBy": "user_example_1",
      "createdAt": "2026-11-02T08:00:00Z"
    },
    "upload": {
      "method": "PUT",
      "url": "https://example.invalid/upload/test",
      "headers": {
        "Content-Type": "image/png"
      },
      "expiresAt": "2026-11-02T08:10:00Z"
    },
    "uploadToken": "file-upload-test"
  }
}
```

<a id="response-r-file-02"></a>

### R_FILE_02 — 提交 · 云文件 / 上传完成校验

适用接口：[FILE-02](#api-file-02)、[FILE-03](#api-file-03)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`contentType`|string|必有|声明且须与实际签名一致的媒体类型；image/jpeg,image/png,image/webp,application/pdf,text/plain,text/markdown,application/vnd.openxmlformats-officedocument.wordprocessingml.document；用途同时限制|
|`createdAt`|string|必有|服务器创建时间；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`createdBy`|string|必有|会话确定的创建人ID；遵守本字段类型、统一上限及对应业务条件|
|`fileName`|string|必有|文件名，无路径或控制字符；遵守本字段类型、统一上限及对应业务条件|
|`id`|string|必有|服务器生成的稳定资源ID；ID 1～120，[A-Za-z0-9_-]；空值按本字段分支|
|`purpose`|string|必有|文件的固定业务用途；遵守本字段类型、统一上限及对应业务条件|
|`revision`|int|必有|当前资源并发版本；整数1～999999999；仅明确允许的首次私有记录用0|
|`sizeBytes`|int|必有|实际/声明文件字节数；1～8388608整数；服务端实际核对|
|`status`|string|必有|该资源状态，见第5节及所属模块动作；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "file_example_1",
    "purpose": "SUPPORT_ATTACHMENT",
    "fileName": "screenshot-test.png",
    "contentType": "image/png",
    "sizeBytes": 1024,
    "status": "READY",
    "revision": 2,
    "createdBy": "user_example_1",
    "createdAt": "2026-11-02T08:00:00Z"
  }
}
```

<a id="response-r-file-04"></a>

### R_FILE_04 — 提交 · 云文件 / 短期下载授权

适用接口：[FILE-04](#api-file-04)。

|字段|类型|义务/空值/默认|含义与校验|
|---|---|---|---|
|`expiresAt`|string/null|必有；允许null|当前码/token/授权的到期时间；具体可空规则见模块；UTC RFC3339时间点；是否允许null见类型和所属业务|
|`url`|string|必有|材料LINK或短期传输地址，按所属分支区分；遵守本字段类型、统一上限及对应业务条件|

示例投影：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "url": "https://example.invalid/download/test",
    "expiresAt": "2026-11-02T08:05:00Z"
  }
}
```

<a id="implementation"></a>

## 10. 客户端接入与设计缺口

### 10.1 当前证据边界

|范围|当前可据代码确认的情况|尚不能据此声称|
|---|---|---|
|P58 图标与 P57 风格|原生本机能力，图标不联网，不新增路径、账号字段、权限或运行依赖|图标选择已同步到账号或其他手机|
|账号、课程、组队、任务、人员、报告和教学|存在客户端路由、请求与本机预览/规则实现；各模块附当前源码定位|真实服务器已部署、名单已核验、多人并发事务已正确执行|
|P51 转交|原 HELP-09 候选与 HELP-15 回显已纳入本版完整结构|教师/助理角色自动拥有任意私人工单处理权|
|P52–53 学期与定位|严格校验完整字典、稳定termId及学校学期；明确选期后匹配/创建|演示学期和节次是真实学校校历或课表|
|P54–55 求助图片/回复|FILE-01～04与HELP-07/09/10已有客户端字节传输、READY核对、私有缓存与原请求恢复|真实对象存储HTTPS、关联事务或所有业务文件用途已经联调|
|P56 通知范围调整|复用NOTICE-03/05，新范围预览、正文版及接收快照核对|真实名单、跨设备回执、令牌与幂等事务已由服务器验证|
|聊天与系统通知|前台同步、站内索引、当前本机通知显示及设置入口|后台推送必达、所有厂商保活或完整多设备聊天已读证明|

客户端测试数量记录在 Android 交付说明，不把它当成这281项服务的上线验收。本文的接口目录、结构和示例做静态一致性检查；没有访问未配置的服务器，也没有发送真实用户业务。

### 10.2 明确保留的缺口

1. **课程入课证明 FILE**：COURSE-18/21描述了FILE引用，但现有FILE-01的MATERIAL_PROOF必须绑定人员`caseId`，两者不是同一范围。当前入课流程只能用LINK；不允许拿squareId、ticketId或虚构caseId上传。服务器与客户端需先共同确定独立用途/范围及受限下载契约，再启用该分支。本版不编造已兼容的新purpose或路径。
2. **其他云文件用途**：本版完整定义既有DELIVERY_ARTIFACT/MATERIAL_PROOF/WORK_ARTIFACT目标契约；当前App的完整上传/下载流程以求助图片为范围，本机附件库不冒充云端文件。未接入的入口应明确未开放。
3. **通知置顶**：已有产品约束限制有效置顶数量，当前HTTP目录没有通知pin专用操作、P58客户端也没有该入口；不能借修订正文偷偷写pinned。需要后续确定服务扩展后实施，不套用留言主题BOARD-13。
4. **合并表单课程查询**：当前MergeScreen创建表单曾以无条件`GET /squares`读取候选，而目标课程目录契约要求schoolId/termId。当前正式服务器未联调，此调用点需在接入时补齐明确范围；本文不放宽服务器学校学期鉴权，也不在文档任务中修改App。
5. **跨课程待处理汇总**：现有TEACH-01/02完整返回工作台，App可在授权范围筛选；没有新增全站或跨学校汇总端点。若后续做跨课程处理，须逐课程保持授权和来源，不能扩大A权限。
6. **真实环境**：学校身份/选课名单、账号恢复渠道、对象存储、教师权属、多人同时操作、实物扫码摇动、系统通知与后台推送仍需真实联调。硬件、模拟器、Preview和HTTP夹具证据分开登记。

### 10.3 重编时明确的结构约定

以下是本版为后端实施而消除歧义的要求，不是声称现有服务器已经迁移：

- LINK引用为`kind/url/title`，FILE引用为`kind/fileId/title`；不能从一个LINK示例推导FILE也必传url。
- 空集合、空子记录仍有完整字段结构；有数据时按第9节展开。普通人员投票响应只提供数量和本人myBallot，不公开全量选民/他人选票。移出表决result未结算为null，结算为PASSED/NOT_PASSED，不用空对象冒充字符串结果。
- 课程合并按当前客户端采用WAIT_FACT状态；每侧返回eligibleCount/agree/disagree/abstain/unvoted/passed和requiredYes，不能换成名称相似的统计字段导致客户端显示0。
- 人员交接返回方案版本、实际items及接任状态，组长接替表现为LEADER条目；nextLeaderId是保存输入。补资料的核对记录固定responseVersion，保留于回应review及事项history，和任务逐标准验收结构分开。GOV-19是当前有权处理者撤回待补资料请求，不是学生擦除既有回应。
- 正式分工的写入请求用plan/assessedPlan包装；Agreement响应按当前读取代码在顶层返回ownerId/dueAt/units/reviewerPolicy及kind，不额外套plan。普通与正式任务创建的必填项分别定义，不能要求普通创建也传assessedPlan。
- 教学账本gaps是待核对说明字符串列表，报告gaps是带code/section/resourceId/message的结构化缺口，两者不因同名而混用。

服务器实施这些结构时，应按第11节逐项核验客户端读取和异常路径。某个Preview分支允许缺字段，不是正式API可以缺字段的依据；同样不能用设计示例覆盖当前已确认的真实教学事实。

<a id="acceptance"></a>

## 11. 部署、数据保留与联调验收

### 11.1 部署前置配置

|配置|提供/维护者|最低要求|
|---|---|---|
|API_BASE_URL与环境标识|服务器部署人员|实际HTTPS证书，测试/正式数据与凭据隔离；不把虚构示例域名投入使用|
|学校、学期、节次和时区|学校/课程负责人提供，服务器导入|完整可信字典、合法纯日期与学校IANA时区，稳定ID及版本|
|核验用户与选课事实|学校/课程负责人核验，受控管理人员导入|同校已核验学号唯一，实际教师和选课关系可追溯；不能由App自报覆盖|
|账号开通与恢复渠道|项目负责人指定|实际核验人员，私下交付临时口令，撤销旧会话；密码/token不记日志|
|软件帮助及受理路由|软件负责人配置|有效HELP_CONTENT_WRITE/SUPPORT_HANDLE及受理范围，无接收者明确不可用|
|课程求助与人员权限|当前负责教师|明确助理能力、期限、本组回避、可转交的同范围真实渠道|
|内容举报接收者|项目负责人配置|CONTENT_REPORT_HANDLE仅必要证据范围，不取得全群管理权|
|私有文件存储|服务器部署人员|隔离存储、短期签名、真实字节校验、访问重验、8MiB限额、孤立文件清理；不指定云厂商|
|会话及签名凭据|服务器部署人员|安全随机来源、安全摘要和受控备份，禁止客户端或文档内嵌服务秘密|
|教学规则|当前教师经业务接口确认|真实w、说明、理由及依据，缺规则时不能正式发布成绩|
|清理、审计与恢复任务|服务器部署人员|定时任务、受限审计、备份恢复演练及清理窗口；有未决事项时保留相应证据|

部署语言、数据库和存储产品可由团队选择；JSON、权限、状态、唯一性和事务结果必须保持。App无需知道数据库表名、对象存储主密钥或后台管理口令。

### 11.2 服务端必须维护的唯一约束

|约束|必须防止的错误|
|---|---|
|学校内已核验学生标识|同一实际学生被重复开户或凭自报学号冒名|
|学校/学期/规范课程/教师/完整时段集合|同一课程并发重复创建或把另一学期误作精确匹配|
|学生+作业的有效正式团队关系|同时接受两个邀请后进入两组|
|项目/岗位有效成员与预留总量|邀请、转组和本人接受同时抢占超额席位|
|任务当前待验收提交与提交sequence|覆盖旧提交或同时验收/撤回两次生效|
|任务分项/本人/有效验收的贡献确认|代签、重复计算或把上传者算独占贡献|
|本人/项目/clientMessageId；本人/clientRequestId|未知网络结果重发产生重复消息、工单或举报|
|通知/正文版/收件人回执|旧知悉顶替新版本或重复计分母|
|本人/主题关注、本人/项目阅读位置、本人/帮助正文版反馈|重复记录或阅读位置倒退|
|任务有效骰子会话、会话/轮次/投骰者|多个并行有效抽签、重复投骰重生点数|
|来源操作与实际成绩快照/复核关联|预览后来源变化仍发布，或未发布新成绩就结案|

数据库操作登记、业务写入和幂等结果提交必须处在同一个可靠事务边界。只在执行结束后缓存200不足以防止并发和进程中断重复。

### 11.3 保留与清理设计

课程关系、教学成绩、成员、验收及报告记录在学期结束后保留365天；相关复核/人员事项未结时暂停相应证据清理至关闭后30天。聊天、留言、附件及修订在项目归档后保留180天，未决事项所需片段独立受限保留。私人求助/举报关闭后180天；过期会话30天后清理；幂等结果7天；无引用的未完成上传24小时。实际部署须实施清理任务并记录范围和操作者。

清理前保留7天可撤销清理窗口；备份访问同样受控。撤回/撤销/关闭都是业务状态，不等于立即物理删除。正式历史快照不因当前引用失效而损坏；离组后停止云端正文读取，但不能承诺收回既有下载或截图。

### 11.4 首条真实联调流程

1. AUTH-04登录、AUTH-08核对本人；COURSE-04/03获取实际学期和节次，COURSE-10匹配后才COURSE-09创建/进入课程。
2. 按可信事实走COURSE-16→17，或COURSE-18→20/21→22；验证持码不直接入课、自审被拒和无人审核不假成功。
3. COURSE-07/11选择或代建本次作业；TEAM-20建组，另一学生TEAM-21申请；TEAM-15通过后，邀请本人TEAM-17最终接受，两个设备刷新核对同一关系。
4. TASK-05创建普通任务；本人TASK-06认领（startNow=false），TASK-07开始；DELIVERY-19提交固定材料与标准。
5. R以DELIVERY-12逐标准验收；用两个并发请求验证验收/DELIVERY-14撤回只有一个结果，旧提交快照保留。
6. 明确核对正式教学规则、约定/贡献、账本及来源后，GRADE-15→16、GRADE-18、GRADE-23、GRADE-13→14发布；学生GRADE-10读取仅本人成绩。

### 11.5 必测场景矩阵

以下为待执行的真实联调验收要求，不是本次文档检查的通过记录。

|编号|场景|应核对的实际结果|
|---|---|---|
|IT01|两端同时接受同作业两个邀请|只生效一个正式团队，另一端读到冲突与真实原关系，席位预留一致|
|IT02|刷新/重复同键/异正文同键/请求处理中|会话期限不延长、原操作仅一次、异意图冲突、不生成第二记录|
|IT03|创建课程超时、重新匹配、另一个学期同名课|原唯一对象可查，不自动重复建课，学校学期不串范围|
|IT04|课程码过期/换码/教师转交，学生自审|旧码无效，本人资格重新核验，无权不能批准|
|IT05|岗位互换/多人方案中途离组或席位变化|方案保留但停止生效，不改任务和成绩|
|IT06|依赖循环/开始后加前置/认领后稍后开始|前置被正确拒绝，原截止不变，不出现隐式责任暂停|
|IT07|正式分项并行提交、部分通过和本人份额确认|逐条结果固定，已通过项不被覆盖，上传者不独占贡献|
|IT08|验收与撤回并发、撤回后新交|同版本先有效者生效，原时间/标准保留，恢复本人草稿，新交追加序号|
|IT09|人员退出、表决通过但接任未接受|正式关系保留，教师不代学生接任，不直接移人|
|IT10|事项转交、补资料返回、授权到期及回避|接续前原人负责，原材料版本保留，失权不再读新材料|
|IT11|广场合并两侧人数不等、资格变化、原作业成绩|分别严格过半、资格变化停止，旧成绩与作业保持分离|
|IT12|规则/来源在预览后变化、分母0、成绩复核拟修订|拒绝过期发布，不默认成绩，实际新发布后才修订结案|
|IT13|报告缺项、退出收尾再进、空项目归档|新closingCycle，旧FINAL不顶替，空原因真实且不造100%|
|IT14|骰子并发同轮/重试/未收齐/任务被他人认领|每人每轮一个服务端点数，隐藏他人点数，来源失效，不自动选次高人|
|IT15|聊天断线补页、旧消息撤回、游标过期、多端读位置|连续补齐、变更同步、占位不泄露、合法max不越缺口，无自动补发|
|IT16|通知双身份、范围变化、格式修订、旧知悉|明确身份，新快照独立回执，排版不重置，清提示不代业务完成|
|IT17|免打扰跨夜、系统拒权、催阅频率|站内保留、不绕设置、不假称设备送达，不多发催阅|
|IT18|求助无路由/同范围转交失权/未知回复|无虚假单号，转交失败原人负责，原body/key保持，403不冒充成功|
|IT19|文件假完成/错类型/越权/过期授权|真实字节校验，未READY不可引用，当前访问重验，存储请求无业务Bearer|
|IT20|私有资料、撤回修订、互评不足、教师评价展示授权|最少投影，匿名阈值不足不公开，正式成绩和原始互评不泄露|

每次登记接口编号、客户端/服务器版本、账号角色和资源范围、脱敏请求响应、持久化读取、并发及失败恢复结果。单个200或一张成功截图不能代替完整结果核对。

<a id="sources"></a>

## 12. 文档依据与维护

本版依据当前产品设计与 Android 源码重新组织，模块源码索引列于第8节。协议通道见 `RemoteBackend.java`、`DataGateway.java`；权威产品依据为[现行产品设计源](../.document-sources/product-design-v13-caifeng/产品设计.md)及[产品设计图解](咕噜派_产品设计图解_蔡烽.md)。P51–56的实际客户端接入通过对应规则和页面核对，P57/P58属于本机视觉与图标能力。

现有协议命名用于兼容核对；本文的完整说明、接口定义和响应字典均在本文件内，无需拼接原主文档与增补记录。对于第10节明确的衔接缺口，尚不能作为已可联调功能实施；不要用虚构参数补齐。

维护源在 `.document-sources/api-p58/`：`guide.md`、`policies.json`、`contract.json`、`closing.md` 与 `build.py`。生成器只读取这套独立维护源，不读取或修改原有API文档。修改字段、权限或状态后同步这里的模型及规则，运行生成器和校验脚本，保留真实联调记录，不能只改生成文件。

本文没有承诺后台、名单、对象存储、多人事务或推送已完成。当前仍需实施/联调的范围包括课程FILE核验、其他云文件用途、跨课程待处理汇总、通知置顶及上述真实环境能力。
