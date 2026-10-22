# 雷火网关 API 研究记录

研究日期：2026-10-21。仅执行模型目录、状态信息、公开前端和官方源码的只读请求；未执行聊天、图片、视频、语音或 3D 的生成请求。

## 本实例已确认的信息

- 默认入口：`https://ai.leihuo.netease.com`。
- `GET /v1/models` 使用 `Authorization: Bearer <用户令牌>`，返回 `{ "object": "list", "success": true, "data": [...] }`。
- 本次账户可见 88 个模型。完整原始目录保存在 `app/src/main/assets/model-catalog.json`，不含凭据。
- 模型对象包含 `id`、`object`、`created`、`owned_by`、`supported_endpoint_types`。
- `GET /api/status` 可匿名读取，系统报告 `New API`，版本为 `v1.0.0-rc.21-internal.9`。
- 公开管理前端：`https://admin.ai.leihuo.netease.com`。其模型详情页从 `GET /api/pricing` 的 `supported_endpoint` 读取各协议的 `path` 和 `method`，不是在前端硬编码这些路径。
- `GET /api/pricing` 不接受当前网关 API 令牌作为管理登录，返回 `Unauthorized, invalid access token`。未尝试读取浏览器登录资料。
- 补充只读检查：`GET /api/endpoint` 返回 404；`GET /api/models` 使用当前 API 令牌也返回 `Unauthorized, invalid access token`，没有得到额外协议映射。
- 本机既有 `ai.mjs` 使用 `POST /v1/chat/completions` 和 Bearer 身份验证直接聊天；此次仅阅读其实现，未重新请求聊天。

目录是当前用户及其令牌权限下可见的模型清单，并非网关管理员配置的全站目录；安装后的 App 应使用手机设置中的用户令牌刷新清单。

## 目录分类

下表按模型名称和已声明的能力分类，用于界面显示。分类不代替实际协议选择。

| 展示分类 | 数量 |
| --- | ---: |
| 对话 | 43 |
| 图像 | 12 |
| 视频 | 10 |
| 语音 | 9 |
| 3D | 9 |
| 向量 | 3 |
| Jev 专用 | 2 |
| 合计 | 88 |

同一个模型可能有多种调用方式。`openai` 出现 39 次，`openai-response` 出现 22 次，`anthropic` 出现 28 次，`gemini` 出现 10 次。`gemini-2.5-flash-image` 也声明 `openai`；MiMo 的 ASR、声音克隆和声音设计模型也使用 `openai` 标记。仅凭这个标记不能确定是否接受普通文字聊天输入。

以下六个模型的协议列表为空，应保留在完整模型菜单中，并允许用户指定协议：

`codex-auto-review`、`danqingyue`、`deepseek-v4-flash`、`gpt-5.6-luna`、`gpt-6-sol`、`tripo-default`。

`-fim` 模型也在原始目录中，应保留；它们可能要求补全或前后缀输入，而非一般聊天。

## 有官方源码依据的标准路径

以下路径来自 New API 官方源码。它们是实现适配器的依据，此次没有向雷火实例发出生成请求，因此不代表已实测每个模型成功。

| 目录协议或能力 | New API 标准路径 | 请求内容 |
| --- | --- | --- |
| `openai` | `POST /v1/chat/completions` | `model`、`messages`、`stream` |
| `openai-response` | `POST /v1/responses` | `model`、`input`、`stream` |
| `anthropic` | `POST /v1/messages` | `model`、`messages`、`max_tokens`；`x-api-key` 和 `anthropic-version` |
| `gemini` | `POST /v1beta/models/{model}:generateContent` | `contents`；生成图片时使用 Gemini 的图像输出配置 |
| `image-generation` / OpenAI 图片协议 | `POST /v1/images/generations` | `model`、`prompt` 等 |
| OpenAI 图片编辑协议 | `POST /v1/images/edits` | multipart 图片及编辑提示 |
| 向量协议 | `POST /v1/embeddings` | `model`、`input` |
| OpenAI 音频协议 | `POST /v1/audio/speech` | `model`、`input`、`voice` 等 |
| OpenAI 音频转写协议 | `POST /v1/audio/transcriptions` | multipart 音频及 `model` |
| 标准视频协议 | `POST /v1/video/generations` | 异步生成参数；具体字段依模型要求 |
| 标准视频任务查询 | `GET /v1/video/generations/{task_id}` | 查询已提交任务 |

目录中的 `openai-image-generation`、`openai-image-edit-endpoint` 和 `openai-embedding` 是雷火实例的协议名称；其标准兼容路径需以实际请求结果或管理端 `supported_endpoint` 为准。不要将名字相似当成已经验证。

标准流式响应应分别解析 OpenAI 的 `choices[].delta`、Responses 的 `response.output_text.delta`、Anthropic 的 `content_block_delta` 和 Gemini 的 `candidates[].content.parts`。图片结果可能使用 URL 或 Base64；任务结果可能先返回任务 ID，之后查询。

`app/src/main/assets/gateway-endpoints.json` 提供 8 个标准协议的 JSON 请求模板，另以 `__unknown` 记录未确认的专用路由及 multipart 图片编辑要求。模板依据源码，不意味着已经向本实例逐模型实调。Gemini 图片模型应优先使用 Gemini 协议，并设置 `generationConfig.responseModalities` 为 `TEXT`、`IMAGE`；不能因它同时声明 `image-generation` 就跳过原生图片输出方式。

