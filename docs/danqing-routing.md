# danqingyue 与 Seed 内部路由的只读检索

记录日期：2026-10-21。此次未发送模型生成请求。

## danqingyue

本实例 `/v1/models` 返回 `id: "danqingyue"`、`owned_by: "openai"`、`supported_endpoint_types: []`。`owned_by` 只能表示目录归属，不能单独证明该模型应使用聊天还是图片生成请求。

公开网易平台名称为“丹青约”，主页为 <https://danqing.163.com/>，网易伏羲资料描述它为图文多模态智能体。平台首页公开 JS 路径为 `/fuxi-static/danqing/main/1.9.2/assets/index-JT1DVfKe.js`。已只读查看主页与此 JS，未获得雷火模型 ID `danqingyue` 与该平台具体 API 的绑定依据。

GitHub 代码检索 `danqingyue`、`"danqingyue" "model"`、`"danqing" "netease"` 以及网易/QuantumNous 范围未找到相关模型协议；全局同名结果是历史过期域名列表。网页搜索能找到丹青约产品介绍，没有取得本实例 `/v1/images/generations` 或 `/v1/chat/completions` 的准确请求示例。这些检索结果只能说明当前没有取得依据，不能证明不存在内部文档或接口。

因此不根据名字把它视为普通生图模型，也不伪造图片或聊天提交模板。应保留目录项和明确的接入信息提示；下一步需取得登录后的该模型详情、内部接口文档或用户提供的官方请求例子。

## Seed 的模型/渠道选择

Seed Audio 1.0 和 Seed TTS 2.0 原厂 schema 已确认，详见 `audio-routing.md`。Seed TTS 的原厂请求没有顶层 `model`，版本通过 `X-Api-Resource-Id: seed-tts-2.0` 选择。

公开 rc.21 普通分发器从 JSON 顶层 `model` 读取模型，未注册 `openspeech_unidirectional` / `openspeech_create` 专用路由；该版本代码无法证明雷火 internal.9 专用路由的模型选择规则。官方模型版本请求头也不能独立证明雷火用同一请求头选择渠道。

公开雷火前端已下载的入口和异步组件没有 `selected-model` 或 `X-Api-Resource-Id` 的专门调用示例。GitHub 交叉检索 `"seed-tts-2.0" "leihuo"`、`"openspeech_create" "leihuo"`、`"X-Api-Resource-Id" "ai.leihuo.netease.com"`、`"openspeech_unidirectional"` 及 `"admin.ai.leihuo.netease.com"` 未取得精确示例。公开使用雷火的第三方客户端代码只展示标准 V1 聊天/图片用法，不能补证 Seed 专用行为。

客户端仅使用官方登录同步到的 Seed POST 路径和原厂 schema，没有增加没有依据的模型选择头。其内部渠道选择、账户权限及输出仍需本实例真实资料或实际返回确认；本次没有为验证而发送计费合成请求。

## 可复核来源

- 雷火模型目录：<https://ai.leihuo.netease.com/v1/models>
- 丹青约产品：<https://danqing.163.com/>
- 网易伏羲丹青约资料：<https://fuxi.163.com/tags/88>
- rc.21 普通分发器：<https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/middleware/distributor.go>
- rc.21 标准路由：<https://github.com/QuantumNous/new-api/blob/v1.0.0-rc.21/router/relay-router.go>
- Seed V3 单向流式原厂说明：<https://www.volcengine.com/docs/6561/1598757>
