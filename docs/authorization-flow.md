# MCP 动态客户端注册与授权完整流程

> 以本项目为例，走通一次完整的 MCP OAuth2 授权流程。
> 目标资源：`http://localhost:8001/mcp`
> 授权服务器：`http://localhost:9000`
> 涉及规范：RFC 9728（PRM）、RFC 8414（AS Metadata）、RFC 7591（动态注册）、RFC 7636（PKCE）、RFC 6749（OAuth 2.0）

正常情况下 MCP Inspector、Qoder、WorkBuddy 等客户端会**自动**完成以下全部步骤；这里手动展开是为了看清每一步发生了什么，也方便你自己实现 MCP 客户端时对照调试。

---

## 第一步：访问 MCP 资源，获取 401 挑战

```http
POST http://localhost:8001/mcp
```

响应：

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer resource_metadata=http://localhost:8001/.well-known/oauth-protected-resource/mcp
```

> 关键：`resource_metadata` 在 **`WWW-Authenticate` 响应头**里，给出下一步要取的 PRM URL。（RFC 9728 规范中该值应为带引号的字符串，Spring Security 实际返回时不带引号）

---

## 第二步：按 401 指示获取受保护资源元数据（PRM，RFC 9728）

```http
GET http://localhost:8001/.well-known/oauth-protected-resource/mcp
```

响应：

```json
{
  "resource": "http://localhost:8001/mcp",
  "authorization_servers": ["http://localhost:9000"],
  "resource_name": "Spring MCP Resource Server",
  "bearer_methods_supported": ["header"],
  "tls_client_certificate_bound_access_tokens": true
}
```

> 取 `authorization_servers[0]` 作为 issuer，进入第三步。

---

## 第三步：获取授权服务器元数据（RFC 8414）

```http
GET http://localhost:9000/.well-known/oauth-authorization-server
```

响应（节选）：

```json
{
  "issuer": "http://localhost:9000",
  "authorization_endpoint": "http://localhost:9000/oauth2/authorize",
  "token_endpoint": "http://localhost:9000/oauth2/token",
  "jwks_uri": "http://localhost:9000/oauth2/jwks",
  "revocation_endpoint": "http://localhost:9000/oauth2/revoke",
  "introspection_endpoint": "http://localhost:9000/oauth2/introspect",
  "registration_endpoint": "http://localhost:9000/oauth2/register"
}
```

> 从元数据中取出后续要用的端点：`registration_endpoint`、`authorization_endpoint`、`token_endpoint`。

---

## 第四步：动态客户端注册（RFC 7591）

```http
POST http://localhost:9000/oauth2/register
Content-Type: application/json

{
  "client_name": "my-mcp-client",
  "redirect_uris": ["http://127.0.0.1:8765/callback"],
  "grant_types": ["authorization_code", "refresh_token"],
  "response_types": ["code"],
  "token_endpoint_auth_method": "none"
}
```

> `token_endpoint_auth_method: "none"` 表示公共客户端（配合 PKCE）；若服务器要求机密客户端，改为 `client_secret_post` 或 `client_secret_basic`。
>
> 本项目注意：注册请求、授权请求**都不要传 `scope`**。DCR 请求带 scope 会被拒绝（`Invalid Client Registration: scope`）；DCR 客户端没有分配任何 scope，授权请求带 scope 会报 `invalid_scope`。

响应：

```json
{
  "client_id": "kEL3K1i1KBf1RFstLb...",
  "client_id_issued_at": 1793030412,
  "client_name": "my-mcp-client",
  "redirect_uris": ["http://127.0.0.1:8765/callback"],
  "grant_types": ["authorization_code", "refresh_token"],
  "token_endpoint_auth_method": "none"
}
```

> 保存 `client_id`，之后复用（本项目将其持久化在 H2 中，AS 重启不丢失）。

---

## 第五步 A：浏览器授权请求（RFC 6749 + RFC 7636）

在浏览器中打开（或重定向到）：

```
http://localhost:9000/oauth2/authorize
  ?response_type=code
  &client_id=<client_id>
  &redirect_uri=http%3A%2F%2F127.0.0.1%3A8765%2Fcallback
  &state=<随机 state>
  &code_challenge=<BASE64URL(SHA256(code_verifier))>
  &code_challenge_method=S256
