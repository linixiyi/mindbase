#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v2 报告校验与压缩摘要（task-0007，eval-real-run 包）：核验一份 v2 评测报告"真的跑了
120 题有效口径"，并产出不含任何回答正文的压缩摘要 JSON，供 docs/eval/reports/ 归档；
--check 反向校验已有摘要（task-0008 的 verify 只调 --check，不重复触发评测）。

背景（spec C7 / D3 / D9）：报告全文（126 题的 question/answer/retrievedText/judgeReason）
留在 git 忽略的 eval-reports/，交付需要可审计的证据又不把正文放进公开仓库——本脚本就是
"报告 → 压缩摘要"这道闸。真机操作（启服务/跑评测）由编排脚本负责，本脚本只读文件。

计数口径（spec D9，2026-11-13 裁决）：后端跑全量数据集（eval-v2.jsonl 126 行，其中 6 行
status="blocked" 的 OCR 题也会被执行——EvalDatasetParser 不读 status、EvalCase 无 status
字段、EvalReportAggregator.summarize 把 totalCases 记为 results.size()），所以真机报告
summary.totalCases=126。摘要按"计数口径"核对并呈现三个数：
    reportTotalCases=126（报告原样）   blockedExcluded=6（caseId 命中数据集 blocked 集）
    effectiveCases=120（剔除后，必须 ==120，否则退出 1 并列差集）
blocked 集来源：按报告 datasetSource 定位数据集文件（镜像 RagEvalService.loadDataset 的解析：
classpath:eval/<名> → src/main/resources/eval/<名>；其余先按 eval-datasets/ 相对（后端
eval.dataset-dir 缺省）、再按工作树根相对（D9）；绝对路径按原样；upload:/inline 形态无法
定位）→ 解析 status="blocked" 行的 id，与报告 case.caseId 精确匹配——id 对 id 的事实匹配，
不猜测。找不到数据集文件时报"无法核对计数口径"失败（退出 2），不猜；数据集没有任何
blocked id 时按同口径核对（此时有效题数=全部用例数，与 120 不符要如实报，见 D9）。
指标数字一律原样引用报告（Python 侧不自产数字，与 docs/eval/reports/README-v2.md 口径
一致）；counting 块只记录剔除事实，不改写任何报告指标。

effective release gate（sidecar 的 κ 与重算结论）：复用 scripts/eval_release_preflight.py
的同一套纯函数（resolve_thresholds / evaluate / find_agreement / sidecar_path_for），保证与
task-0006 预检同口径：报告 config + sidecar agreement（缺省自动找同目录
{报告名}.human-reviews.json，--sidecar 可显式指定，都没有时回退报告内嵌 agreement）合并判定
三道门禁（评委独立 / 人工 κ / 已配价）。报告原始 release gate（judgeIndependence /
releaseEligible / releaseBlockedReason）冻结字段原样入摘要，不被改写；报告冻结 false + sidecar
κ 达标翻盘为 effective true 属正常呈现（预检同款语义）。--write 不因 effective gate false 而
拒绝落盘——首跑 SELF_JUDGE 的摘要必须如实归档（releaseEligible=false 如实呈现，不伪装可发布）。

摘要内容（不含 question/answer/retrievedText 等任何正文）：runId、label、startedAt/finishedAt、
datasetSource、topK、generation/judge 模型（报告 config.llm/judge 原值；真实报告在没有激活
主供应商时 llm=null——RagEvalCaseRunner.primaryLlmDescription 的容错口径——摘要如实记 null，
不编造模型名）、config 关键项（judge/judgeIndependence/rrf/rerank/parentContext）、
summary 指标子集（原样引用）、counting 三数与 blocked id 清单、报告原始
release gate、sidecar κ 与 effective release gate、cost。结构版本 summarySchemaVersion=1；
落盘前先过与 --check 同一套校验（含 <30KB 断言）再原子写入；--check 深扫全部键与字符串：
禁止键（question/answer/retrievedText 等）命中、或任何字符串 >500 字符判为疑似正文，一律拒绝。

用法（在工作树根执行）：
    python3 -B scripts/eval_report_summary.py --write docs/eval/reports/v2-first-summary.json
        # 报告缺省自动取 eval-reports/ 下最新一份 *.json（*.human-reviews.json 除外；
        # EVAL_MAX_CASES=20 小跑报告会因有效题数 !=120 被拒，防止把预热轮当正式轮归档）
    python3 -B scripts/eval_report_summary.py --check docs/eval/reports/v2-first-summary.json
    python3 -B scripts/eval_report_summary.py --report eval-reports/xxx.json \
        --sidecar eval-reports/xxx.human-reviews.json --write /tmp/summary.json
    python3 -B scripts/eval_report_summary.py --self-test

退出码：
    0  校验通过（--write 已落盘 / --check 摘要有效）
    1  校验失败（不是 v2 报告 / 题数不符 / 覆盖率为 null / gate 键缺失 / 摘要含正文 /
       摘要字段缺失或不自洽 / 超过 30KB）
    2  环境/用法错误（报告或数据集文件缺失/非法 JSON、数据集无法定位无法核对计数口径、
       --sidecar/--env-file 指定的文件不存在、--write 目标与报告或 sidecar 冲突）
    --self-test 固定走内置 fixture，离线、不联网、不读宿主环境、不读真实 .env 与真实报告。

