#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按证据短语回填 expectedChunkIds（task-0005，eval-real-run 包）。

读评测数据集与 ES（按 fileMd5 拉全部分块的 chunkId/textContent），用与
RetrievalMetrics.normalizeText 完全相同的口径（NFKC → 去空白 → 小写，
见 src/main/java/com/mindbase/service/eval/RetrievalMetrics.java:268）把每条
expectedEvidence 匹配到分块，把 expectedChunkIds 写回数据集，让
RetrievalMetrics.isRelevant 走 chunkId 判定而不是短语 anyMatch 近似。

写回格式（由解析器语义决定，不是自由选择）：
  * expectedChunkIds 与 expectedFileNames 平行，第 i 个元素是第 i 个期望文件里
    答案所在分块的 chunkId（EvalCase.java javadoc；EvalDatasetParser.java:191
    对长度失配会 warn，EvalDatasetIntegrityTest 断言解析零 warn）。本数据集
    每条恰好 1 个期望文件，因此写回值是单元素列表，如 ["3"]。
  * 一条用例的所有期望文件都匹配到分块才写回；任一文件匹配不到就写 []（空列表，
    即「不标注」），保持 goldFallback=phrase 短语口径。绝不写 [""]：chunkIdEquals
    对空串返回 false 且不回退短语（RetrievalMetrics.java:237），空串标注会让
    该题在 chunkId 口径下永假，比不标注更糟。
  * chunkId 写成十进制字符串（与 EvalGoldFieldsTest 的 ["3","12"] 标注一致）。

匹配规则：
  * 分块候选 = 该文件 fileMd5 在 ES 里的全部分块；短语（归一化后）是分块
    textContent（归一化后）的子串即命中（与 RetrievalMetrics.containsEvidence
    的 contains 语义一致）。
  * 匹配到多个分块：取包含完整短语且 chunkId 最小的一个，并在报告里标 ambiguous。
  * 跨块短语（短语被分块边界切开）匹配不到 → 该用例未匹配，如实报告。
  * 绝不用文件名哈希、页码或其他猜测来源补 id；匹配不到就是匹配不到。

覆盖率：匹配到 chunkId 的用例数 / 带 expectedEvidence 的用例数（126 条全带）。
--check 低于 --min-coverage 退出 1 并列出未匹配用例。

模式：
  （默认）        只读报告：打印 markdown 表（id、文件、短语命中、chunkId、ambiguous）。
  --check        覆盖率门禁：覆盖率 < --min-coverage 退出 1 并列未匹配用例；
                 无法判定（ES 连接失败/查询失败/数据集问题）退出 2。
  --write        幂等写回数据集（保持 JSONL 字段顺序与中文不转义；注释行原样保留；
                 原子替换；写回后重新加载校验）。覆盖率 0（含无法判定）时拒绝写回、
                 退出 2——此时写回会给全部用例写无信息的空数组（语料未入库或短语全不命中）。
  --dry-run      只读预览：打印前 10 条匹配结果（不写任何文件）。
  --self-test    离线自检：fixture 数据集 + 注入 fake HTTP，覆盖精确匹配、跨块短语、
                 大小写/全半角差异、缺失文件、多分块取最小、幂等写回、覆盖率门禁。

ES 连接（ELASTICSEARCH_* 环境变量，进程环境优先、仓库根 .env 兜底；与
eval_corpus_readiness.py 同口径）：
  ELASTICSEARCH_SCHEME    默认 http
  ELASTICSEARCH_HOST      默认 localhost
  ELASTICSEARCH_PORT      默认 9200
  ELASTICSEARCH_USERNAME  默认 elastic
  ELASTICSEARCH_PASSWORD  默认空
  EVAL_ES_INDEX           ES 索引/别名，默认 knowledge_base（KnowledgeIndexNames.ALIAS）
  scheme=https 时按自签证书处理：不校验证书（对应后端 insecure-trust-all-certificates=true）。
  本脚本对 ES 只读（只发 POST {index}/_search）。

退出码：
  0  报告/--dry-run/--write 成功；或 --check 且覆盖率 >= --min-coverage
  1  --check：覆盖率低于 --min-coverage（列出未匹配用例）
  2  无法判定：ES 连接失败 / 查询失败 / 数据集或参数问题 / --write 覆盖率 0 拒写

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
import unicodedata
import urllib.error
import urllib.request

# ---- 常量 -------------------------------------------------------------------
DEFAULT_DATASET = os.path.join("src", "main", "resources", "eval", "eval-v2.jsonl")
DEFAULT_ES_INDEX = "knowledge_base"   # KnowledgeIndexNames.ALIAS（KnowledgeIndexNames.java:12）
DEFAULT_ES_TIMEOUT = 15.0             # 单次 ES 请求超时（秒）
DEFAULT_MIN_COVERAGE = 0.9
MD5_RE = re.compile(r"^[0-9a-fA-F]{32}$")
ES_PAGE_SIZE = 1000                   # 单页拉取的分块数
ES_MAX_CHUNKS_PER_FILE = 20000        # 单文件分块数上限（防失控）
DRY_RUN_PREVIEW = 10                  # --dry-run 打印条数

ST_MATCHED = "命中"
ST_UNMATCHED = "未匹配"
ST_MULTI = "多分块命中"   # 命中且 ambiguous（多分块含完整短语，取 chunkId 最小者）
ST_MISSING_FILE = "缺分块"  # 该文件在 ES 里没有任何分块（未入库/未解析），不是短语匹配不上


class EvalBackfillError(Exception):
    """可预期的操作失败（数据集/配置/ES 查询级），退出 2。"""


class TransportError(EvalBackfillError):
    """网络/连接层失败（与 HTTP 4xx/5xx 区分：报「连接失败」而非「未匹配」）。"""


# ---------------------------------------------------------------------------
# 归一化（与 RetrievalMetrics.normalizeText 同口径）
# ---------------------------------------------------------------------------

# Java replaceAll("\\s+", "") 在无 UNICODE_CHARACTER_CLASS 时只匹配 ASCII 六字符
# （[ \t\n\x0B\f\r]）；全角空格/NBSP 等由 NFKC 先折算成普通空格再被去掉，
# 与 Java 侧行为一致。这里逐字符删除，等价于 removeAll("\\s+")。
_JAVA_WS_TABLE = {ord(c): None for c in " \t\n\x0b\f\r"}


def normalize_text(text):
    """NFKC → 去空白 → 小写，与 RetrievalMetrics.normalizeText（Java）同口径。"""
    if text is None:
        return ""
    return unicodedata.normalize("NFKC", text).translate(_JAVA_WS_TABLE).lower()


# ---------------------------------------------------------------------------
# 数据集加载（保留注释行结构；写回时注释原样保留）
# ---------------------------------------------------------------------------

