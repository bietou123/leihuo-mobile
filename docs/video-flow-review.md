# 视频任务流程只读审查

日期：2026-10-21。审查对象为本轮修改前的 `app/src/main/assets/app.js`、`MainActivity.java`、`ChatStore.java` 和 `gateway-endpoints.json`。未编辑实现，未执行测试或任何计费生成请求。仅阅读本地代码和 New API 官方公开源码。

## 必须随着视频路由接通修复的问题

### 1. 成功任务不一定包含可播放 URL

原 `pollTask` 遇到 `completed`/`succeeded` 就返回；`generateMedia` 无媒体、无文字时把结果显示为 JSON。

官方当前 Google 插件的 `legacyRenderers.openai_video` 完成响应只有任务元数据（`id`、`status`、`progress` 等），没有 URL。因此增加提交和查询路由后，Veo 仍可能只显示 JSON。旧 `dto/video.go` 的 `VideoTaskResponse` 有 `url`，不能把这个旧 DTO 当作每种视频协议的保证。

最小修复：任务模板明确区分提交、查询和内容路径；成功但没有 URL 时，从已确认的内容接口获取视频。当前官方 Google 插件支持 `video` artifact，内容请求通过网关身份验证代理 Google 文件；必须核对雷火版本是否支持相同接口。不要把用户网关令牌传给 Google URI。对未知内容协议保留任务 ID 和明确的继续查询入口，不把无 URL JSON 作为已经生成的视频。

### 2. 状态 schema、错误和任务 ID 的处理不完整

原 `pollTask` 只读取 `task.path`，模板如使用 `pollingPath` 必须先归一化；只有 `id`/`task_id` 系列路径，没有 Google operation 的 `name`；状态只有字符串路径，没有 `done` 布尔值。Google 原生接口的 operation 同时要求从 `name` 取任务 ID、从 `done` 和 `error` 判定状态，不能简单照搬 OpenAI 状态 schema。

每次 GET 只检查 status 字符串中的 `fail/error/cancel`。HTTP 200 的 `{ "error": { "message": "..." } }` 没有 status 时会继续轮询。成功匹配用子串正则也会把 `unsuccessful`、`not_completed` 判为成功。提交成功但找不到任务 ID 时直接返回 JSON，没有说明查询无法启动。

最小修复：统一 `readPath` 和模板字段名；按已证实协议指定 ID、状态、完成标记路径；每次提交/查询都先检查结构化错误。用已确认的终态集合判定成功/失败，明确完成与媒体存在的关系。任务 ID 缺失且没有直接媒体时给出可读错误，同时保存原始响应供排查。原生 Google operation 需要专门模板，不应通过猜测新增 ID 路径和路由。

### 3. 任务 ID 不持久化，进程回收后容易重复创建付费任务

原 `runGeneration` 只在提交前和结束后持久化。`pollTask` 的 ID 在函数局部变量中，`answer.rawResult` 只有完成后才设置。后台 `visibilitychange` 即使触发保存，也没有任务 ID 和查询模板可保存。`init` 只把 streaming 改为 false，提示“重新生成”，无法继续查原任务。

最小修复：收到任务 ID 后立即写入 assistant 消息并保存 `{id, model, endpoint, method, body, headers, startedAt, state}` 等查询所需的非敏感信息。保存实际配置的网关地址或其标识，避免切换网关后把旧任务发到新网关。取消/超时/进程回收后保留任务 ID，提供“继续查询”而无需重新 POST。密钥仍仅从原生配置读取，不保存进聊天记录。

### 4. 停止、换对话后的状态与 UI

轮询等待期间 `activeRequest` 是伪请求 ID，`pending` 中放了可取消的定时器，这一做法能让停止中断三秒等待。但 `stopGeneration` 只取消当前请求，不立刻改变全局 busy 状态。`newChat/openChat` 同步切换页面时 `resizeComposer` 仍看到旧 `activeRequest`；旧 generation 的 finally 清空 request 后，仅当 `activeChat===chat` 才更新 composer。因此新页面可能暂留停止按钮，直到用户输入触发 resize。

用户停止轮询时 assistant content 通常已有“正在生成视频，请稍候…”。取消 catch 仅在 content 为空时写“已停止生成”，于是已经停止的旧对话仍显示正在生成。停止 HTTP 或轮询也不会取消服务器任务，应保留该任务以便继续查询。

当前发送/切模型/图片生成的 guard 基本阻止会话之间同时生成，不应把所有全局变量风险说成已能复现的并发创建。若修复时让停止立刻清 busy，旧 generation 的 finally 无条件 `activeRequest=null` 就可能覆盖新请求，需要同时加入 generation owner/token。模型工具可以与会话生成并发，但它自己的 request 没有纳入 `activeRequest`；工具失败或结束也应由独立状态管理，不要复用全局生成 request。

最小修复：使用 generation 对象/token 作为生成所有者，只有当前所有者能清 busy；取消时记录 canceled 并立即更新 composer；换对话以后旧 generation 结束也重新计算 composer。视频任务停止提示为“已停止等待，可继续查询任务”，保留 ID；不要在未实现服务器取消接口时说任务已被取消。