凭据纪律：本脚本只读文件，不登录、不持 token；--env-file（缺省 $EVAL_ENV_FILE → 仓库根
.env）只用于 eval.human-agreement 阈值键（EVAL_HUMAN_AGREEMENT_*，与 eval_release_preflight
同优先级：记录值 > env > 缺省 0.6/5），任何值只进内存，不打印、不落盘。报告与 sidecar 文件
只读不改（--write 目标撞上报告/sidecar 直接退出 2）。只依赖 Python 3 标准库（spec D1）。
"""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

import eval_release_preflight as preflight   # 同目录脚本（task-0006）：effective gate 同口径复用

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_REPORT_DIR = REPO_ROOT / "eval-reports"
CLASSPATH_PREFIX = "classpath:eval/"          # RagEvalService.CLASSPATH_PREFIX（RagEvalService.java:62）
DATASET_DIR = "eval-datasets"                 # RagEvalProperties.datasetDir 缺省（RagEvalProperties.java:25）
CLASSPATH_DIR = REPO_ROOT / "src/main/resources/eval"

SUMMARY_SCHEMA_VERSION = 1
EFFECTIVE_CASES_REQUIRED = 120                # 计数题数（README-v2 语料说明；spec D9）
MAX_SUMMARY_BYTES = 30 * 1024                 # 成功判定：摘要文件 <30KB
MAX_STRING_CHARS = 500                        # 超过判为疑似正文（合法最长字段是 cost/阻断原因 ~300）

REPORT_DOC = "docs/eval/reports/README-v2.md"

# 摘要禁止出现的键（正文泄漏防线；本脚本产出的摘要块名刻意避开这些名字）
FORBIDDEN_KEYS = frozenset({
    "question", "answer", "referenceAnswer", "retrievedText", "retrieved", "judgeReason",
    "judgeRawOutput", "judgeRaw", "claimVerdicts", "claimVerdict", "reactTrace", "textContent",
    "expectedEvidence", "cases", "caseResults", "error", "agreement", "humanReview",
    "reviews", "diagnosisGuide",
})

# summary 指标子集：原样引用报告 EvalSummary 的字段（键名与 EvalReport.EvalSummary 组件一致）
METRIC_KEYS = (
    "totalCases", "labeledCases", "retrievalCases", "negExcludedCases", "retrievalHits",
    "recallAtK", "mrr", "docRecallAtK", "evidenceLabeledCases", "meanExpectedDocCoverage",
    "judgedCases", "correctCases", "meanJudgeScore", "accuracy", "needsHumanReviewCount",
    "failedCases", "retrievalModeCounts", "diagnosisCounts", "ndcgAtK", "fullCoverageHitRate",
    "retrievalErrorRate", "refusalLabeledCases", "refusalCorrectCases", "refusalAccuracy",
    "refusalExpectedCases", "refusedAsExpected", "faithfulness", "citationPrecision",
    "citationRecall", "judgeRepeatEnabled", "judgeAgreementCases", "judgeDisagreementCases",
    "judgeAgreementRate",
)

# config 关键项（任务书：judge/rrf/rerank/parentContext）
CONFIG_KEYS = (
    "judge", "judgeIndependence", "rrfK", "rerankEnabled", "rerankModel", "rerankScoreThreshold",
    "parentContextEnabled", "parentContextNeighbourChunks", "parentContextMaxCharsPerHit",
)

METRICS_NOTE = ("报告原始数字：后端按报告全部用例计算（分母含 blocked 执行题），"
                "本摘要原样引用、不重算；有效计数口径见 counting 块（D9）")
COUNTING_NOTE = ("D9 计数口径：有效题数=报告用例剔除数据集 status=blocked 的 caseId 后必须为 120；"
                 "指标原样引用报告不重算")

REQUIRED_TOP_KEYS = (
    "summarySchemaVersion", "generatedAt", "source", "runId", "label", "datasetSource", "topK",
    "startedAt", "finishedAt", "models", "config", "counting", "metrics", "releaseGate",
    "effectiveReleaseGate", "cost",
)

DIFF_PREVIEW = 10   # 差集列表最多列多少条（其余以"共 N 条"收尾）


class SummaryError(Exception):
    """校验失败（内容性），退出 1。"""


class UsageError(Exception):
    """环境/用法错误，退出 2。"""


# ---------------------------------------------------------------------------
# JSON / 值工具（eval_release_preflight 同口径）
# ---------------------------------------------------------------------------

def _load_json(path) -> object:
    try:
        return preflight.load_json_file(path)
    except preflight.PreflightError as exc:
        raise UsageError(str(exc)) from None


def _rel(base: Path, path: Path) -> str:
    """工作树内路径转相对显示；工作树外（如 self-test 临时目录）保留绝对路径。"""
    resolved = Path(path).resolve()
    try:
        return str(resolved.relative_to(Path(base).resolve()))
    except ValueError:
        return str(resolved)


# ---------------------------------------------------------------------------
# 数据集定位与 blocked id 集（D9 计数口径的事实来源）
# ---------------------------------------------------------------------------

def resolve_dataset_source(dataset_source):
    """按报告 datasetSource 定位本地数据集文件。

    镜像 RagEvalService.loadDataset（RagEvalService.java:134-170）的解析规则，另加 D9 的
    工作树根相对回退。返回 (存在的 Path 或 None, 解析方式说明)。定位不到不猜，交给调用方
    报"无法核对计数口径"。
    """
    if not isinstance(dataset_source, str) or not dataset_source.strip():
        return None, "datasetSource 缺失或为空"
    src = dataset_source.strip()
    if src.startswith(CLASSPATH_PREFIX):
        name = src[len(CLASSPATH_PREFIX):]
        candidate = CLASSPATH_DIR / name
        return (candidate if candidate.is_file() else None,
                f"{CLASSPATH_PREFIX}<名> → {_rel(REPO_ROOT, candidate)}")
    if src.startswith("upload:") or src == "inline":
        return None, f"{src.split(':', 1)[0]}:* 形态不落盘为可直接读取的数据集文件"
    given = Path(src)
    if given.is_absolute():
        candidate = given
        return (candidate if candidate.is_file() else None, f"绝对路径 → {candidate}")
    for base, label in ((REPO_ROOT / DATASET_DIR, f"{DATASET_DIR}/ 相对（后端 eval.dataset-dir 缺省）"),
                        (REPO_ROOT, "工作树根相对（D9）")):
        candidate = base / given
        if candidate.is_file():
            return candidate, label
    return None, "eval-datasets/ 相对与工作树根相对均不存在"


def parse_dataset_ids(path):
    """解析数据集 JSONL，返回 (blocked_ids 集, counting_ids 有序表)。

    行号口径与 EvalDatasetParser 一致：物理行号（注释/空行也计数），id 缺省 line-<行号>
    （EvalDatasetParser.parseLine 的兼容命名）。status=="blocked"（EvalDatasetIntegrityTest
    同款字面比较）进 blocked 集，其余进计数表。
    """
    try:
        raw = Path(path).read_text(encoding="utf-8")
    except OSError as exc:
        raise UsageError(f"数据集文件不可读: {path}: {exc}") from None
    blocked: set = set()
    counting: list = []
    for line_no, line in enumerate(raw.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("//"):
            continue
        try:
            row = json.loads(stripped)
        except json.JSONDecodeError as exc:
            raise UsageError(f"数据集文件 {path} 第 {line_no} 行不是合法 JSON: {exc.msg}") from None
        if not isinstance(row, dict):
            raise UsageError(f"数据集文件 {path} 第 {line_no} 行必须是 JSON 对象")
        case_id = row.get("id")
        if isinstance(case_id, str):
            case_id = case_id.strip() or None
        elif isinstance(case_id, int) and not isinstance(case_id, bool):
            case_id = str(case_id)
        else:
            case_id = None
        if case_id is None:
            case_id = f"line-{line_no}"   # EvalDatasetParser：id 缺省 line-<行号>
        status = row.get("status")
        if isinstance(status, str):
            status = status.strip()
        if status == "blocked":
            blocked.add(case_id)
        else:
            counting.append(case_id)
    if not counting and not blocked:
        raise UsageError(f"数据集文件 {path} 没有任何用例行")
    return blocked, counting


def _bounded(items, limit=DIFF_PREVIEW):
    shown = "、".join(items[:limit])
    more = "" if len(items) <= limit else f"（共 {len(items)} 条，仅列前 {limit}）"
    return f"{shown}{more}" if items else "（无）"


# ---------------------------------------------------------------------------
# 报告结构校验（退出 1 的内容性失败；schemaVersion 缺失/不符 = 不是 v2 报告，不猜测）
# ---------------------------------------------------------------------------

def validate_report(root) -> dict:
    """结构性校验。返回后续步骤要用的引用；任何一项不过 raise SummaryError（退出 1）。"""
    if not isinstance(root, dict):
        raise SummaryError("报告必须是 JSON 对象")
    schema = root.get("schemaVersion")
    if schema is None or (isinstance(schema, str) and not schema.strip()):
        raise SummaryError(f"不是 v2 报告：缺 schemaVersion 字段（不回退猜测，口径见 {REPORT_DOC}）")
    if str(schema).strip() != "2.0":
        raise SummaryError(f"不是 v2 报告：schemaVersion={schema!r}，本脚本只认 2.0（不回退猜测）")

    failures = []
    config = root.get("config")
    if not isinstance(config, dict):
        failures.append("报告缺 config 对象")
        config = {}
    else:
        if preflight.as_text(config.get("judgeIndependence")) is None:
            failures.append("报告 config 缺 judgeIndependence（或为空）")
        if "releaseEligible" not in config:
            failures.append("报告 config 缺 releaseEligible 键")
        elif not isinstance(config.get("releaseEligible"), bool):
            failures.append("报告 config.releaseEligible 不是布尔值")
        if "releaseBlockedReason" not in config:
            failures.append("报告 config 缺 releaseBlockedReason 键")
    if not isinstance(root.get("cost"), dict):
        failures.append("报告缺 cost 对象")
    if preflight.as_text(root.get("runId")) is None:
        failures.append("报告缺 runId——摘要无法归档")
    if preflight.as_text(root.get("label")) is None:
        failures.append("报告缺 label")
    if root.get("startedAt") is None or root.get("finishedAt") is None:
        failures.append("报告缺 startedAt/finishedAt")
    if preflight.as_int(root.get("topK")) is None:
        failures.append("报告 topK 不是整数")

    summary = root.get("summary")
    if not isinstance(summary, dict):
        failures.append("报告缺 summary 对象")
        summary = {}
    else:
        if preflight.as_int(summary.get("totalCases")) is None:
            failures.append("报告 summary.totalCases 不是整数")
        retrieval_cases = preflight.as_int(summary.get("retrievalCases"))
        if retrieval_cases is None or retrieval_cases <= 0:
            failures.append(f"报告 summary.retrievalCases 必须 >0（实际 "
                            f"{summary.get('retrievalCases')!r}）——报告没有可判定的检索分母")
        for key, label in (("retrievalErrorRate", "检索错误率"), ("fullCoverageHitRate", "全命中率")):
            if preflight.as_number(summary.get(key)) is None:
                failures.append(f"报告 summary.{key} 为 null（{label}不可判定）"
                                "——不能当作有效 120 题口径，先排查检索/覆盖再重跑")
        # 2026-11-14 事故：53/126 生成失败、0 条被评委判定的报告仍能通过旧摘要校验。
        # 报告摘要只归档“确实生成了、确实评过”的 run，否则数字没有意义。
        judged = preflight.as_int(summary.get("judgedCases"))
        failed = preflight.as_int(summary.get("failedCases"))
        total = preflight.as_int(summary.get("totalCases"))
        if judged is None or judged <= 0:
            failures.append(f"报告 summary.judgedCases={judged!r}（必须 >0）——没有一条被评委判定，"
                            "不能作为评测结论归档，先排查模型/网关再重跑")
        elif total and judged / total < 0.9:
            failures.append(f"报告 summary.judgedCases={judged}/{total}（<90%）——可评率过低，"
                            "不能作为评测结论归档，先排查生成失败再重跑")
        if failed is None:
            failures.append("报告 summary.failedCases 缺失——无法判断生成失败率")
        elif total and failed / total > 0.1:
            failures.append(f"报告 summary.failedCases={failed}/{total}（>10%）——生成失败率过高，"
                            "不能作为评测结论归档，先排查模型/网关再重跑")
        if preflight.as_number(summary.get("accuracy")) is None:
            failures.append("报告 summary.accuracy 为 null——没有可用的评委结论，不能作为评测结论归档")

    cases = root.get("cases")
    if not isinstance(cases, list):
        failures.append("报告缺 cases 数组")
        cases = []
    else:
        ids = []
        broken = False
        for index, case in enumerate(cases):
            case_id = case.get("caseId") if isinstance(case, dict) else None
            case_id = preflight.as_text(case_id) if isinstance(case_id, str) else None
            if case_id is None:
                failures.append(f"报告 cases[{index}] 缺 caseId——无法核对计数口径（不猜测）")
                broken = True
                break
            ids.append(case_id)
        if not broken:
            seen = set()
            for case_id in ids:
                if case_id in seen:
                    failures.append(f"报告 cases 出现重复 caseId: {case_id}（报告完整性存疑，不用于摘要）")
                    break
                seen.add(case_id)
            total = preflight.as_int(summary.get("totalCases"))
            if total is not None and total != len(cases):
                failures.append(f"报告 summary.totalCases={total} 与 cases 数量 {len(cases)} 不一致"
                                "——报告完整性存疑，不用于摘要")
    if failures:
        raise SummaryError("；".join(failures))
    return {"config": config, "cost": root["cost"], "summary": summary, "cases": cases,
            "case_ids": ids}


# ---------------------------------------------------------------------------
# 计数口径核对（D9）
# ---------------------------------------------------------------------------

def reconcile_counting(root, pieces, report_path) -> dict:
    """reportTotalCases / blockedExcluded / effectiveCases 三数与差集。

    数据集文件定位不到 → UsageError（退出 2，无法核对不猜测）；有效题数 != 120 →
    SummaryError（退出 1）并列出计数差集。
    """
    dataset_source = preflight.as_text(root.get("datasetSource"))
    dataset_path, how = resolve_dataset_source(dataset_source)
    if dataset_path is None:
        raise UsageError(f"无法核对计数口径：按 datasetSource={dataset_source!r} 定位不到数据集文件"
                         f"（{how}）——不猜测（D9，口径见 {REPORT_DOC}）")
    blocked_ids, counting_ids = parse_dataset_ids(dataset_path)

    report_ids = pieces["case_ids"]
    id_set = set(report_ids)
    blocked_in_report = [cid for cid in report_ids if cid in blocked_ids]
    effective_ids = [cid for cid in report_ids if cid not in blocked_ids]
    counting_set = set(counting_ids)
    unexpected = [cid for cid in effective_ids if cid not in counting_set]
    missing = [cid for cid in counting_ids if cid not in id_set]
    effective = len(effective_ids)
    report_total = len(report_ids)

    if effective != EFFECTIVE_CASES_REQUIRED:
        lines = [f"题数不符（D9 计数口径）：有效题数 {effective} ≠ {EFFECTIVE_CASES_REQUIRED}",
                 f"数据集 {dataset_path}（blocked id {len(blocked_ids)} 条，计数 id {len(counting_ids)} 条）",
                 f"报告 totalCases={report_total}；blocked 剔除 {len(blocked_in_report)} 条"
                 f"{('：' + _bounded(blocked_in_report)) if blocked_in_report else ''}"]
        if not blocked_ids:
            lines.append("数据集没有任何 status=blocked 题——报告把全部用例都计入有效口径，"
                         "与 120 不符（如实报，不猜测；见 D9 与 README-v2 语料说明）")
        lines.append("计数差集：")
        lines.append(f"- 报告里跑过、但不在数据集计数题中的 caseId（{len(unexpected)} 条）: "
                     + _bounded(unexpected))
        lines.append(f"- 数据集计数题中、报告没跑的 caseId（{len(missing)} 条）: " + _bounded(missing))
        raise SummaryError("\n".join(lines))

    return {
        "datasetFile": _rel(REPO_ROOT, dataset_path),
        "datasetSourceResolved": how,
        "reportTotalCases": report_total,
        "blockedExcluded": len(blocked_in_report),
        "effectiveCases": effective,
        "blockedCaseIds": blocked_in_report,
        "unexpectedCaseIds": unexpected,
        "missingCountingIds": missing,
        "countingNote": COUNTING_NOTE,
    }


# ---------------------------------------------------------------------------
# effective release gate（报告 + sidecar 重算；与 eval_release_preflight 同口径）
# ---------------------------------------------------------------------------

def effective_gate(root, pieces, report_path, sidecar_arg, environ, file_values) -> dict:
    """复用 task-0006 预检的纯函数合并"报告 config + sidecar agreement"，产出摘要用数据。"""
    config = pieces["config"]
    cost = pieces["cost"]
    report_file_name = report_path.name
    report_run_id = preflight.as_text(root.get("runId"))

    sidecar_doc = None
    sidecar_file_name = None
    sidecar_label = None
    agreement = None
    no_agreement_reason = None
    sidecar_run_id = None
    sidecar_report_file = None
    if sidecar_arg:
        sidecar_path = Path(sidecar_arg)
        if not sidecar_path.is_file():
            raise UsageError(f"--sidecar 指定的文件不存在: {sidecar_path}")
        sidecar_doc = _load_json(sidecar_path)
        sidecar_file_name = sidecar_path.name
        sidecar_label = f"{sidecar_path}（--sidecar 显式指定）"
    else:
        default_sidecar = preflight.sidecar_path_for(report_path)
        if default_sidecar.is_file():
            sidecar_doc = _load_json(default_sidecar)
            sidecar_file_name = default_sidecar.name
            sidecar_label = f"{default_sidecar.name}（同目录约定名，HumanReviewStore 口径）"
        else:
            agreement, source = preflight.find_agreement(root)
            if agreement is not None:
                sidecar_label = f"{source}（同目录未找到 {default_sidecar.name}，回退报告内嵌）"
            else:
                sidecar_label = f"无（同目录未找到 {default_sidecar.name}，报告也无内嵌 agreement）"
    if sidecar_doc is not None:
        agreement, source = preflight.find_agreement(sidecar_doc)
        sidecar_run_id = preflight.as_text(sidecar_doc.get("runId"))
        sidecar_report_file = preflight.as_text(sidecar_doc.get("reportFile"))
        if agreement is None:
            no_agreement_reason = "sidecar 里没有 agreement 块（人工判定还没有 import？）"
            sidecar_label = f"{sidecar_label}；{no_agreement_reason}"
        elif source:
            sidecar_label = f"{sidecar_label} → {source}"

    thresholds = preflight.resolve_thresholds(config, agreement, environ=environ,
                                              file_values=file_values)
    result = preflight.evaluate(config, cost, agreement, thresholds, report_run_id,
                                sidecar_run_id, sidecar_report_file, report_file_name,
                                no_agreement_reason)
    min_kappa, kappa_src, min_cases, cases_src = thresholds
    kappa = None
    comparable = None
    comparable_key = None
    if agreement is not None:
        kappa = preflight.as_number(agreement.get("cohensKappa"))
        comparable = preflight.as_int(agreement.get("comparableCases"))
        comparable_key = "comparableCases"
        if comparable is None:
            comparable = preflight.as_int(agreement.get("sampleSize"))
            comparable_key = "sampleSize" if comparable is not None else None
    return {
        "sidecarFile": sidecar_file_name,
        "hasSidecar": sidecar_doc is not None or agreement is not None,
        "agreementSource": sidecar_label,
        "cohensKappa": kappa,
        "comparableCases": comparable,
        "comparableCasesKey": comparable_key,
        "minKappa": min_kappa,
        "minComparableCases": int(min_cases),
        "thresholdSources": {"minKappa": kappa_src, "minComparableCases": cases_src},
        "eligible": bool(result["eligible"]),
        "codes": list(result["codes"]),
        "blockedReasons": list(result["failures"]),
    }


# ---------------------------------------------------------------------------
# 摘要构建 / 校验 / 落盘
# ---------------------------------------------------------------------------

def build_summary(root, pieces, report_path, counting, gate, generated_at) -> dict:
    config = pieces["config"]
    summary = pieces["summary"]
    cost = pieces["cost"]
    metrics = {key: summary[key] for key in METRIC_KEYS if key in summary}
    metrics["metricsNote"] = METRICS_NOTE
    doc = {
        "summarySchemaVersion": SUMMARY_SCHEMA_VERSION,
        "generatedAt": generated_at,
        "source": {
            "reportFile": report_path.name,
            "reportPath": _rel(REPO_ROOT, report_path),
            "sidecarFile": gate["sidecarFile"],
        },
        "runId": preflight.as_text(root.get("runId")),
        "label": preflight.as_text(root.get("label")),
        "datasetSource": preflight.as_text(root.get("datasetSource")),
        "topK": preflight.as_int(root.get("topK")),
        "startedAt": root.get("startedAt"),
        "finishedAt": root.get("finishedAt"),
        "models": {"generation": config.get("llm"), "judge": config.get("judge")},
        "config": {key: config[key] for key in CONFIG_KEYS if key in config},
        "counting": counting,
        "metrics": metrics,
        "releaseGate": {
            "judgeIndependence": config.get("judgeIndependence"),
            "releaseEligible": config.get("releaseEligible"),
            "releaseBlockedReason": config.get("releaseBlockedReason"),
        },
        "effectiveReleaseGate": gate,
        "cost": {
            "amount": cost.get("amount"),
            "currency": cost.get("currency"),
            "note": cost.get("note"),
        },
    }
    token_usage = root.get("tokenUsage")
    if isinstance(token_usage, dict):
        doc["cost"]["tokenUsage"] = {key: token_usage[key]
                                     for key in ("promptTokens", "completionTokens", "totalTokens")
                                     if key in token_usage}
    return doc


def _forbidden_scan(node, path="$", state=None):
    """深扫摘要文档：禁止键命中与超长字符串（疑似正文）。只记录键路径与长度，不打印内容。"""
    if state is None:
        state = {"hits": [], "max_len": 0}
    if isinstance(node, dict):
        for key, value in node.items():
            child = f"{path}.{key}"
            if isinstance(key, str) and key in FORBIDDEN_KEYS:
                state["hits"].append(f"{child}（禁止键）")
            _forbidden_scan(value, child, state)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            _forbidden_scan(value, f"{path}[{index}]", state)
    elif isinstance(node, str):
        length = len(node)
        if length > state["max_len"]:
            state["max_len"] = length
        if length > MAX_STRING_CHARS:
            state["hits"].append(f"{path}（字符串 {length} 字符 > {MAX_STRING_CHARS}，疑似正文）")
    return state


def check_summary_document(doc, file_size=None):
    """--check 与 --write 落盘前共用的摘要校验。返回错误列表（空 = 通过）。"""
    if not isinstance(doc, dict):
        return ["顶层必须是 JSON 对象——不是本脚本产出的摘要"]
    errors = []
    if doc.get("summarySchemaVersion") != SUMMARY_SCHEMA_VERSION:
        errors.append(f"不是本脚本产出的摘要（summarySchemaVersion={doc.get('summarySchemaVersion')!r}，"
                      f"本脚本只认 {SUMMARY_SCHEMA_VERSION}）")
    state = _forbidden_scan(doc)
    if state["hits"]:
        errors.append(f"摘要含正文（必须拒绝）——命中 {len(state['hits'])} 处: "
                      + "；".join(state["hits"][:5]))
    for key in REQUIRED_TOP_KEYS:
        if key not in doc:
            errors.append(f"缺必需字段 {key}")
    if errors:
        return errors

    for key in ("runId", "label", "datasetSource"):
        if preflight.as_text(doc.get(key)) is None:
            errors.append(f"{key} 必须是非空字符串")
    if preflight.as_int(doc.get("topK")) is None:
        errors.append("topK 必须是整数")
    if doc.get("startedAt") is None or doc.get("finishedAt") is None:
        errors.append("startedAt/finishedAt 不能缺")

    models = doc["models"]
    if not isinstance(models, dict) or "generation" not in models or "judge" not in models:
        errors.append("models 必须含 generation / judge 键（generation= config.llm，"
                      "judge= config.judge；报告该值为 null 时如实记 null，不编造）")
    else:
        for key in ("generation", "judge"):
            value = models.get(key)
            if value is not None and preflight.as_text(value) is None:
                errors.append(f"models.{key} 必须是非空字符串或 null（如实引用报告，不编造模型名）")
    if not isinstance(doc["config"], dict) or not doc["config"]:
        errors.append("config 关键项块必须是非空对象")

    counting = doc["counting"]
    if not isinstance(counting, dict):
        errors.append("counting 块缺失")
        counting = {}
    else:
        report_total = preflight.as_int(counting.get("reportTotalCases"))
        blocked = preflight.as_int(counting.get("blockedExcluded"))
        effective = preflight.as_int(counting.get("effectiveCases"))
        if report_total is None or report_total < 0 or blocked is None or blocked < 0:
            errors.append("counting.reportTotalCases / blockedExcluded 必须是非负整数")
        elif report_total != blocked + effective:
            errors.append(f"counting 三数不自洽：reportTotalCases={report_total} != "
                          f"blockedExcluded={blocked} + effectiveCases={effective}")
        if effective != EFFECTIVE_CASES_REQUIRED:
            errors.append(f"counting.effectiveCases={effective} ≠ {EFFECTIVE_CASES_REQUIRED}"
                          "（D9 计数口径：blocked 题不进计数）")
        blocked_ids = counting.get("blockedCaseIds")
        if not isinstance(blocked_ids, list) or any(not isinstance(item, str) for item in blocked_ids):
            errors.append("counting.blockedCaseIds 必须是字符串数组（被剔除的 blocked caseId 清单）")
        if preflight.as_text(counting.get("datasetFile")) is None:
            errors.append("counting.datasetFile 必须是非空字符串（数据集文件定位结果）")

    metrics = doc["metrics"]
    if not isinstance(metrics, dict):
        errors.append("metrics 块缺失")
        metrics = {}
    else:
        for key in ("retrievalErrorRate", "fullCoverageHitRate"):
            if preflight.as_number(metrics.get(key)) is None:
                errors.append(f"metrics.{key} 缺失或为 null（有效口径必须可判定）")
        if "totalCases" in metrics and preflight.as_int(metrics["totalCases"]) is not None \
                and metrics["totalCases"] != counting.get("reportTotalCases"):
            errors.append("metrics.totalCases 与 counting.reportTotalCases 不一致")

    gate = doc["releaseGate"]
    if not isinstance(gate, dict) or preflight.as_text(gate.get("judgeIndependence")) is None \
            or not isinstance(gate.get("releaseEligible"), bool) \
            or "releaseBlockedReason" not in gate:
        errors.append("releaseGate 必须含 judgeIndependence / releaseEligible / releaseBlockedReason"
                      "（报告原始冻结值）")
    else:
        if gate["releaseEligible"] is False and not gate.get("releaseBlockedReason"):
            errors.append("releaseGate 不自洽：releaseEligible=false 但 releaseBlockedReason 为空")
        if gate["releaseEligible"] is True and gate.get("releaseBlockedReason"):
            errors.append("releaseGate 不自洽：releaseEligible=true 却带 releaseBlockedReason")

    effective_block = doc["effectiveReleaseGate"]
    if not isinstance(effective_block, dict) \
            or not isinstance(effective_block.get("eligible"), bool) \
            or not isinstance(effective_block.get("codes"), list) \
            or not isinstance(effective_block.get("hasSidecar"), bool):
        errors.append("effectiveReleaseGate 必须含 eligible / hasSidecar / codes（报告+sidecar 重算结论）")
    else:
        reasons = effective_block.get("blockedReasons")
        if effective_block["eligible"] is False and (not isinstance(reasons, list) or not reasons):
            errors.append("effectiveReleaseGate 不自洽：eligible=false 但没有 blockedReasons")
        if effective_block["eligible"] is True and isinstance(reasons, list) and reasons:
            errors.append("effectiveReleaseGate 不自洽：eligible=true 却带 blockedReasons")

    cost = doc["cost"]
    if not isinstance(cost, dict) or "amount" not in cost:
        errors.append("cost 块必须含 amount（null=未配价，如实记录）")

    source = doc["source"]
    if not isinstance(source, dict) or preflight.as_text(source.get("reportFile")) is None:
        errors.append("source.reportFile 必须是非空字符串（摘要溯源）")

    if file_size is not None and file_size >= MAX_SUMMARY_BYTES:
        errors.append(f"摘要 {file_size} bytes ≥ 30KB 上限——摘要必须是压缩口径，"
                      "检查是否误放了正文")
    return errors


def summary_bytes(doc) -> bytes:
    return (json.dumps(doc, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


# ---------------------------------------------------------------------------
# 运行入口：--write / --check
# ---------------------------------------------------------------------------

def latest_report(report_dir: Path) -> Path:
    if not report_dir.is_dir():
        raise UsageError(f"报告目录不存在: {report_dir}")
    candidates = [p for p in report_dir.glob("*.json")
                  if not p.name.endswith(preflight.SIDECAR_SUFFIX)]
    if not candidates:
        raise UsageError(f"{report_dir} 下没有报告 JSON（先跑评测；或用 --report 显式指定）")
    return max(candidates, key=lambda p: p.stat().st_mtime)


def run_write(write_path, report_arg=None, report_dir=None, sidecar_arg=None,
              env_file_arg=None, environ=None, out=None) -> int:
    """校验报告并落盘摘要。返回退出码（0/1/2）；输出写到 out（None = stdout）。"""
    environ = os.environ if environ is None else environ
    emit = out.write if out is not None else sys.stdout.write

    target = Path(write_path)
    report_path = Path(report_arg) if report_arg \
        else latest_report(Path(report_dir) if report_dir else DEFAULT_REPORT_DIR)
    if not report_path.is_file():
        raise UsageError(f"报告文件不存在: {report_path}")
    if target.resolve() == report_path.resolve():
        raise UsageError(f"--write 目标 {target} 与报告文件相同——报告文件冻结不改，换个路径")

    root = preflight.unwrap_data(_load_json(report_path))
    pieces = validate_report(root)                                   # 退出 1：结构/口径字段
    counting = reconcile_counting(root, pieces, report_path)         # 退出 2：定位不到；退出 1：题数不符

    env_file = env_file_arg or environ.get("EVAL_ENV_FILE") or str(REPO_ROOT / ".env")
    if env_file_arg and not Path(env_file).is_file():
        raise UsageError(f"--env-file 指定的文件不存在: {env_file}")
    file_values = preflight.read_env_file(env_file)
    gate = effective_gate(root, pieces, report_path, sidecar_arg, environ, file_values)
    if gate["sidecarFile"]:
        sidecar_resolved = (report_path.parent / gate["sidecarFile"]).resolve()
        if target.resolve() == sidecar_resolved:
            raise UsageError(f"--write 目标 {target} 与 sidecar {gate['sidecarFile']} 相同——"
                             "人工判定文件冻结不改，换个路径")

    generated_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    doc = build_summary(root, pieces, report_path, counting, gate, generated_at)
    data = summary_bytes(doc)
    errors = check_summary_document(doc, file_size=len(data))
    if errors:
        raise SummaryError("落盘前自检未过（摘要与 --check 同一套校验，不该失败）：\n- "
                           + "\n- ".join(errors))
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(target.name + ".tmp")
    tmp.write_bytes(data)
    tmp.replace(target)

    summary = pieces["summary"]
    config = pieces["config"]
    kappa_text = "未计算" if gate["cohensKappa"] is None else f"{gate['cohensKappa']:.3f}"
    comparable_text = "未知" if gate["comparableCases"] is None else str(gate["comparableCases"])
    kappa_threshold = "停用" if gate["minKappa"] is None else format(gate["minKappa"], "g")
    lines = [
        "== v2 报告校验与压缩摘要（task-0007；计数口径 D9）==",
        f"报告: {_rel(REPO_ROOT, report_path)}（runId={root.get('runId')}，schemaVersion=2.0）",
        f"数据集: datasetSource={root.get('datasetSource')!r} → {counting['datasetFile']}"
        f"（{counting['datasetSourceResolved']}）",
        f"计数口径: 报告 totalCases={counting['reportTotalCases']}；"
        f"blocked 剔除 {counting['blockedExcluded']} 条"
        f"{('：' + _bounded(counting['blockedCaseIds'])) if counting['blockedCaseIds'] else ''}；"
        f"有效题数 {counting['effectiveCases']} ✓（== {EFFECTIVE_CASES_REQUIRED}）",
    ]
    if counting["unexpectedCaseIds"] or counting["missingCountingIds"]:
        lines.append(f"警告: 计数差集非空（数据集计数题与报告用例不完全一致）——"
                     f"多出 {len(counting['unexpectedCaseIds'])} 条、缺失 "
                     f"{len(counting['missingCountingIds'])} 条，明细已记录进摘要 counting 块")
    lines += [
        f"指标（原样引用报告）: retrievalCases={summary.get('retrievalCases')}；"
        f"retrievalErrorRate={summary.get('retrievalErrorRate')}；"
        f"fullCoverageHitRate={summary.get('fullCoverageHitRate')}；"
        f"accuracy={summary.get('accuracy')}",
        f"release gate（报告原始冻结）: judgeIndependence={config.get('judgeIndependence')}；"
        f"releaseEligible={str(config.get('releaseEligible')).lower()}；"
        f"原因: {config.get('releaseBlockedReason') or '（无——报告放行）'}",
        f"effective release gate（报告+sidecar 重算，eval_release_preflight 同口径）: "
        f"sidecar={gate['sidecarFile'] or '无'}；κ={kappa_text}（可比 {comparable_text} 条）；"
        f"阈值 κ≥{kappa_threshold}/样本≥{gate['minComparableCases']}；"
        f"eligible={str(gate['eligible']).lower()}",
    ]
    lines += [f"  - {message}" for message in gate["blockedReasons"]]
    lines += [
        f"摘要: {target}（{len(data)} bytes < 30KB）——不含 question/answer/retrievedText，"
        f"无正文泄漏（禁止键 0 命中，最长字符串 {_forbidden_scan(doc)['max_len']} ≤ {MAX_STRING_CHARS}）",
    ]
    emit("\n".join(lines) + "\n")
    return 0


def run_check(check_path, out=None) -> int:
    """校验已有摘要文件（自包含：不要求报告/数据集还在）。返回退出码。"""
    emit = out.write if out is not None else sys.stdout.write
    path = Path(check_path)
    if not path.is_file():
        raise UsageError(f"摘要文件不存在: {path}")
    data = path.read_bytes()
    try:
        doc = json.loads(data.decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise UsageError(f"不是合法 JSON 摘要: {path}: {exc}") from None
    errors = check_summary_document(doc, file_size=len(data))
    if errors:
        emit(f"!! 摘要校验失败（{len(errors)} 项）: {path}\n")
        for error in errors:
            emit(f"  - {error}\n")
        return 1
    counting = doc["counting"]
    gate = doc["releaseGate"]
    effective = doc["effectiveReleaseGate"]
    state = _forbidden_scan(doc)
    emit("== 摘要校验（--check）==")
    emit(f"摘要: {path}（{len(data)} bytes < 30KB；summarySchemaVersion={doc['summarySchemaVersion']}）")
    emit(f"runId={doc.get('runId')}；label={doc.get('label')!r}；generatedAt={doc.get('generatedAt')}")
    emit(f"计数口径（D9）: effectiveCases={counting['effectiveCases']} ✓"
         f"（reportTotalCases={counting['reportTotalCases']} − blockedExcluded="
         f"{counting['blockedExcluded']}；数据集 {counting.get('datasetFile')}）")
    emit(f"指标（原样引用报告）: retrievalErrorRate={doc['metrics'].get('retrievalErrorRate')}；"
         f"fullCoverageHitRate={doc['metrics'].get('fullCoverageHitRate')}")
    emit(f"报告原始 release gate: judgeIndependence={gate.get('judgeIndependence')}；"
         f"releaseEligible={str(gate.get('releaseEligible')).lower()}")
    emit(f"effective release gate: eligible={str(effective.get('eligible')).lower()}；"
         f"κ={effective.get('cohensKappa')}；codes={effective.get('codes') or '无'}")
    emit(f"无正文泄漏（禁止键 0 命中，最长字符串 {state['max_len']} ≤ {MAX_STRING_CHARS}）")
    emit("check PASS")
    return 0


# ---------------------------------------------------------------------------
# --self-test：内置 fixture，离线、不联网、不读宿主环境、不读真实 .env 与真实报告
# ---------------------------------------------------------------------------

RUN_ID = "0f1e2d3c4b5a6"
REPORT_NAME = "report-fix-1.json"
SECRET_MARKER = "SK-DO-NOT-PRINT-42"
BLOCKED_IDS = ["ocr-07-blocks", "ocr-07-logical-address", "ocr-07-physical-address",
               "ocr-07-io-exchange", "ocr-07-inode", "ocr-07-cluster"]


def _counting_ids(count=120):
    return [f"fact-01-{i:03d}" for i in range(1, count + 1)]


def _para_ids(count=6):
    return [f"para-02-{i:03d}" for i in range(1, count + 1)]


def _fixture_dataset(path: Path, counting, blocked):
    rows = [{"id": cid, "question": f"Q-{cid}", "referenceAnswer": f"A-{cid}",
             "split": "dev", "type": "fact", "difficulty": 1, "expectedRefusal": False,
             "expectedFileNames": ["01-clip-radford-2021.pdf"], "expectedEvidence": ["anchor phrase"]}
            for cid in counting]
    rows += [{"id": cid, "question": f"Q-{cid}", "referenceAnswer": f"A-{cid}",
              "split": "dev", "type": "ocr", "difficulty": 3, "expectedRefusal": False,
              "expectedFileNames": ["07-os-filesystem-ppt-zh.pdf"],
              "expectedEvidence": ["scan fragment"], "ocr": True, "status": "blocked"}
             for cid in blocked]
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
                    encoding="utf-8")
    return path


def _fixture_report(case_ids, dataset_source, *, schema_version="2.0", coverage=0.75,
                    err_rate=0.0, retrieval_cases=None, total=None, judged_cases=None,
                    failed_cases=1, independence="SELF_JUDGE",
                    judge="deepseek/deepseek-chat", llm="deepseek/deepseek-chat",
                    eligible=False, reason="评委与生成共用同一模型（SELF_JUDGE），自评分数不能用于发布判定",
                    cost_amount=None, config_extra=None):
    cases = [{"caseId": cid} for cid in case_ids]
    summary = {
        "totalCases": len(cases) if total is None else total,
        "labeledCases": len(cases) - 1,
        "retrievalCases": len(cases) - 1 if retrieval_cases is None else retrieval_cases,
        "negExcludedCases": 1,
        "retrievalHits": len(cases) - 2,
        "recallAtK": 0.8,
        "mrr": 0.7,
        "docRecallAtK": 0.8,
        "evidenceLabeledCases": len(cases) - 1,
        "meanExpectedDocCoverage": 0.6,
        "judgedCases": len(cases) - 1 if judged_cases is None else judged_cases,
        "correctCases": len(cases) - 3,
        "meanJudgeScore": 0.82,
        "accuracy": 0.8,
        "needsHumanReviewCount": 2,
        "failedCases": failed_cases,
        "retrievalModeCounts": {"HYBRID": len(cases)},
        "diagnosisCounts": {"NONE": len(cases) - 1, "RETRIEVAL_FAILED": 1},
        "ndcgAtK": {"3": 0.7, "5": 0.7, "10": 0.7},
        "fullCoverageHitRate": coverage,
        "retrievalErrorRate": err_rate,
        "refusalLabeledCases": 1, "refusalCorrectCases": 0, "refusalAccuracy": 0.0,
        "refusalExpectedCases": 1, "refusedAsExpected": 0,
        "faithfulness": 0.9, "citationPrecision": 0.8, "citationRecall": 0.5,
        "judgeRepeatEnabled": False, "judgeAgreementCases": None,
        "judgeDisagreementCases": None, "judgeAgreementRate": None,
    }
    config = {
        "topK": 5, "mode": "react", "llm": llm, "judge": judge,
        "judgeIndependence": independence, "releaseEligible": eligible,
        "releaseBlockedReason": reason, "kappa": None,
        "kappaNote": "kappa 未计算：无人工复核数据，不伪造 κ",
        "rrfK": 60, "rerankEnabled": False, "rerankModel": None, "rerankScoreThreshold": None,
        "parentContextEnabled": True, "parentContextNeighbourChunks": 1,
        "parentContextMaxCharsPerHit": 1200,
        "pricingInputPer1k": None, "pricingOutputPer1k": None, "pricingCurrency": None,
    }
    if config_extra:
        config.update(config_extra)
    cost = {"amount": cost_amount, "currency": "CNY" if cost_amount is not None else None,
            "note": "priced" if cost_amount is not None else "unpriced: set eval.pricing.*"}
    return {
        "runId": RUN_ID, "label": "summary-self-test", "datasetSource": dataset_source,
        "topK": 5, "startedAt": 1794505815.0, "finishedAt": 1794505816.0,
        "schemaVersion": schema_version, "config": config, "cost": cost,
        "summary": summary, "cases": cases,
    }


def _fixture_sidecar(kappa=0.71, comparable=6, run_id=RUN_ID, report_file=REPORT_NAME):
    return {"runId": run_id, "reportFile": report_file, "updatedAt": "2026-11-13T00:00:00Z",
            "reviews": {},
            "agreement": {"status": "OK", "comparableCases": comparable, "cohensKappa": kappa,
                          "minKappa": 0.6, "minComparableCases": 5, "disagreementCaseIds": []}}


def _write_json(path: Path, obj) -> Path:
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def _run_write(environ, **kwargs):
    buffer = io.StringIO()
    try:
        code = run_write(environ=environ, out=buffer, **kwargs)
    except SummaryError as error:
        buffer.write(f"SummaryError: {error}\n")
        code = 1
    except UsageError as error:
        buffer.write(f"UsageError: {error}\n")
        code = 2
    return code, buffer.getvalue()


def _run_check(environ, **kwargs):
    buffer = io.StringIO()
    try:
        code = run_check(out=buffer, **kwargs)
    except SummaryError as error:
        buffer.write(f"SummaryError: {error}\n")
        code = 1
    except UsageError as error:
        buffer.write(f"UsageError: {error}\n")
        code = 2
    return code, buffer.getvalue()


def self_test() -> int:
    clean_env = {}   # 与宿主隔离：所有 run_* 的 environ 都经参数注入，不读真实 .env
    with tempfile.TemporaryDirectory(prefix="eval-report-summary-selftest-") as directory:
        room = Path(directory)
        dataset = _fixture_dataset(room / "eval-v2-fixture.jsonl", _counting_ids(), BLOCKED_IDS)
        dataset_source = str(dataset)
        empty_env = room / "empty.env"
        empty_env.write_text("", encoding="utf-8")
        out_dir = room / "out"
        all_ids = _counting_ids() + BLOCKED_IDS

        def write_report(name, report):
            path = _write_json(room / name, report)
            return path, path.read_bytes()

        # ── fixture 1：126 题（120 计数 + 6 blocked 全被执行）→ 计数口径通过；
        #    sidecar κ 达标 + INDEPENDENT + 已配价 → effective gate 翻盘为 true，冻结 false 原样保留 ──
        report1, before1 = write_report(REPORT_NAME, _fixture_report(
            all_ids, dataset_source, independence="INDEPENDENT",
            judge="siliconflow/Qwen2.5-72B-Instruct", eligible=False,
            reason="评委与生成共用同一模型（SELF_JUDGE），自评分数不能用于发布判定",
            cost_amount=1.23))
        _write_json(preflight.sidecar_path_for(report1), _fixture_sidecar())
        target1 = out_dir / "summary1.json"
        code, text = _run_write(clean_env, write_path=str(target1), report_arg=str(report1),
                                env_file_arg=str(empty_env))
        assert code == 0, (code, text)
        assert report1.read_bytes() == before1, "报告文件被改动——摘要脚本必须只读"
        doc1 = json.loads(target1.read_text(encoding="utf-8"))
        assert doc1["counting"]["reportTotalCases"] == 126, doc1["counting"]
        assert doc1["counting"]["blockedExcluded"] == 6, doc1["counting"]
        assert doc1["counting"]["effectiveCases"] == 120, doc1["counting"]
        assert sorted(doc1["counting"]["blockedCaseIds"]) == sorted(BLOCKED_IDS)
        assert doc1["counting"]["unexpectedCaseIds"] == [] and doc1["counting"]["missingCountingIds"] == []
        assert doc1["counting"]["datasetFile"].endswith("eval-v2-fixture.jsonl")
        assert doc1["releaseGate"]["releaseEligible"] is False          # 冻结值原样呈现
        assert doc1["releaseGate"]["releaseBlockedReason"]
        assert doc1["effectiveReleaseGate"]["eligible"] is True         # sidecar κ 达标翻盘
        assert doc1["effectiveReleaseGate"]["cohensKappa"] == 0.71
        assert doc1["effectiveReleaseGate"]["minKappa"] == 0.6
        assert doc1["effectiveReleaseGate"]["minComparableCases"] == 5
        assert doc1["effectiveReleaseGate"]["sidecarFile"] == "report-fix-1.human-reviews.json"
        assert doc1["effectiveReleaseGate"]["hasSidecar"] is True
        assert doc1["effectiveReleaseGate"]["codes"] == []
        assert doc1["metrics"]["retrievalErrorRate"] == 0.0
        assert doc1["metrics"]["fullCoverageHitRate"] == 0.75
        assert doc1["metrics"]["totalCases"] == 126 == doc1["counting"]["reportTotalCases"]
        assert doc1["models"]["generation"] == "deepseek/deepseek-chat"
        assert doc1["models"]["judge"] == "siliconflow/Qwen2.5-72B-Instruct"
        assert doc1["config"]["rrfK"] == 60 and doc1["config"]["parentContextEnabled"] is True
        assert doc1["config"]["judge"] == "siliconflow/Qwen2.5-72B-Instruct"
        assert doc1["cost"]["amount"] == 1.23
        assert doc1["source"]["reportFile"] == REPORT_NAME
        assert doc1["summarySchemaVersion"] == SUMMARY_SCHEMA_VERSION
        raw = target1.read_bytes()
        assert len(raw) < MAX_SUMMARY_BYTES, len(raw)
        assert b'"question"' not in raw and b'"answer"' not in raw and b'"retrievedText"' not in raw
        assert b"Q-fact-01-0001" not in raw and b"anchor phrase" not in raw, "正文/数据集内容不得进摘要"
        assert SECRET_MARKER.encode() not in raw and SECRET_MARKER not in text
        code, text = _run_check(clean_env, check_path=str(target1))
        assert code == 0, (code, text)
        assert "effectiveCases=120" in text and "check PASS" in text
        assert "blockedExcluded=6" in text

        # ── fixture 2：上游已过滤（blocked 没跑）——报告正好 120 题 → 同样通过，blockedExcluded=0；
        #    报告冻结 releaseEligible=true + 无 sidecar → effective gate false（无人工判定）如实记录 ──
        report2, _ = write_report("report-fix-2.json", _fixture_report(
            _counting_ids(), dataset_source, independence="INDEPENDENT", cost_amount=1.0,
            judge="siliconflow/Qwen2.5-72B-Instruct", eligible=True, reason=None))
        target2 = out_dir / "summary2.json"
        code, text = _run_write(clean_env, write_path=str(target2), report_arg=str(report2),
                                env_file_arg=str(empty_env))
        assert code == 0, (code, text)
        doc2 = json.loads(target2.read_text(encoding="utf-8"))
        assert doc2["counting"]["effectiveCases"] == 120 and doc2["counting"]["blockedExcluded"] == 0
        assert doc2["counting"]["blockedCaseIds"] == []
        assert doc2["releaseGate"]["releaseEligible"] is True
        assert doc2["releaseGate"]["releaseBlockedReason"] is None
        assert doc2["effectiveReleaseGate"]["eligible"] is False        # 无 sidecar → 人工一致性未过
        assert preflight.R_NO_SIDE in doc2["effectiveReleaseGate"]["codes"]
        assert doc2["effectiveReleaseGate"]["blockedReasons"]
        assert doc2["effectiveReleaseGate"]["hasSidecar"] is False

        # ── fixture 3：题数不符（20 题预热报告）→ 退出 1，差集列出缺失计数题，不落盘 ──
        report3, _ = write_report("report-fix-3.json", _fixture_report(_counting_ids(20), dataset_source))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary3.json"),
                                report_arg=str(report3), env_file_arg=str(empty_env))
        assert code == 1, (code, text)
        assert "题数不符" in text and "有效题数 20 ≠ 120" in text, text
        assert "报告没跑的 caseId（100 条）" in text, text
        assert not (out_dir / "summary3.json").exists(), "校验失败不得落盘"

        # ── fixture 4：blocked 剔除后 ≠120（5 条 blocked + 1 条多出来的 phantom）→ 退出 1 并列差集 ──
        report4, _ = write_report("report-fix-4.json", _fixture_report(
            _counting_ids() + BLOCKED_IDS[:5] + ["phantom-extra-case"], dataset_source))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary4.json"),
                                report_arg=str(report4), env_file_arg=str(empty_env))
        assert code == 1, (code, text)
        assert "有效题数 121 ≠ 120" in text, text
        assert "phantom-extra-case" in text, text
        assert not (out_dir / "summary4.json").exists()

        # ── fixture 5：数据集没有 blocked 题 → 126 全算有效，与 120 不符如实报 ──
        dataset_nb = _fixture_dataset(room / "eval-v2-noblocked.jsonl",
                                      _counting_ids() + _para_ids(), [])
        report5, _ = write_report("report-fix-5.json", _fixture_report(
            _counting_ids() + _para_ids(), str(dataset_nb)))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary5.json"),
                                report_arg=str(report5), env_file_arg=str(empty_env))
        assert code == 1 and "有效题数 126 ≠ 120" in text and "没有任何 status=blocked" in text, (code, text)

        # ── fixture 6：覆盖率/错误率 null、retrievalCases=0 → 退出 1，不当作可判定口径 ──
        report6, _ = write_report("report-fix-6.json", _fixture_report(all_ids, dataset_source, coverage=None))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary6.json"),
                                report_arg=str(report6), env_file_arg=str(empty_env))
        assert code == 1 and "fullCoverageHitRate 为 null" in text, (code, text)
        report6b, _ = write_report("report-fix-6b.json", _fixture_report(all_ids, dataset_source, err_rate=None))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary6b.json"),
                                report_arg=str(report6b), env_file_arg=str(empty_env))
        assert code == 1 and "retrievalErrorRate 为 null" in text, (code, text)
        report6c, _ = write_report("report-fix-6c.json", _fixture_report(all_ids, dataset_source, retrieval_cases=0))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary6c.json"),
                                report_arg=str(report6c), env_file_arg=str(empty_env))
        assert code == 1 and "retrievalCases 必须 >0" in text, (code, text)

        # ── fixture 7：缺 schemaVersion / schemaVersion 不是 2.0 → "不是 v2 报告"，不猜测 ──
        broken = _fixture_report(all_ids, dataset_source)
        broken.pop("schemaVersion")
        report7, _ = write_report("report-fix-7.json", broken)
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary7.json"),
                                report_arg=str(report7), env_file_arg=str(empty_env))
        assert code == 1 and "不是 v2 报告" in text and "schemaVersion" in text, (code, text)
        report7b, _ = write_report("report-fix-7b.json", _fixture_report(all_ids, dataset_source,
                                                                         schema_version="1.0"))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary7b.json"),
                                report_arg=str(report7b), env_file_arg=str(empty_env))
        assert code == 1 and "不是 v2 报告" in text and "'1.0'" in text, (code, text)

        # ── fixture 8：gate 键缺失/类型错、totalCases 与 cases 数不一致、缺 caseId、重复 caseId → 退出 1 ──
        report8, _ = write_report("report-fix-8.json", _fixture_report(
            all_ids, dataset_source, config_extra={"judgeIndependence": None}))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary8.json"),
                                report_arg=str(report8), env_file_arg=str(empty_env))
        assert code == 1 and "judgeIndependence" in text, (code, text)
        report8b, _ = write_report("report-fix-8b.json", _fixture_report(
            all_ids, dataset_source, config_extra={"releaseEligible": "false"}))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary8b.json"),
                                report_arg=str(report8b), env_file_arg=str(empty_env))
        assert code == 1 and "releaseEligible 不是布尔值" in text, (code, text)
        report8c = _fixture_report(all_ids, dataset_source)
        del report8c["config"]["releaseBlockedReason"]   # 整个键缺失（不只是 null）
        _write_json(room / "report-fix-8c.json", report8c)
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary8c.json"),
                                report_arg=str(room / "report-fix-8c.json"), env_file_arg=str(empty_env))
        assert code == 1 and "缺 releaseBlockedReason 键" in text, (code, text)
        # releaseBlockedReason=null 且 releaseEligible=false（后端不可能产出的不自洽形态）→ 落盘前自检拒绝
        report8g, _ = write_report("report-fix-8g.json", _fixture_report(
            all_ids, dataset_source, config_extra={"releaseBlockedReason": None}))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary8g.json"),
                                report_arg=str(report8g), env_file_arg=str(empty_env))
        assert code == 1 and "releaseGate 不自洽" in text, (code, text)
        report8d, _ = write_report("report-fix-8d.json", _fixture_report(
            all_ids, dataset_source, total=125))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary8d.json"),
                                report_arg=str(report8d), env_file_arg=str(empty_env))
        assert code == 1 and "与 cases 数量 126 不一致" in text, (code, text)
        no_id = _fixture_report(all_ids, dataset_source)
        no_id["cases"][3] = {"question": "broken case"}   # fixture 报告可含正文，摘要不行
        report8e, _ = write_report("report-fix-8e.json", no_id)
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary8e.json"),
                                report_arg=str(report8e), env_file_arg=str(empty_env))
        assert code == 1 and "缺 caseId" in text, (code, text)
        dup = _fixture_report(all_ids, dataset_source)
        dup["cases"][5]["caseId"] = dup["cases"][4]["caseId"]
        report8f, _ = write_report("report-fix-8f.json", dup)
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary8f.json"),
                                report_arg=str(report8f), env_file_arg=str(empty_env))
        assert code == 1 and "重复 caseId" in text, (code, text)

        # ── fixture 8h：生成失败率过高 / 无评委结论 → 退出 1（2026-11-14 事故：53/126 失败仍可通过旧校验） ──
        report8h, _ = write_report("report-fix-8h.json", _fixture_report(
            all_ids, dataset_source, judged_cases=0, failed_cases=53))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary8h.json"),
                                report_arg=str(report8h), env_file_arg=str(empty_env))
        assert code == 1 and "judgedCases" in text and "failedCases" in text, (code, text)

        # ── fixture 9：datasetSource 无法定位（upload: 形态）→ 退出 2 "无法核对计数口径"，不猜 ──
        report9, _ = write_report("report-fix-9.json", _fixture_report(all_ids, "upload:eval-v2.jsonl"))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary9.json"),
                                report_arg=str(report9), env_file_arg=str(empty_env))
        assert code == 2 and "无法核对计数口径" in text, (code, text)
        report9b, _ = write_report("report-fix-9b.json", _fixture_report(all_ids, "inline"))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary9b.json"),
                                report_arg=str(report9b), env_file_arg=str(empty_env))
        assert code == 2 and "无法核对计数口径" in text, (code, text)
        report9c, _ = write_report("report-fix-9c.json", _fixture_report(all_ids, "no-such-dataset.jsonl"))
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary9c.json"),
                                report_arg=str(report9c), env_file_arg=str(empty_env))
        assert code == 2 and "无法核对计数口径" in text and "no-such-dataset.jsonl" in text, (code, text)

        # ── fixture 10：--write 目标与报告/sidecar 冲突 → 退出 2（报告与人工判定文件冻结不改）──
        code, text = _run_write(clean_env, write_path=str(report1),
                                report_arg=str(report1), env_file_arg=str(empty_env))
        assert code == 2 and "报告文件冻结不改" in text, (code, text)
        code, text = _run_write(clean_env, write_path=str(preflight.sidecar_path_for(report1)),
                                report_arg=str(report1), env_file_arg=str(empty_env))
        assert code == 2 and "sidecar" in text and "冻结不改" in text, (code, text)

        # ── fixture 11：报告缺文件 / 非法 JSON / --sidecar、--env-file 指定文件不存在 → 退出 2 ──
        code, text = _run_write(clean_env, write_path=str(out_dir / "s.json"),
                                report_arg=str(room / "ghost.json"), env_file_arg=str(empty_env))
        assert code == 2 and "报告文件不存在" in text, (code, text)
        bad = room / "bad.json"
        bad.write_text("{not json", encoding="utf-8")
        code, text = _run_write(clean_env, write_path=str(out_dir / "s.json"),
                                report_arg=str(bad), env_file_arg=str(empty_env))
        assert code == 2 and "不是合法 JSON" in text, (code, text)
        code, text = _run_write(clean_env, write_path=str(out_dir / "s.json"),
                                report_arg=str(report1), sidecar_arg=str(room / "ghost.human-reviews.json"),
                                env_file_arg=str(empty_env))
        assert code == 2 and "--sidecar 指定的文件不存在" in text, (code, text)
        code, text = _run_write(clean_env, write_path=str(out_dir / "s.json"),
                                report_arg=str(report1), env_file_arg=str(room / "no-such.env"))
        assert code == 2 and "--env-file 指定的文件不存在" in text, (code, text)

        # ── fixture 12：sidecar runId 绑定（跨轮 sidecar 判 FAIL）与内嵌 agreement 回退 ──
        _write_json(preflight.sidecar_path_for(report1),
                    _fixture_sidecar(run_id="other-run"))
        target12 = out_dir / "summary12.json"
        code, text = _run_write(clean_env, write_path=str(target12), report_arg=str(report1),
                                env_file_arg=str(empty_env))
        assert code == 0, (code, text)   # 落盘不受 effective gate 影响，结论如实记录
        doc12 = json.loads(target12.read_text(encoding="utf-8"))
        assert doc12["effectiveReleaseGate"]["eligible"] is False
        assert preflight.R_RUNID in doc12["effectiveReleaseGate"]["codes"]
        embedded = json.loads(json.dumps(_fixture_report(
            all_ids, dataset_source, independence="INDEPENDENT",
            judge="siliconflow/Qwen2.5-72B-Instruct", cost_amount=1.0)))
        embedded.pop("config")["config"] if False else None
        embedded["config"] = json.loads(json.dumps(embedded["config"]))
        embedded["humanReview"] = {"reviews": [], "agreement": {
            "comparableCases": 9, "cohensKappa": 0.8, "minKappa": 0.6, "minComparableCases": 5}}
        report12b, _ = write_report("report-fix-12b.json", embedded)
        target12b = out_dir / "summary12b.json"
        code, text = _run_write(clean_env, write_path=str(target12b), report_arg=str(report12b),
                                env_file_arg=str(empty_env))
        assert code == 0, (code, text)
        doc12b = json.loads(target12b.read_text(encoding="utf-8"))
        assert doc12b["effectiveReleaseGate"]["eligible"] is True
        assert "报告内嵌" in doc12b["effectiveReleaseGate"]["agreementSource"]
        assert doc12b["effectiveReleaseGate"]["sidecarFile"] is None
        assert doc12b["effectiveReleaseGate"]["hasSidecar"] is True

        # ── fixture 13：自动选最新报告；sidecar 与旧报告即使更新也不被当成报告；预热报告被拒 ──
        report_dir = room / "eval-reports"
        report_dir.mkdir()
        old = _write_json(report_dir / "20261113-010000_warmup-20_aaa.json",
                          _fixture_report(_counting_ids(20), dataset_source))
        newer = _write_json(report_dir / "20261113-020000_full-126_bbb.json", _fixture_report(
            all_ids, dataset_source, independence="INDEPENDENT",
            judge="siliconflow/Qwen2.5-72B-Instruct", cost_amount=1.0))
        sidecar_noise = _write_json(report_dir / "20261113-020000_full-126_bbb.human-reviews.json",
                                    _fixture_sidecar())
        os.utime(old, (1_000_000, 1_000_000))
        os.utime(sidecar_noise, (3_000_000, 3_000_000))   # 比报告更新，也不能被当成报告选中
        os.utime(newer, (2_000_000, 2_000_000))
        target13 = out_dir / "summary13.json"
        code, text = _run_write(clean_env, write_path=str(target13), report_dir=str(report_dir),
                                env_file_arg=str(empty_env))
        assert code == 0, (code, text)
        assert "full-126_bbb.json" in text and "warmup-20" not in text, text
        assert json.loads(target13.read_text(encoding="utf-8"))["counting"]["effectiveCases"] == 120
        # 只剩预热报告时：有效题数 20 ≠ 120 → 拒归档（防止把预热轮当正式轮）
        newer.unlink()
        sidecar_noise.unlink()
        code, text = _run_write(clean_env, write_path=str(out_dir / "summary13b.json"),
                                report_dir=str(report_dir), env_file_arg=str(empty_env))
        assert code == 1 and "有效题数 20 ≠ 120" in text, (code, text)

        # ── fixture 14：--check 失败矩阵（含正文注入 / 超长字符串 / 版本不符 / 三数不自洽 / 超限）──
        tampered = json.loads(target1.read_text(encoding="utf-8"))
        tampered["question"] = "一份完整回答正文……"      # 禁止键注入
        bad_path = _write_json(room / "tampered.json", tampered)
        code, text = _run_check(clean_env, check_path=str(bad_path))
        assert code == 1 and "摘要含正文（必须拒绝）" in text and ".question" in text, (code, text)
        tampered2 = json.loads(target1.read_text(encoding="utf-8"))
        tampered2["label"] = "正文" + "很长的回答正文" * 80   # >500 字符 → 疑似正文
        bad_path2 = _write_json(room / "tampered2.json", tampered2)
        code, text = _run_check(clean_env, check_path=str(bad_path2))
        assert code == 1 and "疑似正文" in text, (code, text)
        tampered3 = json.loads(target1.read_text(encoding="utf-8"))
        tampered3["metrics"]["ndcgAtK"] = {"3": "x" * 600}
        bad_path3 = _write_json(room / "tampered3.json", tampered3)
        code, text = _run_check(clean_env, check_path=str(bad_path3))
        assert code == 1 and "疑似正文" in text, (code, text)
        wrong_version = json.loads(target1.read_text(encoding="utf-8"))
        wrong_version["summarySchemaVersion"] = 2
        code, text = _run_check(clean_env, check_path=str(_write_json(room / "wrongver.json", wrong_version)))
        assert code == 1 and "summarySchemaVersion" in text, (code, text)
        inconsistent = json.loads(target1.read_text(encoding="utf-8"))
        inconsistent["counting"]["blockedExcluded"] = 5   # 126 != 5 + 120
        code, text = _run_check(clean_env, check_path=str(_write_json(room / "incons.json", inconsistent)))
        assert code == 1 and "三数不自洽" in text, (code, text)
        effective119 = json.loads(target1.read_text(encoding="utf-8"))
        effective119["counting"]["effectiveCases"] = 119
        effective119["counting"]["blockedExcluded"] = 7
        code, text = _run_check(clean_env, check_path=str(_write_json(room / "eff119.json", effective119)))
        assert code == 1 and "effectiveCases=119" in text, (code, text)
        cov_null = json.loads(target1.read_text(encoding="utf-8"))
        cov_null["metrics"]["fullCoverageHitRate"] = None
        code, text = _run_check(clean_env, check_path=str(_write_json(room / "covnull.json", cov_null)))
        assert code == 1 and "fullCoverageHitRate" in text, (code, text)
        gate_inconsistent = json.loads(target1.read_text(encoding="utf-8"))
        gate_inconsistent["releaseGate"]["releaseBlockedReason"] = None   # eligible=false 但无原因
        code, text = _run_check(clean_env, check_path=str(_write_json(room / "gateinc.json", gate_inconsistent)))
        assert code == 1 and "releaseGate 不自洽" in text, (code, text)
        missing_field = json.loads(target1.read_text(encoding="utf-8"))
        missing_field.pop("cost")
        code, text = _run_check(clean_env, check_path=str(_write_json(room / "nocost.json", missing_field)))
        assert code == 1 and "缺必需字段 cost" in text, (code, text)
        # 文件缺失 / 非法 JSON → 退出 2
        code, text = _run_check(clean_env, check_path=str(room / "ghost-summary.json"))
        assert code == 2 and "摘要文件不存在" in text, (code, text)
        code, text = _run_check(clean_env, check_path=str(bad))
        assert code == 2 and "不是合法 JSON 摘要" in text, (code, text)

        # ── 纯函数断言 ──
        resolved, how = resolve_dataset_source("classpath:eval/eval-v2.jsonl")
        assert resolved is not None and resolved.is_file() and "src/main/resources/eval" in str(resolved)
        assert resolve_dataset_source("upload:x.jsonl")[0] is None
        assert resolve_dataset_source("inline")[0] is None
        assert resolve_dataset_source("no-such-dataset.jsonl")[0] is None
        assert resolve_dataset_source(str(dataset))[0].resolve() == dataset.resolve()
        assert resolve_dataset_source(None)[0] is None
        # 行号口径：注释/空行也占行号；id 缺省 line-<物理行号>
        mini = room / "mini-dataset.jsonl"
        mini.write_text('# 头注释\n\n{"question":"a","referenceAnswer":"b"}\n'
                        '{"id":"x-1","question":"a","referenceAnswer":"b","status":"blocked"}\n'
                        '// 斜杠注释\n{"id":"x-2","question":"a","referenceAnswer":"b"}\n',
                        encoding="utf-8")
        blocked_mini, counting_mini = parse_dataset_ids(mini)
        assert blocked_mini == {"x-1"}, (blocked_mini, counting_mini)
        assert counting_mini == ["line-3", "x-2"], counting_mini
        state = _forbidden_scan({"a": {"question": 1}, "b": ["ok", {"answer": "x"}], "c": "y" * 501})
        assert len(state["hits"]) == 3 and state["max_len"] == 501, state
        assert check_summary_document(["not", "a", "dict"])
        assert preflight.as_int(True) is None and preflight.as_number(True) is None

    print("self-test OK: v2 报告校验与压缩摘要（计数口径 D9 / effective gate 复用预检 / 摘要防正文）")
    print("  - fixture1 126=120+6 blocked → 0 且三数 126/6/120 正确；报告只读；sidecar κ 翻盘与冻结 false 并存；--check 0")
    print("  - fixture2 上游已过滤 120 题 → 0（blockedExcluded=0）；无 sidecar → effective false 如实记录")
    print("  - fixture3 题数不符（20 题预热）→ 1 列缺失 100 条不落盘；fixture4 blocked 剔除后 121 → 1 列 phantom 差集")
    print("  - fixture5 数据集无 blocked → 126≠120 如实报；fixture6 覆盖率/错误率 null、retrievalCases=0 → 1")
    print("  - fixture7 缺 schemaVersion / 非 2.0 → 1「不是 v2 报告」不猜测；fixture8 gate 键缺失/类型错/完整性 → 1")
    print("  - fixture9 数据集无法定位（upload:/inline/不存在）→ 2 无法核对计数口径；fixture10 目标撞报告/sidecar → 2")
    print("  - fixture11 文件缺失/非法 JSON/--sidecar/--env-file → 2；fixture12 sidecar runId 绑定与内嵌 agreement 回退")
    print("  - fixture13 自动选最新报告（sidecar 不冒充报告）；预热报告被拒；fixture14 摘要含正文/超长字符串/版本/自洽 → 1")
    return 0


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="v2 报告校验与压缩摘要：核验 120 题有效计数口径（D9）并产出不含正文的摘要"
                    "（详见模块 docstring 与 " + REPORT_DOC + "）")
    parser.add_argument("--report", help="v2 评测报告 JSON（缺省自动取 eval-reports/ 下最新一份）")
    parser.add_argument("--report-dir", dest="report_dir", help="自动选报告时的目录（缺省 eval-reports/）")
    parser.add_argument("--sidecar", help="人工判定旁路文件（缺省: 同目录 {报告名}.human-reviews.json）")
    parser.add_argument("--env-file", dest="env_file",
                        help="κ 阈值环境变量来源文件（缺省: $EVAL_ENV_FILE，再缺省仓库根 .env；值不打印）")
    parser.add_argument("--write", metavar="PATH", help="校验通过后把压缩摘要写入该路径")
    parser.add_argument("--check", metavar="PATH", help="校验已有摘要文件（自包含，不要求报告还在）")
    parser.add_argument("--self-test", dest="self_test", action="store_true",
                        help="内置 fixture 自检：离线、不读宿主环境与真实报告")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    if args.self_test and (args.report or args.sidecar or args.write or args.check or args.report_dir):
        parser.error("--self-test 与其它参数只能二选一")
    if args.self_test:
        return self_test()
    if args.write and args.check:
        parser.error("--write 与 --check 只能二选一")
    if not args.write and not args.check:
        parser.print_help(sys.stderr)
        return 2
    try:
        if args.check:
            return run_check(args.check)
        return run_write(args.write, report_arg=args.report, report_dir=args.report_dir,
                         sidecar_arg=args.sidecar, env_file_arg=args.env_file)
    except SummaryError as error:
        print(f"!! {error}", file=sys.stderr)
        return 1
    except UsageError as error:
        print(f"eval_report_summary: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
