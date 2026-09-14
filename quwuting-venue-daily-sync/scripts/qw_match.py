#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""舞讯 × 平台门店 比对引擎（Step 2–3 的两段式脚本，Python3 标准库，零依赖）。

用法：
  # 1) 先把舞讯整理成 mentions JSON（结构见下）
  # 2) 跑比对（自带 export 拉取 + 两段式落盘）
  #    token 复用 qw_api.py login 写的缓存，无需手工 export ADMIN_TOKEN
  python3 scripts/qw_match.py \
      --mentions /tmp/qw_mentions_YYYYMMDD.json --out /tmp/qw_match_YYYYMMDD.json \
      --base-url http://localhost:8080

mentions JSON（Step 1 的产出，人工/LLM 提取，本脚本不解析原文）：
{
  "reportDate": "2026-09-14",
  "sources": ["xianbao360"],                 # ⚠️ 同一正文被转述 = 同一来源，不要写两个
  "cityHeaderMap": {"江阴": "无锡市", ...},   # 县级 header → 母城（可选，不在映射里则按「X市」猜）
  "noListHeaders": ["扬中", "泰州"],          # 只出现城市名、未列门店的 header（可选，仅用于汇报）
  "mentions": [{"city": "成都", "name": "天涯", "note": ""}]
}

判定优先级（见 reference/matching-playbook.md §3）：
  平台别名域 → 数据源字典 → 规则层（全等/去后缀/包含）→ UNMATCHED 兜底三道
  （首二字子串+长度差≤6 / 形近字同长末字同唯一 / keyword 交叉验证由 --cross-check 触发）

脚本只做「比对 + 落盘 + 摘要」，**不做**任何写库；M/S 判定与五表分级由 Agent 侧据此输出进行。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from collections import Counter

DEFAULT_DICT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "..", "reference", "xianbao360-venue-dict.json")
TOKEN_CACHE = os.environ.get("QW_TOKEN_CACHE", "/tmp/qw_token.json")   # 与 qw_api.py 同一份缓存


def resolve_token(cli_token: str, base_url: str) -> str:
    """token 顺序：--token > ADMIN_TOKEN > 缓存文件（同 base_url 才复用，防跨环境串号）。"""
    if cli_token:
        return cli_token
    if os.environ.get("ADMIN_TOKEN"):
        return os.environ["ADMIN_TOKEN"]
    try:
        with open(TOKEN_CACHE, encoding="utf-8") as f:
            d = json.load(f)
    except Exception:
        return ""
    if d.get("baseUrl") and d["baseUrl"] != base_url.rstrip("/"):
        return ""
    return d.get("token") or ""

# 通用后缀（顺序敏感：长的在前，循环剥到不动为止；「交谊舞」须单独成后缀）
SUFFIX = ["量贩ktv", "演艺大舞厅", "音乐舞厅", "娱乐厅", "交谊舞厅", "交谊舞", "大舞厅", "音乐茶楼",
          "音乐吧", "歌舞城", "大众舞厅", "歌舞厅", "舞厅", "酒吧", "俱乐部", "club", "ktv",
          "音乐酒馆", "酒馆"]
CONTAINED_MIN_RATIO = 0.34   # 包含式最低包含度
PREFIX_LEN_TOL = 6           # 首二字子串兜底的名称长度差上限


def norm(s: str | None) -> str:
    """归一化：去空白/小写/全角括号与全角字母数字转半角。"""
    if not s:
        return ""
    s = s.strip().lower().replace("\u3000", "").replace(" ", "")
    for a, b in (("（", "("), ("）", ")"), ("【", "("), ("】", ")"),
                 ("［", "("), ("］", ")"), ("[", "("), ("]", ")")):
        s = s.replace(a, b)
    return "".join(chr(ord(c) - 0xFEE0) if 0xFF01 <= ord(c) <= 0xFF5E else c for c in s)


def strip_suffix(s: str) -> str:
    t = norm(s)
    changed = True
    while changed:
        changed = False
        for suf in SUFFIX:
            if t.endswith(suf) and len(t) > len(suf):
                t = t[:-len(suf)]
                changed = True
    return t


def keys_of(name: str) -> set[str]:
    """店名派生的全部匹配 key：全名 + 去后缀 + 括号内别名。"""
    out = set()
    n = norm(name)
    if n:
        out.add(n)
    s = strip_suffix(name)
    if s:
        out.add(s)
    for m in re.finditer(r"\(([^()]+)\)", n):
        inner = m.group(1)
        if inner:
            out.add(inner)
            out.add(strip_suffix(inner))
    return out


def city_key(c: str) -> str:
    return c[:-1] if c.endswith("市") else c


