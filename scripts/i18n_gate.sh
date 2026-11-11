#!/usr/bin/env bash
#
# scripts/i18n_gate.sh — task-0006 i18n 止血门禁（spec: 2026-11-11_frontend-quality / C6）
#
# 背景（spec 背景 5 / 决策 D4 / Fork F3）：产品页约 1,182 行中文硬编码、30 个视图文件，
# 短期无法全量迁移。本门禁不做翻译，只冻结基线：
#   ① frontend/i18n-baseline.txt 记录每个 .vue 的中文行数（格式 `path count`，path 相对 frontend/），
#      由 --update-baseline 生成；首次生成后不得手改数字，重新生成必须说明原因。
#   ② --check 逐文件比较：任一文件超出基线即失败并打印差值；行数下降允许通过，
#      并提示可运行 --update-baseline 收紧基线。
#
# 「中文行」定义：一行内含至少一个 Han 字符（perl \p{Han}），且该行在下列忽略规则后仍含中文：
#   - 忽略注释行：/* */ 与 <!-- --> 整体剥离（均可跨行）；整行以 // 开头的行丢弃
#   - 忽略 console. 调试行：任何含 console. 的行不计入
#
# 用法：
#   bash scripts/i18n_gate.sh --check            逐文件比较当前树与基线（verify 命令）
#   bash scripts/i18n_gate.sh --update-baseline  以当前树重新生成 frontend/i18n-baseline.txt
#   bash scripts/i18n_gate.sh --self-test        临时目录自检：超基线失败/减行通过/缺文件失败
#
# 退出码：0 = 通过；1 = 门禁失败（含基线缺失/格式错误）；2 = 用法错误
#
# 已知限制（对当前代码成立）：
#   - /* 与 <!-- 的剥离是启发式：字符串字面量里出现这些序列的行可能被误剥；
#     误剥只会让计数偏小、门禁偏松，不会误伤正常开发。
#   - 行尾的 // 尾注释里的中文仍计入（任务书只要求忽略「注释行」，即整行注释）。
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEFAULT_FRONTEND_DIR="$REPO_ROOT/frontend"
DEFAULT_BASELINE="$DEFAULT_FRONTEND_DIR/i18n-baseline.txt"

# 剥离 /* */ 与 <!-- -->（均可跨行）。LC_ALL=C 按字节处理：中文 UTF-8 字节不含 ASCII，
# 注释定界符不可能出现在中文字符内部，字节切片安全。
strip_comments() {
  LC_ALL=C awk '
    BEGIN { state = 0 }   # 0 = 正文，1 = 在 /* */ 内，2 = 在 <!-- --> 内
    {
      line = $0
      out = ""
      while (length(line) > 0) {
        if (state == 1) {
          j = index(line, "*/")
          if (j > 0) { line = substr(line, j + 2); state = 0 }
          else { line = "" }
        } else if (state == 2) {
          j = index(line, "-->")
          if (j > 0) { line = substr(line, j + 3); state = 0 }
          else { line = "" }
        } else {
          i1 = index(line, "/*")
          i2 = index(line, "<!--")
          if (i1 == 0 && i2 == 0) { out = out line; line = "" }
          else if (i1 > 0 && (i2 == 0 || i1 < i2)) {
            out = out substr(line, 1, i1 - 1)
            line = substr(line, i1 + 2)
            state = 1
          } else {
            out = out substr(line, 1, i2 - 1)
            line = substr(line, i2 + 4)
            state = 2
          }
        }
      }
      print out
    }
  ' "$1"
}