```

> 注意不要传 `scope` 参数，DCR 注册的客户端无 scope，传了会报 `invalid_scope`（见第四步）。

浏览器跳转到本项目自带的登录页，账号 `user / dev-123456`，登录成功后 AS 重定向回：

```
http://127.0.0.1:8765/callback?code=<authorization_code>&state=<原 state>
```

> 校验返回的 `state` 与发出的一致，防 CSRF。

---

## 第五步 B：用授权码换 token

```http
POST http://localhost:9000/oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=<authorization_code>
&redirect_uri=http://127.0.0.1:8765/callback
&client_id=<client_id>
&code_verifier=<PKCE code_verifier>
```

> 机密客户端额外带 `client_secret`（body 或 Basic 认证）。公共客户端不需要。

响应（实测）：

```json
{
  "access_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 28800
}
```

> 本项目注意两点：
> - `expires_in` 为 **28800（8 小时）**——DCR 客户端的 access_token TTL 已在 `ClientRepositoryConfiguration` 中从 SAS 默认的 5 分钟调整为 8 小时；
> - 响应中**没有 `refresh_token`**——公共客户端（`token_endpoint_auth_method: "none"`）被 Spring Security 有意不发 refresh_token（见 `OAuth2RefreshTokenGenerator`），token 过期后需重新走一遍第四、五步。

---

## 第六步：带 token 访问 MCP 资源

```http
POST http://localhost:8001/mcp
Authorization: Bearer <access_token>
Content-Type: application/json

{ ... MCP 请求体 ... }
```

RS 用从 AS JWKS 端点（`http://localhost:9000/oauth2/jwks`）拉取的公钥本地验签，不回源校验。授权到此结束；MCP 协议层面，首个请求须为 `initialize` 以换取 `mcp-session-id`，后续请求需在 `mcp-session-id` 头中带上它。

---

## 附：token 刷新（本项目公共客户端不适用）

```http
POST http://localhost:9000/oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token
&refresh_token=<refresh_token>
&client_id=<client_id>
```

> 仅当客户端是机密客户端（注册时带 client_secret）且持有 refresh_token 时可用。本项目经 DCR 注册的公共客户端拿不到 refresh_token，跳过此步。

---

## 流程图（文字版）

```
┌─────────────────────────────────────────────────────────────┐
│ 1. POST /mcp                                                │
│    ← 401 + WWW-Authenticate: resource_metadata="..."        │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. GET .well-known/oauth-protected-resource/mcp  (RFC 9728) │
│    ← resource, authorization_servers[]                      │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. GET .well-known/oauth-authorization-server    (RFC 8414) │
│    ← registration_endpoint, authorization_endpoint,         │
│      token_endpoint, ...                                    │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. POST registration_endpoint                    (RFC 7591) │
│    ← client_id                                              │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 5A. GET authorization_endpoint (+PKCE)           (RFC 6749) │
│     ← 浏览器登录 user/dev-123456，回调 ?code=...&state=...   │
│ 5B. POST token_endpoint (code + code_verifier)              │
│     ← access_token（8h，无 refresh_token）                   │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 6. POST /mcp   Authorization: Bearer <access_token>         │
│    ← 200 OK                                                 │
└─────────────────────────────────────────────────────────────┘
```

---

## 要点备忘

| 步骤 | 端点来源 | 关键点 |
|------|----------|--------|
| 1 | 资源地址 | 401 头的 `resource_metadata` 是下一步 URL |
| 2 | 401 指示的 URL | 取 `authorization_servers[0]` |
| 3 | `.well-known/oauth-authorization-server` | 取注册/授权/token 端点 |
| 4 | 元数据 `registration_endpoint` | POST JSON，拿 `client_id`；请求不带 `scope` |
| 5A | 元数据 `authorization_endpoint` | 浏览器跳转，带 PKCE + state；不传 `scope` |
| 5B | 元数据 `token_endpoint` | 后端 POST，带 `code_verifier`；本项目 8h、无 refresh_token |
| 6 | 资源地址 | `Authorization: Bearer` |
| 刷新 | 元数据 `token_endpoint` | 仅机密客户端可用 |

> 注意：RFC 7591 不规定注册路径；路径以元数据里的 `registration_endpoint` 为准（本项目为 `/oauth2/register`，不是 `/oauth/register`）。
