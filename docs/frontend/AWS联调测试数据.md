# AWS Android 联调测试数据

2026-11-04 已将源服务器最新快照复制到 `124.220.167.12`，账号密码及业务 ID 保留。新增服务器有21个账号、4个广场、3个作业、2个项目、3个任务、53条聊天、4张求助工单和4个 READY 文件。以下是最初生成数据的说明；复制时的最新数据和访问地址见 [新服务器部署记录](../../deploy/124.220.167.12部署记录.md)。两台服务器后续数据独立运行。

生成时间：2026-11-03 21:34:16 +08:00。服务版本 `0.1.0-P16`，环境 `prod`。这些数据已实际写入 AWS，不是待导入样例；均为虚构测试数据。

API：`https://18.166.212.12/api/v1`。Retrofit baseUrl：`https://18.166.212.12/api/v1/`。接入约定见 [Android 联调文档](联调接入.md)。

按用户要求，测试账号已简化为 `student1` 等名称，密码与用户名相同。完整对照表见 [AWS测试账号与密码](private/AWS测试账号与密码.md)（本机文件，已排除 Git）。本文和账号表交付联调人员使用。

## 1. 数据总览

| 数据 | 数量 |
|---|---|
| 学校 | 1 |
| 学期 | 1 |
| 真实教学班 | 2 |
| 可信选课记录 | 18 |
| 测试账号 | 21 |
| 课程广场 | 4 |
| 作业 | 1 |
| 项目 | 2 |
| 正式项目成员关系 | 4 |
| 任务 | 3 |
| 正式约定 | 1 |
| 聊天消息 | 51 |
| 留言主题 | 1 |
| 公告 | 1 |
| 求助工单 | 1 |
| READY 文件 | 1 |
| 广场合并提案 | 2 |
| 帮助文章 | 1 |

## 2. 测试账号与使用场景

全部账号为 VERIFIED、未停用，均已通过 HTTPS 登录和 `/me` 实测。密码与用户名相同，例如 `student1 / student1`、`teacher1 / teacher1`。

| 登录名 | 用户 ID | 显示名 | 角色 | 用途 |
|---|---|---|---|---|
| teacher1 | user_demo_full_teacher_01 | 演示教师甲 | TEACHER | 移动应用教师，查看作业、公告和合并 |
| teacher2 | user_demo_full_teacher_02 | 演示教师乙 | TEACHER | 数据库课程教师 |
| assistant1 | user_demo_full_assistant_01 | 演示助理甲 | STUDENT, CLASS_ADMIN | 移动应用班助理；未持有帮助维护能力 |
| assistant2 | user_demo_full_assistant_02 | 演示助理乙 | STUDENT, CLASS_ADMIN | 数据库班助理 |
| student1 | user_demo_full_student_01 | 演示学生01 | STUDENT | 快速组组长：任务、聊天、文件、求助、复盘 |
| student2 | user_demo_full_student_02 | 演示学生02 | STUDENT | 岗位组组长：开发岗、正式分工 |
| student3 | user_demo_full_student_03 | 演示学生03 | STUDENT | 岗位组测试岗成员，分工份额待确认 |
| student4 | user_demo_full_student_04 | 演示学生04 | STUDENT | 快速组成员，已参与独立验收 |
| student5 | user_demo_full_student_05 | 演示学生05 | STUDENT | 移动应用乙广场普通学生 |
| student6 | user_demo_full_student_06 | 演示学生06 | STUDENT | 移动应用乙广场普通学生 |
| student7 | user_demo_full_student_07 | 演示学生07 | STUDENT | 移动应用乙广场普通学生 |
| student8 | user_demo_full_student_08 | 演示学生08 | STUDENT | 有移动应用选课资格，未入广场；过期邀请 |
| student9 | user_demo_full_student_09 | 演示学生09 | STUDENT | 数据库课程普通学生 |
| student10 | user_demo_full_student_10 | 演示学生10 | STUDENT | 数据库课程普通学生 |
| student11 | user_demo_full_student_11 | 演示学生11 | STUDENT | 数据库课程普通学生 |
| student12 | user_demo_full_student_12 | 演示学生12 | STUDENT | 数据库课程普通学生 |
| student13 | user_demo_full_student_13 | 演示学生13 | STUDENT | 数据库课程普通学生 |
| student14 | user_demo_full_student_14 | 演示学生14 | STUDENT | 数据库课程普通学生 |
| student15 | user_demo_full_student_15 | 演示学生15 | STUDENT | 数据库课程普通学生 |
| student16 | user_demo_full_student_16 | 演示学生16 | STUDENT | 数据库课程普通学生 |
| operator1 | user_demo_full_operator | 演示受理员 | STUDENT | 平台求助受理员、帮助内容维护 |

