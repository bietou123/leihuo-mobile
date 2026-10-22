# Veo 接口补充调查

调查日期：2026-10-21。没有执行生成 POST 请求。以下区分本实例只读结果和官方版本源码证据。

## 本实例只读结果

1. `https://ai.leihuo.netease.com/api/pricing` 和 `https://admin.ai.leihuo.netease.com/api/pricing` 的匿名 GET 均返回 HTTP 401：`Unauthorized, not logged in and no access token provided`。之前携带 API 令牌得到的无效 token 错误并不是定价接口唯一的限制；匿名同样不开放。
2. 两个域名的 `/api/status` 都声明定价页 `enabled: true`、`requireAuth: true`，系统版本为 `v1.0.0-rc.21-internal.9`。
3. 因此没有取得 `supported_endpoint` 路径映射，也没有创建假定价映射文件。
4. 原始模型清单中的三个 Veo 3.1 模型均 `owned_by: vertex-ai`，协议名为 `openai-video-gen`。
5. 使用当前 API 令牌只读查询源码已定义的不存在任务：`GET /v1/video/generations/task_readonly_route_probe_nonexistent`，本实例返回 HTTP 400：`{"code":"task_not_exist","message":"task_not_exist","data":null}`。确认该任务查询路径存在；没有查询他人任务或创建新任务。

## 接近本实例版本的源码依据

官方标签 `v1.0.0-rc.21` 比不断变化的 `main` 更接近本实例报告的版本。

- `router/video-router.go` 明确注册 `POST /v1/video/generations`、`GET /v1/video/generations/:task_id`；另有 OpenAI 路径 `POST /v1/videos`、`GET /v1/videos/:task_id`、`GET /v1/videos/:task_id/content`。
- `relay/relay_adaptor.go` 将 `ChannelTypeVertexAi` 指向 `taskvertex.TaskAdaptor`。
- `relay/channel/task/vertex/adaptor.go` 的模型表明确包含 `veo-3.1-fast-generate-preview` 和 `veo-3.1-generate-preview`，将标准任务请求转换为 Vertex 的 `predictLongRunning` 请求。
- `middleware/distributor.go` 的标准视频 POST 从请求体读取 `model`，按模型与用户令牌权限选择配置的渠道；GET 从已保存任务取回原模型。没有通过 `TaskAdaptor.GetModelList` 检查请求模型白名单。
- `relay/relay_task.go` 在选择适配器后使用请求的原模型及渠道模型映射构造 `UpstreamModelName`。Vertex 适配器用这个名称生成上游地址。

`veo-3.1-lite-generate-preview` 未出现在 rc.21 的静态模型表，但已出现在本实例动态目录。静态表的缺项不会在上述提交路径直接拒绝它，仍会按网关配置的模型及渠道映射转发；Google 上游是否接受、网关是否另外修改了相关行为，此次未发生成请求，不能确认。不要将静态清单缺项作为客户端删除此模型的理由。

据此，Veo 可采用已定义的标准任务接口完成适配；这具有版本源码及实际查询路径依据，仍需将成功生成能力标记为尚未实调。

## 标准提交请求字段

标准路径：`POST /v1/video/generations`，JSON body；身份验证使用网关 Bearer API 令牌。不要把 Google 原厂完整 Vertex URL 或 Google 凭据放进手机。

| 字段 | rc.21 接收类型与处理 |
| --- | --- |
| `model` | 字符串，填写手机当前选择的完整网关模型 ID |
| `prompt` | 字符串，非空提示 |
| `duration` | 内部是整数；自定义 JSON 反序列化同时允许整数 `8` 或字符串 `"8"`。客户端应发送整数 |
| `seconds` | 字符串；应发送 `"8"`，不要发送 JSON 数值。Vertex 生成 body 主要读取 `duration`，推荐只使用 `duration` |
| `size` | 字符串 `WxH`，例如 `1280x720`，会用于推导 `resolution` 和 `aspectRatio` |
| `image` | 单个字符串 URL 或图片 Data URI；标准验证转换为 `images` |
| `images` | 字符串数组；此版本 Vertex 仅使用第一张参考图 |
| `metadata` | 对象，或内容为 JSON 对象的字符串；推荐对象。字段使用 Veo 的 camelCase |

`metadata` 经 `UnmarshalMetadata` 转换为以下 Veo 参数，未知字段不会进入 typed Veo payload：