def _get(base_url: str, path: str, token: str | None = None) -> dict:
    req = urllib.request.Request(base_url.rstrip("/") + path,
                                 headers={"User-Agent": "quwuting-venue-daily-sync/1.0",
                                          **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=30) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    if payload.get("code") != 0:
        sys.exit(f"[error] {path} code={payload.get('code')}: {payload.get('message')}")
    return payload["data"]


def fetch_venues(base_url: str, token: str, size: int = 500) -> list[dict]:
    """全量翻页（export 自带 id ASC 稳定排序；见 matching-playbook §2）。"""
    out, page = [], 0
    while True:
        d = _get(base_url, f"/admin/venue-sync/venues/export?page={page}&size={size}", token)
        out.extend(d["content"])
        if d.get("last") or not d["content"]:
            break
        page += 1
    return out


def match_one(m, cities: set[str], by_city: dict, indexes: dict, header_map: dict) -> dict:
    alias_index, name_index, dict_entry, dict_uncertain, removed = indexes
    raw_city, name = m["city"], m["name"]
    ck = city_key(raw_city)
    mapped = ck + "市" if ck + "市" in cities else header_map.get(raw_city)
    if mapped not in cities:
        mapped = None
    rec = {"src_city": raw_city, "name": name, "note": m.get("note", ""),
           "platform_city": mapped, "venueId": None, "confidence": None, "via": None,
           "mapped_platform_name": None, "status": None, "district": None,
           "guard": (ck, norm(name)) in dict_uncertain}
    if not mapped:
        rec["confidence"] = "NO_CITY"
        return rec
    cands = by_city.get(mapped, [])
    # 县级 header 回挂母城后按 district 收窄
    if raw_city != city_key(mapped):
        sub = [v for v in cands if norm(v.get("district", "")).startswith(norm(raw_city)[:2])]
        if sub:
            cands = sub
    rec["cand_count"] = len(cands)
    nk = keys_of(name)

    hit = None
    for k in sorted(nk, key=len, reverse=True):                      # 1) 平台别名域
        vids = alias_index.get((mapped, k))
        if vids:
            hit = (vids[0], "alias-domain:" + k, "EXACT")
            break
    if not hit:                                                       # 2) 数据源字典
        # 县级 header 的字典条目可能登记在母城名下（江阴·猴子 ↔ 无锡市），两把 key 都试
        for e in (dict_entry.get((ck, norm(name)), [])
                  + dict_entry.get((city_key(mapped), norm(name)), [])):
            if e.get("venue_id"):
                hit = (e["venue_id"], "dict:" + e.get("kind", ""), "ALIAS")
                break
            pm = e.get("platform_name")
            if pm:
                vids = name_index.get((mapped, norm(pm))) or \
                       [v["venueId"] for v in cands if norm(v["name"]) == norm(pm)]
                if vids:
                    hit = (vids[0], "dict-name:" + e.get("kind", ""), "ALIAS")
                    break
    if not hit:                                                       # 3) 规则层：全等 / 去后缀
        for k in sorted(nk, key=len, reverse=True):
            vids = name_index.get((mapped, k))
            if vids:
                hit = (vids[0], "exact:" + k, "EXACT")
                break
    if not hit:                                                       # 4) 包含式
        best = None
        for k in sorted(nk, key=len, reverse=True):
            if len(k) < 2:
                continue
            for v in cands:
                vn = norm(v["name"])
                if k in vn or vn in k:
                    ratio = len(k) / max(len(vn), 1)
                    if ratio >= CONTAINED_MIN_RATIO and (best is None or ratio > best[2]):
                        best = (v["venueId"], f"contained:{k}~{v['name']}", ratio)
        if best:
            hit = (best[0], best[1], "CONTAINED")
    if not hit:                                                       # 5) 兜底：首二字 / 形近字
        n0 = norm(name)
        f1 = [v for v in cands if n0[:2] and n0[:2] in norm(v["name"])
              and abs(len(norm(v["name"])) - len(n0)) <= PREFIX_LEN_TOL]
        f2 = [v for v in cands if len(norm(v["name"])) == len(n0) and norm(v["name"])[-1:] == n0[-1:]
              and norm(v["name"]) != n0]
        if len(f1) == 1:
            hit = (f1[0]["venueId"], "fuzzy-prefix", "CONTAINED")
        elif len(f2) == 1:
            hit = (f2[0]["venueId"], "fuzzy-shape", "CONTAINED")
        else:
            rec["confidence"] = "UNMATCHED"
            rec["fuzzy_hints"] = [v["name"] for v in (f1 + f2)[:5]]
            return rec
    rec.update(venueId=hit[0], via=hit[1], confidence=hit[2])
    return rec


def main() -> int:
    ap = argparse.ArgumentParser(description="舞讯 × 平台门店比对引擎")
    ap.add_argument("--mentions", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--token", default=os.environ.get("ADMIN_TOKEN", ""))
    ap.add_argument("--dict", default=os.path.normpath(DEFAULT_DICT))
    ap.add_argument("--export-cache", help="复用已落盘的 export JSON（数组或含 content 的对象）")
    ap.add_argument("--no-cross-check", action="store_true",
                    help="关闭 UNMATCHED 的 keyword 交叉验证（默认开启，见 matching-playbook §5 兜底第三道）")
    args = ap.parse_args()

    M = json.load(open(args.mentions, encoding="utf-8"))
    D = json.load(open(args.dict, encoding="utf-8"))
    if not args.token:
        args.token = resolve_token("", args.base_url)
    if not args.token and not args.export_cache:
        sys.exit(f"[error] 需要 ADMIN token：先 `qw_api.py login`（会缓存到 {TOKEN_CACHE}），"
                 f"或用 --token / ADMIN_TOKEN / --export-cache")

    venues = (json.load(open(args.export_cache, encoding="utf-8")) if args.export_cache
              else fetch_venues(args.base_url, args.token))
    if isinstance(venues, dict):
        venues = venues["content"]
    cities = {v["city"] for v in venues}
    by_city: dict[str, list] = {}
    for v in venues:
        by_city.setdefault(v["city"], []).append(v)

    alias_index: dict = {}
    name_index: dict = {}
    for v in venues:
        for k in keys_of(v["name"]):
            name_index.setdefault((v["city"], k), []).append(v["venueId"])
        for a in (v.get("aliases") or []):
            for k in keys_of(a):
                alias_index.setdefault((v["city"], k), []).append(v["venueId"])

    dict_entry: dict = {}
    dict_uncertain: dict = {}
    removed: dict = {}
    for e in D.get("entries", []):
        dict_entry.setdefault((city_key(e.get("city", "")), norm(e["news_name"])), []).append(e)
    for e in D.get("uncertain_entries", []):
        dict_uncertain.setdefault((city_key(e.get("city", "")), norm(e["news_name"])), []).append(e)
    for e in D.get("removed_duplicates", []):
        removed.setdefault((city_key(e.get("city", "")), norm(e.get("platform_name", ""))), []).append(e)

    indexes = (alias_index, name_index, dict_entry, dict_uncertain, removed)
    header_map = M.get("cityHeaderMap", {})
    vm = {v["venueId"]: v for v in venues}

    results = []
    for m in M["mentions"]:
        r = match_one(m, cities, by_city, indexes, header_map)
        v = vm.get(r["venueId"]) if r.get("venueId") else None
        if v:
            r.update(mapped_platform_name=v["name"], status=v["status"],
                     district=v.get("district"), platform_city_of_hit=v["city"],
                     aliases=v.get("aliases"))
        results.append(r)

    # 兜底第三道：UNMATCHED 的 keyword 交叉验证（2026-09-14 新增，见 matching-playbook §5）
    # 兜住「短名点名长名店」——长度差/包含度两道都失效。⚠️ keyword 必须 URL 编码。
    if not args.no_cross_check and args.token:
        for r in results:
            if r["confidence"] != "UNMATCHED" or r["guard"] or not r["platform_city"]:
                continue
            q = urllib.parse.urlencode({"keyword": r["name"], "page": 0, "size": 20, "sort": "newest"})
            try:
                d = _get(args.base_url, f"/venues?{q}")
            except SystemExit:
                continue
            items = d if isinstance(d, list) else d.get("content", [])
            hits = [x for x in items if x.get("city") == r["platform_city"]]
            if len(hits) == 1:
                vid = hits[0].get("id") or hits[0].get("venueId")
                r.update(venueId=vid, confidence="CONTAINED", via="keyword-cross-check",
                         mapped_platform_name=hits[0]["name"], status=hits[0].get("status"),
                         district=hits[0].get("district"), platform_city_of_hit=hits[0].get("city"))
                r.pop("fuzzy_hints", None)
            elif hits:
                r["cross_check_candidates"] = [f"{x['name']}#{x.get('id') or x.get('venueId')}"
                                               for x in hits]

    out = {"reportDate": M.get("reportDate"), "sources": M.get("sources", []),
           "noListHeaders": M.get("noListHeaders", []),
           "reversalGate": "全源一致门：|S|>=2 且 M==S 才可自动写库（单个源时全部动作降级人工清单）",
           "venueTotal": len(venues), "results": results}
    json.dump(out, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print(f"平台门店 {len(venues)} 家 / {len(cities)} 城 · mention {len(results)} 条")
    print("置信分布:", dict(Counter(r["confidence"] for r in results)))
    un = [r for r in results if r["confidence"] == "UNMATCHED"]
    if un and (args.no_cross_check or not args.token):
        print("⚠️ UNMATCHED 未做 keyword 交叉验证（--no-cross-check 或无 token）——进表③前必须补做")
    print("UNMATCHED（进表③前必须 keyword 交叉验证）:")
    for r in un:
        print(f"  {r['src_city']}·{r['name']}  守卫={r['guard']}  邻近候选={r.get('fuzzy_hints')}")
    print("非 OPEN 命中（反转候选）:")
    for r in results:
        if r.get("venueId") and r["status"] != "OPEN":
            print(f"  #{r['venueId']} {r['mapped_platform_name']}（{r['platform_city_of_hit']}·{r.get('district')}）"
                  f"{r['status']} ← {r['src_city']}·{r['name']} [{r['confidence']}]")
    print(f"→ 已落盘 {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
