#!/usr/bin/env python3
"""评测报告对比与本地门禁。只读消费 Java harness（RagEvalService）产出的报告，本身不产生任何评测数字。

支持三种报告输入（自动识别，同一套指标口径）：
- v2 原始报告（schemaVersion=2.0，当前 RagEvalService 产出）：summary（含 nDCG@K / fullCoverageHitRate /
  retrievalErrorRate）+ cases 全量明细；
- v1 原始报告（无 schemaVersion 字段的历史产出）：summary + cases（retrievalHit/firstRelevantRank/reciprocalRank）；
- docs/eval/reports/ 下的脱敏归档：originalSummary（当时接口输出）/ overall（统一口径重算）+
  cases（chunkHit/chunkFirstRank，无分块明细列表，只有 retrievedCount）。

用法：
  python3 scripts/eval_compare.py report-A.json report-B.json ...         # 多份报告并排对比
  python3 scripts/eval_compare.py report.json --fail-if 'accuracy<0.85'   # 门禁：任一报告命中任一失败条件 → exit 3
  python3 scripts/eval_compare.py --self-test                             # 用内置两份真实结构 fixture 跑通新旧 schema

发布门禁（评委独立性）：--fail-if 涉及 accuracy / judgeScore 类指标且报告 config.releaseEligible=false
（评委为 SELF_JUDGE 自评，或 κ/可比样本不达标）时，打印 releaseBlockedReason 并退出 2——评委分数不可信，
不能据此做发布判定；确要继续须显式加 --allow-self-judge，放行时打印降级说明，结果仅作参考。

--fail-if 语法：<metric><op><value>（表达式是"失败条件"，成立即门禁失败），op 取 >= > <= < == !=。
指标名：
  summary 指标：recallAtK / docRecallAtK / mrr / accuracy / meanJudgeScore / meanExpectedDocCoverage /
                fullCoverageHitRate / retrievalErrorRate / failedCases / totalCases / labeledCases /
                judgedCases / correctCases / retrievalHits / needsHumanReviewCount / emptyRetrieval /
                ndcgAtK.3 / ndcgAtK.5 / ndcgAtK.10；
  题型指标：  <type>.<metric>，如 fact.recallAtK、multi.accuracy（type 取用例显式 type 字段或 caseId 前缀）。
指标缺失（旧 schema 没有该字段）或无法评估时按失败处理——门禁宁可误报也不放行。

退出码：0 门禁通过；1 报告读取/解析失败；2 发布门禁拦截（releaseEligible=false 且未 --allow-self-judge）；
3 --fail-if 指标条件未通过。
"""
import argparse
import json
import re
import sys
from collections import defaultdict

TYPES = ["fact", "para", "multi", "conflict", "rustfs", "resume", "neg"]
FAIL_DIAGNOSES = ("RETRIEVAL_FAILED", "GENERATION_FAILED", "JUDGE_FAILED")
FAILING_DIAGNOSES_SET = set(FAIL_DIAGNOSES)

# 题型指标名 → by_type 行键（与 summary 指标共用一套名字，避免两套词汇）
TYPE_ROW_KEYS = {
    "totalCases": "n",
    "recallAtK": "recall",
    "docRecallAtK": "doc_recall",
    "mrr": "mrr",
    "accuracy": "acc",
    "meanJudgeScore": "score",
    "failedCases": "failed",
    "emptyRetrieval": "empty",
    "judgedCases": "judged",
}

# 脱敏归档 overall 块的键名 → v1/v2 summary 规范键名（只映射名字，数值原样）
OVERALL_KEY_MAP = {
    "cases": "totalCases",
    "chunkRecallAt5": "recallAtK",
    "chunkMrr": "mrr",
    "docRecallAt5": "docRecallAtK",
    "judgeAccuracy": "accuracy",
    "failed": "failedCases",
}

CONDITION_RE = re.compile(
    r"^\s*([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)?)\s*(>=|<=|==|!=|>|<)\s*(-?\d+(?:\.\d+)?)\s*$")

# 依赖评委分数的指标（accuracy 及评委打分族）：这些门禁受报告 releaseEligible 发布门禁约束
JUDGE_METRIC_NAMES = {"accuracy", "meanJudgeScore", "judgeScore"}

