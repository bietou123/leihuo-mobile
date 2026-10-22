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

### 1.1.1 更新

主聊天界面的“加号 → 照片”使用 Android 原生系统选图，支持一次选择多张图片；输入栏最多保留 5 张，每张原图不超过 15 MB。发送前按照片方向信息纠正方向，将长边缩至最多 1600 像素并转成 JPEG，降低上传和对话存储的占用。能否识别照片取决于所选模型的视觉能力。

本次选图修复尚未经过手机真机验证。已有本地模拟检查的范围见下方“验证情况”。

### 媒体调用范围

1.1 版加入普通媒体的直接调用流程，并保存视频任务编号：停止等待、断网或重启后，可继续查询同一个任务。表单模板覆盖 3 个 Veo、4 个 Seedance、Wan 3.0、MiMo 和 MiniMax Speech；Seed 和 MiniMax H3/H3 Max 的专用路径从登录后的官方映射取得。H3 系列仅在同步到提交与单任务查询接口后启用，支持文字和单张首帧图片。使用默认时长时，Veo 为 8 秒，Seedance/Wan 为 5 秒，H3/H3 Max 为 10 秒、768p，可在发送栏设置。

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

先前的本地流程检查使用模拟原生响应，覆盖视频提交/查询/播放、断开后续查不重复提交、已完成视频内容获取、图片、MiMo/MiniMax 语音、ASR 音频上传、Seed 同步后的调用。H3/H3 Max 后续适配经过官方契约与源码静态核对，尚未纳入该模拟检查。未进行手机真机和计费模型调用测试；模拟检查不能代替真实模型调用。

```powershell
npm install --prefix "$env:LOCALAPPDATA/leihuo-web-check" jsdom@24.1.3 --ignore-scripts --no-audit --no-fund
node scripts/check-media-flow.mjs
```

## 许可证

项目原创源码采用 [MIT 许可证](LICENSE)。第三方 JavaScript 组件、字体等保留各自的许可证，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 及 `app/src/main/assets/vendor/` 中随组件分发的许可证文件。
