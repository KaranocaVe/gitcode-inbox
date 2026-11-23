# 容器部署

GHCR 镜像为 `ghcr.io/karanocave/gitcode-inbox`，包含 Linux amd64 和 arm64。推送到 `main` 后，GitHub Actions 先运行 Maven 测试和演示容器冒烟测试，再发布 `latest` 和对应的 `sha-<commit>` 标签。容器以 UID 10001 运行，应用监听 8080，健康检查路径为 `/actuator/health`，启用访问前缀时会自动使用带前缀的路径。

## 单机部署

1. 在 [GitCode OAuth 设置](https://gitcode.com/setting/oauth) 建立应用。回调 URL 使用 `INBOX_PUBLIC_URL`、可选访问前缀与 `/oauth/callback` 拼接。启用前缀后，示例回调为 `https://inbox.example.com/a8K2/7mQp/oauth/callback`。
2. 用 `openssl rand -base64 32` 生成加密密钥。在部署目录放置权限为 0600 的 `.env`。

   ```dotenv
   INBOX_MODE=production
   INBOX_PUBLIC_URL=https://inbox.example.com
   # 可选。用私有随机值替换示例，末尾省略斜杠
   # INBOX_ACCESS_PREFIX=/a8K2/7mQp
   INBOX_TOKEN_KEY=填入32字节Base64密钥
   INBOX_ADMIN_USERS=alice,bob
   GITCODE_CLIENT_ID=填入GitCode应用ID
   GITCODE_CLIENT_SECRET=填入GitCode应用密钥
   ```

   可用以下命令生成两段各四位的随机前缀。将结果保存在部署配置中。

   ```sh
   python3 -c 'import secrets,string; a=string.ascii_letters+string.digits; print("/"+"/".join("".join(secrets.choice(a) for _ in range(4)) for _ in range(2)))'
   ```

3. 将 HTTPS 反向代理指到宿主机 `127.0.0.1:8080`。代理覆盖转发头中的 `X-Forwarded-Proto` 为 `https`，并设置正确的原始 Host 和客户端地址。用 `INBOX_TRUSTED_PROXIES` 配置 Tomcat 实际看到的代理 IP 正则。Compose 的桥接网络可能让应用看到 Docker 网关地址。默认信任回环地址。代理应保留请求的完整路径，并对访问日志里的私有前缀脱敏。
4. 在部署机器用有 GHCR 私有包读取权限的凭据登录，然后拉取和启动。

   ```sh
   docker compose pull
   docker compose up -d --no-build
   docker compose ps
   ```

启用前缀后，浏览器打开 `https://inbox.example.com/a8K2/7mQp/`，脚本调用 `https://inbox.example.com/a8K2/7mQp/api/v1/...`。轮换前缀时同步更新 GitCode 回调 URL、客户端请求地址与部署配置，再重建服务容器。

`inbox-data` 卷保存数据库、在线设置和加密后的 Web 会话。备份使用停止后的卷或 SQLite 一致性备份，并单独保管 `INBOX_TOKEN_KEY`。每个数据库供一个服务实例使用。 `INBOX_DRY_RUN=true` 可将已读请求全局切换为预演。

本地体验可用 `INBOX_MODE=demo docker compose up --build`。生产更新可在 `.env` 中用 `INBOX_IMAGE` 固定 GHCR 的提交标签或镜像摘要。
