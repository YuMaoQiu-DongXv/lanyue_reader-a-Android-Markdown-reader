# 蓝阅 (Lanyue) · Android Markdown 阅读器 — 设计说明书 v1.0

> 状态：**已确认开工**（2026-10 会话）
> 一句话：一个约 600 KB 的纯离线 Android Markdown 阅读器，原生 WebView 渲染（marked + KaTeX + Prism 全内嵌），蓝白简洁 UI，轻量编辑，PDF 与长图导出，零权限、零网络、零 AndroidX。

---

## 1. 硬约束

| 项 | 约束 | 来源 |
|---|---|---|
| APK 体积 | **目标 < 1 MB**，天花板 1.5 MB（冲突时优先公式保真） | 用户 |
| 安装体积 | < 5 MB | 用户 |
| 配色 | 蓝白为主，整洁简洁 | 用户 |
| 网络 | 零权限全离线（不申请 `INTERNET`） | 用户 Q6 |
| 最低版本 | minSdk 26（Android 8.0） | 用户 Q3 |
| 第三方 UI 库 | **禁止** AndroidX / Material / Kotlin stdlib | 体积约束推导 |
| 必过验收样本 | `D:\DSH program\blackhole\article1\从零开始搓一个黑洞——glsl编程实战-Baopinsui的文章.md` | 用户 Q12 |

## 2. 决策清单

### 2.1 用户决策

| # | 决策 | 结论 |
|---|---|---|
| 1 | 交付形态 | 装 Android SDK，交付**已签名 release APK** |
| 2 | 产品定位 | **阅读优先**，编辑为轻量功能 |
| 3 | 分发 | 侧载自用 + 分享 apk，不上架 |
| 4 | UI 实现 | 完全手写（`RippleDrawable` 做反馈） |
| 5 | 主题 | 浅色蓝白 + 深色（深蓝灰 `#0F172A` 系），**三态**：跟随系统／强制浅／强制深 |
| 6 | 公式 | KaTeX **完整字体集** + **mhchem** + 四种分隔符 `$ $$ \( \) \[ \]` |
| 7 | 语法 | GFM 表格/任务列表/删除线/自动链接/脚注/`==高亮==`/上下标/front matter 全开；原始 HTML 安全子集；mermaid 不做（降级为代码块+提示） |
| 8 | 代码高亮 | Prism 18 种：`glsl javascript typescript python java c cpp csharp json bash markdown sql yaml html css rust go` |
| 9 | 阅读功能 | 目录、文内搜索、字号调节、滚动位置记忆、最近文件；不做多标签页 |
| 10 | 附加功能 | 图片点击放大、代码块复制、导出后分享、阅读进度百分比、双击顶部回顶 |
| 11 | 编辑器 | 系统 `EditText` + Markdown 工具栏；无行号、无编辑区高亮 |
| 12 | 编码 | UTF-8 优先，GBK/GB18030 兜底识别（识别到显示提示条），保存一律 UTF-8 |
| 13 | 保存 | 显式保存 + 未保存退出提示 + 自动保存开关（默认开）；只读 URI 走"另存为" |
| 14 | PDF | 系统打印链路（`PrintManager`），A4、可调边距、去系统页眉页脚、CSS 控制不断页；图片默认降采样 ~1600px，配"高质量导出"开关 |
| 15 | 图片导出 | 分片长图（≤12000px/片）+ 仅当前屏 + 选区导出 |
| 16 | 图片内存 | 显示用屏宽×2 降采样 + LRU + 懒加载；点击放大才按需解码原图 |
| 17 | 图片路径 | 自动解析同级相对路径；失败给"授权该文件夹"按钮（永久记住） |
| 18 | 大文档 | ≤1 MB 一次性渲染；>1 MB 分段懒渲染；>5 MB 顶部提示 |
| 19 | front matter | 顶部折叠信息卡；导出时呈现为"标题/作者/来源/日期"小表头 |
| 20 | 文件关联 | 只接管 `.md`/`.markdown`（`text/markdown`），不抢 `.txt`；SAF 默认，"所有文件访问"高级开关默认关 |
| 21 | 字体 | 只用系统字体（不导入、不内嵌） |
| 22 | 验收通过线 | 见 §5 |
| 23 | 交付 | `dist/` APK + 真机自查清单 + git 仓库 + 一键构建脚本 + 自签密钥 |