# ---------------------------------------------------------------------------
# 内置 fixture：--self-test 用，两份都是真实报告改造，不是手写指标。
# - FIXTURE_V1：docs/eval/reports/05_分块级_阈值0.1_第1轮_45d445a4b5e9.json（脱敏归档）改造成原始
#   v1 报告形态：originalSummary→summary，chunkHit→retrievalHit、chunkFirstRank→firstRelevantRank
#   （值原样），reciprocalRank 按定义 1/r 还原；self-test 断言"从 cases 重算 == 记录的 summary"。
# - FIXTURE_V2：EvalSmokeFixtureTest 以生产 react 链路（Mockito 桩检索/模型，无网络无 ES）真实跑出
#   的 v2 报告，仅固定 runId 与时间戳；summary/cases/config 全部原样。
# ---------------------------------------------------------------------------
_FIXTURE_V1_JSON = r"""{"runId":"45d445a4b5e9","label":"v2-evidence-run1","datasetSource":"src/main/resources/eval/mindbase-eval-v1.jsonl","topK":5,"startedAt":"2026-10-11T03:35:46.039554Z","finishedAt":"2026-10-11T03:48:43.807843Z","config":{"topK":5,"llm":"deepseek/deepseek-v4-pro","generationMaxTokens":4000,"judgeMaxTokens":2000,"rerankEnabled":true,"rerankModel":"gte-rerank-v2","rerankScoreThreshold":0.1,"rrfK":60,"parentContextEnabled":true,"parentContextNeighbourChunks":1,"parentContextMaxCharsPerHit":1500,"contextualRetrievalEnabled":false,"humanReviewSampleRate":0.1,"randomSeed":42},"summary":{"totalCases":50,"labeledCases":46,"retrievalHits":35,"recallAtK":0.7609,"mrr":0.5902,"docRecallAtK":1.0,"evidenceLabeledCases":46,"meanExpectedDocCoverage":1.0,"judgedCases":50,"correctCases":43,"meanJudgeScore":4.32,"accuracy":0.86,"needsHumanReviewCount":1,"failedCases":0,"retrievalModeCounts":{"HYBRID":50},"diagnosisCounts":{"OK":35,"PROMPT_OR_MODEL":0,"RETRIEVAL_MISS":7,"MISS_BUT_CORRECT":4,"NOT_LABELED":4,"RETRIEVAL_FAILED":0,"GENERATION_FAILED":0,"JUDGE_FAILED":0}},"cases":[{"caseId":"fact-01-springboot","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.5069,0.4219,0.3397,0.292,0.2916],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":4,"judgeCorrect":true,"diagnosis":"OK","latencyMs":7727},{"caseId":"fact-02-es-version","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.5926,0.5291,0.396,0.3954,0.3944],"retrievalHit":true,"docHit":true,"firstRelevantRank":5,"reciprocalRank":0.2,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":5491},{"caseId":"fact-03-database","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.29,0.2538,0.2267,0.22,0.2122],"retrievalHit":true,"docHit":true,"firstRelevantRank":4,"reciprocalRank":0.25,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":9007},{"caseId":"fact-04-frontend-stack","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.7466,0.4142,0.4065,0.4032,0.396],"retrievalHit":false,"docHit":true,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":2,"judgeCorrect":false,"diagnosis":"RETRIEVAL_MISS","latencyMs":12332},{"caseId":"fact-05-pinia-modules","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.7599,0.6792,0.2153,0.2011,0.1837],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":4909},{"caseId":"fact-06-bitmap","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.636,0.3571,0.3515,0.2088,0.2058],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":13952},{"caseId":"fact-07-embedding-dim","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.5881,0.4038,0.328,0.2564,0.229],"retrievalHit":true,"docHit":true,"firstRelevantRank":3,"reciprocalRank":0.3333333333333333,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":8234},{"caseId":"fact-08-tika","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.6435,0.5134,0.472,0.4492,0.4312],"retrievalHit":true,"docHit":true,"firstRelevantRank":2,"reciprocalRank":0.5,"judgeScore":4,"judgeCorrect":true,"diagnosis":"OK","latencyMs":14103},{"caseId":"fact-09-knn-window","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.4081,0.3182,0.2209,0.1949,0.1908],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":7493},{"caseId":"fact-10-index-name","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.5942,0.5181,0.3842,0.3592,0.3547],"retrievalHit":false,"docHit":true,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":1,"judgeCorrect":false,"diagnosis":"RETRIEVAL_MISS","latencyMs":17686},{"caseId":"fact-11-jwt-claims","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.2177,0.1989,0.1985,0.1984,0.1971],"retrievalHit":true,"docHit":true,"firstRelevantRank":4,"reciprocalRank":0.25,"judgeScore":4,"judgeCorrect":true,"diagnosis":"OK","latencyMs":9767},{"caseId":"fact-12-permission-levels","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.8387,0.4679,0.3436,0.1874,0.1867],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":7456},{"caseId":"fact-13-private-prefix","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.7986,0.5231,0.4229,0.228,0.1974],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":5001},{"caseId":"fact-14-kafka-vs-rabbitmq","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.4963,0.4705,0.3282,0.2949,0.2867],"retrievalHit":true,"docHit":true,"firstRelevantRank":3,"reciprocalRank":0.3333333333333333,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":13774},{"caseId":"fact-15-mockito","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.2321,0.219,0.2018,0.1994,0.1991],"retrievalHit":false,"docHit":true,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":3,"judgeCorrect":false,"diagnosis":"RETRIEVAL_MISS","latencyMs":23314},{"caseId":"fact-16-finish-date","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.7771,0.3824,0.3449,0.3144,0.2934],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":5916},{"caseId":"fact-17-origin","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.8301,0.6057,0.5762,0.5037,0.472],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":8548},{"caseId":"fact-18-delete-document","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.218,0.1911,0.1887,0.1858,0.1846],"retrievalHit":false,"docHit":true,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":1,"judgeCorrect":false,"diagnosis":"RETRIEVAL_MISS","latencyMs":17548},{"caseId":"fact-19-axios","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.5986,0.4378,0.1762,0.1747,0.1736],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":8799},{"caseId":"fact-20-bool-should","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.8153,0.7645,0.7071,0.6688,0.5586],"retrievalHit":false,"docHit":true,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":2,"judgeCorrect":false,"diagnosis":"RETRIEVAL_MISS","latencyMs":47190},{"caseId":"fact-21-kafka-redis-minio","type":"fact","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.5824,0.517,0.3884,0.3805,0.3416],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":28961},{"caseId":"para-01-why-not-like","type":"para","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.18,0.165,0.1548,0.154,0.1434],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":8645},{"caseId":"para-02-network-broken","type":"para","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.1641,0.1623,0.1592,0.1512,0.1511],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":4,"judgeCorrect":true,"diagnosis":"OK","latencyMs":27254},{"caseId":"para-03-typewriter","type":"para","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.1433,0.1385,0.1308,0.1282,0.1108],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":3,"judgeCorrect":true,"diagnosis":"OK","latencyMs":45239},{"caseId":"para-04-colleague-visible","type":"para","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.1697,0.1613,0.1556,0.1546,0.1542],"retrievalHit":false,"docHit":true,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":3,"judgeCorrect":true,"diagnosis":"MISS_BUT_CORRECT","latencyMs":26244},{"caseId":"para-05-no-wait","type":"para","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.1935,0.1926,0.1875,0.1874,0.1846],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":4,"judgeCorrect":true,"diagnosis":"OK","latencyMs":14318},{"caseId":"para-06-remember-context","type":"para","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.1639,0.142,0.1355,0.1253,0.1172],"retrievalHit":false,"docHit":true,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":2,"judgeCorrect":false,"diagnosis":"RETRIEVAL_MISS","latencyMs":25467},{"caseId":"para-07-hallucination","type":"para","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.1464,0.1402,0.1393,0.1378,0.1344],"retrievalHit":true,"docHit":true,"firstRelevantRank":5,"reciprocalRank":0.2,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":17521},{"caseId":"para-08-offline-model","type":"para","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.1533,0.1498,0.1448,0.1445,0.1437],"retrievalHit":false,"docHit":true,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":4,"judgeCorrect":true,"diagnosis":"MISS_BUT_CORRECT","latencyMs":28318},{"caseId":"multi-01-ingest-pipeline","type":"multi","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.469,0.4486,0.4291,0.4182,0.4136],"retrievalHit":false,"docHit":true,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":4,"judgeCorrect":true,"diagnosis":"MISS_BUT_CORRECT","latencyMs":54106},{"caseId":"multi-02-qa-flow","type":"multi","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.4811,0.4308,0.2646,0.2463,0.2404],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":17648},{"caseId":"multi-03-rag-three-stages","type":"multi","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.7608,0.5124,0.4904,0.4542,0.4069],"retrievalHit":true,"docHit":true,"firstRelevantRank":2,"reciprocalRank":0.5,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":7797},{"caseId":"multi-04-redis-usages","type":"multi","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.3656,0.2474,0.1957,0.1931,0.1902],"retrievalHit":true,"docHit":true,"firstRelevantRank":3,"reciprocalRank":0.3333333333333333,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":22240},{"caseId":"multi-05-new-middleware","type":"multi","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.7131,0.4602,0.4075,0.3556,0.2873],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":37231},{"caseId":"multi-06-permission-chain","type":"multi","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.4466,0.352,0.3217,0.2691,0.2568],"retrievalHit":false,"docHit":true,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":3,"judgeCorrect":false,"diagnosis":"RETRIEVAL_MISS","latencyMs":24995},{"caseId":"conflict-01-embedding-vendor","type":"conflict","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.7172,0.688,0.4509,0.4306,0.3365],"retrievalHit":true,"docHit":true,"firstRelevantRank":4,"reciprocalRank":0.25,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":11032},{"caseId":"conflict-02-price","type":"conflict","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.7806,0.5536,0.46,0.4588,0.3971],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":14738},{"caseId":"rustfs-01-sdk","type":"rustfs","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.5188,0.249,0.1891,0.1845,0.182],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":9069},{"caseId":"rustfs-02-object-key","type":"rustfs","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.6257,0.1567,0.1562,0.1546,0.1316],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":7231},{"caseId":"rustfs-03-iceberg","type":"rustfs","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.4963,0.1757,0.1725,0.1701,0.1663],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":4,"judgeCorrect":true,"diagnosis":"OK","latencyMs":9201},{"caseId":"rustfs-04-version-limits","type":"rustfs","retrievalMode":"HYBRID","retrievedCount":2,"rerankScores":[0.4344,0.3477],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":4,"judgeCorrect":true,"diagnosis":"OK","latencyMs":9130},{"caseId":"rustfs-05-codename","type":"rustfs","retrievalMode":"HYBRID","retrievedCount":2,"rerankScores":[0.7836,0.1926],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":3696},{"caseId":"resume-01-paiflow-stack","type":"resume","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.8352,0.3206,0.2965,0.2709,0.1965],"retrievalHit":false,"docHit":true,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"MISS_BUT_CORRECT","latencyMs":5095},{"caseId":"resume-02-dag","type":"resume","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.1921,0.1916,0.1668,0.1614,0.1588],"retrievalHit":true,"docHit":true,"firstRelevantRank":2,"reciprocalRank":0.5,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":11817},{"caseId":"resume-03-minidb-index","type":"resume","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.1923,0.1851,0.1334,0.1272,0.1267],"retrievalHit":true,"docHit":true,"firstRelevantRank":2,"reciprocalRank":0.5,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":9653},{"caseId":"resume-04-wechat-callback","type":"resume","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.7014,0.1574,0.1473,0.1409,0.1231],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"OK","latencyMs":8942},{"caseId":"neg-01-helm","type":"neg","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.194,0.1936,0.1872,0.1869,0.1845],"retrievalHit":null,"docHit":null,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"NOT_LABELED","latencyMs":9588},{"caseId":"neg-02-milvus","type":"neg","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.1979,0.1944,0.1895,0.186,0.1846],"retrievalHit":null,"docHit":null,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"NOT_LABELED","latencyMs":14388},{"caseId":"neg-03-p99","type":"neg","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.1781,0.1679,0.1649,0.1649,0.1648],"retrievalHit":null,"docHit":null,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"NOT_LABELED","latencyMs":11723},{"caseId":"neg-04-neo4j","type":"neg","retrievalMode":"HYBRID","retrievedCount":5,"rerankScores":[0.298,0.2049,0.2038,0.1961,0.1953],"retrievalHit":null,"docHit":null,"firstRelevantRank":null,"reciprocalRank":0.0,"judgeScore":5,"judgeCorrect":true,"diagnosis":"NOT_LABELED","latencyMs":8234}]}"""
_FIXTURE_V2_JSON = r"""{"runId":"selftestv2","label":"smoke-fixture-20","datasetSource":"inline-fixture-20.jsonl","topK":5,"startedAt":"2026-11-09T00:00:00Z","finishedAt":"2026-11-09T00:00:10Z","schemaVersion":"2.0","gitCommit":"f0c116e93cff1c4c03571115a1be69f7d61885c2","esAlias":"knowledge_base","embeddingModel":null,"promptVersion":"unknown","config":{"topK":5,"mode":"react","reactChainNote":"与生产 WS 链路共用 ChatPipeline 的 ReAct 循环/工具调用/引用装配；差异：headless 运行无会话/幂等/限流，不落 MySQL/Redis，不推送 WS 帧，不做引用事后校验；工具检索以发起评测的管理员身份执行，模型调用以 eval.requester-id 计量","llm":null,"generationMaxTokens":4000,"judgeMaxTokens":2000,"rerankEnabled":true,"rerankModel":"gte-rerank-v2","rerankScoreThreshold":0.1,"rrfK":60,"parentContextEnabled":true,"parentContextNeighbourChunks":1,"parentContextMaxCharsPerHit":1500,"contextualRetrievalEnabled":false,"humanReviewSampleRate":0.0,"randomSeed":1},"tokenUsage":{"promptTokens":570,"completionTokens":285,"totalTokens":855},"cost":{"amount":null,"currency":null,"note":"unpriced: 模型单价未配置，不做估算"},"summary":{"totalCases":20,"labeledCases":19,"retrievalHits":18,"recallAtK":0.9474,"mrr":0.9211,"docRecallAtK":1.0,"evidenceLabeledCases":19,"meanExpectedDocCoverage":0.8947,"judgedCases":19,"correctCases":18,"meanJudgeScore":4.8421,"accuracy":0.9474,"needsHumanReviewCount":0,"failedCases":1,"retrievalModeCounts":{"HYBRID":19,"NONE":1},"diagnosisCounts":{"OK":18,"PROMPT_OR_MODEL":0,"RETRIEVAL_MISS":1,"MISS_BUT_CORRECT":0,"NOT_LABELED":0,"RETRIEVAL_FAILED":1,"GENERATION_FAILED":0,"JUDGE_FAILED":0},"ndcgAtK":{"3":0.9279,"5":0.9279,"10":0.9279},"fullCoverageHitRate":0.8421,"retrievalErrorRate":0.05},"diagnosisGuide":{"OK":"检索命中，回答正确","PROMPT_OR_MODEL":"检索命中但回答错误：资料已经给到模型，优先检查提示词或模型","RETRIEVAL_MISS":"检索未命中：先别动模型，优先检查分块和检索","MISS_BUT_CORRECT":"检索未命中但回答正确：检查期望文档的标注是否完整，或模型是否用了其他文档","NOT_LABELED":"没有标注期望文档，只评估回答","RETRIEVAL_FAILED":"检索调用失败","GENERATION_FAILED":"生成回答失败","JUDGE_FAILED":"评委调用失败或输出无法解析，需要人工判断"},"cases":[{"caseId":"fact-01-mindbase-role","question":"MindBase 是什么？","referenceAnswer":"MindBase 是一个企业级 AI 知识管理系统，围绕 RAG 工作流构建。","expectedFileMd5s":[],"expectedFileNames":["mindbase-intro.pdf"],"expectedEvidence":["企业级 AI 知识管理系统"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：MindBase 是一个企业级 AI 知识管理系统，围绕 RAG 工作流构建。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":20,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"fact-02-mindbase-storage","question":"MindBase 用什么数据库做主存储？","referenceAnswer":"MindBase 的主持久化使用 MySQL。","expectedFileMd5s":[],"expectedFileNames":["mindbase-intro.pdf"],"expectedEvidence":["MySQL"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：MindBase 的主持久化使用 MySQL。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":2,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"fact-03-mindbase-cache","question":"MindBase 的 Redis 用来做什么？","referenceAnswer":"Redis 用于缓存、会话和短期聊天上下文。","expectedFileMd5s":[],"expectedFileNames":["mindbase-intro.pdf"],"expectedEvidence":["缓存"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：Redis 用于缓存、会话和短期聊天上下文。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"fact-04-rustfs-api","question":"RustFS 支持什么接口？","referenceAnswer":"RustFS 提供 S3 兼容接口。","expectedFileMd5s":[],"expectedFileNames":["rustfs-notes.txt"],"expectedEvidence":["S3 兼容接口"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"rustfs-md5","fileName":"rustfs-notes.txt","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：RustFS 提供 S3 兼容接口。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"rustfs-md5","fileName":"rustfs-notes.txt","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"fact-05-rustfs-erasure","question":"RustFS 靠什么保证数据可靠？","referenceAnswer":"RustFS 使用纠删码保证数据可靠性。","expectedFileMd5s":[],"expectedFileNames":["rustfs-notes.txt"],"expectedEvidence":["纠删码"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"rustfs-md5","fileName":"rustfs-notes.txt","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：RustFS 使用纠删码保证数据可靠性。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"rustfs-md5","fileName":"rustfs-notes.txt","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"para-01-mindbase-rag","question":"MindBase 的 RAG 流程有哪些环节？","referenceAnswer":"包括检索、重排序与生成等环节。","expectedFileMd5s":[],"expectedFileNames":["mindbase-intro.pdf"],"expectedEvidence":["检索"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：包括检索、重排序与生成等环节。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"para-02-mindbase-search","question":"为什么 MindBase 默认用混合检索？","referenceAnswer":"混合检索兼顾关键词精确匹配与语义相似度。","expectedFileMd5s":[],"expectedFileNames":["mindbase-intro.pdf"],"expectedEvidence":["混合检索"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：混合检索兼顾关键词精确匹配与语义相似度。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":0,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"para-03-resume-focus","question":"候选人的主要技术方向是什么？","referenceAnswer":"主要技术方向是 AI 应用开发。","expectedFileMd5s":[],"expectedFileNames":["resume-sample.pdf"],"expectedEvidence":["AI 应用开发"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"resume-md5","fileName":"resume-sample.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：主要技术方向是 AI 应用开发。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"resume-md5","fileName":"resume-sample.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"multi-01-mindbase-stack","question":"MindBase 的后端基于什么框架？","referenceAnswer":"后端基于 Spring Boot，前端使用 Vue 3。","expectedFileMd5s":[],"expectedFileNames":["mindbase-intro.pdf"],"expectedEvidence":["Spring Boot"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：后端基于 Spring Boot，前端使用 Vue 3。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"multi-02-mindbase-mq","question":"MindBase 用什么做异步文件处理？","referenceAnswer":"使用 Kafka 做异步文件处理。","expectedFileMd5s":[],"expectedFileNames":["mindbase-intro.pdf"],"expectedEvidence":["Kafka"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"noise-md5","fileName":"noise.pdf","chunkId":1,"score":0.9,"relevant":false},{"rank":2,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":2,"reciprocalRank":0.5,"expectedDocCoverage":1.0,"answer":"根据知识库：使用 Kafka 做异步文件处理。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1,2],"references":[{"rank":1,"fileMd5":"noise-md5","fileName":"noise.pdf","chunkId":1,"score":0.9,"relevant":false},{"rank":2,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"multi-03-resume-projects","question":"简历里有哪几类项目经历？","referenceAnswer":"包括 RAG 知识库与推荐系统两类项目。","expectedFileMd5s":[],"expectedFileNames":["resume-sample.pdf"],"expectedEvidence":["RAG"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"resume-md5","fileName":"resume-sample.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：包括 RAG 知识库与推荐系统两类项目。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"resume-md5","fileName":"resume-sample.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"conflict-01-mindbase-port","question":"MindBase 后端服务运行在哪个端口？","referenceAnswer":"后端服务运行在 8081 端口。","expectedFileMd5s":[],"expectedFileNames":["mindbase-intro.pdf"],"expectedEvidence":["8081"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：后端服务运行在 8081 端口。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"rustfs-01-put","question":"怎么向 RustFS 上传对象？","referenceAnswer":"使用 S3 兼容的 PUT 接口上传对象。","expectedFileMd5s":[],"expectedFileNames":["rustfs-notes.txt"],"expectedEvidence":["PUT"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"rustfs-md5","fileName":"rustfs-notes.txt","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：使用 S3 兼容的 PUT 接口上传对象。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"rustfs-md5","fileName":"rustfs-notes.txt","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"rustfs-02-bucket","question":"RustFS 的桶是什么？","referenceAnswer":"桶是 RustFS 中存放对象的顶层容器。","expectedFileMd5s":[],"expectedFileNames":["rustfs-notes.txt"],"expectedEvidence":["桶"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"rustfs-md5","fileName":"rustfs-notes.txt","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：桶是 RustFS 中存放对象的顶层容器。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":0,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"rustfs-md5","fileName":"rustfs-notes.txt","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"resume-01-skills","question":"候选人掌握哪些编程语言？","referenceAnswer":"掌握 Java 与 TypeScript。","expectedFileMd5s":[],"expectedFileNames":["resume-sample.pdf"],"expectedEvidence":["Java"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"resume-md5","fileName":"resume-sample.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：掌握 Java 与 TypeScript。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"resume-md5","fileName":"resume-sample.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"resume-02-education","question":"候选人的学历是什么？","referenceAnswer":"本科学历，计算机相关专业。","expectedFileMd5s":[],"expectedFileNames":["resume-sample.pdf"],"expectedEvidence":["本科"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"resume-md5","fileName":"resume-sample.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":1.0,"answer":"根据知识库：本科学历，计算机相关专业。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"resume-md5","fileName":"resume-sample.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"multi-04-partial-storage","question":"MindBase 的存储和检索分别依赖什么？","referenceAnswer":"主存储用 MySQL，检索依赖 Elasticsearch。","expectedFileMd5s":[],"expectedFileNames":["mindbase-intro.pdf"],"expectedEvidence":["MySQL","Elasticsearch"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":0.5,"answer":"根据知识库：主存储用 MySQL，检索依赖 Elasticsearch。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"multi-05-partial-deploy","question":"RustFS 的部署形态和接口是什么？","referenceAnswer":"RustFS 以单二进制部署并提供 S3 兼容接口。","expectedFileMd5s":[],"expectedFileNames":["rustfs-notes.txt"],"expectedEvidence":["单二进制","S3 兼容接口"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"rustfs-md5","fileName":"rustfs-notes.txt","chunkId":1,"score":0.9,"relevant":true}],"retrievalHit":true,"docHit":true,"firstRelevantRank":1,"reciprocalRank":1.0,"expectedDocCoverage":0.5,"answer":"根据知识库：RustFS 以单二进制部署并提供 S3 兼容接口。 [1]。","judgeScore":5,"judgeCorrect":true,"judgeReason":"与参考答案一致","judgeRawOutput":null,"diagnosis":"OK","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":1,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"rustfs-md5","fileName":"rustfs-notes.txt","chunkId":1,"score":0.9,"relevant":true}]}},{"caseId":"neg-01-quantum","question":"MindBase 在量子计算上的应用方案是什么？","referenceAnswer":"知识库没有量子计算相关内容，应当拒答说明未找到。","expectedFileMd5s":[],"expectedFileNames":["mindbase-intro.pdf"],"expectedEvidence":["量子计算"],"retrievalMode":"HYBRID","retrieved":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":false}],"retrievalHit":false,"docHit":true,"firstRelevantRank":null,"reciprocalRank":0.0,"expectedDocCoverage":0.0,"answer":"MindBase 可以直接用于量子计算场景。","judgeScore":2,"judgeCorrect":false,"judgeReason":"应拒答未拒答，回答与知识库不符","judgeRawOutput":null,"diagnosis":"RETRIEVAL_MISS","needsHumanReview":false,"reviewReason":null,"error":null,"latencyMs":0,"retrievedText":null,"claimVerdicts":null,"tokenUsage":{"promptTokens":30,"completionTokens":15,"totalTokens":45},"reactTrace":{"modelRounds":2,"toolCallRounds":1,"toolCalls":1,"referenceNumbers":[1],"references":[{"rank":1,"fileMd5":"mb-md5","fileName":"mindbase-intro.pdf","chunkId":1,"score":0.9,"relevant":false}]}},{"caseId":"fact-06-retrieval-error","question":"MindBase 默认检索返回多少条？","referenceAnswer":"默认返回 topK 5 条。","expectedFileMd5s":[],"expectedFileNames":["mindbase-intro.pdf"],"expectedEvidence":["topK"],"retrievalMode":"NONE","retrieved":[],"retrievalHit":null,"docHit":null,"firstRelevantRank":null,"reciprocalRank":null,"expectedDocCoverage":null,"answer":null,"judgeScore":null,"judgeCorrect":null,"judgeReason":null,"judgeRawOutput":null,"diagnosis":"RETRIEVAL_FAILED","needsHumanReview":false,"reviewReason":null,"error":"检索失败: es down","latencyMs":0,"retrievedText":null,"claimVerdicts":null,"tokenUsage":null,"reactTrace":null}]}"""