def load_dataset(path):
    """读 JSONL 数据集。

    返回 (raw_text, entries, problems)：
      raw_text  原文（写回时以此为基础保留注释行）
      entries   [{"lineNo": 行号(1起), "obj": dict, "id": str, "md5s": [str],
                  "names": [str], "phrases": [str]}]
      problems  [str] 结构问题列表（空列表 = 通过）

    数据行必须是 JSON 对象；id/expectedFileNames/expectedEvidence/expectedFileMd5s
    缺失或类型不对记 problem。md5 统一小写。
    """
    if not os.path.isfile(path):
        raise EvalBackfillError(f"数据集不存在: {path}")
    try:
        with open(path, "r", encoding="utf-8") as f:
            raw_text = f.read()
    except OSError as e:
        raise EvalBackfillError(f"数据集不可读: {path}（{e}）")

    entries, problems = [], []
    for line_no, line in enumerate(raw_text.split("\n"), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        try:
            obj = json.loads(stripped)
        except ValueError as e:
            problems.append(f"第 {line_no} 行不是合法 JSON: {e}")
            continue
        if not isinstance(obj, dict):
            problems.append(f"第 {line_no} 行不是 JSON 对象")
            continue
        case_id = obj.get("id")
        if not isinstance(case_id, str) or not case_id.strip():
            problems.append(f"第 {line_no} 行缺非空字符串 id")
            continue
        names = obj.get("expectedFileNames")
        md5s = obj.get("expectedFileMd5s")
        phrases = obj.get("expectedEvidence")
        if not isinstance(names, list) or not names or not all(isinstance(n, str) and n.strip() for n in names):
            problems.append(f"第 {line_no} 行（{case_id}）缺非空 expectedFileNames 列表")
            continue
        if not isinstance(phrases, list) or not all(isinstance(p, str) for p in phrases):
            problems.append(f"第 {line_no} 行（{case_id}）expectedEvidence 不是字符串列表")
            continue
        if not isinstance(md5s, list) or not all(isinstance(m, str) for m in md5s):
            problems.append(f"第 {line_no} 行（{case_id}）expectedFileMd5s 不是字符串列表")
            continue
        if len(md5s) != len(names):
            problems.append(f"第 {line_no} 行（{case_id}）expectedFileMd5s 与 expectedFileNames 长度不一致"
                            f"（{len(md5s)} != {len(names)}）")
            continue
        entries.append({
            "lineNo": line_no,
            "obj": obj,
            "id": case_id.strip(),
            "md5s": [m.strip().lower() for m in md5s],
            "names": [n.strip() for n in names],
            "phrases": [p for p in phrases if normalize_text(p)],
        })
    return raw_text, entries, problems


# ---------------------------------------------------------------------------
# .env 读取与 ES 配置（与 eval_corpus_readiness.py 同口径；只取值，绝不回显）
# ---------------------------------------------------------------------------

def parse_env_text(text, key):
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


def env_file_get(env_file, key):
    if not env_file or not os.path.isfile(env_file):
        return ""
    try:
        with open(env_file, "r", encoding="utf-8", errors="replace") as f:
            return parse_env_text(f.read(), key)
    except OSError:
        return ""


def env_lookup(env, env_file, key):
    """先进程环境变量，后仓库根 .env。缺失返回空串。"""
    value = env.get(key, "")
    if isinstance(value, str) and value.strip():
        return value.strip()
    return env_file_get(env_file, key)


class EsConfig:
    def __init__(self, url, index, auth_header="", timeout=DEFAULT_ES_TIMEOUT):
        self.url = url.rstrip("/")
        self.index = index
        self.auth_header = auth_header
        self.timeout = timeout

    def headers(self):
        h = {"Content-Type": "application/json"}
        if self.auth_header:
            h["Authorization"] = self.auth_header
        return h


def build_es_config(env, env_file):
    """ELASTICSEARCH_* 环境变量（缺省 elastic/空、http://localhost:9200），进程环境优先、.env 兜底。"""
    scheme = (env_lookup(env, env_file, "ELASTICSEARCH_SCHEME") or "http").lower()
    host = env_lookup(env, env_file, "ELASTICSEARCH_HOST") or "localhost"
    port = env_lookup(env, env_file, "ELASTICSEARCH_PORT") or "9200"
    user = env_lookup(env, env_file, "ELASTICSEARCH_USERNAME") or "elastic"
    pwd = env_lookup(env, env_file, "ELASTICSEARCH_PASSWORD") or ""
    index = env_lookup(env, env_file, "EVAL_ES_INDEX") or DEFAULT_ES_INDEX
    if scheme not in ("http", "https"):
        raise EvalBackfillError(f"ELASTICSEARCH_SCHEME 只支持 http/https，当前: {scheme}")
    url = f"{scheme}://{host}:{port}"
    auth_header = ""
    if user:  # 用户名非空即带 Basic（密码可为空串）；默认 elastic/空
        auth_header = "Basic " + base64.b64encode(f"{user}:{pwd}".encode()).decode()
    return EsConfig(url=url, index=index, auth_header=auth_header)


def build_insecure_ssl_context():
    """https 自签证书场景：不校验证书（只读查询，与后端 dev 配置一致）。"""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def default_http(method, url, headers, data, timeout):
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
# ES 拉取分块（只读；_search term fileMd5，翻页拉全）
# ---------------------------------------------------------------------------

def parse_search_page(status, body):
    """解析 ES _search 单页响应。返回 (ok, hits:list[dict], total:int|None, error:str)。

    401/404 是「无法判定」而非「没有分块」：错误信息给出环境变量名/索引名排查方向。
    """
    if status == 401:
        return False, [], None, ("ES 认证失败（401）：设置 ELASTICSEARCH_USERNAME / "
                                 "ELASTICSEARCH_PASSWORD 环境变量后重试（本脚本不猜默认密码）")
    if status == 404:
        return False, [], None, "ES 索引/别名不存在（404；检查 EVAL_ES_INDEX，后端启动会建别名 knowledge_base）"
    if not body:
        return False, [], None, f"ES 响应体为空（HTTP {status}）"
    try:
        payload = json.loads(body.decode("utf-8", errors="replace"))
    except ValueError as e:
        return False, [], None, f"ES 响应不是合法 JSON: {e}"
    if not isinstance(payload, dict) or not isinstance(payload.get("hits"), dict):
        return False, [], None, "ES 响应缺 hits 对象"
    if status != 200:
        reason = ""
        err = payload.get("error")
        if isinstance(err, dict):
            reason = str(err.get("type") or "") + ": " + str(err.get("reason") or "")
        elif err is not None:
            reason = str(err)
        return False, [], None, f"ES _search HTTP {status}：{reason or '（无错误详情）'}"
    hits_obj = payload["hits"]
    hits = hits_obj.get("hits")
    if not isinstance(hits, list):
        return False, [], None, "ES 响应 hits.hits 不是数组"
    total_obj = hits_obj.get("total")
    if isinstance(total_obj, dict) and isinstance(total_obj.get("value"), int):
        total = total_obj["value"]
    elif isinstance(total_obj, int):
        total = total_obj
    else:
        total = None  # 老 ES 不带 total 结构时按翻页到底处理
    return True, hits, total, ""


def fetch_chunks_for_md5(es_cfg, file_md5, http_fn):
    """按 fileMd5 拉该文件全部分块，返回 [(chunkId:int, text_norm:str)]（按 chunkId 升序）。

    只取 _source 的 chunkId/textContent；chunkId 非整数或缺失的分块记为问题（不能猜）。
    """
    chunks = []
    from_ = 0
    total = None
    while True:
        body = json.dumps({
            "size": ES_PAGE_SIZE,
            "from": from_,
            "track_total_hits": True,
            "_source": ["fileMd5", "chunkId", "textContent"],
            "query": {"term": {"fileMd5": file_md5}},
            "sort": [{"chunkId": "asc"}],
        }, ensure_ascii=False).encode("utf-8")
        try:
            status, resp = http_fn("POST", f"{es_cfg.url}/{es_cfg.index}/_search",
                                   headers=es_cfg.headers(), data=body, timeout=es_cfg.timeout)
        except TransportError as e:
            raise TransportError(f"拉取 {file_md5[:8]}… 分块时 {e}")
        ok, hits, page_total, error = parse_search_page(status, resp)
        if not ok:
            raise EvalBackfillError(f"拉取 fileMd5={file_md5[:8]}… 分块失败: {error}")
        if total is None:
            total = page_total
        for hit in hits:
            source = hit.get("_source") if isinstance(hit, dict) else None
            if not isinstance(source, dict):
                raise EvalBackfillError(f"fileMd5={file_md5[:8]}… 的命中缺 _source，无法定位 chunkId（不猜 id）")
            chunk_id = source.get("chunkId")
            if not isinstance(chunk_id, int) or isinstance(chunk_id, bool):
                raise EvalBackfillError(f"fileMd5={file_md5[:8]}… 存在 chunkId 非整数的分块（不猜 id，需人工检查 ES）")
            chunks.append((chunk_id, normalize_text(source.get("textContent"))))
        if len(hits) < ES_PAGE_SIZE:
            break
        from_ += ES_PAGE_SIZE
        if total is not None and from_ >= total:
            break
        if from_ >= ES_MAX_CHUNKS_PER_FILE:
            raise EvalBackfillError(f"fileMd5={file_md5[:8]}… 分块数超过上限 {ES_MAX_CHUNKS_PER_FILE}，需人工处理")
    if total is not None and len(chunks) < total:
        raise EvalBackfillError(f"fileMd5={file_md5[:8]}… 分块数 {len(chunks)} < ES 报告总数 {total}，翻页异常")
    chunks.sort(key=lambda c: c[0])
    return chunks


# ---------------------------------------------------------------------------
# 匹配（纯函数）
# ---------------------------------------------------------------------------

def match_phrases_to_chunks(phrases, chunks):
    """把短语列表匹配到分块列表。phrases 为 (原文, 归一化) 对；chunks 为 (chunkId, 归一化文本)。

    返回 {"chunkId": int|None, "ambiguous": bool, "perPhrase": [(原文, chunkId|None)],
          "hitCount": 命中短语数}。

    规则：短语（归一化后）是分块文本（归一化后）子串即命中；任一短语命中多个不同
    分块 → 取全部命中里 chunkId 最小者并标 ambiguous；同一分块命中多条短语不算
    ambiguous；跨块短语切开后任何分块都不含完整短语 → 不命中。
    """
    per_phrase = []
    all_chunk_ids = set()
    ambiguous = False
    for original, phrase_norm in phrases:
        hits = [cid for cid, text_norm in chunks if phrase_norm in text_norm]
        per_phrase.append((original, min(hits) if hits else None))
        if len(set(hits)) > 1:
            ambiguous = True
        all_chunk_ids.update(hits)
    if not all_chunk_ids:
        return {"chunkId": None, "ambiguous": False, "perPhrase": per_phrase, "hitCount": 0}
    return {
        "chunkId": min(all_chunk_ids),
        "ambiguous": ambiguous,
        "perPhrase": per_phrase,
        "hitCount": sum(1 for _, cid in per_phrase if cid is not None),
    }


def match_case(entry, chunks_by_md5):
    """对一条用例逐期望文件匹配。

    返回 row dict：
      files: [{name, md5, chunkCount, chunkId|None, ambiguous, hitCount, perPhrase}]
      matched: 所有期望文件都拿到了 chunkId
      chunkIds: 写回值——全部命中时与 expectedFileNames 平行的字符串列表，否则 []
      missingFile: 任一期望文件在 ES 里 0 分块（未入库/未解析）
    """
    phrase_pairs = [(p, normalize_text(p)) for p in entry["phrases"]]
    files = []
    for name, md5 in zip(entry["names"], entry["md5s"]):
        chunks = chunks_by_md5.get(md5)
        if chunks is None:
            chunks = []
        m = match_phrases_to_chunks(phrase_pairs, chunks)
        files.append({
            "name": name,
            "md5": md5,
            "chunkCount": len(chunks),
            "chunkId": m["chunkId"],
            "ambiguous": m["ambiguous"],
            "hitCount": m["hitCount"],
            "phraseTotal": len(entry["phrases"]),
            "perPhrase": m["perPhrase"],
        })
    matched = all(f["chunkId"] is not None for f in files)
    missing_file = any(f["chunkCount"] == 0 for f in files)
    chunk_ids = [str(f["chunkId"]) for f in files] if matched else []
    return {
        "id": entry["id"],
        "lineNo": entry["lineNo"],
        "files": files,
        "matched": matched,
        "missingFile": missing_file,
        "chunkIds": chunk_ids,
    }


def compute_rows(entries, chunks_by_md5):
    """全部用例匹配。返回 (rows, unmatched_ids)。"""
    rows = [match_case(e, chunks_by_md5) for e in entries]
    unmatched = [r["id"] for r in rows if not r["matched"]]
    return rows, unmatched


def coverage_rate(rows):
    """覆盖率：匹配到 chunkId 的用例 / 带（非空）证据短语的用例。"""
    eligible = [r for r in rows if r["files"] and any(f["phraseTotal"] > 0 for f in r["files"])]
    if not eligible:
        return None  # 没有可判定的用例
    return sum(1 for r in eligible if r["matched"]) / len(eligible)


# ---------------------------------------------------------------------------
# 写回（幂等：保持字段顺序与中文不转义；注释行原样保留；原子替换）
# ---------------------------------------------------------------------------

def annotate_obj(obj, chunk_ids):
    """返回更新后的 obj：expectedChunkIds 统一插在 expectedFileNames 之后。

    已存在的旧标注（无论原在哪个位置）先移除再按固定位置重插——保证重复 --write
    的键序稳定（幂等）。Python dict 保持插入序，json.dumps 按插入序输出，
    即「保持 JSONL 字段顺序」。
    """
    result = {}
    for key, value in obj.items():
        if key == "expectedChunkIds":
            continue  # 统一重插，位置稳定
        result[key] = value
        if key == "expectedFileNames":
            result["expectedChunkIds"] = [str(c) for c in chunk_ids]
    if "expectedChunkIds" not in result:
        result["expectedChunkIds"] = [str(c) for c in chunk_ids]
    return result


def build_annotated_text(raw_text, assignments):
    """按 assignments {id: [chunkId 字符串]} 重写数据行；注释/空行原样保留。

    返回 (new_text, rewritten_count, unknown_ids)。数据行重新序列化
    （ensure_ascii=False 中文不转义；默认分隔符与仓库数据集现有格式一致）。
    """
    out_lines = []
    rewritten = 0
    seen_ids = set()
    for line in raw_text.split("\n"):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            out_lines.append(line)
            continue
        obj = json.loads(stripped)
        case_id = str(obj.get("id", "")).strip()
        if case_id in assignments:
            seen_ids.add(case_id)
            new_obj = annotate_obj(obj, assignments[case_id])
            out_lines.append(json.dumps(new_obj, ensure_ascii=False))
            rewritten += 1
        else:
            out_lines.append(line)
    unknown = [cid for cid in assignments if cid not in seen_ids]
    trailing_newline = raw_text.endswith("\n") or raw_text == ""
    new_text = "\n".join(out_lines)
    if trailing_newline and not new_text.endswith("\n"):
        new_text += "\n"
    return new_text, rewritten, unknown


def write_back(dataset_path, raw_text, assignments):
    """原子写回并自校验：重新加载写回后的文件，确认与待写 assignments 一致。"""
    new_text, rewritten, unknown = build_annotated_text(raw_text, assignments)
    if unknown:
        raise EvalBackfillError(f"待写回的 id 在数据集中不存在: {unknown[:5]}")
    tmp_path = dataset_path + ".tmp"
    try:
        with open(tmp_path, "w", encoding="utf-8", newline="") as f:
            f.write(new_text)
        os.replace(tmp_path, dataset_path)
    except OSError as e:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except OSError:
                pass
        raise EvalBackfillError(f"写回失败: {dataset_path}（{e}）")
    # 自校验：重新加载，逐 id 比对写回值
    _, entries, problems = load_dataset(dataset_path)
    if problems:
        raise EvalBackfillError(f"写回后的数据集自身校验失败（不应发生）: {problems[:5]}")
    by_id = {e["id"]: e for e in entries}
    for case_id, chunk_ids in assignments.items():
        entry = by_id.get(case_id)
        if entry is None:
            raise EvalBackfillError(f"写回后数据集缺用例 {case_id}（不应发生）")
        actual = entry["obj"].get("expectedChunkIds")
        expected = [str(c) for c in chunk_ids]
        if actual != expected:
            raise EvalBackfillError(f"写回后 {case_id} 的 expectedChunkIds={actual} != 期望 {expected}")
    return rewritten


# ---------------------------------------------------------------------------
# 渲染
# ---------------------------------------------------------------------------

def render_markdown(rows):
    lines = ["| 用例 id | 期望文件 | 分块数 | 短语命中 | chunkId | ambiguous | 状态 |",
             "| --- | --- | --- | --- | --- | --- | --- |"]
    for r in rows:
        for f in r["files"]:
            if f["chunkCount"] == 0:
                status = ST_MISSING_FILE
            elif f["chunkId"] is None:
                status = ST_UNMATCHED
            elif f["ambiguous"]:
                status = ST_MULTI
            else:
                status = ST_MATCHED
            chunk_id = f["chunkId"] if f["chunkId"] is not None else "-"
            ambiguous = "是" if f["ambiguous"] else "-"
            lines.append(f"| {r['id']} | {f['name']} | {f['chunkCount']} | "
                         f"{f['hitCount']}/{f['phraseTotal']} | {chunk_id} | {ambiguous} | {status} |")
    return "\n".join(lines)


def render_dry_run(rows, limit=DRY_RUN_PREVIEW):
    """--dry-run：前 N 条匹配结果（含逐短语命中，便于人工核对）。"""
    lines = [f"==> --dry-run：前 {min(limit, len(rows))}/{len(rows)} 条匹配结果（只读，不写文件）"]
    for r in rows[:limit]:
        for f in r["files"]:
            status = ST_MISSING_FILE if f["chunkCount"] == 0 else (
                ST_UNMATCHED if f["chunkId"] is None else (ST_MULTI if f["ambiguous"] else ST_MATCHED))
            lines.append(f"  {r['id']}  文件={f['name']} 分块数={f['chunkCount']} "
                         f"短语命中={f['hitCount']}/{f['phraseTotal']} chunkId="
                         f"{f['chunkId'] if f['chunkId'] is not None else '-'} 状态={status}")
            for phrase, cid in f["perPhrase"]:
                shown = phrase if len(phrase) <= 40 else phrase[:37] + "…"
                lines.append(f"    - [{cid if cid is not None else '未命中'}] {shown}")
    return "\n".join(lines)


def build_summary(rows, unmatched, coverage):
    matched = sum(1 for r in rows if r["matched"])
    ambiguous = sum(1 for r in rows if r["matched"] and any(f["ambiguous"] for f in r["files"]))
    missing_file_ids = [r["id"] for r in rows if r["missingFile"]]
    summary = {
        "totalCases": len(rows),
        "matchedCases": matched,
        "unmatchedCases": len(unmatched),
        "ambiguousCases": ambiguous,
        "missingFileCases": len(missing_file_ids),
        "coverage": coverage,
    }
    return summary, missing_file_ids


# ---------------------------------------------------------------------------
# 命令实现
# ---------------------------------------------------------------------------

def resolve_default_env_file():
    here = os.path.dirname(os.path.abspath(__file__))
    return os.environ.get("EVAL_ENV_FILE") or os.path.join(here, "..", ".env")


def validate_min_coverage(value):
    if not 0.0 <= value <= 1.0:
        raise EvalBackfillError(f"--min-coverage 必须在 [0,1]: {value}")
    return value


def collect_chunks(entries, es_cfg, http_fn):
    """按去重后的 md5 拉全部分块。返回 chunks_by_md5；任何查询失败抛 EvalBackfillError/TransportError。"""
    chunks_by_md5 = {}
    for md5 in sorted({m for e in entries for m in e["md5s"]}):
        if not MD5_RE.match(md5):
            raise EvalBackfillError(f"数据集存在非法 md5（32 位十六进制）: {md5[:8]}…")
        chunks_by_md5[md5] = fetch_chunks_for_md5(es_cfg, md5, http_fn)
    return chunks_by_md5


def run_match(dataset_path, es_cfg, http_fn):
    """加载数据集 → 拉 ES 分块 → 匹配。返回 (raw_text, entries, rows, unmatched, coverage)。"""
    raw_text, entries, problems = load_dataset(dataset_path)
    if problems:
        raise EvalBackfillError("数据集校验失败:\n  - " + "\n  - ".join(problems[:10]))
    if not entries:
        raise EvalBackfillError(f"数据集没有可处理的数据行: {dataset_path}")
    chunks_by_md5 = collect_chunks(entries, es_cfg, http_fn)
    rows, unmatched = compute_rows(entries, chunks_by_md5)
    coverage = coverage_rate(rows)
    return raw_text, entries, rows, unmatched, coverage


def cmd_run(args, modes):
    """默认报告 / --check / --write / --dry-run 的公共实现。modes 为标志集合。"""
    es_cfg = build_es_config(os.environ, args.env_file)
    if args.es_timeout is not None:
        if args.es_timeout <= 0:
            raise EvalBackfillError(f"--es-timeout 必须为正数: {args.es_timeout}")
        es_cfg.timeout = args.es_timeout

    raw_text, entries, rows, unmatched, coverage = run_match(args.dataset, es_cfg, default_http)

    is_check = "check" in modes
    is_write = "write" in modes
    is_dry = "dry_run" in modes

    print(f"==> expectedChunkIds 回填（task-0005）[{'+'.join(sorted(modes)) or '报告'}]："
          f"数据集={args.dataset} ES={es_cfg.url}/{es_cfg.index}")
    if is_dry:
        print(render_dry_run(rows))
    else:
        print(render_markdown(rows))

    summary, missing_file_ids = build_summary(rows, unmatched, coverage)
    cov_text = "n/a" if coverage is None else f"{coverage:.4f}（{summary['matchedCases']}/{summary['totalCases']}）"
    parts = [f"命中 {summary['matchedCases']}/{summary['totalCases']}",
             f"覆盖率 {cov_text}",
             f"ambiguous {summary['ambiguousCases']}",
             f"缺分块文件 {summary['missingFileCases']}"]
    print("==> 汇总：" + "；".join(parts))
    if unmatched:
        print("==> 未匹配用例（" + str(summary["unmatchedCases"]) + " 条）: " + ", ".join(unmatched))
    if missing_file_ids:
        print("==> 期望文件在 ES 无任何分块的用例（先跑 scripts/eval_corpus_ingest.py 入库）: "
              + ", ".join(missing_file_ids))

    exit_code = 0
    if is_write:
        # 门禁：覆盖率 0（含无法判定）时写回 = 给全部用例写无信息的空数组。
        # 这只会在语料未入库（期望文件在 ES 0 分块）或短语全不命中时发生，
        # 与 task-0005/C5「覆盖率非零」意图相反——拒绝写，先入库/核对匹配报告。
        if coverage is None or coverage == 0.0:
            raise EvalBackfillError(
                "--write 拒绝执行：覆盖率 0，写回将给全部 "
                f"{summary['totalCases']} 条用例写空 expectedChunkIds（无信息退化写回）。"
                "先跑 scripts/eval_corpus_ingest.py 入库并用 scripts/eval_corpus_readiness.py --check "
                "确认语料就绪；语料已入库则按上方匹配报告核对证据短语。"
            )
        assignments = {r["id"]: r["chunkIds"] for r in rows}
        rewritten = write_back(args.dataset, raw_text, assignments)
        print(f"==> 已写回 {rewritten}/{len(rows)} 条用例的 expectedChunkIds（幂等；"
              f"未匹配用例写 [] 保持短语口径；注释行原样保留）")

    if is_check:
        if coverage is None:
            print("!! 无法计算覆盖率（没有带证据短语的用例）", file=sys.stderr)
            exit_code = 2
        elif coverage < args.min_coverage:
            print(f"!! 覆盖率 {coverage:.4f} < --min-coverage {args.min_coverage}（exit 1）", file=sys.stderr)
            exit_code = 1
        else:
            print(f"==> 覆盖率 {coverage:.4f} >= --min-coverage {args.min_coverage} PASS")
    return exit_code


# ---------------------------------------------------------------------------
# --self-test（离线：fixture 数据集 + 注入 fake HTTP，不联网、不读真实 .env）
# ---------------------------------------------------------------------------

def _md5(tag):
    return (tag * 32)[:32]


_FIX_CHUNKS_A = {
    # 文件 A：3 个分块；短语精确落在 chunk 1
    0: "Introduction. This paper studies contrastive learning.",
    1: "The model was trained on 400 million (image, text) pairs collected from the internet.",
    2: "Ablation studies show the effect of augmentation strength.",
}
_FIX_CHUNKS_B = {
    # 文件 B：全角/大写/康熙部首差异；NFKC+去空白+小写后与半角小写短语相等
    0: "ＴＨＥ ＥＸＰＥＲＩＭＥＮＴ　ＵＳＥＳ ｍｅｄｉｃａｌ ｉｍａｇｅｓ。",   # 全角字母+全角空格+全角句号
    1: "We report results on two benchmarks.",
}


def _fixture_es_http(chunks_by_md5, raise_error=None, status=None, body_override=None):
    """fake ES：POST {index}/_search 按 term fileMd5 返回 fixture 分块（单页装下）。

    body_override(md5, request) 可完全接管响应体（request 为已解析的请求 JSON，
    用于模拟翻页等行为）。
    """
    calls = []

    def http_fn(method, url, headers=None, data=None, timeout=15.0):
        calls.append({"method": method, "url": url, "headers": dict(headers or {}), "data": data})
        if raise_error is not None:
            raise raise_error
        if status is not None and status != 200:
            err_type = {401: "security_exception", 404: "index_not_found_exception"}.get(status, "es_rejected")
            return status, json.dumps({"error": {"type": err_type, "reason": "fixture"}}).encode()
        request = json.loads(data.decode())
        md5 = request["query"]["term"]["fileMd5"]
        if body_override is not None:
            return 200, body_override(md5, request)
        source = chunks_by_md5.get(md5, {})
        hits = [{"_source": {"fileMd5": md5, "chunkId": cid, "textContent": text}}
                for cid, text in sorted(source.items())]
        payload = {"hits": {"total": {"value": len(hits), "relation": "eq"}, "hits": hits}}
        return 200, json.dumps(payload, ensure_ascii=False).encode("utf-8")

    return http_fn, calls


def _fixture_dataset(path, rows):
    """写 fixture 数据集（含注释行，验证注释保留）。"""
    lines = ["# fixture 数据集（self-test 用）"]
    for obj in rows:
        lines.append(json.dumps(obj, ensure_ascii=False))
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return path


def _base_case(case_id, md5, name, phrases, **extra):
    obj = {
        "id": case_id,
        "question": "q-" + case_id,
        "split": "dev",
        "type": case_id.split("-")[0],
        "difficulty": 1,
        "expectedRefusal": False,
        "expectedFileMd5s": [md5],
        "expectedFileNames": [name],
        "expectedEvidence": phrases,
        "referenceAnswer": "r-" + case_id,
    }
    obj.update(extra)
    return obj


def self_test():
    tests = []
    register = lambda name, fn: tests.append((name, fn))

    # ---- 场景 1：精确匹配 ----------------------------------------------------
    def test_exact_match():
        with tempfile.TemporaryDirectory() as tmp:
            ds = _fixture_dataset(os.path.join(tmp, "ds.jsonl"), [
                _base_case("fact-a-1", _md5("a"), "a.pdf", ["400 million (image, text) pairs"]),
            ])
            http_fn, calls = _fixture_es_http({_md5("a"): _FIX_CHUNKS_A})
            es = EsConfig(url="http://es-fixture:9200", index="knowledge_base", auth_header="Basic eC4=")
            raw, entries, rows, unmatched, coverage = run_match(ds, es, http_fn)
            assert rows[0]["matched"] and rows[0]["chunkIds"] == ["1"], rows
            assert rows[0]["files"][0]["chunkCount"] == 3
            assert not rows[0]["files"][0]["ambiguous"]
            assert unmatched == [] and coverage == 1.0
            # 查询口径：POST {index}/_search，term fileMd5，_source 只取三字段
            assert calls[0]["method"] == "POST"
            assert calls[0]["url"] == "http://es-fixture:9200/knowledge_base/_search"
            body = json.loads(calls[0]["data"].decode())
            assert body["query"] == {"term": {"fileMd5": _md5("a")}}
            assert body["_source"] == ["fileMd5", "chunkId", "textContent"]
            assert body["sort"] == [{"chunkId": "asc"}]
            # 归一化口径与 Java 一致（样例断言，防口径漂移）
            assert normalize_text("ＡＢＣ　ＤＥ\r\nF") == "abcdef"
            assert normalize_text("⼤") == normalize_text("大")   # 康熙部首 NFKC 折算
            assert normalize_text("＄４００") == "$400"           # 全角数字/符号
    register("fixture 精确匹配 → chunkId 写回单元素字符串列表", test_exact_match)

    # ---- 场景 2：跨块短语 → 匹配不到，如实报告 --------------------------------
    def test_cross_chunk_phrase():
        with tempfile.TemporaryDirectory() as tmp:
            ds = _fixture_dataset(os.path.join(tmp, "ds.jsonl"), [
                _base_case("para-a-2", _md5("a"), "a.pdf", ["contrastive learning. The model"]),
            ])
            # 短语被 chunk 0/1 边界切开："contrastive learning." 在 chunk0，"The model" 在 chunk1
            http_fn, _ = _fixture_es_http({_md5("a"): _FIX_CHUNKS_A})
            es = EsConfig(url="http://es-fixture:9200", index="knowledge_base")
            _, _, rows, unmatched, coverage = run_match(ds, es, http_fn)
            assert not rows[0]["matched"] and rows[0]["chunkIds"] == []
            assert rows[0]["files"][0]["chunkId"] is None
            assert rows[0]["files"][0]["perPhrase"][0][1] is None
            assert unmatched == ["para-a-2"] and coverage == 0.0
    register("fixture 跨块短语 → 匹配不到写空并报告（不猜 id）", test_cross_chunk_phrase)

    # ---- 场景 3：大小写/全半角差异 → NFKC 归一后命中 ---------------------------
    def test_case_and_width():
        with tempfile.TemporaryDirectory() as tmp:
            # 短语不带句尾标点：全角句号「。」不经 NFKC 变半角点，短语与其后的正文匹配以标点后置为准
            ds = _fixture_dataset(os.path.join(tmp, "ds.jsonl"), [
                _base_case("fact-b-1", _md5("b"), "b.pdf", ["the experiment uses medical images"]),
            ])
            http_fn, _ = _fixture_es_http({_md5("b"): _FIX_CHUNKS_B})
            es = EsConfig(url="http://es-fixture:9200", index="knowledge_base")
            _, _, rows, unmatched, coverage = run_match(ds, es, http_fn)
            assert rows[0]["matched"] and rows[0]["chunkIds"] == ["0"], rows
            assert unmatched == [] and coverage == 1.0
    register("fixture 大小写/全半角差异 → NFKC+小写后命中", test_case_and_width)

    # ---- 场景 4：缺失文件（ES 无该 md5 分块）→ 未匹配，不猜 id ------------------
    def test_missing_file():
        with tempfile.TemporaryDirectory() as tmp:
            ds = _fixture_dataset(os.path.join(tmp, "ds.jsonl"), [
                _base_case("fact-a-1", _md5("a"), "a.pdf", ["contrastive learning"]),
                _base_case("fact-c-1", _md5("c"), "c.pdf", ["anything"]),
            ])
            http_fn, _ = _fixture_es_http({_md5("a"): _FIX_CHUNKS_A})  # c.md5 无分块
            es = EsConfig(url="http://es-fixture:9200", index="knowledge_base")
            _, _, rows, unmatched, coverage = run_match(ds, es, http_fn)
            by_id = {r["id"]: r for r in rows}
            assert by_id["fact-a-1"]["matched"] and by_id["fact-a-1"]["chunkIds"] == ["0"]
            assert not by_id["fact-c-1"]["matched"] and by_id["fact-c-1"]["chunkIds"] == []
            assert by_id["fact-c-1"]["missingFile"] and by_id["fact-c-1"]["files"][0]["chunkCount"] == 0
            assert unmatched == ["fact-c-1"] and coverage == 0.5
    register("fixture 缺失文件（ES 0 分块）→ 标缺分块、写空、不猜 id", test_missing_file)

    # ---- 场景 5：多分块命中 → 取 chunkId 最小者并标 ambiguous -------------------
    def test_multi_chunk_ambiguous():
        chunks = {
            3: "The secret phrase appears here.",
            7: "Later the secret phrase appears again with more context around it.",
            9: "Unrelated tail chunk.",
        }
        with tempfile.TemporaryDirectory() as tmp:
            ds = _fixture_dataset(os.path.join(tmp, "ds.jsonl"), [
                _base_case("fact-a-3", _md5("a"), "a.pdf", ["the secret phrase appears"]),
            ])
            http_fn, _ = _fixture_es_http({_md5("a"): chunks})
            es = EsConfig(url="http://es-fixture:9200", index="knowledge_base")
            _, _, rows, _, _ = run_match(ds, es, http_fn)
            f = rows[0]["files"][0]
            assert rows[0]["chunkIds"] == ["3"], rows
            assert f["ambiguous"] is True and f["chunkId"] == 3
            # 报告里 ambiguous 可见
            table = render_markdown(rows)
            assert "多分块命中" in table and "是" in table
    register("fixture 多分块命中 → 取 chunkId 最小者并标 ambiguous", test_multi_chunk_ambiguous)

    # ---- 场景 5b：同一分块命中多条短语 → 不算 ambiguous -------------------------
    def test_same_chunk_multi_phrases_not_ambiguous():
        # 两条短语都落在同一个 chunk 0：按短语统计 distinct chunk 数，不算 ambiguous
        chunks = {
            0: "The model was trained on 400 million pairs.",
            1: "Unrelated tail chunk.",
        }
        with tempfile.TemporaryDirectory() as tmp:
            ds = _fixture_dataset(os.path.join(tmp, "ds.jsonl"), [
                _base_case("fact-a-4", _md5("a"), "a.pdf",
                           ["the model was trained", "400 million pairs"]),
            ])
            http_fn, _ = _fixture_es_http({_md5("a"): chunks})
            es = EsConfig(url="http://es-fixture:9200", index="knowledge_base")
            _, _, rows, _, _ = run_match(ds, es, http_fn)
            f = rows[0]["files"][0]
            assert f["chunkId"] == 0 and f["ambiguous"] is False, f
            assert f["hitCount"] == 2 and f["phraseTotal"] == 2, f
            assert rows[0]["chunkIds"] == ["0"], rows
    register("fixture 同一分块命中多条短语 → 不算 ambiguous（按短语数 distinct chunk）",
             test_same_chunk_multi_phrases_not_ambiguous)

    # ---- 场景 6：幂等写回（字段顺序/中文不转义/注释保留/写两次一致/[] 处理）------
    def test_write_back_idempotent():
        with tempfile.TemporaryDirectory() as tmp:
            ds = _fixture_dataset(os.path.join(tmp, "ds.jsonl"), [
                _base_case("fact-a-1", _md5("a"), "a.pdf", ["contrastive learning"]),
                _base_case("para-b-2", _md5("b"), "b.pdf", ["不存在的短语"], expectedRefusal=True),
            ])
            http_fn, _ = _fixture_es_http({_md5("a"): _FIX_CHUNKS_A})  # b 无分块 → []
            es = EsConfig(url="http://es-fixture:9200", index="knowledge_base")
            raw, entries, rows, unmatched, _ = run_match(ds, es, http_fn)
            assignments = {r["id"]: r["chunkIds"] for r in rows}
            rewritten = write_back(ds, raw, assignments)
            assert rewritten == 2
            with open(ds, "r", encoding="utf-8") as f:
                first_text = f.read()
            # 注释行保留、中文不转义
            assert "# fixture 数据集（self-test 用）" in first_text
            assert "不存在的短语" in first_text and "\\u" not in first_text
            # 字段顺序：expectedChunkIds 在 expectedFileNames 之后、expectedEvidence 之前
            first_lines = [l for l in first_text.split("\n") if l and not l.startswith("#")]
            keys0 = list(json.loads(first_lines[0]).keys())
            assert keys0.index("expectedChunkIds") == keys0.index("expectedFileNames") + 1
            assert json.loads(first_lines[0])["expectedChunkIds"] == ["0"]
            # 未匹配写 []（不是 [""]，不是缺字段）
            obj1 = json.loads(first_lines[1])
            assert obj1["expectedChunkIds"] == [] and obj1["expectedRefusal"] is True
            # 幂等：以写回后的文本再写一次，逐字节一致
            rewritten2 = write_back(ds, first_text, assignments)
            assert rewritten2 == 2
            with open(ds, "r", encoding="utf-8") as f:
                second_text = f.read()
            assert second_text == first_text
            # annotate_obj 不改原字段顺序
            obj = json.loads(first_lines[0])
            assert list(annotate_obj(obj, ["5"]).keys()) == keys0
    register("fixture 幂等写回（字段顺序/中文不转义/注释保留/写两次逐字节一致/[] 口径）", test_write_back_idempotent)

    # ---- 场景 7：覆盖率门禁（--check 低于阈值退出 1 并列未匹配用例）-------------
    def test_coverage_gate():
        with tempfile.TemporaryDirectory() as tmp:
            ds = _fixture_dataset(os.path.join(tmp, "ds.jsonl"), [
                _base_case("fact-a-1", _md5("a"), "a.pdf", ["contrastive learning"]),
                _base_case("para-b-2", _md5("b"), "b.pdf", ["nothing matches this"]),
                _base_case("para-c-3", _md5("c"), "c.pdf", ["cross chunk phrase here"]),
            ])
            chunks_c = {0: "cross chunk", 1: "phrase here"}
            http_fn, _ = _fixture_es_http({_md5("a"): _FIX_CHUNKS_A, _md5("c"): chunks_c})
            es = EsConfig(url="http://es-fixture:9200", index="knowledge_base")
            _, _, rows, unmatched, coverage = run_match(ds, es, http_fn)
            assert coverage == 1.0 / 3.0
            assert unmatched == ["para-b-2", "para-c-3"]
            # 门禁判定
            assert validate_min_coverage(0.9) == 0.9
            try:
                validate_min_coverage(1.5)
                raise AssertionError("越界阈值应报错")
            except EvalBackfillError:
                pass
            table = render_markdown(rows)
            assert "para-b-2" in table and "para-c-3" in table
            summary, missing = build_summary(rows, unmatched, coverage)
            assert summary["matchedCases"] == 1 and summary["unmatchedCases"] == 2
            assert missing == ["para-b-2"], missing  # b 的 md5 无分块，同时是缺文件用例
    register("fixture 覆盖率 1/3 → 低于 0.9 应退出 1 并列未匹配用例", test_coverage_gate)

    # ---- 场景 8：ES 失败分类与分页（401/404/翻页装下/total 校验）-----------------
    def test_es_failures_and_paging():
        es = EsConfig(url="http://es-fixture:9200", index="knowledge_base")
        # 401：报环境变量名，不猜默认密码
        http_fn, _ = _fixture_es_http({}, status=401)
        try:
            fetch_chunks_for_md5(es, _md5("a"), http_fn)
            raise AssertionError("401 应报错")
        except EvalBackfillError as e:
            assert "ELASTICSEARCH_USERNAME" in str(e) and "ELASTICSEARCH_PASSWORD" in str(e)
        # 404：报索引名提示
        http_fn, _ = _fixture_es_http({}, status=404)
        try:
            fetch_chunks_for_md5(es, _md5("a"), http_fn)
            raise AssertionError("404 应报错")
        except EvalBackfillError as e:
            assert "EVAL_ES_INDEX" in str(e)
        # 传输层失败：报连接失败而非未匹配
        http_fn, _ = _fixture_es_http({}, raise_error=TransportError("连接失败: refused"))
        try:
            fetch_chunks_for_md5(es, _md5("a"), http_fn)
            raise AssertionError("传输失败应报错")
        except TransportError as e:
            assert "连接失败" in str(e)
        # 翻页：size=1000 一页装不下时按 from 翻页拉全
        def paged_body():
            all_hits = [{"_source": {"fileMd5": _md5("a"), "chunkId": cid, "textContent": f"chunk {cid} content"}}
                        for cid in range(1200)]

            def _body(_md5, request):
                frm = request.get("from", 0)
                size = request.get("size", 10)
                page = all_hits[frm:frm + size]
                return json.dumps({"hits": {"total": {"value": 1200, "relation": "eq"}, "hits": page}},
                                  ensure_ascii=False).encode("utf-8")
            return _body
        http_fn, calls = _fixture_es_http({}, body_override=paged_body())
        chunks = fetch_chunks_for_md5(es, _md5("a"), http_fn)
        assert len(chunks) == 1200 and len(calls) == 2  # 1000 + 200
        assert json.loads(calls[1]["data"].decode())["from"] == 1000
        assert [c[0] for c in chunks[:3]] == [0, 1, 2]
        # total < 实收：翻页异常要报错
        def short_total(_md5, request):
            hits = [{"_source": {"fileMd5": _md5, "chunkId": 0, "textContent": "x"}},
                    {"_source": {"fileMd5": _md5, "chunkId": 1, "textContent": "y"}}]
            return json.dumps({"hits": {"total": {"value": 5, "relation": "eq"}, "hits": hits}}).encode()
        http_fn, _ = _fixture_es_http({}, body_override=short_total)
        try:
            fetch_chunks_for_md5(es, _md5("a"), http_fn)
            raise AssertionError("total 校验应报错")
        except EvalBackfillError as e:
            assert "翻页异常" in str(e)
        # chunkId 缺失/非整数：不猜 id
        def bad_chunk(_md5, request):
            hits = [{"_source": {"fileMd5": _md5, "textContent": "x"}}]
            return json.dumps({"hits": {"total": {"value": 1, "relation": "eq"}, "hits": hits}}).encode()
        http_fn, _ = _fixture_es_http({}, body_override=bad_chunk)
        try:
            fetch_chunks_for_md5(es, _md5("a"), http_fn)
            raise AssertionError("chunkId 缺失应报错")
        except EvalBackfillError as e:
            assert "不猜 id" in str(e)
    register("ES 失败分类（401/404/连接失败）与分页/total 校验/缺 chunkId 不猜", test_es_failures_and_paging)

    # ---- 场景 9：数据集校验与配置兜底 ----------------------------------------
    def test_dataset_validation_and_config():
        with tempfile.TemporaryDirectory() as tmp:
            # 不存在
            try:
                load_dataset(os.path.join(tmp, "nope.jsonl"))
                raise AssertionError("数据集不存在应报错")
            except EvalBackfillError as e:
                assert "不存在" in str(e)
            # 坏 JSON 行
            bad = os.path.join(tmp, "bad.jsonl")
            with open(bad, "w", encoding="utf-8") as f:
                f.write('{"id": "x"}\nnot-json\n')
            _, entries, problems = load_dataset(bad)
            assert not entries and len(problems) == 2
            # md5 与文件名长度不一致
            bad2 = os.path.join(tmp, "bad2.jsonl")
            with open(bad2, "w", encoding="utf-8") as f:
                f.write(json.dumps(_base_case("fact-a-1", _md5("a"), "a.pdf", ["p"])) + "\n")
            with open(bad2, "a", encoding="utf-8") as f:
                row = _base_case("fact-a-2", _md5("a"), "a.pdf", ["p"])
                row["expectedFileMd5s"] = []
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
            _, _, problems2 = load_dataset(bad2)
            assert any("长度不一致" in p for p in problems2)
            # ES 配置：缺省/覆盖/.env 兜底/非法 scheme
            cfg = build_es_config({}, "")
            assert cfg.url == "http://localhost:9200" and cfg.index == "knowledge_base"
            cfg2 = build_es_config({"ELASTICSEARCH_SCHEME": "https", "ELASTICSEARCH_HOST": "es.internal",
                                    "ELASTICSEARCH_PORT": "9201", "EVAL_ES_INDEX": "kb_x",
                                    "ELASTICSEARCH_USERNAME": "svc", "ELASTICSEARCH_PASSWORD": "pw"}, "")
            assert cfg2.url == "https://es.internal:9201" and cfg2.index == "kb_x"
            assert base64.b64decode(cfg2.auth_header.split()[1]).decode() == "svc:pw"
            env_file = os.path.join(tmp, ".env")
            with open(env_file, "w", encoding="utf-8") as f:
                f.write('EVAL_ES_INDEX="kb_env"\n')
            assert build_es_config({}, env_file).index == "kb_env"
            try:
                build_es_config({"ELASTICSEARCH_SCHEME": "ftp"}, "")
                raise AssertionError("非法 scheme 应报错")
            except EvalBackfillError:
                pass
    register("数据集校验（不存在/坏行/md5 失配）与 ES 配置兜底", test_dataset_validation_and_config)

    passed, failed = 0, []
    print("==> eval_backfill_chunk_ids --self-test（离线，fixture 数据集 + fake HTTP，不联网）")
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

def build_parser():
    parser = argparse.ArgumentParser(
        prog="eval_backfill_chunk_ids.py",
        description="按证据短语回填 expectedChunkIds（task-0005）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
模式：
  （默认）          打印 markdown 匹配报告（只读）
  --check          覆盖率门禁：覆盖率 < --min-coverage 退出 1 并列未匹配用例；无法判定退出 2
  --write          幂等写回数据集（保持字段顺序与中文不转义；注释行原样保留）；
                   覆盖率 0 时拒绝写回退出 2（避免全量空数组退化写回）
  --dry-run        打印前 10 条匹配结果（只读）
  --self-test      离线自检（fixture + fake HTTP，不联网）

环境变量（详见 docs/eval/runbook.md）：
  ELASTICSEARCH_SCHEME/HOST/PORT/USERNAME/PASSWORD   ES 直连（默认 http://localhost:9200, elastic/空）
  EVAL_ES_INDEX            ES 索引/别名（默认 knowledge_base）
  EVAL_ENV_FILE            .env 路径（默认仓库根 .env，ELASTICSEARCH_* 兜底来源）

退出码：0 成功/覆盖率达标；1 --check 覆盖率低于阈值；2 无法判定（连接失败/查询失败/数据集问题/--write 覆盖率 0 拒写）
""")
    parser.add_argument("--check", action="store_true",
                        help="覆盖率门禁：低于 --min-coverage 退出 1")
    parser.add_argument("--write", action="store_true",
                        help="幂等写回数据集 expectedChunkIds")
    parser.add_argument("--dry-run", action="store_true", dest="dry_run",
                        help="打印前 10 条匹配结果（只读）")
    parser.add_argument("--self-test", action="store_true", dest="self_test",
                        help="离线自检（不联网）")
    parser.add_argument("--min-coverage", type=float, default=DEFAULT_MIN_COVERAGE,
                        help="覆盖率下限（默认 0.9）")
    parser.add_argument("--dataset", default=DEFAULT_DATASET,
                        help="评测数据集路径（默认 src/main/resources/eval/eval-v2.jsonl）")
    parser.add_argument("--env-file", default=None,
                        help=".env 路径（默认 EVAL_ENV_FILE 或仓库根 .env）")
    parser.add_argument("--es-timeout", type=float, default=None,
                        help="单次 ES 请求超时秒数（默认 15）")
    return parser


def main(argv=None):
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
    modes = set()
    if args.check:
        modes.add("check")
    if args.write:
        modes.add("write")
    if args.dry_run:
        modes.add("dry_run")
    try:
        return cmd_run(args, modes)
    except TransportError as e:
        print(f"!! 无法判定（exit 2）：{e}", file=sys.stderr)
        return 2
    except EvalBackfillError as e:
        print(f"!! {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
