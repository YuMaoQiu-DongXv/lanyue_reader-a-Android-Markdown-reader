#!/usr/bin/env bash
# 把 app/assets/web 同步到 _work/render-test（桌面 headless 自查用），并可选启动静态服务器。
# 用法：
#   bash tools/sync-render-test.sh          # 只同步
#   bash tools/sync-render-test.sh serve    # 同步 + 前台启动 http 服务（根目录 = D:\DSH program）
set -e
ROOT="/d/DSH program"
SRC="$ROOT/mdreader/app/assets/web"
DST="$ROOT/_work/render-test"
mkdir -p "$DST"
cp -f "$SRC/index.html" "$SRC/app.css" "$SRC/app.js" "$SRC/pipeline.js" "$DST/"
rm -rf "$DST/vendor"
cp -r "$SRC/vendor" "$DST/vendor"
cp -f "$ROOT/mdreader/tools/rendertest/test.html" "$DST/test.html"
cp -f "$ROOT/mdreader/tools/rendertest/formula.html" "$DST/formula.html"
cp -f "$ROOT/mdreader/tools/rendertest/code.html" "$DST/code.html"
cp -f "$ROOT/mdreader/tools/rendertest/layout.html" "$DST/layout.html"
rm -rf "$DST/testdoc" && cp -r "$ROOT/mdreader/testdoc" "$DST/testdoc"
cp -f "$DST/testdoc/蓝阅验收测试.md" "$DST/testdoc/acceptance.md"
echo "synced -> $DST"
ls -1 "$DST"
if [ "$1" = "serve" ]; then
  echo "serving http://127.0.0.1:8791/  (root: $ROOT)"
  cd "$ROOT" && python -m http.server 8791 --bind 127.0.0.1
fi
