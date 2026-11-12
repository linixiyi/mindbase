#!/usr/bin/env bash
# 真机评测冒烟门禁（task-0004）。
#
# 两种模式：
#   --dry-run（默认）：只读静态检查——curl/python3 存在、EVAL_BASE_URL 缺省值、数据集可读、
#                     报告目录可创建、/actuator/health 软检查（不可达只 warn）。不要求 token。
#                     静态项缺失退出 2 并指明缺哪个。
#   --run：            真跑。按 EVAL_ADMIN_TOKEN → EVAL_ADMIN_USERNAME/PASSWORD → 仓库根 .env 的
#                     ADMIN_BOOTSTRAP_USERNAME/PASSWORD 三级自动取管理员 token（POST /api/v1/users/login，
#                     读 data.token，token 只进内存），提交 POST /api/v1/admin/eval/run，
#                     轮询 GET /api/v1/admin/eval/runs/{id} 到终态，取 /report 断言
#                     retrievalErrorRate<=EVAL_MAX_ERROR_RATE(默认0.1) 且
#                     fullCoverageHitRate>=EVAL_MIN_COVERAGE(默认0.5)，不满足退出 1。
#                     缺凭据退出 2 并指明缺哪个变量。401 时重新登录一次再试。
#   --require-corpus:  语料就绪门禁（task-0003）：等价于 dry-run + 就绪检查——静态检查通过后调用
#                     python3 scripts/eval_corpus_readiness.py --check（task-0002，把 manifest 的
#                     每个 md5 与 ES 分块数对上），未就绪退出 2 并列出缺哪几篇；readiness 脚本
#                     不存在时报「缺就绪检查」退出 2，不静默跳过。
#                     --run --require-corpus：提交评测前强制同一检查，检查不过退出 2、
#                     绝不发出 POST /api/v1/admin/eval/run；EVAL_SKIP_CORPUS_CHECK=1 可显式跳过
#                     （仅限调试，只对 --run 生效）。
#
# 与后端代码事实对齐（RagEvalService.RunState）：
#   - 后端终态枚举是 RUNNING/SUCCEEDED/FAILED；脚本把 SUCCEEDED/COMPLETED 都当成功终态、
#     FAILED/CANCELLED 都当失败终态，两种写法都接受。
#   - datasetPath 只有 "classpath:eval/<名>" 走 classpath（RagEvalService.CLASSPATH_PREFIX），
#     裸文件名会解析到 eval-datasets/ 下；默认提交 "classpath:eval/eval-v2.jsonl"。
#
# 真机前置与失败排查见 docs/eval/real-smoke.md 与 docs/eval/runbook.md（服务/凭据/端点）。
# token/密码只进内存：不写文件、不进日志、不回显。
set -euo pipefail
cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

usage() {
  cat <<'EOF'
用法: bash scripts/eval_real_smoke.sh [--dry-run|--run] [--require-corpus]

  --dry-run（默认）  只读静态检查：curl/python3 存在、EVAL_BASE_URL 缺省 http://localhost:8081、
                    数据集可读、报告目录可创建、/actuator/health 软检查（不可达只 warn）。
                    不要求 token；静态项缺失退出 2 并指明缺哪个。
  --run              真跑：三级自动取管理员 token（EVAL_ADMIN_TOKEN → EVAL_ADMIN_USERNAME/PASSWORD
                    → 仓库根 .env 的 ADMIN_BOOTSTRAP_USERNAME/PASSWORD）→ 提交 POST /api/v1/admin/eval/run
                    → 轮询 GET /api/v1/admin/eval/runs/{id} 到终态 → 取 /report 断言
                    retrievalErrorRate<=0.1（EVAL_MAX_ERROR_RATE 可覆盖）与
                    fullCoverageHitRate>=0.5（EVAL_MIN_COVERAGE 可覆盖）。
                    缺凭据退出 2 并指明缺哪个变量；401 自动重新登录一次；断言不满足退出 1。
  --require-corpus   语料就绪门禁（等价于 dry-run + 就绪检查）：静态检查通过后调用
                    python3 scripts/eval_corpus_readiness.py --check（manifest 的每个 md5 ↔
                    ES 分块数）；未就绪退出 2 并列出缺哪几篇（补救：语料入库
                    python3 scripts/eval_corpus_ingest.py --run，见 docs/eval/corpus-ingest.md）；
                    readiness 脚本不存在时报「缺就绪检查」退出 2，不静默跳过。
  --run --require-corpus
                    真跑 + 提交前强制同一语料就绪检查：检查不过退出 2、不提交评测。
                    EVAL_SKIP_CORPUS_CHECK=1 显式跳过（仅限调试，只对 --run 生效；
                    --require-corpus 单独使用时检查照常执行）。

环境变量与失败排查见 docs/eval/real-smoke.md；语料入库见 docs/eval/corpus-ingest.md。
EOF
}