优先交给前端使用：`student1`、`student2`、`student3`、`student4`、`teacher1`、`operator1`。普通学生 `student9` 可用于验证跨课程项目访问被拒绝。

运营账号 `/me.capabilities` 实际包含 `HELP_CONTENT_WRITE`，受理工作台读取已验证；学生/教师的项目或课程操作权限仍需以具体资源响应为准，不能仅看全局 capabilities。

## 3. 学校、课表与广场

| 对象 | ID | 内容 |
|---|---|---|
| 演示大学 | school_demo_full | Asia/Shanghai |
| 演示学期 | term_demo_full | 2026-09-01 至 2027-01-15 |

| 教学班 ID | 课程名 ID | 教师登录名 | 上课安排 |
|---|---|---|---|
| vc_demo_full_mobile | course_name_demo_full_mobile | teacher1 | 周三 08:00–09:40，第1–16周，每周 |
| vc_demo_full_db | course_name_demo_full_db | teacher2 | 周五 10:00–11:40，第1–16周，每周 |

学生 01–08 和助理甲在移动应用可信名单，学生 09–16 和助理乙在数据库可信名单。可信选课资格与已经加入广场是两个状态。

| 广场号 | 名称 | 广场 ID | 学生/助理成员 |
|---|---|---|---|
| DF-M-A | 移动应用开发实践·历史甲 | square_demo_full_mobile_a | 学生01–04、助理甲 |
| DF-M-B | 移动应用开发实践·历史乙 | square_demo_full_mobile_b | 学生05–07、助理甲 |
| DF-DB | 数据库系统原理 | square_demo_full_db | 学生09–12、助理乙 |
| DF-DB-B | 数据库系统原理·历史乙 | square_demo_full_db_b | 学生13–16、助理乙 |

广场查询返回的课程展示名是 `fullCourseName`，广场号是 `squareNo`。学生01能在广场目录读到4个广场，只有移动应用甲广场的 `myMembershipStatus=ACTIVE`；目录可见不等于已入课或拥有其内部资源权限。

## 4. 作业、项目与任务

| 作业 | ID | 所在广场 | 确认状态 | 截止时间（北京时间） |
|---|---|---|---|---|
| 演示作业 | bc5c9829-c3f6-4380-882d-84076e79f1f4 | square_demo_full_mobile_a | CONFIRMED | 2027-02-01 21:34:17 +08:00 |

种子作业为教师确认作业，要求完成可演示课程项目，人数范围2–6。作业截止按生成时刻加90天设置，可能晚于演示学期结束；这是既有种子的演示安排。

| 项目 | 项目 ID | 模式 | 状态 | 组长 | 容量/当前成员 | revision快照 |
|---|---|---|---|---|---|---|
| 演示快速组 | 209da08b-6923-47a1-9af6-518265fbe84a | QUICK | CLOSING | student1 | 4/2 | 8 |
| 演示岗位组 | c76de7f0-5439-4cef-a414-d248a7e4cd83 | DETAILED | ACTIVE | student2 | 3/2 | 5 |

快速组成员为学生01、04，处于 CLOSING、招募已关闭；岗位组成员为学生02、03，处于 ACTIVE、招募开启。需要测试招募或创建新任务时优先使用岗位组，涉及收尾限制时使用快速组。

| 岗位 | 岗位 ID | 容量 | 当前成员 |
|---|---|---|---|
| 开发 | c85ee9ae-3408-4906-b8b5-9b95a355e68b | 1 | student2 |
| 测试 | c92e95e7-7ad2-4f34-8516-7f52b4b677d5 | 2 | student3 |

