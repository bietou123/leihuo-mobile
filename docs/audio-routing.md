# 音频请求与路径依据

研究日期：2026-10-21。仅阅读雷火既有模型目录、New API 官方源码及原厂公开文档，未向本实例发送语音识别、合成、克隆或声音设计请求。

## 雷火目录与路径确认范围

| 模型 | 雷火目录协议 | 有依据的请求方式 | 本实例确认范围 |
| --- | --- | --- | --- |
| `mimo-v2.5-asr` | `openai` | `POST /v1/chat/completions`，音频消息 | 目录声明 `openai`；New API 同版本标准路由和小米原厂请求格式均有依据，未实调 |
| `mimo-v2.5-tts-voiceclone` | `openai` | `POST /v1/chat/completions`，`audio.voice` 传音频样本 | 目录声明 `openai`；标准路由和原厂格式均有依据，未实调 |
| `mimo-v2.5-tts-voicedesign` | `openai` | `POST /v1/chat/completions`，`user` 消息传声音描述 | 目录声明 `openai`；标准路由和原厂格式均有依据，未实调 |
| `mimo-v2.5-tts` | `voice-generation` | 小米原厂为 `POST /v1/chat/completions` | 原厂路径和字段已确认；雷火 `voice-generation` 的内部路径尚无元数据或源码依据，未实调 |
| `speech-2.6-hd`、`speech-2.6-turbo`、`speech-2.8-hd` | `minimax-t2a_v2` 等专用协议 | New API 标准转换器可用 `POST /v1/audio/speech`；MiniMax 原厂为 `POST /v1/t2a_v2` | 标准转换器及原厂 OpenAPI 有源码依据；雷火内部版本是否仍走此转换器未实调，专用映射未确认 |
| `seed-audio-1.0` | `openspeech_create` | 原厂为 `POST /api/v3/tts/create` | 原厂字段已确认；客户端只使用登录后同步到的雷火协议路径，未实调 |
| `seed-tts-2.0` | `openspeech_unidirectional` | 原厂为 `POST /api/v3/tts/unidirectional` | 原厂字段和分块响应已确认；客户端只使用登录后同步到的雷火协议路径，未实调 |

网关请求使用用户设置的雷火地址与 `Authorization: Bearer <用户令牌>`。小米官方支持 `api-key` 和 Bearer 两种原厂认证；调用雷火时不另取或发送小米凭据。

