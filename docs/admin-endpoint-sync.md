# 官方后台登录后同步接口映射

日期：2026-10-21。只读调查，未登录账户、未检索/读取浏览器凭据、未再次请求匿名或 API Key 的 `/api/pricing`，未发送生成请求。本文件记录部署前端的实际行为，以及独立后台登录页面的实现设计。

## 已确认的部署信息

`GET https://admin.ai.leihuo.netease.com/api/status` 返回：

```json
{
  "version": "v1.0.0-rc.21-internal.9",
  "server_address": "https://admin.ai.leihuo.netease.com",
  "docs_link": "https://docs.newapi.pro",
  "HeaderNavModules": {
    "home": false,
    "console": true,
    "pricing": { "enabled": true, "requireAuth": true },
    "docs": false,
    "about": false
  }
}
```

原始 `HeaderNavModules` 字段是 JSON 字符串，此处为便于阅读展开。公开状态中的文档链接是通用 New API 文档，没有发现雷火内部各供应商接口文档链接。

精确搜索既有 Claude memory Markdown、共享 memory Markdown、当前项目 docs 和 `ai.mjs` 后，只找到网关地址、`/v1/models` 和本项目既有 API 研究记录，没有找到额外的雷火媒体协议说明。没有搜索或输出 `.env`、系统环境变量值、浏览器资料或会话凭据。

## 部署前端的鉴权证据

### 同域 Cookie 和 New-Api-User

来源：<https://admin.ai.leihuo.netease.com/static/js/index.451f6f8ea0.js>，模块 `63228`。

其关键逻辑等价于：

```js
const api = axios.create({
  baseURL: '',
  withCredentials: true,
  headers: { 'Cache-Control': 'no-store' }
});

function userId() {
  return window.localStorage.getItem('uid');
}

api.interceptors.request.use(config => {
  const uid = userId();
  if (uid) config.headers['New-Api-User'] = uid;
  return config;
});
```

该模块没有自动添加 `Authorization`。正常网页 API 请求依靠同域浏览器登录状态，并附加 `localStorage.uid` 中的用户 ID。`New-Api-User` 是已登录用户 ID，不能独立作为身份验证凭据。

同一 bundle 模块 `29396` 的 `cq` 函数为：

```js
window.localStorage.setItem('uid', String(id));
```

同一 bundle 模块 `65965` 的 auth store 使用 `localStorage.user` 保存用户元数据。其含义是前端状态，并不是模型 API Key。

### 用户通过官方网页登录

来源：<https://admin.ai.leihuo.netease.com/static/js/async/7948.965c8ecf10.js>。

模块 `52685`：

```js
api.post(`/api/user/login?turnstile=${turnstile}`, {
  username,
  password
});
api.post('/api/user/login/2fa', payload);
api.get('/api/user/logout');
```

模块 `52921` 的 `handleLoginSuccess` 收到登录响应后，先保存 `response.data.id`，然后 `GET /api/user/self`，把其 `data` 交给 `auth.setUser` 并再次保存用户 ID。此部署代码没有从登录响应提取 `access_token`，也没有将其保存到 localStorage 或设置 `Authorization`。

主 bundle 的 `/(auth)/sign-in` 路由映射到 `/sign-in`。`/pricing` 和 `/pricing/$modelId` 的进入条件读取 `pricing.requireAuth`；未登录时跳转 `/sign-in` 并保留 `redirect`。因此登录入口可以直接为：

```text
https://admin.ai.leihuo.netease.com/pricing
```

官方页面将负责密码、验证码、2FA 或部署支持的其他登录流程。

### Pricing 真实接口与 schema

来源：<https://admin.ai.leihuo.netease.com/static/js/async/1172.ae3d557169.js>，模块 `69474`。

```js
async function loadPricing() {
  return (await api.get('/api/pricing')).data;
}
// hook 返回：
endpointMap = response?.supported_endpoint ?? {};
models = response?.data ?? [];
```

`supported_endpoint` 在响应顶层，不是在 `data.supported_endpoint`。模型详情组件将 `model.supported_endpoint_types` 中每个 type 映射为：

```js
const endpoint = endpointMap[type] || {};
const path = endpoint.path || '';
const method = endpoint.method || 'POST';
```

它会将 path 中的 `{model}` 替换成模型名。用于 App 的同步结果只需要：

```json
{
  "some-protocol": { "path": "/confirmed/path/{model}", "method": "POST" }
}
```

不需要返回整个 pricing 响应、用户信息、Cookie、localStorage.user 或任何管理令牌。

## Authorization 与不同版本的界限

New API 较旧公开源码有明确 session 机制：

