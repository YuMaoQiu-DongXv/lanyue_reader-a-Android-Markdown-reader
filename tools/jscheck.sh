#!/usr/bin/env bash
# JS 语法闸门：对纯 JS 文件直接 node --check；对 HTML 抽取内联 <script> 后校验。
# 用法： bash tools/jscheck.sh <file> [file...]
set -u
ROOT="/d/DSH program"
TMP="$ROOT/_work/_jscheck.js"
mkdir -p "$ROOT/_work"
fail=0
for f in "$@"; do
  case "$f" in
    *.html)
      python - "$f" "$TMP" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
blocks = re.findall(r'<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>', src, re.S)
open(sys.argv[2], 'w', encoding='utf-8').write('\n;\n'.join(blocks))
print('  html: extracted %d inline block(s), %d chars' % (len(blocks), sum(len(b) for b in blocks)))
PY
      target="$TMP"
      ;;
    *) target="$f" ;;
  esac
  if node --check "$target" 2>/tmp/jscheck.err; then
    echo "SYNTAX OK   $f"
  else
    echo "SYNTAX FAIL $f"
    sed 's/^/    /' /tmp/jscheck.err | head -20
    fail=1
  fi
done
exit $fail
