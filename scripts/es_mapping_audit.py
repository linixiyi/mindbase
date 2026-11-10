#!/usr/bin/env python3
"""ES mapping 必需字段审计：显式声明缺一不可，检索权限字段 public 缺失即审计失败。

spec 2026-11-10_fix-eval-and-runtime-gaps task-0021（C21）：mapping 原本只声明 isPublic
（knowledge_base.json:42），而 HybridSearchService 权限过滤查的是 public
（HybridSearchService.java:325 term field("public").value(true)）——EsDocument 用 Lombok @Data
声明 boolean isPublic（EsDocument.java:24），Jackson 按 getter isPublic() 把 JSON 键序列化成
"public"，因此真实落盘字段长期靠动态 mapping 兜底；换集群/重建索引时权限过滤可能失效。
本脚本内置必需字段清单，对真机 GET {url}/{index}/_mapping 逐项核对。

用法（在仓库根执行）：
    python3 -B scripts/es_mapping_audit.py --self-test                       # 内置 fixture 自检，不发网络请求
    python3 -B scripts/es_mapping_audit.py --url http://localhost:9200       # 真查默认索引 knowledge_base
    python3 -B scripts/es_mapping_audit.py --url http://localhost:9200 --index knowledge_base_xxx
    python3 -B scripts/es_mapping_audit.py                                   # 无 --url 时等价 --self-test

必需字段清单（grep 证据，非猜测；实现前逐一在源码确认）：

| 字段          | 期望类型     | 依据 |
|---------------|--------------|------|
| textContent   | text         | HybridSearchService.java:54 FIELD_TEXT；:292 match、:290 combined_fields、:378 toSearchResult 读取 |
| chunkContext  | text         | HybridSearchService.java:55 FIELD_CHUNK_CONTEXT；:290 combined_fields、:297 rerankText 读取 |
| vector        | dense_vector | HybridSearchService.java:306 knn field("vector")；ConflictCandidateFinder.java:160 同 |
| userId        | keyword      | HybridSearchService.java:324 权限过滤 term；:380 toSearchResult 读取 |
| public        | boolean      | HybridSearchService.java:325 权限过滤 term field("public")——本卡（C21）要求 mapping 显式声明 |
| orgTag        | keyword      | HybridSearchService.java:327 权限过滤 terms；:381 toSearchResult 读取 |
| fileMd5       | keyword      | HybridSearchService.java:376/463；ElasticsearchService.java:90/110/128 term 查询 |
| chunkId       | integer      | HybridSearchService.java:377 toSearchResult 读取；EsDocument.java:15 |
| pageNumber    | integer      | HybridSearchService.java:384 toSearchResult 读取；EsDocument.java:18 |
| anchorText    | text         | HybridSearchService.java:385 toSearchResult 读取；EsDocument.java:19 |
| modelVersion  | keyword      | ElasticsearchService.java:128-131 countByFileMd5AndModelVersion term 查询 |
| isPublic      | boolean      | 旧数据兼容（task-0021 卡面明确保留），当前索引链路不再写入该键 |

不要求 `id`：EsDocument.java:13 虽有 id 字段，但它是文档元数据 _id（检索路径用 Hit::id，
HybridSearchService.java:186），toSearchResult 不从 _source 读取，mapping 无需声明。

退出码：
    0  --self-test 通过；或真查时全部必需字段在位
    1  真查发现缺字段或类型不符
    2  审计未执行：ES 未连接、索引不存在、响应无法解析（网络失败绝不计为字段缺失）；
       或 --self-test 自身断言失败

只依赖标准库，单文件，不引第三方包。
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DEFAULT_INDEX = "knowledge_base"  # KnowledgeIndexNames.ALIAS（KnowledgeIndexNames.java:12）
HTTP_TIMEOUT_SECONDS = 5

# 必需字段清单：字段名 → 期望 ES 类型。依据见模块 docstring 的 grep 证据表。
REQUIRED_FIELDS: dict[str, str] = {
    "textContent": "text",
    "chunkContext": "text",
    "vector": "dense_vector",
    "userId": "keyword",
    "public": "boolean",
    "orgTag": "keyword",
    "fileMd5": "keyword",
    "chunkId": "integer",
    "pageNumber": "integer",
    "anchorText": "text",
    "modelVersion": "keyword",
    "isPublic": "boolean",
}

LEVEL_ERROR = "error"


def audit_properties(properties: dict) -> list[dict]:
    """对 _mapping 的 properties 逐项核对必需字段，返回 findings（有序，与 REQUIRED_FIELDS 同序）。

    finding 形如 {"level": "error", "field": <字段名>, "detail": <人读原因>}。
    只看 properties 下的显式声明；mapping _meta、动态模板等键不参与判定。
    """
    findings: list[dict] = []
    properties = properties or {}
    for field, expected_type in REQUIRED_FIELDS.items():
        actual = properties.get(field)
        if not isinstance(actual, dict):
            findings.append({
                "level": LEVEL_ERROR,
                "field": field,
                "detail": f"缺失必需字段 {field}（期望类型 {expected_type}）",
            })
            continue
        actual_type = actual.get("type")
        if actual_type != expected_type:
            findings.append({
                "level": LEVEL_ERROR,
                "field": field,
                "detail": f"类型不符: {field} 期望 {expected_type}，实际 {actual_type!r}",
            })
    return findings


def extract_entries(payload: dict) -> list[tuple[str, dict]]:
    """解析 GET {url}/{index}/_mapping 响应，返回 [(索引名, properties), ...]。

    响应形如 {"<具体索引名>": {"mappings": {"properties": {...}}}}；按别名查询时键是
    具体索引名而非别名，所以不做键名断言。返回空列表表示响应里没有任何索引 mapping。
    """
    entries: list[tuple[str, dict]] = []
    for index_name, body in (payload or {}).items():
        if not isinstance(body, dict):
            continue
        properties = body.get("mappings", {}).get("properties", {})
        entries.append((index_name, properties if isinstance(properties, dict) else {}))
    return entries


def findings_to_markdown(all_findings: list[tuple[str, list[dict]]]) -> str:
    """把审计结果渲染成 markdown 表（与仓库其他审计脚本一致的输出风格）。

    all_findings: [(索引名, findings 列表), ...]
    """
    lines = ["| 索引 | 字段 | 级别 | 说明 |", "|---|---|---|---|"]
    total = 0
    for index_name, findings in all_findings:
        for f in findings:
            total += 1
            lines.append(f"| {index_name} | {f['field']} | {f['level']} | {f['detail']} |")
    if total == 0:
        lines.append("| （全部） | - | - | 必需字段全部在位 |")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# --self-test：内置好/缺 public 两份 fixture，断言 findings；不发网络请求
# ---------------------------------------------------------------------------

# GOOD fixture：与修复后 knowledge_base.json 的 properties 逐字一致（含 index:false、dims 等额外键，
# 同时验证审计只看 type 不受额外键影响）。
FIXTURE_GOOD = {
    "fileMd5": {"type": "keyword"},
    "chunkId": {"type": "integer"},
    "pageNumber": {"type": "integer"},
    "anchorText": {"type": "text", "index": False},
    "textContent": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
    "chunkContext": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
    "vector": {"type": "dense_vector", "dims": 2048, "index": True, "similarity": "cosine"},
    "modelVersion": {"type": "keyword"},
    "userId": {"type": "keyword"},
    "orgTag": {"type": "keyword"},
    "public": {"type": "boolean"},
    "isPublic": {"type": "boolean"},
}


def run_self_test() -> int:
    failures: list[str] = []

    def check(condition: bool, message: str) -> None:
        if not condition:
            failures.append(message)

    # fixture 1（好）：无任何 error finding
    good_findings = audit_properties(FIXTURE_GOOD)
    check(good_findings == [], f"好 fixture 不应有 findings，实际: {good_findings}")

    # fixture 2（缺 public）：恰好一条 error，指向 public
    bad_properties = {k: v for k, v in FIXTURE_GOOD.items() if k != "public"}
    bad_findings = audit_properties(bad_properties)
    check(len(bad_findings) == 1, f"缺 public fixture 应恰好 1 条 finding，实际 {len(bad_findings)} 条: {bad_findings}")
    if bad_findings:
        f = bad_findings[0]
        check(f["level"] == LEVEL_ERROR, f"缺 public finding 级别应为 error，实际: {f['level']}")
        check(f["field"] == "public", f"缺 public finding 应指向 public，实际: {f['field']}")
        check("缺失" in f["detail"] and "public" in f["detail"], f"finding 细节应说明缺失与字段名: {f['detail']}")
        check("boolean" in f["detail"], f"finding 细节应说明期望类型 boolean: {f['detail']}")
    # 兼容字段 isPublic 在两份 fixture 里都必须保留（卡面要求：保留 isPublic 兼容旧数据）
    check("isPublic" in FIXTURE_GOOD, "好 fixture 应保留 isPublic（旧数据兼容）")
    check("isPublic" in bad_properties, "缺 public fixture 也应保留 isPublic（缺的只是 public）")

    # 类型不符分支覆盖：public 存在但类型不是 boolean 也必须报 error（非任务书两 fixture 的补充断言）
    wrong_type_properties = copy.deepcopy(FIXTURE_GOOD)
    wrong_type_properties["public"] = {"type": "keyword"}
    type_findings = audit_properties(wrong_type_properties)
    check(len(type_findings) == 1 and type_findings[0]["field"] == "public"
          and "类型不符" in type_findings[0]["detail"],
          f"public 类型漂移应报一条类型不符 finding，实际: {type_findings}")

    # markdown 渲染分支：无 finding 与有 finding 两种（曾因解包错误在这里崩，故纳入自检）
    clean_md = findings_to_markdown([("kb_ok", [])])
    check("必需字段全部在位" in clean_md, f"干净审计的 markdown 应含'必需字段全部在位'，实际:\n{clean_md}")
    dirty_md = findings_to_markdown([("kb_bad", bad_findings)])
    check("| kb_bad | public | error |" in dirty_md,
          f"缺 public 的 markdown 行应含索引名/字段/级别，实际:\n{dirty_md}")

    if failures:
        for message in failures:
            print(f"FAIL: {message}")
        print(f"--self-test 失败: {len(failures)} 项断言未过")
        return 2
    print("--self-test 通过: 好 fixture 0 findings；缺 public fixture 1 条 error 指向 public；类型漂移分支覆盖")
    return 0


# ---------------------------------------------------------------------------
# --url：真查 GET {url}/{index}/_mapping
# ---------------------------------------------------------------------------


def fetch_mapping(url: str, index: str) -> dict:
    """GET {url}/{index}/_mapping，返回 JSON dict；不可达抛 ConnectionError。"""
    base = url.rstrip("/")
    quoted_index = urllib.parse.quote(index, safe="")
    request = urllib.request.Request(f"{base}/{quoted_index}/_mapping",
                                     method="GET",
                                     headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            raw = response.read()
    except urllib.error.HTTPError as exc:
        raise ConnectionError(f"ES 返回 HTTP {exc.code}（{exc.reason}），索引/权限问题，审计未执行") from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise ConnectionError(f"未连接: 无法访问 {base}（{exc}）") from exc
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ConnectionError(f"ES 响应不是合法 JSON，审计未执行（{exc}）") from exc
    if not isinstance(payload, dict):
        raise ConnectionError(f"ES 响应结构异常（期望 JSON 对象，得到 {type(payload).__name__}），审计未执行")
    return payload


def run_url_audit(url: str, index: str) -> int:
    try:
        payload = fetch_mapping(url, index)
    except ConnectionError as exc:
        # 网络失败 / 索引不存在都不是字段缺失，绝不产生缺字段 findings
        print(f"ES 未连接，审计未执行: {exc}")
        print(f"提示: 网络失败不计为字段缺失。请确认 ES 已启动且索引 {index} 存在后重试。")
        return 2

    entries = extract_entries(payload)
    if not entries:
        print(f"ES 已连接，但 {url} 对 {index} 返回的 _mapping 里没有任何索引，审计未执行。")
        return 2

    all_findings = [(name, audit_properties(props)) for name, props in entries]
    print(findings_to_markdown(all_findings))
    errors = sum(len(findings) for _, findings in all_findings)
    if errors:
        print(f"审计结果: {len(entries)} 个索引，{errors} 条 error（必需字段清单 {len(REQUIRED_FIELDS)} 项）")
        return 1
    print(f"审计结果: {len(entries)} 个索引，必需字段 {len(REQUIRED_FIELDS)} 项全部在位")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="ES mapping 必需字段审计（task-0021 / C21）；无 --url 时只跑 self-test")
    parser.add_argument("--self-test", action="store_true",
                        help="用内置好/缺 public 两份 fixture 断言 findings，不发网络请求")
    parser.add_argument("--url", default=None, metavar="URL",
                        help="ES 基地址（如 http://localhost:9200），真查 GET {url}/{index}/_mapping")
    parser.add_argument("--index", default=DEFAULT_INDEX, metavar="INDEX",
                        help=f"要审计的索引或别名（默认 {DEFAULT_INDEX}，即 KnowledgeIndexNames.ALIAS）")
    args = parser.parse_args(argv)

    if args.url and not args.self_test:
        return run_url_audit(args.url, args.index)
    return run_self_test()


if __name__ == "__main__":
    sys.exit(main())