DRY_RUN=1
REQUIRE_CORPUS=0
MODE_SET=""
if [ "$#" -eq 0 ]; then
  set -- --dry-run
fi
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run|-d)
      if [ -n "$MODE_SET" ] && [ "$MODE_SET" != "dry" ]; then
        echo "!! --dry-run 与 --run 互斥，只能二选一" >&2
        exit 2
      fi
      DRY_RUN=1
      MODE_SET=dry
      ;;
    --run|-r)
      if [ -n "$MODE_SET" ] && [ "$MODE_SET" != "run" ]; then
        echo "!! --dry-run 与 --run 互斥，只能二选一" >&2
        exit 2
      fi
      DRY_RUN=0
      MODE_SET=run
      ;;
    --require-corpus) REQUIRE_CORPUS=1 ;;
    --help|-h)        usage; exit 0 ;;
    *) echo "!! 未知参数: $1（支持 --dry-run / --run / --require-corpus / --help）" >&2; exit 2 ;;
  esac
  shift
done

# ---- 配置（环境变量可覆盖） ----------------------------------------------
BASE_URL="${EVAL_BASE_URL:-http://localhost:8081}"   # 缺省 localhost:8081
ENV_FILE="${EVAL_ENV_FILE:-$REPO_ROOT/.env}"         # 第三级凭据来源（仓库根 .env）
DATASET_PATH="${EVAL_DATASET_PATH:-classpath:eval/eval-v2.jsonl}"
TOP_K="${EVAL_TOP_K:-5}"
MAX_ERROR_RATE="${EVAL_MAX_ERROR_RATE:-0.1}"
MIN_COVERAGE="${EVAL_MIN_COVERAGE:-0.5}"
POLL_INTERVAL="${EVAL_POLL_INTERVAL_SECONDS:-15}"
POLL_TIMEOUT="${EVAL_POLL_TIMEOUT_SECONDS:-1800}"
OUTPUT_DIR="${EVAL_OUTPUT_DIR:-eval-reports}"        # 与 application.yml eval.output-dir 缺省一致
LABEL="real-smoke-$(date +%s)"
# 语料就绪检查（task-0003）：readiness 脚本固定在本工作树 scripts/ 下（task-0002）；
# EVAL_CORPUS_MANIFEST 可把 readiness 临时指向别的 manifest（透传 --manifest，调试/fixture 用）。
READINESS_SCRIPT="$REPO_ROOT/scripts/eval_corpus_readiness.py"
CORPUS_MANIFEST="${EVAL_CORPUS_MANIFEST:-}"

ERRLOG="$(mktemp /tmp/eval-smoke-curl.XXXXXXXX)"
# 门禁脚本必须保住原退出码：bash 3.2 的 EXIT trap 会把退出码重置为 trap 内最后一条命令的状态，
# 且 trap 内 set -e 依然生效（rm 失败会以 127 直接中断，exit $RC 跑不到）——所以 rm 必须 || true 兜底。
trap 'RC=$?; rm -f "$ERRLOG" 2>/dev/null || true; exit $RC' EXIT

