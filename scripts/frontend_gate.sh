#!/usr/bin/env bash
#
# scripts/frontend_gate.sh — task-0012 前端本地门禁（spec: 2026-11-11_frontend-quality / C12）
#
# 把此前各人手动跑的 typecheck / lint / 单测 / build 串成一条可复跑命令（spec 背景 10）。
# 四个阶段统一在 frontend/ 目录内执行，对应 docs/frontend-quality.md 的四条基础命令：
#
#   1. pnpm typecheck                       （vue-tsc --noEmit --skipLibCheck）
#   2. pnpm exec eslint . --max-warnings 0  （0 警告容忍；失败处理不得放宽 --max-warnings，
#                                            修复动作见 docs/frontend-quality.md §4.2）
#   3. pnpm test:unit                       （vitest run；全部用例无后端、无外部服务可跑）
#   4. pnpm build                           （vite build --mode prod；仅 --full 追加）
#
# 语义：
#   - fail-fast：任一阶段失败即把阶段名打到 stderr 并以 1 退出，后续阶段不再执行。
#   - 缺 frontend/node_modules 时打印 `cd frontend && pnpm install` 提示并以 1 退出，不静默失败。
#   - 脚本从仓库任意目录调用均可（REPO_ROOT 取脚本自身位置推导）。
#   - 本门禁是本地闸门，不改 .github/workflows/**（spec R1：CI 不接 e2e，本轮不新增工作流）。
#
# 用法：
#   bash scripts/frontend_gate.sh --check      阶段 1→2→3（verify 命令）
#   bash scripts/frontend_gate.sh --full       阶段 1→2→3→4（提交 / 发布前）
#   bash scripts/frontend_gate.sh --self-test  临时目录自检：用 pnpm shim 模拟阶段失败并断言退出码
#
# 退出码：0 = 全部阶段通过；1 = 有阶段失败 / node_modules 缺失 / pnpm 不可用；2 = 用法错误
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEFAULT_FRONTEND_DIR="$REPO_ROOT/frontend"

# 在指定目录的子 shell 里执行命令（不污染调用方 cwd；--self-test 时 PATH 前置的 pnpm shim 对子 shell 生效）
in_dir() {
  local dir="$1"; shift
  (cd "$dir" && "$@")
}

# 执行单个阶段：先打印阶段名；命令退出非 0 时把阶段名打到 stderr 并返回 1（调用方据此 fail-fast）
run_stage() {
  local name="$1"; shift
  echo "frontend_gate: [阶段] $name"
  if "$@"; then
    echo "frontend_gate: [通过] $name"
    return 0
  fi
  echo "frontend_gate: [失败] 阶段 '$name' 退出码非 0 —— 依据上方该阶段的输出定位修复；后续阶段因 fail-fast 未执行" >&2
  return 1
}

# 门禁主体。参数：frontend_dir  mode（check|full）
run_gate() {
  local frontend_dir="$1" mode="$2"

  if [ ! -d "$frontend_dir" ]; then
    echo "frontend_gate: frontend 目录不存在：$frontend_dir" >&2
    return 1
  fi

  # 缺依赖不静默失败：直接给出安装命令（任务书交付①）
  if [ ! -d "$frontend_dir/node_modules" ]; then
    echo "frontend_gate: 缺少 $frontend_dir/node_modules —— 依赖未安装或被清空" >&2
    echo "frontend_gate: 先运行:  cd frontend && pnpm install  然后重跑本门禁" >&2
    return 1
  fi

  if ! command -v pnpm >/dev/null 2>&1; then
    echo "frontend_gate: 未找到 pnpm 命令 —— 先安装（corepack enable 或 npm i -g pnpm，要求 >= 8.7）" >&2
    return 1
  fi

  run_stage "typecheck (pnpm typecheck)" in_dir "$frontend_dir" pnpm typecheck || return 1
  run_stage "eslint 0-warnings (pnpm exec eslint . --max-warnings 0)" in_dir "$frontend_dir" pnpm exec eslint . --max-warnings 0 || return 1
  run_stage "unit tests (pnpm test:unit)" in_dir "$frontend_dir" pnpm test:unit || return 1

  if [ "$mode" = "full" ]; then
    run_stage "build (pnpm build)" in_dir "$frontend_dir" pnpm build || return 1
  fi

  # macOS 自带 bash 3.2 在 $var 后紧跟全角标点时会吞多字节首字节（见 i18n_gate.sh 同类注释），必须用 ${var} 花括号形式
  echo "frontend_gate: 全部阶段通过（mode=${mode}，dir=${frontend_dir}）"
  return 0
}

