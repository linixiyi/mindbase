#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""发布条件预检（task-0006，eval-real-run 包）：合并「报告 config + sidecar agreement」，
一条命令告诉用户这份 v2 评测报告离"可发布"还差哪几项。

背景（spec 2026-11-12_eval-real-run C6 / A9 / D4）：报告里 releaseEligible=false 是必然的
（eval.judge.provider 未配置 → SELF_JUDGE；没有人工复核 → κ 未计算；eval.pricing.* 未配置 →
cost.amount=null），但没人知道缺哪一项。报告文件本身冻结不改（HumanReviewStore 把人工判定
写在报告旁的旁路文件 *.human-reviews.json），因此发布结论必须按"报告 + sidecar"的有效口径
重算——本脚本就是这条重算命令：只读文件，绝不改报告或 sidecar，也绝不自动生成人工标签（F4）。

三道门禁（全部通过才 effective releaseEligible=true）：
  ① 评委独立性  config.judgeIndependence == "INDEPENDENT"。
                SELF_JUDGE（或任何非 INDEPENDENT 值，宁可误拦不可放行）→ 打印配置
                eval.judge.provider / eval.judge.model 的具体步骤（RagEvalCaseRunner.java:496-524：
                provider 按 llm scope 定向解析，不要求处于激活状态；key 由用户提供）。
  ② 人工一致性  agreement.comparableCases（缺该键时回退 sampleSize）≥ min-comparable-cases
                且 cohensKappa ≥ min-kappa（min-kappa 显式停用时只查样本量，但如实标注"不代表可发布"）。
                缺 sidecar → 提示用 scripts/eval_human_review.py export/import 收 ≥5 条人工判定。
  ③ 费用折算    cost.amount 非 null（0.0 是合法值）。null → 提示 EVAL_PRICING_INPUT_PER_1K /
                OUTPUT_PER_1K / CURRENCY（EvalReport.Cost：两侧单价须同时配置才折算），
                并如实区分 "unpriced"（未配价）与 "priced 但无 token 用量记录" 两种成因。

阈值来源（打印来源，可审计）：sidecar agreement 记录值（minKappa / minComparableCases，
JudgeHumanAgreement 字段）> 报告 config 记录值（EvalReport 的 CONFIG_MIN_KAPPA /
CONFIG_MIN_COMPARABLE_CASES 同名键）> 进程环境 / --env-file（键 EVAL_HUMAN_AGREEMENT_MIN_KAPPA、
EVAL_HUMAN_AGREEMENT_MIN_CASES，application.yml:342-349）> 缺省 0.6 / 5。
记录值优先于环境：agreement 是后端算 κ 时所用阈值的快照，发布判定不追认事后放宽——想换阈值
就重新 import 重算 agreement，不改旧文件。env 值置空表示停用 κ 检查（与 yml 注释一致），
但这不代表可发布——其余门禁照常生效。

用法（在工作树根执行）：
    python3 -B scripts/eval_release_preflight.py --report eval-reports/xxx.json
    python3 -B scripts/eval_release_preflight.py --report eval-reports/xxx.json \
        --sidecar eval-reports/xxx.human-reviews.json --env-file /path/to/.env
    python3 -B scripts/eval_release_preflight.py --self-test

--sidecar 缺省按 HumanReviewStore.sidecarPath（HumanReviewStore.java:29-33）口径自动找同目录
{报告文件名去掉 .json}.human-reviews.json；找不到再回退报告内嵌的 humanReview.agreement /
humanAgreement（GET /runs/{id}/report 或 /runs/{id} 响应保存成文件的形态）。不做目录级
模糊匹配 *.human-reviews.json——挑错轮次的 sidecar 会把别一轮的 κ 安到本报告头上，宁缺勿错
（runId 绑定校验仍兜底：sidecar 与报告 runId 不一致判 FAIL）。

--env-file 缺省取 $EVAL_ENV_FILE，再缺省仓库根 .env（后端启动也读它，键名一致）；进程环境里的
同名键优先于文件。缺省文件不存在不算错误（视为无配置）；**显式指定**的 --env-file / --sidecar
不存在则退出 2。任何值只进内存用于判定，不打印值（"已设置/未设置"状态除外）；凭据纪律与 runbook 一致。

退出码：
    0  effective releaseEligible=true（三道门禁全过；注意这是"报告 + sidecar"重算结论，
       原始报告 config.releaseEligible 保持冻结值不变）
    1  effective releaseEligible=false（列出全部命中原因与每项的可执行修复步骤）
    2  环境/用法错误（文件缺失或非法 JSON、--sidecar/--env-file 指定的文件不存在），或报告
       结构性缺字段（缺 config / config.judgeIndependence / cost）——此时打印"该报告不能用于
       发布判定"，**不默认 PASS**（缺字段与"门禁未过"是两种失败：前者连诚实判定的材料都不齐）
    --self-test 固定走内置 fixture，断言以上退出码与原因；离线、不读真实报告、不联网、
    不读宿主环境（进程环境经参数注入）。

