#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""语料入库脚本（task-0001，eval-real-run 包）。

读 eval-datasets/manifest.json 与 eval-datasets/corpus/，把 10 篇评测 PDF 按
5MB 分片上传到 /api/v1/upload/chunk → POST /api/v1/upload/merge → 轮询 ES 该
md5 分块数 >0（合并后经 outbox 发 Kafka 异步解析，必须等到入库才算完成）。

模式：
  --dry-run    只读本地校验：打印「文件 → md5 → 分片数」并校验 manifest 与本地
               大小/md5；不联网、不要求 token。校验失败退出 2。
  --run        真跑。按 EVAL_ADMIN_TOKEN → EVAL_ADMIN_USERNAME/PASSWORD →
               仓库根 .env 的 ADMIN_BOOTSTRAP_USERNAME/PASSWORD 三级取管理员 token
               （口径与 scripts/eval_real_smoke.sh 一致，token 只进内存）。
               逐文件：先查 ES 分块数，>0 直接跳过（重跑幂等，同 md5 已入库不重灌）；
               否则并发（最多 2）上传缺失分片 → merge → 轮询 ES。
  --resume     配合 --run 的断点续传：未入库的文件先查 GET /api/v1/upload/status，
               只补传缺失分片（不加时全量重传，后端按分片幂等去重）。
  --only NAME  只处理一篇（manifest name 精确匹配，或唯一前缀），用于单篇重试。
  --self-test  离线自检：md5 校验、5MB 分片边界（整倍数/余数/空文件）、manifest
               缺文件、merge 响应解析、ES count 轮询状态机（注入 fake HTTP），
               不联网。

凭据纪律：token/密码只从环境变量或仓库根 .env 读取，只进内存；
不打印、不落盘、不进任何日志。

只依赖 Python 3 标准库（urllib/hashlib/json/ssl 等），不新增依赖。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import ssl
import sys
import tempfile
import threading
import time
import uuid
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

# ---- 常量（与后端对齐的事实） ------------------------------------------------
# UploadController.DEFAULT_CHUNK_SIZE_BYTES = 5L * 1024 * 1024
CHUNK_SIZE = 5 * 1024 * 1024
MAX_CHUNK_WORKERS = 2          # 卡面要求：最多 2 并发
CHUNK_ATTEMPTS = 3             # 单个分片瞬时失败重试次数
MERGE_ATTEMPTS = 3             # merge 遇 409（合并中）重试次数
MB_TOLERANCE = 0.05            # manifest 的 mb 是四舍五入到 1 位小数，容差即 0.05MB
MD5_RE = re.compile(r"^[0-9a-fA-F]{32}$")

DEFAULT_BASE_URL = "http://localhost:8081"   # 同 eval_real_smoke.sh
DEFAULT_ES_INDEX = "knowledge_base"          # 后端业务读写别名（KnowledgeIndexNames.ALIAS）
DEFAULT_ES_POLL_INTERVAL = 5.0               # 秒
DEFAULT_ES_POLL_TIMEOUT = 900.0              # 秒，单文件等待入库上限（Kafka 解析较慢）


class EvalIngestError(Exception):
    """可预期的操作失败（带可直接执行的排查建议）。"""


class TransportError(EvalIngestError):
    """网络/连接层失败（与 HTTP 4xx/5xx 区分，按可重试处理）。"""


# ---------------------------------------------------------------------------
# 纯函数：分片边界
# ---------------------------------------------------------------------------

def chunk_count(size: int) -> int:
    """与 UploadService.getTotalChunks 同口径：ceil(size / 5MB)；空文件 0 分片。"""
    if size < 0:
        raise ValueError("size 不能为负")
    if size == 0:
        return 0
    return (size + CHUNK_SIZE - 1) // CHUNK_SIZE


def chunk_ranges(size: int) -> list:
    """返回 [(start, end), ...]（左闭右开）。空文件 → []；整倍数/余数各取其值。"""
    return [(i * CHUNK_SIZE, min((i + 1) * CHUNK_SIZE, size))
            for i in range(chunk_count(size))]


def md5_of_file(path: str) -> str:
    """流式计算文件 md5（1MB 块读，不整读进内存）。"""
    h = hashlib.md5()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


# ---------------------------------------------------------------------------
# manifest 加载与本地校验
# ---------------------------------------------------------------------------

def load_manifest(path: str) -> list:
    """读 manifest.json，校验结构；返回 documents 列表。结构问题抛 EvalIngestError。"""
    if not os.path.isfile(path):
        raise EvalIngestError(f"manifest 不存在: {path}")
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        raise EvalIngestError(f"manifest 不可读或不是合法 JSON: {path}（{e}）")
    if not isinstance(data, dict):
        raise EvalIngestError(f"manifest 顶层必须是 JSON 对象: {path}")
    docs = data.get("documents")
    if not isinstance(docs, list) or not docs:
        raise EvalIngestError(f"manifest 缺少非空 documents 数组: {path}")
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
        raise EvalIngestError("manifest 校验失败:\n  - " + "\n  - ".join(problems))
    return cleaned


def check_local_document(doc: dict, corpus_dir: str, compute_md5: bool = True) -> dict:
    """校验单篇语料：存在、非空、大小与 manifest mb 相符（±0.05MB）、md5 一致。

    返回 {"ok": bool, "size": int, "md5": str|None, "problems": [str], "exists": bool}。
    """
    name = doc["name"]
    path = os.path.join(corpus_dir, name)
    problems = []
    size = 0
    md5 = None
    if not os.path.isfile(path):
        problems.append(f"本地缺文件: {path}（corpus 目录不入库，需按 docs/eval/corpus-ingest.md 先放入语料）")
        return {"ok": False, "size": 0, "md5": None, "problems": problems, "exists": False}
    size = os.path.getsize(path)
    if size == 0:
        problems.append(f"{name}: 文件大小为 0")
    declared_mb = doc.get("mb")
    if isinstance(declared_mb, (int, float)) and size > 0:
        actual_mb = size / (1024 * 1024)
        if abs(actual_mb - declared_mb) > MB_TOLERANCE:
            problems.append(f"{name}: 大小 {actual_mb:.2f}MB 与 manifest {declared_mb}MB 相差超过 {MB_TOLERANCE}MB")
    if size > 0 and compute_md5:
        md5 = md5_of_file(path)
        if md5.lower() != doc["md5"].lower():
            problems.append(f"{name}: 本地 md5 {md5} 与 manifest {doc['md5']} 不一致（文件被改动？）")
    return {"ok": not problems, "size": size, "md5": md5, "problems": problems, "exists": True}


def select_documents(docs: list, only: str = "") -> list:
    """--only 过滤：先精确匹配 name，否则唯一前缀匹配；无匹配/歧义抛 EvalIngestError。"""
    if not only:
        return list(docs)
    exact = [d for d in docs if d["name"] == only]
    if exact:
        return exact
    prefix = [d for d in docs if d["name"].startswith(only)]
    if len(prefix) == 1:
        return prefix
    if len(prefix) > 1:
        names = ", ".join(d["name"] for d in prefix)
        raise EvalIngestError(f"--only {only} 匹配到多篇，请用完整文件名: {names}")
    raise EvalIngestError(f"--only {only} 未匹配到任何 manifest 文件（可选: {', '.join(d['name'] for d in docs)}）")


# ---------------------------------------------------------------------------
# .env 读取（口径与 eval_real_smoke.sh env_file_var 一致；值绝不回显）
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
# 三级凭据（EVAL_ADMIN_TOKEN → EVAL_ADMIN_USERNAME/PASSWORD → .env ADMIN_BOOTSTRAP_*）
# ---------------------------------------------------------------------------

class Credentials:
    def __init__(self, token="", username="", password="", source=""):
        self.token = token or ""
        self.username = username or ""
        self.password = password or ""
        self.source = source

    def can_relogin(self) -> bool:
        return bool(self.username) and bool(self.password)


