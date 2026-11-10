#!/usr/bin/env python3
"""阈值曲线：从 v2 评测报告的逐例分块分数（cases[].retrieved[].score 与 relevant）重算
0.05–0.35 阈值网格下的检索指标，回答"报告阈值 0.1 的命中里有多少在 0.2 会塌掉"。

只读消费 RagEvalService 产出的 v2 报告，本身不发起评测、不产生任何评测数字。

用法：
  python3 scripts/eval_threshold_curve.py report.json [report.json ...]  # 每份报告一张阈值曲线 markdown 表
  python3 scripts/eval_threshold_curve.py --self-test                    # 内置 fixture：复刻 v1 已知的
                                                                         # "0.2→0.1 有 13 题从空召回变命中"，
                                                                         # 断言 0.1→0.2 方向 13 题塌陷与翻转计数

指标口径（与 Java 侧对齐）：
- 阈值过滤与线上一致：分块留存条件为 score >= 阈值（与 HybridSearchService.java:231 的 rerank 截断
  同式）。报告明细只含过了报告当时阈值（config.rerankScoreThreshold，通常 0.1）的分块，因此更低档位
  不会冒出新分块，0.05–0.10 行通常与报告阈值行持平；塌陷信息集中在更高的档位上。
- hit（命中）：阈值后仍存在 relevant=true 的分块。chunkR@5 = labeled 用例中命中的比例，与报告
  recallAtK 同口径（对账：在报告阈值一档重算 v2 报告应等于 summary.recallAtK，自检里验证）。
- labeled 分母：retrievalHit 非 null 的用例（与报告 labeledCases 一致）；检索失败（retrievalHit=null）
  与未标注用例不进分母。
- 全命中率：labeled 中"每份期望文档阈值后仍留有 relevant 分块"的比例。期望文档取
  expectedFileMd5s/expectedFileNames（md5 与文件名按下标配对，缺一边按另一边单独成项，与 Java
  expectedDocCoverage 的逐项计数一致；两字段都空时退化为"报告明细里出现过 relevant 分块的文件"）。
  注意：报告 summary 的 fullCoverageHitRate 对标注 expectedEvidence 的多证据题按短语级覆盖判定，
  而逐例明细只有分块级 relevant 标记、不含"分块命中了哪条短语"，短语级覆盖无法离线重算——本列按
  文档级口径，单文档题两口径一致，多证据题可能高于报告值（自检里有对账与差值说明）。
- 空召回数：阈值后没有任何分块留存的用例数。按全部用例口径（含检索失败用例），与报告
  emptyRetrieval 的 retrievedCount=0 口径一致。
- 平均命中数：labeled 用例平均留存的 relevant 分块数（未命中计 0）。
- 翻转题数：本档相对上一档命中状态发生变化的 labeled 用例数。阈值网格升序、分数固定，命中只会
  塌陷（hit→miss），故翻转数即塌陷数；首档无上一档，输出 "-"。

解析共用 scripts/eval_compare.py 的 load_report / normalize_report（同一套报告读取与形态归一），
不引第三方库。旧 v1 / 脱敏归档报告没有逐例 retrieved 明细（只有汇总指标与 rerankScores 数组），
无法重算阈值曲线：输出"该报告无分数字段"并退出 2，不做兼容猜测——尤其不拿 v1 的 rerankScores
数组冒充逐例分数（那是重排分数列表，没有分块级 relevant 标注，算出来的曲线是假的）。

退出码：0 成功（含 --self-test）；1 报告读取/解析失败；2 报告无逐例分数字段。
"""
import argparse
import contextlib
import io
import json
import os
import sys
import tempfile

import eval_compare
from eval_compare import load_report, normalize_report

# 阈值网格：0.05–0.35，步长 0.05（任务固定口径）。round 消除 0.05*i 的浮点尾差，
# 保证与报告 JSON 分数的等值比较稳定（如 0.15 >= 0.15、0.20 >= 0.20）。
THRESHOLD_LOW = 0.05
THRESHOLD_HIGH = 0.35
THRESHOLD_STEP = 0.05
THRESHOLDS = [round(i * THRESHOLD_STEP, 2) for i in range(1, 8)]


class MissingScoreError(ValueError):
    """报告缺逐例分块分数（v1/归档形态，或明细缺 score）——明确报错，不做兼容猜测。"""