进一步阅读官方 Alibaba 插件确认 `qwen-image-3.0-pro` 在模型表中，且 `openai_image.decodeRequest` 接受 `model`、`prompt` 和 `n`，因此 Qwen 图片走 OpenAI 图片生成标准接口有源码依据。官方 Doubao 插件也明确实现 `openai_image` 和 `openai_video`；其视频 `decodeRequest` 支持带 `model`、`prompt` 的 JSON，请求返回 ID/任务状态，完成结果通过任务查询读取。

图片默认尺寸应随模型设置：官方 Doubao 插件给 `doubao-seedream-4-5-251128`、`doubao-seedream-5-0-260128` 的最小像素数为 3,686,400，因此 1024×1024 不满足要求。模板不强行指定尺寸，界面选择尺寸时应使用模型支持的数值。

## 视频与音频表单适配

`video-profiles.js` 已提供 Veo（含 Lite）、Seedance 1.5/2.0/Fast/2.5 和 Wan 3.0 的标准提交及任务查询模板，并提供须登录同步真实路径才启用的 MiniMax H3/H3 Max V2 模板；`audio-profiles.js` 已提供 MiMo 识别、合成、声音克隆、声音设计，MiniMax 语音合成以及 Seed Audio/Seed TTS 原厂请求模板。界面通过文字、附件及参数选项构造这些请求，不要求用户手写 API JSON。

Veo、Seedance 使用公开 rc.21 标准视频转换器；MiMo 按原厂协议使用标准聊天入口；MiniMax 合成使用 rc.21 `/v1/audio/speech` 转换器。Seed 的未知专用路径须先通过网关官方登录同步，不能用原厂 URL 猜测雷火路径。Wan 3.0 的最新 schema 有官方插件依据，但旧 rc.21 会丢失 `ratio`，因此其画面比例存在版本兼容限制。详情见 `video-routing.md`、`veo-routing.md`、`audio-routing.md`。

这些实现经过源码和官方文档核对，没有发送计费生成请求；它们证明请求模板有依据，不代表本实例所有模型均已实测成功。

## 专用路径仍需本实例资料的协议

| 模型系列 | 本实例声明的协议 |
| --- | --- |
| Seedance 2.0 / 2.5 | `seedance` |
| Veo 3.1 | `openai-video-gen` |
| MiniMax H3 | `create`、`query`、`query_by_task_id`、`delete`、`video_regeneration`、`h3_context_ir` |
| 腾讯混元 3D | `hunyuan-submit`、`hunyuan-fetch` |
| Tripo 生成 | `tripo-generation`、`tripo-task`、`tripo-upload` |
| Tripo 编辑 | `tripo-edit`、`tripo-task` |
| Jev | `typesafe-system-one` |
| MiMo TTS | `voice-generation` |
| Seed Audio | `openspeech_create` |
| Seed TTS | `openspeech_unidirectional` |
| MiniMax Speech | `minimax-t2a_v2`、`minimax-get_voice`、`minimax-delete_voice`、`minimax-files_upload`、`minimax-voice_clone` |

上表保留本实例声明的专用协议名；其中 Veo、Seedance、MiMo 合成、MiniMax 合成已有上一节的标准适配途径，但专用路径仍未通过匿名元数据取得。已同步路径来自官方登录后的 `supported_endpoint`。H3/H3 Max 原厂 V2 协议现已有准确资料，且 helper 仅在同步到提交和查询两条路径后启用；不能改用旧 Hailuo V1 转换器。3D、Jev 等缺少完整接入依据的能力，仍不能宣称已经可直接生成。`danqingyue` 的端点元数据为空，公开丹青约平台资料也不能证明该雷火 ID 的协议，见 `danqing-routing.md`。

资料不足时应在客户端说明缺失的接入信息，不让用户通过手写 JSON 完成常规音频、图片和视频流程。不要在收到生成错误后自动轮流发送多个协议请求；非幂等请求的重发可能重复创建生成任务。媒体下载使用响应中返回的资源地址，应避免将网关令牌转发给不同站点。

## 参考资料

- 本实例：`GET https://ai.leihuo.netease.com/v1/models`、`GET https://ai.leihuo.netease.com/api/status`。
- 本实例公开前端：`https://admin.ai.leihuo.netease.com/static/js/index.451f6f8ea0.js`，定价及模型详情共享组件 `static/js/async/1172.ae3d557169.js`。
- New API 默认端点：<https://github.com/QuantumNous/new-api/blob/main/common/endpoint_defaults.go>。
- New API 通用路由：<https://github.com/QuantumNous/new-api/blob/main/router/relay-router.go>。
- New API 视频路由：<https://github.com/QuantumNous/new-api/blob/main/router/video-router.go>。
- New API 视频请求/响应：<https://github.com/QuantumNous/new-api/blob/main/dto/video.go>。
- New API Alibaba 插件：<https://github.com/QuantumNous/new-api/blob/main/plugins/tasks/alibaba/plugin.js>。
- New API Doubao 插件：<https://github.com/QuantumNous/new-api/blob/main/plugins/tasks/doubao/plugin.js>。
- New API 官方文档：<https://docs.newapi.pro>。