### 2.2 实施方决策（用户未反对即生效）

| 项 | 决定 | 理由 |
|---|---|---|
| 应用名 / 包名 | 「蓝阅」/ `com.dsh.mdreader` | 用户授权 |
| 项目路径 | `D:\DSH program\mdreader\` | 用户授权 |
| 语言 | 纯 Java + 原生 JS/CSS | Kotlin stdlib 压缩后仍 300–400 KB |
| 版本 | 1.0.0（versionCode 1） | — |
| 图标 | 蓝白矢量 adaptive icon | 几百字节 |
| UI 语言 | 中文优先 + `values-en` 兜底 | — |
| 纸张 / 边距 | A4 / 默认 15mm | — |
| 长图宽度 | 1080（可选 1440） | 分享友好 |
| 超宽内容 | 表格/代码块/公式统一横向滚动容器 | 不撑破布局 |
| 链接 | 外链交系统浏览器；`#锚点` 内部跳转 | 无网络权限也能开浏览器 |
| Android 16 | 强制 edge-to-edge，Insets 全手动处理 | targetSdk 36 要求 |
| **构建系统** | **免 Gradle**：`aapt2 + javac + d8 + zipalign + apksigner` 手写 `build.sh` | 无依赖时 Gradle 纯属负担（省 ~200 MB 下载与不确定性），且便于逐环节实测体积 |
| 权限 | 安装后权限列表为空 | — |
| 非目标 | 云同步、多标签页、mermaid、内嵌中文字体、Play 上架、主题商店、插件系统 | — |

## 3. 架构

```
Java 壳（framework-only，无 AndroidX）
├── MainActivity          单 Activity + 模式状态机（浏览 / 编辑 / 导出中）
├── UiKit                 手写顶栏/底栏/目录抽屉/工具栏 + RippleDrawable + Insets
├── ReaderWebView         WebView 封装；shouldInterceptRequest 拦截三类请求
│                         ├── appassets://…    → assets/web/（JS/CSS/字体）
│                         └── localimg://…     → ContentResolver + BitmapFactory 降采样
├── FileGate              SAF 打开/另存/目录授权/持久化权限/最近文件
├── CodecGate             BOM → UTF-8 → GB18030 探测链
├── Exporter              PrintManager(PDF) / 分片长图 / 选区 / 分享
└── Settings              SharedPreferences（主题、字号、自动保存、高级开关）
```

```
WebView 渲染层（assets/web/）
├── index.html
├── app.css               蓝白设计变量 + 深浅两套 + 打印样式 + 超宽容器
├── app.js                流水线：
│     预处理(规范 $$ 嵌段 / front matter 提取 / 相对路径改写)
│       → marked(GFM)
│       → 安全子集清洗(禁 script/iframe/on*)
│       → KaTeX auto-render(四种分隔符)
│       → Prism 高亮
│       → 目录 / 脚注 / 进度 / 懒加载
└── vendor/               marked · katex · mhchem · auto-render · prism · fonts(20×woff2)
```

**关键数据流（打开黑洞文章）**
`SAF URI` → 字节 → 编码探测 → 源文本注入 WebView → 预处理改写 `images/xxx` 为 `localimg://…` → marked 渲染 → `shouldInterceptRequest` 命中 → 按屏宽×2 降采样 → 回填；失败则渲染"授权文件夹"占位块。

## 4. 体积预算（**实测**，2026-10 构建）