run_self_test() {
  local tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/frontend_gate_selftest.XXXXXX")"
  # trap 里此刻就展开 $tmp（函数返回后 local 变量已出栈，届时展开会在 set -u 下报 unbound）
  trap "rm -rf '$tmp'" EXIT

  # pnpm 假实现（shim）：把调用参数追加到 $SHIM_LOG；$SHIM_FAIL_FILE 里列出的阶段模拟失败（exit 1）。
  # 真实 pnpm 不被调用，因此 --self-test 无需 node_modules、无需 dev server、无外部服务。
  local shim_dir="$tmp/bin"
  mkdir -p "$shim_dir"
  cat > "$shim_dir/pnpm" <<'SHIM'
#!/usr/bin/env bash
set -u
stage="${1:-}"
if [ "$stage" = "exec" ] && [ "$#" -ge 2 ]; then stage="$2"; fi
echo "pnpm-shim: $*" >> "$SHIM_LOG"
if [ -n "${SHIM_FAIL_FILE:-}" ] && [ -f "$SHIM_FAIL_FILE" ] && grep -Fxq "$stage" "$SHIM_FAIL_FILE"; then
  echo "pnpm-shim: simulated failure of stage '$stage'" >&2
  exit 1
fi
exit 0
SHIM
  chmod +x "$shim_dir/pnpm"

  local shim_log="$tmp/shim.log" fail_file="$tmp/fail_stage"
  : > "$shim_log"
  export SHIM_LOG="$shim_log" SHIM_FAIL_FILE="$fail_file"
  PATH="$shim_dir:$PATH"

  local fdir="$tmp/frontend"
  mkdir -p "$fdir/node_modules"

  local rc=0
  local out

  # 断言 1（对照组）：--check 全部阶段通过必须退出 0，且 pnpm 调用顺序为 typecheck → eslint → test:unit
  if out="$(run_gate "$fdir" check 2>&1)"; then
    if [ "$(wc -l < "$shim_log" | tr -d ' ')" -eq 3 ] \
      && printf '%s' "$(head -n 1 "$shim_log")" | grep -q 'typecheck' \
      && printf '%s' "$(head -n 2 "$shim_log" | tail -n 1)" | grep -q 'eslint' \
      && printf '%s' "$(tail -n 1 "$shim_log")" | grep -q 'test:unit'; then
      echo "self-test 1/4 PASS：--check 全阶段通过且顺序为 typecheck → eslint → test:unit"
    else
      echo "self-test 1/4 FAIL：--check 退出 0 但 shim 调用记录不符预期："
      cat "$shim_log"
      rc=1
    fi
  else
    echo "self-test 1/4 FAIL：--check 应通过，实际失败："
    echo "$out"
    rc=1
  fi

  # 断言 2（任务书点名）：模拟 eslint 阶段失败 → 退出码非 0、输出点名该阶段，且 fail-fast 不再跑 test:unit
  echo 'eslint' > "$fail_file"
  : > "$shim_log"
  out="$(run_gate "$fdir" check 2>&1)"
  if [ $? -eq 0 ]; then
    echo "self-test 2/4 FAIL：模拟 eslint 失败应非 0 退出，实际通过"
    rc=1
  elif printf '%s' "$out" | grep -q "\[失败\] 阶段 'eslint 0-warnings"; then
    if grep -q 'test:unit' "$shim_log"; then
      echo "self-test 2/4 FAIL：fail-fast 生效但 test:unit 仍被执行："
      cat "$shim_log"
      rc=1
    else
      echo "self-test 2/4 PASS：模拟 eslint 阶段失败 → 退出非 0、输出点名阶段，且后续 test:unit 未执行"
    fi
  else
    echo "self-test 2/4 FAIL：失败输出未点名 eslint 阶段："
    echo "$out"
    rc=1
  fi
  : > "$fail_file"

  # 断言 3（任务书点名）：缺 node_modules → 退出非 0 且提示 pnpm install（不静默失败）
  rm -rf "$fdir/node_modules"
  : > "$shim_log"
  out="$(run_gate "$fdir" check 2>&1)"
  if [ $? -eq 0 ]; then
    echo "self-test 3/4 FAIL：缺 node_modules 应非 0 退出，实际通过"
    rc=1
  elif printf '%s' "$out" | grep -q 'pnpm install'; then
    echo "self-test 3/4 PASS：缺 node_modules 退出非 0 且提示 pnpm install"
  else
    echo "self-test 3/4 FAIL：缺 node_modules 失败但未提示 pnpm install："
    echo "$out"
    rc=1
  fi
  mkdir -p "$fdir/node_modules"

  # 断言 4：--full 追加 build 阶段（第 4 次 pnpm 调用是 build）且整体退出 0
  : > "$shim_log"
  if out="$(run_gate "$fdir" full 2>&1)"; then
    if [ "$(wc -l < "$shim_log" | tr -d ' ')" -eq 4 ] \
      && printf '%s' "$(tail -n 1 "$shim_log")" | grep -q 'build'; then
      echo "self-test 4/4 PASS：--full 在三阶段后追加 build 且退出 0"
    else
      echo "self-test 4/4 FAIL：--full 退出 0 但 shim 调用记录不符预期："
      cat "$shim_log"
      rc=1
    fi
  else
    echo "self-test 4/4 FAIL：--full 应通过，实际失败："
    echo "$out"
    rc=1
  fi

  if [ "$rc" -eq 0 ]; then
    echo "self-test: PASS"
  else
    echo "self-test: FAIL"
  fi
  return "$rc"
}

case "${1:-}" in
  --check)
    run_gate "$DEFAULT_FRONTEND_DIR" check
    exit $?
    ;;
  --full)
    run_gate "$DEFAULT_FRONTEND_DIR" full
    exit $?
    ;;
  --self-test)
    run_self_test
    exit $?
    ;;
  *)
    echo "用法: bash scripts/frontend_gate.sh --check | --full | --self-test" >&2
    echo "  --check      pnpm typecheck → pnpm exec eslint . --max-warnings 0 → pnpm test:unit（在 frontend/ 内）" >&2
    echo "  --full       --check 基础上追加 pnpm build" >&2
    echo "  --self-test  临时目录自检：模拟阶段失败并断言退出码" >&2
    exit 2
    ;;
esac
