# MCP 接入

在 GitCode 收件箱的 Web 设置中创建个人 API 令牌。在 MCP 客户端添加一个 **Streamable HTTP** 服务，URL 为收件箱地址加 `/mcp`。启用两段式前缀时，在 `/mcp` 前保留这两段。每次连接和调用都发送 `Authorization: Bearer <令牌>`。

下面是使用通用 MCP 客户端配置形式的示例。按客户端的配置格式填写 URL 和请求头。

```json
{
  "mcpServers": {
    "gitcode-inbox": {
      "type": "streamable-http",
      "url": "https://inbox.example.com/a8K2/7mQp/mcp",
      "headers": {
        "Authorization": "Bearer <从设置页创建的令牌>"
      }
    }
  }
}
```

MCP 和 Web 使用同一账户的未读快照、上游凭据和请求配额。可以在 Web 设置中撤销 MCP 令牌，下一次请求立即失效。管理员在创建令牌时可勾选「允许 MCP 管理设置和查看指标」。实例只向此类令牌公开管理工具，并在每次调用时检查账户是否仍是管理员。之前创建的令牌自动沿用普通权限。

## 收件箱工具

| 工具 | 参数与行为 |
| --- | --- |
| `inbox_status` | 查询连接、同步状态和未读总数。 |
| `inbox_list_groups` | 按 `kind`、`repo` 和 `q` 筛选分组，支持分页。 |
| `inbox_list_messages` | 按 `group_id` 展开单个 Issue 或 PR 的未读通知。 |
| `inbox_list_unassigned` | 分页读取未归组通知，例如私信和邀请。 |
| `inbox_sync` | `mode` 可选 `incremental` 或 `full`，默认增量。 |
| `inbox_mark_read` | 传入一个或多个 `group_ids`。默认预演，明确传 `dry_run: false` 才提交。服务全局预演模式优先。 |

分页参数为 `limit`、`offset` 和可选 `version`。将第一页返回的版本带入下一页，遇到 `snapshot_changed` 时从第一页重读。批量已读失败时，检查结构化响应中的已确认、待提交和结果未知的 ID，再决定如何继续。

## 管理工具

| 工具 | 参数与行为 |
| --- | --- |
| `inbox_get_metrics` | 查询最近 `hours` 小时的同步、限流和已读指标，默认 24 小时。 |
| `inbox_get_settings` | 读取当前轮询、完整校准、防抖和流控配置。 |
| `inbox_update_settings` | 原子更新给定配置项。字段和范围见仓库主文档的 REST 设置表。 |

## HTTP 协议

传输采用无状态 Streamable HTTP。客户端向同一个 URL 发送 JSON-RPC POST 请求，通过 `initialize` 协商版本，再发送 `notifications/initialized` 和后续请求。服务支持 `2025-11-25` 和 `2025-06-18`。请求结果同时包含结构化数据和文本内容。工具业务错误通过 `isError` 和结构化错误返回。服务级限流使用 HTTP 429 与 `Retry-After`。

本地 `INBOX_MODE=demo` 可在设置页面签发演示令牌，再通过 HTTP 测试。生产要求 HTTPS。来自浏览器的 `Origin` 必须匹配部署站点地址。
