/* ============================================================
   蓝阅 · Markdown 渲染流水线（纯逻辑，可独立测试）
   暴露：window.LanyuePipeline
     .render(source, opts)        -> { html, toc, frontMatter, notices, chunks, stats }
     .protectedRanges(source)     -> [{ s, e, type }]   供测试/诊断
     .splitChunks(source, bytes)  -> [chunk]

   设计要点（每一条都是踩过坑之后定下来的）：
     1) 保护区掩码：围栏代码 / 行内代码 / 四种公式分隔符整段隔离，
        文本级改写（脚注引用、==高亮==、~下标~、^上标^、图片路径）只发生在自由区。
     2) 数学必须用「占位符」而不是「原样透传」：
        即使我把 $...$ 原样交给 marked，marked 仍会把里面的 * 和 _ 当成强调语法
        —— 样本里 `$Projection*Rotation*Translation$` 就被吃成了 <em>Rotation</em>。
        所以数学整段先换成 %%LMDn%%，marked 之后再原样还原（并做 HTML 转义）。
     3) 段落中间的 $$...$$ 前后补空行，否则 marked 会把它和正文塞进同一个 <p>。
     4) \(...\) 与 \[...\] 归一化成 $...$ / $$...$$，因为 marked 会把 \( 的反斜杠当转义吃掉。
     5) 结构改写（标题 id、代码块外壳、表格包裹）放在 DOM 阶段做，最稳。
   ============================================================ */
