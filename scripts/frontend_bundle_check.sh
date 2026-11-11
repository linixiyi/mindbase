#!/usr/bin/env bash
#
# scripts/frontend_bundle_check.sh — task-0013 构建产物体积预算门禁（spec: 2026-11-11_frontend-quality / C13）
#
# 跑一次 pnpm build，读取 frontend/dist/assets 的未压缩 JS 体积，打印 top5 chunk，
# 入口 chunk 超过预算即非 0 退出。--report-only 只打印测量结果，不做阈值判定。
#
# 预算与理由（本注释即任务书要求的"阈值与理由写进脚本注释"）：
#   阈值：入口 chunk 900KB（未压缩，ENTRY_BUDGET_BYTES = 900 * 1024 = 921600）。
#   理由：2026-11-11 基线实测入口 chunk 1,583,991B（约 1.5MB），原因是 markdown 渲染链
#   （vue-markdown-shiki，其 dist/index.mjs 预打包了 markdown-it + shiki 核心）与 naive-ui
#   被静态引入首屏。task-0013 在 frontend/vite.config.ts 的 manualChunks 把两者拆出后
#   实测入口 632,873B。900KB = 拆分后余量（约 270KB）之上的首屏主 chunk 上限：
#   任何人再把一个大依赖静态塞回首屏（> 270KB）都会撞线，而正常业务代码增长不受影响。
#   用未压缩体积而非 gzip：gzip 率随部署配置（nginx/brotli）波动，不可复现；
#   vite build 配置 reportCompressedSize=false，本地以 stat 字节数为准。
#
# 拆包回退记录（任务书失败处理：回退 manualChunks 项须在此登记，不得提高阈值掩盖）：
#   - 尚无回退项。
#   - 实施记录：manualChunks 若整包匹配 vue-markdown-shiki / @shikijs，会把 shiki 约 280 个
#     语言语法数据文件（vue-markdown-shiki dist 内的动态 import 目标）一并静态化，
#     产出 9.3MB 的 markdown chunk 并取消语言懒加载——因此 vite.config.ts 只匹配
#     vue-markdown-shiki/dist/index.mjs（静态入口）与 shiki 包的运行时顶层文件；
#     语言数据保持动态 chunk（实测 282 个语言 chunk 与拆分前字节级同名，懒加载行为不变）。
#     这是"匹配范围"的修正而非阈值调整。
#   - 权衡记录：拆出的 markdown（约 447KB）与 naive-ui（约 972KB）chunk 仍随首屏并行加载
#     （二者均被首屏代码静态依赖），拆分不减少首屏总字节，收益是入口主 chunk 缩小、
#     大依赖跨版本缓存稳定。若要进一步减少首屏字节需把静态依赖改为真正按需加载，属后续卡。
#
# 语义：
#   - 缺 frontend/node_modules 时提示 pnpm install 并以 1 退出，不静默失败（与 frontend_gate.sh 一致）。
#   - pnpm build 失败视为真实失败，--report-only 也不豁免（没有产物就无从测量）。
#   - 入口 chunk 通过解析 dist/index.html 的 <script type="module" src="..."> 定位（hash 每次构建都变）。
#   - 脚本从仓库任意目录调用均可（REPO_ROOT 取脚本自身位置推导）。
#   - 本门禁是本地闸门，不改 .github/workflows/**。
#
# 用法：
#   bash scripts/frontend_bundle_check.sh                构建 + 打印 top5 + 预算判定（verify 命令）
#   bash scripts/frontend_bundle_check.sh --report-only  构建 + 打印 top5，不做预算判定（首次测量用）
#
# 退出码：0 = 通过 / 仅报告；1 = 超预算 / 构建失败 / 环境缺失；2 = 用法错误
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FRONTEND_DIR="$REPO_ROOT/frontend"
DIST_DIR="$FRONTEND_DIR/dist"
ASSETS_DIR="$DIST_DIR/assets"

# 预算：900KB 未压缩（理由见文件头注释；改阈值须同时修订注释并在 PR 说明，禁止为通过而调高）
ENTRY_BUDGET_BYTES=$((900 * 1024))

# 跨平台取文件字节数（macOS stat -f %z / GNU stat -c %s）
file_size() {
  if stat -f %z "$1" >/dev/null 2>&1; then
    stat -f %z "$1"
  else
    stat -c %s "$1"
  fi
}

