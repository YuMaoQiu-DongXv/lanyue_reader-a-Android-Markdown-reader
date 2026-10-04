# 蓝阅 · Lanyue

一个 **477 KB** 的纯离线 Android Markdown 阅读器。原生 WebView 渲染（marked + KaTeX + Prism 全部内嵌），
蓝白简洁 UI，轻量编辑，PDF 与分片长图导出，**零权限、零网络、零 AndroidX**。

- APK：**477 KB**（目标 < 1 MB，天花板 1.5 MB）
- 权限：**无**（安装后权限列表为空；不申请 INTERNET，飞行模式全功能可用）
- 最低版本：Android 8.0（minSdk 26），targetSdk 36
- 依赖：**没有第三方运行时依赖**——没有 AndroidX、没有 Kotlin、没有 Gradle
  （内嵌的 marked / KaTeX / Prism 均为 MIT，声明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)）

## 快速开始

```bash
bash build.sh          # 免 Gradle 一键构建（aapt2 + javac + d8 + zipalign + apksigner）
bash build.sh check    # 只审计已有产物（体积/权限/条目/签名/对齐）
bash build.sh clean    # 清理
```

产物：`dist/lanyue-1.0.0-release.apk`

环境依赖（`build.sh` 顶部可用环境变量覆盖：`JDK_HOME` / `SDK_ROOT` / `BUILD_TOOLS_VERSION` / `PLATFORM_DIR_NAME`）：

| 组件 | 要求 | 默认探测路径 |
|---|---|---|
| JDK | 17+（d8/dx 的 jar 是 class file 55，JDK 8 必崩） | `C:\Program Files\Microsoft\jdk-17*` |
| Android SDK | build-tools 36.0.0 + platforms/android-36 | `D:\Android\Sdk` |
| python | 仅用于修正 APK 内 assets 条目名的反斜杠（Windows 版 aapt2 的坑） | PATH |

> 本机开发时用的是 JDK 17 + Android SDK 36 + Git Bash（Windows）。Linux/macOS 下把 `JDK_HOME`、`SDK_ROOT` 指对即可，脚本里对 `.exe` 后缀的引用需要去掉。

## 目录结构

```
app/
  AndroidManifest.xml          零权限 + .md 关联 + ShareProvider
  java/com/dsh/mdreader/
    MainActivity.java          单 Activity：WebView 浏览 + 原生编辑器 + Insets
    AppBridge.java             @JavascriptInterface 桥（window.Lanyue）
    AssetServer.java           https://appassets.local 全量就地应答（资源/正文/图片）
    ImageLoader.java           图片按屏宽×2 降采样 + LRU（防 4K 图 OOM）
    FileGate.java              SAF 打开/另存/目录授权/相对图片解析
    CodecGate.java             UTF-8/GB18030 探测
    Exporter.java              PDF(PrintManager) / 分片长图 / 选区 / 分享
    ShareProvider.java         极简 ContentProvider（只暴露 cacheDir/share）
    Settings.java              SharedPreferences
  assets/web/                  HTML/CSS/JS 渲染层（含 vendor：marked/KaTeX/mhchem/Prism）
  res/                         矢量图标、深浅两套主题、中英文案
testdoc/
  蓝阅验收测试.md               专门打靶用的验收文档（图片/代码/公式/表格/导出各一节）
tools/
  INTERFACES.md                Java 模块接口（冻结版）
  jscheck.sh                   JS 语法闸门
  sync-render-test.sh          把 web 资源同步到 _work/render-test 做无头自查
  rendertest/                  桌面自查页：断言测试 / 公式字体 / 代码块 / 多宽度布局 / 手势查看器
真机自查清单.md                 逐条验收步骤
SPEC.md                       设计说明书（含体积预算与实测）
```

## 渲染层设计要点（都是踩坑后定下来的）

1. **保护区掩码**：围栏代码 / 行内代码 / 四种公式分隔符整段隔离，文本级改写只发生在自由区。
2. **数学用占位符，不用原样透传**：即便把 `$...$` 原样交给 marked，marked 仍会把里面的 `*` `_`
   当成强调语法——样本文档的 `$Projection*Rotation*Translation$` 就被吃成了 `<em>Rotation</em>`。
   所以数学先换成 `%%LMDn%%`，marked 之后再还原。
3. **段落中间的 `$$...$$`** 前后补空行，否则会和正文挤进同一个 `<p>`。
4. **`\(...\)` / `\[...\]` 归一化成 `$...$` / `$$...$$`**，因为 marked 会把 `\(` 的反斜杠当转义吃掉。
5. **图片内存**：样本文档 75 张图共 102 MB，全部原图解码约 566 MB → 必然 OOM。
   默认按屏宽×2 降采样 + LRU + 懒加载，只有放大/导出才解码原图。
6. **长图必须分片**：那篇文档渲染约 6~8 万像素高，一张 1080×70000 位图 = 290 MB。
   改为逐屏抓取 + 拼接 + 按可用堆内存动态收敛每片高度；导出期间隐藏 fixed 顶栏。

## 验证状态

| 层次 | 手段 | 结果 |
|---|---|---|
| 渲染流水线 | 无头 Chromium 断言测试（`tools/rendertest/test.html`） | **77/77 通过** |
| 公式与字体 | `formula.html` + `document.fonts` 探针 | 12/12 字体族加载，mhchem 正常，非法公式降级不崩 |
| 代码高亮 | `code.html` | 8 块 211 token，GLSL/JS/Python/JSON/YAML 高亮正确 |
| 布局 | `layout.html`（iframe 五种宽度 + 首页几何） | 320/360/393/412/600px 全部无横向溢出；首页长文件名正确截断 |
| 图片查看器手势 | `zoom.html`（合成 PointerEvent 驱动真实页面） | **28/28 通过**：双指缩放锚点、边界钳制、双击放大还原、二级加载换图不跳变 |
| 真实样本 | 样本副本无头渲染 | 75 图 / 37 个 GLSL 块 / 52 处公式 / 23 标题 |
| 构建产物 | `aapt2 dump badging` + `unzip -l` + `apksigner verify` | 零权限、48 个 assets、无反斜杠、签名与对齐全过 |
| **真机运行** | Android 16 真机（HONOR 100）实测 3 轮 | 已修复 8 个真机问题（图片显示、首页排版、公式着色、代码高亮、PDF 网址洪流、查看器缩放与性能等）；逐条复测清单见 [`真机自查清单.md`](真机自查清单.md) |