(function () {
  'use strict';

  var IMG_BASE = 'https://appassets.local/img/';
  var CHUNK_BYTES = 260 * 1024;        // 分段渲染：单段目标大小
  var FULL_RENDER_LIMIT = 1024 * 1024; // ≤1MB 一次性渲染
  var MATH_TOKEN = /%%LMD(\d+)%%/g;

  /* ------------------------------------------------------------------
     1. 保护区掩码
     ------------------------------------------------------------------ */
  function overlaps(s, e, ranges) {
    for (var i = 0; i < ranges.length; i++) {
      if (s < ranges[i].e && e > ranges[i].s) return true;
    }
    return false;
  }

  function fencedRanges(src) {
    var ranges = [];
    var lines = src.split('\n');
    var pos = 0, fenceChar = null, fenceLen = 0, start = -1;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var m = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
      if (!fenceChar && m) {
        fenceChar = m[1][0]; fenceLen = m[1].length; start = pos;
      } else if (fenceChar && m && m[1][0] === fenceChar && m[1].length >= fenceLen) {
        ranges.push({ s: start, e: pos + line.length, type: 'fence' });
        fenceChar = null;
      }
      pos += line.length + 1;
    }
    if (fenceChar) ranges.push({ s: start, e: src.length, type: 'fence' }); // 未闭合：吃到文件尾
    return ranges;
  }

  function regexRanges(src, ranges, re, type) {
    re.lastIndex = 0;
    var m;
    while ((m = re.exec(src))) {
      var s = m.index, e = m.index + m[0].length;
      if (!overlaps(s, e, ranges)) ranges.push({ s: s, e: e, type: type });
      if (m[0].length === 0) re.lastIndex++;
    }
  }

  function protectedRanges(src) {
    var ranges = fencedRanges(src);
    regexRanges(src, ranges, /`[^`\n]+`/g, 'code');                    // 行内代码
    regexRanges(src, ranges, /\$\$[\s\S]*?\$\$/g, 'math-display');     // $$ ... $$
    regexRanges(src, ranges, /\\\[[\s\S]*?\\\]/g, 'math-display');     // \[ ... \]
    regexRanges(src, ranges, /\\\([\s\S]*?\\\)/g, 'math-inline');      // \( ... \)
    // 行内 $...$：两侧不留空白、开符号前不是字母数字（避免吃掉 "US$5"）
    regexRanges(src, ranges, /(^|[^\w\\$])\$(?![\s$])((?:[^$\n]|\\\$)+?)(?<![\s\\])\$(?![\w$])/g, 'math-inline');
    ranges.sort(function (a, b) { return a.s - b.s; });
    return ranges;
  }

  /* 拼装：自由区做文本改写；数学段换占位符；代码段原样透传 */
  function assemble(src, ranges, transformFree, mathStore) {
    var out = '', cur = 0;
    for (var i = 0; i < ranges.length; i++) {
      var a = ranges[i].s, b = ranges[i].e, type = ranges[i].type;
      if (a > cur) out += transformFree(src.slice(cur, a));
      var raw = src.slice(a, b);
      if (type.indexOf('math') === 0) {
        var src2 = raw;
        if (src2.slice(0, 2) === '\\(' && src2.slice(-2) === '\\)') {
          src2 = '$' + src2.slice(2, -2).trim() + '$';          // 归一化成 KaTeX 认的写法
        } else if (src2.slice(0, 2) === '\\[' && src2.slice(-2) === '\\]') {
          src2 = '$$' + src2.slice(2, -2).trim() + '$$';
        }
        var display = src2.slice(0, 2) === '$$';
        var token = '%%LMD' + mathStore.length + '%%';
        mathStore.push(src2.trim());
        if (display) {
          var prev = out.length ? out.charAt(out.length - 1) : '\n';
          var next = b < src.length ? src.charAt(b) : '\n';
          if (prev !== '\n') out += '\n\n';
          out += token;
          if (next !== '\n') out += '\n\n';
        } else {
          out += token;
        }
      } else {
        out += raw;   // 围栏代码 / 行内代码：必须原样留给 marked
      }
      cur = b;
    }
    if (cur < src.length) out += transformFree(src.slice(cur));
    return out;
  }

  /* ------------------------------------------------------------------
     2. 自由区文本改写：图片相对路径 / 脚注引用 / 高亮 / 上下标
     ------------------------------------------------------------------ */
  function rewriteImages(text, opts) {
    var base = (opts && opts.imgBase) || IMG_BASE;
    // URL 里可能有空格（作者常写 ![](images/我的图 01.png)），marked 遇到空格会拒绝解析成图片
    return text.replace(/!\[([^\]]*)\]\(([^)]*)\)/g, function (whole, alt, inside) {
      var url = inside, title = '';
      var tm = /^([\s\S]*?)(\s+"[^"]*")$/.exec(inside);
      if (tm) { url = tm[1]; title = tm[2]; }
      url = url.trim().replace(/\s/g, '%20');
      if (/^(https?:|data:|blob:|mailto:|#|https:\/\/appassets\.local\/)/i.test(url)) {
        return '![' + alt + '](' + url + title + ')';
      }
      var clean = url.replace(/^\.\//, '').replace(/^<|>$/g, '');
      if (clean.charAt(0) === '/') clean = clean.slice(1);
      return '![' + alt + '](' + base + encodeRel(clean) + title + ')';
    }).replace(/<img\b([^>]*?)\bsrc\s*=\s*(["'])([^"']+)\2([^>]*)>/gi,
      function (whole, pre, q, src, post) {
        if (/^https?:\/\//i.test(src) || /^https:\/\/appassets\.local\//i.test(src)) return whole;
        if (/^(data:|blob:)/i.test(src)) return whole;
        var clean = src.replace(/^\.\//, '').replace(/^\//, '');
        return '<img' + pre + 'src=' + q + base + encodeRel(clean) + q + post + '>';
      });
  }

  function encodeRel(rel) {
    return rel.split('/').map(function (seg) {
      var plain = seg;
      try { plain = decodeURIComponent(seg); } catch (e) { /* 已是裸文本 */ }
      return encodeURIComponent(plain);
    }).join('/');
  }

  function makeFreeTransformer(fnState, opts) {
    return function (text) {
      text = rewriteImages(text, opts);
      text = text.replace(/\[\^([^\]\s]+)\]/g, function (whole, id) {
        if (fnState.defs[id] === undefined) return whole;
        if (fnState.order.indexOf(id) < 0) fnState.order.push(id);
        var n = fnState.order.indexOf(id) + 1;
        return '<sup class="fn-ref" id="fnref-' + n + '"><a href="#fn-' + n + '">[' + n + ']</a></sup>';
      });
      text = text.replace(/==([^=\n]+)==/g, '<mark>$1</mark>');
      text = text.replace(/(?<![~])~([^\s~=]{1,40}?)~(?!~)/g, '<sub>$1</sub>');
      text = text.replace(/\^([^\s^]{1,40}?)\^/g, '<sup>$1</sup>');
      return text;
    };
  }

  /* ------------------------------------------------------------------
     3. front matter / 脚注定义 抽取
     ------------------------------------------------------------------ */
  function extractFrontMatter(src) {
    var m = /^\uFEFF?---[ \t]*\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)/.exec(src);
    if (!m) return { body: src, data: null };
    var body = src.slice(m[0].length);
    var data = { rows: [] };
    m[1].split(/\r?\n/).forEach(function (line) {
      var kv = /^([A-Za-z_][\w.\-]*)\s*[:=]\s*(.*)$/.exec(line);
      if (kv) {
        var v = kv[2].trim().replace(/^["']|["']$/g, '');
        if (v) data.rows.push({ k: kv[1], v: v });
      }
    });
    if (!data.rows.length) return { body: body, data: null };
    // 按 names 的优先级找，而不是按文件里出现的先后
    var pick = function (names) {
      for (var n = 0; n < names.length; n++) {
        for (var i = 0; i < data.rows.length; i++) {
          if (data.rows[i].k.toLowerCase() === names[n]) return data.rows[i].v;
        }
      }
      return '';
    };
    data.title = pick(['title']);
    data.author = pick(['author', 'creator']);
    data.source = pick(['source', 'url', 'link', 'origin']);
    data.date = pick(['updated', 'modified', 'date', 'published', 'created']);
    return { body: body, data: data };
  }

  function extractFootnotes(src) {
    var lines = src.split('\n');
    var defs = {}, order = [], out = [];
    var fences = fencedRanges(src);
    var offset = 0, offsets = lines.map(function (l) {
      var o = offset; offset += l.length + 1; return o;
    });
    for (var i = 0; i < lines.length; i++) {
      var at = offsets[i];
      var m = !overlaps(at, at + 1, fences) && /^\[\^([^\]\s]+)\]:\s*(.*)$/.exec(lines[i]);
      if (!m) { out.push(lines[i]); continue; }
      var id = m[1], body = [m[2]];
      while (i + 1 < lines.length && /^(?: {2,}|\t)\S/.test(lines[i + 1])) {
        i++;
        body.push(lines[i].replace(/^(?: {2,}|\t)/, ''));
      }
      defs[id] = body.join('\n');
      order.push(id);
    }
    return { body: out.join('\n'), defs: defs, order: order };
  }

  /* ------------------------------------------------------------------
     4. 安全子集清洗
     ------------------------------------------------------------------ */
  var OK_TAGS = ('a,abbr,b,blockquote,br,code,dd,del,details,div,dl,dt,em,figcaption,figure,h1,h2,h3,h4,h5,h6,' +
    'hr,i,img,input,ins,kbd,li,mark,ol,p,pre,s,section,small,span,strong,sub,summary,sup,table,tbody,td,' +
    'tfoot,th,thead,tr,u,ul,var').split(',');
  var OK_ATTRS = ('href,src,alt,title,id,class,colspan,rowspan,start,type,checked,disabled,loading,decoding,' +
    'width,height,align,lang,dir').split(',');
  // 注意：绝不能在这里放 'input'。曾误写成 'input[type=file]'，而实现会把选择器属性段剥掉
  // → 变成"删除所有 <input>"，直接干掉 GFM 任务列表的复选框。
  var DROP_TAGS = ['script', 'style', 'iframe', 'object', 'embed', 'form', 'link', 'meta', 'base', 'svg', 'math'];

  function sanitize(root) {
    DROP_TAGS.forEach(function (tag) {
      var nodes = root.querySelectorAll(tag);
      for (var i = nodes.length - 1; i >= 0; i--) {
        if (nodes[i].parentNode) nodes[i].parentNode.removeChild(nodes[i]);
      }
    });
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      var tag = el.tagName.toLowerCase();
      if (OK_TAGS.indexOf(tag) < 0) {
        var parent = el.parentNode;
        if (!parent) continue;
        while (el.firstChild) parent.insertBefore(el.firstChild, el);
        parent.removeChild(el);
        continue;
      }
      var attrs = el.attributes;
      for (var j = attrs.length - 1; j >= 0; j--) {
        var name = attrs[j].name.toLowerCase();
        var val = attrs[j].value || '';
        if (name.indexOf('on') === 0 || name === 'srcdoc' || name === 'style') { el.removeAttribute(attrs[j].name); continue; }
        if (OK_ATTRS.indexOf(name) < 0) { el.removeAttribute(attrs[j].name); continue; }
        if ((name === 'href' || name === 'src') && /^\s*(javascript|vbscript|data:text\/html)/i.test(val)) {
          el.removeAttribute(attrs[j].name);
        }
      }
      if (tag === 'a') {
        el.setAttribute('rel', 'noopener');
        if (el.getAttribute('target')) el.removeAttribute('target');
      }
    }
    return root;
  }

  /* ------------------------------------------------------------------
     5. 结构改写（DOM 阶段）
     ------------------------------------------------------------------ */
  var LANG_ALIAS = {
    js: 'JavaScript', javascript: 'JavaScript', ts: 'TypeScript', typescript: 'TypeScript',
    py: 'Python', python: 'Python', sh: 'Shell', shell: 'Shell', bash: 'Bash', zsh: 'Zsh',
    html: 'HTML', markup: 'HTML', xml: 'XML', md: 'Markdown', markdown: 'Markdown',
    yml: 'YAML', yaml: 'YAML', glsl: 'GLSL', cpp: 'C++', cs: 'C#', csharp: 'C#',
    json: 'JSON', sql: 'SQL', go: 'Go', rust: 'Rust', java: 'Java', c: 'C', css: 'CSS'
  };

  function slugify(text, used) {
    var s = (text || '').trim().toLowerCase()
      .replace(/[\s\u3000]+/g, '-')
      .replace(/[!-\/:-@\[-`{-~，。、；：？！（）《》【】“”‘’—…·]/g, '')
      .replace(/-+/g, '-').replace(/^-|-$/g, '');
    if (!s) s = 'sec';
    var base = s, n = 2;
    while (used[s]) { s = base + '-' + n; n++; }
    used[s] = true;
    return s;
  }

  function restructure(root, state, idPrefix) {
    var used = {};
    var prefix = idPrefix || '';
    var heads = root.querySelectorAll('h1,h2,h3,h4,h5,h6');
    for (var i = 0; i < heads.length; i++) {
      var h = heads[i];
      h.id = prefix + slugify(h.textContent, used);
      state.toc.push({ id: h.id, text: h.textContent.trim(), level: parseInt(h.tagName.slice(1), 10) });
    }
    var tables = root.querySelectorAll('table');
    for (var t = 0; t < tables.length; t++) {
      var tb = tables[t];
      if (tb.parentElement && tb.parentElement.classList.contains('table-wrap')) continue;
      var wrap = document.createElement('div');
      wrap.className = 'table-wrap';
      tb.parentNode.insertBefore(wrap, tb);
      wrap.appendChild(tb);
    }
    var codes = root.querySelectorAll('pre > code');
    for (var c = 0; c < codes.length; c++) {
      var code = codes[c];
      var pre = code.parentElement;
      if (pre.parentElement && pre.parentElement.classList.contains('code-block')) continue;
      var m = /language-([\w+#.-]+)/.exec(code.className || '');
      var lang = m ? m[1].toLowerCase() : '';
      var box = document.createElement('div');
      box.className = 'code-block';
      var head = document.createElement('div');
      head.className = 'code-head';
      var label = document.createElement('span');
      label.className = 'lang';
      label.textContent = lang ? (LANG_ALIAS[lang] || lang.toUpperCase()) : 'CODE';
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'copy';
      btn.setAttribute('data-copy', '1');
      btn.textContent = '复制';
      head.appendChild(label); head.appendChild(btn);
      pre.parentNode.insertBefore(box, pre);
      box.appendChild(head); box.appendChild(pre);
      if (lang === 'mermaid') {
        var note = document.createElement('div');
        note.className = 'mermaid-note';
        note.textContent = 'mermaid 图表未内置（体积约束），此处以源码显示。';
        box.parentNode.insertBefore(note, box.nextSibling);
      }
      if (lang) state.langs[lang] = (state.langs[lang] || 0) + 1;
    }
    var imgs = root.querySelectorAll('img');
    for (var k = 0; k < imgs.length; k++) {
      imgs[k].setAttribute('loading', 'lazy');
      imgs[k].setAttribute('decoding', 'async');
      if (!imgs[k].getAttribute('alt')) imgs[k].setAttribute('alt', '');
    }
    var boxes = root.querySelectorAll('li input[type=checkbox]');
    for (var b = 0; b < boxes.length; b++) boxes[b].setAttribute('disabled', 'disabled');
    return root;
  }

  /* ------------------------------------------------------------------
     6. 分段（大文档渐进渲染）
     ------------------------------------------------------------------ */
  function splitChunks(src, targetBytes) {
    var fences = fencedRanges(src);
    var lines = src.split('\n');
    var offsets = [], off = 0;
    for (var i = 0; i < lines.length; i++) { offsets.push(off); off += lines[i].length + 1; }
    var chunks = [], start = 0, size = 0;
    for (var j = 0; j < lines.length; j++) {
      var at = offsets[j];
      var inFence = overlaps(at, at + 1, fences);
      var blank = /^\s*$/.test(lines[j]);
      var prevBlank = /^\s*$/.test(lines[j - 1] || '');
      // 在「标题/分隔线之前」或「连续空行」处切；两者都必须不在围栏内。
      // 只认连续空行会漏掉绝大多数真实文档（章节之间通常只有一个空行）。
      var isHead = /^#{1,6}\s/.test(lines[j]) || /^-{3,}\s*$/.test(lines[j]);
      var isBoundary = !inFence && j > 0 && prevBlank && (blank || isHead);
      size += lines[j].length + 1;
      if (isBoundary && size >= targetBytes) {
        chunks.push(src.slice(start, at));
        start = at; size = 0;
      }
    }
    if (start < src.length) chunks.push(src.slice(start));
    return chunks.length ? chunks : [src];
  }

  /* ------------------------------------------------------------------
     7. 主入口
     ------------------------------------------------------------------ */
  function render(source, opts) {
    opts = opts || {};
    var state = { toc: [], langs: {} };
    var notices = [];
    var mathStore = [];

    var text = String(source == null ? '' : source).replace(/\r\n?/g, '\n').replace(/^\uFEFF/, '');

    var fm = extractFrontMatter(text);
    text = fm.body;

    var fn = extractFootnotes(text);
    text = fn.body;

    var fnState = { defs: fn.defs, order: [] };
    var ranges = protectedRanges(text);
    text = assemble(text, ranges, makeFreeTransformer(fnState, opts), mathStore);

    var html;
    try {
      if (window.marked && window.marked.parse) html = window.marked.parse(text, { gfm: true, breaks: false, pedantic: false, async: false });
      else html = '<pre>' + escapeHtml(text) + '</pre>';
    } catch (e) {
      notices.push({ type: 'warn', text: 'Markdown 解析异常：' + e.message });
      html = '<pre>' + escapeHtml(text) + '</pre>';
    }

    // 还原数学占位符（此刻 marked 已经处理完强调等语法，数学内容不会再被吃掉）
    var restored = 0;
    html = html.replace(MATH_TOKEN, function (whole, idx) {
      var src2 = mathStore[parseInt(idx, 10)];
      if (src2 === undefined) return whole;
      restored++;
      return escapeHtml(src2);
    });

    var host = document.createElement('div');
    host.innerHTML = html;
    sanitize(host);
    restructure(host, state, opts.idPrefix);

    var fnHtml = '';
    if (fnState.order.length) {
      var items = fnState.order.map(function (id, idx) {
        var body = fn.defs[id] || '';
        var inline = '';
        try { inline = window.marked && window.marked.parseInline ? window.marked.parseInline(body) : escapeHtml(body); }
        catch (e) { inline = escapeHtml(body); }
        return '<li id="fn-' + (idx + 1) + '">' + inline +
          ' <a href="#fnref-' + (idx + 1) + '" class="fn-back">↩</a></li>';
      }).join('');
      fnHtml = '<section class="footnotes"><h4>脚注</h4><ol>' + items + '</ol></section>';
    }

    var full = text.length <= FULL_RENDER_LIMIT;
    return {
      html: host.innerHTML + fnHtml,
      toc: state.toc,
      langs: state.langs,
      frontMatter: fm.data,
      notices: notices,
      chunks: full ? [] : splitChunks(text, CHUNK_BYTES),
      stats: {
        chars: text.length,
        headings: state.toc.length,
        images: host.querySelectorAll('img').length,
        math: mathStore.length,
        mathRestored: restored,
        progressive: !full
      }
    };
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  window.LanyuePipeline = {
    render: render,
    splitChunks: splitChunks,
    protectedRanges: protectedRanges,
    extractFrontMatter: extractFrontMatter,
    extractFootnotes: extractFootnotes,
    FULL_RENDER_LIMIT: FULL_RENDER_LIMIT
  };
})();
