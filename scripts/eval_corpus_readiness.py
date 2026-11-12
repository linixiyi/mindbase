#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""语料就绪检查脚本（task-0002，eval-real-run 包）。

读 eval-datasets/manifest.json 的每个 md5，逐个向 ES 发 POST {index}/_count
（term fileMd5），把「manifest ↔ ES 分块数」对上：任一文件分块数低于
--min-chunks（默认 1）即语料未就绪，退出 1 并列出缺哪几篇与补救命令。
真机评测提交前先跑本脚本 --check，避免在空召回上白烧 20 分钟模型 token。

模式：
  （默认）        只读报告：打印 markdown 表（文件名、md5、期望页数、实际分块数、状态）。
  --check        就绪门禁：任一文件分块数 < --min-chunks 退出 1；
                 无法判定（ES 连接失败/认证失败/索引不存在/查询失败/manifest 问题）退出 2。
  --json         改用 JSON 输出（字段同表 + 汇总与补救建议），可与 --check 组合。
  --self-test    离线自检：fixture JSON 覆盖「全齐 / 缺一篇 / 全空 / ES 不可达」四种场景，
                 注入 fake HTTP，不联网、不读真实 .env。

ES 连接（ELASTICSEARCH_* 环境变量，进程环境优先、仓库根 .env 兜底）：
  ELASTICSEARCH_SCHEME    默认 http
  ELASTICSEARCH_HOST      默认 localhost
  ELASTICSEARCH_PORT      默认 9200
  ELASTICSEARCH_USERNAME  默认 elastic
  ELASTICSEARCH_PASSWORD  默认空
  EVAL_ES_INDEX           ES 索引/别名，默认 knowledge_base（KnowledgeIndexNames.ALIAS）
  scheme=https 时按自签证书处理：不校验证书（对应后端 insecure-trust-all-certificates=true）。
  本脚本对 ES 只读（只发 POST {index}/_count）。

退出码：
  0  纯报告成功；或 --check 且全部文件分块数 >= min-chunks（语料就绪）
  1  --check：至少一个文件分块数 < min-chunks（列出缺哪几篇）
  2  无法判定：ES 连接失败 / 认证失败 / 索引不存在 / 查询失败 / manifest 或参数问题

凭据纪律：ES 口令只从环境变量或仓库根 .env 读取，只进内存；不打印、不落盘、不进
任何日志；401 时不猜默认密码，直接给出需要设置的环境变量名。

只依赖 Python 3 标准库，不新增依赖（spec D1）。
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import ssl
import sys
import tempfile
import urllib.error
import urllib.request

# ---- 常量 -------------------------------------------------------------------
DEFAULT_ES_INDEX = "knowledge_base"   # KnowledgeIndexNames.ALIAS（KnowledgeIndexNames.java:12）
DEFAULT_ES_TIMEOUT = 15.0             # 单次 ES 请求超时（秒）
MD5_RE = re.compile(r"^[0-9a-fA-F]{32}$")

ST_READY = "就绪"
ST_MISSING = "缺失"          # 分块数 0
ST_INSUFFICIENT = "不足"      # 0 < 分块数 < min-chunks
ST_QUERY_FAILED = "查询失败"  # ES 有响应但本次查询失败（401/404/5xx/坏 JSON）
ST_CONN_FAILED = "连接失败"   # 连接层失败（ES 不可达）——不是「分块为 0」


class EvalReadinessError(Exception):
    """可预期的操作失败（配置/静态级），退出 2。"""


class TransportError(EvalReadinessError):
    """网络/连接层失败（与 HTTP 4xx/5xx 区分：报「连接失败」而非「分块为 0」）。"""


# ---------------------------------------------------------------------------
# manifest 加载（与 scripts/eval_corpus_ingest.py 同口径；pages 可选）
# ---------------------------------------------------------------------------

def load_manifest(path: str) -> list:
    """读 manifest.json，校验结构；返回 documents 列表。结构问题抛 EvalReadinessError。"""
    if not os.path.isfile(path):
        raise EvalReadinessError(f"manifest 不存在: {path}")
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        raise EvalReadinessError(f"manifest 不可读或不是合法 JSON: {path}（{e}）")
    if not isinstance(data, dict):
        raise EvalReadinessError(f"manifest 顶层必须是 JSON 对象: {path}")
    docs = data.get("documents")
    if not isinstance(docs, list) or not docs:
        raise EvalReadinessError(f"manifest 缺少非空 documents 数组: {path}")
    problems = []
    cleaned = []
    for i, doc in enumerate(docs):
        if not isinstance(doc, dict):
            problems.append(f"documents[{i}] 不是对象")
            continue
        name = doc.get("name")
        md5 = doc.get("md5")
        if not isinstance(name, str) or not name.strip():
            problems.append(f"documents[{i}] 缺少 name")
            continue
        if "/" in name or "\\" in name or ".." in name:
            problems.append(f"{name}: name 含路径分隔符，拒绝（语料文件名应是纯文件名）")
            continue
        if not isinstance(md5, str) or not MD5_RE.match(md5):
            problems.append(f"{name}: md5 缺失或不是 32 位十六进制")
            continue
        cleaned.append(doc)
    if problems:
        raise EvalReadinessError("manifest 校验失败:\n  - " + "\n  - ".join(problems))
    return cleaned


