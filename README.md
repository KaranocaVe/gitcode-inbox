# GitCode 收件箱

一个可部署的 GitCode 未读收件箱。服务基于 Java 21、Spring Boot 3.5 和 SQLite，前端按 Apple.Design 3.0 的文字层级、对齐和数据比较原则设计。

- 按通知实际指向的仓库、Issue 或 PR 编号聚合。PR 的 `/pull/` 与 `/merge_requests/` 共用分组，详情保留讨论锚点。
- 支持按分组批量已读和预演。写入前重新读取未读集合，按每批 200 条提交。中断后，响应区分已确认、提交失败、结果未知和待提交的消息。
- 后台增量轮询，支持完整校准，保留 SQLite 快照。页面按快照版本翻页。
- 管理端浏览分时指标和同步时延，在线调整轮询间隔、完整校准间隔、同步防抖和双向流控。设置实时生效，重启后保留。
- GitCode OAuth 登录，按用户隔离数据。单账号部署支持 GitCode 密码登录。自动化通过个人 API 密钥访问收件箱，密钥可以在线撤销。

## 启动演示

```sh
INBOX_MODE=demo mvn spring-boot:run
```

打开 [本地收件箱](http://127.0.0.1:8080)。演示账号可访问管理页，写入作用于内置示例。用独立的 `INBOX_DB` 路径运行演示与生产。

## 生产认证

先在 GitCode OAuth 应用设置中注册应用，授予文档所示的 `all_user` 用户信息权限，并登记 `https://你的域名/oauth/callback`。将应用 ID、密钥、站点地址及 32 字节 Base64 加密密钥交给容器，参数见 [容器部署](docs/deployment.md)。 [GitCode OAuth 应用设置](https://gitcode.com/setting/oauth) · [GitCode OAuth 文档](https://docs.gitcode.com/docs/apis/oauth/)

GitCode 公开的通知 API 以仓库为范围。账号级收件箱通过站点的 Web 通知接口访问。用户先完成 OAuth 登录，再在收件箱连接同一身份的 GitCode Web 会话。连接窗口提供取得本站会话令牌的步骤。服务按 OAuth 身份隔离数据，加密存储 Web access/refresh token，刷新后验证 GitCode Web 用户 ID。仅提供 access token 时，过期后需要重新连接。 [GitCode 仓库通知 API](https://docs.gitcode.com/docs/apis/get-api-v-5-repos-owner-repo-notifications/)

## 私有访问前缀

可选设置 `INBOX_ACCESS_PREFIX=/a8K2/7mQp`。两段各支持 4–32 位区分大小写的字母或数字。启用后从 `/a8K2/7mQp/` 进入，Web、REST、OAuth 和健康检查都使用同一前缀。缺少前缀或前缀错误时返回 `404`。通过前缀后，仍需浏览器会话或个人 API 密钥。

## MCP

同一个容器提供 MCP Streamable HTTP 入口 `/mcp`。配置了访问前缀时，连接地址为 `https://你的域名/<第一段>/<第二段>/mcp`。在 Web 设置中创建个人 API 令牌，并让客户端通过 Bearer 请求头发送。MCP 提供收件箱状态、分组与消息列表、同步和批量已读。管理员可签发包含运行指标和在线设置权限的 MCP 令牌。参见 [MCP 接入与工具说明](docs/mcp.md)。

## REST API

启用访问前缀时，在下表的每条路径前附加 `INBOX_ACCESS_PREFIX`。使用网页签发的个人密钥，设置 `Authorization: Bearer <密钥>`。网页会话的写入请求使用登录后返回的 `X-CSRF-Token`。管理员设置和指标要求管理员网页会话。

| 路径 | 方法 | 用途 |
| --- | --- | --- |
| `/api/v1/status` | GET | 未读总数、版本、连接和同步状态 |
| `/api/v1/groups` | GET | 聚合列表。可按 `kind`、`repo`、`q` 过滤 |
| `/api/v1/groups/{owner}/{repo}/{kind}/{number}/messages` | GET | 组内未读消息，`kind` 为 `issue` 或 `pr` |
| `/api/v1/unassigned` | GET | 私信和邀请等未归组通知 |
| `/api/v1/sync` | POST | 同步。传 `{"mode":"full"}` 请求完整校准 |
| `/api/v1/groups/read-state` | PUT | 传 `{"group_ids":["nebula/runtime/pr/418"],"read":true,"dry_run":true}` 查看预演 |
| `/api/v1/admin/settings` | GET / PUT | 读取与替换运行设置 |
| `/api/v1/admin/metrics?hours=24` | GET | 按小时记录的同步、上游流量、流控、批量已读及成功同步时延 |

列表支持 `limit`、`offset` 与可选 `version`。各页传入相同版本，收到 `409 snapshot_changed` 后从第一页读取。限流时返回 `429` 和 `Retry-After`。部分已读失败时，检查返回的 `result`、`confirmed_ids`、`uncertain_ids` 和 `remaining_ids`，然后重新读取快照。

| 设置 | 默认 | 范围 |
| --- | --- | --- |
| `poll_seconds` | 45 秒 | `0` 停用，或 15–3600 秒 |
| `full_interval_seconds` | 900 秒 | 60–86400 秒 |
| `sync_debounce_seconds` | 5 秒 | 1–300 秒 |
| `upstream_requests_per_minute` | 180 | 10–600 次，实例共用 |
| `api_requests_per_minute` | 120 | 10–1200 次，按账号计算 |

增量使用最近消息边界和上游总量判定是否继续翻页。旧消息中等量的已读与恢复未读会由下一次完整校准发现。GitCode 返回 `429` 后服务遵守该次 `Retry-After`，无该字段时暂停 60 秒。指标保留 31 天。时延分位数按直方图上界近似，当前未读数按查看者账户计算，其余运行指标按实例汇总。

```sh
mvn -B verify
```