def _is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _lower(value):
    return str(value).strip().lower() if value is not None else None


def case_chunks(case):
    """归一化后的逐例检索明细（dict 列表）；没有明细返回空列表。"""
    chunks = case.get("retrieved")
    if not isinstance(chunks, list):
        return []
    return [chunk for chunk in chunks if isinstance(chunk, dict)]


# ---------------------------------------------------------------------------
# 逐例分数校验：缺分数明确报错，不做兼容猜测
# ---------------------------------------------------------------------------

def validate_scored_cases(report):
    """阈值曲线的前提是逐例 retrieved[].score。缺失时抛 MissingScoreError（"该报告无分数字段"）。"""
    source = report["source"]
    scored_cases = 0
    broken = []
    for case in report["cases"]:
        chunks = case.get("retrieved")
        usable = [chunk for chunk in chunks if isinstance(chunk, dict)] if isinstance(chunks, list) else []
        if usable:
            if all(_is_number(chunk.get("score")) for chunk in usable):
                scored_cases += 1
            else:
                broken.append(str(case.get("caseId")))
        elif (case.get("retrievedCount") or 0) > 0:
            # 声称检索到条目却没有逐例明细：v1 / 脱敏归档形态
            broken.append(str(case.get("caseId")))
    if broken:
        preview = ", ".join(broken[:5]) + ("…" if len(broken) > 5 else "")
        raise MissingScoreError(
            f"该报告无分数字段：{source} 有 {len(broken)} 个用例有检索条目但缺逐例 retrieved[].score"
            f"（如 {preview}）——v1/归档报告只有汇总指标与 rerankScores 数组，无法重算阈值曲线，"
            "不做兼容猜测；请改用 v2 报告（schemaVersion 2.0，逐例含 retrieved 明细）。")
    if scored_cases == 0:
        raise MissingScoreError(
            f"该报告无分数字段：{source}（schema {report['schema']}）的 cases 里没有任何逐例 "
            "retrieved[].score 分块分数——v1/归档报告只有汇总指标与 rerankScores 数组，无法重算"
            "阈值曲线，不做兼容猜测；请改用 v2 报告（schemaVersion 2.0，逐例含 retrieved 明细）。")


# ---------------------------------------------------------------------------
# 阈值网格下的逐例状态与指标
# ---------------------------------------------------------------------------

def case_state(case, threshold):
    """阈值后该用例的检索状态：留存分块数、其中 relevant 分块数、是否命中。"""
    surviving = [chunk for chunk in case_chunks(case)
                 if _is_number(chunk.get("score")) and chunk["score"] >= threshold]
    relevant_hits = [chunk for chunk in surviving if chunk.get("relevant") is True]
    return {"surviving": len(surviving), "hits": len(relevant_hits), "hit": bool(relevant_hits)}


def expected_docs(case):
    """期望文档身份 (md5, name) 列表：md5 与文件名按下标配对（expectedChunkIds 同一平行约定，
    见 RetrievalMetrics.expectedChunkIdFor）；长度不齐或缺失时按各自单独成项（与 Java 侧
    expectedDocCoverage 的逐项计数一致）。"""
    md5s = [_lower(m) for m in (case.get("expectedFileMd5s") or [])]
    names = [_lower(n) for n in (case.get("expectedFileNames") or [])]
    md5s = [m for m in md5s if m]
    names = [n for n in names if n]
    if md5s and len(md5s) == len(names):
        return list(zip(md5s, names))
    return [(m, None) for m in md5s] + [(None, n) for n in names]


def case_full_hit(case, threshold):
    """全命中（文档级口径）：每份期望文档阈值后仍留有至少一个 relevant 分块。

    短语级覆盖（报告 fullCoverageHitRate 对多证据题的口径）离线不可重算：逐例明细只有分块级
    relevant 标记，不含"分块命中了哪条短语"；本函数按文档级近似，单文档题与命中判定一致。
    """
    surviving_relevant = [chunk for chunk in case_chunks(case)
                          if _is_number(chunk.get("score")) and chunk["score"] >= threshold
                          and chunk.get("relevant") is True]
    docs = expected_docs(case)
    if not docs:
        # 用例没写期望文档：退化为"报告明细里出现过 relevant 分块的文件都必须仍留有 relevant 分块"
        seen = set()
        for chunk in case_chunks(case):
            if chunk.get("relevant") is not True:
                continue
            identity = (_lower(chunk.get("fileMd5")), _lower(chunk.get("fileName")))
            if identity != (None, None):
                seen.add(identity)
        docs = sorted(seen)
    if not docs:
        return False
    for expected_md5, expected_name in docs:
        covered = False
        for chunk in surviving_relevant:
            chunk_md5 = _lower(chunk.get("fileMd5"))
            chunk_name = _lower(chunk.get("fileName"))
            if (expected_md5 and chunk_md5 == expected_md5) or (expected_name and chunk_name == expected_name):
                covered = True
                break
        if not covered:
            return False
    return True