# ---------------------------------------------------------------------------
# .env 读取（口径与 eval_corpus_ingest.py 一致；只取值，绝不回显）
# ---------------------------------------------------------------------------

def parse_env_text(text: str, key: str) -> str:
    """从 .env 文本取 KEY=VALUE 的值（取最后一次出现；容忍行首空白、\\r、成对引号）。"""
    value = ""
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        if "=" not in stripped:
            continue
        k, _, v = stripped.partition("=")
        if k.strip() != key:
            continue
        value = v.strip().rstrip("\r").strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
    return value


def env_file_get(env_file: str, key: str) -> str:
    if not env_file or not os.path.isfile(env_file):
        return ""
    try:
        with open(env_file, "r", encoding="utf-8", errors="replace") as f:
            return parse_env_text(f.read(), key)
    except OSError:
        return ""


def env_lookup(env: dict, env_file: str, key: str) -> str:
    """先进程环境变量，后仓库根 .env。缺失返回空串。"""
    value = env.get(key, "")
    if isinstance(value, str) and value.strip():
        return value.strip()
    return env_file_get(env_file, key)


# ---------------------------------------------------------------------------
# ES 配置与 HTTP 层（http_fn 可注入；self-test 用 fake，不联网）
# ---------------------------------------------------------------------------

class EsConfig:
    def __init__(self, url: str, index: str, auth_header: str = "", timeout: float = DEFAULT_ES_TIMEOUT):
        self.url = url.rstrip("/")
        self.index = index
        self.auth_header = auth_header
        self.timeout = timeout

    def headers(self) -> dict:
        h = {"Content-Type": "application/json"}
        if self.auth_header:
            h["Authorization"] = self.auth_header
        return h


def build_es_config(env: dict, env_file: str) -> EsConfig:
    """ELASTICSEARCH_* 环境变量（缺省 elastic/空、http://localhost:9200），进程环境优先、.env 兜底。

    scheme=https 时按自签证书处理（连接层不校验证书，对应
    insecure-trust-all-certificates=true）；本脚本对 ES 只读。
    """
    scheme = (env_lookup(env, env_file, "ELASTICSEARCH_SCHEME") or "http").lower()
    host = env_lookup(env, env_file, "ELASTICSEARCH_HOST") or "localhost"
    port = env_lookup(env, env_file, "ELASTICSEARCH_PORT") or "9200"
    user = env_lookup(env, env_file, "ELASTICSEARCH_USERNAME") or "elastic"
    pwd = env_lookup(env, env_file, "ELASTICSEARCH_PASSWORD") or ""
    index = env_lookup(env, env_file, "EVAL_ES_INDEX") or DEFAULT_ES_INDEX
    if scheme not in ("http", "https"):
        raise EvalReadinessError(f"ELASTICSEARCH_SCHEME 只支持 http/https，当前: {scheme}")
    url = f"{scheme}://{host}:{port}"
    auth_header = ""
    if user:  # 用户名非空即带 Basic（密码可为空串）；默认 elastic/空，与 spec A2 探针口径一致
        auth_header = "Basic " + base64.b64encode(f"{user}:{pwd}".encode()).decode()
    return EsConfig(url=url, index=index, auth_header=auth_header)


def build_insecure_ssl_context() -> ssl.SSLContext:
    """https 自签证书场景：不校验证书（只读查询，风险可控，与后端 dev 配置一致）。"""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def default_http(method: str, url: str, headers: dict, data, timeout: float):
    """真 HTTP：urllib。返回 (status:int, body:bytes)。https 自签证书不校验。"""
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    ctx = None
    if url.lower().startswith("https:"):
        ctx = build_insecure_ssl_context()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            return int(resp.getcode()), resp.read()
    except urllib.error.HTTPError as e:
        return int(e.code), e.read() or b""
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise TransportError(f"连接失败: {getattr(e, 'reason', e) or e}")


# ---------------------------------------------------------------------------
# ES count 响应解析（纯函数）
# ---------------------------------------------------------------------------

