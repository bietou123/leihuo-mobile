# 第三方组件

前端组件和原生运行时依赖随 APK 本地打包，不运行外网 CDN 脚本。

| 组件 | 版本 | 许可证 | 来源 |
| --- | --- | --- | --- |
| marked | 12.0.2 | MIT | https://github.com/markedjs/marked |
| DOMPurify | 3.2.7 | Apache-2.0 或 MPL-2.0 | https://github.com/cure53/DOMPurify |
| highlight.js | 11.11.1 | BSD-3-Clause | https://github.com/highlightjs/highlight.js |
| PDF.js | 3.11.174 | Apache-2.0 | https://github.com/mozilla/pdf.js |
| Mammoth | 1.9.1 | BSD-2-Clause | https://github.com/mwilliamson/mammoth.js |
| OkHttp (`com.squareup.okhttp3:okhttp`) | 4.12.0 | Apache-2.0 | https://github.com/square/okhttp/tree/parent-4.12.0 |
| Okio (`com.squareup.okio:okio`、`okio-jvm`) | 3.6.0 | Apache-2.0 | https://github.com/square/okio/tree/parent-3.6.0 |
| Kotlin 标准库 (`kotlin-stdlib`、`kotlin-stdlib-common`、`kotlin-stdlib-jdk7`、`kotlin-stdlib-jdk8`) | 1.9.10 | Apache-2.0 | https://github.com/JetBrains/kotlin/tree/v1.9.10 |
| JetBrains annotations (`org.jetbrains:annotations`) | 13.0 | Apache-2.0 | https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/ |
| Public Suffix List（OkHttp 随包的域名后缀数据） | 随 OkHttp 4.12.0 分发的快照 | MPL-2.0 | https://publicsuffix.org/list/public_suffix_list.dat |

分发组件保留其原始版权与许可证声明。完整许可证文本随 `app/src/main/assets/vendor/licenses/` 打包。

网络依赖版本按 Gradle 的 `releaseRuntimeClasspath` 解析结果记录；Okio 的传递依赖将 Kotlin 标准库统一解析为 1.9.10。

新增依赖的完整 Apache-2.0 文本见 `Apache-2.0-LICENSE.txt`，对应版权及来源见 `network-libraries-NOTICE.txt`。Public Suffix List 的原始上游声明见 `okhttp-public-suffix-NOTICE.txt`，完整 MPL-2.0 文本见 `public-suffix-list-LICENSE.txt`。

本项目未修改这些上游库及 OkHttp 的后缀数据。Public Suffix List 的源数据和历史版本可从 https://github.com/publicsuffix/list 获取；OkHttp 4.12.0 的处理代码和随包资源可从上表的版本链接获取。本项目的 MIT 许可证适用于本项目代码，各第三方组件遵循其各自许可证。