def threshold_curve(report):
    """计算 0.05–0.35 阈值网格曲线。返回逐档指标行与逐用例命中/留存矩阵（复核与自检用）。

    缺逐例分数时抛 MissingScoreError，不做兼容猜测。
    """
    validate_scored_cases(report)
    cases = report["cases"]
    labeled = [case for case in cases if case.get("retrievalHit") is not None]
    labeled_ids = [str(case.get("caseId")) for case in labeled]

    rows = []
    hit_matrix = {}        # labeled 用例：caseId -> [hit@每档]
    surviving_matrix = {}  # 全部用例：caseId -> [留存分块数@每档]
    prev_labeled_states = None
    for threshold in THRESHOLDS:
        all_states = {str(case.get("caseId")): case_state(case, threshold) for case in cases}
        labeled_states = [all_states[case_id] for case_id in labeled_ids]
        n = len(labeled_ids)
        hits = sum(1 for state in labeled_states if state["hit"])
        full_hits = sum(1 for case, case_id in zip(labeled, labeled_ids)
                        if case_full_hit(case, threshold))
        empty = sum(1 for state in all_states.values() if state["surviving"] == 0)
        if prev_labeled_states is None:
            flips = None  # 首档无上一档
        else:
            flips = sum(1 for prev, curr in zip(prev_labeled_states, labeled_states)
                        if prev["hit"] != curr["hit"])
        rows.append({
            "threshold": threshold,
            "chunkR5": (hits / n) if n else None,
            "fullHit": (full_hits / n) if n else None,
            "empty": empty,
            "avgHits": (sum(state["hits"] for state in labeled_states) / n) if n else None,
            "flips": flips,
            "hits": hits,
        })
        for case_id, state in zip(labeled_ids, labeled_states):
            hit_matrix.setdefault(case_id, []).append(state["hit"])
        for case_id, state in all_states.items():
            surviving_matrix.setdefault(case_id, []).append(state["surviving"])
        prev_labeled_states = labeled_states
    return {
        "rows": rows,
        "labeled_n": len(labeled),
        "total_n": len(cases),
        "labeled_ids": labeled_ids,
        "hit_matrix": hit_matrix,
        "surviving_matrix": surviving_matrix,
    }


# ---------------------------------------------------------------------------
# 展示（markdown 表）
# ---------------------------------------------------------------------------

def _pct(value):
    return "-" if value is None else f"{value * 100:.1f}%"


def _avg(value):
    return "-" if value is None else f"{value:.2f}"


def render_curve(report, curve):
    config = report["config"] or {}
    report_threshold = config.get("rerankScoreThreshold")
    top_k = report.get("topK") if report.get("topK") is not None else "-"
    lines = [
        f"## 阈值曲线：{report['label']} [schema {report['schema']}]"
        f"（报告阈值 {report_threshold}，topK {top_k}，labeled {curve['labeled_n']}/{curve['total_n']}）",
        "",
        "| 阈值 | chunkR@5 | 全命中率 | 空召回数 | 平均命中数 | 翻转题数 |",
        "|-----:|---------:|---------:|---------:|-----------:|---------:|",
    ]
    for row in curve["rows"]:
        flips = "-" if row["flips"] is None else row["flips"]
        lines.append(f"| {row['threshold']:.2f} | {_pct(row['chunkR5'])} | {_pct(row['fullHit'])} "
                     f"| {row['empty']} | {_avg(row['avgHits'])} | {flips} |")
    lines += [
        "",
        f"口径：hit = 阈值后仍有 relevant 分块（score >= 阈值，与 HybridSearchService 的 rerank 截断同式）；"
        f"分母 labeled（retrievalHit 非 null）= {curve['labeled_n']}；空召回数按全部用例（含检索失败）；"
        "平均命中数 = labeled 平均留存 relevant 分块数；翻转题数 = 相对上一档命中状态变化"
        "（升阈值只会塌陷，hit→miss）。",
        f"注：报告明细只含过报告阈值（{report_threshold}）的分块，更低档位不会冒出新分块；"
        "全命中率为期望文档级口径（每份期望文档仍留有 relevant 分块），报告 fullCoverageHitRate 的"
        "多证据短语级覆盖离线不可重算（逐例只存分块级 relevant），单文档题两口径一致。",
    ]
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# --self-test：内置 fixture 断言 0.1→0.2 的 13 题塌陷与翻转计数
# ---------------------------------------------------------------------------

