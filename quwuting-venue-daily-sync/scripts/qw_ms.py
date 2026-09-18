#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""双源 M/S 判定层（Step 3B 核心 + Step 4 五表，Python3 标准库，零依赖）。

**为什么需要它**：`qw_analyze.py` 的 M/S 是「扁平 sources」口径——它把 sources 当成
全局常量，只区分「被点名 / 未被点名」，**单源日够用**（|S|=1，M==S ⟺ 被点名）。
**双源日必须逐店算**：`M = 点名该店的源集合`、`S = 覆盖该店所在城市的源集合`，
再按判定链分流：

    M == S + EXACT/ALIAS → 确定开门 → 表①（**不受 |S| 约束**：单源日也自动写库 + 自动发公告）
    M == S + CONTAINED   → 低置信   → 表②
    M == ∅ + |S| >= 2    → 确定关门 → 表⑤
    M == ∅ + |S| < 2     → 单源覆盖 → 表⑤′（待放行）
    0 < |M| < |S|        → 源间冲突 → 表②
    |S| < 2 且 M != S    → 单源覆盖 → 表②

⚠️ **表① 不受「全源一致门」约束（2026-09-15 用户拍板，本文件 2026-09-18 补齐）**：
用户原话「**高置信的永远自动发送公告**」⇒ `M==S + EXACT/ALIAS + 平台 CEASED/SUSPENDED`
永远进表①、单源日也不降级。全源一致门自此只拦**低置信（CONTAINED）**与**关门方向（表⑤）**。
（此前本脚本先判 `|S|<2` 再判置信度，与 SKILL.md Step 4 表① 行不一致；09-17 当日恰好
「命中店平台状态全为 OPEN ⇒ 表① 0」，该漂移未被触发，09-18 才暴露 —— 已修正。）

（`---` 均以 SKILL.md Step 4「分类主键」为准。）

前置：mentions JSON 的每条 mention 必须带 `src` 字段（来源标识）；
      `qw_match.py` 的产出与 mentions **逐条同序**，本脚本据此对齐。

用法：
  python3 scripts/qw_ms.py --mentions /tmp/qw_mentions_YYYYMMDD.json \
      --match /tmp/qw_match_YYYYMMDD.json \
      --export /tmp/qw_export_YYYYMMDD.json \
      [--out /tmp/qw_diff_YYYYMMDD.json] [--dict reference/xianbao360-venue-dict.json]

⚠️ 本脚本只算清单，**不写库**。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter, defaultdict

DEFAULT_DICT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                             "..", "reference", "xianbao360-venue-dict.json"))


def county_key(s: str) -> str:
    """县级行政区归一：去尾「市」或「县」（仁寿县 → 仁寿）。"""
    for suf in ("市", "县"):
        if s.endswith(suf):
            return s[:-1]
    return s