def parse_count_response(status: int, body: bytes) -> tuple:
    """ES POST /{index}/_count。返回 (ok, count:int|None, error:str)。

    401/404 是「无法判定」而非「分块为 0」：错误信息给出环境变量名/索引名排查方向。
    """
    if status == 401:
        return False, None, ("ES 认证失败（401）：设置 ELASTICSEARCH_USERNAME / "
                             "ELASTICSEARCH_PASSWORD 环境变量后重试（本脚本不猜默认密码）")
    if status == 404:
        return False, None, "ES 索引/别名不存在（404；检查 EVAL_ES_INDEX，后端启动会建别名 knowledge_base）"
    if not body:
        return False, None, f"ES 响应体为空（HTTP {status}）"
    try:
        payload = json.loads(body.decode("utf-8", errors="replace"))
    except ValueError as e:
        return False, None, f"ES 响应不是合法 JSON: {e}"
    if not isinstance(payload, dict):
        return False, None, "ES 响应 JSON 顶层不是对象"
    if status != 200:
        reason = ""
        err = payload.get("error")
        if isinstance(err, dict):
            reason = str(err.get("type") or "") + ": " + str(err.get("reason") or "")
        elif err is not None:
            reason = str(err)
        return False, None, f"ES _count HTTP {status}：{reason or '（无错误详情）'}"
    count = payload.get("count")
    if not isinstance(count, int) or count < 0:
        return False, None, "ES _count 响应缺合法 count 字段"
    return True, count, ""


def es_count_once(es_cfg: EsConfig, file_md5: str, http_fn) -> tuple:
    """单次 ES count。返回 (ok, count, error, transport)；transport=True 表示连接层失败。"""
    query = json.dumps({"query": {"term": {"fileMd5": file_md5}}}, ensure_ascii=False).encode("utf-8")
    try:
        status, body = http_fn("POST", f"{es_cfg.url}/{es_cfg.index}/_count",
                               headers=es_cfg.headers(), data=query, timeout=es_cfg.timeout)
    except TransportError as e:
        return False, None, str(e), True
    except OSError as e:
        return False, None, f"连接失败: {e}", True
    ok, count, error = parse_count_response(status, body)
    return ok, count, error, False


# ---------------------------------------------------------------------------
# 就绪判定（纯函数）
# ---------------------------------------------------------------------------

def classify_status(count, min_chunks: int) -> str:
    """按分块数与下限给状态；count=None（查询失败）单独成状态。"""
    if count is None:
        return ST_QUERY_FAILED
    if count >= min_chunks:
        return ST_READY
    if count == 0:
        return ST_MISSING
    return ST_INSUFFICIENT


def collect_rows(docs: list, es_cfg: EsConfig, min_chunks: int, http_fn) -> tuple:
    """逐文件查 ES。返回 (rows, connection_error)。

    row: {"name","md5","expectedPages","chunks","status","error"}
    连接层失败时快速终止：余下文件一律标「连接失败」（不标「缺失」）。
    """
    rows = []
    connection_error = ""
    for doc in docs:
        name = doc["name"]
        md5 = doc["md5"].lower()
        pages = doc.get("pages")
        expected_pages = pages if isinstance(pages, int) and pages > 0 else None
        row = {"name": name, "md5": md5, "expectedPages": expected_pages,
               "chunks": None, "status": ST_QUERY_FAILED, "error": ""}
        if connection_error:
            row["status"] = ST_CONN_FAILED
            row["error"] = connection_error
            rows.append(row)
            continue
        ok, count, error, is_transport = es_count_once(es_cfg, md5, http_fn)
        if not ok and is_transport:
            connection_error = error
            row["status"] = ST_CONN_FAILED
            row["error"] = error
        elif not ok:
            row["status"] = ST_QUERY_FAILED
            row["error"] = error
        else:
            row["chunks"] = count
            row["status"] = classify_status(count, min_chunks)
        rows.append(row)
    return rows, connection_error


def decide_exit(rows: list, connection_error: str, is_check: bool) -> int:
    """退出码：无法判定→2；--check 且有低于下限→1；其余→0。"""
    if connection_error:
        return 2
    if any(r["status"] in (ST_QUERY_FAILED, ST_CONN_FAILED) for r in rows):
        return 2
    if is_check and any(r["status"] in (ST_MISSING, ST_INSUFFICIENT) for r in rows):
        return 1
    return 0


def summarize(rows: list) -> dict:
    return {
        "total": len(rows),
        "ready": sum(1 for r in rows if r["status"] == ST_READY),
        "missing": sum(1 for r in rows if r["status"] == ST_MISSING),
        "insufficient": sum(1 for r in rows if r["status"] == ST_INSUFFICIENT),
        "queryFailed": sum(1 for r in rows if r["status"] == ST_QUERY_FAILED),
        "connectionFailed": any(r["status"] == ST_CONN_FAILED for r in rows),
    }


