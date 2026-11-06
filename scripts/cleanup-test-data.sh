#!/usr/bin/env bash
# ============================================================================
# cleanup-test-data.sh —— MindBase 测试数据清理包装脚本
# ============================================================================
# 用途：安全执行同目录下的 cleanup-test-data.sql（测试账号/邀请码/会话消息/
#       PRIVATE_ 组织标签等历轮验证留痕，详见 SQL 文件头注释）。
#
# 语义：
#   ./cleanup-test-data.sh          # 默认 dry-run：只统计并打印将删除的行数，不写库
#   ./cleanup-test-data.sh --yes    # 真正执行（--yes 为显式确认，无其他交互）
#
# 幂等：可重复执行；残留清完后再跑，dry-run 显示 0 行。
# 连接：优先取环境变量，其次解析仓库根 .env（与后端 DotenvEnvironmentPostProcessor
#       同名同义）：SPRING_DATASOURCE_URL / SPRING_DATASOURCE_USERNAME /
#       SPRING_DATASOURCE_PASSWORD。缺省 localhost:3306/PaiSmart。
#       密码经 MYSQL_PWD 传递，不会出现在进程参数或本脚本输出里。
# ============================================================================

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="${SCRIPT_DIR}/cleanup-test-data.sql"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() {
    sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
}

EXECUTE=0
for arg in "$@"; do
    case "${arg}" in
        --yes) EXECUTE=1 ;;
        -h|--help) usage ;;
        *)
            echo "未知参数: ${arg}"
            echo "用法: $0 [--yes]   （不带 --yes 一律 dry-run）"
            exit 2
            ;;
    esac
done

[ -f "${SQL_FILE}" ] || { echo "找不到 ${SQL_FILE}"; exit 1; }

# ---- mysql 客户端 ----
MYSQL_BIN="$(command -v mysql || true)"
if [ -z "${MYSQL_BIN}" ]; then
    for cand in /opt/homebrew/bin/mysql /opt/homebrew/opt/mysql-client/bin/mysql /usr/local/mysql/bin/mysql /usr/local/bin/mysql; do
        [ -x "${cand}" ] && MYSQL_BIN="${cand}" && break
    done
fi
[ -n "${MYSQL_BIN}" ] || { echo "未找到 mysql 客户端，请先安装（如 brew install mysql-client）；dry-run 同样需要它来统计行数。"; exit 1; }

# ---- 连接参数：环境变量 > 仓库根 .env > 默认值 ----
ENV_FILE="${REPO_ROOT}/.env"
env_value() { # 只取值不回显
    local key="$1" val=""
    val="${!key:-}"
    if [ -z "${val}" ] && [ -f "${ENV_FILE}" ]; then
        val="$(awk -F= -v k="${key}" '$1 == k { sub(/^[^=]*=/, ""); print; exit }' "${ENV_FILE}")"
        # 与后端 DotenvEnvironmentPostProcessor 一致：仅当两端成对引号时去引号
        case "${val}" in
            '\"'*'"') val="${val#\"}" ; val="${val%\"}" ;;
            \'*\'?) val="${val#\'}" ; val="${val%\'}" ;;
        esac
    fi
    printf '%s' "${val}"
}

DB_URL="$(env_value SPRING_DATASOURCE_URL)"
DB_USER="$(env_value SPRING_DATASOURCE_USERNAME)"
DB_PASS="$(env_value SPRING_DATASOURCE_PASSWORD)"

DB_HOST="localhost"; DB_PORT="3306"; DB_NAME="PaiSmart"
case "${DB_URL}" in
    jdbc:mysql://*)
        rest="${DB_URL#jdbc:mysql://}"
        authority="${rest%%/*}"
        path_and_query="${rest#*/}"
        DB_NAME="${path_and_query%%\?*}"
        DB_HOST="${authority%%:*}"
        case "${authority}" in
            *:*) DB_PORT="${authority#*:}" ;;
        esac
        ;;
esac
[ -n "${DB_USER}" ] || DB_USER="root"

export MYSQL_PWD="${DB_PASS}"

conn_ok=1
"${MYSQL_BIN}" --connect-timeout=5 -h "${DB_HOST}" -P "${DB_PORT}" -u "${DB_USER}" -N -e "SELECT 1" "${DB_NAME}" >/dev/null 2>&1 || conn_ok=0
if [ "${conn_ok}" -ne 1 ]; then
    echo "无法连接 MySQL：${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
    echo "请检查 .env 的 SPRING_DATASOURCE_* 或后端是否可达（连接参数只报告用户名与地址，不回显密码）。"
    exit 1
fi

# ---- 由 SQL 文件机械生成同条件的 COUNT 语句（单一事实来源，避免两份条件漂移） ----
COUNT_SQL="$(awk '
    /^DELETE FROM / {
        line = $0
        sub(/;[[:space:]]*$/, "", line)
        sub(/^DELETE FROM /, "", line)
        pos = index(line, " WHERE ")
        if (pos == 0) next
        tbl  = substr(line, 1, pos - 1)
        cond = substr(line, pos + 7)
        printf "SELECT \047%s\047 AS tbl, COUNT(*) AS rows_to_delete FROM %s WHERE %s;\n", tbl, tbl, cond
    }
' "${SQL_FILE}")"

[ -n "${COUNT_SQL}" ] || { echo "未从 ${SQL_FILE} 解析到任何 DELETE 语句"; exit 1; }

run_sql() {
    "${MYSQL_BIN}" -N -h "${DB_HOST}" -P "${DB_PORT}" -u "${DB_USER}" "${DB_NAME}"
}

if [ "${EXECUTE}" -ne 1 ]; then
    echo "== dry-run（未写库）：以下为将删除的行数统计 =="
    echo "目标库: ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
    printf '%s\n' "${COUNT_SQL}" | run_sql
    echo "--"
    printf '%s\n' "${COUNT_SQL}" | run_sql | awk -F'\t' '{ s += $2 } END { printf "合计将删除 %d 行；确认无误后加 --yes 执行。\n", s }'
    exit 0
fi

echo "== 执行清理（--yes 已确认）：${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME} =="
# 单事务包裹：任一语句失败，mysql 批处理模式即退出且不提交，连接关闭时回滚
{
    echo "START TRANSACTION;"
    cat "${SQL_FILE}"
    echo "COMMIT;"
} | run_sql
exec_rc=$?

if [ "${exec_rc}" -ne 0 ]; then
    echo "清理失败（事务已回滚），请检查上方 mysql 报错。"
    exit 1
fi

echo "== 执行完成，复检残留（应全为 0）=="
printf '%s\n' "${COUNT_SQL}" | run_sql
