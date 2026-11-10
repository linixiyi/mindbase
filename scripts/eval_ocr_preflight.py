#!/usr/bin/env python3
"""OCR 可用性预检：跑评测之前先回答"这台机器的 OCR 链路能不能用"。

spec 2026-11-10_fix-eval-and-runtime-gaps task-0022（C22）。背景：07 扫描件的 6 题
（type=ocr）因 OCR 未配置被标 blocked 且不计入任何分母（eval-v2.jsonl 头注释），此前
只有等评测报告出来才能发现；本脚本把这件事前移到提交评测之前。

检查两条 OCR 路径（与后端实际读取的配置一一对应，只看配置与本地二进制，不联网探测阿里云）：

1. 阿里云 OCR：`ALIYUN_OCR_ENABLED=true`（未设置按 application.yml:243 默认 false）
   且 AccessKey ID / Secret 非空。Key 变量名与 application.yml:245-246 一致，带
   `ALIBABA_CLOUD_ACCESS_KEY_ID/SECRET` 回退（对应 AliyunOcrService.isEnabled/isConfigured）。
2. 本地 LiteParse：`FILE_PARSING_LITEPARSE_OCR_ENABLED=true`（未设置按 application.yml:233
   默认 true，与 ParseService.java:73-74 一致）且 `tesseract` 在 PATH（本地 OCR 引擎；
   语言包默认 chi_sim+eng，application.yml:234）。

配置来源：进程环境变量 → 仓库根 `.env`（只读解析 KEY=VALUE，值一律不回显、不写日志）；
每层都取"strip 后非空"的值，空串与未设置同等对待。判定 "true" 仅接受大小写不敏感的
字面 true；其他值视为未启用并在缺失项里回显（布尔样式值）。

输出：markdown 表（默认）或纯 JSON（--json），两者都包含：可用引擎、逐引擎缺失项、
受影响题数（读 eval-v2.jsonl 统计 type=ocr，并对照 status=blocked）、建议命令。

退出码：
    0  --check/--require-ocr 正常完成（--check 无引擎也退出 0，但在结论里标 blocked）
    0  --self-test 通过
    1  --self-test 断言失败（由未捕获 AssertionError 导致）
    2  --require-ocr 且无可用引擎；或数据集文件级错误（不可读 / 不可解析 / 零条用例）

token 与网络：本脚本不联网、不取 token（真机评测的 token 按 runbook 三级顺序由
eval_real_smoke.sh 处理，与本脚本无关）。--self-test 只用注入的 env/which 桩与临时
fixture，不读仓库数据文件、不读真实 .env。

只依赖标准库，单文件，不引第三方包。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = REPO_ROOT / "src" / "main" / "resources" / "eval" / "eval-v2.jsonl"
DEFAULT_ENV_FILE = REPO_ROOT / ".env"

ENGINE_ALIYUN = "aliyun"
ENGINE_LITEPARSE = "liteparse"

ALIYUN_ENABLED_VAR = "ALIYUN_OCR_ENABLED"
ALIYUN_KEY_ID_VARS = ("ALIYUN_OCR_ACCESS_KEY_ID", "ALIBABA_CLOUD_ACCESS_KEY_ID")
ALIYUN_KEY_SECRET_VARS = ("ALIYUN_OCR_ACCESS_KEY_SECRET", "ALIBABA_CLOUD_ACCESS_KEY_SECRET")
LITEPARSE_ENABLED_VAR = "FILE_PARSING_LITEPARSE_OCR_ENABLED"
TESSERACT_BIN = "tesseract"

# application.yml 里的默认值（注释引用行号，供人工核对）：
ALIYUN_ENABLED_YML_DEFAULT = "false"    # application.yml:243  enabled: ${ALIYUN_OCR_ENABLED:false}
LITEPARSE_ENABLED_YML_DEFAULT = "true"  # application.yml:233  ocr-enabled: ${FILE_PARSING_LITEPARSE_OCR_ENABLED:true}
LITEPARSE_OCR_LANGUAGE_DEFAULT = "chi_sim+eng"  # application.yml:234


class DataError(Exception):
    """数据集文件级错误（不可读 / 不可解析 / 零条用例）——退出 2。"""


def truthy(raw) -> bool:
    """布尔开关判定：仅接受大小写不敏感的字面 "true"；未设置/空串/其他值都算 false。"""
    if raw is None:
        return False
    return str(raw).strip().lower() == "true"


def _display_flag_value(raw) -> str:
    """回显布尔开关的当前值；非布尔样式的值（防误配泄密）隐藏不回显。"""
    text = (raw or "").strip()
    if not text:
        return "<空>"
    if len(text) <= 24 and re.fullmatch(r"[A-Za-z0-9_.+\-]+", text):
        return text
    return "<已设置，值非布尔样式，隐藏>"


def parse_env_file(path: Path) -> dict:
    """只读解析 KEY=VALUE 行的 .env：跳过空行与 # 注释；值去成对引号；不打印任何值。

    语义注意：不 source、不做变量展开；文件不存在/不可读返回 {}（本仓库 .env 可能有
    特殊字符，绝不 shell 展开）。解析失败的行直接跳过。
    """
    try:
        raw = Path(path).read_text(encoding="utf-8")
    except OSError:
        return {}
    result: dict = {}
    for line in raw.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        if key:
            result[key] = value
    return result


def lookup_first(names, process_env, dotenv):
    """按 [进程环境 → .env] 顺序、再按 names 顺序，取第一个 strip 后非空的值。

    返回 (value, source_var)；全空时返回 (None, None)。空串与未设置同等对待
    （docstring 口径），因此 set-but-empty 不会阻断向下一个候选变量回退。
    """
    for name in names:
        for source in (process_env, dotenv):
            if not source:
                continue
            value = source.get(name)
            if value is None:
                continue
            text = str(value).strip()
            if text:
                return text, name
    return None, None


def detect_aliyun(process_env, dotenv) -> dict:
    """阿里云 OCR 可用性：enabled=true 且 AccessKey ID/Secret 非空（变量名同 application.yml:243-246）。

    返回 dict 不含任何密钥值——只有"配置在哪个变量名上"这一事实。
    """
    enabled_raw, enabled_src = lookup_first((ALIYUN_ENABLED_VAR,), process_env, dotenv)
    enabled = truthy(enabled_raw)
    missing: list = []
    if enabled_raw is None:
        missing.append(f"{ALIYUN_ENABLED_VAR}=true（未设置，按 application.yml 默认 "
                       f"{ALIYUN_ENABLED_YML_DEFAULT}，即未启用）")
    elif not enabled:
        missing.append(f"{ALIYUN_ENABLED_VAR}=true（当前为 {_display_flag_value(enabled_raw)}）")

    key_id, key_id_src = lookup_first(ALIYUN_KEY_ID_VARS, process_env, dotenv)
    key_secret, key_secret_src = lookup_first(ALIYUN_KEY_SECRET_VARS, process_env, dotenv)
    if not key_id:
        missing.append("阿里云 AccessKey ID 非空（可配 "
                       f"{' 或 '.join(ALIYUN_KEY_ID_VARS)}）")
    if not key_secret:
        missing.append("阿里云 AccessKey Secret 非空（可配 "
                       f"{' 或 '.join(ALIYUN_KEY_SECRET_VARS)}）")

    available = enabled and bool(key_id) and bool(key_secret)
    return {
        "engine": ENGINE_ALIYUN,
        "label": "阿里云 OCR",
        "criteria": f"{ALIYUN_ENABLED_VAR}=true 且 AccessKey ID/Secret 非空",
        "available": available,
        "enabledFlag": {
            "var": ALIYUN_ENABLED_VAR,
            "set": enabled_raw is not None,
            "value": None if enabled_raw is None else _display_flag_value(enabled_raw),
            "effective": enabled,
            "source": enabled_src,
        },
        "keyIdConfiguredVia": key_id_src,
        "keySecretConfiguredVia": key_secret_src,
        "missing": missing,
    }


def detect_liteparse(process_env, dotenv, which_fn) -> dict:
    """本地 LiteParse 可用性：ocr-enabled=true 且 tesseract 在 PATH。

    未设置 FILE_PARSING_LITEPARSE_OCR_ENABLED 时按 application.yml:233 默认 true
    （与后端 ParseService.java:73-74 一致），因此"未设置"不算缺失项，只在 notes 说明。
    """
    enabled_raw, enabled_src = lookup_first((LITEPARSE_ENABLED_VAR,), process_env, dotenv)
    notes: list = []
    missing: list = []
    if enabled_raw is None:
        effective = truthy(LITEPARSE_ENABLED_YML_DEFAULT)
        notes.append(f"{LITEPARSE_ENABLED_VAR} 未设置，按 application.yml 默认 "
                     f"{LITEPARSE_ENABLED_YML_DEFAULT}（后端 ocr-enabled 默认开启）")
        flag = {
            "var": LITEPARSE_ENABLED_VAR,
            "set": False,
            "value": None,
            "effective": effective,
            "source": None,
            "defaultFromYml": LITEPARSE_ENABLED_YML_DEFAULT,
        }
    else:
        effective = truthy(enabled_raw)
        flag = {
            "var": LITEPARSE_ENABLED_VAR,
            "set": True,
            "value": _display_flag_value(enabled_raw),
            "effective": effective,
            "source": enabled_src,
        }
        if not effective:
            missing.append(f"{LITEPARSE_ENABLED_VAR}=true（当前为 {_display_flag_value(enabled_raw)}）")

    tesseract_path = which_fn(TESSERACT_BIN) if which_fn else shutil.which(TESSERACT_BIN)
    if not tesseract_path:
        missing.append(f"{TESSERACT_BIN} 不在 PATH（本地 OCR 引擎；默认语言 "
                       f"{LITEPARSE_OCR_LANGUAGE_DEFAULT} 需要 chi_sim 语言包）")

    available = effective and bool(tesseract_path)
    return {
        "engine": ENGINE_LITEPARSE,
        "label": "本地 LiteParse（tesseract）",
        "criteria": f"{LITEPARSE_ENABLED_VAR}=true（未设置按 application.yml 默认 "
                    f"{LITEPARSE_ENABLED_YML_DEFAULT}）且 tesseract 在 PATH",
        "available": available,
        "enabledFlag": flag,
        "tesseractPath": tesseract_path,
        "missing": missing,
        "notes": notes,
    }


def count_ocr_cases(path: Path) -> dict:
    """读数据集 JSONL 统计受影响题数：type=ocr 为主口径（任务卡），status=blocked 作对照。

    行处理与 eval_dataset_audit.py 同口径：跳过空行与 # / // 注释；任何一行不是合法
    JSON 对象、或整份文件零条用例，都抛 DataError（计数不可信时不给半截数字）。
    """
    try:
        raw = Path(path).read_text(encoding="utf-8")
    except OSError as exc:
        raise DataError(f"数据集不可读: {path}: {exc}") from exc
    total = 0
    ocr_ids: list = []
    blocked_ids: list = []
    for line_no, line in enumerate(raw.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("//"):
            continue
        try:
            case = json.loads(stripped)
        except json.JSONDecodeError as exc:
            raise DataError(f"数据集第 {line_no} 行不是合法 JSON（计数不可信）: {path}: {exc.msg}") from exc
        if not isinstance(case, dict):
            raise DataError(f"数据集第 {line_no} 行不是 JSON 对象（计数不可信）: {path}")
        total += 1
        case_id = case.get("id")
        case_id = case_id.strip() if isinstance(case_id, str) and case_id.strip() else f"行 {line_no}"
        type_value = case.get("type")
        type_text = type_value.strip() if isinstance(type_value, str) else ""
        status_value = case.get("status")
        status_text = status_value.strip() if isinstance(status_value, str) else ""
        if type_text == "ocr":
            ocr_ids.append(case_id)
        if status_text == "blocked":
            blocked_ids.append(case_id)
    if total == 0:
        raise DataError(f"数据集为零条用例（跳过注释后没有任何 JSON 对象行）: {path}")

    notes: list = []
    ocr_set, blocked_set = set(ocr_ids), set(blocked_ids)
    for case_id in ocr_ids:
        if case_id not in blocked_set:
            notes.append(f"{case_id} 标注 type=ocr 但 status≠blocked（数据集头注释约定 ocr 必为 blocked）")
    for case_id in blocked_ids:
        if case_id not in ocr_set:
            notes.append(f"{case_id} 标注 status=blocked 但 type≠ocr（blocked 口径通常与 type=ocr 对齐）")
    return {
        "path": str(path),
        "totalCases": total,
        "ocrCases": len(ocr_ids),
        "ocrIds": ocr_ids,
        "blockedCases": len(blocked_ids),
        "blockedIds": blocked_ids,
        "notes": notes,
    }


def suggested_commands(engines: dict, blocked: bool) -> list:
    """按缺失项给出建议命令；密钥一律用占位符 <...>，真实值不进脚本与文档。"""
    cmds: list = []
    aliyun = engines[ENGINE_ALIYUN]
    lite = engines[ENGINE_LITEPARSE]
    if not aliyun["available"]:
        flag = aliyun["enabledFlag"]
        if not flag["effective"]:
            cmds.append("# 仓库根 .env 增加（只写变量名约定，值不进仓库）：ALIYUN_OCR_ENABLED=true")
        if not aliyun["keyIdConfiguredVia"]:
            cmds.append("# 仓库根 .env 增加：ALIYUN_OCR_ACCESS_KEY_ID=<AccessKey ID>"
                        "（或 ALIBABA_CLOUD_ACCESS_KEY_ID）")
        if not aliyun["keySecretConfiguredVia"]:
            cmds.append("# 仓库根 .env 增加：ALIYUN_OCR_ACCESS_KEY_SECRET=<AccessKey Secret>"
                        "（或 ALIBABA_CLOUD_ACCESS_KEY_SECRET）")
    if not lite["available"]:
        flag = lite["enabledFlag"]
        if flag["set"] and not flag["effective"]:
            cmds.append(f"# 仓库根 .env 增加：{LITEPARSE_ENABLED_VAR}=true")
        if not lite["tesseractPath"]:
            cmds.append("# macOS：brew install tesseract tesseract-lang（含 chi_sim 中文包）")
            cmds.append("# Linux(Debian/Ubuntu)：sudo apt-get install tesseract-ocr tesseract-ocr-chi-sim")
            cmds.append(f"# 验证：tesseract --list-langs | grep chi_sim（后端默认 {LITEPARSE_OCR_LANGUAGE_DEFAULT}）")
    if blocked:
        cmds.append("# 解除后重跑：python3 scripts/eval_ocr_preflight.py；真机评测前置检查：bash scripts/eval_real_smoke.sh --dry-run")
    return cmds


def build_result(dataset: dict, engines: dict, mode: str) -> dict:
    """汇总结果对象（markdown 与 JSON 共用同一份数据，防止两种输出漂移）。"""
    available_engines = [name for name in (ENGINE_ALIYUN, ENGINE_LITEPARSE)
                         if engines[name]["available"]]
    blocked = not available_engines
    ocr = dataset["ocrCases"]
    blocked_cases = dataset["blockedCases"]
    total = dataset["totalCases"]
    if blocked:
        conclusion = (f"blocked —— 无可用 OCR 引擎；数据集 type=ocr 的 {ocr} 题"
                      f"（其中 status=blocked {blocked_cases} 题）维持 blocked、不计入任何分母，"
                      f"可评测计数题 {total - blocked_cases} 条（数据集共 {total} 条）")
    else:
        conclusion = (f"ready —— 可用引擎：{', '.join(available_engines)}；数据集 type=ocr 的 {ocr} 题"
                      f"不再受 OCR 配置阻塞（是否解除 07 的 blocked 标注属数据集/产品决策，"
                      f"本脚本只报告预检状态，不改数据集）")
    missing = {name: engines[name]["missing"] for name in (ENGINE_ALIYUN, ENGINE_LITEPARSE)}
    return {
        "mode": mode,
        "dataset": dataset,
        "engines": engines,
        "availableEngines": available_engines,
        "blocked": blocked,
        "missing": missing,
        "conclusion": conclusion,
        "suggestedCommands": suggested_commands(engines, blocked),
        "notes": [
            "本预检只读环境变量、仓库根 .env（值不回显）与本地二进制，不联网探测阿里云，不要求 token",
            "受影响题数口径 = 数据集 type=ocr 的用例数（任务卡约定）；status=blocked 仅作对照",
        ] + dataset["notes"] + engines[ENGINE_LITEPARSE]["notes"],
    }


def render_markdown(result: dict) -> str:
    """markdown 输出：引擎表 + 受影响题数 + 结论 + 建议命令。"""
    dataset = result["dataset"]
    engines = result["engines"]
    lines = [
        "# OCR 可用性预检（eval_ocr_preflight）",
        "",
        f"模式：{result['mode']}"
        + ("（--require-ocr：无可用引擎时退出 2）" if result["mode"] == "require-ocr" else ""),
        f"数据集：{dataset['path']}（{dataset['totalCases']} 条用例）",
        "",
        "| 引擎 | 判定条件 | 状态 | 缺失项 |",
        "|------|----------|------|--------|",
    ]
    for name in (ENGINE_ALIYUN, ENGINE_LITEPARSE):
        engine = engines[name]
        status = "✅ 可用" if engine["available"] else "❌ 不可用"
        missing = "；".join(m.replace("|", "\\|") for m in engine["missing"]) if engine["missing"] else "—"
        lines.append(f"| {engine['label']} | {engine['criteria']} | {status} | {missing} |")
    lines += [
        "",
        f"受影响题数：type=ocr 共 {dataset['ocrCases']} 题"
        f"（status=blocked {dataset['blockedCases']} 条对照），"
        f"涉及用例：{', '.join(dataset['ocrIds']) if dataset['ocrIds'] else '—'}",
        "",
        f"**结论：{result['conclusion']}**",
        "",
        "建议命令：",
        "",
        "```bash",
    ]
    lines += result["suggestedCommands"] if result["suggestedCommands"] else ["# （无需操作）"]
    lines += [
        "```",
        "",
    ]
    lines += [f"- {note}" for note in result["notes"]]
    lines += [
        "",
        "退出码：0（--check 正常完成，是否 blocked 看结论；--require-ocr 无可用引擎时退出 2）；"
        "2（数据集不可读/不可解析）。详见 docs/eval/ocr-preflight.md。",
    ]
    return "\n".join(lines)


def render_json(result: dict) -> str:
    return json.dumps(result, ensure_ascii=False, indent=2)


def check(dataset_path, process_env=None, dotenv=None, which_fn=None,
          require: bool = False, as_json: bool = False):
    """预检主路径：探测两引擎 → 统计受影响题数 → 渲染 → 退出码。

    返回 (exit_code, output)。process_env/which_fn/dotenv 可注入（self-test 桩）；
    None 时取真实 os.environ / shutil.which / 仓库根 .env。
    """
    if process_env is None:
        process_env = dict(os.environ)
    if dotenv is None:
        dotenv = parse_env_file(DEFAULT_ENV_FILE)
    engines = {
        ENGINE_ALIYUN: detect_aliyun(process_env, dotenv),
        ENGINE_LITEPARSE: detect_liteparse(process_env, dotenv, which_fn),
    }
    dataset = count_ocr_cases(Path(dataset_path))  # DataError 由调用方（main/self-test）处理
    mode = "require-ocr" if require else "check"
    result = build_result(dataset, engines, mode)
    code = 2 if (require and not result["availableEngines"]) else 0
    output = render_json(result) if as_json else render_markdown(result)
    return code, output


# ---------------------------------------------------------------------------
# --self-test：注入 env/which 桩 + 临时 fixture，断言 3 种引擎组合；不读仓库数据文件
# ---------------------------------------------------------------------------

STUB_KEY_ID = "AKID-SELFTEST-STUB"
STUB_KEY_SECRET = "SECRET-SELFTEST-STUB"
STUB_TESSERACT = "/stub/bin/tesseract"


def _stub_which(path_or_none):
    """which 桩工厂：传入 None 或桩路径，返回 shutil.which 同签名函数。"""
    def which_fn(name):
        return path_or_none if name == TESSERACT_BIN else None
    return which_fn


def _write_fixture_dataset(directory: str, cases: list) -> str:
    """把 fixture 用例写成带 # 头注释的 JSONL（模拟真实数据集形态），返回路径。"""
    header = "# fixture 头注释 1\n# fixture 头注释 2\n"
    body = "\n".join(json.dumps(case, ensure_ascii=False) for case in cases)
    path = Path(directory) / "dataset.jsonl"
    path.write_text(header + body + "\n", encoding="utf-8")
    return str(path)