| 组成 | 原始 | 进 APK | 占比 |
|---|---|---|---|
| KaTeX 字体（woff2，已压缩） | 253.7 KB | **253.7 KB** | 56.0% |
| KaTeX JS/CSS + mhchem + auto-render | 327.0 KB | 87.9 KB | 19.4% |
| classes.dex（Java，无 AndroidX） | 75.9 KB | 36.1 KB | 8.0% |
| 手写 UI（html/css/js） | 82.3 KB | 25.5 KB | 5.6% |
| Prism 核心 + 19 个语言组件 | 53.9 KB | 23.6 KB | 5.2% |
| marked | 45.8 KB | 13.9 KB | 3.1% |
| 签名（META-INF） | 13.9 KB | 5.9 KB | 1.3% |
| resources.arsc + Manifest + 图标/主题/字符串 | 11.4 KB | 6.7 KB | 1.5% |
| **合计** | ~864 KB | **453.2 KB**（+zip 开销 → **APK 469 KB**） | 100% |

**结论：APK = 469 KB，低于 1 MB 目标，Q2 批准的"1.5 MB 放宽条款"完全没有用上。**
KaTeX 字体独占 56% 是唯一硬成本；其余全部加起来 200 KB 不到。

## 5. 验收通过线

样本 = 黑洞文章（实测：81 KB、1278 行、30 标题、37 个 GLSL 块、30 处行内公式、1 处**嵌在段落中间的** `$$`、75 张同目录相对路径图共 102 MB、2 处脚注、0 表格）。

1. 75 张图全部显示（降采样）、点击可放大、滚动不卡不崩
2. 37 个 GLSL 块正确高亮、可横滚、可复制
3. 30 处行内公式 + 1 处嵌段 `$$` 渲染正确
4. 30 个标题目录可跳转、2 处脚注可跳、front matter 走信息卡
5. **APK < 1 MB**；安装后 < 5 MB
6. 冷启动 ≤ 1 s；打开该文档可交互 ≤ 2 s（图片继续按需加载）
7. PDF 导出齐全、无跨页断裂、体积 10–20 MB 级
8. 分片长图片序连续、无缺失、无空白片
9. **安装时权限列表为空**；飞行模式全功能可用
10. 深浅两套配色下代码块/表格/公式对比度正常

## 6. 风险清单

| # | 风险 | 缓解 |
|---|---|---|
| R1 | 段落中间的 `$$` 解析翻车（样本正好踩中） | 渲染前预处理规范成独立块；headless 截图专盯该处 |
| R2 | 非标准 Provider 下同级目录推导失败 | "授权文件夹"一键补救，失败不静默 |
| R3 | 4K 图导致 WebView OOM（全量解码达 566 MB） | 降采样 + LRU + 懒加载，实测峰值内存 |
| R4 | PDF 分页质量无法在桌面完全验证 | 桌面验打印 CSS 断页规则；真机给逐条检查点 |
| R5 | 体积失控（KaTeX 字体 254 KB 是硬成本） | 每版实测；超了先砍 Prism 语言再谈字体子集化 |
| R6 | 无 AndroidX 意味着列表/圆角/动画全手写 | 控件面收窄到必需，工时已计入 |

## 7. 验证计划

1. **桌面 headless 自查**：同一 Chromium 内核渲染黑洞文档（`_work/render-test/` 瘦身副本先跑，全量图最后跑一次），截图 + `read_image` 逐张复核高亮/嵌段公式/front matter/图片排布/深浅两色。
2. **产物审计**：`aapt2 dump badging`（权限列表）、`unzip -l`（内容构成）、APK 与安装体积实测。
3. **真机验收**：用户按 `真机自查清单.md` 十条逐项过，问题回报修复。

## 8. 交付物

- `dist/lanyue-1.0.0-release.apk`（已签名）
- 源码 + `SPEC.md` + `build.sh`（免 Gradle 一键构建）
- `真机自查清单.md`
- 自签密钥 + `keystore.local.txt`（**用户需自行备份；丢失则无法覆盖升级**）

---

## 9. 验证记录（交付时实测）

