#!/usr/bin/env bash
#
# scripts/frontend_a11y_gate.sh — task-0005 无障碍门禁（spec: 2026-11-11_frontend-quality / C5）
#
# 用 grep/awk 实现，不引入 eslint 插件依赖（spec 决策 D3）。检查项：
#   ① conversation-sidebar.vue：列表容器 role="listbox"；会话行 role="option" + tabindex="0"
#      + Enter/Space 键盘触发
#   ② conversation-sidebar.vue：操作按钮组 focus-within:opacity-100（键盘焦点可见，不再只随 hover 显形）
#   ③ input-box.vue：textarea aria-label="输入消息"
#   ④ input-box.vue：发送按钮 aria-label="发送"
#   ⑤ chat-list.vue：消息列表容器 aria-live="polite" 与 aria-busy（生成中置 true）
#   ⑥ conversation-sidebar.vue 不再有 click-only 的 div @click ——「div @click」在本门禁中的
#      语义：div 的 @click=（排除无 = 的 @click.stop 传播守卫）必须同时具备 role="option"、
#      tabindex="0" 与 @keydown.enter/@keydown.space；缺任一即视为键盘不可达的点击 div 回归。
#      @click 本身保留给鼠标（ARIA listbox 的 option 标准激活方式即 click + Enter/Space），
#      门禁禁止的是「只能点、不能键盘操作」的 div，而非禁止一切 div 点击。
#   ⑦ chat 模块（frontend/src/views/chat/）aria-live 出现次数 ≥1
#
# 所有 grep 都先剥离 HTML 注释（可跨行）再匹配——注释里的字样不算实现。
#
# 用法：
#   bash scripts/frontend_a11y_gate.sh --check      对真实 chat 模块跑全部检查
#   bash scripts/frontend_a11y_gate.sh --self-test  用临时 fixture 自检门禁逻辑（含「缺 aria-live 必失败」）
#
# 退出码：0 = 通过；1 = 有缺失项（逐条打印）；2 = 用法错误
#
# 已知限制（对当前代码成立）：awk 扫描以「行尾 > 」判定开标签结束，属性值内含行尾裸 ">" 会误判。
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEFAULT_CHAT_DIR="$REPO_ROOT/frontend/src/views/chat"

# 去掉 HTML 注释（<!-- --> 可跨行），防止注释字样造成假阳性
strip_html_comments() {
  awk '
    BEGIN { inc = 1 }
    {
      line = $0; out = ""
      while (length(line) > 0) {
        if (inc) {
          i = index(line, "<!--")
          if (i > 0) { out = out substr(line, 1, i - 1); line = substr(line, i + 4); inc = 0 }
          else       { out = out line; line = "" }
        } else {
          j = index(line, "-->")
          if (j > 0) { line = substr(line, j + 3); inc = 1 }
          else       { line = "" }
        }
      }
      print out
    }
  ' "$1"
}

# 扫描 <div ...> 开标签（多行感知）：含 @click= 但缺键盘可达三件套即违规（语义见文件头 ⑥）
click_only_div_scan() {
  awk '
    /<div([ \t>]|$)/ { collecting = 1; buf = "" }
    collecting { buf = buf $0 "\n" }
    collecting && />[ \t]*$/ {
      collecting = 0
      if (buf ~ /@click=/) {
        if (buf !~ /role="option"/ || buf !~ /tabindex="0"/ || buf !~ /@keydown\.enter/ || buf !~ /@keydown\.space/) {
          print "存在 click-only 的 div @click：@click= 的 div 必须同时具备 role=\"option\" + tabindex=\"0\" + @keydown.enter/@keydown.space（@click.stop 传播守卫除外）"
          found = 1
        }
      }
    }
    END { exit (found ? 1 : 0) }
  '
}

run_checks() {
  local chat_dir="$1"
  local sidebar="$chat_dir/modules/conversation-sidebar.vue"
  local input_box="$chat_dir/modules/input-box.vue"
  local chat_list="$chat_dir/modules/chat-list.vue"

  MISSING=()

  # 文件不存在时记录缺失并返回 1（调用方据此跳过该文件的逐项检查）
  _need_file() {
    if [ ! -f "$1" ]; then
      MISSING+=("[$2] 文件不存在：$1")
      return 1
    fi
    return 0
  }

  # 在剥离注释后的内容里找模式，找不到记缺失
  _grep_has() {
    if ! grep -q -- "$3" <(strip_html_comments "$2"); then
      MISSING+=("$1")
    fi
  }

  # ① ② ⑥ conversation-sidebar.vue
  if _need_file "$sidebar" "conversation-sidebar.vue"; then
    _grep_has '[conversation-sidebar.vue] 列表容器缺 role="listbox"' "$sidebar" 'role="listbox"'
    _grep_has '[conversation-sidebar.vue] 会话行缺 role="option"' "$sidebar" 'role="option"'
    _grep_has '[conversation-sidebar.vue] 会话行缺 tabindex="0"（键盘不可聚焦）' "$sidebar" 'tabindex="0"'
    _grep_has '[conversation-sidebar.vue] 会话行缺 Enter 键盘触发（@keydown.enter）' "$sidebar" '@keydown.enter'
    _grep_has '[conversation-sidebar.vue] 会话行缺 Space 键盘触发（@keydown.space）' "$sidebar" '@keydown.space'
    _grep_has '[conversation-sidebar.vue] 操作按钮组缺 focus-within:opacity-100（键盘焦点时不可见）' "$sidebar" 'focus-within:opacity-100'
    if ! scan_out="$(strip_html_comments "$sidebar" | click_only_div_scan)"; then
      MISSING+=("[conversation-sidebar.vue] ${scan_out}")
    fi
  fi

  # ③ ④ input-box.vue
  if _need_file "$input_box" "input-box.vue"; then
    _grep_has '[input-box.vue] textarea 缺 aria-label="输入消息"' "$input_box" 'aria-label="输入消息"'
    _grep_has '[input-box.vue] 发送按钮缺 aria-label="发送"' "$input_box" 'aria-label="发送"'
  fi

  # ⑤ chat-list.vue
  if _need_file "$chat_list" "chat-list.vue"; then
    _grep_has '[chat-list.vue] 消息列表容器缺 aria-live="polite"' "$chat_list" 'aria-live="polite"'
    _grep_has '[chat-list.vue] 消息列表容器缺 aria-busy（生成中应置 true）' "$chat_list" 'aria-busy'
  fi

  # ⑦ chat 模块 aria-live ≥1
  live_count="$(grep -rn "aria-live" "$chat_dir" 2>/dev/null | wc -l | tr -d ' ')"
  if [ "$live_count" -lt 1 ]; then
    MISSING+=("[chat 模块] aria-live 出现次数为 0（要求 ≥1）：$chat_dir")
  fi

  if [ "${#MISSING[@]}" -gt 0 ]; then
    echo "frontend_a11y_gate: 发现 ${#MISSING[@]} 项缺失："
    local item
    for item in "${MISSING[@]}"; do
      echo "  - $item"
    done
    return 1
  fi
  echo "frontend_a11y_gate: 通过（12 项检查，chat_dir=${chat_dir}）"
  return 0
}