def build_remediation(rows: list, connection_error: str) -> list:
    """可直接执行的补救建议（静态字符串，不含任何凭据）。"""
    tips = []
    if connection_error:
        tips.append("先恢复 ES 连接：确认 ES 容器在跑、ELASTICSEARCH_SCHEME/HOST/PORT 正确"
                    "（默认 http://localhost:9200，详见 docs/eval/runbook.md）")
    below = [r for r in rows if r["status"] in (ST_MISSING, ST_INSUFFICIENT)]
    if below:
        names = ", ".join(r["name"] for r in below)
        tips.append(f"未就绪 {len(below)} 篇：{names}")
        if len(below) == 1:
            tips.append(f"补救：python3 scripts/eval_corpus_ingest.py --run --only {below[0]['name']}")
        else:
            tips.append("补救：python3 scripts/eval_corpus_ingest.py --run（dry-run/续传见 docs/eval/corpus-ingest.md）")
    failed = [r for r in rows if r["status"] == ST_QUERY_FAILED]
    if failed:
        tips.append(f"{len(failed)} 篇 ES 查询失败（各文件 error 有具体原因）；先解决 ES 可用性再重跑 --check")
    return tips


# ---------------------------------------------------------------------------
# 输出渲染
# ---------------------------------------------------------------------------

def render_markdown(rows: list) -> str:
    lines = ["| 文件名 | md5 | 期望页数 | 实际分块数 | 状态 |",
             "| --- | --- | --- | --- | --- |"]
    for r in rows:
        pages = r["expectedPages"] if r["expectedPages"] is not None else "-"
        chunks = r["chunks"] if r["chunks"] is not None else "-"
        lines.append(f"| {r['name']} | {r['md5']} | {pages} | {chunks} | {r['status']} |")
    return "\n".join(lines)


def build_payload(es_cfg: EsConfig, rows: list, connection_error: str,
                  min_chunks: int, is_check: bool, exit_code: int) -> dict:
    """JSON 输出结构（不含任何凭据）。"""
    return {
        "es": {
            "url": es_cfg.url,
            "index": es_cfg.index,
            "reachable": not connection_error,
            "error": connection_error or None,
        },
        "minChunks": min_chunks,
        "check": bool(is_check),
        "ready": exit_code == 0 and (not is_check or all(r["status"] == ST_READY for r in rows)),
        "exitCode": exit_code,
        "files": [
            {
                "name": r["name"],
                "md5": r["md5"],
                "expectedPages": r["expectedPages"],
                "chunks": r["chunks"],
                "status": r["status"],
                "error": r["error"] or None,
            }
            for r in rows
        ],
        "summary": summarize(rows),
        "remediation": build_remediation(rows, connection_error),
    }


def render_json(es_cfg: EsConfig, rows: list, connection_error: str,
                min_chunks: int, is_check: bool, exit_code: int) -> str:
    return json.dumps(build_payload(es_cfg, rows, connection_error, min_chunks, is_check, exit_code),
                      ensure_ascii=False, indent=2)


# ---------------------------------------------------------------------------
# 命令实现
# ---------------------------------------------------------------------------

def resolve_default_env_file() -> str:
    here = os.path.dirname(os.path.abspath(__file__))
    return os.environ.get("EVAL_ENV_FILE") or os.path.join(here, "..", ".env")


def run_check(manifest_path: str, es_cfg: EsConfig, min_chunks: int, http_fn,
              is_check: bool) -> tuple:
    """load_manifest → 逐文件查 ES → 判退出码。返回 (rows, connection_error, exit_code)。"""
    docs = load_manifest(manifest_path)
    rows, connection_error = collect_rows(docs, es_cfg, min_chunks, http_fn)
    exit_code = decide_exit(rows, connection_error, is_check)
    return rows, connection_error, exit_code


def validate_min_chunks(value: int) -> int:
    if value < 0:
        raise EvalReadinessError(f"--min-chunks 不能为负: {value}")
    return value


def cmd_report(args) -> int:
    """默认/报告/门禁模式。退出码见模块 docstring。"""
    min_chunks = validate_min_chunks(args.min_chunks)
    es_cfg = build_es_config(os.environ, args.env_file)
    if args.es_timeout is not None:
        if args.es_timeout <= 0:
            raise EvalReadinessError(f"--es-timeout 必须为正数: {args.es_timeout}")
        es_cfg.timeout = args.es_timeout

    rows, connection_error, exit_code = run_check(
        args.manifest, es_cfg, min_chunks, default_http, is_check=args.check)

    if args.json:
        print(render_json(es_cfg, rows, connection_error, min_chunks, args.check, exit_code))
        return exit_code

    mode = "check" if args.check else "报告"
    print(f"==> 语料就绪检查（task-0002）[{mode}]：manifest={args.manifest} "
          f"ES={es_cfg.url}/{es_cfg.index} min-chunks={min_chunks}")
    print(render_markdown(rows))
    s = summarize(rows)
    parts = [f"就绪 {s['ready']}/{s['total']}"]
    if s["missing"]:
        parts.append(f"缺失 {s['missing']}")
    if s["insufficient"]:
        parts.append(f"不足 {s['insufficient']}")
    if s["queryFailed"]:
        parts.append(f"查询失败 {s['queryFailed']}")
    if s["connectionFailed"]:
        parts.append("ES 连接失败")
    print(f"==> 汇总：{'；'.join(parts)}")
    tips = build_remediation(rows, connection_error)
    if exit_code == 0 and args.check:
        print("==> 语料就绪 PASS")
    for tip in tips:
        if exit_code != 0:
            print(f"!! {tip}", file=sys.stderr)
        else:
            print(f"    NOTE: {tip}")
    if exit_code == 2:
        detail = connection_error or f"{s['queryFailed']} 篇查询失败（见上表各行 error/状态）"
        print(f"!! 无法判定语料就绪（exit 2）：{detail}", file=sys.stderr)
    elif exit_code == 1:
        print("!! 语料未就绪（exit 1）：先入库再提交真机评测", file=sys.stderr)
    return exit_code