# 统计单个文件的中文行数：stdout = 非负整数
count_file() {
  strip_comments "$1" | perl -CSD -ne '
    BEGIN { $n = 0 }
    next if /console\./;            # 忽略 console. 调试行
    (my $t = $_) =~ s/^[ \t\r]+//;
    next if $t =~ m{^//};           # 忽略整行 // 注释
    $n++ if /\p{Han}/;              # 含 Han 字符的行计入
    END { print "$n\n" }
  '
}

# 列出 frontend 下全部 .vue（排除 node_modules/dist，按字节序排序保证基线确定性）
list_vue_files() {
  find "$1" \( -name node_modules -o -name dist \) -prune -o -type f -name '*.vue' -print | LC_ALL=C sort
}

run_check() {
  local baseline="$1" frontend_dir="$2"

  if [ ! -f "$baseline" ]; then
    echo "i18n_gate: 基线文件不存在：$baseline"
    echo "i18n_gate: 先运行 bash scripts/i18n_gate.sh --update-baseline 生成基线，再 --check"
    return 1
  fi
  if [ ! -d "$frontend_dir" ]; then
    echo "i18n_gate: 扫描目录不存在：$frontend_dir"
    return 1
  fi

  local violations=() reduce_hints=()
  local total_actual=0 total_baseline=0 file_count=0
  local bpath bcount rest f actual diff rel item

  # 基线路径清单（供反向「树中文件不在基线」查找）
  local paths_tmp
  paths_tmp="$(mktemp "${TMPDIR:-/tmp}/i18n_gate_paths.XXXXXX")"
  awk '{ if ($1 == "" || $1 ~ /^#/) next; print $1 }' "$baseline" > "$paths_tmp"

  # 正向：基线 → 磁盘。逐条比较；缺文件即失败（--self-test 的「缺文件失败」场景）
  while read -r bpath bcount rest; do
    case "$bpath" in ''|'#'*) continue ;; esac
    if [ -n "${rest:-}" ] || ! printf '%s' "$bcount" | grep -Eq '^[0-9]+$'; then
      violations+=("基线格式错误（应为 \"path count\" 两列）：$bpath $bcount ${rest:-}")
      continue
    fi
    file_count=$((file_count + 1))
    total_baseline=$((total_baseline + bcount))
    f="$frontend_dir/$bpath"
    if [ ! -f "$f" ]; then
      violations+=("$bpath 基线 ${bcount} 行，但文件已不存在 —— 如确认删除/改名，运行 --update-baseline 更新基线")
      continue
    fi
    actual="$(count_file "$f")"
    total_actual=$((total_actual + actual))
    if [ "$actual" -gt "$bcount" ]; then
      diff=$((actual - bcount))
      violations+=("$bpath 基线 ${bcount}，当前 ${actual}，超出 ${diff} 行 —— 中文硬编码只减不增（F3）；确需豁免请 --update-baseline 并说明原因")
    elif [ "$actual" -lt "$bcount" ]; then
      diff=$((bcount - actual))
      reduce_hints+=("$bpath 基线 ${bcount}，当前 ${actual}（-${diff}）")
    fi
  done < "$baseline"

  # 反向：磁盘 → 基线。不在基线中的新 .vue 若已含中文，同样算增长
  while IFS= read -r f; do
    rel="${f#$frontend_dir/}"
    if ! grep -Fxq -- "$rel" "$paths_tmp"; then
      file_count=$((file_count + 1))
      actual="$(count_file "$f")"
      total_actual=$((total_actual + actual))
      if [ "$actual" -gt 0 ]; then
        violations+=("$rel 不在基线中且含 ${actual} 行中文 —— 新文件请先 --update-baseline 登记基线（登记前新增中文需说明原因）")
      fi
    fi
  done < <(list_vue_files "$frontend_dir")

  rm -f "$paths_tmp"

  # 注意：macOS 自带 bash 3.2 在 $var 后紧跟全角标点时会把多字节首字节吞进变量名
  # （$baseline； 被解析成变量 baseline\xEF → set -u 报 unbound），故此处必须用 ${var} 花括号形式。
  echo "i18n_gate: 基线 ${baseline}；扫描 ${frontend_dir}（${file_count} 个 .vue，当前中文共 ${total_actual} 行 / 基线 ${total_baseline} 行）"

  if [ "${#violations[@]}" -gt 0 ]; then
    echo "i18n_gate: 发现 ${#violations[@]} 个违规项："
    for item in "${violations[@]}"; do
      echo "  - $item"
    done
    echo "i18n_gate: 处理方式：中文行数只减不增；若为有意豁免/文件删除，运行 bash scripts/i18n_gate.sh --update-baseline 并在 PR/提交说明里写明原因（决策 D4）。"
    return 1
  fi

  echo "i18n_gate: 通过（$file_count 个 .vue 全部未超基线）"

  if [ "${#reduce_hints[@]}" -gt 0 ]; then
    echo "i18n_gate: 提示：以下文件中文行数已低于基线，可运行 --update-baseline 收紧基线："
    for item in "${reduce_hints[@]}"; do
      echo "  - $item"
    done
  fi
  return 0
}

