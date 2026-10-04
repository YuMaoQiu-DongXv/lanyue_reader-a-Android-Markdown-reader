# 第三方组件与许可

本项目**不分发任何第三方运行时依赖**（没有 AndroidX、没有 Kotlin、没有 Gradle 插件），
但内嵌了以下三个前端库的发行文件。它们都是 MIT 许可，与本项目一致；
各自的完整许可原文随源码放在 `app/assets/web/vendor/<库>/LICENSE`。

| 库 | 版本 | 用途 | 许可 | 上游 |
|---|---|---|---|---|
| [marked](https://github.com/markedjs/marked) | 18.0.14 | Markdown 解析（GFM） | MIT © 2018-2026 MarkedJS | `vendor/marked/` |
| [KaTeX](https://github.com/KaTeX/KaTeX) | 0.19.0 | 数学公式排版（含 mhchem 化学式扩展与 20 个 woff2 字体） | MIT © 2013-2020 Khan Academy and other contributors | `vendor/katex/` |
| [Prism](https://github.com/PrismJS/prism) | 1.30.0 | 代码语法高亮（核心 + 19 个语言组件） | MIT © 2012 Lea Verou | `vendor/prism/` |

## 为什么随附 LICENSE 原文

MIT 许可要求：**再分发时须保留版权声明与许可声明**。
`marked` 的发行文件自带版权头，但 `prism-core.min.js` 与 `katex.min.js` 是压缩产物、头部不含声明，
因此这里显式把三份 LICENSE 原文一并放入源码树 —— 这样源码分发与 APK 分发都满足要求。

## KaTeX 字体

`vendor/katex/fonts/` 下的 20 个 `.woff2` 来自 KaTeX 发行包，同样适用上表中的 MIT 许可。
这是整个应用体积的主要部分（254 KB，占 APK 的 53%）。
