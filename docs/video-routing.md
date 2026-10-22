# 视频请求模板与兼容边界

记录日期：2026-10-21。客户端实现位于 `app/src/main/assets/video-profiles.js`。

雷火状态接口报告 `New API v1.0.0-rc.21-internal.9`。以下标准入口和转换格式来自公开 `v1.0.0-rc.21` 源码；新模型的参数范围另参考公开 `main` 的原厂插件。内部版本可能有新增处理，不能把公共源码视为本实例的逐模型生成实测。本次没有提交视频生成请求。

## UI 契约

```js
const initial = LeihuoVideo.defaultsFor(model);
const profile = LeihuoVideo.profileFor(model);
const constraints = LeihuoVideo.constraintsFor(model);
const fields = LeihuoVideo.optionsFor(model);
const request = LeihuoVideo.template(model, prompt, files, options, endpointMap);
```

`options` 使用 `duration` 数字、`aspectRatio` 字符串、`resolution` 小写字符串和 `generateAudio` 布尔值。`defaultsFor` 返回这四项初始值；`profileFor` 提供 `durations`、`minDuration`、`maxDuration`、`resolutions`、`ratios`、`maxImages` 等约束。`constraintsFor` 将范围约束转为适合选择框的时长候选数组。应按模型保存选项，避免从上一模型继承不兼容的时长或分辨率。

| 目录模型 | App 初始时长 | 范围与选项 |
| --- | --- | --- |
| Veo 3.1 / Fast / Lite | 8 秒 | 4、6、8 秒；720p、1080p；16:9、9:16 |
| Seedance 1.5 Pro | 5 秒 | UI 至少 4 秒，最长 12 秒；480p、720p、1080p |
| Seedance 2.0 | 5 秒 | UI 至少 4 秒，最长 15 秒；480p、720p、1080p、4k |
| Seedance 2.0 Fast | 5 秒 | UI 至少 4 秒，最长 15 秒；480p、720p |
| Seedance 2.5 | 5 秒 | UI 至少 4 秒，最长 30 秒；480p、720p、1080p |
| Wan 3.0 Video | 5 秒 | 2–30 秒整数；480p、720p、1080p |
| MiniMax H3 | 10 秒 | 4–15 秒整数；768p、2k；须同步提交及查询路径 |
| MiniMax H3 Max | 10 秒 | 5–15 秒整数；480p、768p；须同步提交及查询路径 |

Seedance 的 5 秒是客户端选择的初始值，不将其描述为所有上游模型的默认值。模型目录保持动态，helper 识别模型家族并照传实际模型 ID；不使用适配器 `GetModelList` 静态过滤目录。Veo Lite 虽未列在公开 rc.21 的模型表中，仍保留并走同一动态标准转换器。

MiniMax H3 与 H3 Max 的 V2 原厂协议已由官方 OpenAPI 取得，helper 已提供文字及单张首帧图片的直接请求模板。仅当网关 `create` 和 `query_by_task_id` 两条真实路径都带 `synced: true` 且方法为 POST/GET 时启用，否则返回 `null`。详情及尚未提供 UI 的原厂能力见 `h3-routing.md`。

## 标准提交和任务查询

默认提交入口：`POST /v1/video/generations`。

查询入口：`GET /v1/video/generations/{公开任务ID}`。任务 ID 优先读取 `task_id`、`id`、`data.task_id`、`data.id`；状态优先读取 `data.status` 或 `status`。视频常见路径为 `data.url`、`data.result_url`；失败说明包括 `data.fail_reason` 和 `data.reason`。

helper 的 `task` 还提供 `mediaPaths`、`errorPaths` 和已注册的媒体入口 `/v1/videos/$TASK_ID/content`。认证、状态轮询、任务保存、媒体下载由 App 的主流程处理。不能因为提交成功就显示已生成，也不能因失败自动换协议重新提交。

Seedance 的专用路由仅在 `endpointMap.seedance` 的提交记录和其 `task` 查询记录都带 `synced: true`、方法分别为 POST/GET，并且使用合法本地路径时启用；查询路径还必须明确包含 `$TASK_ID`。只有一个提交路径时仍使用有源码依据的标准任务路由，不推导未知私有查询路径。

H3 查询路径可规范化已有 `{id}`、`{task_id}`、`:id`、`:task_id` 或 `$TASK_ID` 占位符。没有占位符时，仅在真实同步路径以 `/query/video_generation` 结尾时，依据已证实的 V2 查询协议追加 `/$TASK_ID`；其他没有任务 ID 的未知路径不会启用。H3 状态是字符串 `queued/running/succeeded/failed/cancelled`，分别读取 `task.status`、`task.content.url` 和 `task.error.message`，并兼容网关 `data` 包装。

