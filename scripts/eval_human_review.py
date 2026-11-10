#!/usr/bin/env python3
"""人工复核导出/回填 CLI：把评委标了 needsHumanReview 的用例导出给人填，回填到评测运行并按 κ 阈值判定。

spec 2026-11-10_fix-eval-and-runtime-gaps task-0013（C13）：人工复核此前只有 Java 端点
（RagEvalAdminController POST/GET /api/v1/admin/eval/runs/{id}/human-reviews）与前端页面，
命令行无法批量导出/回填，κ 校准一直缺样本。本脚本补齐三步：

    export     从评测报告过滤 needsHumanReview 用例，生成人工复核文件（verdict 留空）
    import     把人工填好的复核文件 POST 回评测运行（管理员 token，runbook 三级顺序）
    agreement  打印报告/状态里的人工一致性（humanAgreement），κ 低于阈值退出 1

字段契约以 src/main/java/com/mindbase/service/eval/EvalHumanReviewService.java:149 为准：

    record HumanReviewInput(String caseId, Boolean correct, Integer score, String note)

映射关系（import 时）：复核文件的 verdict → correct（true/false 必填）；score（可选，1-5 整数）
与 note（可选，≤500 字，EvalHumanReviewService.MAX_NOTE_CHARS=500）同名直传；caseId 同名直传。
单次最多 500 条（RagEvalProperties.maxCases，application.yml eval.max-cases 默认 500）。

humanAgreement 的取数位置（按优先级，先找到先用，来源会打印出来）：
    1. humanAgreement          —— GET /api/v1/admin/eval/runs/{id} 状态 JSON 顶层（RagEvalService.RunStatus）
    2. humanReview.agreement   —— GET /api/v1/admin/eval/runs/{id}/report 报告接口附带（RagEvalAdminController.withHumanReview）
    3. agreement               —— 报告旁路文件 {报告名}.human-reviews.json（HumanReviewStore）
    落盘报告（EvalReport）本身没有 humanAgreement 字段；config.kappa 通常为 null
    （EvalReportAggregator.KAPPA_NOT_COMPUTED_NOTE），所以三处都没有时就是"未计算"，退出 2，不伪造数字。

token 三级顺序（与 .spec/specs/2026-11-10_fix-eval-and-runtime-gaps/runbook.md 完全一致，dry-run 不要求）：
    1. 环境变量 EVAL_ADMIN_TOKEN
    2. 环境变量 EVAL_ADMIN_USERNAME + EVAL_ADMIN_PASSWORD（POST /api/v1/users/login，读 data.token）
    3. 仓库根 .env 的 ADMIN_BOOTSTRAP_USERNAME + ADMIN_BOOTSTRAP_PASSWORD（自读，不回显值）
    都拿不到 → 退出 2，指明缺哪个变量并指向 runbook。

用法（在仓库根执行）：
    python3 -B scripts/eval_human_review.py export --report eval-reports/xxx.json --out review.json
    python3 -B scripts/eval_human_review.py import --run <runId> --file review.json --reviewer 张三 [--dry-run]
    python3 -B scripts/eval_human_review.py agreement --report eval-reports/xxx.json [--min-kappa 0.6]
    python3 -B scripts/eval_human_review.py --self-test

退出码：
    0  成功（export 完成 / agreement κ 达标 / import 成功或 dry-run 计划可行 / self-test 通过）
    1  操作性失败（agreement κ 低于阈值；import 登录失败、服务端拒绝、token 失效且无凭据可重登）
    2  环境/用法错误（文件不存在或不可解析、报告缺 runId/cases、复核文件缺 verdict 或字段不合法、
       三级凭据都拿不到、后端不可达、报告缺 humanAgreement 即"未计算"）

只依赖标准库，单文件，不引第三方包；--self-test 与 --dry-run 不发任何网络请求。
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ENV_FILE = REPO_ROOT / ".env"
RUNBOOK_REL = ".spec/specs/2026-11-10_fix-eval-and-runtime-gaps/runbook.md"
DEFAULT_BASE_URL = "http://localhost:8081"

DEFAULT_MIN_KAPPA = 0.6          # RagEvalProperties.HumanAgreement.minKappa 默认值
MAX_NOTE_CHARS = 500             # EvalHumanReviewService.MAX_NOTE_CHARS
DEFAULT_MAX_CASES = 500          # RagEvalProperties.maxCases 默认值
LOGIN_TIMEOUT = 30
POST_TIMEOUT = 60

# 导出文件每条用例的字段与顺序（任务卡字面契约；verdict 留给人填，note 留空）
EXPORT_KEYS = ("caseId", "question", "answer", "judgeScore", "judgeReason", "verdict", "note")
# 提交 payload 每条的字段与顺序 = EvalHumanReviewService.HumanReviewInput 的分量顺序
INPUT_KEYS = ("caseId", "correct", "score", "note")


class Fail(Exception):
    """带退出码的业务失败：code ∈ {1, 2}。"""

    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


class HttpFailure(Exception):
    """HTTP 非 2xx 响应，import 的 401 重登逻辑据此分支。"""

    def __init__(self, status: int, body_text: str):
        super().__init__(f"HTTP {status}")
        self.status = status
        self.body_text = body_text


# ---------------------------------------------------------------------------
# JSON 读取与形状工具
# ---------------------------------------------------------------------------

def unwrap_data(root):
    """接口响应是 {code, message, data} 包裹（RagEvalAdminController.ok）；落盘报告/旁路文件没有包裹。
    顶层既无 runId 也无 cases 也无 humanAgreement 且带 dict 型 data 时，展开到 data。"""
    if (isinstance(root, dict) and isinstance(root.get("data"), dict)
            and "runId" not in root and "cases" not in root and "humanAgreement" not in root):
        return root["data"]
    return root


def load_json_file(path) -> object:
    try:
        raw = Path(path).read_text(encoding="utf-8")
    except OSError as exc:
        raise Fail(2, f"文件不可读: {path}: {exc}") from exc
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise Fail(2, f"不是合法 JSON: {path}: 第 {exc.lineno} 行 {exc.msg}") from exc


def write_json(path, obj) -> None:
    out_path = Path(path)
    try:
        if out_path.parent and not out_path.parent.exists():
            out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except OSError as exc:
        raise Fail(2, f"写文件失败: {path}: {exc}") from exc


def _as_text(value):
    return value if isinstance(value, str) else None


def _as_int(value):
    """整数透传；JSON 布尔是 int 子类，显式排除（judgeScore=true 是损坏数据，不能当 1 用）。"""
    if isinstance(value, bool):
        return None
    return value if isinstance(value, int) else None


def _as_number(value):
    if isinstance(value, bool):
        return None
    return value if isinstance(value, (int, float)) else None


# ---------------------------------------------------------------------------
# humanAgreement 定位与 κ 判定
# ---------------------------------------------------------------------------

def find_agreement(root):
    """返回 (agreement dict 或 None, 来源标签 或 None)。三处取数位置见模块 docstring，先找到先用。"""
    root = unwrap_data(root)
    if not isinstance(root, dict):
        return None, None
    if isinstance(root.get("humanAgreement"), dict):
        return root["humanAgreement"], "humanAgreement（评测状态 JSON 顶层，GET /runs/{id}）"
    human_review = root.get("humanReview")
    if isinstance(human_review, dict) and isinstance(human_review.get("agreement"), dict):
        return human_review["agreement"], "humanReview.agreement（报告接口附带，GET /runs/{id}/report）"
    if isinstance(root.get("agreement"), dict):
        return root["agreement"], "agreement（报告旁路 .human-reviews.json）"
    return None, None


def kappa_of(agreement) -> float | None:
    """取 cohensKappa 的数值；null / 非数值返回 None（JudgeHumanAgreement κ 无定义时就是 null）。"""
    value = _as_number(agreement.get("cohensKappa")) if isinstance(agreement, dict) else None
    return float(value) if value is not None else None


def format_kappa_line(agreement, min_kappa: float) -> str:
    kappa = kappa_of(agreement)
    if kappa is None:
        return "  cohensKappa: null（无定义：可比样本为 0，或两边判对比例乘积为 1）"
    return f"  cohensKappa: {kappa:.3f}（--min-kappa {min_kappa:g}）"


def agreement_verdict(kappa, min_kappa: float) -> tuple[int, str]:
    """纯函数：κ 与阈值的判定。None 表示不可判定（调用方负责退出 2）。"""
    if kappa is None:
        return 2, "结论: κ 未计算 → 退出 2，不伪造数字"
    if kappa >= min_kappa:
        return 0, f"结论: κ {kappa:.3f} >= {min_kappa:g} → 达标（退出 0）"
    return 1, (f"结论: κ {kappa:.3f} < {min_kappa:g} → 低于阈值，评委需重新校准（退出 1；"
               "分歧用例看 agreement.disagreementCaseIds，或在 disagreementCaseIds 上补人工复核）")


# ---------------------------------------------------------------------------
# export：过滤 needsHumanReview
# ---------------------------------------------------------------------------

def build_export(report_root) -> tuple[dict, int]:
    """报告 → ({runId, cases:[7 字段固定形状]}, 报告用例总数)。只收 needsHumanReview is True 的用例。"""
    root = unwrap_data(report_root)
    if not isinstance(root, dict):
        raise Fail(2, "报告必须是 JSON 对象（评测报告 / 状态 JSON / 报告接口响应均可）")
    run_id = root.get("runId")
    if not isinstance(run_id, str) or not run_id.strip():
        raise Fail(2, "报告缺 runId，无法生成复核文件（确认传的是评测报告而不是别的 JSON）")
    cases = root.get("cases")
    if not isinstance(cases, list):
        raise Fail(2, "报告缺 cases 数组，无法过滤 needsHumanReview（确认传的是评测报告或报告接口响应）")
    exported = []
    for index, case in enumerate(cases):
        if not isinstance(case, dict):
            raise Fail(2, f"报告 cases[{index}] 不是 JSON 对象，报告疑似损坏，拒绝静默跳过")
        if case.get("needsHumanReview") is not True:
            continue
        exported.append({
            "caseId": _as_text(case.get("caseId")),
            "question": _as_text(case.get("question")),
            "answer": _as_text(case.get("answer")),
            "judgeScore": _as_int(case.get("judgeScore")),
            "judgeReason": _as_text(case.get("judgeReason")),
            "verdict": None,
            "note": "",
        })
    return {"runId": run_id.strip(), "cases": exported}, len(cases)


# ---------------------------------------------------------------------------
# import：复核文件 → HumanReviewInput payload（客户端先校验，不发无效请求）
# ---------------------------------------------------------------------------

def build_review_payload(file_root, run_id_arg: str) -> dict:
    """复核文件 → {"reviews": [{caseId, correct, score, note}, ...]}。

    输入两种形态：export 输出的 {runId, cases:[...]}，或手工整理的裸用例数组。
    verdict → correct；verdict 缺失/非布尔、score 非 1-5 整数、note 超 500 字 → Fail(2)，
    逐个列出不合法的 caseId，绝不带着坏 payload 打后端。
    """
    root = unwrap_data(file_root)
    if isinstance(root, dict) and isinstance(root.get("cases"), list):
        entries = root["cases"]
        file_run_id = root.get("runId")
    elif isinstance(root, list):
        entries = root
        file_run_id = None
    else:
        raise Fail(2, "复核文件必须是 {runId, cases:[...]}（export 输出）或用例数组，另见 docs/eval/human-review.md")
    file_run_id = str(file_run_id).strip() if isinstance(file_run_id, str) else None
    if file_run_id and file_run_id != run_id_arg:
        raise Fail(2, f"复核文件 runId={file_run_id} 与 --run {run_id_arg} 不一致：人工判定绑定该轮的评委标签，"
                      "跨轮回填会被后端按未知用例拒绝（EvalHumanReviewService.validate）")

    reviews = []
    missing_verdict = []
    invalid = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise Fail(2, f"复核文件第 {index} 项不是 JSON 对象")
        case_id = entry.get("caseId")
        case_id = case_id.strip() if isinstance(case_id, str) else ""
        if not case_id:
            raise Fail(2, f"复核文件第 {index} 项缺 caseId（EvalHumanReviewService 对空 caseId 返回 400）")
        verdict = entry.get("verdict")
        if not isinstance(verdict, bool):
            missing_verdict.append(case_id)
            continue
        score = entry.get("score")
        if score is not None and (isinstance(score, bool) or not isinstance(score, int)
                                  or not 1 <= score <= 5):
            invalid.append(f"{case_id}(score={score!r}，须为 1-5 整数)")
            continue
        note = entry.get("note")
        if note is not None and not isinstance(note, str):
            invalid.append(f"{case_id}(note 不是字符串)")
            continue
        if isinstance(note, str) and len(note) > MAX_NOTE_CHARS:
            invalid.append(f"{case_id}(note {len(note)} 字，超过 {MAX_NOTE_CHARS})")
            continue
        reviews.append({"caseId": case_id, "correct": verdict, "score": score, "note": note})
    if missing_verdict:
        raise Fail(2, "这些用例还没填 verdict（true/false），先人工复核再 import: "
                      + "、".join(missing_verdict))
    if invalid:
        raise Fail(2, "这些用例字段不合法: " + "；".join(invalid))
    if not reviews:
        raise Fail(2, "复核文件里没有任何带 verdict 的用例（verdict 是人工结论，true=答对 / false=答错）")
    if len(reviews) > DEFAULT_MAX_CASES:
        raise Fail(2, f"单次最多提交 {DEFAULT_MAX_CASES} 条（eval.max-cases / RagEvalProperties.maxCases），"
                      f"当前 {len(reviews)} 条，请分批 import")
    return {"reviews": reviews}


# ---------------------------------------------------------------------------
# 凭据三级顺序（runbook 约定，值永不回显）
# ---------------------------------------------------------------------------

def read_env_file(path) -> dict:
    """读 KEY=VALUE（支持成对单/双引号包裹），只在本函数与登录 body 里使用值，绝不打印。"""
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


def credential_plan(environ=None, env_values=None):
    """三级顺序判定。返回 (kind, value, description)：kind ∈ {token, credentials, missing}。
    environ/env_values 参数供 self-test 注入，不碰真实环境。"""
    environ = os.environ if environ is None else environ
    env_values = {} if env_values is None else env_values
    token = str(environ.get("EVAL_ADMIN_TOKEN") or "").strip()
    if token:
        return "token", token, "EVAL_ADMIN_TOKEN（环境变量，一级）"
    user = str(environ.get("EVAL_ADMIN_USERNAME") or "").strip()
    password = str(environ.get("EVAL_ADMIN_PASSWORD") or "").strip()
    if user and password:
        return "credentials", (user, password), "EVAL_ADMIN_USERNAME/EVAL_ADMIN_PASSWORD（环境变量，二级）"
    env_user = str(env_values.get("ADMIN_BOOTSTRAP_USERNAME") or "").strip()
    env_password = str(env_values.get("ADMIN_BOOTSTRAP_PASSWORD") or "").strip()
    if env_user and env_password:
        return ("credentials", (env_user, env_password),
                "ADMIN_BOOTSTRAP_USERNAME/ADMIN_BOOTSTRAP_PASSWORD（仓库根 .env，三级）")
    missing = []
    if not token:
        missing.append("EVAL_ADMIN_TOKEN（环境变量）")
    if not (user and password):
        absent = [name for name, value in (("EVAL_ADMIN_USERNAME", user), ("EVAL_ADMIN_PASSWORD", password))
                  if not value]
        missing.append("EVAL_ADMIN_USERNAME + EVAL_ADMIN_PASSWORD（环境变量，缺 " + "、".join(absent) + "）")
    if not (env_user and env_password):
        absent = [name for name, value in (("ADMIN_BOOTSTRAP_USERNAME", env_user),
                                           ("ADMIN_BOOTSTRAP_PASSWORD", env_password)) if not value]
        missing.append("ADMIN_BOOTSTRAP_USERNAME + ADMIN_BOOTSTRAP_PASSWORD（仓库根 .env，缺 "
                       + "、".join(absent) + "）")
    return "missing", missing, ""


def resolve_credentials_only(environ=None, env_values=None):
    """401 重登用：只认账密两级（环境变量优先，再 .env）；拿不到返回 None。"""
    environ = os.environ if environ is None else environ
    env_values = {} if env_values is None else env_values
    user = str(environ.get("EVAL_ADMIN_USERNAME") or "").strip()
    password = str(environ.get("EVAL_ADMIN_PASSWORD") or "").strip()
    if user and password:
        return user, password, "EVAL_ADMIN_USERNAME/EVAL_ADMIN_PASSWORD（环境变量）"
    env_user = str(env_values.get("ADMIN_BOOTSTRAP_USERNAME") or "").strip()
    env_password = str(env_values.get("ADMIN_BOOTSTRAP_PASSWORD") or "").strip()
    if env_user and env_password:
        return env_user, env_password, "ADMIN_BOOTSTRAP_USERNAME/ADMIN_BOOTSTRAP_PASSWORD（仓库根 .env）"
    return None


# ---------------------------------------------------------------------------
# HTTP（仅 import 真跑；标准库 urllib）
# ---------------------------------------------------------------------------

def http_json(url, token=None, body=None, timeout=POST_TIMEOUT):
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            detail = exc.read().decode("utf-8", "replace")
        except Exception:
            pass
        raise HttpFailure(exc.code, detail) from None
    except urllib.error.URLError as exc:
        raise Fail(2, f"后端不可达: {url}（{exc.reason}）——确认后端已从仓库根启动，见 runbook：{RUNBOOK_REL}") from None
    try:
        return json.loads(payload)
    except json.JSONDecodeError as exc:
        raise Fail(1, f"响应不是合法 JSON: {url}（{exc}）") from exc


def login_for_token(base_url, username, password):
    """POST /api/v1/users/login，读 data.token（runbook 约定）。值不打印。"""
    response = http_json(f"{base_url}/api/v1/users/login", body={"username": username, "password": password},
                         timeout=LOGIN_TIMEOUT)
    token = None
    if isinstance(response, dict) and isinstance(response.get("data"), dict):
        token = response["data"].get("token")
    if not isinstance(token, str) or not token.strip():
        message = response.get("message") if isinstance(response, dict) else response
        raise Fail(1, f"登录响应缺 data.token: {message}")
    return token.strip()


def http_failure_to_fail(exc: HttpFailure) -> Fail:
    message = exc.body_text.strip()
    try:
        parsed = json.loads(exc.body_text)
        if isinstance(parsed, dict) and parsed.get("message"):
            message = f"{parsed.get('message')}（code={parsed.get('code')}）"
    except (json.JSONDecodeError, ValueError):
        pass
    if len(message) > 400:
        message = message[:400] + "…"
    return Fail(1, f"服务端返回 HTTP {exc.status}: {message or '（无响应体）'}；排查表见 runbook 第 5 节")


# ---------------------------------------------------------------------------
# 子命令
# ---------------------------------------------------------------------------

def cmd_export(args) -> int:
    report_root = load_json_file(args.report)
    export, total = build_export(report_root)
    write_json(args.out, export)
    print(f"已导出 {len(export['cases'])}/{total} 条 needsHumanReview 用例（runId={export['runId']}）→ {args.out}")
    print("下一步：人工在每条 verdict 填 true/false（可加 score 1-5 与 note ≤500 字），再执行 import；")
    print("字段说明与 κ 判定见 docs/eval/human-review.md")
    return 0


def cmd_import(args) -> int:
    base_url = (os.environ.get("EVAL_BASE_URL") or DEFAULT_BASE_URL).rstrip("/")
    env_file = os.environ.get("EVAL_ENV_FILE") or str(DEFAULT_ENV_FILE)
    env_values = read_env_file(env_file)
    file_root = load_json_file(args.file)
    payload = build_review_payload(file_root, args.run)
    url = f"{base_url}/api/v1/admin/eval/runs/{args.run}/human-reviews"
    reviews = payload["reviews"]

    if args.dry_run:
        kind, value, description = credential_plan(env_values=env_values)
        print("dry-run：只校验文件与打印计划，不发网络请求、不要求 token")
        print(f"- 目标: POST {url}")
        print(f"- 提交条数: {len(reviews)}（字段 {', '.join(INPUT_KEYS)} —— EvalHumanReviewService.HumanReviewInput）")
        sample = reviews[0]
        print(f"- 首条校验: caseId={sample['caseId']} correct={sample['correct']} "
              f"score={sample['score']} note={'<空>' if not sample['note'] else f'{len(sample['note'])} 字'}")
        print(f"- token 来源（真跑时按三级顺序取）: {description if kind != 'missing' else '三级均未配置（dry-run 不要求；真跑将退出 2）'}")
        print(f"- 环境变量: EVAL_BASE_URL={base_url} EVAL_ENV_FILE={env_file}")
        print(f"- reviewer 本地标注: {args.reviewer}（后端实际以管理员 token 的用户名记录 reviewer）")
        return 0

    kind, value, description = credential_plan(env_values=env_values)
    if kind == "missing":
        raise Fail(2, "拿不到管理员凭据，缺: " + "；".join(value)
                   + f"。取 token 三级顺序见 runbook：{RUNBOOK_REL}")
    if kind == "token":
        token = value
    else:
        token = login_for_token(base_url, value[0], value[1])
    print(f"凭据来源: {description}")

    response = None
    for attempt in ("首次", "重登后"):
        try:
            response = http_json(url, token=token, body=payload)
            break
        except HttpFailure as exc:
            if exc.status != 401:
                raise http_failure_to_fail(exc) from None
            if attempt == "重登后":
                raise http_failure_to_fail(exc) from None
            credentials = resolve_credentials_only(env_values=env_values)
            if credentials is None:
                raise Fail(1, "提交人工判定 401（token 过期或非管理员），且没有可用的账密变量可重新登录；"
                              f"见 runbook：{RUNBOOK_REL}") from None
            print(f"401，用 {credentials[2]} 重新登录一次后重试")
            token = login_for_token(base_url, credentials[0], credentials[1])
    assert response is not None

    message = response.get("message") if isinstance(response, dict) else response
    print(f"人工判定已提交: {len(reviews)} 条 → {url}")
    print(f"后端响应: {message}")
    data = response.get("data") if isinstance(response, dict) else None
    if isinstance(data, dict) and isinstance(data.get("agreement"), dict):
        agreement = data["agreement"]
        print(f"复核汇总: reviewedCases={agreement.get('reviewedCases')} "
              f"comparableCases={agreement.get('comparableCases')} "
              f"agreementRate={agreement.get('agreementRate')} "
              f"cohensKappa={agreement.get('cohensKappa')} status={agreement.get('status')}")
        if agreement.get("warning"):
            print(f"后端警告: {agreement['warning']}")
    print(f"reviewer 本地标注: {args.reviewer}（后端实际以管理员 token 的用户名记录 reviewer）")
    return 0


def cmd_agreement(args) -> int:
    root = load_json_file(args.report)
    agreement, source = find_agreement(root)
    if agreement is None:
        print("humanAgreement 未计算：文件里没有 humanAgreement / humanReview.agreement / agreement 三处任一——")
        print("落盘报告（EvalReport）本身不带 humanAgreement，还没有人工判定时 κ 就是 null。")
        print("先 export → 人工复核 → import，再拿 GET /runs/{id} 状态 JSON 或旁路 .human-reviews.json 回来判定；不伪造数字。")
        return 2
    min_kappa = args.min_kappa
    print(f"humanAgreement 来源: {source}")
    print(f"  status: {agreement.get('status')}")
    if agreement.get("warning"):
        print(f"  warning: {agreement['warning']}")
    print(f"  reviewedCases={agreement.get('reviewedCases')} comparableCases={agreement.get('comparableCases')} "
          f"agreedCases={agreement.get('agreedCases')}")
    rate = _as_number(agreement.get("agreementRate"))
    rate_text = f"{rate:.3f}" if rate is not None else "null"
    print(f"  agreementRate: {rate_text}")
    print(format_kappa_line(agreement, min_kappa))
    disagreements = agreement.get("disagreementCaseIds")
    if isinstance(disagreements, list) and disagreements:
        preview = "、".join(str(item) for item in disagreements[:5])
        more = "" if len(disagreements) <= 5 else f"（共 {len(disagreements)} 条，仅列前 5）"
        print(f"  disagreementCaseIds: {preview}{more}")
    kappa = kappa_of(agreement)
    code, line = agreement_verdict(kappa, min_kappa)
    print(line)
    return code


# ---------------------------------------------------------------------------
# --self-test：内置 fixture，不发网络请求、不读仓库数据文件
# ---------------------------------------------------------------------------

RUN_ID = "f1e2d3c4b5a6"
MIN_KAPPA = DEFAULT_MIN_KAPPA


def _case(case_id, question, needs_human, judge_score=None, judge_reason=None,
          judge_correct=None, answer="生成答案", **extra) -> dict:
    case = {
        "caseId": case_id,
        "question": question,
        "answer": answer,
        "judgeScore": judge_score,
        "judgeReason": judge_reason,
        "judgeCorrect": judge_correct,
        "needsHumanReview": needs_human,
    }
    case.update(extra)
    return case


def fixture_report() -> dict:
    """6 条用例，3 条 needsHumanReview=true（评委双采样产物：1 条 disagree、2 条 unstable）。"""
    return {"runId": RUN_ID, "label": "self-test", "schemaVersion": "2.0", "cases": [
        _case("fact-01", "CLIP 的预训练数据规模是多少？", False, judge_score=5,
              judge_reason="与参考答案一致", judge_correct=True),
        _case("para-03", "这段结论的同义改写是什么？", False, judge_score=4,
              judge_reason="语义一致", judge_correct=True),
        # needsHumanReview=true：两次采样结论相反，judgeScore=null，judgeReason 记 A/B
        _case("multi-07", "两篇文档对同一指标的口径差异是什么？", True, judge_score=None,
              judge_reason="A=4/true; B=3/false", judge_correct=None, reviewReason="judge-disagree"),
        # needsHumanReview=true：一次采样解析失败
        _case("fact-12", "训练用了多少 GPU？", True, judge_score=None,
              judge_reason="评委输出解析失败", judge_correct=None, reviewReason="judge-unstable"),
        _case("neg-02", "语料里没有的成本是多少？", True, judge_score=2,
              judge_reason="未按预期拒答", judge_correct=False),
        _case("ocr-01", "扫描件表格里的数字是多少？", False, judge_score=3,
              judge_reason="部分正确", judge_correct=False),
    ]}


def fixture_agreement_status(kappa) -> dict:
    """GET /runs/{id} 状态 JSON 形状：data.humanAgreement 顶层。"""
    return {"code": 200, "message": "获取评测任务成功", "data": {
        "runId": RUN_ID, "state": "SUCCEEDED",
        "humanAgreement": {
            "status": "OK" if kappa is None or kappa >= MIN_KAPPA else "NEEDS_RECALIBRATION",
            "warning": None, "reviewedCases": 12, "comparableCases": 10, "agreedCases": 9,
            "agreementRate": 0.9, "cohensKappa": kappa, "judgeCorrectRate": 0.8,
            "humanCorrectRate": 0.9, "scoredCases": 5, "meanAbsoluteScoreDiff": 0.6,
            "humanOnlyCases": 1, "disagreementCaseIds": ["fact-01", "para-03"],
            "minAgreementRate": 0.8, "minKappa": MIN_KAPPA, "minComparableCases": 5,
        },
    }}


def _reviewed_file() -> dict:
    """export 输出经人工填写后的形态：3 条 verdict 已填，score/note 部分填。"""
    return {"runId": RUN_ID, "cases": [
        {"caseId": "multi-07", "question": "两篇文档对同一指标的口径差异是什么？", "answer": "生成答案",
         "judgeScore": None, "judgeReason": "A=4/true; B=3/false", "verdict": True,
         "note": "评委 B 漏看了第二篇的口径表", "score": 4},
        {"caseId": "fact-12", "question": "训练用了多少 GPU？", "answer": "生成答案",
         "judgeScore": None, "judgeReason": "评委输出解析失败", "verdict": False, "note": ""},
        {"caseId": "neg-02", "question": "语料里没有的成本是多少？", "answer": "生成答案",
         "judgeScore": 2, "judgeReason": "未按预期拒答", "verdict": False, "note": None, "score": None},
    ]}


def _run_fn(fn, *fn_args, **fn_kwargs):
    """静默捕获 stdout 跑函数，Fail 按 main() 的口径折算成退出码。返回 (返回值或退出码, stdout)。"""
    out_io = io.StringIO()
    with contextlib.redirect_stdout(out_io):
        try:
            result = fn(*fn_args, **fn_kwargs)
        except Fail as error:
            result = error.code
    return result, out_io.getvalue()


def _ns(**kwargs):
    return argparse.Namespace(**kwargs)


@contextlib.contextmanager
def _patched_environ(remove=(), **overrides):
    """临时改 os.environ（self-test 隔离宿主环境用，含仓库根 .env 的 EVAL_ENV_FILE 指向），退出时复原。"""
    saved = dict(os.environ)
    try:
        for key in remove:
            os.environ.pop(key, None)
        os.environ.update(overrides)
        yield
    finally:
        os.environ.clear()
        os.environ.update(saved)


def self_test() -> int:
    # 1) export：条数与形状
    export, total = build_export(fixture_report())
    assert total == 6, total
    assert export["runId"] == RUN_ID, export["runId"]
    assert len(export["cases"]) == 3, export["cases"]
    assert [c["caseId"] for c in export["cases"]] == ["multi-07", "fact-12", "neg-02"], \
        [c["caseId"] for c in export["cases"]]
    for entry in export["cases"]:
        assert tuple(entry.keys()) == EXPORT_KEYS, tuple(entry.keys())
        assert entry["verdict"] is None and entry["note"] == "", entry
    disagree = export["cases"][0]
    assert disagree["judgeScore"] is None and disagree["judgeReason"] == "A=4/true; B=3/false", disagree
    scored = export["cases"][2]
    assert scored["judgeScore"] == 2 and scored["judgeReason"] == "未按预期拒答", scored
    # 接口响应包裹 {code, message, data} 也能导出；judgeScore=true 这类损坏值记 None 而不是 1
    wrapped, _ = build_export({"code": 200, "message": "ok", "data": fixture_report()})
    assert wrapped == export, "unwrap_data 应展开接口包裹"
    poisoned = build_export({"runId": RUN_ID, "cases": [_case("x", "q", True, judge_score=True)]})[0]
    assert poisoned["cases"][0]["judgeScore"] is None, poisoned

    # 2) export 落盘 round-trip
    with tempfile.TemporaryDirectory(prefix="eval-human-review-selftest-") as directory:
        out_path = Path(directory) / "nested" / "review.json"
        code, out = _run_fn(cmd_export, _ns(report="/nonexistent-report.json", out=str(out_path)))
        # 报告不存在 → 退出 2，不写文件
        assert code == 2, (code, out)
        report_path = Path(directory) / "report.json"
        report_path.write_text(json.dumps(fixture_report(), ensure_ascii=False), encoding="utf-8")
        code, out = _run_fn(cmd_export, _ns(report=str(report_path), out=str(out_path)))
        assert code == 0, (code, out)
        assert f"已导出 3/6 条 needsHumanReview 用例（runId={RUN_ID}）" in out, out
        round_trip = json.loads(out_path.read_text(encoding="utf-8"))
        assert round_trip == export, round_trip
        agreement_missing_path = Path(directory) / "report-no-agreement.json"
        agreement_missing_path.write_text(json.dumps({"runId": RUN_ID, "cases": []}, ensure_ascii=False),
                                          encoding="utf-8")

    # 3) import payload 形状：键序 = HumanReviewInput 分量序，verdict → correct
    payload = build_review_payload(_reviewed_file(), RUN_ID)
    assert list(payload.keys()) == ["reviews"], payload.keys()
    reviews = payload["reviews"]
    assert len(reviews) == 3, reviews
    for entry in reviews:
        assert tuple(entry.keys()) == INPUT_KEYS, tuple(entry.keys())
    assert reviews[0] == {"caseId": "multi-07", "correct": True, "score": 4,
                          "note": "评委 B 漏看了第二篇的口径表"}, reviews[0]
    assert reviews[1] == {"caseId": "fact-12", "correct": False, "score": None, "note": ""}, reviews[1]
    assert reviews[2] == {"caseId": "neg-02", "correct": False, "score": None, "note": None}, reviews[2]
    # 裸数组形态也接受
    assert build_review_payload(_reviewed_file()["cases"], RUN_ID) == payload

    def _expect_fail(code_expected, fn, *fn_args, fragment=None):
        try:
            fn(*fn_args)
        except Fail as error:
            assert error.code == code_expected, (error.code, error)
            if fragment:
                assert fragment in str(error), (fragment, error)
            return str(error)
        raise AssertionError(f"期望 Fail({code_expected})，但成功了")

    # 4) import 客户端校验：缺 verdict / 字段不合法 / runId 不一致 / 空文件 / 超上限，全部退出 2 且不发网络
    entries = [dict(entry, verdict=None) for entry in _reviewed_file()["cases"]]
    _expect_fail(2, build_review_payload, {"runId": RUN_ID, "cases": entries}, RUN_ID,
                 fragment="还没填 verdict")
    _expect_fail(2, build_review_payload, _reviewed_file(), "other-run", fragment="不一致")
    _expect_fail(2, build_review_payload, {"runId": RUN_ID, "cases": [{"caseId": "c1"}]}, RUN_ID,
                 fragment="还没填 verdict")
    _expect_fail(2, build_review_payload, {"runId": RUN_ID, "cases": [{"verdict": True}]}, RUN_ID,
                 fragment="缺 caseId")
    for bad_score in (0, 6, 4.5, "4", True):
        bad = [dict(_reviewed_file()["cases"][0], score=bad_score)]
        message = _expect_fail(2, build_review_payload, {"runId": RUN_ID, "cases": bad}, RUN_ID,
                               fragment="score")
        assert repr(bad_score) in message or "score" in message, message
    over_note = [dict(_reviewed_file()["cases"][0], note="x" * (MAX_NOTE_CHARS + 1))]
    _expect_fail(2, build_review_payload, {"runId": RUN_ID, "cases": over_note}, RUN_ID,
                 fragment=f"超过 {MAX_NOTE_CHARS}")
    edge_note = [dict(_reviewed_file()["cases"][0], note="x" * MAX_NOTE_CHARS)]
    assert len(build_review_payload({"runId": RUN_ID, "cases": edge_note}, RUN_ID)["reviews"]) == 1
    _expect_fail(2, build_review_payload, {"runId": RUN_ID, "cases": []}, RUN_ID,
                 fragment="没有任何带 verdict 的用例")
    big_file = {"runId": RUN_ID, "cases": [{"caseId": f"c{i}", "verdict": True} for i in range(DEFAULT_MAX_CASES + 1)]}
    _expect_fail(2, build_review_payload, big_file, RUN_ID, fragment="分批 import")
    _expect_fail(2, build_review_payload, {"foo": "bar"}, RUN_ID, fragment="复核文件必须是")

    # 5) κ 解析与判定：状态 JSON 形状
    code, out = _run_fn(cmd_agreement, _ns(report="/nonexistent.json", min_kappa=MIN_KAPPA))
    assert code == 2, (code, out)  # 文件不存在 → 退出 2
    with tempfile.TemporaryDirectory(prefix="eval-human-review-selftest-") as directory:
        def _write_and_run(obj, min_kappa=MIN_KAPPA):
            path = Path(directory) / "status.json"
            path.write_text(json.dumps(obj, ensure_ascii=False), encoding="utf-8")
            return _run_fn(cmd_agreement, _ns(report=str(path), min_kappa=min_kappa))

        code, out = _write_and_run(fixture_agreement_status(0.71))
        assert code == 0, (code, out)
        assert "humanAgreement（评测状态 JSON 顶层" in out, out
        assert "cohensKappa: 0.710" in out and "达标（退出 0）" in out, out
        code, out = _write_and_run(fixture_agreement_status(0.42))
        assert code == 1, (code, out)
        assert "0.420 < 0.6" in out and "低于阈值" in out, out
        code, out = _write_and_run(fixture_agreement_status(0.6))
        assert code == 0, (code, out)  # 恰好等于阈值不算低于
        code, out = _write_and_run(fixture_agreement_status(None))
        assert code == 2, (code, out)
        assert "cohensKappa: null" in out and "未计算" in out, out
        code, out = _write_and_run({"runId": RUN_ID, "cases": []})
        assert code == 2, (code, out)
        assert "humanAgreement 未计算" in out and "不伪造数字" in out, out
        code, out = _write_and_run(fixture_agreement_status(0.42), min_kappa=0.3)
        assert code == 0, (code, out)  # --min-kappa 可下调
        # 报告接口形状：humanReview.agreement
        report_endpoint = {"code": 200, "data": dict(fixture_report(), humanReview={
            "reviews": [], "agreement": fixture_agreement_status(0.42)["data"]["humanAgreement"]})}
        code, out = _write_and_run(report_endpoint)
        assert code == 1 and "humanReview.agreement" in out, (code, out)
        # 旁路文件形状：顶层 agreement
        sidecar = {"runId": RUN_ID, "reportFile": "x.json",
                   "agreement": fixture_agreement_status(0.42)["data"]["humanAgreement"]}
        code, out = _write_and_run(sidecar)
        assert code == 1 and "旁路 .human-reviews.json" in out, (code, out)
        # 优先级：humanAgreement > humanReview.agreement
        both = {"code": 200, "data": {"runId": RUN_ID,
                                      "humanAgreement": dict(fixture_agreement_status(0.9)["data"]["humanAgreement"]),
                                      "humanReview": {"agreement": fixture_agreement_status(0.1)["data"]["humanAgreement"]}}}
        code, out = _write_and_run(both)
        assert code == 0 and "评测状态 JSON 顶层" in out, (code, out)
    # κ 纯函数：布尔 cohensKappa 视为不可判定
    assert kappa_of({"cohensKappa": True}) is None
    assert kappa_of({"cohensKappa": 0.5}) == 0.5
    assert kappa_of({}) is None
    assert agreement_verdict(None, 0.6)[0] == 2
    assert agreement_verdict(0.6, 0.6)[0] == 0
    assert agreement_verdict(0.59, 0.6)[0] == 1

    # 6) 凭据三级顺序与 .env 解析（注入 env，不碰真实环境，不回显值）
    with tempfile.TemporaryDirectory(prefix="eval-human-review-selftest-") as directory:
        env_path = Path(directory) / ".env"
        env_path.write_text(
            "# 注释行\n\n"
            "ADMIN_BOOTSTRAP_USERNAME=\"bootstrap-admin\"\n"
            "ADMIN_BOOTSTRAP_PASSWORD='bootstrap-pass'\n"
            "OTHER_KEY=should-not-matter\n", encoding="utf-8")
        parsed = read_env_file(env_path)
        assert parsed["ADMIN_BOOTSTRAP_USERNAME"] == "bootstrap-admin", parsed.keys()
        assert parsed["ADMIN_BOOTSTRAP_PASSWORD"] == "bootstrap-pass", parsed.keys()
        assert parsed["OTHER_KEY"] == "should-not-matter"
        assert read_env_file(Path(directory) / "missing.env") == {}

        fake_env = {"EVAL_ADMIN_TOKEN": " t1 ", "EVAL_ADMIN_USERNAME": "u", "EVAL_ADMIN_PASSWORD": ""}
        kind, value, description = credential_plan(environ=fake_env, env_values={})
        assert kind == "token" and value == "t1" and "一级" in description, (kind, description)
        kind, value, description = credential_plan(
            environ={"EVAL_ADMIN_USERNAME": "u", "EVAL_ADMIN_PASSWORD": "p"}, env_values={})
        assert kind == "credentials" and value == ("u", "p") and "二级" in description, (kind, description)
        kind, value, description = credential_plan(environ={}, env_values=parsed)
        assert kind == "credentials" and value == ("bootstrap-admin", "bootstrap-pass") and "三级" in description, (kind, description)
        kind, missing, _ = credential_plan(environ={"EVAL_ADMIN_USERNAME": "u"}, env_values={})
        assert kind == "missing" and missing == [
            "EVAL_ADMIN_TOKEN（环境变量）",
            "EVAL_ADMIN_USERNAME + EVAL_ADMIN_PASSWORD（环境变量，缺 EVAL_ADMIN_PASSWORD）",
            "ADMIN_BOOTSTRAP_USERNAME + ADMIN_BOOTSTRAP_PASSWORD（仓库根 .env，缺 ADMIN_BOOTSTRAP_USERNAME、ADMIN_BOOTSTRAP_PASSWORD）",
        ], missing
        assert resolve_credentials_only(environ={}, env_values=parsed) == ("bootstrap-admin", "bootstrap-pass",
                                                                           "ADMIN_BOOTSTRAP_USERNAME/ADMIN_BOOTSTRAP_PASSWORD（仓库根 .env）")
        assert resolve_credentials_only(environ={}, env_values={}) is None

        # 7) import --dry-run：校验通过即退出 0，不发网络；凭据三级缺失也允许 dry-run。
        #    patch os.environ 与 EVAL_ENV_FILE，与宿主真实环境（含仓库根 .env）隔离。
        file_path = Path(directory) / "review.json"
        file_path.write_text(json.dumps(_reviewed_file(), ensure_ascii=False), encoding="utf-8")
        with _patched_environ(remove=("EVAL_ADMIN_TOKEN", "EVAL_ADMIN_USERNAME", "EVAL_ADMIN_PASSWORD"),
                              EVAL_ENV_FILE=str(Path(directory) / "missing.env")):
            code, out = _run_fn(cmd_import, _ns(run=RUN_ID, file=str(file_path), reviewer="张三", dry_run=True))
            assert code == 0, (code, out)
            assert "dry-run：只校验文件与打印计划" in out, out
            assert "POST " in out and "human-reviews" in out, out
            assert "提交条数: 3" in out and "HumanReviewInput" in out, out
            assert "三级均未配置" in out, out  # 凭据缺失不拦截 dry-run
            bad_file = Path(directory) / "bad.json"
            bad_file.write_text(json.dumps({"runId": "other", "cases": _reviewed_file()["cases"]},
                                           ensure_ascii=False), encoding="utf-8")
            code, out = _run_fn(cmd_import, _ns(run=RUN_ID, file=str(bad_file), reviewer="张三", dry_run=True))
            assert code == 2, (code, out)  # runId 不一致在 dry-run 也拦下

    print(f"self-test OK: 人工复核导出/回填 CLI（{len(EXPORT_KEYS)} 个导出字段 / "
          f"{len(INPUT_KEYS)} 个提交字段，字段序与 EvalHumanReviewService.HumanReviewInput 一致）")
    print("  - export：6 条报告导出 3 条 needsHumanReview，7 字段固定形状 verdict=null note=\"\"，接口包裹自动展开")
    print("  - import payload：verdict→correct、score 1-5、note≤500 全部客户端校验，坏文件退出 2 不发网络；dry-run 无凭据退出 0")
    print("  - agreement：三处取数位置（状态 JSON / 报告接口 / 旁路文件）与优先级，κ 0.71→0、0.42→1、null/缺失→2 未计算")
    print("  - 凭据三级顺序（EVAL_ADMIN_TOKEN → EVAL_ADMIN_*.env → .env ADMIN_BOOTSTRAP_*）注入式断言，全程不回显值")
    return 0


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="人工复核导出/回填 CLI（详见模块 docstring）：export / import / agreement / --self-test")
    parser.add_argument("--self-test", dest="self_test", action="store_true",
                        help="内置 fixture 自检：断言导出条数、payload 形状与 κ 解析，不发网络请求")
    subparsers = parser.add_subparsers(dest="command")
    parser_export = subparsers.add_parser("export", help="从评测报告导出 needsHumanReview 用例（verdict 留空）")
    parser_export.add_argument("--report", required=True, help="评测报告 JSON（落盘报告或报告接口响应均可）")
    parser_export.add_argument("--out", required=True, help="人工复核文件输出路径")
    parser_export.set_defaults(func=cmd_export)
    parser_import = subparsers.add_parser("import", help="把人工填好的复核文件 POST 回评测运行")
    parser_import.add_argument("--run", required=True, help="评测 runId")
    parser_import.add_argument("--file", required=True, help="复核文件（export 输出经人工填写，或裸用例数组）")
    parser_import.add_argument("--reviewer", required=True, help="复核人姓名（仅本地标注；后端以 token 用户名记录）")
    parser_import.add_argument("--dry-run", dest="dry_run", action="store_true",
                               help="只校验文件并打印计划：不发网络、不要求 token")
    parser_import.set_defaults(func=cmd_import)
    parser_agreement = subparsers.add_parser("agreement", help="打印 humanAgreement 并按 κ 阈值判定")
    parser_agreement.add_argument("--report", required=True,
                                  help="评测状态 JSON / 报告接口响应 / 旁路 .human-reviews.json")
    parser_agreement.add_argument("--min-kappa", dest="min_kappa", type=float, default=DEFAULT_MIN_KAPPA,
                                  help=f"κ 下限（默认 {DEFAULT_MIN_KAPPA}，与 RagEvalProperties.HumanAgreement 同源）")
    parser_agreement.set_defaults(func=cmd_agreement)

    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    if args.self_test and args.command:
        parser.error("--self-test 与子命令只能二选一")
    if args.self_test:
        return self_test()
    if not args.command:
        parser.print_help(sys.stderr)
        return 2
    try:
        return args.func(args)
    except Fail as error:
        print(f"eval_human_review: {error}", file=sys.stderr)
        return error.code


if __name__ == "__main__":
    sys.exit(main())
