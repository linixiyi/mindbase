#!/usr/bin/env python3
"""评测数据集结构审计：静态检查 eval-v2.jsonl 与 eval-datasets/manifest.json 的 gold 标注质量。

spec 2026-11-10_fix-eval-and-runtime-gaps task-0012（C12）：126 题从未做结构审计；重复题、
空 evidence、未知文件名、md5 缺口只能等运行到那一题才炸。本脚本在跑评测前把数据集静态扫一遍，
按 error / warn 分级报出：error = 会让解析炸掉或让该题不可评分；warn = 能跑但会让指标失真，
需要人工确认。只做静态检查，不发网络请求、不启动任何服务、不产生评测数字。

用法（在仓库根执行）：
    python3 scripts/eval_dataset_audit.py             # 等价 --check，审计默认数据集
    python3 scripts/eval_dataset_audit.py --check     # 同上（--check 为默认动作）
    python3 scripts/eval_dataset_audit.py --self-test # 内置 clean/dirty fixture 自检，不读仓库数据文件

检查清单与分级（依据 = Java 侧行为或数据集头注释）：

| 检查                          | 级别  | 依据 |
|-------------------------------|-------|------|
| JSON 解析失败                 | error | 行不是合法 JSON 对象；EvalDatasetParser.java:128-137 同样抛错 |
| id 重复                       | error | EvalDatasetParser.java:100-103 对重复 id 直接抛 IllegalArgumentException |
| 问题缺失                      | error | EvalDatasetParser.java:140-141 对缺 question 抛错 |
| split 非法                    | error | 只允许 dev|test；EvalDatasetParser.java:153-154 对非法值抛错；缺失同样报出（v2 数据集要求显式 split） |
| type 未知                     | error | type 词表见 eval-v2.jsonl 头注释第 9 行：fact/para/multi/neg/ocr；缺失或不在词表报出（解析器不校验 type，靠本审计兜底） |
| 空证据（非 neg）              | error | 非 neg 题没有 expectedEvidence → 证据短语覆盖口径不可评分 |
| 文件名为空（非 neg）          | error | 非 neg 题没有 expectedFileNames → 无 gold 文档，文档级覆盖不可评分 |
| 未知文件                      | error | expectedFileNames 引用了 manifest 没有的文档——manifest 缺条目报出而不是跳过 |
| md5 缺失                      | error | 有 expectedFileNames 但 expectedFileMd5s 为空或数量不齐（task-0003 的平行契约） |
| 答案为空（非 neg）            | error | answer 与 referenceAnswer 都为空 → 评委没有参考答案 |
| neg 缺 referenceAnswer        | error | EvalDatasetParser.java:139-144 对所有用例（含 neg）强制要求 referenceAnswer，缺失整份数据集解析失败 |
| 问题重复                      | warn  | NFKC 归一化去空白后相同；可能是变体题，人工确认（解析器不查） |
| neg 题带 evidence             | warn  | neg 题（type=neg 或 expectedRefusal=true）带 expectedEvidence 会进 evidenceLabeled 分母并按证据短语判相关，污染覆盖率口径（当前数据集 17 条全部如此，故为 warn 不阻断） |
| md5 与 manifest 不一致        | warn  | case 的 md5 与 manifest 条目不同 → MD5 匹配静默失效，退回文件名匹配（解析器不查，只有本审计能发现） |
| manifest 条目异常             | warn  | manifest 文档条目缺 name 或 md5 |

neg 题定义：type == "neg" 或 expectedRefusal == true（v2 词表中两者应当一致，取并集防漏报；
数据集头注释第 5 行：负样本 expectedRefusal=true 且 neg- 前缀）。

"answer" 字段口径：任务卡写 "answer"，实际数据集统一用 referenceAnswer（126/126），
Java 侧 EvalCase.referenceAnswer 同名。因此检查 answer 与 referenceAnswer 两者取先非空者。

退出码：0 成功（--check 无 error、仅 warn 或完全干净；--self-test 通过）；
1 --check 存在 error；2 用法或数据文件级错误（路径不存在 / manifest 或数据集整体不可解析 / 零条用例）。

只依赖标准库，单文件，不引第三方包。
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import re
import sys
import tempfile
import unicodedata
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = REPO_ROOT / "src" / "main" / "resources" / "eval" / "eval-v2.jsonl"
DEFAULT_MANIFEST = REPO_ROOT / "eval-datasets" / "manifest.json"

VALID_SPLITS = {"dev", "test"}
# type 词表：eval-v2.jsonl 头注释第 9 行（fact=单点事实；para=同义改写/理解；multi=多跳/跨条目；
# neg=应拒答；ocr=扫描件占位）。Java 解析器不校验 type，词表以数据集自身文档为准。
KNOWN_TYPES = {"fact", "para", "multi", "neg", "ocr"}

# 检查名常量：self-test 与 markdown 输出都引用同一套字符串，防止漂移
CHECK_JSON = "JSON 解析失败"
CHECK_ID_DUP = "id 重复"
CHECK_QUESTION_DUP = "问题重复"
CHECK_QUESTION_MISSING = "问题缺失"
CHECK_SPLIT = "split 非法"
CHECK_TYPE = "type 未知"
CHECK_EVIDENCE_EMPTY = "空证据"
CHECK_FILENAMES_EMPTY = "文件名为空"
CHECK_UNKNOWN_FILE = "未知文件"
CHECK_MD5_MISSING = "md5 缺失"
CHECK_MD5_MISMATCH = "md5 与 manifest 不一致"
CHECK_ANSWER_EMPTY = "答案为空"
CHECK_NEG_MISSING_REF = "neg 缺 referenceAnswer"
CHECK_NEG_EVIDENCE = "neg 题带 evidence"
CHECK_MANIFEST_ENTRY = "manifest 条目异常"

ERROR_CHECKS = {
    CHECK_JSON, CHECK_ID_DUP, CHECK_QUESTION_MISSING, CHECK_SPLIT, CHECK_TYPE,
    CHECK_EVIDENCE_EMPTY, CHECK_FILENAMES_EMPTY, CHECK_UNKNOWN_FILE, CHECK_MD5_MISSING,
    CHECK_ANSWER_EMPTY, CHECK_NEG_MISSING_REF,
}
WARN_CHECKS = {
    CHECK_QUESTION_DUP, CHECK_NEG_EVIDENCE, CHECK_MD5_MISMATCH, CHECK_MANIFEST_ENTRY,
}


class DataError(Exception):
    """数据文件级错误（路径不存在 / 整体不可解析 / 零条用例）——退出 2，不属于逐条 findings。"""


def _finding(level: str, check: str, location: str, cases_label: str, message: str,
             sort_line: int) -> dict:
    return {"level": level, "check": check, "location": location,
            "cases": cases_label, "message": message, "sort_line": sort_line}


def normalize_question(text: str) -> str:
    """NFKC 归一化后去除所有空白字符（空格/制表/换行/全角空格等 isspace 字符）。"""
    return "".join(ch for ch in unicodedata.normalize("NFKC", text or "") if not ch.isspace())


def as_list(value) -> list:
    """与 Java EvalDatasetParser.strings / eval_backfill_gold.as_list 同口径：
    字符串视作单元素列表，逐项去空白、丢空项；其余形态（缺键/数字/对象）按空列表。"""
    if value is None:
        return []
    if isinstance(value, str):
        return [value.strip()] if value.strip() else []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    return []


def is_neg(case: dict) -> bool:
    """neg 题 = type=neg 或 expectedRefusal=true（取并集防漏报，见模块 docstring）。"""
    type_value = case.get("type")
    if isinstance(type_value, str) and type_value.strip() == "neg":
        return True
    return case.get("expectedRefusal") is True


def answer_text(case: dict):
    """答案文本：answer 字段优先，回退 referenceAnswer（数据集实际字段，见 docstring）。"""
    for key in ("answer", "referenceAnswer"):
        value = case.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return None


def load_manifest(path: Path):
    """读 manifest.json → (文件名 -> md5 或 None, 条目级 warn findings)。文件级错误抛 DataError。"""
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise DataError(f"manifest 不可读: {path}: {exc}") from exc
    try:
        manifest = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise DataError(f"manifest 不是合法 JSON: {path}: {exc}") from exc
    if not isinstance(manifest, dict):
        raise DataError(f"manifest 顶层必须是 JSON 对象: {path}")
    documents = manifest.get("documents")
    if not isinstance(documents, list):
        raise DataError(f"manifest 缺 documents 数组: {path}")
    name_to_md5: dict = {}
    findings: list = []
    for index, doc in enumerate(documents):
        location = f"manifest documents[{index}]"
        if not isinstance(doc, dict):
            findings.append(_finding("warn", CHECK_MANIFEST_ENTRY, location, "-",
                                     "manifest 条目不是对象，该条目不参与文件名/md5 对照", 0))
            continue
        name = doc.get("name")
        name = name.strip() if isinstance(name, str) else ""
        md5 = doc.get("md5")
        md5 = md5.strip() if isinstance(md5, str) else ""
        if not name:
            findings.append(_finding("warn", CHECK_MANIFEST_ENTRY, location, "-",
                                     "manifest 条目缺 name，该条目不参与文件名/md5 对照", 0))
            continue
        if not md5:
            findings.append(_finding("warn", CHECK_MANIFEST_ENTRY, location, "-",
                                     f"manifest 条目缺 md5：“{name}”，引用它的用例无法做 md5 对照", 0))
            name_to_md5[name] = None
        else:
            name_to_md5[name] = md5
    return name_to_md5, findings


def load_cases(path: Path):
    """读数据集 JSONL → ([(行号, case dict)], 行级 error findings)。

    空行与 # / // 注释行跳过但保留物理行号（与 eval_backfill_gold 同口径，便于对回原文件）。
    单行 JSON 损坏记 error finding 后继续扫其余行；零条可审计用例抛 DataError。
    """
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise DataError(f"数据集不可读: {path}: {exc}") from exc
    entries: list = []
    findings: list = []
    total = 0
    for line_no, line in enumerate(raw.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("//"):
            continue
        try:
            case = json.loads(stripped)
        except json.JSONDecodeError as exc:
            findings.append(_finding("error", CHECK_JSON, f"行 {line_no}", f"行 {line_no}",
                                     f"该行不是合法 JSON: {exc.msg}（行级字段检查跳过）", line_no))
            continue
        if not isinstance(case, dict):
            findings.append(_finding("error", CHECK_JSON, f"行 {line_no}", f"行 {line_no}",
                                     "该行不是 JSON 对象（行级字段检查跳过）", line_no))
            continue
        total += 1
        entries.append((line_no, case))
    if total == 0:
        raise DataError(f"数据集为空（没有可审计的用例行，只处理 JSON 对象行，跳过 #/// 注释）: {path}")
    return entries, findings


def audit_cases(entries, name_to_md5, manifest_findings=None):
    """逐用例审计。entries = [(行号, case dict)]；name_to_md5 = 文件名 -> md5 或 None。

    返回 findings 列表（不排序；check() 负责合并排序）。每条 finding：
    {level, check, location, cases, message, sort_line}。
    """
    findings: list = list(manifest_findings or [])
    seen_ids: dict = {}
    seen_questions: dict = {}

    for line_no, case in entries:
        label = f"行 {line_no}"
        raw_id = case.get("id")
        case_id = raw_id.strip() if isinstance(raw_id, str) and raw_id.strip() else None
        if case_id:
            label = case_id
            seen_ids.setdefault(case_id, []).append((line_no, case_id))

        neg = is_neg(case)

        # -- 问题缺失 / 问题重复 --
        question = case.get("question")
        question_text = question.strip() if isinstance(question, str) else ""
        if not question_text:
            findings.append(_finding(
                "error", CHECK_QUESTION_MISSING, f"行 {line_no}", label,
                "缺 question（EvalDatasetParser.java:140-141 对缺 question 的行直接抛错）", line_no))
        else:
            key = normalize_question(question_text)
            seen_questions.setdefault(key, []).append((line_no, case_id or f"行 {line_no}"))

        # -- split 非法 --
        split_value = case.get("split")
        split_text = split_value.strip() if isinstance(split_value, str) else ""
        if not split_text:
            findings.append(_finding(
                "error", CHECK_SPLIT, f"行 {line_no}", label,
                "split 缺失（v2 数据集要求显式 split，只允许 dev|test；解析器对缺失按 legacy 降级容忍，审计按 v2 契约报出）",
                line_no))
        elif split_text not in VALID_SPLITS:
            findings.append(_finding(
                "error", CHECK_SPLIT, f"行 {line_no}", label,
                f"split 非法（只允许 dev|test）: {split_text}（EvalDatasetParser.java:153-154 会直接抛错）", line_no))

        # -- type 未知 --
        type_value = case.get("type")
        type_text = type_value.strip() if isinstance(type_value, str) else ""
        if not type_text:
            findings.append(_finding(
                "error", CHECK_TYPE, f"行 {line_no}", label,
                "type 缺失（词表：fact/para/multi/neg/ocr，见 eval-v2.jsonl 头注释第 9 行）", line_no))
        elif type_text not in KNOWN_TYPES:
            findings.append(_finding(
                "error", CHECK_TYPE, f"行 {line_no}", label,
                f"type 不在已知词表（fact/para/multi/neg/ocr）: {type_text}", line_no))

        evidence = as_list(case.get("expectedEvidence"))
        names = as_list(case.get("expectedFileNames"))
        md5s = as_list(case.get("expectedFileMd5s"))

        # -- neg / 非 neg 分支的 gold 完整性 --
        if neg:
            if evidence:
                findings.append(_finding(
                    "warn", CHECK_NEG_EVIDENCE, f"行 {line_no}", label,
                    "neg 题（type=neg 或 expectedRefusal=true）标注了 expectedEvidence：会进 evidenceLabeled "
                    "分母并按证据短语判相关，污染拒答题的覆盖率口径，建议清空或人工确认", line_no))
            reference = case.get("referenceAnswer")
            if not (isinstance(reference, str) and reference.strip()):
                findings.append(_finding(
                    "error", CHECK_NEG_MISSING_REF, f"行 {line_no}", label,
                    "neg 题缺 referenceAnswer（EvalDatasetParser.java:139-144 对所有用例强制要求该字段，"
                    "缺失会让整份数据集解析失败）", line_no))
        else:
            if not evidence:
                findings.append(_finding(
                    "error", CHECK_EVIDENCE_EMPTY, f"行 {line_no}", label,
                    "非 neg 题没有 expectedEvidence：证据短语覆盖口径不可评分", line_no))
            if not names:
                findings.append(_finding(
                    "error", CHECK_FILENAMES_EMPTY, f"行 {line_no}", label,
                    "非 neg 题没有 expectedFileNames：无 gold 文档，文档级覆盖不可评分", line_no))
            if answer_text(case) is None:
                findings.append(_finding(
                    "error", CHECK_ANSWER_EMPTY, f"行 {line_no}", label,
                    "答案为空：answer 与 referenceAnswer 都缺失（评委没有参考答案）", line_no))

        # -- 文件名 / md5 对照（neg 与非 neg 同规则：只要引用了文件就必须可对照） --
        for name in names:
            if name not in name_to_md5:
                findings.append(_finding(
                    "error", CHECK_UNKNOWN_FILE, f"行 {line_no}", label,
                    f"expectedFileNames 引用 “{name}”，manifest 无此条目（manifest 缺条目报出而不是跳过）", line_no))
        if names:
            if not md5s:
                findings.append(_finding(
                    "error", CHECK_MD5_MISSING, f"行 {line_no}", label,
                    f"expectedFileMd5s 缺失：标注了 {len(names)} 个 expectedFileNames 但 expectedFileMd5s 为空"
                    "（task-0003 契约要求与文件名平行补齐）", line_no))
            elif len(md5s) != len(names):
                findings.append(_finding(
                    "error", CHECK_MD5_MISSING, f"行 {line_no}", label,
                    f"expectedFileMd5s 缺失：{len(md5s)} 个与 expectedFileNames {len(names)} 个数量不齐", line_no))
            else:
                for name, md5 in zip(names, md5s):
                    manifest_md5 = name_to_md5.get(name)
                    if manifest_md5 is not None and md5 != manifest_md5:
                        findings.append(_finding(
                            "warn", CHECK_MD5_MISMATCH, f"行 {line_no}", label,
                            f"“{name}” 的 expectedFileMd5s={md5} 与 manifest={manifest_md5} 不一致："
                            "MD5 匹配会静默失效退回文件名匹配", line_no))

    # -- 分组 findings：id 重复 / 问题重复 --
    for value in sorted(seen_ids):
        hits = seen_ids[value]
        if len(hits) > 1:
            lines = [line for line, _ in hits]
            labels = [case_id for _, case_id in hits]
            findings.append(_finding(
                "error", CHECK_ID_DUP, "、".join(f"行 {line}" for line in lines), "、".join(labels),
                f"id “{value}” 被 {len(hits)} 条用例共用（EvalDatasetParser.java:100-103 解析时直接抛错）",
                min(lines)))
    for value in sorted(seen_questions):
        hits = seen_questions[value]
        if len(hits) > 1:
            lines = [line for line, _ in hits]
            labels = [case_id for _, case_id in hits]
            findings.append(_finding(
                "warn", CHECK_QUESTION_DUP, "、".join(f"行 {line}" for line in lines), "、".join(labels),
                f"问题 NFKC 归一化去空白后相同：“{value}”共 {len(hits)} 条，请人工确认是否变体题（解析器不查）",
                min(lines)))
    return findings


def sort_findings(findings):
    """稳定排序：manifest 条目（行 0）在最前，其后按 (物理行号, 检查名)。"""
    return sorted(findings, key=lambda f: (f["sort_line"], f["check"]))


def exit_code(findings) -> int:
    """error > 0 → 1；仅 warn 或干净 → 0。"""
    return 1 if any(f["level"] == "error" for f in findings) else 0


def render_audit(dataset_label: str, manifest_label: str, case_count: int, doc_count: int,
                 findings) -> str:
    """按 error/warn 分级渲染 markdown 表与计数。"""
    errors = [f for f in findings if f["level"] == "error"]
    warns = [f for f in findings if f["level"] == "warn"]
    lines = [
        "# 评测数据集审计（eval-v2）",
        "",
        "| 项 | 值 |",
        "|----|----|",
        f"| 数据集 | {dataset_label}（{case_count} 条用例） |",
        f"| manifest | {manifest_label}（{doc_count} 篇文档） |",
        f"| error | {len(errors)} |",
        f"| warn | {len(warns)} |",
        "",
    ]
    if errors:
        verdict = f"error {len(errors)} > 0 → 退出 1（error 会炸解析或让该题不可评分，先修 error 再跑评测）"
    elif warns:
        verdict = f"仅 warn（{len(warns)} 条）→ 退出 0（warn 不阻断，建议逐条人工确认）"
    else:
        verdict = "0 error / 0 warn → 退出 0，数据集结构干净"
    lines += [f"结论：{verdict}", ""]

    for level, group, title in (("error", errors, "error（会炸解析或不可评分）"),
                                ("warn", warns, "warn（能跑但指标失真，需人工确认）")):
        lines += [f"## {title}：{len(group)} 条", ""]
        if not group:
            lines += ["_（无）_", ""]
            continue
        lines += ["| # | 检查 | 位置 | 用例 | 说明 |", "|---:|------|------|------|------|"]
        for index, finding in enumerate(group, start=1):
            message = finding["message"].replace("|", "\\|")
            lines.append(f"| {index} | {finding['check']} | {finding['location']} "
                         f"| {finding['cases']} | {message} |")
        lines.append("")

    lines.append(f"计数：error {len(errors)} / warn {len(warns)}（用例 {case_count}，manifest 文档 {doc_count}）")
    return "\n".join(lines)


def check(dataset_arg: str, manifest_arg: str) -> int:
    """--check 主路径：读两份文件 → 审计 → markdown 输出 → 按分级给退出码。"""
    dataset_path = Path(dataset_arg)
    manifest_path = Path(manifest_arg)
    try:
        name_to_md5, manifest_findings = load_manifest(manifest_path)
        entries, line_findings = load_cases(dataset_path)
    except DataError as error:
        print(f"eval_dataset_audit: {error}", file=sys.stderr)
        return 2
    findings = sort_findings(line_findings + audit_cases(entries, name_to_md5, manifest_findings))
    print(render_audit(str(dataset_path), str(manifest_path), len(entries),
                       len(name_to_md5), findings))
    return exit_code(findings)


# ---------------------------------------------------------------------------
# --self-test：内置 clean / dirty 两份 fixture，断言 findings 数量与退出码
# ---------------------------------------------------------------------------

MD5_CLIP = "a" * 32
MD5_PARA = "b" * 32
CLEAN_NAME_TO_MD5 = {"clip.pdf": MD5_CLIP, "para.pdf": MD5_PARA}
DIRTY_NAME_TO_MD5 = {"clip.pdf": MD5_CLIP, "para.pdf": MD5_PARA, "nomd5.pdf": None}


def _valid_fact(case_id: str, question: str, line: int, **overrides) -> dict:
    """构造一条完全干净的 fact 用例，字段可按 overrides 覆盖（dirty fixture 的基底）。"""
    case = {
        "id": case_id,
        "question": question,
        "split": "dev",
        "type": "fact",
        "expectedRefusal": False,
        "expectedFileNames": ["clip.pdf"],
        "expectedFileMd5s": [MD5_CLIP],
        "expectedEvidence": ["锚点短语"],
        "referenceAnswer": "参考答案",
    }
    case.update(overrides)
    return case


def clean_fixture_entries():
    """干净 fixture：3 条用例覆盖三条主干形态，断言 0 findings。
    - fact：文件+md5+证据+参考答案齐全（dev）；
    - neg 带 trap 文档（文件+md5、无 evidence、有 referenceAnswer，test）；
    - neg 裸题（无文件/md5/evidence 键，仅 question+referenceAnswer，dev）。"""
    return [
        (4, _valid_fact("fact-01-a", "CLIP 的预训练数据规模是多少？", 4)),
        (5, {"id": "neg-01-a", "question": "CLIP 数据集采购花了多少钱？", "split": "test",
             "type": "neg", "expectedRefusal": True,
             "expectedFileNames": ["clip.pdf"], "expectedFileMd5s": [MD5_CLIP],
             "referenceAnswer": "论文未提及采购成本，应拒答"}),
        (6, {"id": "neg-02-a", "question": "CLIP 训练用了多少 GPU 时？", "split": "dev",
             "type": "neg", "expectedRefusal": True,
             "referenceAnswer": "语料无此信息，应拒答"}),
    ]


def dirty_fixture_entries():
    """脏 fixture：15 条用例，每条只踩一个检查，合计 10 error + 3 warn（+1 manifest 条目 warn，
    由 fixture 的 manifest 提供，见 dirty_fixture_manifest），断言 findings 总数=14 与退出码 1。"""
    return [
        (4, _valid_fact("dup-1", "问题甲的具体内容是什么？", 4)),
        # 5: id 重复（与行 4 共用 "dup-1"）→ 1 error
        (5, _valid_fact("dup-1", "问题乙的具体内容是什么？", 5)),
        # 6/7: 问题 NFKC 归一化去空白后相同 → 1 warn
        (6, _valid_fact("qdup-1", "ＣＬＩＰ　作者　是谁", 6)),
        (7, _valid_fact("qdup-2", "CLIP 作者\t是谁", 7)),
        # 8: 非 neg 空 evidence → 1 error
        (8, _valid_fact("no-ev", "预训练数据从哪里来？", 8, expectedEvidence=[])),
        # 9: 非 neg 空 expectedFileNames → 1 error
        (9, _valid_fact("no-file", "这段结论的同义改写是什么？", 9, type="para",
                        expectedFileNames=None, expectedFileMd5s=None)),
        # 10: 引用 manifest 没有的文件 → 1 error（md5 数量对齐所以不叠 md5 缺失）
        (10, _valid_fact("unknown-file", "幽灵文档讲了什么？", 10,
                         expectedFileNames=["ghost.pdf"], expectedFileMd5s=["0" * 32])),
        # 11: 有文件名但 md5 空 → 1 error
        (11, _valid_fact("no-md5", "这篇文章的结论是什么？", 11, expectedFileMd5s=[])),
        # 12: split 非法 → 1 error
        (12, _valid_fact("bad-split", "训练集里的问题怎么问？", 12, split="train")),
        # 13: type 不在词表 → 1 error
        (13, _valid_fact("bad-type", "这道 quiz 的答案是什么？", 13, type="quiz")),
        # 14: 非 neg 缺 answer/referenceAnswer → 1 error
        (14, _valid_fact("no-answer", "没有参考答案的问题怎么评？", 14,
                         referenceAnswer=None)),
        # 15: 缺 question → 1 error
        (15, _valid_fact("no-question", None, 15)),
        # 16: neg 题带 evidence → 1 warn
        (16, {"id": "neg-ev", "question": "语料里没有的采购成本是多少？", "split": "test",
              "type": "neg", "expectedRefusal": True, "expectedEvidence": ["陷阱短语"],
              "referenceAnswer": "应拒答"}),
        # 17: neg 缺 referenceAnswer → 1 error
        (17, {"id": "neg-no-ref", "question": "另一个应拒答的问题？", "split": "dev",
              "type": "neg", "expectedRefusal": True}),
        # 18: md5 与 manifest 不一致 → 1 warn
        (18, _valid_fact("md5-mismatch", "md5 对不上的文档说了什么？", 18,
                         expectedFileMd5s=["f" * 32])),
    ]


def dirty_fixture_manifest():
    """脏 fixture 的 manifest：多一条缺 md5 的条目（没有任何用例引用它）→ 1 条 manifest 条目异常 warn。"""
    return {"documents": [
        {"name": "clip.pdf", "md5": MD5_CLIP},
        {"name": "para.pdf", "md5": MD5_PARA},
        {"name": "nomd5.pdf"},
    ]}


def _write_fixture_dir(dataset_text: str, manifest: dict) -> str:
    """把 fixture 落到临时目录，返回目录路径（调用方负责清理）。"""
    directory = tempfile.mkdtemp(prefix="eval-dataset-audit-selftest-")
    (Path(directory) / "dataset.jsonl").write_text(dataset_text, encoding="utf-8")
    (Path(directory) / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
    return directory


def _entries_to_jsonl(entries) -> str:
    """fixture entries → 模拟真实文件形态（3 行 # 头注释 + 每条用例一行 JSON）。"""
    header = "# fixture 头注释 1\n# fixture 头注释 2\n# fixture 头注释 3\n"
    body = "\n".join(json.dumps(case, ensure_ascii=False) for _, case in entries)
    return header + body + "\n"


