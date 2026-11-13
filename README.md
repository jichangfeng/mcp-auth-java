# mcp-auth-java

基于 Spring AI 与 Spring Authorization Server 实现的带 OAuth2 授权保护的 MCP（Model Context Protocol）服务器演示项目。

项目整体模仿 [python-sdk 的 simple-auth 示例](https://github.com/modelcontextprotocol/python-sdk)，将 Python 版本的功能用 Java 复刻：一个独立的授权服务器（Authorization Server）+ 一个受 OAuth2 保护的 MCP 服务器（Resource Server），支持 MCP 客户端（如 MCP Inspector、Qoder、WorkBuddy）通过动态客户端注册（DCR）+ PKCE 授权码流程完成授权后访问 MCP 工具。

> 声明：这是一个**演示/学习项目**，仅实现了本地 HTTP 明文环境下的最小功能，不适用于生产环境（生产需要 HTTPS、独立用户体系、持久化数据库、DCR 防护等，见文末）。

## 架构

```
┌──────────────┐   ①发现受保护资源元数据    ┌─────────────────────┐
│              │ ◄─────────────────────── │                     │
│              │   ②获取 AS 元数据/DCR      │   auth-server :9000 │
│   MCP 客户端  │ ◄───────────────────────► │  Spring Authorization│
│  (Qoder 等)  │                           │  Server + 登录页     │
│              │   ③浏览器登录 + PKCE 授权   │─────────────────────│
│              │ ◄───────────────────────► │   H2 客户端库        │
│              │   ④code 换 access_token    │   签名密钥文件        │
│              │                           └─────────────────────┘
│              │   ⑤携带 Bearer token 访问   ┌─────────────────────┐
│              │ ────────────────────────► │  mcp-server :8001   │
└──────────────┘        ⑥验签(JWKS 公钥)     │  Spring AI MCP      │
                                           │  Streamable HTTP    │
                                           └─────────────────────┘
```

- **auth-server（端口 9000）**：Spring Authorization Server，提供登录页、DCR（`/oauth2/register`）、授权（`/oauth2/authorize`）、令牌（`/oauth2/token`）、JWKS（`/oauth2/jwks`）等端点。登录账号固定为 `user / dev-123456`。
- **mcp-server（端口 8001）**：Spring AI MCP Server（Streamable HTTP + SYNC），提供 `greet`、`add`、`current-time` 三个演示工具。作为 OAuth2 资源服务器，验签用的 JWKS 公钥从 AS 自动拉取，自身不保存任何密钥。

## 环境要求

- JDK 21+（开发时使用 Liberica JDK 21）
- Maven 3.9+

注意：如果本机通过 scoop 装了多个 JDK，确认 `JAVA_HOME` 指向 JDK 21 再执行 Maven 命令，否则可能回退到旧版本 JDK 导致编译失败（报 `release version 21 not supported` 之类）。

## 构建与启动

```powershell
# 1. 构建
mvn clean install -DskipTests

# 2. 启动授权服务器（必须先启动）
java -jar auth-server\target\auth-server-0.0.1-SNAPSHOT.jar

# 3. 启动 MCP 服务器（必须晚于 auth-server）
java -jar mcp-server\target\mcp-server-0.0.1-SNAPSHOT.jar
```

**⚠️ 启动顺序必须是先 auth-server 后 mcp-server。** mcp-server 使用的 mcp-security starter 在启动时会立即解析 `issuer-uri` 指向的 AS 元数据，AS 未就绪会直接启动失败（`Unable to resolve the Configuration with the provided Issuer`）。

也可以在 IDE 中通过 `mvn spring-boot:run` 分别启动两个模块。

## 配置 MCP 客户端（以 Qoder 为例）

在 Qoder 的 MCP 配置（`.qoder/mcp.json`）中添加：

```json
{
  "mcpServers": {
    "mcp-demo-server": {
      "url": "http://localhost:8001/mcp",
      "type": "streamable-http"
    }
  }
}
```

首次连接时 Qoder 会自动完成：读取 RS 的受保护资源元数据 → 发现 AS → 动态注册客户端（DCR）→ 跳转浏览器登录（`user / dev-123456`）→ PKCE 授权 → 拿到 access_token 访问 MCP。MCP Inspector 等其他支持 OAuth 的客户端流程相同。

想看清每一步的具体 HTTP 报文（或自己实现 MCP 客户端时对照调试），见 **[docs/authorization-flow.md](docs/authorization-flow.md)**——完整的动态客户端注册与授权六步流程，含请求/响应示例、PKCE、流程图与要点备忘。

## 设计要点

- **DCR 客户端持久化**：客户端注册信息存放在 H2 文件库（`data/mcp-auth-clients`）。客户端（如 Qoder）注册一次后会缓存 client_id 反复使用，若不持久化，AS 重启后会出现"未知客户端"授权报错。
- **DCR 客户端 token 时效**：SAS 对 DCR 客户端默认只发 5 分钟的 access_token，本项目在 `ClientRepositoryConfiguration` 中统一调整为 **8 小时**（refresh_token TTL 30 天）。
- **公开客户端不发放 refresh_token**：Qoder 等客户端通过 DCR 注册为公开客户端（`token_endpoint_auth_method=none`），Spring Security 对这类客户端**有意**不发 refresh_token（见 `OAuth2RefreshTokenGenerator`），符合 RFC 6749 安全建议。因此 token 过期后需要重新走一次浏览器授权。
- **签名密钥持久化**：SAS 默认每次启动随机生成 RSA 签名密钥对（仅内存），AS 重启后旧 token 全部验签失败、客户端被迫重新授权。本项目将密钥持久化到 `data/as-signing-key.json`，重启后密钥不变。
- **工作目录敏感**：H2 数据库与签名密钥的路径 `./data/...` 相对**进程启动时的工作目录**，请固定以一种方式启动（统一在仓库根目录用 `java -jar`，或在模块目录用 `mvn spring-boot:run`），混用会导致数据分散在两处。
- **RS 验签无需密钥**：mcp-server 只配置了 `issuer-uri`，验签公钥由 Spring Security 自动从 `http://localhost:9000/oauth2/jwks` 拉取。

## 日志与数据文件

按上述步骤在**仓库根目录**启动时，日志和数据均落在仓库根目录下：

| 路径 | 说明 |
| --- | --- |
| `logs/auth-server.log` | 授权服务器日志（单文件 10MB、保留 7 天） |
| `logs/mcp-server.log` | MCP 服务器日志（同上） |
| `data/mcp-auth-clients.mv.db` | DCR 客户端 H2 库（可删除，删后客户端需重新授权） |
| `data/as-signing-key.json` | AS 签名密钥（**勿删**，删了旧 token 全部失效） |

这些路径在配置里写作 `./data/...`、`./logs/...`，相对**进程启动时的工作目录**——按 README 的方式从仓库根目录启动就落在仓库根；若改为在模块目录下 `mvn spring-boot:run` 启动，则会落在对应模块目录下，注意不要混用两种启动方式。

## 参考资料

- 基于 Spring AI MCP 相关文档开发：
  - [MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
  - [MCP Security](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html)
  - [MCP Annotations](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-overview.html)
- 功能原型模仿 [python-sdk 的 simple-auth 示例](https://github.com/modelcontextprotocol/python-sdk)（`examples/servers/simple-auth`）
- Spring Security Authorization Server（SAS）参考文档

## 距离生产还差什么

HTTPS/反向代理与 issuer 地址管理、真实的用户体系（接入企业 SSO）、H2 换 PostgreSQL 等生产级数据库、DCR 端点防护与客户端审核、token 撤销与审计日志、分布式部署下的会话与密钥管理。