### 5. 轮询总时间没有上限，单次网络超时无法恢复

240 次循环、每次间隔三秒只是至少 12 分钟的等待；每个 GET 默认 300 秒超时，理论总等待可约 20 小时。第一次 GET 网络错误立即结束全部轮询，任务 ID 也未保存。等待超时和取消都可能留下“正在生成”文本。

最小修复：按真实截止时间限制总等待（例如 15 或 20 分钟，由产品选择），单次 GET 使用短超时，并在总期限内选择有限、仅查询的重试。截止、断网或用户停止均保留可恢复任务，解释当前停止等待。禁止自动重发提交请求，也不要失败后自动换协议提交。

## 媒体响应和播放

### 6. URL 识别可能漏掉真实视频字段

原 `extractMedia` 对 HTTPS 字符串依 key 或文件后缀识别媒体。Google `generatedVideos[].video.uri` 中的 `uri` 没有匹配，若链接没有 `.mp4` 后缀则被忽略。`output` 直接字符串也有同样问题。根级 `url` 配合 preferredKind 能识别标准结果，但 `video_url`、`thumbnail_url` 的宽泛递归可能把封面或其他链接混成媒体。

最小修复：首先按模板中确认的媒体路径取结果及类型，使用泛型递归作为兼容补充；对确认的视频 `uri` 字段支持无后缀 URL。不要把所有 `uri` 都判成视频。Google原生 URI需要上游鉴权，其发现不等于可以直接播放。

### 7. WebView 媒体代理丢失 Range 和响应状态

`shouldInterceptRequest` 只把 URL 传给 `loadPublicMedia`。后者没有转发浏览器的 `Range`/`If-Range` 请求头，返回三参数 `WebResourceResponse` 也没有保留真实 206 状态、`Content-Range`、`Content-Length`、`Accept-Ranges` 等响应头。这会影响视频拖动和浏览器按字节读取，尾部 moov 的 MP4 可能无法按正常方式预载。

最小修复：把 `WebResourceRequest` 传入，只转发必要的媒体请求头，返回带状态和有限响应头的六参数 `WebResourceResponse`，正确处理 206、416、304（WebResourceResponse 对 3xx 有限制，重定向继续手工处理）。媒体链接仍不得携带网关 API Key，已确认的同源受保护内容下载应走另一条受控路径，不能给所有远端请求自动加密钥。

媒体代理目前只接收 `image/*`、`audio/*`、`video/*`。若已确认内容接口返回 `application/octet-stream`，需要可信的模板类型/MIME处理，而不能让现有 `extractMedia` 把所有二进制默认判为图片。远端直链需要兼容其实际 MIME，不宜广泛放开远端 HTML/脚本。

### 8. 二进制视频读入内存和聊天保存存在大小限制

原生网络层把非文本文件完整读成 byte[]，再转 Base64 JSON，经 evaluateJavascript 传给 WebView；单响应上限 64 MB。聊天保存上限 32 MB；视频约超过 24 MB 后，Base64 已可能让整份聊天超过 32 MB，保存失败，重启后结果丢失。大文件还需要数份内存复制。

最小修复：优先使用网关返回的可播放、非敏感链接。受保护内容采用流或应用缓存文件，返回可控制的本地媒体地址，并让 WebView 使用原生缓存服务；聊天只保存任务/文件引用。若此次仅做小范围兼容，至少对受保护视频的大小上限给出可读提示并保留任务 ID，不默默丢结果。

## 建议本轮最小落地顺序

1. 先按已证实雷火接口补提交、查询、内容路径；确认成功无 URL 的处理。
2. 通用状态解析加入错误检查、严格终态、截止时间和任务立即持久化。
3. owner/token 统一停止和 finally，修复换对话后的 composer 与停止文案。
4. 视频 URL 的确认路径和媒体 Range 响应。
5. 为已提交任务留继续查询入口，保证超时/停止/重启不诱导重复付费 POST。

## 阅读依据

- 当前项目 `app.js`：原代码 333–410、448–467、533–539 行。
- 当前项目 `MainActivity.java`：原代码 133–140、197–232、261–357、439–447、606–613 行。
- 当前项目 `ChatStore.java`：32 MB 上限。
- <https://github.com/QuantumNous/new-api/blob/main/dto/video.go>：旧任务响应可有 `url`。
- <https://github.com/QuantumNous/new-api/blob/main/relaykit/dto/openai_video.go>：当前 OpenAIVideo 元数据不定义 `url`。
- <https://github.com/QuantumNous/new-api/blob/main/plugins/tasks/google/plugin.js>：Veo operation schema、`video.uri`、artifact 内容下载、legacy video renderer。
- <https://github.com/QuantumNous/new-api/blob/main/router/video-router.go>：标准视频提交和查询路由。

公开 main 分支的协议依据不等于雷火 internal.9 的部署能力；具体路由仍须结合本实例资料确认。
