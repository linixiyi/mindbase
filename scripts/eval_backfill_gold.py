#!/usr/bin/env python3
"""按 eval-datasets/manifest.json 给评测数据集回填文件级 gold md5（expectedFileMd5s）。

背景（spec 2026-11-10_fix-eval-and-runtime-gaps task-0003）：eval-v2.jsonl 的 gold 只有
expectedFileNames + expectedEvidence，没有任何 expectedFileMd5s；本脚本读语料清单
manifest.json（documents[].name -> md5），按 expectedFileNames 平行补齐 expectedFileMd5s，
让检索指标可以优先按 MD5 匹配期望文档。

用法（在仓库根执行）：
    python3 scripts/eval_backfill_gold.py --check    # 幂等校验：已补齐且与 manifest 一致则退出 0
    python3 scripts/eval_backfill_gold.py --write    # 落盘回填（幂等：已补齐则不重写）
    python3 scripts/eval_backfill_gold.py            # 缺省动作等价 --check

规则：
- expectedFileMd5s 与 expectedFileNames 平行（第 i 个 md5 属于第 i 个文件）；
- manifest 缺映射（无此文档 / 条目缺 md5 / md5 非 32 位十六进制）时打印缺失文件名并退出非 0：
  --check 只报出；--write 跳过该用例（其 gold md5 保持为 null，绝不猜测或用文件名哈希顶替），
  其余可解析用例照常回填；
- 空行与 # / // 注释行原样保留，其余每行一个 JSON 对象，键序保持，expectedFileMd5s 写在
  expectedFileNames 之前；
- 退出码：0 成功 / 1 存在缺映射或未回填用例 / 2 用法或数据格式错误。

只依赖标准库。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = REPO_ROOT / "src" / "main" / "resources" / "eval" / "eval-v2.jsonl"
DEFAULT_MANIFEST = REPO_ROOT / "eval-datasets" / "manifest.json"

MD5_PATTERN = re.compile(r"^[0-9a-f]{32}$")


def as_list(value) -> list[str]:
    """与 Java 侧 EvalDatasetParser.strings 同口径：字符串视作单元素列表，去空白、丢空项。"""
    if value is None:
        return []
    if isinstance(value, str):
        return [value.strip()] if value.strip() else []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    return []


def load_manifest(path: Path) -> tuple[dict[str, str | None], list[str]]:
    """返回 文件名 -> md5（缺 md5 记 None），以及缺映射文件名列表（"name: 原因"）。"""
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise SystemExit(f"manifest 不存在: {path}")
    except json.JSONDecodeError as exc:
        raise SystemExit(f"manifest 不是合法 JSON: {path}: {exc}")
    if not isinstance(manifest, dict):
        raise SystemExit(f"manifest 顶层必须是 JSON 对象: {path}")
    name_to_md5: dict[str, str | None] = {}
    broken: list[str] = []
    for doc in manifest.get("documents") or []:
        if not isinstance(doc, dict):
            continue
        name = str(doc.get("name", "")).strip()
        if not name:
            continue
        md5 = doc.get("md5")
        md5 = md5.strip() if isinstance(md5, str) else None
        if md5 is None:
            name_to_md5[name] = None
            broken.append(f"{name}: manifest 条目缺 md5")
        elif not MD5_PATTERN.match(md5):
            name_to_md5[name] = None
            broken.append(f"{name}: manifest md5 不是 32 位十六进制（{md5}），拒绝采信")
        else:
            name_to_md5[name] = md5
    return name_to_md5, broken


def desired_md5s(names: list[str], name_to_md5: dict[str, str | None]) -> tuple[list[str | None], list[str]]:
    """按文件名解析期望 md5；缺映射的位置记 None 并给出原因列表。"""
    desired: list[str | None] = []
    missing: list[str] = []
    for name in names:
        if name not in name_to_md5:
            desired.append(None)
            missing.append(f"{name}: manifest 无此文档")
            continue
        md5 = name_to_md5[name]
        if md5 is None:
            desired.append(None)
            missing.append(f"{name}: manifest 条目缺 md5")
        else:
            desired.append(md5)
    return desired, missing


def backfilled_case(case: dict, md5s: list[str]) -> dict:
    """重排键序：expectedFileMd5s 统一写在 expectedFileNames 之前，其余键保持原序。"""
    out: dict = {}
    for key, value in case.items():
        if key == "expectedFileMd5s":
            continue
        if key == "expectedFileNames":
            out["expectedFileMd5s"] = md5s
        out[key] = value
    if "expectedFileMd5s" not in out:
        out["expectedFileMd5s"] = md5s
    return out


def main() -> int:
    parser = argparse.ArgumentParser(
        description="按 manifest 给评测数据集回填 expectedFileMd5s（缺省动作等价 --check）")
    parser.add_argument("--check", action="store_true", help="只校验不落盘（幂等）")
    parser.add_argument("--write", action="store_true", help="把回填结果写回数据集（幂等）")
    parser.add_argument("--dataset", default=str(DEFAULT_DATASET), help="评测数据集 JSONL 路径")
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST), help="语料清单 manifest.json 路径")
    args = parser.parse_args()
    if args.check and args.write:
        parser.error("--check 与 --write 只能二选一")
    mode = "write" if args.write else "check"

    dataset_path = Path(args.dataset)
    manifest_path = Path(args.manifest)
    name_to_md5, manifest_broken = load_manifest(manifest_path)

    try:
        raw_text = dataset_path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise SystemExit(f"数据集不存在: {dataset_path}")
    raw_lines = raw_text.splitlines()

    out_lines: list[str] = []
    total_cases = 0
    backfilled = 0
    already_ok = 0
    missing_reports: list[str] = []
    unbackfilled_reports: list[str] = []
    changed = False

    for line_no, line in enumerate(raw_lines, start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("//"):
            out_lines.append(line)
            continue
        try:
            case = json.loads(stripped)
        except json.JSONDecodeError as exc:
            print(f"[error] 第 {line_no} 行不是合法 JSON: {exc}", file=sys.stderr)
            return 2
        if not isinstance(case, dict):
            print(f"[error] 第 {line_no} 行必须是 JSON 对象", file=sys.stderr)
            return 2
        total_cases += 1
        names = as_list(case.get("expectedFileNames"))
        if not names:
            # 无期望文档（如纯拒答题）：没有可回填的 md5，原样保留
            out_lines.append(line)
            continue
        case_id = case.get("id", f"line-{line_no}")
        desired, missing = desired_md5s(names, name_to_md5)
        if missing:
            for reason in missing:
                missing_reports.append(f"用例 {case_id}: {reason}")
            # gold md5 记为 null：跳过该用例，绝不猜测或用文件名哈希顶替
            out_lines.append(line)
            continue
        desired_all = [md5 for md5 in desired if md5 is not None]
        current = as_list(case.get("expectedFileMd5s"))
        if current == desired_all:
            already_ok += 1
            out_lines.append(line)
            continue
        backfilled += 1
        unbackfilled_reports.append(
            f"用例 {case_id}: expectedFileMd5s {current or '缺失'} -> {desired_all}")
        if mode == "write":
            out_lines.append(json.dumps(backfilled_case(case, desired_all),
                                        ensure_ascii=False, separators=(", ", ": ")))
            changed = True
        else:
            out_lines.append(line)

    print(f"# eval_backfill_gold {mode}")
    print(f"dataset: {dataset_path}（{total_cases} 条用例）")
    print(f"manifest: {manifest_path}（{len(name_to_md5)} 篇文档）")
    print(f"gold md5 已回填: {already_ok}/{total_cases}，本次{'回填' if mode == 'write' else '待回填'}: {backfilled}")

    for reason in manifest_broken:
        print(f"[missing] {reason}")
    for report in missing_reports:
        print(f"[missing] {report}")
    for report in unbackfilled_reports:
        print(f"[backfill] {report}")

    # 先落盘，再给退出码（否则退出码分支会吞掉写入）
    if mode == "write" and changed:
        dataset_path.write_text("\n".join(out_lines) + "\n", encoding="utf-8")
        print(f"已写回 {dataset_path}（本次回填 {backfilled} 条用例）")

    if missing_reports or manifest_broken:
        print("存在缺映射文档（gold md5 记为 null），见上方 [missing] 列表", file=sys.stderr)
        if mode == "write":
            print("已回填其余可解析用例；缺映射用例保持原样，补齐 manifest 后重跑", file=sys.stderr)
        return 1
    if mode == "check" and unbackfilled_reports:
        print(f"存在 {backfilled} 条未回填用例，执行 --write 落盘", file=sys.stderr)
        return 1

    print("OK：全部用例的 expectedFileMd5s 已与 manifest 一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