- <https://github.com/QuantumNous/new-api/blob/v0.11.0-alpha.6/controller/user.go>：`setupLogin` 调用 `sessions.Default(c)`，设置 id/username/role/status/group，再 `session.Save()`；注释明确为设置 session 和 Cookie，然后返回用户元数据。
- <https://github.com/QuantumNous/new-api/blob/v0.11.0-alpha.6/middleware/auth.go>：`authHelper` 先取 session；没有 session username 时，才以 `Authorization` 调用 `model.ValidateAccessToken`。随后要求 `New-Api-User` 与已验证的用户 ID 相等。

当前公开 main 分支已经改变了后台鉴权：

- <https://github.com/QuantumNous/new-api/blob/main/middleware/auth.go>：`classifyDashboardCredential` 从 `Authorization` 解析 dashboard access token 或后台 PAT；`TokenAuth` 另外验证 relay API Key。这是不同凭据体系。
- <https://github.com/QuantumNous/new-api/blob/main/router/api-router.go>：`/api/pricing` 经 `HeaderNavModuleAuth("pricing")`，正常模型 API Key 不等于后台用户登录。

因此不能把当前 main 的后台 Bearer 流程硬套到雷火 internal.9。实现应优先复制本实例网页实际的同源 Cookie + `New-Api-User` 请求行为，让浏览器持有登录状态；无需读取或导出任何管理 access token。今后如果雷火部署改为在网页请求中追加 Authorization，需要重新核对对应部署前端，把请求仍留在官方页面环境，而不是将管理令牌加入模型 API 配置。

## App 最小可实现设计

1. 独立创建后台登录 Activity/WebView，先加载用户确认的官方入口 `https://ai.leihuo.netease.com/usercenter`。用户中心读取不到接口时，再打开 `https://admin.ai.leihuo.netease.com/pricing` 供继续登录读取。这个 WebView 不注册 `Native` 或其他 `addJavascriptInterface`；不复用当前有桥的本地聊天 WebView。JavaScript、DOM storage 和同域 Cookie 按官方页面需要开启。密码由用户输入官方网页，不通过 App 原生设置表单读取。
2. 保留浏览器登录状态在官方 WebView。只在 `https://ai.leihuo.netease.com` 或 `https://admin.ai.leihuo.netease.com` 的文档环境执行同步脚本。登录跳到其他供应商页面时仍无桥；回到官方域再同步。
3. 原生页面提供“读取接口”按钮。按钮使用 `evaluateJavascript` 注入同源只读 fetch：

   ```js
   const headers = { 'Cache-Control': 'no-store' };
   const uid = localStorage.getItem('uid');
   if (uid) headers['New-Api-User'] = uid;
   fetch('/api/pricing', {
     method: 'GET',
     credentials: 'include',
     cache: 'no-store',
     headers
   });
   ```

   不给此请求加手机配置的模型 API Key，也不从 localStorage 读取管理 token。原生同步只返回标准化 `supported_endpoint[type].path/method`。
4. `evaluateJavascript` 不等待 Promise；异步 fetch 的结果先放入页面里随机命名的临时槽位，Java 用短间隔 `evaluateJavascript` callback 获取完成结果。也可以由页面同步完成后调用原生事先定义的受限导航回调，但不应为此暴露全功能 JS interface。临时槽位只放结果映射与固定错误类别，不放用户 ID 或 Cookie；读取后删除。
5. 原生层独立验证 type/path/method：对象规模和字符串长度有上限；path 必须是单 `/` 开头的网关相对路径，不允许 `//`、反斜杠、控制字符和 `..`；method 限 GET/POST/PUT/PATCH/DELETE。只提取规定字段，拒绝任何 `Authorization`、`Cookie`、用户资料或任意响应附加字段。映射与其来源域/网关配置关联保存，换网关时不复用其他来源映射。
6. 登录页没有桥、只通过 native callback 获取映射仍要在回调再次检查当前 URL、同步 operation ID 和 payload 大小。后台网页加载失败或会话过期只提示用户在官方网页重新登录；不会自动请求模型生成。
7. 同步成功后保存的是非敏感接口元数据。后台登录 Cookie 留在 WebView Cookie store，禁止复制到 `ConfigStore`、聊天历史、模型网络桥或日志。

## 同步能解决的范围

Pricing 返回接口 path/method，可以解决雷火自有协议“模型目录有 type，但本地不知道实际路由”的问题。它没有承诺提供每个协议完整请求和输出 schema。

当前部署模型详情的代码示例生成器只专门处理 Anthropic、Gemini、embedding/rerank、image-generation、typesafe-system-one；其余 type 最后生成通用聊天/Responses 示例。不能把这个示例当成所有音频/视频专用模型的请求证据。

实现仍需将已证实供应商请求参数、音频/图片输入、任务查询和媒体结果解码 profile 与同步得到的真实路径配对。未知 profile 的模型不得仅凭同步路径发裸 POST。要兑现“选模型后直接用”，下一步是补足各供应商 profile；后台映射同步是其中一环。