def _curve_fixture_report():
    """复刻 v1 已知现象的 v2 形态 fixture（真实 v1 报告没有逐例分数，只能按已知结论构造）：

    - 13 个 band 用例：全部分块 score∈[0.1,0.2)——0.1 档命中、0.2 档空召回，即 v1 观察到的
      "0.2→0.1 有 13 题从空召回变命中"的逆向（0.1→0.2 有 13 题塌陷）；
    - 6 个 solid 用例（relevant 分块 0.30）与两个压线用例（0.20/0.10 恰好等值，各带一条 0.40 的
      无关噪声分块）钉死 >= 语义与"有留存但未命中"的区分；
    - 1 个双期望文档用例：0.15 档丢掉其中一个文档，命中仍在但全命中率塌，钉死全命中率与
      chunkR@5 的分叉；
    - 1 个检索失败用例（retrieved=[]）与 1 个未标注用例，钉死分母与空召回口径。

    全表 7 档数字为手工设计并逐格钉死（见 self_test 的 expected 表）。
    """
    cases = []

    def chunk(rank, md5, name, chunk_id, score, relevant):
        return {"rank": rank, "fileMd5": md5, "fileName": name, "chunkId": chunk_id,
                "score": score, "relevant": relevant}

    for i in range(1, 14):
        case_id = f"band-{i:02d}"
        cases.append({
            "caseId": case_id, "type": "fact",
            "expectedFileMd5s": [f"md5-{case_id}"], "expectedFileNames": [f"{case_id}.pdf"],
            "expectedEvidence": ["证据"],
            "retrievalMode": "HYBRID", "retrievalHit": True, "docHit": True,
            "diagnosis": "OK",
            "retrieved": [
                chunk(1, f"md5-{case_id}", f"{case_id}.pdf", 1, 0.15, True),
                chunk(2, f"md5-noise-{i:02d}", f"noise-{i:02d}.pdf", 1, 0.18, False),
                chunk(3, f"md5-noise-{i:02d}", f"noise-{i:02d}.pdf", 2, 0.12, False),
            ],
        })
    for i in range(1, 7):
        case_id = f"solid-{i:02d}"
        cases.append({
            "caseId": case_id, "type": "fact",
            "expectedFileMd5s": [f"md5-{case_id}"], "expectedFileNames": [f"{case_id}.pdf"],
            "expectedEvidence": ["证据"],
            "retrievalMode": "HYBRID", "retrievalHit": True, "docHit": True,
            "diagnosis": "OK",
            "retrieved": [
                chunk(1, f"md5-{case_id}", f"{case_id}.pdf", 1, 0.30, True),
                chunk(2, f"md5-noise-{i:02d}", f"noise-{i:02d}.pdf", 1, 0.12, False),
            ],
        })
    cases.append({
        "caseId": "exact-020", "type": "fact",
        "expectedFileMd5s": ["md5-exact020"], "expectedFileNames": ["exact-020.pdf"],
        "expectedEvidence": ["证据"],
        "retrievalMode": "HYBRID", "retrievalHit": True, "docHit": True, "diagnosis": "OK",
        "retrieved": [
            chunk(1, "md5-exact020", "exact-020.pdf", 1, 0.20, True),
            chunk(2, "md5-noise-e20", "noise-e20.pdf", 1, 0.40, False),
        ],
    })
    cases.append({
        "caseId": "exact-010", "type": "fact",
        "expectedFileMd5s": ["md5-exact010"], "expectedFileNames": ["exact-010.pdf"],
        "expectedEvidence": ["证据"],
        "retrievalMode": "HYBRID", "retrievalHit": True, "docHit": True, "diagnosis": "OK",
        "retrieved": [
            chunk(1, "md5-exact010", "exact-010.pdf", 1, 0.10, True),
            chunk(2, "md5-noise-e10", "noise-e10.pdf", 1, 0.40, False),
        ],
    })
    cases.append({
        "caseId": "multi-doc", "type": "multi",
        "expectedFileMd5s": ["md5-a", "md5-b"], "expectedFileNames": ["a.pdf", "b.pdf"],
        "expectedEvidence": [],
        "retrievalMode": "HYBRID", "retrievalHit": True, "docHit": True, "diagnosis": "OK",
        "retrieved": [
            chunk(1, "md5-a", "a.pdf", 1, 0.25, True),
            chunk(2, "md5-b", "b.pdf", 1, 0.12, True),
            chunk(3, "md5-noise", "noise.pdf", 1, 0.40, False),
        ],
    })
    cases.append({
        "caseId": "failed-01", "type": "fact",
        "expectedFileMd5s": ["md5-failed"], "expectedFileNames": ["failed.pdf"],
        "expectedEvidence": ["证据"],
        "retrievalMode": "NONE", "retrievalHit": None, "docHit": None,
        "diagnosis": "RETRIEVAL_FAILED", "error": "检索失败: es down",
        "retrieved": [],
    })
    cases.append({
        "caseId": "neg-01", "type": "neg",
        "expectedFileMd5s": [], "expectedFileNames": [], "expectedEvidence": [],
        "retrievalMode": "HYBRID", "retrievalHit": None, "docHit": None,
        "diagnosis": "NOT_LABELED",
        "retrieved": [chunk(1, "md5-x", "x.pdf", 1, 0.50, False)],
    })
    return {
        "runId": "threshold-curve-selftest",
        "label": "threshold-curve-selftest",
        "schemaVersion": "2.0",
        "topK": 5,
        "config": {"topK": 5, "rerankScoreThreshold": 0.1},
        "summary": {},
        "cases": cases,
    }


