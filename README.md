# 雷火手机 App

Android 安装版，界面参考 ChatGPT 手机版。连接网易雷火网关，模型列表从 `/v1/models` 动态更新；内置目录包含 2026-10-21 返回的全部 88 个模型。

本项目是独立开发的非官方客户端，与网易、雷火及 OpenAI 无隶属关系。服务商和产品名称用于说明兼容的服务；界面与图标为独立实现。

## 使用

1. 从 [GitHub Releases](https://github.com/bietou123/leihuo-mobile/releases/latest) 下载 APK，或安装本机构建后放在桌面的 `雷火.apk`。要求 Android 8.0 或更新版本。
2. 首次打开时填写雷火 API Key，API 地址默认 `https://ai.leihuo.netease.com`。
3. 点顶部模型名称切换模型。图片模型输入描述生成图片；MiMo/MiniMax 音频模型输入朗读文字生成声音；Veo/Seedance/Wan 视频模型输入描述生成视频。Seed 音频和 MiniMax H3/H3 Max 需要先完成下一步中的接口同步。
4. 需要识别音频或克隆声音时上传 WAV/MP3；底部的声音设置可选音色、朗读风格等。视频设置可选时长、比例和清晰度，完成后直接播放。
5. Seed Audio / Seed TTS 和 MiniMax H3/H3 Max 需要先进入设置，点“登录雷火并同步更多接口”。在 `https://ai.leihuo.netease.com/usercenter` 完成官方登录后点“读取接口”，读取成功后点“完成”返回。如果用户中心未返回映射，App 会打开官方管理页供继续登录读取。官方网页登录用于读取专用接口；模型调用仍使用第 2 步填写的 API Key。
6. 侧栏保存历史对话。底部加号可添加音频、照片、PDF、Word（docx）和文本文件。

支持流式回复、思考过程、Markdown/代码、停止、重新生成、编辑、复制、手机语音输入与朗读、模型搜索与收藏、深浅主题、对话导入与导出。生成的图片可保存到手机；支持声明图片编辑能力的模型上传图片编辑。

### 1.2.2 VPN 网络恢复与视频续查

针对 VPN 已连接但手机无法解析官方网关域名的情况，原生请求增加备用 HTTPS DNS 和有期限的私有地址缓存。优先使用系统 DNS，仅对雷火官方网关启用备用解析；保持原 HTTPS 域名和证书校验，不在 APK 中预置网关 IP。手机的 VPN 仍需能够访问网关所在网络。

视频任务的 GET 查询遇到短暂网络问题时，最多额外重试 3 次，等待上限逐步增加到 90 秒；生成 POST 不自动重发。复制错误信息现在包含真实 HTTP 状态、错误来源、净化后的原因和查询重试次数。未收到 HTTP 响应时会明确说明。

覆盖安装后打开原视频消息，点“继续查询视频”；已完成的任务可点“重新读取视频”。原任务编号会保留。本轮只读查询的已有任务在网关返回成功及 MP4 产物，手机播放尚未验证。网络恢复与诊断细节见 [docs/network-recovery.md](docs/network-recovery.md)。

### 1.2.1 视频、图片和音频兼容修复

修复视频产物被封面或输入链接替代、同源视频内容接口缺少鉴权，以及部分 CDN 使用 `application/octet-stream` 导致播放器停在 0:00 的客户端问题。视频任务按已确认的结果字段读取；已完成的历史任务也提供“重新读取视频”，只查询原任务，不重新提交生成。播放失败会显示提示，支持复制不包含密钥、Cookie 或媒体签名链接的错误信息。

视频模板覆盖 Veo、Seedance、Wan 和 MiniMax H3/H3 Max，并遵循登录同步的已确认路由。Seedance 原厂任务路径可从已同步的 Ark 集合接口取得对应 GET 查询；未知私有路由在提交前提示。

图片请求补齐 Gemini 全部参考图、Seedream 的 `image`、Qwen 的原厂 `input`，图片面板尺寸写入标准图片请求。音频补齐 MiMo 声音设计的预览文字，修复音频 URL 被误当作 Base64。原生媒体读取和图片保存按文件头兼容常见图片、音频、MP4 和 WebM 的二进制响应，保留 Range 播放与拖动。

本轮完成源码静态审查和 release 构建，未运行模型生成或逐型号计费实测，也未在手机上验证此次修复。接口转换依据与部署限制见 [docs/image-routing.md](docs/image-routing.md)、[docs/audio-routing.md](docs/audio-routing.md) 和 [docs/video-routing.md](docs/video-routing.md)。

### 1.2.0 综合记忆

默认开启“综合记忆”。升级后会自动汇入已有对话，之后各个聊天窗口里的用户文字、AI 回复和已提取的文件文字都会进入同一份记忆。新建对话、切换历史窗口或更换聊天模型时，会根据当前问题带入相关记忆；不用每次重新介绍自己。

进入 **设置 → 综合记忆 → 管理综合记忆**，可以搜索已有记忆、删除单条、补充长期信息、导出或清空。综合记忆独立保存在手机上，删除聊天不会删除已保存的记忆；清空记忆后只自动记录新消息。关闭开关会暂停记录和引用，关闭期间的消息不会在重新开启后补录。

记忆保留有效聊天文字全文，单次回复按相关程度取用片段，不会把全部历史每次都发送。图片和音频只保存附件名称及聊天中的文字说明、识别回复；原始图片和音频不进入记忆库。正在生成、被中断或报错的 AI 回复，以及思考过程不会作为最终回复写入记忆。

记忆检索在本机完成，不额外调用模型做总结。被取用的记忆会随正常聊天请求发给当前模型，可能增加输入 token。记忆文件最大 32 MB，超限会提示整理并保留旧文件；不自动淘汰旧记忆。实现细节见 [docs/memory.md](docs/memory.md)。

### 1.1.1 图片上传

主聊天界面的“加号 → 照片”使用 Android 原生系统选图，支持一次选择多张图片；输入栏最多保留 5 张，每张原图不超过 15 MB。发送前按照片方向信息纠正方向，将长边缩至最多 1600 像素并转成 JPEG，降低上传和对话存储的占用。能否识别照片取决于所选模型的视觉能力。

用户已确认图片上传可用。综合记忆尚未经过手机真机验证；已有本地模拟检查的范围见下方“验证情况”。

### 媒体调用范围

1.1 版加入普通媒体的直接调用流程，并保存视频任务编号：停止等待、断网或重启后，可继续查询同一个任务。1.2.1 也允许重新读取已完成任务。表单模板识别 Veo、Seedance、Wan、MiMo 和 MiniMax Speech 模型家族；Seed 和 MiniMax H3/H3 Max 的专用路径从登录后的官方映射取得。H3 系列仅在同步到提交与单任务查询接口后启用，支持文字和单张首帧图片。使用默认时长时，Veo 为 8 秒，Seedance/Wan 为 5 秒，H3/H3 Max 为 10 秒、768p，可在发送栏设置。

3D、Jev、丹青约等能力仍需相应协议定义；没有定义的模型不会自动发送猜测的生成请求。Seed 的原厂参数和 H3 的 V2 契约已接入，但其雷火内部渠道选择和所有模型的实际生成结果尚未逐个验证。旧公开 rc.21 的 Wan 3 适配器会丢弃比例字段，内部网关是否修复仍待实际结果确认。源码证据见 [docs/gateway-api.md](docs/gateway-api.md)、[docs/audio-routing.md](docs/audio-routing.md)、[docs/video-routing.md](docs/video-routing.md) 和 [docs/h3-routing.md](docs/h3-routing.md)。模型权限和余额由你的雷火账号决定。

API Key 通过 Android Keystore 的 AES/GCM 加密后保存，原生网络层注入请求，不写入前端源码、APK、日志或 Git。对话和生成的音视频存放在应用私有目录；JSON 对话导出包含文字、任务编号及媒体引用，不打包本地音视频文件。安装包无账号预置，首次使用需要填写自己的 API Key。

## 从源码构建

安装 JDK 17 和 Android SDK，SDK 内需要 Android SDK Platform 35 与 Build Tools 35.0.0。设置 `JAVA_HOME` 指向 JDK，将 SDK 路径写入项目根目录的 `local.properties`（`sdk.dir=你的SDK绝对路径`），或设置环境变量 `ANDROID_HOME`。`local.properties` 不进入 Git。

仓库包含 Gradle 8.10.2 Wrapper。克隆仓库后，在项目根目录执行以下命令；首次构建会下载 Gradle 和 Android 构建依赖。

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

Linux / macOS：

```sh
./gradlew assembleDebug
```

APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。Debug 包使用开发签名，与 Releases 的签名不同。

前端第三方组件已经随源码保存在 `app/src/main/assets/vendor/`。需要重新下载固定版本组件时，可在 Windows PowerShell 执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/download-vendor.ps1
```

### Windows 独立签名构建

`scripts/build-apk.ps1` 可使用独立的本地工具目录构建 release 包，并将 APK 复制到桌面。默认目录为 `$env:USERPROFILE/.local/share/leihuo-build`，执行前需安装以下工具：

- `jdk/<JDK目录>/bin/java.exe`：JDK 17。
- `gradle/gradle-8.10.2/bin/gradle.bat`：Gradle 8.10.2。
- `android-sdk/`：Android SDK，包含 Platform 35 与 Build Tools 35.0.0。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-apk.ps1 -Output "$env:USERPROFILE/Desktop/雷火.apk"
```

工具位于其他目录时，通过 `-BuildRoot` 指定根目录。脚本只设置当前进程的环境变量；持久 release 签名保存在工具根目录的 `signing/` 下，不进入仓库。后续版本沿用该签名才能覆盖升级。发布前递增 `app/build.gradle` 中的 `versionCode`。

## 验证情况

先前的本地流程检查使用模拟原生响应，覆盖视频提交/查询/播放、断开后续查不重复提交、已完成视频内容获取、图片、MiMo/MiniMax 语音、ASR 音频上传、Seed 同步后的调用。H3/H3 Max、综合记忆及 1.2.1、1.2.2 的后续实现经过源码静态核对，尚未纳入该模拟检查；本轮没有添加或运行测试。没有进行综合记忆或此次修复的真机验证、计费模型调用；模拟检查不能代替真实模型调用。

```powershell
npm install --prefix "$env:LOCALAPPDATA/leihuo-web-check" jsdom@24.1.3 --ignore-scripts --no-audit --no-fund
node scripts/check-media-flow.mjs
```

## 许可证

项目原创源码采用 [MIT 许可证](LICENSE)。第三方 JavaScript 组件、字体和原生网络依赖等保留各自的许可证，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 及 `app/src/main/assets/vendor/` 中随组件分发的许可证文件。