| 任务 | 任务 ID | 模式 | 状态 | 所属项目 | 负责人 | revision快照 |
|---|---|---|---|---|---|---|
| 待认领任务 | 91e8b821-a017-4205-88e1-fc2945b7366e | ORDINARY | TODO | 快速组 | — | 1 |
| 正式分工 | 6e830be7-d9ad-460b-89e0-56bf02f7a8d5 | ASSESSED | TODO | 岗位组 | student2 | 1 |
| 前置任务 | a8691029-710c-4048-b5bc-2883f021cc87 | ORDINARY | DONE | 快速组 | student1 | 4 |

“待认领任务”通过 BEFORE_START 依赖“前置任务”，但前置任务已走正式提交和独立验收成为 DONE，所以这份初始数据中的依赖条件已满足；不要把它当作当前仍被依赖阻塞的失败案例。

正式任务约定 ID：`4018e753-0ec3-463a-b30f-e869344f76f9`，版本 1，状态 `PENDING`，验收策略 `SELF_CHECK`。

| 交付单元 ID | 标题 | 总点数 | 学生02份额 | 学生03份额 | 初始签署状态 |
|---|---|---|---|---|---|
| 14e6fb7f-ce53-47a6-962c-455f525b91cc | 登录页面 | 10.00 | 6.00 | 4.00 | 两人均未确认 |

任务、约定、项目版本会随联调变化。写操作前重新 GET 最新 revision，并按接口携带幂等键。本文 ID 是本次服务器快照，用于定位数据，不应写死到客户端。

## 5. 协作、公告、文件与求助

| 类型 | 名称 | 资源 ID | 状态/说明 |
|---|---|---|---|
| 留言主题 | 演示留言 | fbfa2fe9-d9fa-4534-adfa-43ce88e3544f | OPEN |
| 教师公告 | 演示公告 | 35f5f3da-0e58-4884-a54e-aca3501cdfa1 | ACTIVE |
| 求助工单 | 演示求助 | cb192789-438f-4ac3-b005-81aafec4840b | SUBMITTED |
| 文件 | demo.png | 36c1ae52-7b17-44c4-a3b5-01589564fe01 | READY |
| 个人复盘 | 学生01复盘 | dd903e23-c975-41dc-a7c8-3f60053c3539 | DRAFT |
| 帮助文章 | 如何提交任务 | help_general_1 | published=true |

快速组有51条聊天，正文为“演示历史消息 1”到“演示历史消息 51”，作者为学生01。实测 `limit=20` 返回20条、`hasMore=true`、`latestSequence=51`，可测试历史翻页和变更游标。不要假设一次返回全部消息。

公告“演示公告”内容为“请在现场完成知悉。”，但实际 `requiresAcknowledgement=false`，不要求必读回执；测试必读提醒需再通过教师接口创建相应公告。留言“演示留言”初始为 OPEN、0条回复。

文件 `demo.png` 是70字节 PNG，属于学生01，purpose 为 SUPPORT_ATTACHMENT。已通过业务授权接口取得短期 URL 并实际下载验证 PNG 签名；文件尚未附到工单。其他账号不应因知道 fileId 就自动获得下载权。

求助工单“演示求助”由学生01创建，当前为 SUBMITTED，分配给 `operator1`。使用该账号可读取受理工作台。

| 求助路由 ID | 名称 | 接收人 | 可用性说明 |
|---|---|---|---|
| route_course | 课程与教学求助 | — | 已有配置，接收人为空；未验证完整受理链路 |
| route_grades | 成绩与评价求助 | — | 已有配置，接收人为空；未验证完整受理链路 |
| route_support | 平台使用求助 | operator1 | 平台使用路由已验证 |

帮助文章适配版本范围 `1.0.*`，入口类型 TASK，入口 ID 为 null。实际 App 版本或入口枚举不匹配时，应按帮助接口过滤/降级，不将文章配置当作已验证的页面跳转。

## 6. 合并和边界测试

| 场景 | 合并 ID | 源广场 → 目标广场 | 初始状态 | 投票截止（北京时间） |
|---|---|---|---|---|
| 演示投票期 | d2855a65-20d4-4496-a501-81793c143b14 | square_demo_full_mobile_a → square_demo_full_mobile_b | VOTING | 2026-11-06 21:34:18 +08:00 |
| 演示即将到期 | dc44871e-21ab-43b1-9943-0ec3a248cbad | square_demo_full_db → square_demo_full_db_b | VOTING | 2026-11-03 22:34:18 +08:00 |