### 9.1 渲染流水线（无头 Chromium 断言测试，`tools/rendertest/test.html`）
**77 / 77 断言通过。**覆盖：
- front matter 解析（含"按优先级取 updated 而非按出现顺序"）
- **段落中间的 `$$…$$` 被拆成独立块**（R1 风险，正面证据）
- 四种分隔符 `$ $$ \( \) \[ \]`；其中 `\(...\)`/`\[...\]` 会被 marked 吃掉反斜杠，已用归一化解决
- **数学内部的 `*` `_` 不被 marked 当成强调语法**（占位符方案，样本真实踩过）
- 围栏代码 / 行内代码保护；脚注（引用 + 定义区 + 内联 markdown）
- 相对图片路径改写（含空格、中文、已编码路径不被二次编码）
- 安全子集清洗（script/iframe/on*/style/javascript: 全部剥掉，details/summary 保留）
- 标题 id 去重、表格包裹、代码块外壳、任务列表复选框
- 分段渲染不切在代码围栏内部
- **真实样本**：75 图 / 37 个 GLSL 块 / 52 处公式 / 23 个标题（= 围栏外的 `#` 行数）

### 9.2 公式与字体（`tools/rendertest/formula.html`）
12/12 字体族加载成功（20 个 woff2 全部可达）；mhchem 化学式 `\ce{}` 正确；矩阵/求和/积分/根式/字体族正常；
非法公式（我故意写的 `$a_b_c$`）降级为红色原文而不崩。

### 9.3 代码高亮（`tools/rendertest/code.html`）
8 个代码块 / 211 个 token；GLSL、JavaScript、Python、JSON、YAML、Bash 高亮正确；
未知语言（brainfuck）退化为无色等宽；超长行横向滚动不撑破布局。

### 9.4 布局（`tools/rendertest/layout.html`，iframe 实测真实页面）
320 / 360 / 393 / 412 / 600 px 五种宽度：**全部无横向溢出**，顶栏 5 个按钮全部可见。
（过程中修掉一个真 bug：进度百分比原来是 inline span，把最右侧的 ⋯ 按钮挤出了屏幕；
现改为 fixed 浮标。）

### 9.5 构建产物审计（`bash build.sh check`）
```
APK = 480287 字节 = 469 KB
权限列表为空 ✓
minSdkVersion 26 / targetSdkVersion 36 / label 蓝阅 ✓
assets/web 条目数 = 48，条目名无反斜杠 ✓
classes.dex 在包内 ✓
签名校验通过（v2/v3）✓    4 字节对齐成立 ✓
```

## 10. 与设计说明书的偏差

| 项 | SPEC 原定 | 实际 | 原因 |
|---|---|---|---|
| `Exporter` 构造函数 | `(Activity, WebView, Settings, Layout, ShareProvider)` | `(Activity, WebView, Settings, Layout, AssetServer)` | 导出前需要把图片切换成高分辨率，必须拿到 `AssetServer`；`ShareProvider` 是纯静态工具类，不需要实例 |
| 长图分片高度 | 固定 ≤12000px | ≤12000px，且再按可用堆内存动态收敛 | 32 位设备上 12000px 片仍可能 OOM；宁可按内存收窄也不崩 |
| 图片导出落盘 | — | API 29+ 免权限写系统相册 `Pictures/蓝阅`；API 26–28 落应用缓存 + 分享 | 保持"零权限"承诺：低版本写相册需要 `WRITE_EXTERNAL_STORAGE` |
| 体积 | 估算 460–520 KB | 实测 469 KB | 估算准确 |

## 11. 未验证项（诚实交代）

本机**没有安卓设备也没有模拟器**（`adb devices` 为空，`D:\Android\Sdk` 未装 system-image），
以下几项只能在真机上验证，已写入 `真机自查清单.md`：

1. WebView 请求拦截（`https://appassets.local` 是否真的全部就地应答）——**这是整个应用的地基，静态度量无法覆盖**
2. SAF 打开/另存、以及不同 Provider（外部存储 / 下载 / 微信缓存）下的相对图片解析
3. PDF 打印链路的分页质量
4. 长图逐屏抓取的实际效果与内存表现
5. 4K 图滚动时的真实内存曲线