def acquire_credentials(env: dict, env_file: str) -> Credentials:
    """按三级顺序取凭据；三级都不可用抛 EvalIngestError（列出缺哪个变量名，不含值）。"""
    token = env_lookup(env, env_file, "EVAL_ADMIN_TOKEN")
    if token:
        return Credentials(token=token, source="EVAL_ADMIN_TOKEN")

    user = env_lookup(env, env_file, "EVAL_ADMIN_USERNAME")
    pwd = env_lookup(env, env_file, "EVAL_ADMIN_PASSWORD")
    if user and pwd:
        return Credentials(username=user, password=pwd,
                           source="EVAL_ADMIN_USERNAME/EVAL_ADMIN_PASSWORD")

    notes = []
    if user and not pwd:
        notes.append("EVAL_ADMIN_PASSWORD（EVAL_ADMIN_USERNAME 已设但密码未设）")
    elif pwd and not user:
        notes.append("EVAL_ADMIN_USERNAME（EVAL_ADMIN_PASSWORD 已设但用户名未设）")

    if not user and not pwd:
        if not env_file or not os.path.isfile(env_file):
            notes.append(f"EVAL_ADMIN_USERNAME / EVAL_ADMIN_PASSWORD（未设置）；且 .env 不存在: {env_file}")
        else:
            boot_user = env_file_get(env_file, "ADMIN_BOOTSTRAP_USERNAME")
            boot_pwd = env_file_get(env_file, "ADMIN_BOOTSTRAP_PASSWORD")
            if boot_user and boot_pwd:
                return Credentials(username=boot_user, password=boot_pwd,
                                   source=f"{env_file} 的 ADMIN_BOOTSTRAP_USERNAME/ADMIN_BOOTSTRAP_PASSWORD")
            if not boot_user:
                notes.append(f"ADMIN_BOOTSTRAP_USERNAME（{env_file} 中未定义）")
            if not boot_pwd:
                notes.append(f"ADMIN_BOOTSTRAP_PASSWORD（{env_file} 中未定义）")

    raise EvalIngestError(
        "缺少管理员凭据，无法入库（缺的就是下面这些）：\n  - "
        + "\n  - ".join(notes)
        + "\n  三级顺序：EVAL_ADMIN_TOKEN → EVAL_ADMIN_USERNAME+EVAL_ADMIN_PASSWORD → "
        + f"{env_file} 的 ADMIN_BOOTSTRAP_USERNAME+ADMIN_BOOTSTRAP_PASSWORD")


# ---------------------------------------------------------------------------
# HTTP 层（可注入替换；self-test 用 fake 函数，不联网）
# ---------------------------------------------------------------------------

def default_http(method: str, url: str, headers: dict, data, timeout: float):
    """真 HTTP：urllib。返回 (status:int, body:bytes)。https 自签证书不校验。"""
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    ctx = None
    if url.lower().startswith("https:"):
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            return int(resp.getcode()), resp.read()
    except urllib.error.HTTPError as e:
        return int(e.code), e.read() or b""
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise TransportError(f"连接失败: {getattr(e, 'reason', e) or e}")


def _json_body_bytes(body) -> bytes:
    return json.dumps(body, ensure_ascii=False).encode("utf-8")


def build_multipart(fields: dict, file_field: str, filename: str,
                    file_content_type: str, data: bytes) -> tuple:
    """组装 multipart/form-data（标准库实现）。返回 (body: bytes, content_type: str)。"""
    boundary = "----MindBaseEvalBoundary" + uuid.uuid4().hex
    crlf = b"\r\n"
    parts = []
    for key, value in fields.items():
        parts.append(b"--" + boundary.encode() + crlf)
        parts.append(f'Content-Disposition: form-data; name="{key}"'.encode() + crlf + crlf)
        parts.append(str(value).encode("utf-8") + crlf)
    parts.append(b"--" + boundary.encode() + crlf)
    parts.append(
        f'Content-Disposition: form-data; name="{file_field}"; filename="{filename}"'.encode() + crlf)
    parts.append(f"Content-Type: {file_content_type}".encode() + crlf + crlf)
    parts.append(data + crlf)
    parts.append(b"--" + boundary.encode() + b"--" + crlf)
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


# ---------------------------------------------------------------------------
# 响应解析（纯函数，self-test 直接覆盖）
# ---------------------------------------------------------------------------

def _unwrap_response(status: int, body: bytes) -> tuple:
    """统一响应壳 {code,message,data}。返回 (code:int|None, message:str, data:dict|None, parse_error:str)。"""
    if not body:
        return None, "", None, "响应体为空"
    try:
        payload = json.loads(body.decode("utf-8", errors="replace"))
    except ValueError as e:
        return None, "", None, f"响应不是合法 JSON: {e}"
    if not isinstance(payload, dict):
        return None, "", None, "响应 JSON 顶层不是对象"
    code = payload.get("code")
    message = payload.get("message") or ""
    data = payload.get("data") if isinstance(payload.get("data"), dict) else None
    if code is None and 200 <= status < 300:
        code = status  # 容忍没有 code 壳的 2xx
    return code, str(message), data, ""


def parse_login_response(status: int, body: bytes) -> tuple:
    """POST /users/login。返回 (token:str, error:str)。二选一非空。"""
    if status == 401:
        return "", "登录 401：用户名或密码不对；确认账号存在且 role=ADMIN"
    if status == 429:
        return "", "登录 429：接口按 IP 限流，等窗口过去再试，不要连续重试"
    code, message, data, parse_error = _unwrap_response(status, body)
    if parse_error:
        return "", f"登录响应异常（HTTP {status}）：{parse_error}"
    if status != 200 or code != 200:
        return "", f"登录失败 HTTP {status}：{message or '（响应无 message）'}"
    token = (data or {}).get("token")
    if not isinstance(token, str) or not token:
        return "", "登录响应 200 但未取到 data.token，响应结构异常"
    return token, ""


def parse_chunk_response(status: int, body: bytes) -> tuple:
    """POST /upload/chunk。返回 (ok, message, already_completed)。

    already_completed=True 表示后端记录已 COMPLETED（409「文件已完成合并，不允许继续上传分片」），
    调用方应停止传分片、直接走 merge 幂等路径。
    """
    if status == 409:
        code, message, _, parse_error = _unwrap_response(status, body)
        msg = message or (parse_error if parse_error else "")
        if "已完成合并" in msg:
            return False, msg, True
        return False, f"分片上传 409：{msg or '（响应无 message）'}", False
    code, message, _, parse_error = _unwrap_response(status, body)
    if parse_error:
        return False, f"分片上传响应异常（HTTP {status}）：{parse_error}", False
    if status != 200 or code != 200:
        return False, f"分片上传失败 HTTP {status}：{message or '（响应无 message）'}", False
    return True, message or "分片上传成功", False


def parse_merge_response(status: int, body: bytes) -> tuple:
    """POST /upload/merge。返回 (ok, message, object_url)。

    两种 200：正常合并「文件合并成功，任务已提交处理」与幂等「文件已完成合并」，
    都带 data.object_url。解析要求 code==200；object_url 缺失不判失败但记录提示。
    """
    code, message, data, parse_error = _unwrap_response(status, body)
    if parse_error:
        return False, f"merge 响应异常（HTTP {status}）：{parse_error}", ""
    if status != 200 or code != 200:
        return False, f"merge 失败 HTTP {status}：{message or '（响应无 message）'}", ""
    object_url = (data or {}).get("object_url") or ""
    if not object_url:
        message = (message + "（注意：响应缺 data.object_url）").strip()
    return True, message or "merge 成功", object_url


def parse_status_response(status: int, body: bytes) -> tuple:
    """GET /upload/status。返回 (ok, uploaded:list[int], error:str)。"""
    code, message, data, parse_error = _unwrap_response(status, body)
    if parse_error:
        return False, [], f"status 响应异常（HTTP {status}）：{parse_error}"
    if status != 200 or code != 200:
        return False, [], f"status 查询失败 HTTP {status}：{message or '（响应无 message）'}"
    uploaded_raw = (data or {}).get("uploaded")
    uploaded = []
    if isinstance(uploaded_raw, list):
        for item in uploaded_raw:
            try:
                uploaded.append(int(item))
            except (TypeError, ValueError):
                pass
    return True, sorted(set(uploaded)), ""