run_update_baseline() {
  local baseline="$1" frontend_dir="$2"

  if [ ! -d "$frontend_dir" ]; then
    echo "i18n_gate: 扫描目录不存在：$frontend_dir" >&2
    return 2
  fi

  # 在基线同目录生成临时文件，写完原子 mv，避免半截基线
  local tmp_out
  tmp_out="$(mktemp "$frontend_dir/.i18n-baseline.XXXXXX")"
  trap "rm -f '$tmp_out'" EXIT

  local files=0 han_files=0 total=0
  local rel cnt

  {
    echo "# frontend/i18n-baseline.txt — 中文硬编码行数基线（spec: 2026-11-11_frontend-quality / task-0006 / C6）"
    echo "# 生成方式：bash scripts/i18n_gate.sh --update-baseline。首次生成后不得手改数字；"
    echo "# 重新生成必须伴随原因说明（注释清理/有意豁免/文件删除），写入 PR 或提交说明（决策 D4）。"
    echo "# 格式：<相对 frontend/ 的 .vue 路径> <中文行数>；忽略注释行与 console. 调试行；# 开头为本文件注释。"
    while IFS= read -r f; do
      rel="${f#$frontend_dir/}"
      cnt="$(count_file "$f")"
      files=$((files + 1))
      total=$((total + cnt))
      if [ "$cnt" -gt 0 ]; then han_files=$((han_files + 1)); fi
      printf '%s %s\n' "$rel" "$cnt"
    done < <(list_vue_files "$frontend_dir")
  } > "$tmp_out"

  mv "$tmp_out" "$baseline"
  trap - EXIT

  echo "i18n_gate: 已生成 $baseline"
  echo "i18n_gate: 共 $files 个 .vue，其中 $han_files 个含中文，合计 $total 行（忽略注释行与 console. 调试行）"
  return 0
}