不打印密钥值、不改报告文件、不自动生成人工标签；只依赖 Python 3 标准库（spec D1）。
"""

from __future__ import annotations

import argparse
import io
import json
import math
import os
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ENV_FILE = REPO_ROOT / ".env"
SIDECAR_SUFFIX = ".human-reviews.json"   # HumanReviewStore.SIDECAR_SUFFIX（HumanReviewStore.java:21）
SELF_JUDGE = "SELF_JUDGE"                # RagEvalCaseRunner.SELF_JUDGE
INDEPENDENT = "INDEPENDENT"              # RagEvalCaseRunner.INDEPENDENT
DEFAULT_MIN_KAPPA = 0.6                  # application.yml eval.human-agreement.min-kappa 缺省
DEFAULT_MIN_COMPARABLE = 5               # application.yml eval.human-agreement.min-comparable-cases 缺省

ENV_MIN_KAPPA = "EVAL_HUMAN_AGREEMENT_MIN_KAPPA"
ENV_MIN_CASES = "EVAL_HUMAN_AGREEMENT_MIN_CASES"
ENV_PRICING_KEYS = ("EVAL_PRICING_INPUT_PER_1K", "EVAL_PRICING_OUTPUT_PER_1K", "EVAL_PRICING_CURRENCY")
ENV_JUDGE_KEYS = ("EVAL_JUDGE_PROVIDER", "EVAL_JUDGE_MODEL")

HUMAN_REVIEW_SCRIPT = "scripts/eval_human_review.py"
HUMAN_REVIEW_DOC = "docs/eval/human-review.md"
CHECKLIST_DOC = "docs/eval/release-checklist.md"

# 命中原因代码（self-test 断言用；文本另附中文说明，含 SELF_JUDGE/无人工判定/未配价 关键词）
R_SELF_JUDGE = "SELF_JUDGE"
R_NO_SIDE = "NO_HUMAN_REVIEWS"        # 无人工判定：缺 sidecar 也缺报告内嵌 agreement
R_NO_AGREEMENT = "SIDECAR_NO_AGREEMENT"
R_RUNID = "SIDECAR_RUN_MISMATCH"
R_SAMPLE = "INSUFFICIENT_SAMPLE"
R_KAPPA_LOW = "KAPPA_BELOW_MIN"
R_KAPPA_MISSING = "KAPPA_NOT_COMPUTED"
R_UNPRICED = "UNPRICED"


class PreflightError(Exception):
    """环境/用法/结构错误，退出 2。"""


# ---------------------------------------------------------------------------
# JSON / 值工具（eval_human_review.py 同口径）
# ---------------------------------------------------------------------------

def unwrap_data(root):
    """接口响应是 {code, message, data} 包裹（RagEvalAdminController.ok）；落盘报告没有包裹。
    顶层既无 config 也无 cases 也无 runId 且带 dict 型 data 时，展开到 data。"""
    if (isinstance(root, dict) and isinstance(root.get("data"), dict)
            and "config" not in root and "cases" not in root and "runId" not in root):
        return root["data"]
    return root


def load_json_file(path) -> object:
    try:
        raw = Path(path).read_text(encoding="utf-8")
    except OSError as exc:
        raise PreflightError(f"文件不可读: {path}: {exc}") from exc
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise PreflightError(f"不是合法 JSON: {path}: 第 {exc.lineno} 行 {exc.msg}") from exc


def as_text(value):
    return value.strip() if isinstance(value, str) and value.strip() else None


def as_number(value):
    """数值透传；JSON 布尔是 int 子类，显式排除（amount=true 是损坏数据，不能当 1 用）。"""
    if isinstance(value, bool):
        return None
    return float(value) if isinstance(value, (int, float)) else None


def as_int(value):
    if isinstance(value, bool):
        return None
    return value if isinstance(value, int) else None


def fmt_frozen(value):
    """冻结记录按 JSON 字面量风格显示（true/false/null），避免与 JSON 原文混淆。"""
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def read_env_file(path) -> dict:
    """读 KEY=VALUE（支持成对单/双引号包裹）；值只进内存，绝不打印。文件不存在返回空。"""
    values: dict = {}
    try:
        raw = Path(path).read_text(encoding="utf-8")
    except OSError:
        return values
    for line in raw.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("\"", "'"):
            value = value[1:-1]
        if key:
            values[key] = value
    return values


def env_value(environ, file_values, key):
    """进程环境优先于 env 文件。返回 (值或 None, 来源标签或 None)；空串按"已设置但为空"返回。"""
    if key in environ:
        return environ[key], f"进程环境 {key}"
    if key in file_values:
        return file_values[key], f"env 文件 {key}"
    return None, None


def key_status_line(key, environ, file_values):
    """"已设置/未设置"状态行：只报状态与来源，不打印值。"""
    raw, src = env_value(environ, file_values, key)
    if raw is None:
        return f"{key} 未设置"
    label = "进程环境" if src.startswith("进程") else "env 文件"
    state = "已设置但为空" if raw.strip() == "" else "已设置"
    return f"{key} {state}（{label}，值不打印）"


# ---------------------------------------------------------------------------
# sidecar 与 agreement 定位
# ---------------------------------------------------------------------------

def sidecar_path_for(report_path: Path) -> Path:
    """与 HumanReviewStore.sidecarPath（HumanReviewStore.java:29-33）同口径：
    {报告文件名去掉 .json}.human-reviews.json，同目录。"""
    name = report_path.name
    base = name[:-len(".json")] if name.endswith(".json") else name
    return report_path.with_name(base + SIDECAR_SUFFIX)


def find_agreement(root):
    """三种形态取 agreement，优先级与 eval_human_review.find_agreement 一致：
    humanAgreement（状态 JSON 顶层）> humanReview.agreement（报告接口附带）> agreement（sidecar 顶层）。
    返回 (agreement dict 或 None, 来源标签)。"""
    if not isinstance(root, dict):
        return None, None
    if isinstance(root.get("humanAgreement"), dict):
        return root["humanAgreement"], "humanAgreement（报告内嵌，GET /runs/{id} 状态 JSON 形态）"
    human_review = root.get("humanReview")
    if isinstance(human_review, dict) and isinstance(human_review.get("agreement"), dict):
        return human_review["agreement"], "humanReview.agreement（报告内嵌，GET /runs/{id}/report 形态）"
    if isinstance(root.get("agreement"), dict):
        return root["agreement"], "agreement（sidecar 顶层）"
    return None, None


# ---------------------------------------------------------------------------
# 阈值解析（记录值优先于环境；来源全程打印）
# ---------------------------------------------------------------------------

def _parse_threshold_number(raw, source, key):
    """env 值是字符串、JSON 记录值是数字，两条路都归一成有限 float；坏值不打印。"""
    if isinstance(raw, str):
        try:
            value = float(raw.strip())
        except ValueError:
            raise PreflightError(f"{source} 里为 {key} 配置的值无法解析为有限数字（值不打印），"
                                 "请检查该配置") from None
    else:
        value = as_number(raw)
        if value is None:
            raise PreflightError(f"{source} 里为 {key} 配置的值无法解析为有限数字（值不打印），"
                                 "请检查该配置")
    if not math.isfinite(value):
        raise PreflightError(f"{source} 里为 {key} 配置的值不是有限数字（值不打印），请检查该配置")
    return value


def _threshold_from_record(record, record_label, key):
    """从 agreement / config 记录里取阈值。键存在即为"已记录"（null = 后端记录的"停用"）；
    键不存在返回 (None, None) 表示未记录，交给下一级来源。"""
    if not isinstance(record, dict) or key not in record:
        return None, None
    raw = record.get(key)
    if raw is None:
        return None, f"{record_label} {key}=null（该检查在记录时已停用）"
    return _parse_threshold_number(raw, record_label, key), record_label


def resolve_thresholds(config, agreement, environ=None, file_values=None):
    """返回 (min_kappa|None, min_kappa 来源, min_comparable, min_comparable 来源)。

    优先级：sidecar agreement 记录值 > 报告 config 记录值 > 进程环境/--env-file > 缺省。
    env 值为空串 = 停用 κ 检查（application.yml 注释口径）；样本量下限不允许停用。
    """
    environ = os.environ if environ is None else environ
    file_values = {} if file_values is None else file_values

    min_kappa, kappa_src = _threshold_from_record(agreement, "sidecar agreement 记录值", "minKappa")
    if kappa_src is None:
        min_kappa, kappa_src = _threshold_from_record(config, "报告 config 记录值", "minKappa")
    if kappa_src is None:
        raw, src = env_value(environ, file_values, ENV_MIN_KAPPA)
        if raw is None:
            min_kappa, kappa_src = DEFAULT_MIN_KAPPA, f"缺省 {DEFAULT_MIN_KAPPA:g}（eval.human-agreement.min-kappa）"
        elif raw.strip() == "":
            min_kappa, kappa_src = None, f"{src}=\"\"（κ 检查显式停用）"
        else:
            min_kappa = _parse_threshold_number(raw, src, ENV_MIN_KAPPA)
            kappa_src = src

    min_cases, cases_src = _threshold_from_record(agreement, "sidecar agreement 记录值", "minComparableCases")
    if cases_src is None:
        min_cases, cases_src = _threshold_from_record(config, "报告 config 记录值", "minComparableCases")
    if cases_src is None:
        raw, src = env_value(environ, file_values, ENV_MIN_CASES)
        if raw is not None and raw.strip() != "":
            value = _parse_threshold_number(raw, src, ENV_MIN_CASES)
            if value != int(value):
                raise PreflightError(f"{src} 的 {ENV_MIN_CASES} 必须是整数（值不打印）")
            min_cases, cases_src = int(value), src
        else:
            min_cases = DEFAULT_MIN_COMPARABLE
            cases_src = f"缺省 {DEFAULT_MIN_COMPARABLE}（eval.human-agreement.min-comparable-cases）"
    return min_kappa, kappa_src, int(min_cases), cases_src


# ---------------------------------------------------------------------------
# 门禁判定（纯函数：输入已解析的材料，输出逐项结论）
# ---------------------------------------------------------------------------

def check_judge_independence(config):
    """① 评委独立性。返回失败列表 [(code, message)]；只有字面 INDEPENDENT 放行。"""
    independence = as_text(config.get("judgeIndependence"))
    if independence == INDEPENDENT:
        return []
    if independence == SELF_JUDGE:
        return [(R_SELF_JUDGE,
                 f"SELF_JUDGE：评委与生成共用同一模型（config.judgeIndependence={independence}），"
                 "自评分数不能用于发布判定")]
    return [(R_SELF_JUDGE,
             f"SELF_JUDGE：config.judgeIndependence={independence!r} 不是 INDEPENDENT"
             "（宁可误拦不可放行，只有字面 INDEPENDENT 放行）")]


def judge_fix_steps(environ, file_values):
    """SELF_JUDGE 的修复步骤：第二家 provider 的配置方法（RagEvalCaseRunner.java:496-524）。"""
    lines = [
        "修复（配置独立评委）:",
        "  1. 在管理后台「模型供应商」用第二家供应商配置一个 llm scope 的供应商编码（不要求设为激活状态，"
        "eval.judge.provider 按 llm scope 定向解析）——provider key 由你提供，脚本不持有也不打印。",
        "  2. 设置 eval.judge.provider=<该供应商编码>（application.yml 或环境变量 EVAL_JUDGE_PROVIDER），"
        "可选 eval.judge.model=<模型名>（EVAL_JUDGE_MODEL）覆盖该供应商默认模型。",
        "  3. 重启后端重跑评测；报告文件冻结，旧报告的 SELF_JUDGE 不会改变，重跑后再跑本预检。",
    ]
    lines += ["  当前: " + key_status_line(key, environ, file_values) for key in ENV_JUDGE_KEYS]
    return lines


def check_human_agreement(agreement, thresholds, no_agreement_reason=None):
    """② 人工一致性。返回 (失败列表, 信息行)。agreement 为 None 时按 no_agreement_reason 分叉：
    无 sidecar 也无内嵌 → R_NO_SIDE；sidecar 存在但没有 agreement 块 → R_NO_AGREEMENT。"""
    min_kappa, _kappa_src, min_cases, _cases_src = thresholds
    if agreement is None:
        if no_agreement_reason is not None:
            return [(R_NO_AGREEMENT, f"无人工判定：{no_agreement_reason}，κ 未计算")], []
        return [(R_NO_SIDE, "无人工判定：没有 sidecar，也没有报告内嵌 agreement，κ 未计算")], []

    failures = []
    info = []
    comparable = as_int(agreement.get("comparableCases"))
    comparable_key = "comparableCases"
    if comparable is None:
        comparable = as_int(agreement.get("sampleSize"))
        comparable_key = "sampleSize"
    if comparable is None:
        failures.append((R_SAMPLE,
                         "可比样本数不可读（agreement 里没有 comparableCases/sampleSize 整数键），"
                         "样本量门禁无法判定"))
    elif comparable < min_cases:
        failures.append((R_SAMPLE,
                         f"可比样本 {comparable} 条（{comparable_key}）少于下限 {min_cases} 条，"
                         "一致性结论不成立"))
    else:
        info.append(f"可比样本 {comparable} 条（{comparable_key}）≥ 下限 {min_cases} 条")

    kappa = as_number(agreement.get("cohensKappa"))
    if min_kappa is not None:
        if kappa is None:
            failures.append((R_KAPPA_MISSING,
                             "κ 未计算/无定义（cohensKappa=null：可比样本为 0，或两边判对比例乘积为 1），"
                             "不能用于发布判定"))
        elif kappa < min_kappa:
            failures.append((R_KAPPA_LOW,
                             f"κ {kappa:.3f} 低于下限 {min_kappa:g}，评委与人工一致性不足"
                             "（分歧用例看 agreement.disagreementCaseIds，补人工复核或重新校准评委）"))
            disagreements = agreement.get("disagreementCaseIds")
            if isinstance(disagreements, list) and disagreements:
                preview = "、".join(str(item) for item in disagreements[:5])
                more = "" if len(disagreements) <= 5 else f"（共 {len(disagreements)} 条，仅列前 5）"
                info.append(f"disagreementCaseIds: {preview}{more}")
        else:
            info.append(f"cohensKappa {kappa:.3f} ≥ 下限 {min_kappa:g}")
    else:
        info.append("κ 阈值已停用——本项只查样本量；这不代表可发布，其余门禁照常")
    return failures, info


def human_fix_steps(report_path, run_id, min_cases):
    """缺 sidecar / 样本不足的修复步骤：export → 人工填 → import（≥min_cases 条可比样本）。"""
    run_arg = run_id if run_id else "<runId>"
    return [
        f"修复（收集人工判定，≥{min_cases} 条可比样本）:",
        f"  python3 -B {HUMAN_REVIEW_SCRIPT} export --report {report_path} --out review.json",
        "  # 人工在 review.json 逐条填 verdict（true/false，可加 score 1-5 与 note ≤500 字）——判定由人做，脚本不代填",
        f"  python3 -B {HUMAN_REVIEW_SCRIPT} import --run {run_arg} --file review.json --reviewer <复核人>",
        "  # 判定落在报告旁 *.human-reviews.json（HumanReviewStore），报告文件本身冻结；字段说明见 "
        + HUMAN_REVIEW_DOC,
        "  然后重跑本预检。",
    ]


def check_pricing(cost):
    """③ 费用折算。cost.amount 非 null（含 0.0）即过；null 按"未配价"拦截。"""
    amount = as_number(cost.get("amount"))
    if amount is not None:
        currency = cost.get("currency")
        return [], [f"cost.amount={amount:.6g}" + (f"（{currency}）" if currency else "")]
    note = as_text(cost.get("note")) or ""
    if note.startswith("priced"):
        message = (f"未配价：cost.amount=null（eval.pricing.* 已配置但本次没有 token 用量记录，"
                   f"无可折算金额；note: {note}）")
    else:
        message = f"未配价：cost.amount=null（note: {note or '无'}），费用不可折算"
    return [(R_UNPRICED, message)], []


def pricing_fix_steps(environ, file_values):
    lines = [
        "修复（配价）:",
        "  在 .env 或进程环境同时设置 EVAL_PRICING_INPUT_PER_1K 与 EVAL_PRICING_OUTPUT_PER_1K"
        "（每 1000 tokens 单价，两者都配置才折算，见 application.yml eval.pricing 注释），"
        "可选 EVAL_PRICING_CURRENCY；重启后端重跑评测。",
    ]
    lines += ["  当前: " + key_status_line(key, environ, file_values) for key in ENV_PRICING_KEYS]
    lines.append("  报告冻结：cost.amount=null 的旧报告不会因配置变化补上金额，需重跑评测。")
    return lines


def evaluate(config, cost, agreement, thresholds, report_run_id, sidecar_run_id,
             sidecar_report_file, report_file_name, no_agreement_reason=None):
    """合并口径的发布门禁。返回 dict：checks（逐项）、failures/codes（全部命中原因）、eligible。

    runId 绑定：报告与 sidecar 都有 runId 且不一致 → ②判 FAIL（人工判定绑定该轮评委标签）；
    任一边缺 runId → 不判 FAIL，由外层打警告（无法校验绑定时如实说明，不猜）。
    """
    checks = []

    fail_1 = check_judge_independence(config)
    checks.append({
        "id": "①评委独立性",
        "ok": not fail_1,
        "failures": fail_1,
        "ok_lines": ["config.judgeIndependence=INDEPENDENT"],
        "info_lines": [],
    })

    fail_2, info_2 = check_human_agreement(agreement, thresholds, no_agreement_reason)
    if agreement is not None and report_run_id and sidecar_run_id and report_run_id != sidecar_run_id:
        fail_2 = [(R_RUNID, f"sidecar runId={sidecar_run_id} 与报告 runId={report_run_id} 不一致——"
                            "人工判定绑定该轮评委标签，κ 不能跨轮合并（换轮要重新 export/import）")] + fail_2
    if sidecar_report_file and report_file_name and sidecar_report_file != report_file_name:
        info_2 = info_2 + [f"警告: sidecar.reportFile={sidecar_report_file} 与当前报告文件名 "
                            f"{report_file_name} 不一致（文件可能被改名，按 runId 绑定为准）"]
    checks.append({
        "id": "②人工一致性",
        "ok": not fail_2,
        "failures": fail_2,
        "ok_lines": info_2 if not fail_2 else [],
        "info_lines": info_2,
    })

    fail_3, info_3 = check_pricing(cost)
    checks.append({
        "id": "③费用折算",
        "ok": not fail_3,
        "failures": fail_3,
        "ok_lines": info_3,
        "info_lines": [],
    })

    failures = [message for check in checks for _, message in check["failures"]]
    codes = [code for check in checks for code, _ in check["failures"]]
    return {"checks": checks, "failures": failures, "codes": codes, "eligible": not failures}


# ---------------------------------------------------------------------------
# 渲染
# ---------------------------------------------------------------------------

def render(report_path, report_root, report_run_id, sidecar_label, thresholds, result, warnings,
           file_values, environ):
    min_kappa, kappa_src, min_cases, cases_src = thresholds
    config = report_root.get("config")
    lines = []
    lines.append("== 发布条件预检（报告 + sidecar 合并口径）==")
    lines.append(f"报告: {report_path}" + (f"（runId: {report_run_id}）" if report_run_id else ""))
    lines.append(f"sidecar/agreement 来源: {sidecar_label}")
    lines.append(f"阈值: min-comparable-cases={min_cases}（来源: {cases_src}）；"
                 + ("min-kappa 停用（来源: " + kappa_src + "）" if min_kappa is None
                    else f"min-kappa={min_kappa:g}（来源: {kappa_src}）"))
    for warning in warnings:
        lines.append(f"警告: {warning}")

    for check in result["checks"]:
        lines.append("")
        lines.append(f"{check['id']}: {'PASS' if check['ok'] else 'FAIL'}")
        if check["ok"]:
            lines += [f"  - {line}" for line in check["ok_lines"]]
        for _, message in check["failures"]:
            lines.append(f"  - {message}")
        if not check["ok"] and check["id"] == "①评委独立性":
            lines += ["  " + line for line in judge_fix_steps(environ, file_values)]
        if not check["ok"] and check["id"] == "②人工一致性":
            lines += [f"  - {line}" for line in check["info_lines"]]
            lines += ["  " + line for line in human_fix_steps(report_path, report_run_id, min_cases)]
        if not check["ok"] and check["id"] == "③费用折算":
            lines += ["  " + line for line in pricing_fix_steps(environ, file_values)]

    lines.append("")
    lines.append(f"effective releaseEligible: {'true' if result['eligible'] else 'false'}")
    if result["eligible"]:
        lines.append("三道门禁全部通过（评委独立 / 人工一致 / 已配价）。")
    else:
        lines.append("命中原因:")
        lines += [f"  - {message}" for message in result["failures"]]
    frozen_eligible = config.get("releaseEligible")
    frozen_reason = config.get("releaseBlockedReason")
    frozen_kappa = config.get("kappa")
    frozen_note = config.get("kappaNote")
    frozen_line = (f"原始报告冻结记录: config.releaseEligible={fmt_frozen(frozen_eligible)}"
                   f"，config.kappa={fmt_frozen(frozen_kappa)}")
    if frozen_reason is not None:
        frozen_line += f"，releaseBlockedReason: {frozen_reason}"
    elif frozen_note:
        frozen_line += f"（{frozen_note}）"
    lines.append(frozen_line)
    lines.append("口径: 原始报告文件冻结不改；发布判定以「报告 + sidecar」重算为准（本输出的 effective "
                 "releaseEligible）；重算结论不回写报告文件。")
    lines.append(f"步骤与分工见 {CHECKLIST_DOC}")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def run_preflight(report_path, sidecar_arg=None, env_file_arg=None, environ=None, out=None) -> int:
    """返回退出码（0/1/2）；输出写到 out（None = stdout），self-test 注入 StringIO 捕获。"""
    environ = os.environ if environ is None else environ
    emit = out.write if out is not None else sys.stdout.write

    report_root = unwrap_data(load_json_file(report_path))
    if not isinstance(report_root, dict):
        raise PreflightError(f"报告必须是 JSON 对象（{report_path}）——该报告不能用于发布判定")
    config = report_root.get("config")
    cost = report_root.get("cost")
    structural = []
    if not isinstance(config, dict):
        structural.append("报告缺 config 对象（不是 v2 评测报告的形态）")
    elif as_text(config.get("judgeIndependence")) is None:
        structural.append("报告 config 缺 judgeIndependence 字段")
    if not isinstance(cost, dict):
        structural.append("报告缺 cost 对象")
    if structural:
        raise PreflightError("；".join(structural) + "——该报告不能用于发布判定（缺字段不默认 PASS，"
                             f"口径见 {CHECKLIST_DOC}）")

    report_file_name = Path(report_path).name
    report_run_id = as_text(report_root.get("runId"))

    # sidecar 定位：显式 --sidecar > 同目录约定名 > 报告内嵌 agreement
    sidecar_label = None
    agreement = None
    no_agreement_reason = None
    sidecar_run_id = None
    sidecar_report_file = None
    sidecar_doc = None
    if sidecar_arg:
        sidecar_path = Path(sidecar_arg)
        if not sidecar_path.is_file():
            raise PreflightError(f"--sidecar 指定的文件不存在: {sidecar_path}")
        sidecar_doc = unwrap_data(load_json_file(sidecar_path))
        sidecar_label = f"{sidecar_path}（--sidecar 显式指定）"
    else:
        default_sidecar = sidecar_path_for(Path(report_path))
        if default_sidecar.is_file():
            sidecar_doc = unwrap_data(load_json_file(default_sidecar))
            sidecar_label = f"{default_sidecar}（同目录约定名，HumanReviewStore 口径）"
        else:
            agreement, source = find_agreement(report_root)
            sidecar_label = (f"{source}（同目录未找到 {default_sidecar.name}，回退报告内嵌）"
                             if agreement else
                             f"无（同目录未找到 {default_sidecar.name}，报告也无内嵌 agreement）")
    if sidecar_doc is not None:
        agreement, source = find_agreement(sidecar_doc)
        sidecar_run_id = as_text(sidecar_doc.get("runId"))
        sidecar_report_file = as_text(sidecar_doc.get("reportFile"))
        if agreement is None:
            no_agreement_reason = "sidecar 里没有 agreement 块（人工判定还没有 import？）"
            sidecar_label += f"；{no_agreement_reason}"
        else:
            sidecar_label += f" → {source}"

    env_file = env_file_arg or environ.get("EVAL_ENV_FILE") or str(DEFAULT_ENV_FILE)
    if env_file_arg and not Path(env_file).is_file():
        raise PreflightError(f"--env-file 指定的文件不存在: {env_file}")
    env_values = read_env_file(env_file)

    thresholds = resolve_thresholds(config, agreement, environ=environ, file_values=env_values)
    result = evaluate(config, cost, agreement, thresholds, report_run_id, sidecar_run_id,
                      sidecar_report_file, report_file_name, no_agreement_reason)

    warnings = []
    if report_run_id is None:
        warnings.append("报告缺 runId，无法校验 sidecar 与报告的轮次绑定（人工判定是否属于这一轮）")

    emit(render(str(report_path), report_root, report_run_id, sidecar_label, thresholds, result,
                warnings, env_values, environ) + "\n")
    return 0 if result["eligible"] else 1


# ---------------------------------------------------------------------------
# --self-test：内置 fixture，离线、不读真实报告、不联网、不读宿主环境
# ---------------------------------------------------------------------------

RUN_ID = "0f1e2d3c4b5a6"
MIN_KAPPA = DEFAULT_MIN_KAPPA
MIN_CASES = DEFAULT_MIN_COMPARABLE
SECRET_MARKER = "SK-DO-NOT-PRINT-42"


def _config(judge_independence, release_eligible=False, blocked_reason=None, extra=None) -> dict:
    """模仿 RagEvalService 落盘报告的 config 形态（smoke fixture 同款键；刚跑完 κ 必为 null）。"""
    config = {
        "topK": 5,
        "llm": "deepseek/deepseek-chat",
        "judge": SELF_JUDGE if judge_independence == SELF_JUDGE else "siliconflow/Qwen2.5-72B-Instruct",
        "judgeIndependence": judge_independence,
        "releaseEligible": release_eligible,
        "releaseBlockedReason": blocked_reason,
        "kappa": None,
        "kappaNote": "kappa 未计算：无人工复核数据，不伪造 κ",
        "pricingInputPer1k": None,
        "pricingOutputPer1k": None,
        "pricingCurrency": None,
    }
    if extra:
        config.update(extra)
    return config


def _report(judge_independence, cost_amount=None, cost_note=None, extra_config=None) -> dict:
    return {
        "runId": RUN_ID,
        "label": "preflight-self-test",
        "schemaVersion": "2.0",
        "config": _config(judge_independence,
                          release_eligible=False,
                          blocked_reason="评委与生成共用同一模型（SELF_JUDGE），自评分数不能用于发布判定"
                          if judge_independence == SELF_JUDGE else
                          "kappa 未计算：无人工复核数据，不伪造 κ",
                          extra=extra_config),
        "cost": {"amount": cost_amount, "currency": "CNY" if cost_amount is not None else None,
                 "note": cost_note or ("unpriced: set eval.pricing.*" if cost_amount is None else "priced")},
        "summary": {"totalCases": 126, "judgedCases": 126},
        "cases": [],
    }


def _agreement(kappa, comparable, min_kappa=MIN_KAPPA, min_comparable=MIN_CASES,
               with_keys=True, status=None) -> dict:
    """HumanReviewStore 落盘 sidecar 顶层 agreement 的形态（JudgeHumanAgreement 字段子集）。"""
    agreement = {
        "status": status or ("OK" if kappa is not None and (min_kappa is None or kappa >= min_kappa)
                             and comparable >= min_comparable else "NEEDS_RECALIBRATION"),
        "warning": None,
        "reviewedCases": comparable + 2,
        "comparableCases": comparable,
        "agreedCases": comparable - 1,
        "agreementRate": (comparable - 1) / comparable if comparable else None,
        "cohensKappa": kappa,
        "judgeCorrectRate": 0.8,
        "humanCorrectRate": 0.9,
        "disagreementCaseIds": ["multi-07", "fact-12"]
        if kappa is not None and min_kappa is not None and kappa < min_kappa else [],
    }
    if with_keys:
        agreement["minKappa"] = min_kappa
        agreement["minComparableCases"] = min_comparable
    return agreement


def _sidecar(agreement, run_id=RUN_ID, report_file=None) -> dict:
    document = {"runId": run_id, "updatedAt": "2026-11-13T00:00:00Z", "reviews": {}, "agreement": agreement}
    if report_file:
        document["reportFile"] = report_file
    return document


def _write_json(path: Path, obj) -> Path:
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def _run(environ, **kwargs) -> tuple[int, str]:
    """捕获 stdout 跑 run_preflight；PreflightError 折算成退出码 2。"""
    buffer = io.StringIO()
    try:
        code = run_preflight(environ=environ, out=buffer, **kwargs)
    except PreflightError as error:
        buffer.write(f"eval_release_preflight: {error}\n")
        code = 2
    return code, buffer.getvalue()


def self_test() -> int:
    clean_env = {}   # self-test 用的"进程环境"：与宿主隔离，run_preflight 的 environ 全部经参数注入

    with tempfile.TemporaryDirectory(prefix="eval-release-preflight-selftest-") as directory:
        room = Path(directory)
        empty_env = room / "empty.env"
        empty_env.write_text("", encoding="utf-8")   # 模拟"无任何 EVAL_* 配置"

        def run_report(name, report, sidecar_arg=None, env_file=None):
            report_path = _write_json(room / name, report)
            before = report_path.read_bytes()
            code, text = _run(clean_env, report_path=str(report_path),
                              sidecar_arg=sidecar_arg, env_file_arg=env_file or str(empty_env))
            assert report_path.read_bytes() == before, "报告文件被改动——预检必须只读"
            return report_path, code, text

        def sidecar_for(report_path: Path, agreement, run_id=RUN_ID, report_file=None) -> Path:
            return _write_json(sidecar_path_for(report_path),
                               _sidecar(agreement, run_id, report_file or report_path.name))

        # ── fixture 1：SELF_JUDGE + 缺 sidecar + 未配价（三重命中）→ 退出 1 ──
        report, code, text = run_report("report1.json", _report(SELF_JUDGE))
        assert code == 1, (code, text)
        for keyword in ("SELF_JUDGE", "无人工判定", "未配价", "eval.judge.provider",
                        "EVAL_PRICING_INPUT_PER_1K", HUMAN_REVIEW_SCRIPT, "原始报告冻结",
                        "releaseEligible: false", "以「报告 + sidecar」重算为准"):
            assert keyword in text, (keyword, text)
        assert "releaseBlockedReason: 评委与生成共用同一模型" in text, text
        assert "export --report" in text and "import --run" in text, text

        # 缺省 sidecar 定位：补上同目录约定名 sidecar（κ 达标；SELF_JUDGE 与未配价仍命中）
        sidecar_for(report, _agreement(0.71, 6))
        code, text = _run(clean_env, report_path=str(report), env_file_arg=str(empty_env))
        assert code == 1, (code, text)
        assert "无人工判定" not in text, text
        assert "cohensKappa 0.710 ≥ 下限 0.6" in text, text
        assert "（同目录约定名" in text, text

        # ── fixture 2：κ 不足（sidecar 在，评委独立，已配价）→ 退出 1，只命中 ② ──
        report2 = _write_json(room / "report2.json", _report(INDEPENDENT, cost_amount=1.23))
        sidecar_for(report2, _agreement(0.42, 10))
        before = report2.read_bytes()
        code, text = _run(clean_env, report_path=str(report2), env_file_arg=str(empty_env))
        assert report2.read_bytes() == before
        assert code == 1, (code, text)
        assert "0.420 低于下限 0.6" in text, text
        assert "disagreementCaseIds: multi-07、fact-12" in text, text
        assert "①评委独立性: PASS" in text, text
        assert "③费用折算: PASS" in text, text
        assert "releaseEligible: false" in text, text
        # κ 恰好等于阈值不算低
        sidecar_for(report2, _agreement(0.6, 10))
        code, text = _run(clean_env, report_path=str(report2), env_file_arg=str(empty_env))
        assert code == 0, (code, text)

        # ── fixture 3：未配价（其余全过）→ 退出 1，提示 EVAL_PRICING_*，且不打印 env 值 ──
        env_file = room / "pricing.env"
        env_file.write_text(
            f"EVAL_PRICING_INPUT_PER_1K={SECRET_MARKER}\n"
            "EVAL_PRICING_CURRENCY=\n", encoding="utf-8")
        report3 = _write_json(room / "report3.json", _report(INDEPENDENT))
        sidecar_for(report3, _agreement(0.75, 8))
        code, text = _run(clean_env, report_path=str(report3), env_file_arg=str(env_file))
        assert code == 1, (code, text)
        assert "未配价" in text and "EVAL_PRICING_OUTPUT_PER_1K 未设置" in text, text
        assert "EVAL_PRICING_INPUT_PER_1K 已设置（env 文件，值不打印）" in text, text
        assert "EVAL_PRICING_CURRENCY 已设置但为空（env 文件，值不打印）" in text, text
        assert SECRET_MARKER not in text, "env 值绝不能出现在输出里"
        # 进程环境优先于 env 文件（同样不打印值）；但报告冻结：cost.amount=null 仍判 FAIL，
        # 环境变量只能说明"下一步重跑评测就能带上费用"，不能替冻结报告补钱
        env_with_pricing = dict(clean_env, EVAL_PRICING_INPUT_PER_1K=SECRET_MARKER,
                                EVAL_PRICING_OUTPUT_PER_1K=SECRET_MARKER)
        code, text = _run(env_with_pricing, report_path=str(report3), env_file_arg=str(env_file))
        assert code == 1, (code, text)
        assert "EVAL_PRICING_INPUT_PER_1K 已设置（进程环境，值不打印）" in text, text
        assert SECRET_MARKER not in text, text
        # cost.amount=0.0 是合法值（免费模型），不算未配价
        report3b = _write_json(room / "report3b.json", _report(INDEPENDENT, cost_amount=0.0))
        sidecar_for(report3b, _agreement(0.75, 8))
        code, text = _run(clean_env, report_path=str(report3b), env_file_arg=str(empty_env))
        assert code == 0 and "cost.amount=0" in text, (code, text)
        # priced 但无 token 用量：amount 仍 null，如实区分成因
        report3c = _write_json(room / "report3c.json", _report(
            INDEPENDENT, cost_note="priced: eval.pricing.* inputPer1k=0.01, outputPer1k=0.02, currency=CNY，"
                                   "但本次没有记录任何 token 用量，无可折算金额"))
        sidecar_for(report3c, _agreement(0.75, 8))
        code, text = _run(clean_env, report_path=str(report3c), env_file_arg=str(empty_env))
        assert code == 1 and "没有 token 用量记录" in text, (code, text)

        # ── fixture 4：全通过（报告冻结记录 false + sidecar κ 达标翻盘）→ 退出 0 ──
        report4 = _write_json(room / "report4.json", _report(INDEPENDENT, cost_amount=0.88))
        sidecar_for(report4, _agreement(0.71, 6))
        code, text = _run(clean_env, report_path=str(report4), env_file_arg=str(empty_env))
        assert code == 0, (code, text)
        assert "releaseEligible: true" in text, text
        assert "config.releaseEligible=false" in text, text   # 冻结记录原样呈现，不被改写
        assert "kappa 未计算" in text, text                   # 冻结里的 kappaNote 原样可见
        assert "原始报告文件冻结不改" in text, text

        # ── 结构性缺字段 → 退出 2 "该报告不能用于发布判定"，不默认 PASS ──
        for name, broken in (
            ("no-config.json", {"runId": RUN_ID, "cost": {"amount": 1.0}}),
            ("no-independence.json", {"runId": RUN_ID, "config": {"topK": 5}, "cost": {"amount": 1.0}}),
            ("no-cost.json", {"runId": RUN_ID, "config": _config(INDEPENDENT)}),
            # v1 报告形态：有 config 但没有 judgeIndependence / cost
            ("v1-shape.json", {"runId": "abc", "config": {"topK": 5, "llm": "deepseek/deepseek-v4-pro"},
                               "overall": {}}),
            ("wrapped-empty.json", {"code": 200, "message": "ok", "data": {"runId": RUN_ID}}),
        ):
            _, code, text = run_report(name, broken)
            assert code == 2, (name, code, text)
            assert "不能用于发布判定" in text, (name, text)
        # 非法 JSON / 文件缺失 / 显式 --sidecar 与 --env-file 不存在 → 2
        bad = room / "bad.json"
        bad.write_text("{not json", encoding="utf-8")
        code, text = _run(clean_env, report_path=str(bad))
        assert code == 2 and "不是合法 JSON" in text, (code, text)
        code, text = _run(clean_env, report_path=str(room / "ghost.json"))
        assert code == 2 and "文件不可读" in text, (code, text)
        code, text = _run(clean_env, report_path=str(report),
                          sidecar_arg=str(room / "ghost.human-reviews.json"))
        assert code == 2 and "--sidecar 指定的文件不存在" in text, (code, text)
        code, text = _run(clean_env, report_path=str(report), env_file_arg=str(room / "no-such.env"))
        assert code == 2 and "--env-file 指定的文件不存在" in text, (code, text)

        # ── sidecar runId 绑定：跨轮 sidecar 判 FAIL；没有 agreement 块判 FAIL ──
        report5 = _write_json(room / "report5.json", _report(INDEPENDENT, cost_amount=1.0))
        sidecar_for(report5, _agreement(0.9, 8), run_id="other-run")
        code, text = _run(clean_env, report_path=str(report5), env_file_arg=str(empty_env))
        assert code == 1 and "runId=other-run" in text and "不一致" in text, (code, text)
        _write_json(sidecar_path_for(report5),
                    _sidecar(_agreement(0.9, 8), run_id=RUN_ID, report_file="report5.json"))
        code, text = _run(clean_env, report_path=str(report5), env_file_arg=str(empty_env))
        assert code == 0, (code, text)
        _write_json(sidecar_path_for(report5), {"runId": RUN_ID, "reportFile": "report5.json", "reviews": {}})
        code, text = _run(clean_env, report_path=str(report5), env_file_arg=str(empty_env))
        assert code == 1 and "没有 agreement 块" in text, (code, text)
        # reportFile 改名（runId 一致）→ 不拦，只警告
        sidecar_for(report5, _agreement(0.9, 8), run_id=RUN_ID, report_file="old-name.json")
        code, text = _run(clean_env, report_path=str(report5), env_file_arg=str(empty_env))
        assert code == 0 and "警告: sidecar.reportFile=old-name.json" in text, (code, text)

        # ── 阈值来源与优先级 ──
        # a) sidecar 记录 minComparableCases=8 优先于缺省 5：6 条 → FAIL
        report6 = _write_json(room / "report6.json", _report(INDEPENDENT, cost_amount=1.0))
        sidecar_for(report6, _agreement(0.9, 6, min_comparable=8))
        code, text = _run(clean_env, report_path=str(report6), env_file_arg=str(empty_env))
        assert code == 1 and "少于下限 8 条" in text and "来源: sidecar agreement 记录值" in text, (code, text)
        # b) 记录缺失时用 env 文件阈值：min-kappa=0.9，κ=0.71 → FAIL
        strict_env = room / "strict.env"
        strict_env.write_text("EVAL_HUMAN_AGREEMENT_MIN_KAPPA=0.9\n", encoding="utf-8")
        report7 = _write_json(room / "report7.json", _report(INDEPENDENT, cost_amount=1.0))
        sidecar_for(report7, _agreement(0.71, 10, with_keys=False))
        code, text = _run(clean_env, report_path=str(report7), env_file_arg=str(strict_env))
        assert code == 1 and "0.710 低于下限 0.9" in text and "env 文件" in text, (code, text)
        # c) env 值为空 = 停用 κ 检查：κ=null 但样本量够 → 通过（其余门禁照常）
        disable_env = room / "disable.env"
        disable_env.write_text("EVAL_HUMAN_AGREEMENT_MIN_KAPPA=\n", encoding="utf-8")
        report8 = _write_json(room / "report8.json", _report(INDEPENDENT, cost_amount=1.0))
        sidecar_for(report8, _agreement(None, 7, with_keys=False))
        code, text = _run(clean_env, report_path=str(report8), env_file_arg=str(disable_env))
        assert code == 0 and "κ 阈值已停用" in text, (code, text)
        # d) κ 检查启用而 κ=null（无定义）→ FAIL
        report9 = _write_json(room / "report9.json", _report(INDEPENDENT, cost_amount=1.0))
        sidecar_for(report9, _agreement(None, 7, with_keys=False))
        code, text = _run(clean_env, report_path=str(report9), env_file_arg=str(empty_env))
        assert code == 1 and "κ 未计算/无定义" in text, (code, text)
        # e) comparableCases 缺失时回退 sampleSize（任务书命名口径），样本不足照样拦
        report10 = _write_json(room / "report10.json", _report(INDEPENDENT, cost_amount=1.0))
        agreement10 = _agreement(0.8, 3, with_keys=False)
        agreement10.pop("comparableCases")
        agreement10["sampleSize"] = 3
        sidecar_for(report10, agreement10)
        code, text = _run(clean_env, report_path=str(report10), env_file_arg=str(empty_env))
        assert code == 1 and "可比样本 3 条（sampleSize）少于下限 5 条" in text, (code, text)

        # ── 报告内嵌 agreement 回退（API 响应保存成文件、无 sidecar）──
        embedded = _report(INDEPENDENT, cost_amount=1.0)
        embedded["humanReview"] = {"reviews": [], "agreement": _agreement(0.8, 9, with_keys=False)}
        code, text = _run(clean_env, report_path=str(_write_json(room / "report11.json", embedded)),
                          env_file_arg=str(empty_env))
        assert code == 0 and "humanReview.agreement（报告内嵌" in text, (code, text)

        # ── 报告缺 runId：不崩，打警告（sidecar 无法绑定校验时如实说明）──
        no_run_id = _report(INDEPENDENT, cost_amount=1.0)
        no_run_id.pop("runId")
        report12 = _write_json(room / "report12.json", no_run_id)
        sidecar_for(report12, _agreement(0.8, 9))
        code, text = _run(clean_env, report_path=str(report12), env_file_arg=str(empty_env))
        assert code == 0 and "报告缺 runId" in text and "警告" in text, (code, text)

    # ── 纯函数断言（不落盘）──
    assert as_number(True) is None and as_number(0) == 0.0 and as_number("x") is None
    assert as_int(True) is None and as_int(5) == 5 and as_int(5.0) is None
    assert as_text("  ") is None and as_text(" INDEPENDENT ") == "INDEPENDENT"
    assert fmt_frozen(True) == "true" and fmt_frozen(None) == "null" and fmt_frozen(False) == "false"
    assert sidecar_path_for(Path("/x/eval-reports/a_b_c.json")) == Path("/x/eval-reports/a_b_c.human-reviews.json")
    assert sidecar_path_for(Path("/x/no-ext")) == Path("/x/no-ext.human-reviews.json")
    assert check_judge_independence({"judgeIndependence": "INDEPENDENT"}) == []
    assert check_judge_independence({"judgeIndependence": "self-judge"})[0][0] == R_SELF_JUDGE
    assert check_judge_independence({})[0][0] == R_SELF_JUDGE   # 空值宁可误拦（结构缺字段另有 2 号出口兜底）
    assert check_pricing({"amount": 0.0})[0] == []
    assert check_pricing({"amount": None})[0][0][0] == R_UNPRICED
    assert check_pricing({"amount": True})[0][0][0] == R_UNPRICED
    assert evaluate(_config(INDEPENDENT), {"amount": 1.0}, _agreement(0.9, 6), (0.6, "s", 5, "s"),
                    RUN_ID, RUN_ID, None, None)["eligible"] is True
    merged = evaluate(_config(INDEPENDENT), {"amount": None}, _agreement(0.9, 6), (0.6, "s", 5, "s"),
                      RUN_ID, RUN_ID, None, None)
    assert merged["codes"] == [R_UNPRICED] and merged["eligible"] is False
    assert find_agreement({"agreement": {"a": 1}}) == ({"a": 1}, "agreement（sidecar 顶层）")
    assert find_agreement({}) == (None, None)
    assert read_env_file(Path("/nonexistent.env")) == {}
    # 阈值解析纯函数：记录值 > env > 缺省；env 空=停用 κ
    assert resolve_thresholds({}, _agreement(0.9, 6, min_kappa=0.7), environ={})[0] == 0.7
    assert resolve_thresholds({}, _agreement(0.9, 6, with_keys=False), environ={},
                              file_values={ENV_MIN_KAPPA: "0.8"})[0] == 0.8
    kappa_thr, src, cases, _ = resolve_thresholds({}, _agreement(0.9, 6, with_keys=False),
                                                  environ={"EVAL_HUMAN_AGREEMENT_MIN_KAPPA": ""}, file_values={})
    assert kappa_thr is None and "显式停用" in src
    assert resolve_thresholds({}, None, environ={}, file_values={})[0] == 0.6
    assert resolve_thresholds({}, None, environ={}, file_values={})[2] == 5
    assert resolve_thresholds({}, None, environ={"EVAL_HUMAN_AGREEMENT_MIN_CASES": "7"},
                              file_values={})[2] == 7

    print("self-test OK: 发布条件预检（3 道门禁 / 4 组场景 fixture + 结构缺字段与阈值来源断言）")
    print("  - fixture1 SELF_JUDGE+缺 sidecar+未配价 → 1，原因含 SELF_JUDGE/无人工判定/未配价与可执行修复步骤")
    print("  - fixture2 κ 0.42 → 1（0.6 恰等不算低）；fixture3 未配价 → 1 且 env 值不打印；fixture4 报告冻结 false + sidecar κ 达标 → 0")
    print("  - 结构缺字段（含 v1 报告形态）→ 2「该报告不能用于发布判定」，不默认 PASS；报告文件全程未被改动")
    print("  - 阈值来源 sidecar 记录 > config 记录 > env/env-file > 缺省；env 置空=停用 κ；runId 绑定与内嵌 agreement 回退")
    return 0


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="发布条件预检：合并报告 config + sidecar agreement，判定评委独立性/人工 κ/定价三道门禁"
                    "（详见模块 docstring 与 docs/eval/release-checklist.md）")
    parser.add_argument("--report", help="v2 评测报告 JSON（落盘报告或报告接口响应均可）")
    parser.add_argument("--sidecar", help="人工判定旁路文件（缺省: 同目录 {报告名}.human-reviews.json）")
    parser.add_argument("--env-file", dest="env_file",
                        help="阈值/配价环境变量来源文件（缺省: $EVAL_ENV_FILE，再缺省仓库根 .env）")
    parser.add_argument("--self-test", dest="self_test", action="store_true",
                        help="内置 fixture 自检：4 组场景断言退出码与原因，离线、不读真实报告")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    if args.self_test and (args.report or args.sidecar):
        parser.error("--self-test 与 --report/--sidecar 只能二选一")
    if args.self_test:
        return self_test()
    if not args.report:
        parser.print_help(sys.stderr)
        return 2
    try:
        return run_preflight(args.report, sidecar_arg=args.sidecar, env_file_arg=args.env_file)
    except PreflightError as error:
        print(f"eval_release_preflight: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