def _fixture_cases():
    """数据集 fixture：6 条 = 2 ocr(blocked) + 1 ocr(未标 blocked，触发对照 note) + 1 fact
    + 1 fact(带 status=blocked，触发另一向对照 note) + 1 neg。"""
    return [
        {"id": "ocr-07-a", "type": "ocr", "status": "blocked", "question": "Q1"},
        {"id": "ocr-07-b", "type": "ocr", "status": "blocked", "question": "Q2"},
        {"id": "ocr-07-c", "type": "ocr", "question": "Q3"},
        {"id": "fact-01-a", "type": "fact", "question": "Q4"},
        {"id": "fact-01-z", "type": "fact", "status": "blocked", "question": "Q5"},
        {"id": "neg-01-a", "type": "neg", "question": "Q6"},
    ]


def self_test() -> int:
    # 0) truthy 语义：仅大小写不敏感的字面 true
    assert truthy("true") and truthy("  TRUE "), "truthy true/TRUE"
    assert not truthy(None) and not truthy("") and not truthy("false"), "truthy falsy"
    assert not truthy("1") and not truthy("yes"), "truthy 非 true 字面量"

    # 0b) parse_env_file：注释/引号/无等号行/空值；值不打印
    directory = tempfile.mkdtemp(prefix="eval-ocr-preflight-selftest-")
    env_path = Path(directory) / ".env"
    env_lines = [
        "# 注释行",
        "ALIYUN_OCR_ENABLED=true",
        'QUOTED="hello world"',
        "SINGLE='x y'",
        "NOEQ_LINE",
        "EMPTY=",
    ]
    env_path.write_text("\n".join(env_lines) + "\n", encoding="utf-8")
    parsed = parse_env_file(env_path)
    assert parsed == {"ALIYUN_OCR_ENABLED": "true", "QUOTED": "hello world",
                      "SINGLE": "x y", "EMPTY": ""}, parsed
    assert parse_env_file(Path(directory) / "missing.env") == {}, "缺失 .env 返回空字典"

    fixture_path = _write_fixture_dataset(directory, _fixture_cases())
    counts = count_ocr_cases(Path(fixture_path))
    assert counts["totalCases"] == 6, counts
    assert counts["ocrCases"] == 3 and counts["ocrIds"] == ["ocr-07-a", "ocr-07-b", "ocr-07-c"], counts
    assert counts["blockedCases"] == 3 and counts["blockedIds"] == ["ocr-07-a", "ocr-07-b", "fact-01-z"], counts
    assert len(counts["notes"]) == 2, counts["notes"]  # ocr 未标 blocked 一条 + blocked 非 ocr 一条

    # ------------------------------------------------------------------
    # 组合 1：双引擎可用（阿里云 enabled+Key 齐全；LiteParse enabled+tesseract 在桩 PATH）
    # ------------------------------------------------------------------
    both_env = {
        ALIYUN_ENABLED_VAR: "true",
        "ALIYUN_OCR_ACCESS_KEY_ID": STUB_KEY_ID,
        "ALIYUN_OCR_ACCESS_KEY_SECRET": STUB_KEY_SECRET,
        LITEPARSE_ENABLED_VAR: "true",
    }
    both_which = _stub_which(STUB_TESSERACT)
    code, out = check(fixture_path, both_env, {}, both_which, require=False, as_json=False)
    assert code == 0, (code, out)
    assert "✅ 可用" in out and "❌ 不可用" not in out, out
    assert "结论：ready" in out and "blocked ——" not in out, out
    assert "type=ocr 共 3 题" in out, out
    code_j, out_j = check(fixture_path, both_env, {}, both_which, require=False, as_json=True)
    assert code_j == 0, (code_j, out_j)
    as_json_1 = json.loads(out_j)
    assert as_json_1["blocked"] is False and as_json_1["availableEngines"] == ["aliyun", "liteparse"], as_json_1
    assert as_json_1["dataset"]["ocrCases"] == 3 and as_json_1["dataset"]["totalCases"] == 6, as_json_1
    assert as_json_1["missing"] == {"aliyun": [], "liteparse": []}, as_json_1["missing"]
    assert as_json_1["mode"] == "check", as_json_1
    # --require-ocr 有引擎 → 0
    code_r, _ = check(fixture_path, both_env, {}, both_which, require=True, as_json=False)
    assert code_r == 0, code_r
    # 密钥值绝不进入任何输出（md 与 JSON 都查）
    assert STUB_KEY_SECRET not in out and STUB_KEY_ID not in out, "密钥值泄漏到 markdown"
    assert STUB_KEY_SECRET not in out_j and STUB_KEY_ID not in out_j, "密钥值泄漏到 JSON"
    # 密钥"配置在哪个变量名上"这一事实可以出现（JSON 记录来源变量名）
    assert as_json_1["engines"]["aliyun"]["keyIdConfiguredVia"] == "ALIYUN_OCR_ACCESS_KEY_ID", as_json_1
    assert as_json_1["engines"]["aliyun"]["keySecretConfiguredVia"] == "ALIYUN_OCR_ACCESS_KEY_SECRET", as_json_1

    # ------------------------------------------------------------------
    # 组合 2：仅阿里云可用（FILE_PARSING_LITEPARSE_OCR_ENABLED=true 但 tesseract 不在桩 PATH）
    # ------------------------------------------------------------------
    aliyun_only_which = _stub_which(None)
    code, out = check(fixture_path, both_env, {}, aliyun_only_which, require=False, as_json=False)
    assert code == 0, (code, out)
    code_j, out_j = check(fixture_path, both_env, {}, aliyun_only_which, require=False, as_json=True)
    as_json_2 = json.loads(out_j)
    assert as_json_2["blocked"] is False and as_json_2["availableEngines"] == ["aliyun"], as_json_2
    assert as_json_2["missing"]["aliyun"] == [], as_json_2["missing"]
    assert len(as_json_2["missing"]["liteparse"]) == 1, as_json_2["missing"]
    assert "tesseract 不在 PATH" in as_json_2["missing"]["liteparse"][0], as_json_2["missing"]
    assert "tesseract 不在 PATH" in out, out
    # --require-ocr：阿里云可用即放行 → 0
    code_r, _ = check(fixture_path, both_env, {}, aliyun_only_which, require=True, as_json=False)
    assert code_r == 0, code_r

    # ------------------------------------------------------------------
    # 组合 3：全无（阿里云开关未设 + 无 Key；LiteParse 显式 false + tesseract 缺）
    #   --check 退出 0 但标 blocked；--require-ocr 退出 2
    # ------------------------------------------------------------------
    none_env = {LITEPARSE_ENABLED_VAR: "false"}
    none_which = _stub_which(None)
    code, out = check(fixture_path, none_env, {}, none_which, require=False, as_json=False)
    assert code == 0, (code, out)  # 卡面：--check 无引擎退出 0 但标 blocked
    assert "结论：blocked" in out, out
    assert "❌ 不可用" in out and "✅ 可用" not in out, out
    code_r, out_r = check(fixture_path, none_env, {}, none_which, require=True, as_json=False)
    assert code_r == 2, (code_r, out_r)  # 卡面：--require-ocr 退出 2
    code_j, out_j = check(fixture_path, none_env, {}, none_which, require=False, as_json=True)
    as_json_3 = json.loads(out_j)
    assert as_json_3["blocked"] is True and as_json_3["availableEngines"] == [], as_json_3
    assert len(as_json_3["missing"]["aliyun"]) == 3, as_json_3["missing"]  # 开关 + ID + Secret
    assert len(as_json_3["missing"]["liteparse"]) == 2, as_json_3["missing"]  # 显式 false + tesseract
    assert as_json_3["engines"]["aliyun"]["enabledFlag"]["set"] is False, as_json_3
    assert as_json_3["engines"]["liteparse"]["enabledFlag"]["value"] == "false", as_json_3
    # 建议命令覆盖全部缺失项且不含真实值
    joined = "\n".join(as_json_3["suggestedCommands"])
    for fragment in ("ALIYUN_OCR_ENABLED=true", "ALIYUN_OCR_ACCESS_KEY_ID=<AccessKey ID>",
                     "ALIYUN_OCR_ACCESS_KEY_SECRET=<AccessKey Secret>",
                     "FILE_PARSING_LITEPARSE_OCR_ENABLED=true", "brew install tesseract"):
        assert fragment in joined, fragment
    # 缺失项单元格回显变量名（密钥"配置在哪"的事实可出现在报告里，值不行）
    assert "ALIYUN_OCR_ACCESS_KEY_ID" in out and "ALIYUN_OCR_ACCESS_KEY_SECRET" in out, out

    # ------------------------------------------------------------------
    # 附加语义（组合之外的桩断言，保证配置解析与后端一致）
    # ------------------------------------------------------------------
    # a) FILE_PARSING_LITEPARSE_OCR_ENABLED 未设置 → 按 application.yml:233 默认 true，
    #    不算缺失项（tesseract 在时 LiteParse 直接可用）
    default_lite_env = {
        ALIYUN_ENABLED_VAR: "true",
        "ALIYUN_OCR_ACCESS_KEY_ID": STUB_KEY_ID,
        "ALIYUN_OCR_ACCESS_KEY_SECRET": STUB_KEY_SECRET,
    }
    lite = detect_liteparse(default_lite_env, {}, both_which)
    assert lite["available"] is True and lite["missing"] == [], lite
    assert lite["enabledFlag"]["set"] is False and lite["enabledFlag"]["effective"] is True, lite
    assert lite["notes"], "未设置默认 true 必须出现在 notes 里"
    # b) ALIBABA_CLOUD_* 回退变量名生效（application.yml:245-246）
    fallback_env = {
        ALIYUN_ENABLED_VAR: "true",
        "ALIBABA_CLOUD_ACCESS_KEY_ID": STUB_KEY_ID,
        "ALIBABA_CLOUD_ACCESS_KEY_SECRET": STUB_KEY_SECRET,
    }
    aliyun = detect_aliyun(fallback_env, {})
    assert aliyun["available"] is True and aliyun["missing"] == [], aliyun
    assert aliyun["keyIdConfiguredVia"] == "ALIBABA_CLOUD_ACCESS_KEY_ID", aliyun
    # c) 进程环境优先于 .env；.env 值可用
    aliyun = detect_aliyun({}, {ALIYUN_ENABLED_VAR: "true",
                                "ALIYUN_OCR_ACCESS_KEY_ID": STUB_KEY_ID,
                                "ALIYUN_OCR_ACCESS_KEY_SECRET": STUB_KEY_SECRET})
    assert aliyun["available"] is True, aliyun
    assert aliyun["enabledFlag"]["source"] == ALIYUN_ENABLED_VAR, aliyun
    proc_first = detect_aliyun({ALIYUN_ENABLED_VAR: "true"},
                               {ALIYUN_ENABLED_VAR: "false",
                                "ALIYUN_OCR_ACCESS_KEY_ID": STUB_KEY_ID,
                                "ALIYUN_OCR_ACCESS_KEY_SECRET": STUB_KEY_SECRET})
    assert proc_first["available"] is True, proc_first  # 进程环境 true 赢过 .env false
    # d) 空串等同未设置（docstring 口径）
    blank = detect_aliyun({ALIYUN_ENABLED_VAR: "", "ALIYUN_OCR_ACCESS_KEY_ID": " ",
                           "ALIYUN_OCR_ACCESS_KEY_SECRET": STUB_KEY_SECRET}, {})
    assert blank["available"] is False and len(blank["missing"]) == 2, blank
    # e) 开关值非布尔样式时回显被隐藏（防误配泄密）
    weird = detect_aliyun({ALIYUN_ENABLED_VAR: "true with spaces"}, {})
    assert "<已设置，值非布尔样式，隐藏>" in weird["missing"][0], weird

    # f) 数据文件级错误：单元层抛 DataError；main 端到端映射为退出 2 + stderr 前缀（子进程，
    #    数据集缺失/坏行与引擎配置无关，退出码确定）
    broken = Path(directory) / "broken.jsonl"
    broken.write_text('{"id": "x", "type": "ocr"}\n{"id": "y"\n', encoding="utf-8")
    empty = Path(directory) / "empty.jsonl"
    empty.write_text("# 只有注释\n\n", encoding="utf-8")
    script_path = str(Path(__file__).resolve())
    for bad_label, bad_path in (("数据集缺失", "/nonexistent/dataset.jsonl"),
                                ("坏行", str(broken)), ("空集", str(empty))):
        try:
            check(bad_path, {}, {}, both_which)
            raise AssertionError(f"{bad_label} 必须抛 DataError")
        except DataError:
            pass
        proc = subprocess.run([sys.executable, "-B", script_path, "--check", "--dataset", bad_path],
                              capture_output=True, text=True, timeout=60)
        assert proc.returncode == 2, (bad_label, proc.returncode, proc.stdout, proc.stderr)
        assert proc.stderr.startswith("eval_ocr_preflight:"), (bad_label, proc.stderr)

    import shutil as _shutil
    _shutil.rmtree(directory, ignore_errors=True)

    print("self-test OK: OCR 可用性预检（3 种引擎组合 + 配置解析附加断言）")
    print("  - 组合 1 双引擎可用：check/JSON 退出 0、ready、require-ocr 退出 0；密钥值不出现在任何输出")
    print("  - 组合 2 仅阿里云：LiteParse 缺失项=tesseract 不在 PATH；require-ocr 仍退出 0")
    print("  - 组合 3 全无：--check 退出 0 但标 blocked；--require-ocr 退出 2；建议命令覆盖全部缺失项")
    print("  - 未设置 FILE_PARSING_LITEPARSE_OCR_ENABLED 按 yml 默认 true；ALIBABA_CLOUD_* 回退生效；")
    print("    进程环境优先于 .env；空串等同未设置；数据文件级错误（缺失/坏行/空集）退出 2")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="OCR 可用性预检（详见模块 docstring）：--check 默认，--require-ocr 门禁，--self-test 自检")
    parser.add_argument("--check", action="store_true",
                        help="检查两条 OCR 路径并输出报告（默认动作；无引擎退出 0 但标 blocked）")
    parser.add_argument("--require-ocr", dest="require_ocr", action="store_true",
                        help="同 --check，但无可用 OCR 引擎时退出 2（评测门禁用）")
    parser.add_argument("--json", action="store_true",
                        help="输出纯 JSON（默认输出 markdown 表）")
    parser.add_argument("--self-test", dest="self_test", action="store_true",
                        help="用注入的 env/which 桩断言 3 种引擎组合，不读仓库数据文件、不读真实 .env")
    parser.add_argument("--dataset", default=str(DEFAULT_DATASET),
                        help=f"评测数据集 JSONL 路径（默认 {DEFAULT_DATASET}）")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    if args.self_test and (args.check or args.require_ocr):
        parser.error("--self-test 与 --check/--require-ocr 只能二选一")
    if args.check and args.require_ocr:
        parser.error("--check 与 --require-ocr 只能二选一（--require-ocr 蕴含检查）")
    if args.self_test:
        return self_test()
    try:
        code, output = check(args.dataset, require=args.require_ocr, as_json=args.json)
    except DataError as error:
        print(f"eval_ocr_preflight: {error}", file=sys.stderr)
        return 2
    print(output)
    return code


if __name__ == "__main__":
    sys.exit(main())