| metadata 字段 | 类型 |
| --- | --- |
| `durationSeconds` | 整数数值 |
| `aspectRatio` | 字符串，例如 `16:9`、`9:16` |
| `resolution` | 字符串，例如 `720p`、`1080p`，源码统一为小写 |
| `generateAudio` | 布尔值 |
| `negativePrompt`、`personGeneration`、`storageUri`、`compressionQuality`、`resizeMode` | 字符串，具体值需上游模型支持 |
| `seed` | 整数 |
| `sampleCount` | 整数，但适配器强制设为 1 |

`aspect_ratio` 不等于 `aspectRatio`，`metadata.size` 也不等于顶层 `size`。客户端不要发送这些错误键名。`metadata.model` 会被删除，模型始终来自顶层 `model`。

计费时长解析顺序为 `metadata.durationSeconds`、顶层 `duration`、顶层 `seconds`、默认 8 秒；分辨率解析顺序为 `metadata.resolution`、顶层 `size`、默认 `720p`。源码构造上游 body 时不会在所有字段缺省时显式补齐同样的默认值，因此模板宜直接填写 8 秒和 720p，保持请求和估算一致。

推荐模板：

```json
{
  "model": "$MODEL",
  "prompt": "$PROMPT",
  "duration": 8,
  "metadata": {
    "aspectRatio": "16:9",
    "resolution": "720p",
    "generateAudio": true
  }
}
```

它的参数结构有源码依据，8 秒和 720p 是源码的默认估算值。它不是一次成功生成的实测结果。

## 提交和查询响应

Vertex `DoResponse` 将 Google operation name 留在服务端，返回公开任务 ID 的 OpenAI 视频对象，例如：

```json
{"id":"task_...","task_id":"task_...","object":"video","model":"veo-3.1-fast-generate-preview","status":"queued"}
```

保存 `id`/`task_id` 后用 `GET /v1/video/generations/{公开任务ID}` 续查。该 GET 不发送 JSON body，不需要重新提交生成请求。

rc.21 标准查询返回两种结构：

1. Vertex/Gemini 实时状态读取成功时，返回 `{ "code": "success", "data": { "status": "succeeded", "task_id": "task_...", "url": "...", "format": "mp4", "error": null } }`。待处理状态为 `queued` 或 `processing`，失败为 `failed`。
2. 实时读取不可用时，返回通用 TaskDto 包装：`{ "code": "success", "data": { "task_id": "task_...", "status": "SUCCESS", "result_url": "...", "fail_reason": "...", "data": { ...保存的上游原始响应... } } }`。状态还可能是 `NOT_START`、`SUBMITTED`、`QUEUED`、`IN_PROGRESS`、`FAILURE`。

| 信息 | 应读取的路径 |
| --- | --- |
| 公开任务 ID | `task_id`、`id`、`data.task_id` |
| 当前状态 | `data.status` 或 `status` |
| 标准查询视频 | `data.url`、`data.result_url`；直接形状兼容 `url`、`result_url` |
| OpenAI `/v1/videos/{id}` 查询可能的媒体 | `metadata.url`；若没有地址，媒体内容路径为已注册的 `/v1/videos/{公开任务ID}/content` |
| 上游失败原因 | `data.fail_reason`、`data.reason`、`data.error.message`、`data.data.error.message`；兼容 `error.message`、`message` |
| 网关提交/查询 HTTP 错误 | `{code,message,data}` 或 OpenAI `{error:{message,...}}`，先检查 HTTP 错误及错误对象 |

实时查询分支会将 `error` 固定为 null，失败状态可能没有细节；不能假定 `data.error.message` 必然有值。此时应显示明确的任务失败状态，同时保存原始返回供排查。未完成时 URL 为空，不要把任意 HTTP 地址当作已经生成的视频。

成功媒体可能是 `data:video/...;base64,...` 或网关 `/v1/videos/{taskID}/content` 代理 URL。Vertex 的大视频经过代理读取时，需要媒体请求的认证、Range 和部分内容状态正确传递；查询得到 `succeeded` 不代表手机已经取得视频字节。

## 固定版本参考

- <https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/router/video-router.go>
- <https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/relay_adaptor.go>
- <https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/task/vertex/adaptor.go>
- <https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/common/relay_info.go>
- <https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/common/relay_utils.go>
- <https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/task/gemini/dto.go>
- <https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/task/gemini/billing.go>
- <https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/relay_task.go>
- <https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/middleware/distributor.go>
