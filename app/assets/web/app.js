/* ============================================================
   蓝阅 · 应用层
   职责：与原生桥交互、挂载渲染结果、目录/搜索/设置/导出入口、
        阅读进度与位置记忆、图片放大与失败兜底
   原生桥（Java 注入，对象名 Lanyue）契约见 SPEC.md 与 Java 端注释
   ============================================================ */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var doc = $('doc'), home = $('home');

  /* ---------------- 原生桥（缺失时启用桌面 mock，便于离线自查） ---------------- */
  var HAS_BRIDGE = !!(window.Lanyue && typeof window.Lanyue.getState === 'function');
  if (!HAS_BRIDGE) installMock();
  var B = window.Lanyue;

  function installMock() {
    var params = new URLSearchParams(location.search);
    var docUrl = params.get('doc');
    var fileName = '';
    if (docUrl) { try { fileName = decodeURIComponent(docUrl.split('/').pop()); } catch (e) { fileName = docUrl.split('/').pop(); } }
    var st = {
      version: 1,
      hasDoc: !!docUrl,
      docUrl: docUrl || '',
      imgBase: params.get('imgbase') || './',
      docKey: 'desktop-test',
      fileName: fileName,
      canWrite: false,
      encoding: 'UTF-8',
      theme: params.get('theme') || 'system',
      fontScale: 1,
      insets: { top: 0, bottom: 0 },
      settings: { autosave: true, hqExport: false, allFilesAccess: false },
      recent: [],
      appVersion: 'dev-mock',
      webview: navigator.userAgent
    };
    window.Lanyue = {
      getState: function () { return JSON.stringify(st); },
      openFile: noop, openFolder: noop, openRecent: noop, saveAs: noop,
      saveText: noop, editDoc: noop, exportPdf: noop, exportImages: noop,
      shareLast: noop, setSetting: noop, toast: noop,
      log: function (m) { console.log('[lanyue]', m); },
      docReady: function (s) { console.log('[lanyue] docReady', s); },
      layout: noop
    };
    window.__MOCK__ = true;
    window.__MOCK_GO = params.get('go') || '';
  }
  function noop() {}

  /* ---------------- 状态 ---------------- */
  var state = null;
  var sourceText = '';
  var pipelineResult = null;
  var chunks = [], renderedChunks = 0, chunkSentinels = [];
  var lastExportPayload = null;

  var DELIMS = [
    { left: '$$', right: '$$', display: true },
    { left: '\\[', right: '\\]', display: true },
    { left: '\\(', right: '\\)', display: false },
    { left: '$', right: '$', display: false }
  ];

  /* ---------------- 启动 ---------------- */
  function boot() {
    try { state = JSON.parse(B.getState()); }
    catch (e) { state = { hasDoc: false, theme: 'light', fontScale: 1, insets: { top: 0, bottom: 0 }, settings: {}, recent: [] }; }
    applyChrome(state);
    if (state.hasDoc && state.docUrl) loadDoc();
    else renderHome();
    wireEvents();
  }

  function applyChrome(s) {
    document.documentElement.setAttribute('data-theme', resolveTheme(s));
    document.documentElement.style.setProperty('--inset-top', (s.insets && s.insets.top || 0) + 'px');
    document.documentElement.style.setProperty('--inset-bottom', (s.insets && s.insets.bottom || 0) + 'px');
    document.documentElement.style.setProperty('--font-scale', String(s.fontScale || 1));
    $('doc-title').textContent = s.hasDoc && s.fileName ? s.fileName : '蓝阅';
    $('btn-home').hidden = !s.hasDoc;
  }

  function resolveTheme(s) {
    var t = s.theme || 'system';
    if (t === 'system') {
      return (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light';
    }
    return t === 'dark' ? 'dark' : 'light';
  }

  /* ---------------- 首页 ---------------- */
  function renderHome() {
    doc.hidden = true; home.hidden = false;
    $('doc-title').textContent = '蓝阅';
    try { B.viewChanged('home'); } catch (e) {}
    $('pct').classList.remove('show');
    $('btn-home').hidden = true;
    var items = (state.recent || []).map(function (r) {
      return '<button class="recent-item" data-uri="' + esc(r.uri) + '">' +
        '<span class="dot">' + esc(initials(r.name)) + '</span>' +
        '<span class="meta"><span class="name">' + esc(r.name) + '</span>' +
        '<span class="when">' + esc(r.when || '') + (r.size ? ' · ' + fmtSize(r.size) : '') + '</span></span>' +
        '<span class="open">›</span></button>';
    }).join('');
    home.innerHTML =
      '<div class="brand"><div class="logo">阅</div><h1>蓝阅</h1></div>' +
      '<p class="sub">轻量 · 离线 · Markdown 阅读器</p>' +
      '<button class="btn" id="home-open">' +
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<path d="M4 5.5A1.5 1.5 0 015.5 4h4l2 2.5h7A1.5 1.5 0 0120 8v10.5a1.5 1.5 0 01-1.5 1.5h-13A1.5 1.5 0 014 18.5z"/></svg>' +
      '打开 Markdown 文件</button>' +
      '<button class="btn ghost" id="home-folder">授权文件夹（显示文档内图片）</button>' +
      (items
        ? '<div class="section-label">最近打开</div><div class="recent-list">' + items + '</div>'
        : '<div class="tip">还没有最近记录。<br>用上面的按钮打开一个 <b>.md</b> 文件，它就会出现在这里。</div>') +
      '<div class="tip">把 <b>.md</b> 文件在文件管理器里点开并选择「蓝阅」，下次就能直接从系统「打开方式」进来。<br>本应用不申请任何权限，完全离线运行。</div>';

    bindTap('home-open', function () { B.openFile(); });
    bindTap('home-folder', function () { B.openFolder(); });
    Array.prototype.forEach.call(home.querySelectorAll('.recent-item'), function (el) {
      el.addEventListener('click', function () { B.openRecent(el.getAttribute('data-uri')); });
    });
  }

  /* ---------------- 文档加载与渲染 ---------------- */
  function showLoading(text) {
    doc.hidden = false; home.hidden = true;
    doc.innerHTML = '<div class="loading"><div class="spinner"></div>' + esc(text || '正在载入…') + '</div>';
  }

  function loadDoc() {
    showLoading('正在载入文档…');
    fetchDocText(state.docUrl).then(function (text) {
      sourceText = text;
      mountAll();
    }).catch(function (e) {
      doc.innerHTML = '<div class="notice">文档读取失败：' + esc(e && e.message || e) + '</div>';
      if (B.log) B.log('doc load failed: ' + e);
    });
  }

  function fetchDocText(url) {
    if (window.__MOCK__) {
      return fetch(url).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); });
    }
    return fetch(url, { cache: 'no-store' }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.text();
    });
  }

  function mountAll() {
    var opts = { imgBase: state.imgBase || 'https://appassets.local/img/' };
    var big = sourceText.length > (window.LanyuePipeline.FULL_RENDER_LIMIT || 1048576);

    if (!big) {
      pipelineResult = window.LanyuePipeline.render(sourceText, opts);
      doc.innerHTML = '';
      doc.appendChild(buildPrologue(pipelineResult));
      doc.insertAdjacentHTML('beforeend', pipelineResult.html);
      decorate(doc);
      afterRender(pipelineResult);
      return;
    }

    // 大文档：分段渐进渲染
    chunks = window.LanyuePipeline.splitChunks(sourceText, 260 * 1024);
    renderedChunks = 0;
    pipelineResult = { toc: [], notices: [], frontMatter: null, stats: {} };
    doc.innerHTML = '';
    for (var i = 0; i < chunks.length; i++) {
      var holder = document.createElement('div');
      holder.className = 'chunk';
      holder.setAttribute('data-chunk', String(i));
      var sentinel = document.createElement('div');
      sentinel.className = 'chunk-sentinel';
      doc.appendChild(holder);
      doc.appendChild(sentinel);
      chunkSentinels.push(sentinel);
    }
    renderChunk(0);
    observeSentinels();
    afterRender(pipelineResult);
  }

  function renderChunk(i) {
    if (i >= chunks.length) return;
    var holder = doc.querySelector('[data-chunk="' + i + '"]');
    if (!holder || holder.getAttribute('data-done') === '1') return;
    var res = window.LanyuePipeline.render(chunks[i], {
      imgBase: state.imgBase || 'https://appassets.local/img/',
      idPrefix: 'c' + i + '-'
    });
    holder.innerHTML = res.html;
    holder.setAttribute('data-done', '1');
    renderedChunks++;
    pipelineResult.toc = pipelineResult.toc.concat(res.toc);
    pipelineResult.notices = pipelineResult.notices.concat(res.notices);
    pipelineResult.frontMatter = pipelineResult.frontMatter || res.frontMatter;
    decorate(holder);
    buildToc();
  }

  function observeSentinels() {
    if (!window.IntersectionObserver) { renderAllChunks(); return; }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        var idx = parseInt(en.target.previousElementSibling.getAttribute('data-chunk'), 10);
        io.unobserve(en.target);
        renderChunk(idx + 1);
      });
    }, { rootMargin: '600px 0px' });
    chunkSentinels.forEach(function (s) { io.observe(s); });
  }

  function renderAllChunks() {
    for (var i = 0; i < chunks.length; i++) renderChunk(i);
  }

  /* front matter 信息卡 + 编码提示 */
  function buildPrologue(res) {
    var frag = document.createElement('div');
    var html = '';
    if (state.encodingNotice) {
      html += '<div class="notice">' + esc(state.encodingNotice) + '</div>';
    }
    if (res.frontMatter && res.frontMatter.rows && res.frontMatter.rows.length) {
      var fm = res.frontMatter;
      var sub = [fm.author, fm.date].filter(Boolean).join(' · ');
      html += '<div class="fm-card" id="fm-card"><div class="fm-head">' +
        '<div class="fm-title"><div class="t">' + esc(fm.title || state.fileName || '文档') + '</div>' +
        (sub ? '<div class="s">' + esc(sub) + '</div>' : '') + '</div>' +
        '<span class="chev">▶</span></div><div class="fm-body">' +
        fm.rows.map(function (r) {
          var v = r.v;
          if (/^https?:\/\//i.test(v)) v = '<a href="' + esc(v) + '">' + esc(v) + '</a>';
          else v = esc(v);
          return '<div class="fm-row"><span class="k">' + esc(r.k) + '</span><span class="v">' + v + '</span></div>';
        }).join('') +
        '</div></div>';
    }
    if (res.notices && res.notices.length) {
      html += res.notices.map(function (n) { return '<div class="notice">' + esc(n.text) + '</div>'; }).join('');
    }
    frag.innerHTML = html;
    var card = frag.querySelector('#fm-card');
    if (card) {
      card.querySelector('.fm-head').addEventListener('click', function () { card.classList.toggle('open'); });
    }
    return frag;
  }

  /* 高亮 + 公式 */
  function decorate(root) {
    if (window.Prism && Prism.highlightAllUnder) {
      try { Prism.highlightAllUnder(root); } catch (e) { B.log && B.log('prism: ' + e); }
    }
    if (window.renderMathInElement) {
      try {
        window.renderMathInElement(root, {
          delimiters: DELIMS,
          ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code', 'option'],
          ignoredClasses: ['no-math'],
          throwOnError: false,
          strict: false,
          trust: false,
          errorColor: '#d64545',
          macros: { '\\RR': '\\mathbb{R}' }
        });
      } catch (e) { B.log && B.log('katex: ' + e); }
    }
  }

  function afterRender(res) {
    buildToc();
    bindImageFallback();
    restorePosition();
    // 桌面自查用：?go=<标题 id> 直接跳到某个标题（同时验证锚点跳转逻辑）
    if (window.__MOCK__ && window.__MOCK_GO) {
      var goEl = document.getElementById(window.__MOCK_GO);
      if (goEl) goEl.scrollIntoView({ block: 'start' });
    }
    updateLayout();
    try { B.viewChanged('doc'); } catch (e) {}
    var stats = {
      chars: sourceText.length,
      headings: (res.toc || []).length,
      images: doc.querySelectorAll('img').length,
      formulas: doc.querySelectorAll('.katex').length,
      blocks: (res.toc || []).length,
      progressive: !!chunks.length,
      langs: res.langs || {}
    };
    try { B.docReady(JSON.stringify(stats)); } catch (e) {}
    if (B.log) B.log('rendered: ' + JSON.stringify(stats));
  }

  /* ---------------- 目录 ---------------- */
  function buildToc() {
    var list = $('toc-list');
    var toc = (pipelineResult && pipelineResult.toc) || [];
    $('toc-count').textContent = toc.length ? toc.length + ' 项' : '';
    if (!toc.length) { list.innerHTML = '<div class="toc-empty">本文档没有标题</div>'; return; }
    list.innerHTML = toc.map(function (t) {
      return '<a class="toc-item lv' + Math.min(6, t.level) + '" href="#' + esc(t.id) + '" data-id="' + esc(t.id) + '">' +
        esc(t.text) + '</a>';
    }).join('');
    Array.prototype.forEach.call(list.querySelectorAll('.toc-item'), function (a) {
      a.addEventListener('click', function (e) {
        e.preventDefault();
        var target = document.getElementById(a.getAttribute('data-id'));
        if (target) target.scrollIntoView({ block: 'start', behavior: 'smooth' });
        closePanels();
      });
    });
    observeHeadings();
  }

  var headingObserver = null;
  function observeHeadings() {
    if (!window.IntersectionObserver) return;
    if (headingObserver) headingObserver.disconnect();
    var heads = doc.querySelectorAll('h1,h2,h3,h4,h5,h6');
    if (!heads.length) return;
    headingObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        var id = en.target.id; if (!id) return;
        currentHeadingId = id;
        Array.prototype.forEach.call(document.querySelectorAll('.toc-item'), function (a) {
          a.classList.toggle('active', a.getAttribute('data-id') === id);
        });
      });
    }, { rootMargin: '-64px 0px -72% 0px' });
    Array.prototype.forEach.call(heads, function (h) { headingObserver.observe(h); });
  }

  /* ---------------- 图片：放大 + 失败兜底 ---------------- */
  function bindImageFallback() {
    doc.addEventListener('error', function (e) {
      var el = e.target;
      if (!el || el.tagName !== 'IMG') return;
      var rel = decodeURIComponent((el.getAttribute('src') || '').split('?')[0].split('/img/').pop() || '');
      var fb = document.createElement('span');
      fb.className = 'img-fallback';
      fb.innerHTML = '<b>图片无法显示：' + esc(rel) + '</b>' +
        '文档引用了同目录（或相对路径）的图片。若文件是从文件管理器直接打开的，应用可能拿不到该目录的读取授权。' +
        '<button type="button" data-folder>授权该文件夹</button>';
      if (el.parentNode) el.parentNode.replaceChild(fb, el);
    }, true);
  }

  /* ---------------- 搜索 ---------------- */
  var hits = [], hitIndex = -1;
  function runSearch(q) {
    clearHits();
    q = (q || '').trim();
    if (!q) { $('search-stat').textContent = ''; return; }
    if (chunks.length) { renderAllChunks(); }
    var walker = document.createTreeWalker(doc, NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
        var p = node.parentElement;
        if (!p) return NodeFilter.FILTER_REJECT;
        if (p.closest('script,style,.code-head,.katex,.lightbox,.img-fallback')) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var nodes = [], n;
    while ((n = walker.nextNode())) nodes.push(n);
    var low = q.toLowerCase();
    nodes.forEach(function (node) {
      var text = node.nodeValue, l = text.toLowerCase();
      var idx = l.indexOf(low), last = 0;
      if (idx < 0) return;
      var frag = document.createDocumentFragment();
      while (idx >= 0) {
        if (idx > last) frag.appendChild(document.createTextNode(text.slice(last, idx)));
        var mk = document.createElement('mark');
        mk.className = 'hit';
        mk.textContent = text.slice(idx, idx + q.length);
        frag.appendChild(mk); hits.push(mk);
        last = idx + q.length; idx = l.indexOf(low, last);
      }
      if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
      node.parentNode.replaceChild(frag, node);
    });
    hitIndex = hits.length ? 0 : -1;
    focusHit(false);
    $('search-stat').textContent = hits.length ? '1/' + hits.length : '无结果';
  }

  function clearHits() {
    hits.forEach(function (mk) {
      var p = mk.parentNode; if (!p) return;
      p.replaceChild(document.createTextNode(mk.textContent), mk);
      p.normalize();
    });
    hits = []; hitIndex = -1;
  }

  function focusHit(scroll) {
    hits.forEach(function (mk, i) { mk.classList.toggle('current', i === hitIndex); });
    if (hitIndex >= 0 && hits[hitIndex] && scroll !== false) {
      hits[hitIndex].scrollIntoView({ block: 'center', behavior: 'smooth' });
      $('search-stat').textContent = (hitIndex + 1) + '/' + hits.length;
    }
  }

  function stepHit(d) {
    if (!hits.length) return;
    hitIndex = (hitIndex + d + hits.length) % hits.length;
    focusHit(true);
  }

  /* ---------------- 位置记忆 ---------------- */
  function posKey() { return 'lanyue:pos:' + (state.docKey || 'unknown'); }
  var currentHeadingId = '';
  function savePosition() {
    if (!state.hasDoc) return;
    var pct = docScrollPct();
    try {
      localStorage.setItem(posKey(), JSON.stringify({ p: pct, h: currentHeadingId, t: Date.now() }));
    } catch (e) {}
  }
  function restorePosition() {
    if (!state.hasDoc) return;
    var raw = null;
    try { raw = localStorage.getItem(posKey()); } catch (e) {}
    if (!raw) return;
    var p; try { p = JSON.parse(raw); } catch (e) { return; }
    if (!p) return;
    if (chunks.length) renderAllChunks();
    setTimeout(function () {
      if (p.h) {
        var el = document.getElementById(p.h);
        if (el) { el.scrollIntoView({ block: 'start' }); showPct(); return; }
      }
      if (p.p > 0.005) { window.scrollTo(0, Math.round(p.p * (document.documentElement.scrollHeight - window.innerHeight))); showPct(); }
    }, 60);
  }

  function docScrollPct() {
    var h = document.documentElement.scrollHeight - window.innerHeight;
    return h > 0 ? Math.max(0, Math.min(1, window.scrollY / h)) : 0;
  }

  /* ---------------- 滚动 / 进度 / 布局上报 ---------------- */
  var lastSave = 0, pctTimer = 0;
  function onScroll() {
    var pct = docScrollPct();
    var bar = $('progress');
    bar.style.width = (pct * 100).toFixed(2) + '%';
    bar.classList.add('show');
    showPct(pct);
    var now = Date.now();
    if (now - lastSave > 600) { lastSave = now; savePosition(); }
    updateLayout();
  }
  function showPct(pct) {
    var el = $('pct');
    if (pct === undefined) pct = docScrollPct();
    el.textContent = Math.round(pct * 100) + '%';
    el.classList.add('show');
    clearTimeout(pctTimer);
    pctTimer = setTimeout(function () { el.classList.remove('show'); }, 1400);
  }
  function updateLayout() {
    try {
      B.layout(document.documentElement.scrollHeight, window.innerHeight, window.scrollY);
    } catch (e) {}
  }

  /* ---------------- 面板开关 ---------------- */
  function openPanel(el) {
    closePanels();
    el.classList.add('show');
    $('scrim').classList.add('show');
  }
  function closePanels() {
    ['toc', 'search', 'sheet'].forEach(function (id) { $(id).classList.remove('show'); });
    $('scrim').classList.remove('show');
  }

  /* ---------------- 设置面板 ---------------- */
  function buildSheet() {
    var s = state.settings || {};
    var st = (pipelineResult && pipelineResult.stats) || {};
    var body = $('sheet-body');
    var html = '';
    html += '<div class="gl">本文</div>';
    html += row('info', '文件', state.fileName || '（未打开）');
    html += row('info', '编码', (state.encoding || 'UTF-8') + (state.canWrite ? ' · 可写' : ' · 只读，保存将另存为'));
    html += row('info', '规模', fmtSize(sourceText.length) + ' · ' + (st.headings || 0) + ' 个标题 · ' + (st.images || 0) + ' 张图 · ' + (st.formulas || 0) + ' 处公式');
    html += row('action', '编辑本文', '打开原生编辑器', 'act-edit');
    html += row('action', '另存为…', '导出为新的 .md 文件', 'act-saveas');
    html += '<div class="gl">导出</div>';
    html += row('action', '导出 PDF', '走系统打印链路，可选择纸张与边距', 'act-pdf');
    html += row('action', '导出长图（自动分片）', '每片不超过 12000px，适合分享', 'act-img-pages');
    html += row('action', '导出当前屏', '只截取当前可见区域', 'act-img-view');
    html += row('action', '导出选区', '先在正文中选择内容，再点这里', 'act-img-sel');
    html += row('action', '分享上次导出', lastExportPayload ? '最近：' + lastExportPayload : '还没有导出记录', 'act-share');
    html += row('switch', '高质量图片导出', '图片按原图进 PDF/长图（更慢更大）', 'set-hq', !!s.hqExport);
    html += '<div class="gl">阅读</div>';
    html += row('seg', '主题', '', 'set-theme');
    html += row('step', '字号', '', 'set-font');
    html += row('switch', '编辑后自动保存', '关闭则每次手动保存', 'set-autosave', s.autosave !== false);
    html += '<div class="gl">高级</div>';
    html += row('switch', '所有文件访问', '开启后可直接读写任意目录的 .md（需在系统设置中授权）', 'set-allfiles', !!s.allFilesAccess);
    html += row('info', 'WebView 内核', shortUa(state.webview || ''));
    html += row('info', '版本', '蓝阅 ' + (state.appVersion || '1.0.0'));
    html += row('action', '关闭', '', 'act-close');
    body.innerHTML = html;

    // 主题三态
    var seg = body.querySelector('[data-k="set-theme"] .seg');
    if (seg) {
      ['system|跟随系统', 'light|浅色', 'dark|深色'].forEach(function (pair) {
        var v = pair.split('|')[0], label = pair.split('|')[1];
        var b = document.createElement('button');
        b.textContent = label;
        b.className = ((state.theme || 'system') === v) ? 'on' : '';
        b.addEventListener('click', function () {
          state.theme = v; B.setSetting('theme', v);
          applyChrome(state); buildSheet();
        });
        seg.appendChild(b);
      });
    }
    // 字号
    var step = body.querySelector('[data-k="set-font"] .step');
    if (step) {
      var minus = document.createElement('button'); minus.textContent = '−';
      var val = document.createElement('span'); val.className = 'n';
      val.textContent = Math.round((state.fontScale || 1) * 100) + '%';
      var plus = document.createElement('button'); plus.textContent = '+';
      minus.addEventListener('click', function () { setFont(-0.075); });
      plus.addEventListener('click', function () { setFont(0.075); });
      step.appendChild(minus); step.appendChild(val); step.appendChild(plus);
    }
    // 开关
    Array.prototype.forEach.call(body.querySelectorAll('.sw input'), function (input) {
      input.addEventListener('change', function () {
        var k = input.getAttribute('data-key');
        var on = input.checked;
        var key = k === 'set-hq' ? 'hqExport' : (k === 'set-autosave' ? 'autosave' : 'allFilesAccess');
        state.settings[key] = on;
        B.setSetting(key, on ? 'true' : 'false');
        if (k === 'set-allfiles' && on) B.toast('需在系统「所有文件访问」中手动允许');
      });
    });
  }

  function setFont(delta) {
    var v = Math.max(0.85, Math.min(1.7, Math.round(((state.fontScale || 1) + delta) * 1000) / 1000));
    state.fontScale = v;
    document.documentElement.style.setProperty('--font-scale', String(v));
    B.setSetting('fontScale', String(v));
    buildSheet();
  }

  function row(kind, label, sub, key, checked) {
    if (kind === 'info') {
      return '<div class="row" data-k="' + key + '"><span class="lbl">' + esc(label) + '</span><span class="val">' + esc(sub) + '</span></div>';
    }
    if (kind === 'action') {
      return '<button class="row" data-k="' + key + '" style="width:100%;text-align:left">' +
        '<span class="lbl">' + esc(label) + (sub ? '<small>' + esc(sub) + '</small>' : '') + '</span><span class="val">›</span></button>';
    }
    if (kind === 'switch') {
      return '<div class="row" data-k="' + key + '"><span class="lbl">' + esc(label) +
        (sub ? '<small>' + esc(sub) + '</small>' : '') + '</span>' +
        '<span class="sw"><input type="checkbox" data-key="' + key + '"' + (checked ? ' checked' : '') +
        '><span class="track"></span><span class="knob"></span></span></div>';
    }
    if (kind === 'seg') {
      return '<div class="row" data-k="' + key + '"><span class="lbl">' + esc(label) + '</span><span class="seg"></span></div>';
    }
    if (kind === 'step') {
      return '<div class="row" data-k="' + key + '"><span class="lbl">' + esc(label) + '</span><span class="step"></span></div>';
    }
    return '';
  }

  /* ---------------- 导出 ---------------- */
  function prepareForExport() {
    // 关键：顶栏是 position:fixed，逐屏抓取会让它出现在每一片里 —— 导出期间整体隐藏
    document.documentElement.classList.add('exporting');
    // 大文档先把所有分片渲染出来，并强制图片立即解码，避免导出出现空白
    if (chunks.length) renderAllChunks();
    var imgs = doc.querySelectorAll('img');
    Array.prototype.forEach.call(imgs, function (im) { im.setAttribute('loading', 'eager'); });
    var waits = [];
    Array.prototype.forEach.call(imgs, function (im) {
      if (im.decode) { try { waits.push(im.decode().catch(function () {})); } catch (e) {} }
    });
    return Promise.all(waits);
  }

  function exportPdf() {
    closePanels();
    B.toast('正在准备 PDF…');
    prepareForExport().then(function () {
      updateLayout();
      B.exportPdf(!!(state.settings && state.settings.hqExport));
    });
  }

  function exportImages(mode) {
    closePanels();
    var payload = { mode: mode, chunkHeight: 12000, scale: 2 };
    if (mode === 'selection') {
      var sel = window.getSelection();
      if (!sel || sel.isCollapsed || !sel.rangeCount) { B.toast('请先在正文中选择要导出的内容'); return; }
      var range = sel.getRangeAt(0);
      if (doc.contains(range.commonAncestorContainer) === false && !doc.contains(range.startContainer)) {
        B.toast('请选择正文中的内容'); return;
      }
      var rects = range.getClientRects();
      var top = Infinity, bottom = -Infinity;
      for (var i = 0; i < rects.length; i++) {
        top = Math.min(top, rects[i].top); bottom = Math.max(bottom, rects[i].bottom);
      }
      if (!isFinite(top) || !isFinite(bottom)) { B.toast('无法获取选区位置'); return; }
      payload.top = Math.round(top + window.scrollY);
      payload.bottom = Math.round(bottom + window.scrollY);
      sel.removeAllRanges();
    }
    B.toast(mode === 'viewport' ? '正在导出当前屏…' : '正在导出长图…');
    prepareForExport().then(function () {
      updateLayout();
      B.exportImages(mode, JSON.stringify(payload));
    });
  }

  /* ---------------- 事件绑定 ---------------- */
  function bindTap(id, fn) { var el = $(id); if (el) el.addEventListener('click', fn); }

  function wireEvents() {
    bindTap('btn-toc', function () {
      if (!pipelineResult || !(pipelineResult.toc || []).length) { B.toast('本文档没有标题'); return; }
      openPanel($('toc'));
    });
    bindTap('btn-home', function () { renderHome(); });
    bindTap('btn-search', function () {
      openPanel($('search'));
      setTimeout(function () { $('search-input').focus(); }, 120);
    });
    bindTap('btn-edit', function () {
      if (!state.hasDoc) { B.toast('请先打开一个文件'); return; }
      B.editDoc(sourceText);
    });
    bindTap('btn-more', function () { buildSheet(); openPanel($('sheet')); });
    bindTap('search-close', function () { clearHits(); $('search-input').value = ''; $('search-stat').textContent = ''; closePanels(); });
    bindTap('search-prev', function () { stepHit(-1); });
    bindTap('search-next', function () { stepHit(1); });
    bindTap('scrim', closePanels);
    bindTap('lightbox', function () { $('lightbox').classList.remove('show'); });
    bindTap('lightbox-close', function () { $('lightbox').classList.remove('show'); });

    var si = $('search-input');
    var t = 0;
    si.addEventListener('input', function () {
      clearTimeout(t);
      t = setTimeout(function () { runSearch(si.value); }, 220);
    });
    si.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); clearTimeout(t); runSearch(si.value); stepHit(1); }
    });

    // 正文点击：复制按钮 / 图片放大 / 授权文件夹 / 链接
    doc.addEventListener('click', function (e) {
      var copy = e.target.closest && e.target.closest('.copy[data-copy]');
      if (copy) {
        var code = copy.closest('.code-block').querySelector('pre code');
        copyText(code ? code.innerText : '');
        copy.textContent = '已复制'; copy.classList.add('done');
        setTimeout(function () { copy.textContent = '复制'; copy.classList.remove('done'); }, 1400);
        return;
      }
      var fo = e.target.closest && e.target.closest('[data-folder]');
      if (fo) { B.openFolder(); return; }
      var img = e.target.closest && e.target.closest('img');
      if (img && img.closest('#doc')) {
        var lb = $('lightbox');
        $('lightbox-img').src = hqSrc(img.getAttribute('src'));
        lb.classList.add('show');
        return;
      }
      var a = e.target.closest && e.target.closest('a');
      if (a) {
        var href = a.getAttribute('href') || '';
        if (href.charAt(0) === '#') { e.preventDefault(); var tgt = document.getElementById(href.slice(1)); if (tgt) tgt.scrollIntoView({ block: 'start', behavior: 'smooth' }); return; }
        if (/^https?:/i.test(href)) { e.preventDefault(); B.openUrl ? B.openUrl(href) : B.toast('外部链接：' + href); return; }
        if (!/^(https?:|#)/i.test(href)) { e.preventDefault(); B.toast('相对链接暂不支持跳转：' + href); }
      }
    });

    // 双击标题回顶
    $('doc-title').addEventListener('dblclick', function () { window.scrollTo({ top: 0, behavior: 'smooth' }); });

    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', function () { updateLayout(); }, { passive: true });
    document.addEventListener('visibilitychange', function () { if (document.hidden) savePosition(); });
    window.addEventListener('beforeunload', savePosition);

    if (window.matchMedia) {
      var mq = window.matchMedia('(prefers-color-scheme: dark)');
      var onScheme = function () { if ((state.theme || 'system') === 'system') applyChrome(state); };
      if (mq.addEventListener) mq.addEventListener('change', onScheme);
      else if (mq.addListener) mq.addListener(onScheme);
    }
  }

  function hqSrc(src) {
    if (!src) return src;
    if (window.__MOCK__) return src;
    return src.split('?')[0] + '?hq=1';
  }

  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).catch(function () { legacyCopy(text); });
    } else legacyCopy(text);
  }
  function legacyCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); } catch (e) {}
    document.body.removeChild(ta);
  }

  /* ---------------- 供原生调用 ---------------- */
  window.LanyueApp = {
    applyState: function (json) {
      try {
        var s = typeof json === 'string' ? JSON.parse(json) : json;
        state = Object.assign({}, state, s);
        applyChrome(state);
        if (document.querySelector('.sheet.show')) buildSheet();
      } catch (e) {}
    },
    toast: function (msg) { toast(msg); },
    notify: function (kind, ok, msg) {
      toast(msg);
      if (ok && (kind === 'pdf' || kind === 'image')) lastExportPayload = msg;
    },
    /** 返回键：先关灯箱、再关面板；返回 true 表示"我处理掉了" */
    back: function () {
      if ($('lightbox').classList.contains('show')) { $('lightbox').classList.remove('show'); return true; }
      if ($('toc').classList.contains('show') || $('search').classList.contains('show') || $('sheet').classList.contains('show')) {
        closePanels();
        return true;
      }
      return false;
    },
    goHome: function () {
      closePanels();
      $('lightbox').classList.remove('show');
      renderHome();
      try { B.viewChanged('home'); } catch (e) {}
      return true;
    },
    scrollToTop: function () { window.scrollTo({ top: 0, behavior: 'smooth' }); },
    savePositionNow: function () { savePosition(); return ''; },
    /** 原生导出结束（无论成败）：恢复固定顶栏 */
    exportFinished: function () {
      document.documentElement.classList.remove('exporting');
      return true;
    },
    reloadDoc: function () { if (!window.__MOCK__) loadDoc(); return 1; },
    stats: function () { return JSON.stringify({ scrollHeight: document.documentElement.scrollHeight, innerHeight: window.innerHeight, scrollY: window.scrollY }); }
  };

  function toast(msg) {
    var el = $('toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('show'); }, 2200);
  }

  /* 设置面板里的动作按钮（事件委托，因为面板是动态重建的） */
  document.addEventListener('click', function (e) {
    var el = e.target.closest && e.target.closest('[data-k^="act-"]');
    if (!el) return;
    var k = el.getAttribute('data-k');
    if (k === 'act-edit') { closePanels(); B.editDoc(sourceText); }
    else if (k === 'act-saveas') { closePanels(); B.saveAs(state.fileName || 'untitled.md', sourceText); }
    else if (k === 'act-pdf') exportPdf();
    else if (k === 'act-img-pages') exportImages('pages');
    else if (k === 'act-img-view') exportImages('viewport');
    else if (k === 'act-img-sel') exportImages('selection');
    else if (k === 'act-share') { closePanels(); B.shareLast(); }
    else if (k === 'act-close') closePanels();
  });

  /* ---------------- 工具 ---------------- */
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function fmtSize(n) {
    n = n || 0;
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1048576).toFixed(1) + ' MB';
  }
  function initials(name) {
    name = (name || '?').replace(/\.(md|markdown|txt)$/i, '');
    return name.slice(0, 2) || '?';
  }
  function shortUa(ua) {
    var m = /Chrome\/(\d+[\d.]*)/.exec(ua || '');
    return m ? 'Chromium ' + m[1] : (ua ? ua.slice(0, 40) : '未知');
  }

  /* ---------------- go ---------------- */
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