run_self_test() {
  local tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/frontend_a11y_gate_selftest.XXXXXX")"
  # trap 里此刻就展开 $tmp（函数返回后 local 变量已出栈，届时展开会在 set -u 下报 unbound）
  trap "rm -rf '$tmp'" EXIT

  local fdir="$tmp/modules"
  mkdir -p "$fdir"

  local rc=0
  local out

  cat > "$fdir/conversation-sidebar.vue" <<'FIXTURE'
<template>
  <div class="sidebar">
    <TransitionGroup name="session-list" tag="div" role="listbox">
      <div
        v-for="s in sessions"
        :key="s.id"
        class="chat-session group"
        role="option"
        tabindex="0"
        :aria-selected="s.id === current"
        @click="handleRowActivate(s.id)"
        @keydown.enter.prevent="handleRowActivate(s.id)"
        @keydown.space.prevent="handleRowActivate(s.id)"
      >
        <div
          class="flex items-center transition-opacity"
          :class="{ 'opacity-0 group-hover:opacity-100 focus-within:opacity-100': true }"
          @click.stop
        >
          <NButton text size="tiny">重命名</NButton>
        </div>
      </div>
    </TransitionGroup>
  </div>
</template>
FIXTURE

  cat > "$fdir/input-box.vue" <<'FIXTURE'
<template>
  <textarea aria-label="输入消息" />
  <NButton aria-label="发送" />
  <NButton aria-label="停止生成" />
</template>
FIXTURE

  cat > "$fdir/chat-list.vue" <<'FIXTURE'
<template>
  <div aria-live="polite" :aria-busy="isGenerating">
    <ChatMessage v-for="m in list" :key="m.id" :msg="m" />
  </div>
</template>
FIXTURE

  # 断言 1：完整 fixture 必须通过（对照组——证明后续失败确实由缺失项引起）
  if out="$(run_checks "$tmp")"; then
    echo "self-test 1/3 PASS：完整 fixture 全部通过"
  else
    echo "self-test 1/3 FAIL：完整 fixture 应通过，实际失败："
    echo "$out"
    rc=1
  fi

  # 断言 2（任务书点名）：缺 aria-live 必失败，且失败输出点名 aria-live
  cat > "$fdir/chat-list.vue" <<'FIXTURE'
<template>
  <div :aria-busy="isGenerating">
    <ChatMessage v-for="m in list" :key="m.id" :msg="m" />
  </div>
</template>
FIXTURE
  if out="$(run_checks "$tmp")"; then
    echo "self-test 2/3 FAIL：缺 aria-live 应失败，实际通过"
    rc=1
  elif printf '%s' "$out" | grep -q 'aria-live'; then
    echo "self-test 2/3 PASS：缺 aria-live 失败且输出点名缺失项"
  else
    echo "self-test 2/3 FAIL：缺 aria-live 失败但输出未点名 aria-live："
    echo "$out"
    rc=1
  fi

  # 断言 3：多项缺失（aria-live 与 aria-busy 同时缺）时逐条打印且非 0
  cat > "$fdir/chat-list.vue" <<'FIXTURE'
<template>
  <div>
    <ChatMessage v-for="m in list" :key="m.id" :msg="m" />
  </div>
</template>
FIXTURE
  out="$(run_checks "$tmp")"
  if [ $? -eq 0 ]; then
    echo "self-test 3/3 FAIL：缺 aria-live 与 aria-busy 应失败，实际通过"
    rc=1
  elif printf '%s' "$out" | grep -q 'aria-live' && printf '%s' "$out" | grep -q 'aria-busy'; then
    echo "self-test 3/3 PASS：多项缺失时逐条打印且退出非 0"
  else
    echo "self-test 3/3 FAIL：多项缺失但输出不完整："
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
    run_checks "$DEFAULT_CHAT_DIR"
    exit $?
    ;;
  --self-test)
    run_self_test
    exit $?
    ;;
  *)
    echo "用法: bash scripts/frontend_a11y_gate.sh --check | --self-test" >&2
    exit 2
    ;;
esac
