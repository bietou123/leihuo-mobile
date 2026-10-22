# MiniMax H3 / H3 Max 原厂协议依据

整理日期：2026-10-21。仅通过 GET 阅读官方文档、OpenAPI 和官方仓库脚本；未创建、取消或删除任何实际任务，未运行示例脚本。

本文确认的是 **MiniMax 原厂请求契约**。雷火目录声明的 `create`、`query`、`query_by_task_id`、`delete`、`video_regeneration`、`h3_context_ir` 应由登录管理端同步的 `supported_endpoint` 决定实际网关路径与方法。不能把下列原厂路径直接当成已经验证的雷火路径，也不能将雷火令牌发往 MiniMax 原厂服务。

## 官方来源

- [完整 V2 OpenAPI JSON](https://platform.minimaxi.com/docs/api-reference/video/generation/api/v2-video-generation.json)。创建、上下文扩写、重生成、单任务查询、列表和删除均在同一份定义中。
- [创建视频生成任务](https://platform.minimaxi.com/docs/api-reference/video-generation-v2-create)。页面 `openapi` 字段指向上述 JSON 的 `POST /v2/video_generation`。
- [查询单任务](https://platform.minimaxi.com/docs/api-reference/video-generation-v2-query)、[任务列表](https://platform.minimaxi.com/docs/api-reference/video-generation-v2-list)、[取消或删除](https://platform.minimaxi.com/docs/api-reference/video-generation-v2-delete)。
- [H3-Context-IR](https://platform.minimaxi.com/docs/api-reference/video-generation-v2-h3-context-ir)、[H3 视频再生成](https://platform.minimaxi.com/docs/api-reference/video-generation-v2-regeneration)。
- [MiniMax 官方 H3 仓库 README，固定提交](https://github.com/MiniMax-AI/MiniMax-H3/blob/d21241f0a4b3acbb34c97dae47fa417b7065e438/README.zh-CN.md)。
- [直接创建 2K 文生视频脚本](https://github.com/MiniMax-AI/MiniMax-H3/blob/d21241f0a4b3acbb34c97dae47fa417b7065e438/scripts/readme/full-2k-t2va-reference-2k-result-by-directly-calling-open-platform-api.sh)、[768P 脚本](https://github.com/MiniMax-AI/MiniMax-H3/blob/d21241f0a4b3acbb34c97dae47fa417b7065e438/scripts/readme/full-2k-t2va-reference-768p-result-by-directly-calling-open-platform-api.sh)、[图生视频脚本](https://github.com/MiniMax-AI/MiniMax-H3/blob/d21241f0a4b3acbb34c97dae47fa417b7065e438/scripts/readme/full-2k-i2va-reference-2k-result-by-directly-calling-open-platform-api.sh)。
- [Context IR 脚本](https://github.com/MiniMax-AI/MiniMax-H3/blob/d21241f0a4b3acbb34c97dae47fa417b7065e438/scripts/readme/full-2k-t2va-h3-context-ir.sh)、[视频再生成脚本](https://github.com/MiniMax-AI/MiniMax-H3/blob/d21241f0a4b3acbb34c97dae47fa417b7065e438/scripts/readme/full-2k-t2va-h3-regenerate-2k.sh)。

当前官方 OpenAPI 的 `servers` 为 `https://api.minimax.cn`；固定提交仓库示例列出 CN `https://api.minimaxi.com`、Global `https://api.minimax.io`。这是原厂文档域名的差异，客户端仍使用用户配置的雷火基地址。原厂定义采用 `Authorization: Bearer <API_key>`，提交 JSON 时采用 `Content-Type: application/json`。

## 协议对应关系

| 雷火目录协议名 | 对应的原厂方法与路径 | 请求正文 | 成功响应 |
| --- | --- | --- | --- |
| `create` | `POST /v2/video_generation` | `VideoGenerationV2Req` | `{ "task_id": "..." }` |
| `query_by_task_id` | `GET /v2/query/video_generation/{task_id}` | 无 | `{ "task": { ... } }` |
| `query` | `GET /v2/query/video_generation` | 无；可带查询参数 | `{ "items": [ ... ], "total": 0 }` |
| `delete` | `DELETE /v2/video_generation/{task_id}` | 无 | `{ "task_id": "...", "action": "...", "status": "..." }` |
| `video_regeneration` | `POST /v2/video_regeneration` | `source_task_id` 或 `content` 二选一 | `{ "task_id": "..." }` |
| `h3_context_ir` | `POST /v2/h3_context_ir` | `H3ContextIRReq` | `{ "task_id": "..." }` |

表中的目录名与原厂功能可对应，但网关是否原样转发、如何选择渠道，仍需雷火管理端资料。原厂没有 `selected-model` 私有请求头的依据；不要自行添加该请求头。

## 视频创建

`POST /v2/video_generation` 同时支持 `MiniMax-H3` 与 `MiniMax-H3-Max`。模型 ID 应保持动态目录中的原始值，不将 H3 Max 改名为 H3。

| 参数 | MiniMax-H3 | MiniMax-H3-Max |
| --- | --- | --- |
| `resolution` | `768P`、`2K` | `480P`、`768P`；不支持 `2K` |
| `duration` | 整数 4–15 秒 | 整数 5–15 秒 |
| 输入 | 文字、首尾帧图片、多模态参考素材 | 同左 |
| `extra.prompt_expansion_mode` | 未声明此选项 | `disabled`、`balanced`、`quality`；默认 `balanced` |

Schema 的必填项是 `model`、`content`、`resolution`、`duration`。虽然 Max 的分辨率说明写默认 `768P`，`resolution` 仍在 `required` 中，客户端应显式提交。Max 的 `extra` 只允许 `prompt_expansion_mode`，不能传 `balance`、空字符串、布尔值或任意其他属性。

文生视频最小正文：

```json
{
  "model": "MiniMax-H3",
  "content": [{ "type": "text", "text": "一个男孩在海边打篮球" }],
  "resolution": "2K",
  "duration": 5,
  "ratio": "16:9"
}
```

首帧图生视频在 `content` 中增加：

```json
{
  "type": "image_url",
  "image_url": { "url": "https://example.com/first-frame.jpg" },
  "role": "first_frame"
}
```

参考素材分别使用 `image_url`、`video_url`、`audio_url` 对象，均包含 `url` 属性，`role` 分别为 `reference_image`、`reference_video`、`reference_audio`。首尾帧模式与参考素材模式互斥，不能在同一 `content` 数组混用 `first_frame` / `last_frame` 与 `reference_*`。

每个请求必须包含非空 `text` 项；普通创建和 Context IR 的单个 `text` 最多 7000 个字符。仅文字的官方组合为一个 `text`；首帧图可不传 `role`，但客户端显式传入 `first_frame` 更清楚。

### 宽高比

可用值为 `adaptive`、`21:9`、`16:9`、`4:3`、`1:1`、`3:4`、`9:16`。

- 纯文字：`ratio` 必填，且不能为 `adaptive`。
- 首尾帧图片：由输入图片决定，按 `adaptive` 处理；其他合法比例会被原厂忽略。
- 多模态参考：可省略，默认 `adaptive`；也可指定任一具体比例。

OpenAPI 的输入组合说明支持单尾帧；但 `ContentItem.role` 的文字说明又写 `last_frame` 需与 `first_frame` 成对，存在内部差异。常规界面可先提供单首帧或首尾帧，单尾帧模式需进一步确认。

### 输入文件限制

单次 JSON 请求体总大小 ≤64 MB，Base64 会增大约 33%。原厂支持公网 URL、`mm_file://{file_id}` 与 Base64 Data URI；大文件应使用可直接访问的 URL。平台文件 ID 的上传路由及雷火是否支持 `mm_file://`，不在此次确认范围内。

| 素材 | 格式与编码 | 单文件 | 数量与时长 |
| --- | --- | --- | --- |
| 图片 | JPG、JPEG、PNG、WEBP、HEIC、HEIF | ≤30 MB；宽高 256–5760 px，宽/高 0.4–2.5 | 首帧≤1、尾帧≤1、参考图≤9 |
| 参考视频 | MP4、MOV；视频 H.264/AVC 或 H.265/HEVC；音轨 AAC 或 MP3 | ≤50 MB；宽高与比例同图片；23.976–60 FPS | ≤3；每段 2–15 秒，总时长≤15秒 |
| 参考音频 | WAV、MP3 | ≤15 MB | ≤3；每段 2–15 秒，总时长≤15秒 |

官方仓库进一步规定所有参考文件合计≤12，不能只有参考音频，需配图片或视频。Data URI 示例为 `data:image/jpeg;base64,...`、`data:video/mp4;base64,...`、`data:audio/mp3;base64,...`；格式/MIME 小写。

可选项还包括 `callback_url` 和 `aigc_watermark`。手机直接轮询无需配置回调；如使用回调，原厂先发送 `challenge` 校验，服务端须在 3 秒内原样返回。水印默认 `false`。

## 任务查询与结果

创建成功只返回 `task_id`，不代表视频已完成。保存模型、协议、查询映射与任务 ID 后查询：

`GET /v2/query/video_generation/{task_id}`，无请求正文，成功响应外层为 `task`。

```json
{
  "task": {
    "id": "424010985738629",
    "model": "MiniMax-H3",
    "status": "succeeded",
    "content": { "url": "https://example.com/output.mp4" },
    "resolution": "2K",
    "duration": 5,
    "ratio": "16:9",
    "task_type": "generation",
    "modality": "video"
  }
}
```

| 字段 | 含义 |
| --- | --- |
| `task.status` | `queued`、`running`、`succeeded`、`failed`、`cancelled` |
| `task.task_type` | `generation`、`h3_context_ir`、`regeneration` |
| `task.modality` | 视频任务 `video`；Context IR `text` |
| `task.content.url` | 视频任务成功后的限时下载 URL；过期后可重新查询获取 |
| `task.content.prompt` | Context IR 成功后的增强提示词 |
| `task.error` | 失败信息 `{ "code": "...", "message": "..." }` |
| `task.created_at` / `updated_at` | Unix 时间戳，单位秒 |
| `task.usage` | 仅成功任务返回；视频按秒/图片/Token，Context IR 按 Token |

轮询只在 `queued` 或 `running` 时继续；三个终态分别显示成功、失败或取消。不能将 Context IR 的文字产物当成视频 URL。下载跨站产物时不转发雷火令牌。

原厂单任务与列表只能查询最近 7 天 `[当前时间-7天, 当前时间)` 的任务；超出窗口返回 `invalid task_id`。不要据此重新创建付费任务。

### 列表查询

`GET /v2/query/video_generation`，无正文。可选查询参数：

- `page_num`：整数，从 1 开始。
- `page_size`：整数，官方示例 20。
- `filter.status`：上述五种状态。
- `filter.task_ids`：字符串数组。
- `filter.model`：模型名称字符串。
- `filter.task_type`：`generation`、`h3_context_ir` 或 `regeneration`。

响应 `{ "items": [VideoTask, ...], "total": 0 }`，覆盖上述三个任务类型。OpenAPI 未明确 `filter.task_ids` 的 `style` / `explode`，不猜测逗号拼接编码。

### 取消或删除

`DELETE /v2/video_generation/{task_id}`，无正文。

| 当前任务状态 | 行为 | 响应 `action` / `status` |
| --- | --- | --- |
| `queued` | 取消排队任务 | `cancelled` |
| `succeeded` / `failed` | 删除任务记录 | `deleted` |
| `running` / `cancelled` | 返回错误 | 无成功响应 |

成功响应为 `{ "task_id": "...", "action": "cancelled", "status": "cancelled" }`，或对应的 `deleted`。因此手机的“停止查询”与原厂“取消排队任务”应分别处理；运行中的任务不可用本接口取消。

## H3-Context-IR

`POST /v2/h3_context_ir`，原厂目前仅支持 `MiniMax-H3`。必填 `model`、`content`、`duration`，不带 `resolution`；输入类型、宽高比规则与普通 H3 创建相同。`duration` 为整数 4–15 秒。

```json
{
  "model": "MiniMax-H3",
  "content": [{ "type": "text", "text": "一个男孩在海边打篮球" }],
  "duration": 5,
  "ratio": "16:9"
}
```

创建返回 `task_id`，用同一个单任务查询接口等待完成。产物在 `task.content.prompt`，`task.task_type=h3_context_ir`、`task.modality=text`。此请求仅生成增强提示词，**不会创建视频**。不能在用户只点一次视频生成时自动增加该付费步骤。

## H3 视频再生成

`POST /v2/video_regeneration`，原厂目前仅支持 `MiniMax-H3`，目标 `resolution` 仅 `2K`。以下两种模式必须且只能选择其一，不支持任意视频的通用放大。

### 已有任务 ID

```json
{
  "model": "MiniMax-H3",
  "source_task_id": "424010985738629",
  "resolution": "2K"
}
```

该模式需原厂白名单；源任务须是当前账号已有 `/v2/video_generation` 的成功任务，并处于 7 天查询窗口内。不可假设雷火用户已获该白名单。

### H3 768P 源视频

```json
{
  "model": "MiniMax-H3",
  "content": [
    { "type": "text", "text": "生成源视频时实际使用的最终提示词" },
    {
      "type": "video_url",
      "video_url": { "url": "https://example.com/h3-768p-output.mp4" },
      "role": "base_video"
    }
  ],
  "resolution": "2K"
}
```

`content` 中必须且只能有一个 `base_video`，并原样附上生成该 768P 视频时实际送入模型的全部输入。若曾用 Context IR，文字须为扩写后的最终 prompt；参考图片、视频、音频也必须一致。该模式文字上限 40000 字符。源视频须符合：

- 包含音轨，24 FPS。
- 宽、高均能被 32 整除。
- 面积在 `768×768` 至 `768×1344` 之间。
- 总帧数为 107–362，每档递增 17 帧，约 4–15 秒。

返回 `task_id`，用同一查询接口读取 `task.content.url`，`task_type=regeneration`、`modality=video`。提交重生成是新付费任务，不能因原任务失败或下载过期自动触发。

## 错误与网关接入边界

原厂 API 错误采用真实 HTTP 状态与 OpenAI 风格结构：

```json
{
  "type": "error",
  "error": {
    "type": "bad_request_error",
    "message": "invalid params ... (2013)",
    "http_code": "400"
  },
  "request_id": "..."
}
```

创建可能返回 400、401、402、422、429、500 等；任务查询/删除也有错误响应。错误的 `error.http_code` 是字符串，任务执行失败则在 `task.error` 中报告。

正式启用雷火常规视频流程前，还需取得匹配模型的 `create` 与 `query_by_task_id` 网关映射、对应方法及任务路由依据。只读目录中的能力声明不能证明接口路径和网关转发正文。接口映射缺失时保留模型可见并说明待同步；不要套用 `/v1/chat/completions` 或失败后换协议重发。辅助任务只在原厂契约、网关映射和用户明确操作同时具备时提交。
