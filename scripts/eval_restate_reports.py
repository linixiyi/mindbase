#!/usr/bin/env python3
"""按 v2 统计口径重述 v1 归档评测报告（Wilson 95% CI）。

从 docs/eval/reports/*.json 的逐例 judgeCorrect 重算评委准确率的 Wilson 95% 区间，
与 docs/eval/reports/README-v2.md 的重述表断言一致（--check）。本脚本只读归档，不写任何文件。

数字来源：Java com.mindbase.service.eval.EvalStatistics CLI（唯一事实源，见该类 javadoc——
Python 侧只做重算编排与表格比对，不自产统计数字，与 scripts/eval_gate.sh 同一约定）。

口径：
- successes = cases[] 中 judgeCorrect=true 的条数；trials = judgeCorrect 可判（非空）的条数；
  输出失败（failed=true）的用例没有评委判定，不进分母——与归档 overall.judgeAccuracy 同口径，
  脚本逐轮断言重算点估计与归档汇总一致。
- 归档字段缺失（无 cases 或没有任何 judgeCorrect）的轮次记「该轮无判别口径」，不编造数字。

用法：
  python3 scripts/eval_restate_reports.py            # 打印重算表与 04/05 结论
  python3 scripts/eval_restate_reports.py --check    # 断言 README-v2.md 与重算一致，不一致退出非 0
  可选 --reports-dir / --doc 覆盖默认路径（缺字段路径的自测用）。
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORTS_DIR = os.path.join(REPO, "docs", "eval", "reports")
DOC = os.path.join(REPO, "docs", "eval", "reports", "README-v2.md")
STATS_SRC = os.path.join(REPO, "src", "main", "java", "com", "mindbase", "service", "eval", "EvalStatistics.java")
STATS_CLASS = os.path.join(REPO, "target", "classes", "com", "mindbase", "service", "eval", "EvalStatistics.class")

NO_BASIS = "该轮无判别口径"
# README-v2 的 CI 保留 1 位小数，正确舍入误差 ≤0.05pp；超出即视为表格与重算不一致
CI_TOL_PP = 0.051
# 重算点估计与归档 overall.judgeAccuracy 的一致性容差（归档存 4 位小数）
POINT_TOL = 5e-5
# 04/05 改写结论必须出现的措辞（tasks.md 成功判定的原文）
RESTATE_PHRASES = ("差 1 例", "区间重叠", "不可区分")


def ensure_statistics_class():
    """EvalStatistics.class 缺失或比源码旧时先编译（数字唯一事实源必须可用）。"""
    if os.path.exists(STATS_CLASS) and (
        not os.path.exists(STATS_SRC) or os.path.getmtime(STATS_CLASS) >= os.path.getmtime(STATS_SRC)
    ):
        return
    mvn = "./mvnw" if os.access(os.path.join(REPO, "mvnw"), os.X_OK) else "mvn"
    log_path = os.path.join(tempfile.gettempdir(), f"restate-compile-{os.getpid()}.log")
    print(f"==> EvalStatistics.class 缺失或过期，先编译（{mvn} -q -B -DskipTests compile，日志 {log_path}）")
    with open(log_path, "w", encoding="utf-8") as log:
        proc = subprocess.run(
            [mvn, "-q", "-B", "-DskipTests", "compile"],
            cwd=REPO, stdout=log, stderr=subprocess.STDOUT,
        )
    if proc.returncode != 0:
        with open(log_path, encoding="utf-8") as log:
            sys.exit(f"!! 编译失败（exit {proc.returncode}），mvn 输出尾部：\n" + "\n".join(log.readlines()[-40:]))


def java_wilson(successes, trials):
    """调 EvalStatistics CLI 算 Wilson 95% 区间，返回 (lower, upper)（0~1 小数）。"""
    proc = subprocess.run(
        ["java", "-cp", os.path.join(REPO, "target", "classes"),
         "com.mindbase.service.eval.EvalStatistics", "wilson", str(successes), str(trials)],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        sys.exit(f"!! EvalStatistics CLI 失败（exit {proc.returncode}）：{proc.stderr.strip()}")
    ci = json.loads(proc.stdout)
    return ci["lower"], ci["upper"]


def judge_counts(archive):
    """(successes, trials, failed)；无判别口径时返回 None，不猜数字。"""
    cases = archive.get("cases")
    if not isinstance(cases, list) or not cases:
        return None
    judged = [c for c in cases if isinstance(c, dict) and c.get("judgeCorrect") is not None]
    if not judged:
        return None
    successes = sum(1 for c in judged if c.get("judgeCorrect") is True)
    failed = sum(1 for c in cases if isinstance(c, dict) and c.get("failed") is True)
    return successes, len(judged), failed


def restate(reports_dir):
    """逐份归档重算，返回按文件名排序的行列表。"""
    rows = []
    for path in sorted(glob.glob(os.path.join(reports_dir, "*.json"))):
        name = os.path.basename(path)
        with open(path, encoding="utf-8") as f:
            archive = json.load(f)
        row = {"file": name, "label": archive.get("label", ""), "note": archive.get("note", "")}
        counts = judge_counts(archive)
        if counts is None:
            row["no_basis"] = True
            rows.append(row)
            continue
        successes, trials, failed = counts
        row.update(successes=successes, trials=trials, failed=failed, no_basis=False)
        archived_acc = (archive.get("overall") or {}).get("judgeAccuracy")
        row["archived_acc"] = archived_acc
        lower, upper = java_wilson(successes, trials)
        row.update(lower=lower * 100.0, upper=upper * 100.0, point=successes / trials * 100.0)
        rows.append(row)
    return rows


def fmt_row(row):
    if row["no_basis"]:
        return f"| `{row['file']}` | {NO_BASIS} | {NO_BASIS} | {NO_BASIS} | {NO_BASIS} |"
    return (
        f"| `{row['file']}` | {row['successes']}/{row['trials']} | {row['point']:.1f}% "
        f"| [{row['lower']:.1f}%, {row['upper']:.1f}%] | {row['failed']} |"
    )


def print_table(rows):
    print("从归档重算的评委准确率 Wilson 95% CI（数字来自 EvalStatistics CLI，失败用例不进分母）：")
    print("| 轮次 | 判对/可判 | 点估计 | Wilson 95% CI | 失败 |")
    print("| --- | --- | --- | --- | --- |")
    for row in rows:
        print(fmt_row(row))


def find_row(rows, prefix):
    matches = [r for r in rows if r["file"].startswith(prefix)]
    if len(matches) != 1:
        sys.exit(f"!! 归档目录里 {prefix}* 应恰好一份，实际 {len(matches)} 份")
    return matches[0]


def check_doc(doc_path, rows):
    """断言 README-v2.md 的表格与重算一致；返回问题列表（空 = 通过）。"""
    problems = []
    with open(doc_path, encoding="utf-8") as f:
        lines = f.read().splitlines()

    table_lines = {}  # file -> line
    for line in lines:
        if ".json" not in line or not line.lstrip().startswith("|"):
            continue
        for row in rows:
            if row["file"] in line:
                if row["file"] in table_lines:
                    problems.append(f"文档中 {row['file']} 出现在多行表格里")
                table_lines[row["file"]] = line
    for row in rows:
        if row["file"] not in table_lines:
            problems.append(f"文档表格缺少 {row['file']} 的行")

    for row in rows:
        line = table_lines.get(row["file"])
        if line is None:
            continue
        if row["no_basis"]:
            if NO_BASIS not in line or re.search(r"\[[\d.]+%,\s*[\d.]+%\]", line):
                problems.append(f"{row['file']} 无判别口径，但文档行不是「{NO_BASIS}」：{line.strip()}")
            continue
        m = re.search(r"(\d+)\s*/\s*(\d+)", line)
        if not m or (int(m.group(1)), int(m.group(2))) != (row["successes"], row["trials"]):
            problems.append(f"{row['file']} 判对/可判与重算不一致：文档 {m.groups() if m else '缺失'}，重算 {row['successes']}/{row['trials']}")
        m = re.search(r"\[([\d.]+)%,\s*([\d.]+)%\]", line)
        if not m:
            problems.append(f"{row['file']} 行缺 Wilson CI：{line.strip()}")
        else:
            lo, hi = float(m.group(1)), float(m.group(2))
            if abs(lo - row["lower"]) > CI_TOL_PP or abs(hi - row["upper"]) > CI_TOL_PP:
                problems.append(
                    f"{row['file']} CI 与重算不一致：文档 [{lo}, {hi}]，重算 "
                    f"[{row['lower']:.4f}, {row['upper']:.4f}]（容差 {CI_TOL_PP}pp）"
                )
        m = re.search(r"(\d+)\s*\|\s*$", line)
        if not m or int(m.group(1)) != row["failed"]:
            problems.append(f"{row['file']} 失败数与重算不一致：文档 {m.group(1) if m else '缺失'}，重算 {row['failed']}")
        if row.get("archived_acc") is not None:
            if abs(row["successes"] / row["trials"] - row["archived_acc"]) > POINT_TOL:
                problems.append(
                    f"{row['file']} 逐例重算 {row['successes']}/{row['trials']} 与归档 overall.judgeAccuracy"
                    f"={row['archived_acc']} 不一致，归档内部有矛盾，先核对归档再重述"
                )

    text = "\n".join(lines)
    for phrase in RESTATE_PHRASES:
        if phrase not in text:
            problems.append(f"文档缺「04 回归 / 05 修复」改写措辞：{phrase}")
    return problems


def check_0405(rows):
    """断言 04/05 改写结论在归档上成立：判对数差 1 例且区间重叠。"""
    r4 = find_row(rows, "04_")
    r5 = find_row(rows, "05_")
    problems = []
    for r in (r4, r5):
        if r["no_basis"]:
            problems.append(f"{r['file']} 无判别口径，04/05 对比结论无法从归档成立")
    if problems:
        return problems
    if abs(r5["successes"] - r4["successes"]) != 1:
        problems.append(
            f"04/05 判对数差 {abs(r5['successes'] - r4['successes'])} 例（"
            f"{r4['successes']}/{r4['trials']} vs {r5['successes']}/{r5['trials']}），「差 1 例」的改写不成立"
        )
    overlap = r4["lower"] <= r5["upper"] and r5["lower"] <= r4["upper"]
    if not overlap:
        problems.append(
            f"04/05 区间 [{r4['lower']:.1f}, {r4['upper']:.1f}] 与 [{r5['lower']:.1f}, {r5['upper']:.1f}] 不重叠，"
            "「区间重叠，不可区分」的改写不成立"
        )
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="断言 README-v2.md 与重算一致")
    parser.add_argument("--reports-dir", default=REPORTS_DIR, help="归档目录（默认 docs/eval/reports）")
    parser.add_argument("--doc", default=DOC, help="重述文档（默认 docs/eval/reports/README-v2.md）")
    args = parser.parse_args()

    if not os.path.isdir(args.reports_dir):
        sys.exit(f"!! 归档目录不存在：{args.reports_dir}")
    archives = glob.glob(os.path.join(args.reports_dir, "*.json"))
    if not archives:
        sys.exit(f"!! 归档目录没有 JSON：{args.reports_dir}")

    ensure_statistics_class()
    rows = restate(args.reports_dir)
    print_table(rows)
    for row in rows:
        if row["no_basis"]:
            print(f"!! {row['file']}：归档缺逐例判定字段，{NO_BASIS}，不编造数字")

    r4 = find_row(rows, "04_")
    r5 = find_row(rows, "05_")
    if r4["no_basis"] or r5["no_basis"]:
        print(f"04/05：{NO_BASIS}")
    else:
        print(
            f"04 vs 05：判对数差 {abs(r5['successes'] - r4['successes'])} 例"
            f"（{r4['successes']}/{r4['trials']} vs {r5['successes']}/{r5['trials']}），"
            f"区间 [{r4['lower']:.1f}%, {r4['upper']:.1f}%] 与 [{r5['lower']:.1f}%, {r5['upper']:.1f}%] 重叠"
            f"→ 不可区分"
        )

    if not args.check:
        return

    if not os.path.exists(args.doc):
        sys.exit(f"!! 重述文档不存在：{args.doc}")
    problems = check_doc(args.doc, rows) + check_0405(rows)
    if problems:
        for problem in problems:
            print(f"!! {problem}", file=sys.stderr)
        sys.exit(f"!! restate --check：{len(problems)} 处不一致")
    print(f"restate --check: PASS（{len(rows)} 份归档重算与 {os.path.relpath(args.doc, REPO)} 表格一致，"
          "04/05 改写结论在归档上成立）")


if __name__ == "__main__":
    main()