def _run_check(directory: str):
    """在临时目录上跑 check()，返回 (exit_code, stdout)。"""
    out_io = io.StringIO()
    with contextlib.redirect_stdout(out_io):
        code = check(str(Path(directory) / "dataset.jsonl"), str(Path(directory) / "manifest.json"))
    return code, out_io.getvalue()


def _run_check_dataset_only(dataset_text: str, manifest: dict):
    """只给数据集文本（manifest 正常），返回 (exit_code, stdout)。"""
    directory = tempfile.mkdtemp(prefix="eval-dataset-audit-selftest-")
    try:
        (Path(directory) / "dataset.jsonl").write_text(dataset_text, encoding="utf-8")
        (Path(directory) / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        return _run_check(directory)
    finally:
        import shutil
        shutil.rmtree(directory, ignore_errors=True)


def self_test() -> int:
    # 0) 归一化语义：NFKC 折叠全角 + 去全部空白
    assert normalize_question("ＣＬＩＰ　作者　是谁") == "CLIP作者是谁", \
        normalize_question("ＣＬＩＰ　作者　是谁")
    assert normalize_question("CLIP 作者\t是谁\n") == "CLIP作者是谁"
    assert normalize_question("ＣＬＩＰ　作者　是谁") == normalize_question("CLIP 作者\t是谁")

    # 1) clean fixture：0 findings，退出 0
    clean_findings = audit_cases(clean_fixture_entries(), CLEAN_NAME_TO_MD5, [])
    assert clean_findings == [], clean_findings
    clean_dir = _write_fixture_dir(_entries_to_jsonl(clean_fixture_entries()),
                                   {"documents": [{"name": "clip.pdf", "md5": MD5_CLIP},
                                                  {"name": "para.pdf", "md5": MD5_PARA}]})
    try:
        clean_code, clean_out = _run_check(clean_dir)
        assert clean_code == 0, (clean_code, clean_out)
        assert "0 error / 0 warn → 退出 0" in clean_out, clean_out
        assert "| error | 0 |" in clean_out and "| warn | 0 |" in clean_out, clean_out
    finally:
        import shutil
        shutil.rmtree(clean_dir, ignore_errors=True)

    # 2) dirty fixture：findings 总数 14 = 10 error + 4 warn（含 manifest 条目 warn），退出 1。
    #    注意 CHECK_JSON 不在 dirty fixture 里（由第 3 组单独断言），其余 14 个检查名各命中一条。
    dirty_dir = _write_fixture_dir(_entries_to_jsonl(dirty_fixture_entries()),
                                   dirty_fixture_manifest())
    try:
        dirty_code, dirty_out = _run_check(dirty_dir)
        assert dirty_code == 1, (dirty_code, dirty_out)
        for fragment in ("| # | 检查 | 位置 | 用例 | 说明 |",
                         "error 10 > 0 → 退出 1",
                         "计数：error 10 / warn 4（用例 15，manifest 文档 3）"):
            assert fragment in dirty_out, fragment
        for check_name in sorted((ERROR_CHECKS | WARN_CHECKS) - {CHECK_JSON}):
            assert check_name in dirty_out, check_name
    finally:
        import shutil
        shutil.rmtree(dirty_dir, ignore_errors=True)
    # 内存口径拿 findings 明细，断言数量、级别与检查名集合（一题一缺陷 → 每个检查恰好一条）
    directory = tempfile.mkdtemp(prefix="eval-dataset-audit-selftest-")
    try:
        (Path(directory) / "manifest.json").write_text(
            json.dumps(dirty_fixture_manifest(), ensure_ascii=False), encoding="utf-8")
        name_to_md5, manifest_findings = load_manifest(Path(directory) / "manifest.json")
    finally:
        import shutil
        shutil.rmtree(directory, ignore_errors=True)
    assert len(manifest_findings) == 1, manifest_findings
    assert manifest_findings[0]["check"] == CHECK_MANIFEST_ENTRY, manifest_findings
    dirty_findings = sort_findings(
        audit_cases(dirty_fixture_entries(), name_to_md5, manifest_findings))
    errors = [f for f in dirty_findings if f["level"] == "error"]
    warns = [f for f in dirty_findings if f["level"] == "warn"]
    assert len(dirty_findings) == 14, [(f["check"], f["location"]) for f in dirty_findings]
    assert len(errors) == 10 and len(warns) == 4, (len(errors), len(warns))
    assert {f["check"] for f in errors} == {
        CHECK_ID_DUP, CHECK_EVIDENCE_EMPTY, CHECK_FILENAMES_EMPTY, CHECK_UNKNOWN_FILE,
        CHECK_MD5_MISSING, CHECK_SPLIT, CHECK_TYPE, CHECK_ANSWER_EMPTY,
        CHECK_QUESTION_MISSING, CHECK_NEG_MISSING_REF,
    }, sorted({f["check"] for f in errors})
    assert {f["check"] for f in warns} == {
        CHECK_QUESTION_DUP, CHECK_NEG_EVIDENCE, CHECK_MD5_MISMATCH, CHECK_MANIFEST_ENTRY,
    }, sorted({f["check"] for f in warns})
    assert len({f["check"] for f in dirty_findings}) == len(dirty_findings), \
        [(f["check"], f["location"]) for f in dirty_findings]
    assert exit_code(dirty_findings) == 1

    # 3) 行级 JSON 损坏：error finding + 其余行继续审计 → 退出 1
    broken_text = (_entries_to_jsonl(clean_fixture_entries()[:1])
                   + '{"id": "broken"' + "\n")
    code, out = _run_check_dataset_only(broken_text,
                                        {"documents": [{"name": "clip.pdf", "md5": MD5_CLIP}]})
    assert code == 1, (code, out)
    assert CHECK_JSON in out and "不是合法 JSON" in out, out

    # 4) 文件级错误 → 退出 2：数据集不存在 / 数据集为空 / manifest 不存在（stderr 一并静默）
    def _expect_exit_2(dataset_path: str, manifest_path: str):
        out_io, err_io = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out_io), contextlib.redirect_stderr(err_io):
            code = check(dataset_path, manifest_path)
        assert code == 2, (code, err_io.getvalue())
        assert err_io.getvalue().startswith("eval_dataset_audit:"), err_io.getvalue()

    _expect_exit_2("/nonexistent/dataset.jsonl", "/nonexistent/manifest.json")
    directory = tempfile.mkdtemp(prefix="eval-dataset-audit-selftest-")
    try:
        (Path(directory) / "manifest.json").write_text(
            json.dumps({"documents": [{"name": "clip.pdf", "md5": MD5_CLIP}]}, ensure_ascii=False),
            encoding="utf-8")
        (Path(directory) / "dataset.jsonl").write_text("# 只有注释\n\n", encoding="utf-8")
        _expect_exit_2(str(Path(directory) / "dataset.jsonl"),
                       str(Path(directory) / "manifest.json"))
        (Path(directory) / "manifest.json").unlink()
        _expect_exit_2(str(Path(directory) / "dataset.jsonl"),
                       str(Path(directory) / "manifest.json"))
    finally:
        import shutil
        shutil.rmtree(directory, ignore_errors=True)

    # 5) 退出码契约：仅 warn → 0（neg 题带 evidence 不阻断）
    warn_only_entries = [
        (2, {"id": "neg-warn", "question": "应拒答的问题？", "split": "dev", "type": "neg",
             "expectedRefusal": True, "expectedEvidence": ["陷阱短语"],
             "referenceAnswer": "应拒答"}),
    ]
    warn_findings = audit_cases(warn_only_entries, CLEAN_NAME_TO_MD5, [])
    assert len(warn_findings) == 1 and warn_findings[0]["check"] == CHECK_NEG_EVIDENCE, warn_findings
    assert exit_code(warn_findings) == 0

    print(f"self-test OK: 评测数据集审计（共 {len(ERROR_CHECKS) + len(WARN_CHECKS)} 项检查："
          f"{len(ERROR_CHECKS)} error 级 + {len(WARN_CHECKS)} warn 级）")
    print("  - clean fixture（fact 全字段 / neg 带 trap 文档 / neg 裸题）：0 findings，退出 0")
    print("  - dirty fixture：14 findings = 10 error + 4 warn，退出 1；每个检查恰好命中一条，检查名全量断言")
    print("  - NFKC 归一化去空白把全角/制表符问题折叠为同一问题指纹；行级 JSON 损坏记 error 后继续扫")
    print("  - 文件级错误（数据集/manifest 不存在、数据集为空）退出 2；仅 warn（neg+evidence）退出 0")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="评测数据集结构审计（详见模块 docstring）：--check 默认，--self-test 自检")
    parser.add_argument("--check", action="store_true",
                        help="审计 src/main/resources/eval/eval-v2.jsonl 与 eval-datasets/manifest.json（默认动作）")
    parser.add_argument("--self-test", dest="self_test", action="store_true",
                        help="内置 clean/dirty fixture 自检，断言 findings 数量与退出码，不读仓库数据文件")
    parser.add_argument("--dataset", default=str(DEFAULT_DATASET),
                        help=f"评测数据集 JSONL 路径（默认 {DEFAULT_DATASET}）")
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST),
                        help=f"语料清单 manifest.json 路径（默认 {DEFAULT_MANIFEST}）")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    if args.self_test and args.check:
        parser.error("--check 与 --self-test 只能二选一")
    if args.self_test:
        return self_test()
    return check(args.dataset, args.manifest)


if __name__ == "__main__":
    sys.exit(main())