def main() -> int:
    ap = argparse.ArgumentParser(description="双源 M/S 判定 + 五表分级")
    ap.add_argument("--mentions", required=True)
    ap.add_argument("--match", required=True)
    ap.add_argument("--export", required=True)
    ap.add_argument("--out")
    ap.add_argument("--dict", default=DEFAULT_DICT)
    args = ap.parse_args()

    META = json.load(open(args.mentions, encoding="utf-8"))
    RES = json.load(open(args.match, encoding="utf-8"))["results"]
    VEN = json.load(open(args.export, encoding="utf-8"))
    if isinstance(VEN, dict):
        VEN = VEN["content"]
    D = json.load(open(args.dict, encoding="utf-8"))
    mentions = META["mentions"]
    if len(mentions) != len(RES):
        sys.exit(f"[error] mentions({len(mentions)}) 与 match results({len(RES)}) 不同序/不等长，"
                 f"请确认 match 用的就是这份 mentions")
    if any("src" not in m for m in mentions):
        sys.exit("[error] mentions 缺 `src` 字段（双源判定必需）；单源日请改用 qw_analyze.py")

    guard_ids = {e["venue_id"] for k in ("uncertain_entries", "removed_duplicates")
                 for e in D.get(k, []) if e.get("venue_id")}
    guard_news = {(county_key(e.get("city", "")), e.get("news_name"))
                  for e in D.get("uncertain_entries", [])}
    # 字典已定论「不建库」的词（如苏州/嘉兴的「交谊舞」= 营业类型标识、湖州梦田 = LiveHouse）：
    # 每轮都会再次出现在舞讯里，**不能每轮重复列进表③ 让用户再裁决一次**（2026-09-17 修）
    no_build = {(county_key(e.get("city", "")), e.get("news_name"))
                for e in D.get("entries", [])
                if not e.get("venue_id") and e.get("platform_name") == "（不建库）"}

    vm = {v["venueId"]: v for v in VEN}
    plat_cities = {v["city"] for v in VEN}

    # ── 覆盖城市 S（城市硬边界：只认确有名单的 header） ──
    S: dict[str, set] = defaultdict(set)
    for m, r in zip(mentions, RES):
        if r.get("platform_city"):
            S[r["platform_city"]].add(m["src"])
    covered = {c for c in S if c in plat_cities}

    # ── 县级 header 回挂 → 范围细化（close-direction-playbook §2） ──
    county_to_city = {}
    for v in VEN:
        d = (v.get("district") or "").strip()
        if d and (d.endswith("市") or d.endswith("县")):
            county_to_city[d] = v["city"]
    named_headers = {r["src_city"] for r in RES}
    reported_county = {d for d in county_to_city
                       if county_key(d) in named_headers
                       and f"{county_key(d)}市" not in plat_cities
                       and county_key(d) not in plat_cities}

    def in_scope(v) -> bool:
        d = (v.get("district") or "").strip()
        if not d or d.endswith("区"):
            return True
        return d in reported_county

    # ── 逐店 M（点名该店的源集合） ──
    Mv: dict[int, set] = defaultdict(set)
    news: dict[int, list] = defaultdict(list)
    for m, r in zip(mentions, RES):
        if r.get("venueId"):
            Mv[r["venueId"]].add(m["src"])
            news[r["venueId"]].append(f"{m['src']}·{r['src_city']}·{m['name']}[{r['confidence']}]")
    mentioned_ids = set(Mv)

    t1, t2, t3, t4, t5, t5_single, guard_drop = [], [], [], [], [], [], []
    for r in RES:
        v = vm.get(r.get("venueId")) if r.get("venueId") else None
        if not v:
            if r["confidence"] == "UNMATCHED":
                key = (county_key(r["src_city"]), r["name"])
                g = key in guard_news
                nb = key in no_build
                row = {"src_city": r["src_city"], "name": r["name"],
                       "hints": r.get("fuzzy_hints"), "cross": r.get("cross_check_candidates"),
                       "err": r.get("cross_check_error"), "guard": g,
                       "why": ("字典已定论不建库" if nb else "字典守卫条目" if g else "新店候选")}
                (t4 if (g or nb) else t3).append(row)
            continue
        city = v["city"]
        Sc, Mset = S.get(city, set()), Mv.get(v["venueId"], set())
        row = {"venueId": v["venueId"], "name": v["name"], "city": city,
               "district": v.get("district"), "status": v["status"], "conf": r["confidence"],
               "M": sorted(Mset), "S": sorted(Sc), "news": news[v["venueId"]],
               "statusSource": v.get("statusSource"), "lockedUntil": v.get("statusLockedUntil"),
               "exempt": v.get("dailySyncExempt"), "guard": v["venueId"] in guard_ids}
        if v["venueId"] in guard_ids:
            t4.append({**row, "why": "字典守卫条目"})
        elif v["status"] in ("CEASED", "SUSPENDED"):
            if Mset == Sc and r["confidence"] in ("EXACT", "ALIAS"):
                # 🔴 高置信开门 = 表①，**不受来源数约束**（2026-09-15 用户拍板；
                #    「高置信的永远自动发送公告」⇒ 单源日也自动写库 + 自动发公告、不询问）
                t1.append(row)
            elif len(Sc) < 2:
                t2.append({**row, "why": f"单源覆盖城市 |S|={len(Sc)}"})
            else:
                t2.append({**row, "why": ("源间冲突 0<|M|<|S|" if Mset and Mset != Sc else
                                          "低置信(CONTAINED)" if r["confidence"] == "CONTAINED"
                                          else "M/S 不一致")})
        else:
            t4.append({**row, "why": "平台已 " + v["status"]})

    for v in VEN:
        if v["status"] != "OPEN" or v["city"] not in covered or not in_scope(v):
            continue
        if v["venueId"] in mentioned_ids:
            continue
        row = {"venueId": v["venueId"], "name": v["name"], "city": v["city"],
               "district": v.get("district"), "S": sorted(S[v["city"]]),
               "statusSource": v.get("statusSource"), "lockedUntil": v.get("statusLockedUntil"),
               "exempt": v.get("dailySyncExempt")}
        if v["venueId"] in guard_ids:
            guard_drop.append({**row, "why": "字典守卫条目"})
        elif len(S[v["city"]]) >= 2:
            t5.append(row)
        else:
            t5_single.append({**row, "why": f"单源覆盖城市 |S|={len(S[v['city']])}"})

    # 全源一致确认营业中（零动作日的公告素材：仅双源城市）
    confirm = sorted({vid for vid, ms in Mv.items()
                      if vm.get(vid) and vm[vid]["status"] == "OPEN"
                      and ms == S.get(vm[vid]["city"]) and len(S.get(vm[vid]["city"], ())) >= 2})
    # 表②/③ 去重（同一店被两源各点名一次会各出一行）
    for lst in (t2, t3):
        seen, uniq = set(), []
        for x in lst:
            k = x.get("venueId") or (x["src_city"], x["name"])
            if k in seen:
                continue
            seen.add(k)
            uniq.append(x)
        lst[:] = uniq

    out = {"reportDate": META.get("reportDate"), "sources": META.get("sources", []),
           "coveredCities": sorted(covered),
           "singleSourceCities": sorted(c for c in covered if len(S[c]) < 2),
           "noListHeaders": META.get("noListHeaders", []),
           "reportedCountyDistricts": sorted(reported_county),
           "t1_reversal": t1, "t2_manual": t2, "t3_new": t3, "t4_ref_count": len(t4),
           "t5_suspend": t5, "t5_single_source": t5_single, "guard_dropped": guard_drop,
           "confirm_open_ids": confirm}
    if args.out:
        json.dump(out, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print(f"来源 {len(out['sources'])} 个 {out['sources']}；覆盖城市 {len(covered)}"
          f"（双源 {len(covered) - len(out['singleSourceCities'])} / "
          f"单源 {len(out['singleSourceCities'])}）")
    print(f"单源城市: {out['singleSourceCities']}")
    print(f"无名单 header（整城未动）: {out['noListHeaders']}")
    print(f"县级回挂: {sorted(reported_county)}")
    print(f"\n表① 反转（M==S 高置信）{len(t1)} 家")
    for r in t1:
        print(f"  #{r['venueId']} {r['name']}（{r['city']}·{r['district']}）{r['status']} ← {' / '.join(r['news'])}")
    print(f"\n表② 待放行 {len(t2)} 家："
          f"{dict(Counter('单源' if '单源' in r['why'] else '冲突' if '冲突' in r['why'] else '低置信' for r in t2))}")
    for r in t2:
        print(f"  #{r['venueId']} {r['name']}（{r['city']}·{r['district']}）{r['status']} [{r['why']}] ← {' / '.join(r['news'])}")
    print(f"\n表③ 新店候选（只列不建）{len(t3)} 家")
    for r in t3:
        print(f"  {r['src_city']}·{r['name']} 邻近={r.get('hints')} {r.get('cross') or ''} {r.get('err') or ''}")
    print(f"\n表④ 参考 {len(t4)} 条（折叠）")
    print(f"\n表⑤ 关门（|S|>=2）{len(t5)} 家 / {len({x['city'] for x in t5})} 城: "
          f"{dict(Counter(x['city'] for x in t5))}")
    print(f"表⑤′ 关门（|S|<2，需放行）{len(t5_single)} 家 / {len({x['city'] for x in t5_single})} 城: "
          f"{dict(Counter(x['city'] for x in t5_single))}")
    if guard_drop:
        print(f"守卫豁免剔除 {len(guard_drop)} 家: {[x['name'] + '#' + str(x['venueId']) for x in guard_drop]}")
    gated = [r for r in (t1 + t2 + t5 + t5_single)
             if r.get("exempt") or r.get("statusSource") == "MANUAL" or r.get("lockedUntil")]
    if gated:
        print(f"⚠️ 人工锁/豁免标注 {len(gated)} 家（服务端门禁会跳过，仅供汇报）: "
              f"{[(x['name'], x.get('statusSource'), x.get('lockedUntil'), x.get('exempt')) for x in gated]}")
    print(f"\n「全源一致确认营业中」（双源城市内）{len(confirm)} 家 / "
          f"{len({vm[i]['city'] for i in confirm})} 城 —— 零写库日可作公告素材")
    print("自检：暂停规模应在 40–80 家量级；双源日表① 为 0 属正常（别硬凑）。")
    if args.out:
        print(f"→ 已落盘 {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