过期邀请 ID：`43ef9080-f72c-4022-8dbf-201339514cda`，接收人 `student8`，过期时间 2026-11-03 20:34:18 +08:00。数据库快照中原始状态仍为 PENDING，但授权/回应须按过期时间判断，不表示仍可接受。

数据库班合并提案是生成后约1小时到期的动态场景，晚些测试时状态可能已经变化。种子还准备了过期游标，但未对其全部业务查询绑定做实测；不要把一个游标硬塞给任意列表接口。

实测 `student9` 读取快速组项目返回 `403 / FORBIDDEN`。岗位组开发岗已满（1/1），测试岗尚有1个空位；助理甲未持有 HELP_CONTENT_WRITE，可用于相关权限边界测试。

## 7. 建议的 Android 联调顺序

| 账号 | 操作 | 期望 |
|---|---|---|
| student1 | 登录 → /me → 学期 → 广场目录 → 我的项目 | 能看到快速组及 CLOSING 状态 |
| student1 | 快速组任务、聊天历史、留言和公告 | 3种资源读取正常，聊天能翻页 |
| student2 / student3 | 岗位组与正式任务详情 | 约定 PENDING，6.00/4.00 份额待确认 |
| student1 / student4 | 已完成任务及独立验收记录 | 前置任务 DONE，避免重复提交已完成任务 |
| student1 | 文件授权及下载、另建上传意图 | 已有 PNG 可下载；新上传走完整三步 |
| student1 / operator1 | 我的求助 → 受理工作台 | 两侧看到同一张演示求助 |
| student9 | 读取快速组项目 | 403，正确展示无权限 |
| teacher1 / teacher2 | 课程公告、合并列表与详情 | 验证教师视角及动态到期状态 |

路径示例（以下请求均需 Bearer，除登录/刷新）：

```http
GET /api/v1/me
GET /api/v1/schools/school_demo_full/terms
GET /api/v1/squares?schoolId=school_demo_full&termId=term_demo_full
GET /api/v1/me/projects
GET /api/v1/projects/209da08b-6923-47a1-9af6-518265fbe84a/tasks
GET /api/v1/projects/209da08b-6923-47a1-9af6-518265fbe84a/chat-messages?limit=20
GET /api/v1/projects/209da08b-6923-47a1-9af6-518265fbe84a/board-topics
GET /api/v1/me/support-tickets
GET /api/v1/support-workspace/tickets
```

## 8. 验证结果与数据边界

已完成21个账号逐一登录、读取 `/me` 并注销；记录34项业务读取/刷新/越权检查，结果符合预期，另验证了已有 PNG 授权下载。学生01刷新成功，跨课程项目访问被拒绝，服务健康检查应用和数据库均 UP。

已有一个任务交付/独立验收示例；未生成已发布成绩、完整报告归档、互评、AI 或骰子等全部业务状态。没有在本次操作中跑 Android 真机 UI；数据可用于原生客户端继续联调。

本次使用项目现有 `demo-seed --scenario=full` CLI，通过业务服务创建正常场景，并由种子构造到期边界。执行前备份保存在服务器 `/opt/gulupai/backups/before-demo-seed-20261103.dump`，仅是本次操作前的数据库快照，不代表已建立定时备份或完成恢复演练。

测试账号与业务数据持久保存在服务器，后续请求会修改状态。本次不会自动重置；如需重建，先核对数据及凭据文件，不要直接清库或反复执行种子覆盖正在使用的测试账号。

## 9. 账号简化记录

2026-11-03 按用户要求将登录名简化：学生 `student1`–`student16`，教师 `teacher1`–`teacher2`，助理 `assistant1`–`assistant2`，受理员 `operator1`；密码均与各自用户名相同。内部用户 ID、角色、学校、项目和任务关联保持不变。旧登录名停用，原会话已撤销，客户端需重新登录。修改前数据库备份位于服务器 `/opt/gulupai/backups/before-simple-accounts-20261103.dump`。默认 demo-seed 仍使用原始命名，后续不要重复执行该种子来重置当前环境。