def parse_count_response(status: int, body: bytes) -> tuple:
    """ES POST /{index}/_count。返回 (ok, count:int|None, error:str)。"""
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


# ---------------------------------------------------------------------------
# 登录与带 401 重登的请求客户端
# ---------------------------------------------------------------------------

class AuthedClient:
    """持有 token 与可重登凭据。401 时重登一次再重试一次；token 只进内存。"""

    def __init__(self, base_url: str, creds: Credentials, http_fn=default_http):
        self.base_url = base_url.rstrip("/")
        self.creds = creds
        self.http_fn = http_fn
        self._relogin_lock = threading.Lock()

    def login(self, timeout: float = 30.0) -> str:
        payload = _json_body_bytes({"username": self.creds.username, "password": self.creds.password})
        status, body = self.http_fn(
            "POST", f"{self.base_url}/api/v1/users/login",
            headers={"Content-Type": "application/json"}, data=payload, timeout=timeout)
        token, error = parse_login_response(status, body)
        if error:
            raise EvalIngestError(error)
        self.creds.token = token
        return token

    def ensure_token(self) -> str:
        if self.creds.token:
            return self.creds.token
        return self.login()

    def _relogin(self, timeout: float) -> bool:
        """401 后重登一次。无用户名/密码（token 直接来自 EVAL_ADMIN_TOKEN）返回 False。"""
        if not self.creds.can_relogin():
            return False
        with self._relogin_lock:
            self.login(timeout=timeout)
            return True

    def request(self, method: str, path: str, *, json_body=None, data=None,
                headers=None, timeout: float = 60.0, _retried: bool = False) -> tuple:
        """带 Authorization 的请求。返回 (status, body)。401 重登一次后重试一次。"""
        all_headers = dict(headers or {})
        all_headers["Authorization"] = f"Bearer {self.ensure_token()}"
        if json_body is not None and data is None:
            data = _json_body_bytes(json_body)
            all_headers.setdefault("Content-Type", "application/json")
        try:
            status, body = self.http_fn(method, f"{self.base_url}{path}",
                                        headers=all_headers, data=data, timeout=timeout)
        except TransportError as e:
            raise EvalIngestError(f"请求 {method} {path} 失败（后端不可达？{self.base_url}）：{e}")
        if status == 401 and not _retried:
            if self._relogin(timeout=30.0):
                return self.request(method, path, json_body=json_body, data=data,
                                    headers=headers, timeout=timeout, _retried=True)
        return status, body


# ---------------------------------------------------------------------------
# ES 配置与分块数轮询状态机
# ---------------------------------------------------------------------------