# ---------------------------------------------------------------------------
# --self-test（离线：fixture JSON + 注入 fake HTTP，不联网、不读真实 .env）
# ---------------------------------------------------------------------------

def _fixture_docs() -> list:
    """三篇 fixture：a/b 有 pages，c 无 pages（期望页数应显示 -）。"""
    return [
        {"name": "a.pdf", "title": "A", "pages": 10, "md5": "a" * 32},
        {"name": "b.pdf", "title": "B", "pages": 20, "md5": "b" * 32},
        {"name": "c.pdf", "title": "C", "md5": "c" * 32},
    ]


def _write_manifest_file(directory: str, docs: list) -> str:
    path = os.path.join(directory, "manifest.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"documents": docs}, f, ensure_ascii=False)
    return path


def _fixture_es_http(counts_by_md5=None, raise_error=None, status_by_md5=None):
    """fake ES：按请求体里的 term fileMd5 返回 count/状态码；raise_error 模拟不可达。"""
    calls = []

    def http_fn(method, url, headers=None, data=None, timeout=15.0):
        calls.append({"method": method, "url": url, "headers": dict(headers or {}), "data": data})
        if raise_error is not None:
            raise raise_error
        md5 = json.loads(data.decode())["query"]["term"]["fileMd5"]
        status = (status_by_md5 or {}).get(md5, 200)
        if status != 200:
            err_type = {401: "security_exception", 404: "index_not_found_exception"}.get(status, "es_rejected")
            return status, json.dumps({"error": {"type": err_type, "reason": "fixture"}}).encode()
        return 200, json.dumps({"count": (counts_by_md5 or {}).get(md5, 0)}).encode()

    return http_fn, calls


def _es_fixture() -> EsConfig:
    return EsConfig(url="http://es-fixture:9200", index="knowledge_base", auth_header="Basic eC4=")


def self_test() -> int:
    tests = []
    register = lambda name, fn: tests.append((name, fn))

    # ---- 场景 1：全齐 → check 退出 0 ---------------------------------------
    def test_all_ready():
        with tempfile.TemporaryDirectory() as tmp:
            manifest = _write_manifest_file(tmp, _fixture_docs())
            http_fn, calls = _fixture_es_http({"a" * 32: 12, "b" * 32: 5, "c" * 32: 3})
            rows, conn_err, code = run_check(manifest, _es_fixture(), 1, http_fn, is_check=True)
            assert code == 0 and not conn_err
            assert [r["status"] for r in rows] == [ST_READY] * 3
            assert [r["chunks"] for r in rows] == [12, 5, 3]
            # 查询口径：POST {index}/_count，term fileMd5
            assert calls[0]["method"] == "POST"
            assert calls[0]["url"] == "http://es-fixture:9200/knowledge_base/_count"
            assert json.loads(calls[0]["data"].decode()) == {"query": {"term": {"fileMd5": "a" * 32}}}
            # markdown 表与 JSON 字段
            table = render_markdown(rows)
            assert "| 文件名 | md5 | 期望页数 | 实际分块数 | 状态 |" in table
            assert "| a.pdf | " + "a" * 32 + " | 10 | 12 | 就绪 |" in table
            assert "| c.pdf | " + "c" * 32 + " | - | 3 | 就绪 |" in table  # 无 pages → "-"
            es = _es_fixture()
            payload = json.loads(render_json(es, rows, "", 1, True, code))
            assert payload["ready"] is True and payload["exitCode"] == 0
            assert payload["summary"] == {"total": 3, "ready": 3, "missing": 0,
                                          "insufficient": 0, "queryFailed": 0,
                                          "connectionFailed": False}
            for f in payload["files"]:
                assert {"name", "md5", "expectedPages", "chunks", "status"} <= set(f)
            assert payload["files"][2]["expectedPages"] is None
    register("fixture 全齐 → check 退出 0（表/JSON 字段与查询口径）", test_all_ready)

    # ---- 场景 2：缺一篇 → check 退出 1 并列出缺哪篇 --------------------------
    def test_one_missing():
        with tempfile.TemporaryDirectory() as tmp:
            manifest = _write_manifest_file(tmp, _fixture_docs())
            http_fn, _ = _fixture_es_http({"a" * 32: 12, "b" * 32: 0, "c" * 32: 3})
            rows, conn_err, code = run_check(manifest, _es_fixture(), 1, http_fn, is_check=True)
            assert code == 1 and not conn_err
            by_name = {r["name"]: r for r in rows}
            assert by_name["b.pdf"]["status"] == ST_MISSING and by_name["b.pdf"]["chunks"] == 0
            assert by_name["a.pdf"]["status"] == ST_READY
            missing = [r["name"] for r in rows if r["status"] in (ST_MISSING, ST_INSUFFICIENT)]
            assert missing == ["b.pdf"]
            tips = build_remediation(rows, conn_err)
            assert any("--only b.pdf" in t for t in tips), tips  # 单篇给 --only 补救命令
    register("fixture 缺一篇 → check 退出 1 并列出缺哪篇（单篇给 --only 补救）", test_one_missing)

    # ---- 场景 3：全空 → check 退出 1 ---------------------------------------
    def test_all_empty():
        with tempfile.TemporaryDirectory() as tmp:
            manifest = _write_manifest_file(tmp, _fixture_docs())
            http_fn, _ = _fixture_es_http({})  # 全部 count=0
            rows, conn_err, code = run_check(manifest, _es_fixture(), 1, http_fn, is_check=True)
            assert code == 1 and not conn_err
            assert all(r["status"] == ST_MISSING and r["chunks"] == 0 for r in rows)
            tips = build_remediation(rows, conn_err)
            assert any("eval_corpus_ingest.py --run" in t for t in tips), tips
            # 纯报告模式（非 --check）：信息相同但不因未就绪而退出 1
            code_report = decide_exit(rows, conn_err, is_check=False)
            assert code_report == 0
    register("fixture 全空 → check 退出 1（全部缺失；纯报告模式不判 1）", test_all_empty)

    # ---- 场景 4：ES 不可达 → 报「连接失败」而非「分块为 0」，退出 2 -----------
    def test_es_unreachable():
        with tempfile.TemporaryDirectory() as tmp:
            manifest = _write_manifest_file(tmp, _fixture_docs())
            http_fn, _ = _fixture_es_http(raise_error=TransportError("连接失败: Connection refused"))
            rows, conn_err, code = run_check(manifest, _es_fixture(), 1, http_fn, is_check=True)
            assert code == 2, code  # 无法判定 ≠ 语料未就绪（1）
            assert "连接失败" in conn_err
            assert all(r["status"] == ST_CONN_FAILED for r in rows)
            assert all(r["chunks"] is None for r in rows)
            table = render_markdown(rows)
            assert "连接失败" in table and "分块为 0" not in table
            payload = json.loads(render_json(_es_fixture(), rows, conn_err, 1, True, code))
            assert payload["es"]["reachable"] is False and payload["ready"] is False
            assert payload["summary"]["connectionFailed"] is True
            assert any("恢复 ES 连接" in t for t in payload["remediation"])
    register("fixture ES 不可达 → 报连接失败而非分块为 0，退出 2", test_es_unreachable)

    # ---- min-chunks 边界与非法值 -------------------------------------------
    def test_min_chunks_boundary():
        with tempfile.TemporaryDirectory() as tmp:
            manifest = _write_manifest_file(tmp, _fixture_docs())
            # count == min → 就绪；count < min → 不足
            http_fn, _ = _fixture_es_http({"a" * 32: 5, "b" * 32: 4, "c" * 32: 30})
            rows, _, code = run_check(manifest, _es_fixture(), 5, http_fn, is_check=True)
            by_name = {r["name"]: r for r in rows}
            assert code == 1
            assert by_name["a.pdf"]["status"] == ST_READY       # 5 == min → 就绪
            assert by_name["b.pdf"]["status"] == ST_INSUFFICIENT  # 4 < min → 不足（非缺失）
            assert by_name["c.pdf"]["status"] == ST_READY
            # --min-chunks 0：count=0 也算就绪（显式放宽）
            http_fn2, _ = _fixture_es_http({})
            rows2, _, code2 = run_check(manifest, _es_fixture(), 0, http_fn2, is_check=True)
            assert code2 == 0 and all(r["status"] == ST_READY for r in rows2)
            # 非法值
            try:
                validate_min_chunks(-1)
                raise AssertionError("负数应报错")
            except EvalReadinessError:
                pass
    register("min-chunks 边界（==min 就绪 / <min 不足 / 0 放宽 / 非法值）", test_min_chunks_boundary)

    # ---- 查询失败分类（401/404/坏 JSON）→ 退出 2 ----------------------------
    def test_query_failures():
        with tempfile.TemporaryDirectory() as tmp:
            manifest = _write_manifest_file(tmp, _fixture_docs())
            # 401：报环境变量名，不猜默认密码
            http_fn, calls = _fixture_es_http(status_by_md5={"a" * 32: 401})
            rows, _, code = run_check(manifest, _es_fixture(), 1, http_fn, is_check=True)
            assert code == 2
            row_a = next(r for r in rows if r["name"] == "a.pdf")
            assert row_a["status"] == ST_QUERY_FAILED
            assert "ELASTICSEARCH_USERNAME" in row_a["error"] and "ELASTICSEARCH_PASSWORD" in row_a["error"]
            # 404：提示索引名
            http_fn2, _ = _fixture_es_http(status_by_md5={"b" * 32: 404})
            rows2, _, code2 = run_check(manifest, _es_fixture(), 1, http_fn2, is_check=True)
            assert code2 == 2
            row_b = next(r for r in rows2 if r["name"] == "b.pdf")
            assert "EVAL_ES_INDEX" in row_b["error"]
            # 坏 JSON / 缺 count 字段
            ok, count, err = parse_count_response(200, b"not-json")
            assert not ok and count is None and err
            ok, count, err = parse_count_response(200, json.dumps({"took": 1}).encode())
            assert not ok and "count" in err
            ok, count, err = parse_count_response(502, b"")
            assert not ok and count is None
            # 认证头：默认 elastic/空 与自定义用户名/密码都编入 Basic（此处仅 fixture 值，
            # 不涉及真实凭据；空用户名按缺省口径回退 elastic，与 spec A2 探针一致）
            assert _es_fixture().headers()["Authorization"] == "Basic eC4="
            anon = build_es_config({"ELASTICSEARCH_USERNAME": ""}, "")
            assert base64.b64decode(anon.auth_header.split()[1]).decode() == "elastic:"
            svc = build_es_config({"ELASTICSEARCH_USERNAME": "svc", "ELASTICSEARCH_PASSWORD": "pw"}, "")
            assert base64.b64decode(svc.auth_header.split()[1]).decode() == "svc:pw"
    register("查询失败分类（401 报环境变量名/404 报索引提示/坏 JSON）", test_query_failures)

    # ---- ES 配置：缺省值、环境变量覆盖、.env 兜底、https 自签 ------------------
    def test_es_config():
        # 缺省：elastic/空、http://localhost:9200、索引 knowledge_base
        cfg = build_es_config({}, "")
        assert cfg.url == "http://localhost:9200" and cfg.index == "knowledge_base"
        assert cfg.auth_header.startswith("Basic ")  # 默认用户名 elastic（密码空）
        # 进程环境优先
        cfg2 = build_es_config({"ELASTICSEARCH_HOST": "es.internal", "ELASTICSEARCH_PORT": "9201",
                                "ELASTICSEARCH_SCHEME": "https", "ELASTICSEARCH_USERNAME": "svc",
                                "ELASTICSEARCH_PASSWORD": "pw", "EVAL_ES_INDEX": "kb_x"}, "")
        assert cfg2.url == "https://es.internal:9201" and cfg2.index == "kb_x"
        # .env 兜底（进程环境缺失时）
        with tempfile.TemporaryDirectory() as tmp:
            env_file = os.path.join(tmp, ".env")
            with open(env_file, "w", encoding="utf-8") as f:
                f.write("# c\nELASTICSEARCH_HOST = db.local\r\nEVAL_ES_INDEX=\"kb_env\"\n")
            cfg3 = build_es_config({}, env_file)
            assert cfg3.url == "http://db.local:9200" and cfg3.index == "kb_env"
        # 非法 scheme
        try:
            build_es_config({"ELASTICSEARCH_SCHEME": "ftp"}, "")
            raise AssertionError("非法 scheme 应报错")
        except EvalReadinessError:
            pass
        # https 自签：context 不校验证书
        ctx = build_insecure_ssl_context()
        assert ctx.check_hostname is False and ctx.verify_mode == ssl.CERT_NONE
    register("ES 配置（缺省 elastic/空/env 覆盖/.env 兜底/https 自签不校验）", test_es_config)

    # ---- manifest 校验 ------------------------------------------------------
    def test_manifest_validation():
        with tempfile.TemporaryDirectory() as tmp:
            # 不存在
            try:
                load_manifest(os.path.join(tmp, "nope.json"))
                raise AssertionError("manifest 不存在应报错")
            except EvalReadinessError as e:
                assert "不存在" in str(e)
            # 坏 JSON / 缺 documents
            bad = os.path.join(tmp, "bad.json")
            with open(bad, "w", encoding="utf-8") as f:
                f.write('{"version": 1}')
            try:
                load_manifest(bad)
                raise AssertionError("缺 documents 应报错")
            except EvalReadinessError as e:
                assert "documents" in str(e)
            # md5 非法 / 路径穿越
            with open(bad, "w", encoding="utf-8") as f:
                json.dump({"documents": [{"name": "x.pdf", "md5": "zz"}]}, f)
            try:
                load_manifest(bad)
                raise AssertionError("非法 md5 应报错")
            except EvalReadinessError as e:
                assert "md5" in str(e)
            with open(bad, "w", encoding="utf-8") as f:
                json.dump({"documents": [{"name": "../x.pdf", "md5": "a" * 32}]}, f)
            try:
                load_manifest(bad)
                raise AssertionError("路径分隔符应拒绝")
            except EvalReadinessError:
                pass
    register("manifest 校验（不存在/坏结构/非法 md5/路径穿越）", test_manifest_validation)

    # ---- 汇总与补救建议 ------------------------------------------------------
    def test_summary_and_remediation():
        rows = [
            {"name": "a.pdf", "md5": "a" * 32, "expectedPages": 10, "chunks": 3,
             "status": ST_READY, "error": ""},
            {"name": "b.pdf", "md5": "b" * 32, "expectedPages": 20, "chunks": 0,
             "status": ST_MISSING, "error": ""},
            {"name": "c.pdf", "md5": "c" * 32, "expectedPages": None, "chunks": 2,
             "status": ST_INSUFFICIENT, "error": ""},
        ]
        s = summarize(rows)
        assert s == {"total": 3, "ready": 1, "missing": 1, "insufficient": 1,
                     "queryFailed": 0, "connectionFailed": False}
        tips = build_remediation(rows, "")
        joined = "\n".join(tips)
        assert "b.pdf" in joined and "c.pdf" in joined
        assert "eval_corpus_ingest.py --run" in joined  # 多篇给整跑命令
        # decide_exit 组合
        assert decide_exit(rows, "", is_check=True) == 1
        assert decide_exit(rows, "", is_check=False) == 0
        qf = [dict(rows[0], status=ST_QUERY_FAILED, error="boom")] + rows[1:]
        assert decide_exit(qf, "", is_check=False) == 2  # 查询失败即使纯报告也判 2
    register("汇总与补救建议（统计/--only/查询失败判 2）", test_summary_and_remediation)

    passed, failed = 0, []
    print("==> eval_corpus_readiness --self-test（离线，fixture + fake HTTP，不联网）")
    for name, fn in tests:
        try:
            fn()
            print(f"  PASS {name}")
            passed += 1
        except Exception as e:  # noqa: BLE001 — 测试失败要给出可定位信息
            print(f"  FAIL {name}: {type(e).__name__}: {e}")
            failed.append(name)
    print(f"==> self-test: {passed} passed, {len(failed)} failed")
    if failed:
        print("失败项: " + "; ".join(failed))
        return 1
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="eval_corpus_readiness.py",
        description="语料就绪检查：manifest 的每个 md5 ↔ ES 分块数（task-0002）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
模式：
  （默认）          打印 markdown 表（文件名、md5、期望页数、实际分块数、状态）
  --check          就绪门禁：任一文件分块数 < --min-chunks 退出 1；无法判定退出 2
  --json           JSON 输出（可与 --check 组合）
  --self-test      离线自检（fixture JSON + fake HTTP，不联网）

环境变量（详见 docs/eval/runbook.md）：
  ELASTICSEARCH_SCHEME/HOST/PORT/USERNAME/PASSWORD   ES 直连（默认 http://localhost:9200, elastic/空）
  EVAL_ES_INDEX            ES 索引/别名（默认 knowledge_base）
  EVAL_ENV_FILE            .env 路径（默认仓库根 .env，ELASTICSEARCH_* 兜底来源）

退出码：0 就绪/报告成功；1 --check 有文件低于下限；2 无法判定（连接失败/查询失败/参数问题）
""")
    parser.add_argument("--check", action="store_true",
                        help="就绪门禁：任一文件分块数 < --min-chunks 退出 1")
    parser.add_argument("--json", action="store_true", help="JSON 输出（代替 markdown 表）")
    parser.add_argument("--min-chunks", type=int, default=1,
                        help="就绪下限（默认 1；0 表示不设下限）")
    parser.add_argument("--manifest", default=os.path.join("eval-datasets", "manifest.json"),
                        help="manifest 路径（默认 eval-datasets/manifest.json）")
    parser.add_argument("--env-file", default=None,
                        help=".env 路径（默认 EVAL_ENV_FILE 或仓库根 .env）")
    parser.add_argument("--es-timeout", type=float, default=None,
                        help="单次 ES 请求超时秒数（默认 15）")
    parser.add_argument("--self-test", action="store_true", help="离线自检（不联网）")
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.env_file is None:
        args.env_file = resolve_default_env_file()
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except (AttributeError, ValueError, OSError):
        pass  # 老解释器/非 tty 环境按默认缓冲
    if args.self_test:
        return self_test()
    try:
        return cmd_report(args)
    except EvalReadinessError as e:
        # manifest/参数/ES 配置级失败：指明缺什么，退出 2
        print(f"!! {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