run_check() {
  local report_only="$1"

  if [ ! -d "$FRONTEND_DIR" ]; then
    echo "frontend_bundle_check: frontend 目录不存在：${FRONTEND_DIR}" >&2
    return 1
  fi

  if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
    echo "frontend_bundle_check: 缺少 ${FRONTEND_DIR}/node_modules —— 依赖未安装或被清空" >&2
    echo "frontend_bundle_check: 先运行:  cd frontend && pnpm install  然后重跑本脚本" >&2
    return 1
  fi

  if ! command -v pnpm >/dev/null 2>&1; then
    echo "frontend_bundle_check: 未找到 pnpm 命令 —— 先安装（corepack enable 或 npm i -g pnpm，要求 >= 8.7）" >&2
    return 1
  fi

  # 构建：日志收进临时文件，避免淹没 top5 输出；失败时倾倒尾部便于定位
  local build_log
  build_log="$(mktemp "${TMPDIR:-/tmp}/frontend_bundle_build.XXXXXX")"
  echo "frontend_bundle_check: [构建] (cd frontend && pnpm build)，日志：${build_log}"
  if ! (cd "$FRONTEND_DIR" && pnpm build) >"$build_log" 2>&1; then
    echo "frontend_bundle_check: [失败] pnpm build 退出非 0，输出尾部：" >&2
    tail -n 30 "$build_log" >&2
    rm -f "$build_log"
    return 1
  fi
  tail -n 3 "$build_log"
  rm -f "$build_log"

  if [ ! -f "$DIST_DIR/index.html" ]; then
    echo "frontend_bundle_check: [失败] 未找到 ${DIST_DIR}/index.html —— 构建产物不完整" >&2
    return 1
  fi
  if [ ! -d "$ASSETS_DIR" ]; then
    echo "frontend_bundle_check: [失败] 未找到 ${ASSETS_DIR} —— 构建产物不完整" >&2
    return 1
  fi

  # 入口 chunk：index.html 的 module script src 的文件名（hash 动态，不能写死）
  local entry_src entry_name entry_file entry_size
  entry_src="$(grep -o '<script type="module"[^>]*src="[^"]*"' "$DIST_DIR/index.html" | head -n 1 | sed 's/.*src="//; s/"$//')"
  if [ -z "$entry_src" ]; then
    echo "frontend_bundle_check: [失败] 未能在 dist/index.html 中定位 <script type=\"module\"> 入口" >&2
    return 1
  fi
  entry_name="$(basename "$entry_src")"
  entry_file="$ASSETS_DIR/$entry_name"
  if [ ! -f "$entry_file" ]; then
    entry_file="$(find "$DIST_DIR" -name "$entry_name" -type f 2>/dev/null | head -n 1)"
  fi
  if [ -z "$entry_file" ] || [ ! -f "$entry_file" ]; then
    echo "frontend_bundle_check: [失败] 入口脚本 ${entry_name} 不在构建产物中" >&2
    return 1
  fi
  entry_size="$(file_size "$entry_file")"

  # top5 chunk（未压缩字节，降序）
  echo "frontend_bundle_check: [top5 chunk]（${ASSETS_DIR} 下全部 .js，按未压缩大小降序）"
  local chunk_lines
  chunk_lines=""
  local f fsize fname
  for f in "$ASSETS_DIR"/*.js; do
    [ -f "$f" ] || continue
    fsize="$(file_size "$f")"
    fname="$(basename "$f")"
    chunk_lines="${chunk_lines}${fsize}"$'\t'"${fname}"$'\n'
  done
  if [ -z "$chunk_lines" ]; then
    echo "frontend_bundle_check: [失败] ${ASSETS_DIR} 下没有任何 .js 产物" >&2
    return 1
  fi
  printf '%s' "$chunk_lines" | sort -rn | head -n 5 | awk -F'\t' '{printf "  %10.1f KB  %s\n", $1 / 1024, $2}'

  # 入口行单独打印（即便未进 top5 也能看到）
  local entry_kb budget_kb
  entry_kb="$((entry_size / 1024)).$(( (entry_size % 1024) * 10 / 1024 ))"
  budget_kb=$((ENTRY_BUDGET_BYTES / 1024))
  echo "frontend_bundle_check: [入口] ${entry_name} = ${entry_kb} KB（未压缩，预算 ${budget_kb} KB）"

  if [ "$report_only" = "true" ]; then
    echo "frontend_bundle_check: --report-only 仅打印测量结果，不做预算判定"
    return 0
  fi

  if [ "$entry_size" -gt "$ENTRY_BUDGET_BYTES" ]; then
    echo "frontend_bundle_check: [失败] 入口 chunk ${entry_kb} KB 超过预算 ${budget_kb} KB ——" >&2
    echo "  请检查是否把新的大依赖静态引入首屏；修复方向是 frontend/vite.config.ts 的 manualChunks 拆分或改为路由级懒加载，" >&2
    echo "  不得以提高本脚本阈值的方式通过（见文件头注释）。" >&2
    return 1
  fi

  echo "frontend_bundle_check: [通过] 入口 chunk 在预算内（< ${budget_kb} KB）"
  return 0
}

case "${1:-}" in
  --report-only)
    run_check true
    exit $?
    ;;
  "")
    run_check false
    exit $?
    ;;
  *)
    echo "用法: bash scripts/frontend_bundle_check.sh [--report-only]" >&2
    echo "  （无参数）       pnpm build + 打印 top5 + 入口 chunk 预算判定（> 900KB 即非 0）" >&2
    echo "  --report-only    pnpm build + 打印 top5，不做预算判定" >&2
    exit 2
    ;;
esac