New API 官方 `v1.0.0-rc.21` 的 [`router/relay-router.go`](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/router/relay-router.go#L69) 定义 `/v1` 路由组，并在 [L96](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/router/relay-router.go#L96) 注册 `/chat/completions`，在 [L125](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/router/relay-router.go#L125) 和 [L131](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/router/relay-router.go#L131) 分别注册 `/audio/transcriptions` 与 `/audio/speech`。这些是标准路由证据，不能据此把 MiMo 或 MiniMax 请求改成 OpenAI 音频接口；原厂模型使用的请求格式不同。

雷火管理前端从 `/api/pricing` 返回的 `supported_endpoint` 读取专用协议路径。当前用户 API 令牌不能读取该管理接口。公开代码搜索中 `minimax-t2a_v2`、`minimax-get_voice`、`openspeech_unidirectional` 未找到匹配；`voice-generation` 与 `supported_endpoint_types` 的组合也未找到匹配。搜索结果不足以确定雷火专用映射。

## MiMo 语音识别

依据：[小米 ASR API](https://mimo.mi.com/static/docs/api/audio/Speech-Recognition.md)、[小米 ASR 使用指南](https://mimo.mi.com/static/docs/quick-start/usage-guide/audio/Speech-Recognition.md)。

原厂地址为 `https://api.xiaomimimo.com/v1/chat/completions`。以下是请求正文；发送给雷火时保留模型目录里的实际模型 ID。

```json
{
  "model": "mimo-v2.5-asr",
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "input_audio",
          "input_audio": {
            "data": "data:audio/wav;base64,<音频Base64>"
          }
        }
      ]
    }
  ],
  "asr_options": { "language": "auto" },
  "stream": false
}
```

- 仅支持 WAV 和 MP3。MIME 类型分别为 `audio/wav`、`audio/mpeg` 或 `audio/mp3`。
- 也支持纯 Base64，但此时 `input_audio` 必须同时包含 `format: "wav"` 或 `"mp3"`。
- 官方限制为编码后的字符串不超过 10 MB；客户端可保守地按发送的完整 Data URL 长度限制。
- `asr_options.language` 可选 `auto`、`zh`、`en`。未设置时自动检测。
- 非流式文本读取 `choices[0].message.content`；流式按聊天 SSE 读取 `choices[0].delta.content`。

## MiMo 合成、声音设计与克隆

依据：[小米 TTS API](https://mimo.mi.com/static/docs/api/audio/tts.md)、[小米 TTS 使用指南](https://mimo.mi.com/static/docs/quick-start/usage-guide/audio/speech-synthesis-v2.5.md)。原厂三个 TTS 模型都使用 `https://api.xiaomimimo.com/v1/chat/completions`。

### 通用规则

- 需要朗读的文字放在 `role: "assistant"` 的 `content`。`user` 内容用于风格或声音描述，不会作为朗读正文。
- `audio.format` 支持 `wav`、`mp3`、`pcm`、`pcm16`。非流式默认 WAV；PCM 为 PCM16。
- 非流式返回 `choices[0].message.audio.data`，编码为 Base64，解码后才是音频文件字节。不要把整段 JSON 作为音频文件。
- 声音设计开启文本优化时，处理后的文本在 `choices[0].message.final_text_preview`。
- 流式音频读取 `choices[0].delta.audio.data`。官方示例为 24 kHz、PCM16LE、单声道；应使用 `audio.format: "pcm16"` 拼接音频。
- 普通 TTS 已支持低延迟流式；声音设计与克隆目前仅在全部推理完成后以流式格式一次返回结果。手机文件播放流程宜使用 `stream: false` 和 WAV。

### 内置声音

雷火 `mimo-v2.5-tts` 只声明 `voice-generation`，以下为原厂请求示例，不能视为其雷火内部路由已证实。

```json
{
  "model": "mimo-v2.5-tts",
  "messages": [
    { "role": "user", "content": "用自然、温柔的语气朗读，语速稍慢。" },
    { "role": "assistant", "content": "你好，这是语音合成示例。" }
  ],
  "audio": { "format": "wav", "voice": "mimo_default" },
  "stream": false
}
```

`user` 消息和 `audio.voice` 都可省略。官方音色为 `mimo_default`、`冰糖`、`茉莉`、`苏打`、`白桦`、`Mia`、`Chloe`、`Milo`、`Dean`；`mimo_default` 在中国集群默认为冰糖，其他集群默认为 Mia。

### 声音设计

```json
{
  "model": "mimo-v2.5-tts-voicedesign",
  "messages": [
    { "role": "user", "content": "年轻女性，普通话，声音明亮温柔，语速适中，像晚间电台主持人。" },
    { "role": "assistant", "content": "晚上好，欢迎收听今天的节目。" }
  ],
  "audio": { "format": "wav" },
  "stream": false
}
```

- `user` 声音描述为必填，支持中文或英文。
- 该模型不支持 `audio.voice`，无需音频样本或内置音色 ID。
- 可选 `audio.optimize_text_preview: true` 智能润色朗读文字；开启后可省略 `assistant` 消息，让模型生成朗读文字。正常朗读用户已有正文时可保留 `assistant` 并不开启优化。

### 声音克隆

```json
{
  "model": "mimo-v2.5-tts-voiceclone",
  "messages": [
    { "role": "user", "content": "自然、平稳地朗读。" },
    { "role": "assistant", "content": "你好，这是使用音频样本合成的语音。" }
  ],
  "audio": {
    "format": "wav",
    "voice": "data:audio/wav;base64,<声音样本Base64>"
  },
  "stream": false
}
```

- `audio.voice` 必填，其内容是声音样本的 Data URL，不是内置音色名称。
- 样本仅支持 WAV、MP3，Base64 编码后的字符串不超过 10 MB。
- 风格 `user` 消息可为空字符串或省略；`assistant` 朗读正文仍必填。
- 官方未在已读取页面给出样本时长或采样率硬性要求，不附加未经证实的限制。

## MiniMax 原厂字段

依据：[MiniMax 同步语音合成](https://platform.minimaxi.com/docs/api-reference/speech-t2a-http)、[官方 OpenAPI JSON](https://platform.minimaxi.com/docs/api-reference/speech/t2a/api/openapi.json)。原厂地址为 `https://api.minimax.cn/v1/t2a_v2`，雷火 `minimax-t2a_v2` 路径尚未确认。

```json
{
  "model": "speech-2.8-hd",
  "text": "你好，这是语音合成示例。",
  "stream": false,
  "voice_setting": {
    "voice_id": "Chinese (Mandarin)_Lyrical_Voice",
    "speed": 1,
    "vol": 1,
    "pitch": 0
  },
  "audio_setting": {
    "sample_rate": 32000,
    "bitrate": 128000,
    "format": "mp3",
    "channel": 1
  },
  "output_format": "hex"
}
```

- `text` 长度需小于 10,000 字符；超过 3,000 字符官方推荐流式。
- `voice_setting.voice_id` 为音色 ID。上例 ID 来自官方 OpenAPI；实际账户支持的音色以音色列表或查询结果为准。
- `speed` 范围 `[0.5, 2]`，`vol` 范围 `(0, 10]`，`pitch` 范围 `[-12, 12]`。
- `output_format` 支持 `hex` 或 `url`，默认 `hex`；流式只支持 `hex`，非流式 URL 有效期 24 小时。
- 先检查 `base_resp.status_code` 为 `0`，再读取 `data.audio`；HEX 需转换为字节，不能按 Base64 解码。官方响应允许 `data` 为 `null`。

## MiniMax 的 New API 标准转换器

New API `v1.0.0-rc.21` 同时提供了 `/v1/audio/speech` 到 MiniMax 原厂接口的适配器，因此有一种无需猜测专用路径的标准请求格式。

来源：[`relay/channel/minimax/adaptor.go`](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/minimax/adaptor.go)、[`relay/channel/minimax/relay-minimax.go`](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/minimax/relay-minimax.go)、[`relay/channel/minimax/tts.go`](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/minimax/tts.go)。

- `ConvertAudioRequest` 将 `input` 转为 `text`，`voice` 转为 `voice_setting.voice_id`，`speed` 转为 `voice_setting.speed`。
- `response_format` 同时初始化 `audio_setting.format` 与 `output_format`；随后把 `metadata` 解到 MiniMax 请求对象上，覆盖对应原厂字段。
- `GetRequestURL` 在 `RelayModeAudioSpeech` 模式下选择上游 `/v1/t2a_v2`。
- `handleTTSResponse` 检查 MiniMax 错误码，解码 HEX 后返回音频字节，源码固定使用 `Content-Type: audio/mpeg`；如果上游返回 URL，则返回 302 重定向。

标准网关路径：`POST /v1/audio/speech`。为了使转换后原厂收到合法音频格式，应使用顶层 `response_format: "hex"`，再用 `metadata.audio_setting.format: "mp3"` 覆盖初始化格式，并显式设置 `metadata.output_format: "hex"`。

```json
{
  "model": "speech-2.8-hd",
  "input": "你好，这是语音合成示例。",
  "voice": "Chinese (Mandarin)_Lyrical_Voice",
  "speed": 1,
  "response_format": "hex",
  "metadata": {
    "audio_setting": {
      "format": "mp3",
      "sample_rate": 32000,
      "bitrate": 128000,
      "channel": 1
    },
    "output_format": "hex"
  }
}
```

上例音色 ID 来自 MiniMax 官方 OpenAPI，客户端也可传用户选择的有效音色 ID。该标准路径返回可播放的 MP3 字节，由服务端解 HEX，客户端无需再次解码。由于该版本响应固定标记为 `audio/mpeg`，此模板固定请求 MP3。

这证明官方同版本具备标准转换器；雷火报告的 `v1.0.0-rc.21-internal.9` 是否保留并选中该转换器，仍需本实例资料或实际调用结果确认。不能将标准转换器证据写成 `minimax-t2a_v2` 专用路径已经取得；发送失败时也不自动改走其他路径重发。

## Seed Audio 1.0 与 Seed TTS 2.0

来源为火山引擎公开文档：[Seed Audio 非流式音频生成](https://www.volcengine.com/docs/6561/2550782)、[TTS V3 单向流式 HTTP 接口](https://www.volcengine.com/docs/6561/1598757)、[官方音色列表](https://www.volcengine.com/docs/6561/1257544)。原厂接口使用 `openspeech.bytedance.com`，客户端不以原厂路径代替雷火未知路径。

`audio-profiles.js` 只在相应 `endpointMap` 记录带 `synced: true`、`method: "POST"` 且 `path` 为合法本地相对 URI 时返回 Seed 模板。未同步路径、完整外部 URL、协议相对 URL、反斜杠或目录穿越路径都会返回 `null`。

### Seed Audio 1.0

原厂请求路径为 `/api/v3/tts/create`。目前客户端支持纯文本生成，无需附件，正文结构为：

```json
{
  "model": "seed-audio-1.0",
  "text_prompt": "生成海浪轻轻拍打岸边的声音。",
  "audio_config": { "format": "mp3", "sample_rate": 44100 }
}
```

`model` 保留用户选择的实际模型 ID，提示词最多 3,000 字符。官方还支持参考资源和音色等字段，本模板仅实现纯文本输入。单次最长输出 120 秒；返回为单个 JSON，其顶层 `audio` 是 Base64 编码音频，`url` 是有效期两小时的音频地址，`code`、`message` 表示状态。

### Seed TTS 2.0

原厂请求路径为 `/api/v3/tts/unidirectional`，也提供 `/api/v3/tts/unidirectional/sse`。资源版本通过 `X-Api-Resource-Id: seed-tts-2.0` 请求头选择；客户端将该值设为目录中实际选择的模型 ID，不另加私有模型选择头。请求正文没有顶层 `model`：

```json
{
  "user": { "uid": "leihuo-mobile" },
  "req_params": {
    "text": "你好，欢迎使用语音合成。",
    "speaker": "zh_female_vv_uranus_bigtts",
    "audio_params": { "format": "mp3", "sample_rate": 24000 }
  }
}
```

客户端音色选项来自官方 2.0 音色列表：Vivi 2.0 为 `zh_female_vv_uranus_bigtts`，小何 2.0 为 `zh_female_xiaohe_uranus_bigtts`，云舟 2.0 为 `zh_male_m191_uranus_bigtts`，小天 2.0 为 `zh_male_taocheng_uranus_bigtts`。此处不混用 1.0 或 S2S 音色。

`X-Api-Resource-Id` 的文档证据只证明原厂版本选择，不能证明雷火内部用它选择模型渠道。公开 rc.21 的普通 JSON 分发器只读顶层 `model`，且没有这些 Seed 专用路由；雷火 internal.9 专用路由是否有独立选择逻辑尚未取得证据。网关公开前端也未提供专门 Seed 请求例子，因此没有补造 `selected-model` 请求头。同步到真实提交路径后可以提供原厂模板，但仍不能将其写成已成功调用的模型。

请求模板返回 `audioChunkStream: true` 和 `audioMime: "audio/mpeg"`，由原生网络层解析 HTTP 分块 JSON 或 SSE。`code: 0` 的 `data` 包含 Base64 音频分块，也可能为空并只含句子信息；`code: 20000000` 表示合成成功结束。应逐块解码为字节后按顺序写入音频文件，不能拼接多个 JSON 或直接拼接带填充的 Base64 字符串。其他状态码须显示 `message`，不自动重发。

已读取的 TTS 2.0 文档没有给出全量文字的固定字符上限，因此客户端不额外编造该限制；官方 `40402003` 表示超过实际服务限制。

## 客户端实现注意事项

MiMo 的识别和克隆样本需要上传真实音频文件，并按文件内容编码。系统录音返回的 M4A/AAC 不属于这两种模型已声明的输入格式，应录制或转换为 WAV/MP3，再发送一次请求。选错格式应在本地显示明确错误，不尝试多个生成协议或自动重发。

对于没有雷火路径依据的协议，可先保留原厂参数表单与路径设置。只有配置中的路径来自管理元数据或用户明确给出的雷火文档时，才将其标记为本实例已配置；生成成功与否仍以真实调用结果为准。