def _write_temp_json(payload):
    fd, path = tempfile.mkstemp(suffix=".json", prefix="threshold-curve-selftest-")
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(payload, handle)
    return path


def _expect_error(report_source, raw):
    """断言该报告形态触发 MissingScoreError（"该报告无分数字段"），不做兼容猜测。"""
    normalized = normalize_report(raw, source=report_source)
    try:
        threshold_curve(normalized)
    except MissingScoreError as error:
        assert "该报告无分数字段" in str(error), str(error)
        return error
    raise AssertionError(f"{report_source} 缺逐例分数必须报错，而不是猜一条曲线")


def self_test():
    assert THRESHOLDS == [0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35], THRESHOLDS

    def close(actual, expected):
        return actual is not None and abs(actual - expected) < 1e-9

    # 1) 内置 fixture：全表 7 档逐格钉死（R@5、全命中率、空召回数、平均命中数、翻转题数）
    report = normalize_report(_curve_fixture_report(), source="fixture-curve")
    curve = threshold_curve(report)
    assert curve["total_n"] == 24 and curve["labeled_n"] == 22, (curve["total_n"], curve["labeled_n"])
    idx = {t: i for i, t in enumerate(THRESHOLDS)}
    expected_rows = [
        # (阈值, chunkR@5, 全命中率, 空召回数, 平均命中数, 翻转题数)
        # 全命中率按文档级口径：multi-doc 自 0.15 档起命中仍在但 b.pdf 塌掉 → 不再全命中，
        # 因此 0.20/0.25 档全命中率比 chunkR@5 各少一个 multi-doc
        (0.05, 22 / 22, 22 / 22, 1, 23 / 22, None),
        (0.10, 22 / 22, 22 / 22, 1, 23 / 22, 0),
        (0.15, 21 / 22, 20 / 22, 1, 21 / 22, 1),
        (0.20, 8 / 22, 7 / 22, 14, 8 / 22, 13),
        (0.25, 7 / 22, 6 / 22, 14, 7 / 22, 1),
        (0.30, 6 / 22, 6 / 22, 14, 6 / 22, 1),
        (0.35, 0 / 22, 0 / 22, 20, 0 / 22, 6),
    ]
    for row, (t, r5, full, empty, avg, flips) in zip(curve["rows"], expected_rows):
        assert row["threshold"] == t, (row, t)
        assert close(row["chunkR5"], r5), (t, row)
        assert close(row["fullHit"], full), (t, row)
        assert row["empty"] == empty, (t, row)
        assert close(row["avgHits"], avg), (t, row)
        assert row["flips"] == flips, (t, row)

    # 2) 0.1→0.2 的 13 题塌陷：相邻档（0.15→0.20）翻转恰为 13 个 band 用例，全部 hit→miss 且
    #    0.2 档空召回；反方向（0.2→0.1）即 v1 已知现象"13 题从空召回变命中"（空召回差=13）。
    #    注意 exact-010 的命中在 0.15 档已塌（翻转计入 0.15 行），0.2 档不空召回（噪声 0.40 留存）
    #    ——"命中消失"与"空召回"是两个口径，14 次命中消失 vs 13 个空召回
    band_ids = {f"band-{i:02d}" for i in range(1, 14)}
    collapsed = {case_id for case_id, hits in curve["hit_matrix"].items()
                 if hits[idx[0.15]] and not hits[idx[0.20]]}
    assert collapsed == band_ids, sorted(collapsed)
    for case_id in band_ids:
        assert curve["surviving_matrix"][case_id][idx[0.20]] == 0, case_id  # 塌陷即空召回
        assert curve["surviving_matrix"][case_id][idx[0.10]] == 3, case_id
    assert curve["rows"][idx[0.20]]["flips"] == 13
    assert curve["rows"][idx[0.15]]["flips"] == 1
    assert curve["rows"][idx[0.10]]["hits"] - curve["rows"][idx[0.20]]["hits"] == 14
    assert curve["rows"][idx[0.20]]["empty"] - curve["rows"][idx[0.10]]["empty"] == 13  # v1 口径
    story = {case_id for case_id in band_ids
             if curve["surviving_matrix"][case_id][idx[0.20]] == 0
             and curve["hit_matrix"][case_id][idx[0.10]]}
    assert len(story) == 13

    # 3) >= 边界语义（与 HybridSearchService.java:231 同式）：压线分块在等值档位留存
    assert curve["hit_matrix"]["exact-020"][idx[0.20]] is True
    assert curve["hit_matrix"]["exact-020"][idx[0.25]] is False
    assert curve["hit_matrix"]["exact-010"][idx[0.10]] is True
    assert curve["hit_matrix"]["exact-010"][idx[0.15]] is False
    # 命中塌了但噪声分块（0.40）仍留存：未命中 ≠ 空召回
    assert curve["surviving_matrix"]["exact-010"][idx[0.15]] == 1
    assert curve["hit_matrix"]["band-01"][idx[0.15]] is True  # 0.15 >= 0.15 留存，塌陷发生在 0.20 档
    assert curve["hit_matrix"]["band-01"][idx[0.10]] is True

    # 4) 全命中率（文档级）与 chunkR@5 的分叉：multi-doc 在 0.15 档仍命中（a.pdf 0.25 留存）
    #    但 b.pdf（0.12）塌掉 → 命中在、全命中塌
    multi_case = next(case for case in report["cases"] if case["caseId"] == "multi-doc")
    assert case_full_hit(multi_case, 0.10) is True
    assert case_full_hit(multi_case, 0.15) is False
    assert curve["hit_matrix"]["multi-doc"][idx[0.15]] is True
    assert close(curve["rows"][idx[0.15]]["fullHit"], 20 / 22)
    assert close(curve["rows"][idx[0.15]]["chunkR5"], 21 / 22)

    # 5) 渲染：markdown 表头与关键行存在
    rendered = render_curve(report, curve)
    for fragment in ("| 阈值 | chunkR@5 | 全命中率 | 空召回数 | 平均命中数 | 翻转题数 |",
                     "| 0.20 |", "| 0.35 |", "口径：hit"):
        assert fragment in rendered, fragment
    assert "该报告无分数字段" not in rendered

    # 6) 真实 v2 结构对账：eval_compare 的 smoke fixture（Java EvalSmokeFixtureTest 真跑产出的
    #    报告，20 题、分块分数全 0.9）在报告阈值档重算应等于报告记录的 recallAtK
    v2 = normalize_report(eval_compare._fixtures()["v2"], source="fixture-v2")
    v2_curve = threshold_curve(v2)
    assert v2_curve["labeled_n"] == 19, v2_curve["labeled_n"]
    v2_row_at_report_threshold = v2_curve["rows"][idx[0.10]]
    assert abs(v2_row_at_report_threshold["chunkR5"] - v2["summary"]["recallAtK"]) < 1e-3, \
        (v2_row_at_report_threshold["chunkR5"], v2["summary"]["recallAtK"])
    # 分数全 0.9：0.35 档以内无任何塌陷；空召回只有检索失败用例（retrieved=[]）
    assert all(row["flips"] in (0, None) for row in v2_curve["rows"])
    assert all(row["empty"] == 1 for row in v2_curve["rows"])
    # 文档级全命中 18/19 高于报告短语级 fullCoverageHitRate 16/19：multi-04/multi-05 双证据题
    # 按文档级口径算全命中（口径分叉已在 docstring 与表格注记说明，不是同一数字不冒充）
    assert close(v2_row_at_report_threshold["fullHit"], 18 / 19)
    assert abs(v2["summary"]["fullCoverageHitRate"] - 16 / 19) < 1e-3

    # 7) v1 / 归档 / 部分缺分数：明确报"该报告无分数字段"，不做兼容猜测
    fixtures = eval_compare._fixtures()
    _expect_error("fixture-v1", fixtures["v1"])
    _expect_error("fixture-archive", eval_compare._to_archive_shape(fixtures["v1"]))
    partial = {
        "label": "partial", "schemaVersion": "2.0", "topK": 5, "config": {}, "summary": {},
        "cases": [
            {"caseId": "ok-01", "retrievalHit": True, "retrievedCount": 1,
             "retrieved": [{"rank": 1, "fileMd5": "m", "fileName": "f.pdf", "chunkId": 1,
                            "score": 0.30, "relevant": True}]},
            {"caseId": "broken-01", "retrievalHit": True, "retrievedCount": 3},
        ],
    }
    error = _expect_error("fixture-partial", partial)
    assert "broken-01" in str(error), str(error)

    # 8) 退出码契约：v1 报告走 main() 退出 2，说明输出到 stderr
    v1_path = _write_temp_json(fixtures["v1"])
    try:
        out_io, err_io = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out_io), contextlib.redirect_stderr(err_io):
            code = main([v1_path])
        assert code == 2, code
        assert "该报告无分数字段" in err_io.getvalue(), err_io.getvalue()
    finally:
        os.unlink(v1_path)

    print("self-test OK: 阈值网格 0.05–0.35（步长 0.05，7 档）")
    print("  - 内置 fixture 复刻 v1 现象：0.1→0.2 有 13 题塌陷（hit→miss 且全部变空召回），翻转计数=13；")
    print("    反方向 0.2→0.1 即已知的“13 题从空召回变命中”")
    print("  - 压线分数钉死 score >= 阈值语义；双文档用例钉死全命中率（文档级）与 chunkR@5 的分叉")
    print("  - 真实 v2 smoke fixture 对账：报告阈值档重算 chunkR@5 = 18/19 = 94.7% == 报告 recallAtK；")
    print("    文档级全命中 18/19 高于报告短语级 fullCoverageHitRate 16/19（multi-04/05 双证据题，口径注记已说明）")
    print("  - v1 / 归档 / 部分缺分数报告：明确报“该报告无分数字段”并退出 2，不做兼容猜测")
    return 0


# ---------------------------------------------------------------------------

def main(argv):
    parser = argparse.ArgumentParser(
        description="阈值曲线：从 v2 报告逐例分块分数重算 0.05–0.35 阈值网格（详见模块 docstring）")
    parser.add_argument("reports", nargs="*",
                        help="v2 评测报告 JSON 路径（逐例含 retrieved[].score/relevant）")
    parser.add_argument("--self-test", dest="self_test", action="store_true",
                        help="用内置 fixture 断言 0.1→0.2 的 13 题塌陷与翻转计数")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.reports:
        parser.error("至少给一份 v2 报告路径，或使用 --self-test")

    sections = []
    for path in args.reports:
        report = load_report(path)
        try:
            curve = threshold_curve(report)
        except MissingScoreError as error:
            print(str(error), file=sys.stderr)
            return 2
        sections.append(render_curve(report, curve))
    print("\n\n".join(sections))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"eval_threshold_curve: {error}", file=sys.stderr)
        sys.exit(1)
