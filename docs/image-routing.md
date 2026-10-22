# 图片附件、尺寸与结果解析

更新日期：2026-10-22。对应客户端版本：1.2.1，实现位于 `app/src/main/assets/app.js`。

本轮修复图片附件被遗漏、Gemini 多图仅发送首张，以及图片面板选定尺寸未写入部分请求的问题。接口及转换格式以公开 `New API v1.0.0-rc.21` 源码为依据；雷火报告版本为 `v1.0.0-rc.21-internal.9`，内部改动、所选渠道以及模型特定限制仍以实际返回为准。本轮只读源码并修改客户端，没有提交任何计费图片生成或编辑请求。

## 模型与请求方式

模型目录保持动态；保留实际模型 ID，根据其声明的协议和已经确认的模型家族构造请求。

| 模型家族 | 客户端请求 | 附件字段 |
| --- | --- | --- |
| Seedream 4.0 / 4.5 / 5.0 | `POST /v1/images/generations`，JSON | 单张 `image` 字符串；多张 `image` 数组 |
| Qwen Image | `POST /v1/images/generations`，JSON | `input.messages[].content` 中的 `image` 项 |
| Gemini 图片模型 | `POST /v1beta/models/{model}:generateContent` | 每张图片一个 `inlineData`，同时保留文字项 |
| 声明 OpenAI 图片编辑协议的模型 | `POST /v1/images/edits`，multipart | 单张 `image`；多张 `image[]` |
| 使用 OpenAI 聊天协议生成图片的模型 | `POST /v1/chat/completions`，JSON | 用户 `content` 中逐张加入 `image_url` 项 |

App 的常规图片选择流程将附件转成 JPEG Data URI。构造请求时发送所有选定图片；不支持现有图片编辑格式的模型在提交前提示并保留附件，避免静默忽略附件后创建纯文字生成任务。失败后不会自动换接口重新提交。

## Seedream：保留 `image`

rc.21 的 `dto/openai_image.go` 明确定义 `ImageRequest.Image` 为 `json.RawMessage`，既可接受单个 JSON 字符串，也可接受字符串数组；其自定义序列化会保留这一已定义字段。`relay/channel/volcengine/adaptor.go` 在图片生成模式直接返回该请求对象，随后发往上游 `/api/v3/images/generations`；源码同时注明豆包图生图也使用这一原厂入口。

因此原有标准 JSON 生成入口可直接携带选定参考图，无需增加未经确认的上传步骤：

```json
{
  "model": "doubao-seedream-4-5-251128",
  "prompt": "保持主体，改成窗边的暖色光线",
  "n": 1,
  "size": "2048x2048",
  "image": "data:image/jpeg;base64,<图片Base64>"
}
```

多图时使用 `image: ["data:image/jpeg;base64,...", "data:image/jpeg;base64,..."]`。这确认客户端到公开转换器的字段传递方式，不证明本实例已经逐型号验证图片数量、图像内容或生成结果。

## Qwen：构造原厂 `input`

rc.21 的 Ali 生成转换器 `oaiImage2AliImageRequest` 从 `ImageRequest.Extra["input"]` 读取原厂输入。`ImageRequest.UnmarshalJSON` 将未知顶层字段存入 `Extra`；图片处理器使用 `common.DeepCopy` 复制请求，该函数采用 `jinzhu/copier` 深拷贝，所以此处的 `input` 会保留到转换器。已有原厂输入时，转换器保留它，不再建立默认的纯文字输入。

`relay/channel/ali/dto.go` 定义 `AliMediaContent.Image` 与 `Text`；同版本的 multipart 编辑转换器也逐张把图片转为 Data URI，再构造用户 `messages/content`。相应最小 JSON 结构为：

```json
{
  "model": "qwen-image-3.0-pro",
  "prompt": "保持主体，改成窗边的暖色光线",
  "n": 1,
  "size": "1024x1024",
  "input": {
    "messages": [{
      "role": "user",
      "content": [
        {"image": "data:image/jpeg;base64,<图片Base64>"},
        {"text": "保持主体，改成窗边的暖色光线"}
      ]
    }]
  }
}
```

每张选定图片对应一个 `image` 项。标准顶层 `image/images` 不会被这个 Ali 生成转换器自动转成参考图，因此 Qwen 使用上述 `input`。

`setting/model_setting/qwen.go` 默认同步模型配置包含 `qwen-image`，用名称包含关系匹配；Ali 适配器据此选择 `/api/v1/services/aigc/multimodal-generation/generation`。这提供同版本默认路由依据；部署的渠道映射和同步模型设置可改变具体上游行为。示例演示转换格式，型号实际允许的参考图数量、尺寸及编辑能力未计费实测。

## Gemini：逐张写入 `inlineData`

图片生成正文的 `contents[0].parts` 由文字项和每张图片的独立 `inlineData` 项组成：

```js
parts: [
  {text: prompt},
  ...files.map(file => ({
    inlineData: {mimeType: 'image/jpeg', data: file.data.split(',')[1]}
  }))
]
```

保留既有 `generationConfig.responseModalities: ['TEXT', 'IMAGE']`。1.2.1 修复先前只读取 `files[0]`、丢掉其余图片的客户端问题。图片模型的多图上限仍由所选模型决定。

## 图片面板尺寸

对已有尺寸选项且使用标准图片请求的已适配模型，图片面板选择应写入 `body.size`；不能仅在模板预先存在 `size` 时才赋值。由此补齐 Seedream 4.0 和 Qwen 等模板的参数传递。Gemini 使用自己的生成配置，面板隐藏这个标准尺寸字段。

rc.21 `ImageRequest.Size` 是直接保留的标准字段；Volcengine 请求会携带它。Ali 转换器没有原厂 `parameters` 覆盖时，将顶层 `size` 中的 `x` 转为 `*`，并把顶层 `n` 转成原厂参数。Seedream 4.5 / 5.0 维持现有客户端的 `2048x2048` 选项；其他选项须遵循所选模型的实际限制。

## 结果与兼容边界

图片结果保留 OpenAI `data[].url`、`data[].b64_json`、Gemini `candidates[].content.parts[].inlineData` 和已确认的模型输出结构。综合媒体结果抽取区分输入素材、结果和元数据，防止参考图或其他辅助地址被展示成生成结果。

参数传递修复和公开转换器证据不等同于生成成功。登录同步、账户权限、所选渠道、额度及原厂内容限制仍可能影响调用；没有执行本实例的逐型号收费验证。

## 固定版本来源

- [图片请求 DTO、`Image/Size/Extra` 解析与序列化](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/dto/openai_image.go)
- [标准图片处理器及转换调用链](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/image_handler.go)
- [请求深拷贝实现](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/common/copy.go)
- [Volcengine 图片请求转换与原厂入口](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/volcengine/adaptor.go)
- [Ali 生成及编辑请求转换](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/ali/image.go)
- [Ali 图片原厂字段结构](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/ali/dto.go)
- [Ali 同步图片路由选择](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/relay/channel/ali/adaptor.go)
- [Qwen 默认同步模型设置](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/setting/model_setting/qwen.go)
- [标准图片与 Gemini 路由](https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/router/relay-router.go)

上述转换器核对使用固定公开标签，没有依赖持续变化的 `main` 推断本部署的图片附件处理方式。
