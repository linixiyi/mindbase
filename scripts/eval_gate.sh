#!/usr/bin/env bash
# 本地评测门禁：一条命令跑 20 题 fixture 并对产出的真实 v2 报告做阈值复核。
#
# 链路：./mvnw 跑 EvalSmokeFixtureTest（内置 20 题合成语料，检索与模型全部是 Mockito 桩，
# 无网络、无 ES、无数据库）→ 生产 RagEvalService/RagEvalCaseRunner react 链路真实落一份 v2 报告
# 到 target/eval-smoke/ → eval_compare.py 读报告并按阈值判定，任一指标命中失败条件以非 0 退出。
#
# 阈值按 fixture 的确定性指标留有余量（20 题：recall 0.9474 / accuracy 0.9474 / mrr 0.9211 /
# 全命中 0.8421 / 检索异常率 0.05）：fixture 行为回归时先红在这里，而不是等真机评测。
# 统计口径：点估计阈值之外再卡 accuracy 的 Wilson 95% 下界——数字一律由 Java 侧
# EvalStatistics 计算（唯一事实源），脚本只做阈值比较，不自产数字。
set -euo pipefail
cd "$(dirname "$0")/.."

MVN="./mvnw"
[ -x "$MVN" ] || MVN="mvn"

LOG="$(mktemp /tmp/eval-gate-mvn.XXXXXX.log)"
trap 'rm -f "$LOG"' EXIT

echo "==> 1/3 跑 20 题 fixture（EvalSmokeFixtureTest，无网络/无 ES）"
if ! "$MVN" -q -B -Dtest=EvalSmokeFixtureTest test >"$LOG" 2>&1; then
  echo "!! fixture 评测失败，mvn 输出尾部：" >&2
  tail -40 "$LOG" >&2
  exit 1
fi
grep '\[eval-smoke\]' "$LOG" | tail -1 || true

REPORT="$(ls -t target/eval-smoke/*.json 2>/dev/null | head -1 || true)"
if [ -z "$REPORT" ]; then
  echo "!! 没找到 fixture 报告（target/eval-smoke/*.json）" >&2
  exit 1
fi

echo "==> 2/3 阈值门禁（python3 scripts/eval_compare.py）"
echo "报告: $REPORT"
python3 scripts/eval_compare.py "$REPORT" \
  --fail-if 'recallAtK<0.90' \
  --fail-if 'docRecallAtK<0.95' \
  --fail-if 'mrr<0.90' \
  --fail-if 'accuracy<0.85' \
  --fail-if 'meanJudgeScore<4.50' \
  --fail-if 'fullCoverageHitRate<0.80' \
  --fail-if 'retrievalErrorRate>0.10'

echo "==> 3/3 统计口径门禁（accuracy 的 Wilson 95% 下界，数字来自 EvalStatistics CLI）"
read -r ACC_CORRECT ACC_JUDGED <<<"$(python3 -c "import json,sys; s=json.load(open('$REPORT'))['summary']; print(s['correctCases'], s['judgedCases'])")"
CI_JSON="$(java -cp target/classes com.mindbase.service.eval.EvalStatistics wilson "$ACC_CORRECT" "$ACC_JUDGED")"
echo "accuracy $ACC_CORRECT/$ACC_JUDGED Wilson 95% CI: $CI_JSON"
ACC_LOWER="$(printf '%s' "$CI_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["lower"])')"
# 20 题 fixture 的区间很宽（18/19 → [75.4, 99.1]）；门禁卡区间下界而不是点估计，
# 防止小样本"看似 0.95 实则不稳"。下界阈值 0.70：fixture 真实回归时（如 14/19 → 下界 62%）会先红在这里。
if [ "$(python3 -c "print(1 if float('$ACC_LOWER') >= 0.70 else 0)")" != "1" ]; then
  echo "!! accuracy 的 Wilson 95% 下界 $ACC_LOWER < 0.70，小样本下不稳定，判为不通过" >&2
  exit 1
fi

echo "eval_gate: PASS（20 题 fixture + 7 条阈值 + Wilson 下界全部满足）"