class EsConfig:
    def __init__(self, url: str, index: str, auth_header: str = "", timeout: float = 15.0):
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

    scheme=https 时按自签证书处理（不校验证书，对应 insecure-trust-all-certificates=true）。
    """
    scheme = (env_lookup(env, env_file, "ELASTICSEARCH_SCHEME") or "http").lower()
    host = env_lookup(env, env_file, "ELASTICSEARCH_HOST") or "localhost"
    port = env_lookup(env, env_file, "ELASTICSEARCH_PORT") or "9200"
    user = env_lookup(env, env_file, "ELASTICSEARCH_USERNAME") or "elastic"
    pwd = env_lookup(env, env_file, "ELASTICSEARCH_PASSWORD") or ""
    index = env_lookup(env, env_file, "EVAL_ES_INDEX") or DEFAULT_ES_INDEX
    if scheme not in ("http", "https"):
        raise EvalIngestError(f"ELASTICSEARCH_SCHEME 只支持 http/https，当前: {scheme}")
    url = f"{scheme}://{host}:{port}"
    auth_header = ""
    if user:  # 用户名非空即带 Basic（密码可为空串）；默认 elastic/空，与 A2 探针口径一致
        auth_header = "Basic " + base64.b64encode(f"{user}:{pwd}".encode()).decode()
    return EsConfig(url=url, index=index, auth_header=auth_header)


class PollOutcome:
    def __init__(self, indexed: bool, count=None, polls: int = 0, elapsed: float = 0.0, reason: str = ""):
        self.indexed = indexed
        self.count = count
        self.polls = polls
        self.elapsed = elapsed
        self.reason = reason


def poll_es_chunks(es_cfg: EsConfig, file_md5: str, http_fn, *, interval: float,
                   timeout: float, now_fn=time.monotonic, sleep_fn=time.sleep,
                   max_consecutive_errors: int = 10) -> PollOutcome:
    """轮询 ES 该 md5 的分块数直到 >0（状态机：PENDING→INDEXED / ERROR 计数 / TIMEOUT）。

    - count>0                    → indexed=True
    - count==0                   → PENDING，继续轮询（干净轮询会清空连续错误计数）
    - HTTP/解析异常              → 连续错误 +1，达到 max_consecutive_errors 判失败（如别名不存在）
    - 到 timeout 仍未 >0         → indexed=False, reason=轮询超时
    """
    query = _json_body_bytes({"query": {"term": {"fileMd5": file_md5}}})
    url = f"{es_cfg.url}/{es_cfg.index}/_count"
    start = now_fn()
    polls = 0
    consecutive_errors = 0
    last_error = ""
    while True:
        polls += 1
        try:
            status, body = http_fn("POST", url, headers=es_cfg.headers(),
                                   data=query, timeout=es_cfg.timeout)
            ok, count, error = parse_count_response(status, body)
            if ok:
                if count > 0:
                    return PollOutcome(indexed=True, count=count, polls=polls,
                                       elapsed=now_fn() - start)
                consecutive_errors = 0
            else:
                consecutive_errors += 1
                last_error = error
                if consecutive_errors >= max_consecutive_errors:
                    return PollOutcome(indexed=False, count=None, polls=polls,
                                       elapsed=now_fn() - start,
                                       reason=f"ES 连续 {consecutive_errors} 次异常，最后一次: {last_error}")
        except (TransportError, EvalIngestError, OSError) as e:
            consecutive_errors += 1
            last_error = str(e)
            if consecutive_errors >= max_consecutive_errors:
                return PollOutcome(indexed=False, count=None, polls=polls,
                                   elapsed=now_fn() - start,
                                   reason=f"ES 连续 {consecutive_errors} 次连接异常，最后一次: {last_error}")
        elapsed = now_fn() - start
        if elapsed >= timeout:
            return PollOutcome(indexed=False, count=None, polls=polls, elapsed=elapsed,
                               reason=f"轮询超时（{timeout:.0f}s，共 {polls} 次），最后一次: {last_error or 'count=0'}")
        sleep_fn(min(interval, max(0.0, timeout - elapsed)))


def es_count_once(es_cfg: EsConfig, file_md5: str, http_fn) -> tuple:
    """单次 ES count（--run 开头判断是否已入库用）。返回 (ok, count, error)。"""
    query = _json_body_bytes({"query": {"term": {"fileMd5": file_md5}}})
    try:
        status, body = http_fn("POST", f"{es_cfg.url}/{es_cfg.index}/_count",
                               headers=es_cfg.headers(), data=query, timeout=es_cfg.timeout)
    except (TransportError, EvalIngestError, OSError) as e:
        return False, None, f"ES 连接异常: {e}"
    return parse_count_response(status, body)


# ---------------------------------------------------------------------------
# 单篇入库流程
# ---------------------------------------------------------------------------

def _upload_one_chunk(client: AuthedClient, doc: dict, corpus_dir: str,
                      total_size: int, total_chunks: int, index: int) -> dict:
    """上传单个分片（含瞬时重试）。返回 {"ok","message","already_completed"}。"""
    path = os.path.join(corpus_dir, doc["name"])
    start, end = chunk_ranges(total_size)[index]
    with open(path, "rb") as f:
        f.seek(start)
        data = f.read(end - start)
    fields = {
        "fileMd5": doc["md5"].lower(),
        "chunkIndex": index,
        "totalSize": total_size,
        "fileName": doc["name"],
        "totalChunks": total_chunks,
        "isPublic": "true",
    }
    body, content_type = build_multipart(fields, "file", doc["name"], "application/pdf", data)
    last_message = ""
    for attempt in range(1, CHUNK_ATTEMPTS + 1):
        try:
            status, resp_body = client.request(
                "POST", "/api/v1/upload/chunk", data=body,
                headers={"Content-Type": content_type},
                timeout=max(60.0, len(data) / (1024 * 1024) * 8 + 30))
        except EvalIngestError as e:
            last_message = str(e)
            status, resp_body = None, b""
        if status is not None:
            ok, message, already_completed = parse_chunk_response(status, resp_body)
            if ok or already_completed:
                return {"ok": True, "message": message, "already_completed": already_completed}
            last_message = message
            if status in (409, 429, 500, 502, 503, 504) and attempt < CHUNK_ATTEMPTS:
                time.sleep(min(2.0, 0.5 * attempt))
                continue
            return {"ok": False, "message": message, "already_completed": False}
        if attempt < CHUNK_ATTEMPTS:
            time.sleep(min(2.0, 0.5 * attempt))
            continue
    return {"ok": False, "message": last_message or "分片上传失败（重试耗尽）", "already_completed": False}


def ingest_document(client: AuthedClient, es_cfg: EsConfig, doc: dict, corpus_dir: str,
                    *, resume: bool, poll_interval: float, poll_timeout: float,
                    log=print, es_http_fn=default_http, sleep_fn=time.sleep,
                    now_fn=time.monotonic) -> dict:
    """单篇入库。返回 {"status": done|skipped|failed, "detail": str}。

    失败不删除任何数据；失败后由调用方继续下一篇（单篇失败不影响其他文件）。
    """
    name = doc["name"]
    md5 = doc["md5"].lower()
    path = os.path.join(corpus_dir, name)
    total_size = os.path.getsize(path)
    total_chunks = chunk_count(total_size)
    if total_chunks == 0:
        return {"status": "failed", "detail": "文件为空（0 分片），跳过上传"}

    # 1) 已入库判断：ES 该 md5 分块数 >0 → 重跑幂等直接跳过
    ok, count, error = es_count_once(es_cfg, md5, es_http_fn)
    if ok and count > 0:
        log(f"    ES 分块 {count} (>0) —— 已入库，跳过")
        return {"status": "skipped", "detail": f"ES 分块 {count}"}
    if not ok:
        log(f"    NOTE: ES 预检不可用（{error}）——继续上传，最后仍以 ES 轮询为准")

    # 2) 分片上传（--resume 时只补缺失分片）
    missing = list(range(total_chunks))
    if resume:
        try:
            status, body = client.request(
                "GET", f"/api/v1/upload/status?file_md5={md5}", timeout=30)
            ok, uploaded, error = parse_status_response(status, body)
            if ok:
                missing = [i for i in range(total_chunks) if i not in set(uploaded)]
                if len(missing) < total_chunks:
                    log(f"    断点续传：已传 {len(uploaded)}/{total_chunks}，补传 {len(missing)} 片")
            else:
                log(f"    NOTE: 上传状态查询失败（{error}）——按全量重传处理（后端分片幂等去重）")
        except EvalIngestError as e:
            log(f"    NOTE: 上传状态查询异常（{e}）——按全量重传处理")

    already_completed = False
    if missing:
        log(f"    上传分片 {len(missing)}/{total_chunks}（并发 {MAX_CHUNK_WORKERS}）...")
        results = {}
        workers = min(MAX_CHUNK_WORKERS, len(missing))
        with ThreadPoolExecutor(max_workers=workers) as pool:
            futures = {pool.submit(_upload_one_chunk, client, doc, corpus_dir,
                                   total_size, total_chunks, idx): idx for idx in missing}
            for fut in as_completed(futures):
                idx = futures[fut]
                try:
                    results[idx] = fut.result()
                except (OSError, EvalIngestError) as e:
                    # 分片读盘/请求层异常：记录为该分片失败，其余分片照常收尾（不删任何数据）
                    results[idx] = {"ok": False, "message": f"分片处理异常: {e}", "already_completed": False}
                if results[idx]["ok"]:
                    log(f"    分片 {idx} OK（{idx + 1}/{total_chunks}）")
                else:
                    log(f"    分片 {idx} 失败: {results[idx]['message']}")
        bad = {idx: r for idx, r in results.items() if not r["ok"]}
        if bad:
            first_idx = min(bad)
            return {"status": "failed", "detail": f"分片 {first_idx} 上传失败: {bad[first_idx]['message']}"}
        already_completed = any(r["already_completed"] for r in results.values())
        if already_completed:
            log("    后端记录该文件已完成合并（409 幂等）——跳过剩余分片，直接走合并/轮询")

    # 3) merge（409「合并中」重试几次；COMPLETED 幂等 200）
    merge_message = ""
    for attempt in range(1, MERGE_ATTEMPTS + 1):
        try:
            status, body = client.request(
                "POST", "/api/v1/upload/merge",
                json_body={"fileMd5": md5, "fileName": name}, timeout=120)
        except EvalIngestError as e:
            return {"status": "failed", "detail": f"merge 请求失败: {e}"}
        ok, message, _object_url = parse_merge_response(status, body)
        if ok:
            merge_message = message
            log(f"    合并 OK（{message}）")
            break
        if status == 409 and attempt < MERGE_ATTEMPTS:
            log(f"    merge 409（{message}），{attempt}/{MERGE_ATTEMPTS} 次重试...")
            sleep_fn(3.0)
            continue
        return {"status": "failed", "detail": f"merge 失败: {message}"}
    else:
        return {"status": "failed", "detail": f"merge 重试耗尽: {merge_message or 'HTTP 409'}"}

    # 4) 轮询 ES 该 md5 分块数 >0（合并后 outbox → Kafka 异步解析）
    log(f"    等待 ES 索引（每 {poll_interval:.0f}s 轮询，超时 {poll_timeout:.0f}s）...")
    outcome = poll_es_chunks(es_cfg, md5, es_http_fn, interval=poll_interval,
                             timeout=poll_timeout, sleep_fn=sleep_fn, now_fn=now_fn)
    if outcome.indexed:
        log(f"    ES 分块 {outcome.count}（{outcome.elapsed:.1f}s / {outcome.polls} 次轮询）")
        return {"status": "done", "detail": f"ES 分块 {outcome.count}"}
    reason = outcome.reason
    if already_completed and "超时" in reason:
        reason += "；提示：后端记录已 COMPLETED 但 ES 无分块，可能 ES 数据被清空或解析链路卡住，需人工检查（不要反复重灌）"
    return {"status": "failed", "detail": f"等待入库失败: {reason}"}


# ---------------------------------------------------------------------------
# 命令实现
# ---------------------------------------------------------------------------

def resolve_default_env_file() -> str:
    here = os.path.dirname(os.path.abspath(__file__))
    return os.environ.get("EVAL_ENV_FILE") or os.path.join(here, "..", ".env")


def cmd_dry_run(args) -> int:
    """只读本地校验：文件 → md5 → 分片数，并校验 manifest。失败退出 2。"""
    docs = load_manifest(args.manifest)
    docs = select_documents(docs, args.only)
    print(f"==> 语料入库 dry-run（只读本地校验，不联网）：manifest={args.manifest} corpus={args.corpus_dir}")
    print(f"    共 {len(docs)} 篇；分片大小 5MB（与后端 DEFAULT_CHUNK_SIZE_BYTES 一致）")
    all_ok = True
    for doc in docs:
        check = check_local_document(doc, args.corpus_dir)
        chunks = chunk_count(check["size"]) if check["exists"] else "-"
        md5_display = check["md5"] or doc["md5"]
        if check["ok"]:
            print(f"{doc['name']} → {md5_display} → {chunks} 分片  [OK]")
        else:
            all_ok = False
            for problem in check["problems"]:
                print(f"  !! {problem}", file=sys.stderr)
            print(f"{doc['name']} → {md5_display} → {chunks} 分片  [FAIL]")
    if not all_ok:
        print("dry-run FAIL（上面标 FAIL 的文件先修复再放入语料目录）", file=sys.stderr)
        return 2
    print(f"dry-run PASS（{len(docs)} 篇全部与 manifest 一致）")
    return 0


def _float_env(value: str, default: float, name: str) -> float:
    """环境变量解析为正 float；非法值回退默认并提示（不中断）。"""
    if not value:
        return default
    try:
        parsed = float(value)
        if parsed <= 0:
            raise ValueError
        return parsed
    except ValueError:
        print(f"NOTE: {name}={value!r} 不是正数，回退默认 {default}", file=sys.stderr)
        return default


def cmd_run(args) -> int:
    """真跑：三级取 token → 逐文件 分片上传(≤2 并发) → merge → 轮询 ES。"""
    docs = load_manifest(args.manifest)
    docs = select_documents(docs, args.only)

    env = os.environ
    creds = acquire_credentials(env, args.env_file)
    print(f"==> 语料入库真跑：base={args.base_url} manifest={args.manifest} corpus={args.corpus_dir}")
    print(f"    token 来源：{creds.source}" + ("（已带 token，免登录）" if creds.token else "（将登录获取）"))
    print(f"    文件 {len(docs)} 篇；resume={'开（只补缺失分片）' if args.resume else '关（全量重传，后端幂等去重）'}")

    es_cfg = build_es_config(env, args.env_file)
    poll_interval = _float_env(env_lookup(env, args.env_file, "EVAL_ES_POLL_INTERVAL_SECONDS"),
                               DEFAULT_ES_POLL_INTERVAL, "EVAL_ES_POLL_INTERVAL_SECONDS")
    poll_timeout = _float_env(env_lookup(env, args.env_file, "EVAL_ES_POLL_TIMEOUT_SECONDS"),
                              DEFAULT_ES_POLL_TIMEOUT, "EVAL_ES_POLL_TIMEOUT_SECONDS")

    client = AuthedClient(args.base_url, creds, http_fn=default_http)
    if not creds.token:
        print("==> 登录（POST /api/v1/users/login）...")
        try:
            client.login()
        except EvalIngestError as e:
            print(f"!! {e}", file=sys.stderr)
            return 1
        print("    登录成功（token 只进内存）")

    results = []
    started = time.monotonic()
    for i, doc in enumerate(docs, start=1):
        print(f"==> [{i}/{len(docs)}] {doc['name']}  md5={doc['md5']}")
        try:
            outcome = ingest_document(client, es_cfg, doc, args.corpus_dir,
                                      resume=args.resume, poll_interval=poll_interval,
                                      poll_timeout=poll_timeout,
                                      es_http_fn=default_http)
        except EvalIngestError as e:
            # 凭据/鉴权级别的失败（401 重登后仍失败、登录失败）没有继续的意义，整轮终止
            text = str(e)
            if "401" in text or "登录" in text:
                print(f"!! {doc['name']}: {text}", file=sys.stderr)
                print("!! 鉴权级失败，终止本轮（已完成文件不受影响；用 --resume 可续跑）", file=sys.stderr)
                results.append((doc["name"], {"status": "failed", "detail": text}))
                break
            outcome = {"status": "failed", "detail": text}
        results.append((doc["name"], outcome))

    elapsed = time.monotonic() - started
    done = [n for n, r in results if r["status"] == "done"]
    skipped = [n for n, r in results if r["status"] == "skipped"]
    failed = [(n, r["detail"]) for n, r in results if r["status"] == "failed"]
    print(f"==> 结果：完成 {len(done)} / 跳过(已入库) {len(skipped)} / 失败 {len(failed)}"
          f"（耗时 {elapsed:.0f}s）")
    for n in skipped:
        print(f"    跳过: {n}")
    for n, detail in failed:
        print(f"    失败: {n} —— {detail}", file=sys.stderr)
    if failed:
        print("失败重试：bash 同命令加 --resume 只补缺；单篇重试用 --only <文件名>（详见 docs/eval/corpus-ingest.md）",
              file=sys.stderr)
        return 1
    print("真机入库 PASS")
    return 0


# ---------------------------------------------------------------------------
# --self-test（离线，注入 fake HTTP，不联网、不读真实 .env）
# ---------------------------------------------------------------------------

def _fake_http(script):
    """script: list[(status, body_or_bytes)] 或含 {'raise': exc} 的项。返回 (http_fn, calls)。"""
    calls = []
    state = {"i": 0}

    def http_fn(method, url, headers=None, data=None, timeout=15.0):
        calls.append({"method": method, "url": url, "headers": dict(headers or {}),
                      "data": data, "timeout": timeout})
        item = script[min(state["i"], len(script) - 1)]
        state["i"] += 1
        if isinstance(item, dict) and item.get("raise") is not None:
            raise item["raise"]
        status, body = item
        return status, body if isinstance(body, bytes) else body.encode("utf-8")

    return http_fn, calls


def _write_temp(corpus_dir: str, name: str, content: bytes):
    path = os.path.join(corpus_dir, name)
    with open(path, "wb") as f:
        f.write(content)
    return path


class _FakeClock:
    """可编程时钟，让轮询超时/重试在测试里瞬时发生。"""
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


def self_test() -> int:
    tests = []
    register = lambda name, fn: tests.append((name, fn))

    # ---- 1. md5 校验 + 大小校验 ------------------------------------------
    def test_md5_and_size_validation():
        with tempfile.TemporaryDirectory() as tmp:
            corpus = os.path.join(tmp, "corpus")
            os.makedirs(corpus)
            content = b"hello mindbase eval corpus"
            _write_temp(corpus, "a.pdf", content)
            good_md5 = hashlib.md5(content).hexdigest()
            doc = {"name": "a.pdf", "md5": good_md5, "mb": 0.0}
            # mb=0 对 26 字节：26/1MB≈0.000025，差值远小于 0.05，通过
            check = check_local_document(doc, corpus)
            assert check["ok"], check["problems"]
            assert check["md5"] == good_md5
            # md5 不一致
            bad = dict(doc, md5="0" * 32)
            check = check_local_document(bad, corpus)
            assert not check["ok"] and any("md5" in p for p in check["problems"])
            # 大小偏差超容差
            bad_size = dict(doc, mb=5.0)
            check = check_local_document(bad_size, corpus)
            assert not check["ok"] and any("大小" in p for p in check["problems"])
            # 缺文件
            check = check_local_document({"name": "ghost.pdf", "md5": good_md5}, corpus)
            assert not check["ok"] and not check["exists"]
    register("md5 校验（一致/不一致/大小容差/缺文件）", test_md5_and_size_validation)

    # ---- 2. 5MB 分片边界：整倍数/余数/空文件 ------------------------------
    def test_chunk_boundaries():
        M = CHUNK_SIZE
        assert chunk_count(0) == 0 and chunk_ranges(0) == []          # 空文件
        assert chunk_count(1) == 1 and chunk_ranges(1) == [(0, 1)]
        assert chunk_count(M) == 1 and chunk_ranges(M) == [(0, M)]    # 整 1 片
        assert chunk_count(M + 1) == 2                                # 余数 → 2 片
        assert chunk_count(2 * M) == 2 and len(chunk_ranges(2 * M)) == 2   # 整倍数
        assert chunk_count(2 * M + 1) == 3
        ranges = chunk_ranges(2 * M + 5)
        assert ranges == [(0, M), (M, 2 * M), (2 * M, 2 * M + 5)]
        # 字节切片重组：余数场景末片长度 = size % CHUNK
        buf = bytes(range(256)) * ((M + 123) // 256 + 1)
        size = M + 123
        buf = buf[:size]
        pieces = [buf[s:e] for (s, e) in chunk_ranges(size)]
        assert len(pieces) == 2 and len(pieces[0]) == M and len(pieces[1]) == 123
        assert b"".join(pieces) == buf
        # 负数拒绝
        try:
            chunk_count(-1)
            raise AssertionError("负数应拒绝")
        except ValueError:
            pass
    register("5MB 分片边界（整倍数/余数/空文件）", test_chunk_boundaries)

    # ---- 3. manifest 校验与缺文件 ----------------------------------------
    def test_manifest_missing_file():
        with tempfile.TemporaryDirectory() as tmp:
            manifest = os.path.join(tmp, "manifest.json")
            corpus = os.path.join(tmp, "corpus")
            os.makedirs(corpus)
            _write_temp(corpus, "exists.pdf", b"x")
            with open(manifest, "w", encoding="utf-8") as f:
                json.dump({"documents": [
                    {"name": "exists.pdf", "md5": hashlib.md5(b"x").hexdigest()},
                    {"name": "missing.pdf", "md5": "a" * 32},
                ]}, f)
            docs = load_manifest(manifest)
            assert len(docs) == 2
            checks = [check_local_document(d, corpus) for d in docs]
            assert checks[0]["ok"] and not checks[1]["ok"]
            assert any("本地缺文件" in p for p in checks[1]["problems"])
            # 结构错误：documents 缺失
            with open(manifest, "w", encoding="utf-8") as f:
                f.write('{"version": 1}')
            try:
                load_manifest(manifest)
                raise AssertionError("缺 documents 应报错")
            except EvalIngestError as e:
                assert "documents" in str(e)
            # 结构错误：md5 非法
            with open(manifest, "w", encoding="utf-8") as f:
                json.dump({"documents": [{"name": "a.pdf", "md5": "zz"}]}, f)
            try:
                load_manifest(manifest)
                raise AssertionError("非法 md5 应报错")
            except EvalIngestError as e:
                assert "md5" in str(e)
            # 路径穿越拒绝
            with open(manifest, "w", encoding="utf-8") as f:
                json.dump({"documents": [{"name": "../a.pdf", "md5": "a" * 32}]}, f)
            try:
                load_manifest(manifest)
                raise AssertionError("路径分隔符应拒绝")
            except EvalIngestError:
                pass
    register("manifest 校验（缺文件/结构错误/路径穿越）", test_manifest_missing_file)

    # ---- 4. merge 响应解析 ------------------------------------------------
    def test_merge_response_parsing():
        normal = json.dumps({"code": 200, "message": "文件合并成功，任务已提交处理",
                             "data": {"object_url": "http://minio/merged/x",
                                      "estimatedChunkCount": 42}}).encode()
        ok, message, url = parse_merge_response(200, normal)
        assert ok and url == "http://minio/merged/x" and "合并成功" in message
        idem = json.dumps({"code": 200, "message": "文件已完成合并",
                           "data": {"object_url": "http://minio/merged/x"}}).encode()
        ok, message, url = parse_merge_response(200, idem)
        assert ok and url == "http://minio/merged/x" and "已完成合并" in message
        # 缺 object_url：仍算成功但提示
        ok, message, url = parse_merge_response(
            200, json.dumps({"code": 200, "message": "ok", "data": {}}).encode())
        assert ok and url == "" and "object_url" in message
        # 失败码
        ok, message, _ = parse_merge_response(
            400, json.dumps({"code": 400, "message": "文件分片未全部上传，无法合并"}).encode())
        assert not ok and "分片未全部上传" in message
        # 非 JSON
        ok, message, _ = parse_merge_response(502, b"<html>bad gateway</html>")
        assert not ok and "JSON" in message
        # 空体
        ok, message, _ = parse_merge_response(500, b"")
        assert not ok
    register("merge 响应解析（正常/幂等/缺 url/失败/非 JSON）", test_merge_response_parsing)

    # ---- 5. ES count 轮询状态机（注入 fake HTTP + fake 时钟） --------------
    def test_es_poll_state_machine():
        es = EsConfig(url="http://es:9200", index="knowledge_base", auth_header="Basic eA==")
        clock = _FakeClock()
        # 5.1 0 → 0 → 5：成功
        seq = [(200, json.dumps({"count": 0})),
               (200, json.dumps({"count": 0})),
               (200, json.dumps({"count": 5}))]
        http_fn, calls = _fake_http(seq)
        outcome = poll_es_chunks(es, "a" * 32, http_fn, interval=5, timeout=120,
                                 now_fn=clock, sleep_fn=clock.sleep)
        assert outcome.indexed and outcome.count == 5 and outcome.polls == 3
        assert len(calls) == 3
        assert calls[0]["url"] == "http://es:9200/knowledge_base/_count"
        assert calls[0]["headers"]["Authorization"] == "Basic eA=="
        body = json.loads(calls[0]["data"].decode())
        assert body == {"query": {"term": {"fileMd5": "a" * 32}}}

        # 5.2 永远 0：超时失败
        clock2 = _FakeClock()
        http_fn, _ = _fake_http([(200, json.dumps({"count": 0}))])
        outcome = poll_es_chunks(es, "b" * 32, http_fn, interval=5, timeout=60,
                                 now_fn=clock2, sleep_fn=clock2.sleep)
        assert not outcome.indexed and "超时" in outcome.reason and outcome.polls >= 2

        # 5.3 瞬时异常后恢复（500 → 0 → 3）
        clock3 = _FakeClock()
        http_fn, _ = _fake_http([(500, json.dumps({"error": {"type": "t", "reason": "boom"}})),
                                 (200, json.dumps({"count": 0})),
                                 (200, json.dumps({"count": 3}))])
        outcome = poll_es_chunks(es, "c" * 32, http_fn, interval=1, timeout=60,
                                 now_fn=clock3, sleep_fn=clock3.sleep)
        assert outcome.indexed and outcome.count == 3 and outcome.polls == 3

        # 5.4 别名不存在（404 连续）：连续错误达到上限 → 明确失败并提示索引名
        clock4 = _FakeClock()
        http_fn, _ = _fake_http([(404, json.dumps({"error": {"type": "index_not_found_exception",
                                                             "reason": "no such index"}}))])
        outcome = poll_es_chunks(es, "d" * 32, http_fn, interval=1, timeout=600,
                                 now_fn=clock4, sleep_fn=clock4.sleep, max_consecutive_errors=3)
        assert not outcome.indexed and "索引" in outcome.reason
        # 5.5 传输层异常连续：判失败
        clock5 = _FakeClock()
        http_fn, _ = _fake_http([{"raise": TransportError("refused")}])
        outcome = poll_es_chunks(es, "e" * 32, http_fn, interval=1, timeout=600,
                                 now_fn=clock5, sleep_fn=clock5.sleep, max_consecutive_errors=2)
        assert not outcome.indexed and "连接异常" in outcome.reason
        # 5.6 parse_count_response 细节
        ok, count, err = parse_count_response(200, json.dumps({"count": 7}).encode())
        assert ok and count == 7
        ok, count, err = parse_count_response(200, b"not json")
        assert not ok and err
        ok, count, err = parse_count_response(200, json.dumps({"no": "count"}).encode())
        assert not ok and "count" in err
    register("ES count 轮询状态机（成功/超时/瞬时异常恢复/404/传输异常）", test_es_poll_state_machine)

    # ---- 6. 三级凭据 + 登录解析 ------------------------------------------
    def test_credentials_and_login():
        with tempfile.TemporaryDirectory() as tmp:
            env_file = os.path.join(tmp, ".env")
            # 一级：EVAL_ADMIN_TOKEN
            creds = acquire_credentials({"EVAL_ADMIN_TOKEN": "t1"}, env_file)
            assert creds.token == "t1" and creds.source == "EVAL_ADMIN_TOKEN"
            # 二级：环境变量用户名/密码
            creds = acquire_credentials({"EVAL_ADMIN_USERNAME": "u", "EVAL_ADMIN_PASSWORD": "p"}, env_file)
            assert creds.token == "" and creds.can_relogin()
            # 二级半：只设了密码 → 报缺用户名（报错文本不含凭据值本身）
            secret_pwd = "sekrit-pw-do-not-echo"
            try:
                acquire_credentials({"EVAL_ADMIN_PASSWORD": secret_pwd}, env_file)
                raise AssertionError("只设密码应报错")
            except EvalIngestError as e:
                assert "EVAL_ADMIN_USERNAME" in str(e) and secret_pwd not in str(e)
            # 三级：.env 的 ADMIN_BOOTSTRAP_*
            with open(env_file, "w", encoding="utf-8") as f:
                f.write("# comment\nADMIN_BOOTSTRAP_USERNAME = \"bu\"\n"
                        "ADMIN_BOOTSTRAP_PASSWORD=bp\r\nOTHER=1\n")
            creds = acquire_credentials({}, env_file)
            assert creds.username == "bu" and creds.password == "bp"
            assert "ADMIN_BOOTSTRAP" in creds.source
            # 三级缺失 → 列出缺哪个变量名
            try:
                acquire_credentials({}, os.path.join(tmp, "nope.env"))
                raise AssertionError("无凭据应报错")
            except EvalIngestError as e:
                assert "EVAL_ADMIN_TOKEN" in str(e)
            # env_lookup：进程环境优先于 .env
            assert env_lookup({"K": "env"}, env_file, "K") == "env"
            assert env_lookup({}, env_file, "OTHER") == "1"
        # 登录响应解析
        token, err = parse_login_response(
            200, json.dumps({"code": 200, "data": {"token": "tok"}}).encode())
        assert token == "tok" and not err
        token, err = parse_login_response(401, b'{"code":401,"message":"bad"}')
        assert not token and "401" in err and "bad" not in err  # 错误提示不带凭据值
        token, err = parse_login_response(200, b'{"code":200,"data":{}}')
        assert not token and "data.token" in err
        token, err = parse_login_response(429, b'{"code":429,"message":"x"}')
        assert not token and "429" in err
    register("三级凭据与登录解析（优先级/缺失报变量名/不回显）", test_credentials_and_login)

    # ---- 7. multipart 组装 -------------------------------------------------
    def test_multipart_builder():
        fields = {"fileMd5": "a" * 32, "chunkIndex": 1, "totalSize": 2 * CHUNK_SIZE,
                  "fileName": "x.pdf", "totalChunks": 2, "isPublic": "true"}
        body, ctype = build_multipart(fields, "file", "x.pdf", "application/pdf", b"PDFDATA")
        assert ctype.startswith("multipart/form-data; boundary=")
        text = body.decode("utf-8", errors="replace")
        for key, value in fields.items():
            assert f'name="{key}"' in text
            assert str(value) in text
        assert 'filename="x.pdf"' in text and "application/pdf" in text
        assert b"PDFDATA" in body
        boundary = ctype.split("boundary=", 1)[1].encode()
        assert body.count(b"--" + boundary) >= len(fields) + 2
    register("multipart 组装（字段/文件名/边界一致）", test_multipart_builder)

    # ---- 8. 401 重登一次 ----------------------------------------------------
    def test_401_relogin_once():
        # 预置初始 token（tok1），让第一个请求直接走 401 → 重登 → 重试路径
        creds = Credentials(username="u", password="p", token="tok1")
        # 第一次请求 401 → 登录 200 → 重试 200
        script = [(401, b'{"code":401,"message":"expired"}'),
                  (200, json.dumps({"code": 200, "data": {"token": "tok2"}}).encode()),
                  (200, json.dumps({"code": 200, "data": {"uploaded": [0]}}).encode())]
        http_fn, calls = _fake_http(script)
        client = AuthedClient("http://be", creds, http_fn=http_fn)
        status, body = client.request("GET", "/api/v1/upload/status?file_md5=x")
        assert status == 200
        login_call = calls[1]
        assert login_call["url"].endswith("/api/v1/users/login")
        # 登录请求体含凭据但测试内不打印它；断言 Authorization 用新 token
        assert calls[2]["headers"]["Authorization"] == "Bearer tok2"
        # 登录也 401 → 报错且不重试第二次登录
        creds2 = Credentials(username="u", password="p", token="tok1")
        http_fn2, calls2 = _fake_http([(401, b'{"code":401,"message":"expired"}'),
                                       (401, b'{"code":401,"message":"bad creds"}')])
        client2 = AuthedClient("http://be", creds2, http_fn=http_fn2)
        try:
            client2.request("GET", "/anything")
            raise AssertionError("登录 401 应抛错")
        except EvalIngestError as e:
            assert "用户名或密码" in str(e)
        assert len(calls2) == 2
        # token 来自环境（无用户名/密码）→ 401 原样返回，无法重登
        creds3 = Credentials(token="env-token")
        http_fn3, calls3 = _fake_http([(401, b'{"code":401,"message":"expired"}')])
        client3 = AuthedClient("http://be", creds3, http_fn=http_fn3)
        status, _ = client3.request("GET", "/anything")
        assert status == 401 and len(calls3) == 1
    register("401 重登一次（重登成功/登录失败/token 环境直供）", test_401_relogin_once)

    # ---- 9. 分片响应解析 + 单篇流程端到端（fake HTTP，>5MB 临时文件） -------
    def test_chunk_response_and_flow():
        ok, msg, done = parse_chunk_response(
            200, json.dumps({"code": 200, "message": "分片上传成功",
                             "data": {"uploaded": [0, 1], "progress": 100.0}}).encode())
        assert ok and not done
        ok, msg, done = parse_chunk_response(
            409, json.dumps({"code": 409, "message": "文件已完成合并，不允许继续上传分片"}).encode())
        assert not ok and done
        ok, msg, done = parse_chunk_response(
            409, json.dumps({"code": 409, "message": "文件正在合并中，请稍后重试"}).encode())
        assert not ok and not done and "合并中" in msg
        ok, msg, done = parse_chunk_response(400, b'{"code":400,"message":"type not allowed"}')
        assert not ok and not done and "400" in msg

        # 端到端：2 分片（5MB+16B），resume=False
        with tempfile.TemporaryDirectory() as tmp:
            corpus = os.path.join(tmp, "corpus")
            os.makedirs(corpus)
            size = CHUNK_SIZE + 16
            content = b"\x25PDF" * (size // 4)
            _write_temp(corpus, "two.pdf", content)
            doc = {"name": "two.pdf", "md5": hashlib.md5(content).hexdigest()}
            es = EsConfig(url="http://es", index="knowledge_base")
            # 请求顺序：ES 预检 count → chunk×2 → merge → 轮询 count 0 → count 4
            script = [(200, json.dumps({"count": 0}).encode()),
                      (200, json.dumps({"code": 200, "data": {"uploaded": [0]}}).encode()),
                      (200, json.dumps({"code": 200, "data": {"uploaded": [0, 1]}}).encode()),
                      (200, json.dumps({"code": 200, "message": "文件合并成功，任务已提交处理",
                                        "data": {"object_url": "http://o"}}).encode()),
                      (200, json.dumps({"count": 0}).encode()),
                      (200, json.dumps({"count": 4}).encode())]
            http_fn, calls = _fake_http(script)
            client = AuthedClient("http://be", Credentials(token="t"), http_fn=http_fn)
            clock = _FakeClock()
            outcome = ingest_document(client, es, doc, corpus, resume=False,
                                      poll_interval=1, poll_timeout=60,
                                      log=lambda *_: None, es_http_fn=http_fn,
                                      sleep_fn=clock.sleep, now_fn=clock)
            assert outcome["status"] == "done" and "4" in outcome["detail"], outcome
            chunk_calls = [c for c in calls if c["url"].endswith("/api/v1/upload/chunk")]
            assert len(chunk_calls) == 2
            # 并发下调用顺序不定：收集各请求的 chunkIndex 比集合；其余字段逐请求断言
            def _field(text, name):
                match = re.search(r'name="' + name + r'"\r\n\r\n([^\r\n]+)', text)
                return match.group(1) if match else None
            idxs = sorted(int(_field(c["data"].decode("utf-8", errors="replace"), "chunkIndex"))
                          for c in chunk_calls)
            assert idxs == [0, 1]
            for c in chunk_calls:
                text = c["data"].decode("utf-8", errors="replace")
                assert _field(text, "totalChunks") == "2"
                assert _field(text, "isPublic") == "true"
                assert _field(text, "fileMd5") == doc["md5"]
                assert _field(text, "fileName") == "two.pdf"
                assert str(os.path.getsize(os.path.join(corpus, "two.pdf"))) == _field(text, "totalSize")
            merge_calls = [c for c in calls if c["url"].endswith("/api/v1/upload/merge")]
            assert len(merge_calls) == 1
            assert json.loads(merge_calls[0]["data"].decode()) == {
                "fileMd5": doc["md5"], "fileName": "two.pdf"}

            # 已入库跳过：ES 首查 count>0 → 不发任何上传请求
            http_fn2, calls2 = _fake_http([(200, json.dumps({"count": 9}).encode())])
            client2 = AuthedClient("http://be", Credentials(token="t"), http_fn=http_fn2)
            outcome2 = ingest_document(client2, es, doc, corpus, resume=False,
                                       poll_interval=1, poll_timeout=60,
                                       log=lambda *_: None, es_http_fn=http_fn2,
                                       sleep_fn=clock.sleep, now_fn=clock)
            assert outcome2["status"] == "skipped" and len(calls2) == 1

            # resume：ES 预检 0 → status 已传 [0] → 只补第 1 片
            script3 = [(200, json.dumps({"count": 0}).encode()),
                       (200, json.dumps({"code": 200, "data": {"uploaded": [0]}}).encode()),
                       (200, json.dumps({"code": 200, "data": {"uploaded": [0, 1]}}).encode()),
                       (200, json.dumps({"code": 200, "data": {"object_url": "http://o"}}).encode()),
                       (200, json.dumps({"count": 0}).encode()),
                       (200, json.dumps({"count": 2}).encode())]
            http_fn3, calls3 = _fake_http(script3)
            client3 = AuthedClient("http://be", Credentials(token="t"), http_fn=http_fn3)
            outcome3 = ingest_document(client3, es, doc, corpus, resume=True,
                                       poll_interval=1, poll_timeout=60,
                                       log=lambda *_: None, es_http_fn=http_fn3,
                                       sleep_fn=clock.sleep, now_fn=clock)
            assert outcome3["status"] == "done"
            chunk_calls3 = [c for c in calls3 if c["url"].endswith("/api/v1/upload/chunk")]
            assert len(chunk_calls3) == 1
            assert _field(chunk_calls3[0]["data"].decode("utf-8", errors="replace"),
                          "chunkIndex") == "1"

            # 轮询超时：merge 成功但 ES 永远 0 → failed
            script4 = [(200, json.dumps({"count": 0}).encode()),
                       (200, json.dumps({"code": 200, "data": {"uploaded": [0, 1]}}).encode()),
                       (200, json.dumps({"code": 200, "data": {"uploaded": [0, 1]}}).encode()),
                       (200, json.dumps({"code": 200, "data": {"object_url": "http://o"}}).encode()),
                       (200, json.dumps({"count": 0}).encode())]
            http_fn4, _ = _fake_http(script4)
            client4 = AuthedClient("http://be", Credentials(token="t"), http_fn=http_fn4)
            clock4 = _FakeClock()
            outcome4 = ingest_document(client4, es, doc, corpus, resume=False,
                                       poll_interval=1, poll_timeout=5,
                                       log=lambda *_: None, es_http_fn=http_fn4,
                                       sleep_fn=clock4.sleep, now_fn=clock4)
            assert outcome4["status"] == "failed" and "超时" in outcome4["detail"]
    register("分片响应解析与单篇流程端到端（fake HTTP/续传/已入库跳过/超时）", test_chunk_response_and_flow)

    # ---- 10. --only 选择 ----------------------------------------------------
    def test_select_documents():
        docs = [{"name": "01-clip.pdf", "md5": "a" * 32},
                {"name": "02-dino.pdf", "md5": "b" * 32},
                {"name": "03-dino2.pdf", "md5": "c" * 32}]
        assert len(select_documents(docs, "")) == 3
        assert select_documents(docs, "01-clip.pdf")[0]["name"] == "01-clip.pdf"
        assert select_documents(docs, "02")[0]["name"] == "02-dino.pdf"  # 唯一前缀
        for bad in ("dino", "99"):
            try:
                select_documents(docs, bad)
                raise AssertionError(f"{bad} 应报错（多义/无匹配）")
            except EvalIngestError:
                pass
    register("--only 选择（精确/唯一前缀/歧义与无匹配）", test_select_documents)

    passed, failed = 0, []
    print("==> eval_corpus_ingest --self-test（离线，不联网）")
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
        prog="eval_corpus_ingest.py",
        description="评测语料入库：5MB 分片上传 → merge → 等待 ES 入库（task-0001）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
模式：
  --dry-run               只读本地校验（文件 → md5 → 分片数），不联网，失败退出 2
  --run                   真跑：三级取管理员 token → 分片上传(≤2 并发) → merge → 轮询 ES
  --resume                配合 --run：只补传缺失分片（断点续传；已入库文件总是跳过）
  --only NAME             只处理一篇（精确文件名或唯一前缀），单篇重试
  --self-test             离线自检（fake HTTP，不联网）

环境变量（详见 docs/eval/corpus-ingest.md）：
  EVAL_BASE_URL            后端地址（默认 http://localhost:8081）
  EVAL_ADMIN_TOKEN / EVAL_ADMIN_USERNAME / EVAL_ADMIN_PASSWORD
  EVAL_ENV_FILE            .env 路径（默认仓库根 .env；三级凭据与 ELASTICSEARCH_* 兜底来源）
  ELASTICSEARCH_SCHEME/HOST/PORT/USERNAME/PASSWORD   ES 直连（默认 http://localhost:9200, elastic/空）
  EVAL_ES_INDEX            ES 索引/别名（默认 knowledge_base）
  EVAL_ES_POLL_INTERVAL_SECONDS / EVAL_ES_POLL_TIMEOUT_SECONDS
""")
    parser.add_argument("--dry-run", action="store_true", help="只读本地校验，不联网")
    parser.add_argument("--run", action="store_true", help="真跑入库（需服务与凭据）")
    parser.add_argument("--resume", action="store_true", help="配合 --run：断点续传，只补缺失分片")
    parser.add_argument("--only", metavar="NAME", default="", help="只处理一篇（精确名或唯一前缀）")
    parser.add_argument("--self-test", action="store_true", help="离线自检（不联网）")
    parser.add_argument("--manifest", default=os.path.join("eval-datasets", "manifest.json"),
                        help="manifest 路径（默认 eval-datasets/manifest.json）")
    parser.add_argument("--corpus-dir", default=os.path.join("eval-datasets", "corpus"),
                        help="语料目录（默认 eval-datasets/corpus；工作树需先放入本地 PDF）")
    parser.add_argument("--base-url", default=os.environ.get("EVAL_BASE_URL", DEFAULT_BASE_URL),
                        help="后端地址（默认 EVAL_BASE_URL 或 http://localhost:8081）")
    parser.add_argument("--env-file", default=None,
                        help=".env 路径（默认 EVAL_ENV_FILE 或仓库根 .env）")
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
    if args.run and args.dry_run:
        print("!! --run 与 --dry-run 互斥，只能选一个", file=sys.stderr)
        return 2
    try:
        if args.dry_run:
            return cmd_dry_run(args)
        if args.run:
            return cmd_run(args)
    except EvalIngestError as e:
        # 到这里的都是配置/静态级失败（manifest、--only、凭据三级、ES 配置）：
        # 指明缺什么，退出 2（与 eval_real_smoke.sh 静态项缺失口径一致）
        print(f"!! {e}", file=sys.stderr)
        return 2
    parser.print_help()
    print("\n!! 未指定模式：请加 --dry-run / --run / --resume / --only / --self-test", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
