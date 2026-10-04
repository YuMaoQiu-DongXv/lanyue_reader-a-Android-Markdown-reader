# 蓝阅 · Lanyue

[中文](README.md) · [English](README.en.md)

> 你曾因为想在手机上打开一个 `.md` 文档，翻遍应用商店却找不到一个顺手的阅读器而抓狂吗？
> 或者被那些动辄上百 MB、打开先弹会员、读到一半插广告的"阅读器"搞得心力交瘁？
> **蓝阅就是为了终结这些破事而生的。**

![APK](https://img.shields.io/badge/APK-481%20KB-blue)
![权限](https://img.shields.io/badge/%E6%9D%83%E9%99%90-%E6%97%A0-brightgreen)
![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![许可](https://img.shields.io/badge/license-MIT-blue)

**481 KB。零权限。零网络。** 该有的却一样不少：数学公式、代码高亮、表格、图片、目录、搜索、
阅读位置记忆、轻量编辑、导出 PDF 与长图。

它有多大？——**差不多是你手机里随手一张截图的五分之一**。

| | 体积 |
|---|---|
| 蓝阅 | **481 KB** |
| 随手一张手机截图 | 1 ~ 3 MB（比它大 2~6 倍） |
| 常见的"全能阅读器" | 30 ~ 120 MB（比它大 60~250 倍） |

## 谁不该用蓝阅

先把话说在前面，省得你白装一次：

- 你要多标签、云同步、插件市场、AI 续写、协作批注 → **这些都没有，别装**。
- 你要把它当在线文档编辑器用 → 蓝阅只干一件事：把本地 `.md` 好好显示出来。
- 你介意"功能少" → 它确实只做一件事，但这件事做得很扎实。

## 它到底能干什么

- **数学公式**：KaTeX 全字体集 + mhchem 化学式，`$…$` `$$…$$` `\(…\)` `\[…\]` 四种分隔符都认，
  段落中间夹着的 `$$…$$` 也能正确拆出来；公式里的 `*` `_` 不会被当成强调语法吃掉。
- **代码高亮**：Prism 内置 19 种语言（含 GLSL / Rust / Go / SQL / YAML / Bash），带语言标签与一键复制，
  长行横向滑动不撑破版面。
- **GFM 全套**：表格（超宽可横滑）、任务列表、删除线、脚注、自动链接、`==高亮==`、上下标、front matter 信息卡。
- **图片**：支持同目录相对路径、文件名含空格与中文；4K 截图自动按屏宽降采样（否则 75 张图能吃掉 500 MB 内存）；
  点开可双指缩放、拖动、双击放大。
- **阅读体验**：目录抽屉、全文搜索跳转、字号调节、阅读位置记忆、浅色/深色/跟随系统三态。
- **编辑**：原生编辑器 + 一排 Markdown 格式按钮，自动保存、退出保存提示、只读文件自动转"另存为"。
- **导出**：PDF（走系统打印，A4 可调边距）、长图（自动分片，避免一张 290 MB 的位图）、当前屏、选定区域，都能分享。
- **编码兜底**：UTF-8 / UTF-16 / GB18030 自动识别，保存统一写 UTF-8。

## 安装

APK 在 [Releases](../../releases) 页下载，传到手机点开即可（首次需允许"未知来源"）。

装完可以看一眼权限列表——**它是空的**。没有网络权限、没有存储权限，飞行模式下功能完全不受影响：
解析器、公式引擎、高亮、字体全都打在 APK 里，它从来不需要联网。

## 从源码构建

```bash
bash build.sh          # 免 Gradle 一键构建（aapt2 + javac + d8 + zipalign + apksigner）
bash build.sh check    # 只审计已有产物（体积/权限/条目/签名/对齐）
bash build.sh clean    # 清理
```

产物：`dist/lanyue-1.0.0-release.apk`

| 组件 | 要求 | 默认探测路径 |
|---|---|---|
| JDK | 17+（d8 的 jar 是 class file 55，用 JDK 8 必崩） | `C:\Program Files\Microsoft\jdk-17*` |
| Android SDK | build-tools 36.0.0 + platforms/android-36 | `D:\Android\Sdk` |
| python | 仅用于修正 APK 内 assets 条目名的反斜杠（Windows 版 aapt2 的坑） | PATH |

可用环境变量覆盖：`JDK_HOME` / `SDK_ROOT` / `BUILD_TOOLS_VERSION` / `PLATFORM_DIR_NAME`。
Linux/macOS 下把路径指对、去掉脚本里的 `.exe` 后缀即可。

> 构建脚本里塞满了血泪注释：Windows 版 aapt2 会把子目录 assets 的条目名写成反斜杠（不解就是整个前端 404）、
> `d8.bat` 会捡 PATH 里的 Java 8 用、`classes.dex` 注入必须写成 `jar ufM -C`、keystore 不能放在会被清理的目录里……
> 想自己搭这条链的话，值得一读。

## 聊聊"为什么能这么小"

体积构成（实测）：

| 组成 | 进 APK | 占比 |
|---|---|---|
| KaTeX 字体（20 个 woff2） | 254 KB | **53%** |
| KaTeX JS/CSS + mhchem | 88 KB | 18% |
| Java 代码（classes.dex） | 36 KB | 7% |
| 手写 UI（HTML/CSS/JS） | 26 KB | 5% |
| Prism + marked | 37 KB | 8% |
| 签名、资源、图标、许可 | 40 KB | 8% |

也就是说：**体积的 53% 是公式字体，其余全部加起来不到 230 KB**。
能压到这个量级，靠的是三个取舍——

1. **不用 AndroidX / Material / Kotlin**：整个 Java 层只有 9 个文件、零第三方运行时依赖，
   界面全部手写在 WebView 里。这也是它敢承诺"零权限、零网络"的底气。
2. **不用 Gradle**：直接用 aapt2 + javac + d8 + apksigner 手工串起来，构建脚本 300 行，
   没有 Gradle 守护进程、没有依赖下载，冷启动构建十几秒。
3. **不自带浏览器内核**：复用系统 WebView，而不是塞一个 40 MB 的 Chromium。

## 渲染层的坑（都是踩过才知道的）

1. **数学必须先用占位符换掉**：把 `$…$` 原样交给 marked 是不够的——marked 仍会把公式里的 `*` `_`
   当成强调语法。一篇讲黑洞的文章里 `$Projection*Rotation*Translation$` 就被吃成了 `<em>Rotation</em>`。
   所以数学先换成 `%%LMDn%%`，marked 之后再还原。
2. **`\(…\)` / `\[…\]` 会被吃掉反斜杠**：marked 把 `\(` 当转义序列，渲染前统一归一化成 `$…$` / `$$…$$`。
3. **段落中间的 `$$…$$`** 前后得补空行，否则会和正文挤进同一个 `<p>` 里。
4. **KaTeX 默认不信任 `\color`**：作者用 `$\color{red}{…}$` 做重点标记会整段变成红色报错原文。
   解决办法是给 `trust` 传白名单回调，只放行上色命令，仍然拒绝 `\href` / `\url`。
5. **行内元素上的 `text-overflow: ellipsis` 是无效的**：`<span>` 是行内元素，长文件名会直接冲出卡片——
   首页"最近打开"的排版事故就是这么来的。
6. **超大图会让 GPU 分块光栅掉链子**：给 WebView 喂 4K 原图，放大后图层宽度上万像素、远超纹理上限，
   表现是"矩形缺失 + 一卡一卡"。解法是限制查看档分辨率 + 按纹理预算收敛缩放上限 + `translate3d` 提升合成层。

## 验证状态

它不是一个"能跑就行"的玩具，每一层都有可复现的自查手段（`tools/rendertest/` 下全是无头 Chromium 断言页）：

| 层次 | 手段 | 结果 |
|---|---|---|
| 渲染流水线 | `test.html`，77 条断言 | **77/77 通过** |
| 公式与字体 | `formula.html` + `document.fonts` 探针 | 12/12 字体族加载，着色命令正确，非法公式降级不崩 |
| 代码高亮 | `code.html`（含**计算颜色**断言） | 10 种 token 颜色，GLSL/JS/Python/JSON/YAML 正确 |
| 布局 | `layout.html`（五种宽度 + 首页几何） | 320/360/393/412/600px 无横向溢出，长文件名正确截断 |
| 图片查看器 | `zoom.html`（合成 PointerEvent 驱动） | **28/28 通过**：双指缩放锚点、边界钳制、双击还原、换图不跳变 |
| 真实样本 | 一篇 8 万字节的黑洞 GLSL 文章 | 75 图 / 37 个代码块 / 52 处公式 / 23 个标题 |
| 构建产物 | `aapt2 dump badging` + `unzip -l` + `apksigner verify` | 零权限、51 个 assets、签名与对齐全过 |
| 真机 | Android 16（HONOR 100）实测 3 轮 | 修复 8 个真机问题，逐条清单见 [`真机自查清单.md`](真机自查清单.md) |

## 已知限制（诚实清单）

- **Big5 / Shift_JIS 会被当成 GB18030**：解码器太宽容，无法区分，这类文件会整篇乱码。
- **短的 GBK 文档可能被误判为 UTF-8**：判定阈值刻意偏保守——把 UTF-8 误判成 GB18030 是整篇乱码（灾难），
  反过来只是个别字符变 `�`（可读）。
- **单个文档上限 32 MB**，且读取在主线程完成（2 MB 文档约十几毫秒）。
- **PDF 走系统打印链路**，不内置 PDF 生成器（省掉了数 MB 依赖）；打印界面里选"另存为 PDF"即可。
- **同一文件经两种入口打开会产生不同的位置记忆键**（`document/` 入口 vs 已授权 `tree/` 入口）。
- **没有 mermaid**：图表降级为代码块显示（不内置就是为了不膨胀体积）。
- **覆盖保存是截断写**：SAF 不支持原子替换，写入中途异常可能留下被截断的文件。

## 目录结构

```
app/
  AndroidManifest.xml          零权限 + .md 关联 + ShareProvider
  java/com/dsh/mdreader/       9 个 Java 文件（无 AndroidX、无 Kotlin）
    MainActivity.java          单 Activity：WebView 浏览 + 原生编辑器 + Insets
    AppBridge.java             @JavascriptInterface 桥（window.Lanyue）
    AssetServer.java           https://appassets.local 全量就地应答（资源/正文/图片）
    ImageLoader.java           图片分档降采样（显示/查看/导出）+ LRU
    FileGate.java              SAF 打开/另存/目录授权/相对图片解析
    CodecGate.java             UTF-8 / UTF-16 / GB18030 探测
    Exporter.java              PDF(PrintManager) / 分片长图 / 选区 / 分享
    ShareProvider.java         极简 ContentProvider（只暴露 cacheDir/share）
    Settings.java              SharedPreferences
  assets/web/                  HTML/CSS/JS 渲染层（含 vendor：marked/KaTeX/mhchem/Prism）
  res/                         矢量图标、深浅两套主题、中英文案
testdoc/                       专门打靶用的验收文档（图片/代码/公式/表格/导出各一节）
tools/
  rendertest/                  无头自查页：断言 / 公式 / 代码 / 布局 / 手势
  INTERFACES.md                Java 模块接口（冻结版）
真机自查清单.md                 逐条验收步骤
SPEC.md                        设计说明书（含体积预算、真机问题根因与修复记录）
```

## 第三方组件与许可

本项目不分发任何第三方运行时依赖，但内嵌了三个前端库的发行文件，均为 MIT：
[marked](https://github.com/markedjs/marked) 18.0.14 · [KaTeX](https://github.com/KaTeX/KaTeX) 0.19.0 ·
[Prism](https://github.com/PrismJS/prism) 1.30.0。许可原文随源码放在 `app/assets/web/vendor/*/LICENSE`，
说明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

本仓库自身采用 [MIT 许可](LICENSE)。

---

**如果它帮你省下了手机上那几百 MB，点个 ⭐ 就够了。**
