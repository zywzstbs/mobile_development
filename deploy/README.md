# 后端 HTTPS 部署

当前服务器是 `124.220.167.12`。2026-11-07 已部署后端修复版 `p71fix-20261107T073331Z`，数据库 V031，维护约 58 秒，21 个原账号及旧文件兼容验收通过，详见 [本次部署验收](2026-11-07-P71修复部署验收.md)。学生注册和教师申请开放；首位线上超管 admin 已按用户指令初始化并验证。P71 管理写入联合协议和 A05 安全重置按后续事项办理。上一版 [P62更新及审核操作](2026-11-04-P62更新验收.md) 与 [初次复制记录](124.220.167.12部署记录.md) 保留为历史，下述 AWS 内容也按历史阅读。

2026-11-03 已部署至 `18.166.212.12`：API 基础地址 `https://18.166.212.12/api/v1`，健康检查 `https://18.166.212.12/health`。本地 58 项单元/MVC 测试通过；公网 HTTPS 健康检查返回应用/数据库 UP、环境 prod；HTTP 返回 301 跳转 HTTPS；未认证 `/api/v1/me` 返回 401。28 条数据库迁移全部成功，`RECOVERED_EVIDENCE` 存量为 0。证书模拟续期及 Caddy 重载通过，续期定时任务已启用。后续已验证21个测试账号登录及主要业务读取，尚未执行 Android 真机 UI 联调。

本次 JAR SHA-256：`7ec780725d806e373c3f51a56212116201bb637dd3be597208f48c14a729eb04`。

服务目录：`/opt/gulupai`。应用与 PostgreSQL 只在 Compose 网络内开放，Caddy 对外监听 TCP 80/443。80 端口用于证书校验，其余 HTTP 请求跳转 HTTPS。数据库、上传文件和证书分别持久化。

服务器的 `deploy/.env` 保存独立生成的数据库密码和 AES 密钥（权限 600），不要覆盖或提交。`API_DOMAIN` 为公网 IP，`APP_FILES_PUBLIC_BASE_URL` 为 `https://公网IP`。`compose.ip.yml` 将运行环境设置为 `prod`，沿用 demo profile 的小内存参数。

服务器中的 `compose.lock.yml` 固定 PostgreSQL/Caddy 镜像 digest，`images.lock.env` 固定 Certbot digest，Dockerfile 的基础镜像也已固定。部署包为本地通过测试后构建的 `target/gulupai-server-0.1.0-P16.jar`。

## 常用操作

在服务器执行：

```bash
cd /opt/gulupai/deploy
docker compose -f compose.yml -f compose.ip.yml -f compose.lock.yml ps
docker compose -f compose.yml -f compose.ip.yml -f compose.lock.yml logs --tail 100 app
docker compose -f compose.yml -f compose.ip.yml -f compose.lock.yml up -d --build
```

更新时先在本地重新构建并测试 JAR，上传到 `/opt/gulupai/target/` 后运行最后一条命令。不要用 `down -v`，它会删除数据库和上传文件卷。

## 证书续期

使用 Let's Encrypt 的短期 IP 证书，有效期约 6 天。`gulupai-cert-renew.timer` 每小时触发一次检查，Certbot 判断是否需要续期，通过 HTTP webroot 校验，无需停止代理；成功后重新加载 Caddy。

```bash
systemctl list-timers gulupai-cert-renew.timer
journalctl -u gulupai-cert-renew.service --since yesterday
cd /opt/gulupai/deploy
./renew-ip-certificate.sh --dry-run
```

初始化时先停止代理，用 Certbot standalone 模式在 80 端口申请证书，参数为 `certonly --standalone --preferred-profile shortlived --ip-address 公网IP`。生成证书后才能启动 `Caddyfile.ip`。后续通过脚本的 webroot 模式续期。确保 AWS 入站规则持续允许公网访问 TCP 80/443。

续期脚本依赖同目录的 `images.lock.env`，内容为 `CERTBOT_IMAGE=certbot/certbot@sha256:实际digest`。定时任务安装位置为 `/etc/systemd/system/`，脚本执行权限为 700。

证书和客户端地址绑定当前 IP；更换 IP 时需要重新签发并更新配置。后续使用域名时可改回基础 `Caddyfile`，更新文件公开地址并由 Caddy 管理域名证书。

2026-11-03 已按用户要求通过 CLI 导入测试数据：21个账号、2个教学班、4个广场、2个项目及配套业务场景。详见 [测试数据说明](../docs/frontend/AWS联调测试数据.md)。凭据保存在服务器 `/opt/gulupai/private/demo-credentials.txt` 和本机被 Git 排除的私有 Markdown 中，均为600权限。未迁移旧局域网口令或本地数据库。

Android 接入见 [联调文档](../docs/frontend/联调接入.md)。2026-11-03 只读检查发现数据库运行账号权限、镜像补丁及备份方面的待整改项，详见 [服务器安全检查](../docs/backend/2026-11-03-AWS服务器安全检查.md)。

测试账号后续已按用户要求简化为 `student1`–`student16`、`teacher1`–`teacher2`、`assistant1`–`assistant2`、`operator1`，密码与用户名相同。账号重命名保留内部 ID 和业务关系；不要重新执行默认命名的 demo-seed 来覆盖现有环境。