def _fixtures():
    return {"v1": json.loads(_FIXTURE_V1_JSON), "v2": json.loads(_FIXTURE_V2_JSON)}


# ---------------------------------------------------------------------------
# 报告归一化
# ---------------------------------------------------------------------------

def _normalize_case(case):
    """cases 明细的两种形态归一：原始报告（retrievalHit/firstRelevantRank/retrieved 列表）与
    脱敏归档（chunkHit/chunkFirstRank/retrievedCount）。值只搬运/按定义还原，不改数字。"""
    out = dict(case)
    if "retrievalHit" not in out and "chunkHit" in out:
        out["retrievalHit"] = out["chunkHit"]
    if "firstRelevantRank" not in out and "chunkFirstRank" in out:
        out["firstRelevantRank"] = out["chunkFirstRank"]
    if out.get("reciprocalRank") is None:
        rank = out.get("firstRelevantRank")
        out["reciprocalRank"] = (1.0 / rank) if rank else 0.0
    if not isinstance(out.get("retrieved"), list):
        out.pop("retrieved", None)
    if out.get("retrievedCount") is None:
        out["retrievedCount"] = len(out.get("retrieved") or [])
    return out


def normalize_report(raw, source="<fixture>"):
    """把可识别的三种报告形态归一成固定视图；不可识别时报错而不是猜。"""
    if not isinstance(raw, dict) or not isinstance(raw.get("cases"), list):
        raise ValueError(f"{source}: 不是评测报告（缺 cases 明细）")
    report = {
        "source": source,
        "label": raw.get("label") or source,
        "topK": raw.get("topK"),
        "config": raw.get("config") or {},
        "cases": [_normalize_case(c) for c in raw["cases"]],
    }
    if "summary" in raw:
        report["schema"] = raw.get("schemaVersion") or "v1"
        report["summary"] = dict(raw["summary"] or {})
        report["summary_source"] = "summary"
    elif "originalSummary" in raw or "overall" in raw:
        # 脱敏归档：originalSummary 是当时接口输出的汇总（键名与 v1/v2 summary 同构），优先用它；
        # overall 是统一口径重算值，键名不同（chunkRecallAt5/judgeAccuracy/...），只做回退并映射键名，
        # 另补 overall 独有的 emptyRetrieval。
        report["schema"] = "archive"
        merged = {}
        if raw.get("overall"):
            for key, value in raw["overall"].items():
                merged[OVERALL_KEY_MAP.get(key, key)] = value
        if raw.get("originalSummary"):
            merged.update(raw["originalSummary"])
        report["summary"] = merged
        report["summary_source"] = "originalSummary" if raw.get("originalSummary") else "overall"
    else:
        raise ValueError(f"{source}: 不可识别的报告形态（缺 summary / originalSummary / overall）")
    return report