TOKEN=""
TOKEN_SRC=""
SMOKE_USER=""
SMOKE_PASS=""
API_CODE=""
API_BODY=""

# ---- 工具函数 ------------------------------------------------------------

fail_missing() {  # 静态项缺失：指明缺哪个，退出 2
  echo "!! 缺少静态项: $1" >&2
  echo "   （排查见 docs/eval/real-smoke.md）" >&2
  exit 2
}

# 镜像 RagEvalService.loadDataset 的解析规则：classpath:eval/ 前缀走 src/main/resources/eval/，
# 其余一律按 eval-datasets/ 下的相对路径。
resolve_dataset_file() {
  case "$1" in
    classpath:eval/*) printf '%s' "src/main/resources/eval/${1#classpath:eval/}" ;;
    *)                printf '%s' "eval-datasets/$1" ;;
  esac
}

# 从 .env 读单个 KEY=VALUE（取最后一次出现；容忍行首空白、\r、成对引号）。
# 只返回变量值给调用方，绝不回显。
env_file_var() {
  local key="$1" line val
  [ -f "$ENV_FILE" ] || return 1
  line="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$ENV_FILE" | tail -1 || true)"
  [ -n "$line" ] || return 1
  val="${line#*=}"
  val="${val%$'\r'}"
  val="${val#"${val%%[![:space:]]*}"}"
  val="${val%"${val##*[![:space:]]}"}"
  if [ "${#val}" -ge 2 ]; then
    case "$val" in
      \"*\") val="${val#\"}"; val="${val%\"}" ;;
      \'*\') val="${val#\'}"; val="${val%\'}" ;;
    esac
  fi
  printf '%s' "$val"
}

health_check() {  # 软检查：不可达只 warn，不算失败
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 3 --max-time 5 \
          "$BASE_URL/actuator/health" 2>/dev/null || true)"
  if [ -z "$code" ] || [ "$code" = "000" ]; then
    echo "WARN: $BASE_URL/actuator/health 不可达（后端未启动？）——继续"
  elif [ "$code" != "200" ]; then
    echo "WARN: /actuator/health 返回 HTTP ${code}（非 200）——继续"
  else
    echo "OK: /actuator/health 200"
  fi
}

# 语料就绪检查（task-0003）：调 eval_corpus_readiness.py --check（manifest ↔ ES 分块数）。
# readiness 的退出码：0 就绪；1 未就绪（输出自带缺哪几篇的表格与清单）；2 无法判定
# （ES 连接/认证失败、索引不存在、manifest 问题）。脚本的输出原样透传，不在本脚本里转述数字。
#   - 未就绪/无法判定 → 一律退出 2（fail-closed），不静默放行；
#   - readiness 脚本本身缺失 → 报「缺就绪检查」退出 2，绝不静默跳过。
corpus_readiness_check() {
  if [ ! -f "$READINESS_SCRIPT" ]; then
    echo "!! 缺就绪检查：找不到 $READINESS_SCRIPT" >&2
    echo "   没有「manifest ↔ ES 分块数」门禁就无法判定语料是否入库；按未就绪处理，退出 2（不静默跳过）" >&2
    echo "   修复：确认当前工作树 scripts/ 下有 eval_corpus_readiness.py（task-0002）后重跑" >&2
    exit 2
  fi
  local rc=0
  if [ -n "$CORPUS_MANIFEST" ]; then
    echo "==> 语料就绪检查（python3 scripts/eval_corpus_readiness.py --check --manifest ${CORPUS_MANIFEST}）..."
    python3 -B "$READINESS_SCRIPT" --check --manifest "$CORPUS_MANIFEST" || rc=$?
  else
    echo "==> 语料就绪检查（python3 scripts/eval_corpus_readiness.py --check）..."
    python3 -B "$READINESS_SCRIPT" --check || rc=$?
  fi
  case "$rc" in
    0)
      echo "OK: 语料就绪（manifest 全部文件 ES 分块数达标）"
      ;;
    1)
      echo "!! 语料未就绪（缺哪几篇见上方 readiness 表格与清单）——不提交评测，退出 2" >&2
      echo "   补救：python3 scripts/eval_corpus_ingest.py --run（dry-run/断点续传见 docs/eval/corpus-ingest.md）" >&2
      exit 2
      ;;
    *)
      echo "!! 语料就绪无法判定（eval_corpus_readiness.py --check 退出 ${rc}；常见：ES 连接/认证失败、manifest 问题）——按未就绪处理，退出 2" >&2
      echo "   排查见 docs/eval/runbook.md 与 docs/eval/real-smoke.md" >&2
      exit 2
      ;;
  esac
}

static_checks() {
  # 1. curl 存在
  command -v curl >/dev/null 2>&1 || fail_missing "curl 命令不存在"
  # 2. python3 存在（真跑模式的 JSON 解析与断言依赖）
  command -v python3 >/dev/null 2>&1 || fail_missing "python3 命令不存在（JSON 解析依赖）"
  # 3. 数据集可读（本地文件镜像后端 loadDataset 的解析）
  local ds_file
  ds_file="$(resolve_dataset_file "$DATASET_PATH")"
  if [ ! -r "$ds_file" ]; then
    echo "!! 缺少静态项: 数据集 $ds_file 不可读（datasetPath=${DATASET_PATH}）" >&2
    case "$DATASET_PATH" in
      classpath:*) ;;
      *) echo "   提示：裸文件名按 eval-datasets/ 解析；classpath 数据集应写 classpath:eval/eval-v2.jsonl" >&2 ;;
    esac
    echo "   （排查见 docs/eval/real-smoke.md）" >&2
    exit 2
  fi
  [ -s "$ds_file" ] || fail_missing "数据集 $ds_file 是空文件"
  # 4. 报告目录可创建/可写（后端 writeReport 会 Files.createDirectories）
  if [ -d "$OUTPUT_DIR" ]; then
    [ -w "$OUTPUT_DIR" ] || fail_missing "报告目录 $OUTPUT_DIR 不可写"
    [ -r "$OUTPUT_DIR" ] || fail_missing "报告目录 $OUTPUT_DIR 不可读"
  else
    local anc="$OUTPUT_DIR"
    while [ ! -d "$anc" ]; do anc="$(dirname "$anc")"; done
    [ -w "$anc" ] || fail_missing "报告目录 $OUTPUT_DIR 无法创建（最近存在的祖先 $anc 不可写）"
    echo "NOTE: 报告目录 $OUTPUT_DIR 不存在（后端首次写报告时自动创建；祖先 $anc 可写）"
  fi
}

json_get() {  # 从 stdin 的 JSON 里按 python 表达式取值；失败输出空串
  python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    v = ($1)
    if v is None:
        print('')
    else:
        print(v)
except Exception:
    pass
" 2>/dev/null || true
}

login() {  # login <user> <pass>：成功设全局 TOKEN；失败退出 1；token 不回显
  local u="$1" p="$2" resp body code token msg payload
  payload="$(python3 -c 'import json,sys; print(json.dumps({"username": sys.argv[1], "password": sys.argv[2]}))' "$u" "$p")"
  if ! resp="$(curl -sS -w '\n%{http_code}' --connect-timeout 5 --max-time 30 \
               -X POST "$BASE_URL/api/v1/users/login" \
               -H 'Content-Type: application/json' -d "$payload" 2>"$ERRLOG")"; then
    echo "!! 登录请求失败（后端不可达？${BASE_URL}）：$(tail -1 "$ERRLOG")" >&2
    exit 1
  fi
  code="${resp##*$'\n'}"
  body="${resp%$'\n'*}"
  if [ "$code" != "200" ]; then
    msg="$(printf '%s' "$body" | json_get 'd.get("message", "")')"
    echo "!! 登录失败 HTTP ${code}：${msg:-（响应无 message 字段）}" >&2
    case "$code" in
      401) echo "   用户名或密码不对；确认账号存在且 role=ADMIN（见 docs/eval/real-smoke.md）" >&2 ;;
      429) echo "   登录接口按 IP 限流，等窗口过去再试，不要连续重试（见 docs/eval/real-smoke.md）" >&2 ;;
    esac
    exit 1
  fi
  token="$(printf '%s' "$body" | json_get 'd["data"]["token"]')"
  if [ -z "$token" ]; then
    echo "!! 登录响应 200 但未取到 data.token，响应结构异常" >&2
    exit 1
  fi
  TOKEN="$token"
}

relogin_possible() { [ -n "$SMOKE_USER" ] && [ -n "$SMOKE_PASS" ]; }

force_relogin() {  # 401 时重新登录一次；成功刷新 TOKEN
  if ! relogin_possible; then
    echo "!! 401 且没有可用的用户名/密码（token 直接来自 EVAL_ADMIN_TOKEN），无法自动重新登录" >&2
    return 1
  fi
  echo "401：重新登录一次后重试..."
  login "$SMOKE_USER" "$SMOKE_PASS"
}

# api_call <GET|POST> <path> [json-body]：结果放 API_CODE/API_BODY；
# 401 时自动重新登录一次并重试；仍 401 则把 401 交给调用方处理。
api_call() {
  local method="$1" path="$2" data="${3:-}" resp body code attempt
  for attempt in 1 2; do
    if [ -n "$data" ]; then
      if ! resp="$(curl -sS -w '\n%{http_code}' --connect-timeout 5 --max-time 60 \
                   -X "$method" -H "Authorization: Bearer $TOKEN" \
                   -H 'Content-Type: application/json' \
                   -d "$data" "$BASE_URL$path" 2>"$ERRLOG")"; then
        echo "!! 请求 $method $path 失败（网络/连接错误）：$(tail -1 "$ERRLOG")" >&2
        return 1
      fi
    else
      if ! resp="$(curl -sS -w '\n%{http_code}' --connect-timeout 5 --max-time 60 \
                   -X "$method" -H "Authorization: Bearer $TOKEN" \
                   -H 'Content-Type: application/json' \
                   "$BASE_URL$path" 2>"$ERRLOG")"; then
        echo "!! 请求 $method $path 失败（网络/连接错误）：$(tail -1 "$ERRLOG")" >&2
        return 1
      fi
    fi
    code="${resp##*$'\n'}"
    body="${resp%$'\n'*}"
    if [ "$code" = "401" ] && [ "$attempt" = "1" ] && relogin_possible; then
      force_relogin || return 1
      continue
    fi
    API_CODE="$code"
    API_BODY="$body"
    return 0
  done
}

assert_gate() {  # assert_gate <report-json-file>：两条阈值断言，不满足退出 1
  python3 - "$1" "$MAX_ERROR_RATE" "$MIN_COVERAGE" <<'PY'
import json, sys

path, max_err, min_cov = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
d = json.load(open(path))
rep = d
if isinstance(d, dict) and "summary" not in d:
    inner = d.get("data")
    if isinstance(inner, dict) and "summary" in inner:
        rep = inner
s = rep.get("summary") or {}
err = s.get("retrievalErrorRate")
cov = s.get("fullCoverageHitRate")
print(f"retrievalErrorRate = {err}（上限 {max_err}）")
print(f"fullCoverageHitRate = {cov}（下限 {min_cov}）")

ok = True
if err is None:
    print("!! retrievalErrorRate 为 null，无法判定，冒烟不通过", file=sys.stderr)
    ok = False
elif err > max_err:
    print(f"!! retrievalErrorRate {err} > {max_err}，冒烟不通过", file=sys.stderr)
    ok = False
if cov is None:
    print("!! fullCoverageHitRate 为 null（没有可计算的覆盖样本？），无法判定，冒烟不通过", file=sys.stderr)
    ok = False
elif cov < min_cov:
    print(f"!! fullCoverageHitRate {cov} < {min_cov}，冒烟不通过", file=sys.stderr)
    ok = False

if not ok:
    sys.exit(1)
print("阈值断言通过")
PY
}

# ---- dry-run（默认） ------------------------------------------------------

if [ "$DRY_RUN" = "1" ]; then
  if [ "$REQUIRE_CORPUS" = "1" ]; then
    echo "==> 真机冒烟 dry-run + 语料就绪检查（--require-corpus）：只读检查（不要求 token，不发起真跑）"
  else
    echo "==> 真机冒烟 dry-run：只读静态检查（不要求 token，不发起真跑）"
  fi
  if [ -z "${EVAL_BASE_URL:-}" ]; then
    echo "NOTE: EVAL_BASE_URL 未设置，缺省 $BASE_URL"
  fi
  static_checks
  echo "OK: curl 存在"
  echo "OK: python3 存在"
  echo "OK: 数据集 $(resolve_dataset_file "$DATASET_PATH") 可读（datasetPath=${DATASET_PATH}）"
  health_check
  if [ "$REQUIRE_CORPUS" = "1" ]; then
    if [ -n "${EVAL_SKIP_CORPUS_CHECK:-}" ]; then
      echo "NOTE: EVAL_SKIP_CORPUS_CHECK 只对 --run --require-corpus 生效；--require-corpus 单独使用时语料检查照常执行"
    fi
    corpus_readiness_check
    echo "dry-run + 语料就绪 PASS（静态检查与语料就绪全部满足；真跑用 --run --require-corpus，见 docs/eval/real-smoke.md）"
  else
    echo "dry-run PASS（静态检查全部满足；真跑需服务与凭据，见 docs/eval/real-smoke.md）"
  fi
  exit 0
fi

# ---- 真跑 -----------------------------------------------------------------

if [ "$REQUIRE_CORPUS" = "1" ]; then
  echo "==> 真机冒烟真跑：${BASE_URL}（datasetPath=$DATASET_PATH topK=$TOP_K label=${LABEL}，--require-corpus：提交前强制语料就绪检查）"
else
  echo "==> 真机冒烟真跑：${BASE_URL}（datasetPath=$DATASET_PATH topK=$TOP_K label=${LABEL}）"
fi
static_checks
echo "OK: 静态检查通过"
health_check

# 语料就绪前置（task-0003）：--run --require-corpus 时在取凭据/提交之前强制检查；
# EVAL_SKIP_CORPUS_CHECK=1 显式跳过（仅限调试）。检查不过退出 2，绝不发出 /admin/eval/run。
if [ "$REQUIRE_CORPUS" = "1" ]; then
  if [ "${EVAL_SKIP_CORPUS_CHECK:-}" = "1" ]; then
    echo "WARN: EVAL_SKIP_CORPUS_CHECK=1，显式跳过语料就绪检查（仅限调试；语料未入库会导致整轮空召回、白烧模型 token）"
  else
    corpus_readiness_check
  fi
fi

# 三级取 token：EVAL_ADMIN_TOKEN → EVAL_ADMIN_USERNAME/PASSWORD → .env 的 ADMIN_BOOTSTRAP_*
MISSING_NOTE=""
if [ -n "${EVAL_ADMIN_TOKEN:-}" ]; then
  TOKEN="$EVAL_ADMIN_TOKEN"
  TOKEN_SRC="EVAL_ADMIN_TOKEN"
  echo "token 来源：EVAL_ADMIN_TOKEN"
else
  if [ -n "${EVAL_ADMIN_USERNAME:-}" ] && [ -n "${EVAL_ADMIN_PASSWORD:-}" ]; then
    SMOKE_USER="$EVAL_ADMIN_USERNAME"
    SMOKE_PASS="$EVAL_ADMIN_PASSWORD"
    TOKEN_SRC="EVAL_ADMIN_USERNAME/EVAL_ADMIN_PASSWORD"
    echo "token 来源：EVAL_ADMIN_USERNAME/EVAL_ADMIN_PASSWORD"
  elif [ -n "${EVAL_ADMIN_USERNAME:-}" ] || [ -n "${EVAL_ADMIN_PASSWORD:-}" ]; then
    if [ -z "${EVAL_ADMIN_USERNAME:-}" ]; then
      MISSING_NOTE+="EVAL_ADMIN_USERNAME（EVAL_ADMIN_PASSWORD 已设但用户名未设）"$'\n'
    else
      MISSING_NOTE+="EVAL_ADMIN_PASSWORD（EVAL_ADMIN_USERNAME 已设但密码未设）"$'\n'
    fi
  fi
  if [ -z "$TOKEN" ] && [ -z "$SMOKE_USER" ]; then
    if [ ! -f "$ENV_FILE" ]; then
      MISSING_NOTE+="$ENV_FILE 不存在（无法读 ADMIN_BOOTSTRAP_USERNAME/ADMIN_BOOTSTRAP_PASSWORD）"$'\n'
    else
      U="$(env_file_var ADMIN_BOOTSTRAP_USERNAME || true)"
      P="$(env_file_var ADMIN_BOOTSTRAP_PASSWORD || true)"
      if [ -n "$U" ] && [ -n "$P" ]; then
        SMOKE_USER="$U"
        SMOKE_PASS="$P"
        TOKEN_SRC="$ENV_FILE 的 ADMIN_BOOTSTRAP_USERNAME/ADMIN_BOOTSTRAP_PASSWORD"
        echo "token 来源：$ENV_FILE 的 ADMIN_BOOTSTRAP_USERNAME/ADMIN_BOOTSTRAP_PASSWORD"
      else
        if [ -z "$U" ]; then MISSING_NOTE+="ADMIN_BOOTSTRAP_USERNAME（$ENV_FILE 中未定义）"$'\n'; fi
        if [ -z "$P" ]; then MISSING_NOTE+="ADMIN_BOOTSTRAP_PASSWORD（$ENV_FILE 中未定义）"$'\n'; fi
      fi
    fi
  fi
fi

if [ -z "$TOKEN" ] && [ -z "$SMOKE_USER" ]; then
  echo "!! 缺少管理员凭据，无法真跑（缺的就是下面这些变量）：" >&2
  printf '%s' "$MISSING_NOTE" | sed 's/^/   - /' >&2
  echo "   三级顺序：EVAL_ADMIN_TOKEN → EVAL_ADMIN_USERNAME+EVAL_ADMIN_PASSWORD → $ENV_FILE 的 ADMIN_BOOTSTRAP_USERNAME+ADMIN_BOOTSTRAP_PASSWORD" >&2
  echo "   排查见 docs/eval/real-smoke.md 与 docs/eval/runbook.md 第 3 节" >&2
  exit 2
fi

if [ -z "$TOKEN" ]; then
  echo "==> 登录（POST /api/v1/users/login）..."
  login "$SMOKE_USER" "$SMOKE_PASS"
fi

# 提交
SUBMIT_BODY="$(python3 -c 'import json,sys; print(json.dumps({"datasetPath": sys.argv[1], "topK": int(sys.argv[2]), "label": sys.argv[3]}))' "$DATASET_PATH" "$TOP_K" "$LABEL")"
echo "==> 提交评测（POST /api/v1/admin/eval/run）..."
if ! api_call POST /api/v1/admin/eval/run "$SUBMIT_BODY"; then
  exit 1
fi
if [ "$API_CODE" = "401" ]; then
  echo "!! 提交评测 401：token 过期或不是管理员（role!=ADMIN）；重新登录后仍失败" >&2
  echo "   排查见 docs/eval/real-smoke.md 与 docs/eval/runbook.md 第 6 节" >&2
  exit 1
fi
if [ "$API_CODE" = "403" ]; then
  echo "!! 提交评测 403：当前账号不是 ADMIN 角色（/api/v1/admin/** 要求 ROLE_ADMIN）" >&2
  echo "   排查见 docs/eval/real-smoke.md" >&2
  exit 1
fi
if [ "$API_CODE" != "200" ]; then
  msg="$(printf '%s' "$API_BODY" | json_get 'd.get("message", "")')"
  echo "!! 提交评测失败 HTTP ${API_CODE}：${msg:-（响应无 message 字段）}" >&2
  exit 1
fi
RUN_ID="$(printf '%s' "$API_BODY" | json_get 'd["data"]["runId"]')"
if [ -z "$RUN_ID" ]; then
  echo "!! 提交成功但未取到 data.runId，响应结构异常" >&2
  exit 1
fi
echo "runId: $RUN_ID"

# 轮询到终态（后端 RunState=RUNNING/SUCCEEDED/FAILED；SUCCEEDED/COMPLETED 视为成功终态）
DEADLINE=$(( $(date +%s) + POLL_TIMEOUT ))
while :; do
  if ! api_call GET "/api/v1/admin/eval/runs/$RUN_ID"; then
    exit 1
  fi
  if [ "$API_CODE" != "200" ]; then
    msg="$(printf '%s' "$API_BODY" | json_get 'd.get("message", "")')"
    echo "!! 轮询状态失败 HTTP ${API_CODE}：${msg:-（响应无 message 字段）}" >&2
    exit 1
  fi
  read -r STATE DONE_TOTAL <<<"$(printf '%s' "$API_BODY" | json_get 'str(d["data"]["state"]) + " " + str((d["data"] or {}).get("completedCases")) + "/" + str((d["data"] or {}).get("totalCases"))')"
  if [ -z "$STATE" ]; then
    echo "!! 轮询响应无法解析 data.state（响应结构异常或任务不存在）" >&2
    exit 1
  fi
  case "$STATE" in
    SUCCEEDED|COMPLETED)
      echo "终态: $STATE"
      break
      ;;
    FAILED|CANCELLED)
      msg="$(printf '%s' "$API_BODY" | json_get 'd["data"].get("message") or ""')"
      echo "!! 评测终态 ${STATE}：${msg:-（无 message）}" >&2
      echo "   排查见 docs/eval/real-smoke.md（ES/MinIO/模型 key 等常见失败）" >&2
      exit 1
      ;;
  esac
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "!! 轮询超时（${POLL_TIMEOUT}s）仍未到终态，最后状态: $STATE $DONE_TOTAL" >&2
    echo "   可手动查看 GET /api/v1/admin/eval/runs/${RUN_ID}；排查见 docs/eval/real-smoke.md" >&2
    exit 1
  fi
  echo "运行中: $STATE ${DONE_TOTAL}（每 ${POLL_INTERVAL}s 轮询一次，timeout=${POLL_TIMEOUT}s）"
  sleep "$POLL_INTERVAL"
done

# 报告 + 断言
echo "==> 取报告（GET /api/v1/admin/eval/runs/$RUN_ID/report）..."
if ! api_call GET "/api/v1/admin/eval/runs/$RUN_ID/report"; then
  exit 1
fi
if [ "$API_CODE" = "401" ]; then
  echo "!! 取报告 401：重新登录后仍失败；排查见 docs/eval/real-smoke.md" >&2
  exit 1
fi
if [ "$API_CODE" != "200" ]; then
  msg="$(printf '%s' "$API_BODY" | json_get 'd.get("message", "")')"
  echo "!! 取报告失败 HTTP ${API_CODE}：${msg:-（响应无 message 字段）}" >&2
  exit 1
fi
REPORT_FILE="$(mktemp "/tmp/eval-smoke-report-${RUN_ID}.XXXXXXXX")"
printf '%s' "$API_BODY" > "$REPORT_FILE"
echo "报告已保存: $REPORT_FILE"
echo "==> 阈值断言（retrievalErrorRate<=${MAX_ERROR_RATE} 且 fullCoverageHitRate>=${MIN_COVERAGE}）..."
if ! assert_gate "$REPORT_FILE"; then
  echo "真机冒烟 FAIL（阈值不满足，退出 1）" >&2
  exit 1
fi
echo "真机冒烟 PASS（runId=${RUN_ID}，报告=${REPORT_FILE}）"