run_self_test() {
  local tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/i18n_gate_selftest.XXXXXX")"
  # trap 里此刻就展开 ${tmp}（函数返回后 local 变量已出栈，届时展开会在 set -u 下报 unbound）
  trap "rm -rf '$tmp'" EXIT

  local fdir="$tmp/frontend/src/views"
  mkdir -p "$fdir"
  local baseline="$tmp/frontend/i18n-baseline.txt"

  write_a() { cat > "$fdir/a.vue" <<'FIXTURE'
<template>
  <div>你好世界</div>
  <!-- 模板注释里的中文：不应计数 -->
</template>

<script setup lang="ts">
// 行注释里的中文：不应计数
/* 块注释里的
   中文：不应计数 */
console.log('中文调试：不应计数')
import { ref } from 'vue'
const greeting = ref('欢迎回来')
</script>
FIXTURE
  }
  write_b() { cat > "$fdir/b.vue" <<'FIXTURE'
<template>
  <div>普通内容行</div>
</template>
FIXTURE
  }
  write_c() { cat > "$fdir/c.vue" <<'FIXTURE'
<template>
  <div>plain text only</div>
</template>
FIXTURE
  }
  write_d() { cat > "$fdir/d.vue" <<'FIXTURE'
<template>
  <div>新增文件第一行</div>
  <div>新增文件第二行</div>
</template>
FIXTURE
  }

  write_a
  write_b
  write_c

  local rc=0
  local out

  # 断言 1：--update-baseline 生成的数字必须体现忽略规则
  #   a.vue 原始含中文 5 行（正文 2 + 模板注释 1 + 行注释 1 + console 1 + 跨行块注释 1），正确结果应为 2
  run_update_baseline "$baseline" "$tmp/frontend" >/dev/null
  if grep -Fqx 'src/views/a.vue 2' "$baseline" \
    && grep -Fqx 'src/views/b.vue 1' "$baseline" \
    && grep -Fqx 'src/views/c.vue 0' "$baseline"; then
    echo "self-test 1/6 PASS：--update-baseline 生成的计数忽略注释行与 console. 调试行（a.vue=2, b.vue=1, c.vue=0）"
  else
    echo "self-test 1/6 FAIL：基线计数未体现忽略规则，实际生成："
    grep -v '^#' "$baseline" || true
    rc=1
  fi

  # 断言 2（对照组）：树与基线一致必须通过——证明后续失败确由对应原因引起
  if out="$(run_check "$baseline" "$tmp/frontend")"; then
    echo "self-test 2/6 PASS：树与基线一致时 --check 通过"
  else
    echo "self-test 2/6 FAIL：树与基线一致时应通过，实际失败："
    echo "$out"
    rc=1
  fi

  # 断言 3（任务书点名）：超基线必失败，且打印差值
  cat > "$fdir/a.vue" <<'FIXTURE'
<template>
  <div>你好世界</div>
  <div>新加的一行中文</div>
  <div>欢迎回来</div>
</template>
FIXTURE
  out="$(run_check "$baseline" "$tmp/frontend")"
  if [ $? -eq 0 ]; then
    echo "self-test 3/6 FAIL：a.vue 超基线 1 行应失败，实际通过"
    rc=1
  elif printf '%s' "$out" | grep -q 'a.vue' && printf '%s' "$out" | grep -q '超出 1'; then
    echo "self-test 3/6 PASS：超基线失败且打印差值（超出 1）"
  else
    echo "self-test 3/6 FAIL：超基线失败但输出未点名 a.vue/差值："
    echo "$out"
    rc=1
  fi
  write_a

  # 断言 4（任务书点名）：减行允许通过，并提示可 --update-baseline
  cat > "$fdir/b.vue" <<'FIXTURE'
<template>
  <div>plain now</div>
</template>
FIXTURE
  if out="$(run_check "$baseline" "$tmp/frontend")"; then
    if printf '%s' "$out" | grep -q 'b.vue' && printf '%s' "$out" | grep -q -- '--update-baseline'; then
      echo "self-test 4/6 PASS：减行通过且提示可 --update-baseline"
    else
      echo "self-test 4/6 FAIL：减行通过但缺少 --update-baseline 提示："
      echo "$out"
      rc=1
    fi
  else
    echo "self-test 4/6 FAIL：减行应允许通过，实际失败："
    echo "$out"
    rc=1
  fi
  write_b

  # 断言 5：不在基线中的新 .vue 含中文必须失败（防止绕基线加新文件）
  write_d
  out="$(run_check "$baseline" "$tmp/frontend")"
  if [ $? -eq 0 ]; then
    echo "self-test 5/6 FAIL：基线外新文件含中文应失败，实际通过"
    rc=1
  elif printf '%s' "$out" | grep -q 'd.vue' && printf '%s' "$out" | grep -q '不在基线中'; then
    echo "self-test 5/6 PASS：基线外新文件含中文失败且点名文件"
  else
    echo "self-test 5/6 FAIL：失败但输出未点名 d.vue："
    echo "$out"
    rc=1
  fi
  rm -f "$fdir/d.vue"

  # 断言 6（任务书点名）：基线中的文件从磁盘消失必失败
  rm -f "$fdir/c.vue"
  out="$(run_check "$baseline" "$tmp/frontend")"
  if [ $? -eq 0 ]; then
    echo "self-test 6/6 FAIL：基线文件缺失应失败，实际通过"
    rc=1
  elif printf '%s' "$out" | grep -q 'c.vue' && printf '%s' "$out" | grep -q '不存在'; then
    echo "self-test 6/6 PASS：缺文件失败且点名文件"
  else
    echo "self-test 6/6 FAIL：缺文件失败但输出未点名 c.vue："
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
    run_check "$DEFAULT_BASELINE" "$DEFAULT_FRONTEND_DIR"
    exit $?
    ;;
  --update-baseline)
    run_update_baseline "$DEFAULT_BASELINE" "$DEFAULT_FRONTEND_DIR"
    exit $?
    ;;
  --self-test)
    run_self_test
    exit $?
    ;;
  *)
    echo "用法: bash scripts/i18n_gate.sh --check | --update-baseline | --self-test" >&2
    exit 2
    ;;
esac