def load_report(path):
    with open(path, encoding="utf-8") as handle:
        return normalize_report(json.load(handle), source=str(path))


# ---------------------------------------------------------------------------
# 指标计算（只从 cases 重算显示用分组表；summary 数值以报告记录为准）
# ---------------------------------------------------------------------------

def _ratio(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def case_type(case):
    explicit = case.get("type")
    return str(explicit) if explicit else str(case["caseId"]).split("-")[0]


def by_type(report):
    groups = defaultdict(list)
    for case in report["cases"]:
        groups[case_type(case)].append(case)
    out = {}
    for t in TYPES + ["ALL"]:
        cases = report["cases"] if t == "ALL" else groups.get(t, [])
        if not cases:
            continue
        labeled = [c for c in cases if c.get("retrievalHit") is not None]
        judged = [c for c in cases if c.get("judgeCorrect") is not None]
        out[t] = {
            "n": len(cases),
            "recall": _ratio([1.0 if c.get("retrievalHit") else 0.0 for c in labeled]),
            # 旧报告没有 docHit 字段（只有文件级口径）时显示 -
            "doc_recall": (_ratio([1.0 if c.get("docHit") else 0.0 for c in labeled])
                           if labeled and all("docHit" in c for c in labeled) else None),
            "mrr": _ratio([c.get("reciprocalRank") or 0.0 for c in labeled]),
            "empty": sum(1 for c in cases if (c.get("retrievedCount") or 0) == 0),
            "judged": len(judged),
            "acc": _ratio([1.0 if c.get("judgeCorrect") else 0.0 for c in judged]),
            "score": _ratio([c.get("judgeScore") for c in judged]),
            "failed": sum(1 for c in cases if c.get("diagnosis") in FAILING_DIAGNOSES_SET),
        }
    return out


# ---------------------------------------------------------------------------
# 阈值门禁（--fail-if：表达式是失败条件，命中即违规；无法评估也按违规）
# ---------------------------------------------------------------------------

def evaluate_metric(report, metric):
    """返回 (value, note)。value 为 None 表示该报告评估不了该指标。"""
    summary = report["summary"]
    if metric.startswith("ndcgAtK."):
        k = metric[len("ndcgAtK."):]
        ndcg = summary.get("ndcgAtK")
        if not isinstance(ndcg, dict):
            return None, "该报告没有 ndcgAtK 字段（旧 schema 并列输出 nDCG@K）"
        return ndcg.get(str(k)), None
    if "." in metric:
        tname, key = metric.split(".", 1)
        row = by_type(report).get(tname)
        if row is None:
            return None, f"报告里没有题型 {tname}"
        if key not in TYPE_ROW_KEYS:
            return None, f"题型指标不支持 .{key}（可用: {'/'.join(sorted(TYPE_ROW_KEYS))}）"
        return row[TYPE_ROW_KEYS[key]], None
    if metric == "emptyRetrieval" and summary.get("emptyRetrieval") is None:
        # 归档/部分旧报告没有该汇总字段：从 cases 的检索条数重算
        return sum(1 for c in report["cases"] if (c.get("retrievedCount") or 0) == 0), "由 cases 重算"
    if metric in summary:
        return summary[metric], None
    return None, "summary 里没有该指标"


def _compare(actual, op, expected):
    return {
        ">=": actual >= expected,
        ">": actual > expected,
        "<=": actual <= expected,
        "<": actual < expected,
        "==": actual == expected,
        "!=": actual != expected,
    }[op]


def check_conditions(reports, conditions):
    """返回违规列表 [(报告, 条件, 说明)]；空列表表示全部通过。
    表达式是失败条件（成立即违规），如 'accuracy<0.85' 表示 accuracy 低于 0.85 时门禁失败；
    指标缺失或条件写错也按违规处理——门禁宁可误报也不放行。"""
    violations = []
    for report in reports:
        for condition in conditions:
            match = CONDITION_RE.match(condition)
            if not match:
                violations.append((report["label"], condition, "条件语法无法解析（应为 <metric><op><value>）"))
                continue
            metric, op, raw_value = match.group(1), match.group(2), float(match.group(3))
            actual, note = evaluate_metric(report, metric)
            if actual is None:
                reason = note or "指标缺失"
                violations.append((report["label"], condition, f"无法评估：{reason}"))
            elif _compare(float(actual), op, raw_value):
                violations.append((report["label"], condition,
                                   f"实际值 {actual} 命中失败条件 {metric} {op} {raw_value}"))
    return violations


# ---------------------------------------------------------------------------
# 发布门禁（评委独立性）：releaseEligible=false 的报告不得用 accuracy/judgeScore 做发布判定
# ---------------------------------------------------------------------------

def _is_judge_condition(condition):
    """--fail-if 条件是否依赖评委分数（accuracy / judgeScore 族，含题型指标如 fact.accuracy）。"""
    match = CONDITION_RE.match(condition)
    if not match:
        return False
    return match.group(1).split(".")[-1] in JUDGE_METRIC_NAMES


def release_gate_blocks(reports, conditions):
    """发布门禁阻断项：[(label, judgeIndependence, releaseBlockedReason)]，空列表表示无阻断。

    只在 --fail-if 涉及 accuracy/judgeScore 且报告 config.releaseEligible=false（布尔假，
    Java 侧 EvalReportAggregator.releaseGate 按评委独立性与 κ/可比样本规则写入）时阻断。
    旧报告没有 releaseEligible 字段时不在这一层拦——缺指标的风险由 check_conditions 的
    "无法评估也按违规"承担；纯检索类门禁（recall/mrr 等）不依赖评委分数，不受此门禁影响。
    """
    if not any(_is_judge_condition(condition) for condition in conditions):
        return []
    blocks = []
    for report in reports:
        config = report["config"] or {}
        if config.get("releaseEligible") is False:
            blocks.append((report["label"], config.get("judgeIndependence"),
                           config.get("releaseBlockedReason")))
    return blocks


# ---------------------------------------------------------------------------
# 展示
# ---------------------------------------------------------------------------

def _fmt(value, pct=True):
    if value is None:
        return "-"
    return f"{value * 100:.1f}%" if pct else f"{value:.2f}"


def render(reports):
    lines = []
    for report in reports:
        s = report["summary"]
        label = f"{report['label']} [{report['schema']}, summary:{report['summary_source']}]"
        lines.append(f"== {label} (rerank 阈值 {report['config'].get('rerankScoreThreshold')}, "
                     f"topK {report['topK']}, 模型 {report['config'].get('llm', '-')}) ==")
        lines.append(f"   Recall@K {_fmt(s.get('recallAtK'))}  文件级 {_fmt(s.get('docRecallAtK'))}"
                     f"  MRR {_fmt(s.get('mrr'), False)}  评委准确率 {_fmt(s.get('accuracy'))}"
                     f"  平均分 {_fmt(s.get('meanJudgeScore'), False)}  失败 {s.get('failedCases')}"
                     f"  检索模式 {s.get('retrievalModeCounts')}")
        lines.append(f"   归因 {s.get('diagnosisCounts')}")
        extras = []
        if isinstance(s.get("ndcgAtK"), dict) and s["ndcgAtK"]:
            joined = ", ".join(f"@{k}={_fmt(v)}" for k, v in sorted(s["ndcgAtK"].items()))
            extras.append(f"nDCG {joined}")
        if "fullCoverageHitRate" in s:
            extras.append(f"全命中 {_fmt(s.get('fullCoverageHitRate'))}")
        if "retrievalErrorRate" in s:
            extras.append(f"检索异常率 {_fmt(s.get('retrievalErrorRate'))}")
        if extras:
            lines.append("   v2: " + "  ".join(extras))
    lines.append("")
    lines.append("题型      | " + " | ".join(f"{report['label']:^52}" for report in reports))
    lines.append("          | " + " | ".join(f"{'Recall':>7} {'文件级':>7} {'MRR':>5} {'空':>3} {'准确率':>7} {'均分':>5} {'失败':>3}"
                                             for _ in reports))
    tables = [by_type(report) for report in reports]
    for t in TYPES + ["ALL"]:
        cells = []
        for tab in tables:
            row = tab.get(t)
            if row is None:
                cells.append(" " * 52)
                continue
            cells.append(f"{_fmt(row['recall']):>7} {_fmt(row['doc_recall']):>7} {_fmt(row['mrr'], False):>5} "
                         f"{row['empty']:>3} {_fmt(row['acc']):>7} {_fmt(row['score'], False):>5} {row['failed']:>3}")
        n = tables[0].get(t, {}).get("n", 0)
        lines.append(f"{t:<6}({n:>2}) | " + " | ".join(cells))
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# --self-test：内置两份真实结构 fixture 跑通新旧 schema
# ---------------------------------------------------------------------------

def _recomputed_overview(report):
    """从 cases 重算三个核心指标，用来证明 fixture 数字内部自洽（不是手写的）。"""
    labeled = [c for c in report["cases"] if c.get("retrievalHit") is not None]
    judged = [c for c in report["cases"] if c.get("judgeCorrect") is not None]
    recall = sum(1 for c in labeled if c["retrievalHit"]) / len(labeled) if labeled else None
    mrr = _ratio([c.get("reciprocalRank") or 0.0 for c in labeled]) if labeled else None
    acc = _ratio([1.0 if c.get("judgeCorrect") else 0.0 for c in judged]) if judged else None
    return recall, mrr, acc


def _to_archive_shape(v1_fixture):
    """由 v1 fixture 机械变换出脱敏归档形态（summary→originalSummary、retrievalHit→chunkHit，值不变；
    overall 块按真实归档的键名映射同一批数字），用来验证归档读取路径；不引入任何新数字。"""
    cases = []
    for case in v1_fixture["cases"]:
        item = {k: v for k, v in case.items() if k not in ("retrievalHit", "firstRelevantRank", "reciprocalRank")}
        item["chunkHit"] = case["retrievalHit"]
        item["chunkFirstRank"] = case["firstRelevantRank"]
        cases.append(item)
    summary = v1_fixture["summary"]
    reverse_overall = {value: key for key, value in OVERALL_KEY_MAP.items()}
    overall = {}
    for key, value in summary.items():
        if key in reverse_overall and reverse_overall[key] not in overall:
            overall[reverse_overall[key]] = value
    overall["emptyRetrieval"] = sum(1 for c in v1_fixture["cases"] if (c.get("retrievedCount") or 0) == 0)
    return {
        "runId": v1_fixture["runId"],
        "label": v1_fixture["label"],
        "topK": v1_fixture["topK"],
        "startedAt": v1_fixture["startedAt"],
        "finishedAt": v1_fixture["finishedAt"],
        "config": v1_fixture["config"],
        "originalSummary": summary,
        "overall": overall,
        "cases": cases,
    }


def self_test():
    fixtures = _fixtures()
    v1 = normalize_report(fixtures["v1"], source="fixture-v1")
    v2 = normalize_report(fixtures["v2"], source="fixture-v2")
    archive = normalize_report(_to_archive_shape(fixtures["v1"]), source="fixture-archive")

    # 1) schema 识别
    assert v1["schema"] == "v1", f"v1 fixture 识别为 {v1['schema']}"
    assert v2["schema"] == "2.0", f"v2 fixture 识别为 {v2['schema']}"
    assert archive["schema"] == "archive", f"归档 fixture 识别为 {archive['schema']}"
    assert len(v1["cases"]) == 50 and len(v2["cases"]) == 20

    # 2) fixture 自洽：从 cases 重算 == 报告记录的 summary（数字不是手写的）
    for name, report in (("v1", v1), ("v2", v2), ("archive", archive)):
        recall, mrr, acc = _recomputed_overview(report)
        summary = report["summary"]
        assert abs(recall - summary["recallAtK"]) < 1e-3, f"{name} recall 重算不一致: {recall} vs {summary['recallAtK']}"
        assert abs(mrr - summary["mrr"]) < 1e-3, f"{name} mrr 重算不一致: {mrr} vs {summary['mrr']}"
        assert abs(acc - summary["accuracy"]) < 1e-3, f"{name} accuracy 重算不一致: {acc} vs {summary['accuracy']}"

    # 3) 归档形态与原始 v1 形态读到同一套指标（值只搬运不改写）
    for key in ("recallAtK", "mrr", "accuracy", "docRecallAtK"):
        assert archive["summary"][key] == v1["summary"][key], f"archive/v1 的 {key} 不一致"

    # 4) v2 新指标字段齐全且可评估；旧 schema 缺字段时门禁要能察觉（不静默放行）
    for metric in ("fullCoverageHitRate", "retrievalErrorRate", "ndcgAtK.3", "ndcgAtK.5", "ndcgAtK.10"):
        value, note = evaluate_metric(v2, metric)
        assert value is not None, f"v2 fixture 缺 v2 指标 {metric}: {note}"
    value, note = evaluate_metric(v1, "ndcgAtK.5")
    assert value is None, "v1 fixture 不应有 ndcgAtK"
    assert check_conditions([v1], ["ndcgAtK.5<1"]), "旧报告缺 ndcgAtK 必须按违规处理而不是跳过"

    # 5) 对比表渲染：三份报告（归档/v1/v2）同表输出，关键行存在
    rendered = render([archive, v1, v2])
    for expected in ("fact", "ALL", "Recall@K", "nDCG", "全命中", "检索异常率",
                     "v2-evidence-run1 [v1", "smoke-fixture-20 [2.0", "[archive"):
        assert expected in rendered, f"对比输出缺少 {expected!r}"
    assert len(rendered.splitlines()) > 12, "题型分组表应有多行输出"

    # 6) --fail-if 判定方向：通过与失败两个方向都要验
    assert not check_conditions([v2], ["accuracy<0.5", "recallAtK<0.5", "retrievalErrorRate>0.5",
                                       "fact.recallAtK<0.5", "meanJudgeScore<1.0"]), "好报告不应触发门禁"
    assert check_conditions([v2], ["accuracy<0.99"]), "accuracy 低于阈值必须触发"
    assert check_conditions([v2], ["retrievalErrorRate>0.01"]), "error-rate 型阈值方向必须生效"
    assert not check_conditions([v2], ["neg.totalCases<1"]), "neg 题型恰好 1 条，不触发"
    assert check_conditions([v2], ["multi.totalCases>4"]), "题型指标可评估且方向正确（multi 共 5 条）"
    assert len(check_conditions([v2], ["notAMetric>=1"])) == 1, "未知指标必须按违规处理"
    assert len(check_conditions([v2], ["garbage condition"])) == 1, "非法条件必须按违规处理"

    # 7) 发布门禁：releaseEligible=false 的报告在 accuracy/judgeScore 门禁上必须被拦（评测缺口修复）
    self_judge_raw = json.loads(_FIXTURE_V2_JSON)
    self_judge_raw["config"] = dict(self_judge_raw["config"])
    self_judge_raw["config"].update({
        "judgeIndependence": "SELF_JUDGE",
        "releaseEligible": False,
        "releaseBlockedReason": "评委与生成共用同一模型（SELF_JUDGE），自评分数不能用于发布判定；"
                                "kappa 未计算：无人工复核数据，不伪造 κ",
    })
    self_judge = normalize_report(self_judge_raw, source="fixture-self-judge")
    blocks = release_gate_blocks([self_judge], ["accuracy<0.99"])
    assert len(blocks) == 1 and "SELF_JUDGE" in (blocks[0][2] or ""), \
        "self-judge 报告的 accuracy 门禁必须被发布门禁拦下并给出原因"
    assert release_gate_blocks([self_judge], ["recallAtK<0.99"]) == [], \
        "检索类门禁不依赖评委分数，不受发布门禁影响"
    assert release_gate_blocks([v2], ["accuracy<0.99"]) == [], \
        "旧报告没有 releaseEligible 字段时不走发布门禁（缺指标风险由 check_conditions 承担）"
    eligible_raw = json.loads(_FIXTURE_V2_JSON)
    eligible_raw["config"] = dict(eligible_raw["config"])
    eligible_raw["config"].update({"judgeIndependence": "INDEPENDENT", "releaseEligible": True})
    eligible = normalize_report(eligible_raw, source="fixture-independent")
    assert release_gate_blocks([eligible], ["accuracy<0.99"]) == [], \
        "releaseEligible=true 的独立评委报告不被发布门禁拦截"
    assert not _is_judge_condition("recallAtK<0.9") and _is_judge_condition("fact.accuracy<0.9") \
           and _is_judge_condition("meanJudgeScore<4"), "评委分数族指标识别（含题型指标）"

    print("self-test OK: v1(50 题, schema v1) / v2(20 题, schema 2.0) / archive 形态全部跑通")
    print("  - fixture 指标从 cases 重算与记录 summary 一致（v1: recall 0.7609/mrr 0.5902/acc 0.86；"
          "v2: recall 0.9474/mrr 0.9211/acc 0.9474）")
    print("  - --fail-if 通过/失败/缺字段/未知指标/非法条件 五种判定方向全部验证")
    print("  - 发布门禁：self-judge 报告 accuracy 门禁被拦、检索类门禁放行、releaseEligible=true 放行")
    return 0


# ---------------------------------------------------------------------------

def main(argv):
    parser = argparse.ArgumentParser(description="评测报告对比与本地门禁（详见模块 docstring）")
    parser.add_argument("reports", nargs="*", help="评测报告 JSON 路径（支持 v1/v2/脱敏归档）")
    parser.add_argument("--fail-if", dest="fail_if", action="append", default=[],
                        metavar="'<metric><op><value>'",
                        help="失败条件，如 'accuracy<0.85'；任一报告命中即以非 0 退出")
    parser.add_argument("--allow-self-judge", dest="allow_self_judge", action="store_true",
                        help="放行 releaseEligible=false 的报告继续跑 accuracy/judgeScore 门禁"
                             "（打印降级说明，结果仅作参考，不得作为发布判定依据）")
    parser.add_argument("--self-test", dest="self_test", action="store_true",
                        help="用内置两份真实结构 fixture 跑通新旧 schema 与门禁判定")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.reports:
        parser.error("至少给一份报告路径，或使用 --self-test")

    reports = [load_report(path) for path in args.reports]
    print(render(reports))

    blocks = release_gate_blocks(reports, args.fail_if)
    if blocks and not args.allow_self_judge:
        print()
        print(f"发布门禁未通过：{len(blocks)} 份报告 releaseEligible=false，"
              "其评委分数不能用于 accuracy/judgeScore 类发布判定:", file=sys.stderr)
        for label, independence, reason in blocks:
            print(f"  [{label}] judgeIndependence={independence or '未知'} — {reason or '报告未记录原因'}",
                  file=sys.stderr)
        print("评测缺口修复：self-judge 或评委一致性不达标的报告，accuracy/judgeScore 只能参考，"
              "不得作为发布依据。", file=sys.stderr)
        print("确要继续请显式加 --allow-self-judge。", file=sys.stderr)
        return 2
    if blocks:
        print()
        print(f"降级说明：--allow-self-judge 已放行 {len(blocks)} 份 releaseEligible=false 的报告，"
              "评委分数不可信，本次 accuracy/judgeScore 结果仅作参考，不得作为发布判定依据：")
        for label, independence, reason in blocks:
            print(f"  [{label}] judgeIndependence={independence or '未知'} — {reason or '报告未记录原因'}")

    violations = check_conditions(reports, args.fail_if)
    if violations:
        print()
        print(f"门禁未通过（{len(violations)} 条）:", file=sys.stderr)
        for label, condition, detail in violations:
            print(f"  [{label}] {condition} — {detail}", file=sys.stderr)
        return 3
    if args.fail_if:
        print()
        print(f"门禁通过：{len(args.fail_if)} 条阈值条件 × {len(reports)} 份报告全部满足")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"eval_compare: {error}", file=sys.stderr)
        sys.exit(1)