H3 初始分辨率为 768p、时长 10 秒；文字提示词最多 7,000 字符，Base64 首帧图片解码大小最多 30 MiB。App 现有附件限额更小。图生比例由原图决定，helper 发送 `ratio: "adaptive"`；文字生成使用用户选择的明确比例。V2 没有本次可证实的声音开关，H3 设置界面不显示此开关。当前只实现直接生成，没有首尾帧、参考视频/音频、提示扩写、列表删除及重生成的常规 UI。

## Veo

```json
{
  "model": "veo-3.1-fast-generate-preview",
  "prompt": "雨后森林，镜头缓慢向前移动",
  "duration": 8,
  "metadata": {"aspectRatio": "16:9", "resolution": "720p", "generateAudio": true}
}
```

字段必须使用 `aspectRatio`、`generateAudio` 的 camelCase。Vertex 转换器支持 `images` 中的首张图片，可使用 PNG/JPEG/WebP Data URI 或 HTTPS 地址；客户端一次只允许一张图片，防止第二张附件被转换器忽略。具体时长、分辨率及图片组合是否可用仍取决于所选上游版本。更多响应和媒体细节见 `veo-routing.md`。

## Seedance

```json
{
  "model": "doubao-seedance-2-0-fast-260128",
  "prompt": "小猫在窗边晒太阳，轻轻转头",
  "duration": 5,
  "seconds": "5",
  "metadata": {"resolution": "720p", "ratio": "16:9", "generate_audio": true}
}
```

rc.21 Doubao 转换器读取 `seconds` 字符串并转为原厂 `duration`；仅传顶层 `duration` 不会填入此转换器的上游请求。`metadata` 直接解到原厂请求对象，所以比例与声音字段分别为 `ratio`、`generate_audio`。

图片使用顶层 `images` 数组。转换器逐张生成 `{type: "image_url", image_url: {url: "..."}}`，随后追加正文的 text content；未添加未证实的参考媒体角色或模型专用参数。若启用经过同步的原厂路由，则直接发送原厂 `{model, content, duration, resolution, ratio, generate_audio}`，不携带标准转换包装。

## Wan 3.0

公开 main Alibaba 插件已经明确包含 `wan3.0-video`。该版本原厂请求使用 `parameters.resolution` 和 `parameters.ratio`，图片在 `input.media` 中，时长为 2–30 秒整数；插件未设置时长时使用 5 秒。`-1` 智能时长也有最新插件依据，但本次 App 仅提供明确时长选项。

```json
{
  "model": "wan3.0-video",
  "prompt": "湖面晨雾，远处有山峰",
  "duration": 5,
  "size": "720p",
  "metadata": {
    "input": {"prompt": "湖面晨雾，远处有山峰", "img_url": ""},
    "parameters": {"size": "", "resolution": "720P", "ratio": "16:9", "duration": 5, "audio": true}
  }
}
```

有参考图片时 `metadata.input.media` 使用 `[{"type":"first_frame","url":"图片 Data URI 或 HTTPS URL"}]`。空的 `img_url` 清除旧转换器初始化的旧图片字段，避免与 `media` 同时发送；空的 `parameters.size` 清除旧尺寸格式。旧 Wan 2.x 则保留原先的 `size` 或 `resolution` 格式，图片比例由图片决定。

**rc.21 的限制：**它的 `AliVideoInput` 已包含 `media`，所以可以传首帧；`AliVideoParameters` 可传 `resolution`、`duration`、`audio`，但没有 `ratio` 字段，解码 metadata 时会丢弃比例。公开 main 插件具备完整的 Wan 3.0 ratio 处理。雷火 internal.9 是否已经补齐该字段尚未证实，因此不能保证 Wan 3.0 在本实例一定按用户选择的比例生成。这里没有用其他未证实的路径补救。

## 来源

- [rc.21 视频路由](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/router/video-router.go)
- [rc.21 Vertex 任务转换器](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/task/vertex/adaptor.go)
- [rc.21 Doubao 任务转换器](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/task/doubao/adaptor.go)
- [rc.21 Ali 任务转换器](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/task/ali/adaptor.go)
- [rc.21 通用请求与时长解析](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/common/relay_utils.go)
- [当前公开 Seedance 模型参数](https://github.com/QuantumNous/new-api/blob/main/plugins/tasks/doubao/plugin.js)
- [当前公开 Wan 3.0 及旧 Wan 模型参数](https://github.com/QuantumNous/new-api/blob/main/plugins/tasks/alibaba/plugin.js)

参数和路由有源码依据；可用性、账户额度、文件约束和是否保留内部转换器仍以本实例真实返回为准。
