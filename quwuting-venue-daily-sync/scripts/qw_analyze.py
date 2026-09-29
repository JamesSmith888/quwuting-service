#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""舞讯差异分析引擎（Step 3B 关门方向 + Step 4 五表分级的 analyze 段，Python3 标准库，零依赖）。

配套 `qw_match.py`：match 段产出命中 JSON，本脚本据它算出「可执行动作清单 + 提交前自检」。

用法：
  python3 scripts/qw_analyze.py \
      --match /tmp/qw_match_YYYYMMDD.json \
      --export /tmp/qw_export_all.json      # 平台门店全量（数组；qw_match --export-cache 同一份）
      [--out /tmp/qw_diff_YYYYMMDD.json] [--dict reference/xianbao360-venue-dict.json]

产出（落盘 JSON + stdout 摘要）：
  reversal_auto   表① 开门反转候选（M==S 且高置信）
  reversal_manual 表② 低置信（CONTAINED/FUZZY）→ 需 user 放行（提交时带 forceReversal:true）
  new_candidates  表③ 新店候选（UNMATCHED，且未被守卫命中）——**只列不建**（红线 4）
  ref_only        表④ 命中且已 OPEN / 未覆盖城市
  suspend_items   表⑤ 关门候选（白名单差集 + 范围细化，已剔除守卫、人工状态店、**疑似同店**）——提交前请核对自检行
  suspend_suspect_hold 🛡 **疑似同店（被点名但匹配没挂上）⇒ 暂停方向保守剔除**，不进提交集；
                  由 Agent 判是否 `alias-import` 固化（实现与 qw_ms.py 共用）
  suspend_manual_hold 人工状态店（statusSource=MANUAL）**已剔除、不自动提交**，需用户逐条决定
                  （2026-09-27 P1 护栏：人工锁有期限，但「这店是人定的」不会过期 ⇒ 锁过期也不静默关）
  manual_annotated 人工权威标注（V25 人工锁 / 永久豁免）——**仅标注**，供汇报单列，
                  门禁判定唯一实现在服务端（本地不过滤，提交后看返回体 skippedLocked/skippedExempt）

⚠️ 本脚本只算清单，**不写库**。全源一致门由 Agent 侧据此判定：
- `reversal_auto`（EXACT/ALIAS + 平台 CEASED/SUSPENDED）= **表①，永远自动写库 + 自动发公告**
  （2026-09-15 用户拍板，**单源日也不降级**）；低置信 → `reversal_manual`（表②，待放行）。
- `suspend_items`（关门方向）**仍受 |S|>=2 约束**：`len(sources) < 2` 时只能当「待放行清单」，
  **不得直接提交**（「未提及即关门」是语义，不是跳过确认门的授权）。
  ⚠️ 双源日请改用 `qw_ms.py`——本脚本的 sources 是扁平口径，分不清「一源点名」与「全源一致」。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter, defaultdict

# ── 暂停方向数据护栏：与双源版 qw_ms.py **共用同一份实现**（2026-09-29 固化） ──
# 为什么共用而不是各写一份：护栏口径必须与双源日**完全一致**，否则单源/双源两条链路
# 会在同一形态上给出不同结论（本轮实证：单源版有 P1 人工护栏、双源版没有 ⇒ 静默缺口）。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from qw_ms import suspects_of  # noqa: E402

DEFAULT_DICT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                             "..", "reference", "xianbao360-venue-dict.json"))


def _county_key(s: str) -> str:
    """县级行政区归一：去尾「市」或「县」（仁寿县 → 仁寿，江阴市 → 江阴）。"""
    for suf in ("市", "县"):
        if s.endswith(suf):
            return s[:-1]
    return s


def main() -> int:
    ap = argparse.ArgumentParser(description="舞讯差异分析（3B + 五表）")
    ap.add_argument("--match", required=True, help="qw_match.py 的产出 JSON")
    ap.add_argument("--export", required=True, help="平台门店全量 JSON（数组或含 content 的对象）")
    ap.add_argument("--out")
    ap.add_argument("--dict", default=DEFAULT_DICT)
    args = ap.parse_args()

    M = json.load(open(args.match, encoding="utf-8"))
    res = M["results"]
    venues = json.load(open(args.export, encoding="utf-8"))
    if isinstance(venues, dict):
        venues = venues["content"]
    D = json.load(open(args.dict, encoding="utf-8"))

    guard_ids = {e["venue_id"] for k in ("uncertain_entries", "removed_duplicates")
                 for e in D.get(k, []) if e.get("venue_id")}
    guard_news = {(_county_key(e.get("city", "")), e["news_name"]) for e in D.get("uncertain_entries", [])}

    # ── 数据驱动的县级 header → 母城映射（取代手抄 CITY_MAP；见 matching-playbook §5） ──
    county_to_city = {}
    for v in venues:
        d = (v.get("district") or "").strip()
        if d and (d.endswith("市") or d.endswith("县")):
            county_to_city[d] = v["city"]
    plat_cities = {v["city"] for v in venues}

    # ── 覆盖范围（城市硬边界）：只认「确有门店名单」的 header ──
    # 只有城市名、没列门店的 header（「扬中*」「泰州*」）**不产生 mention**，天然落不进 covered；
    # 若 mentions JSON 带了 noListHeaders 就顺便打印一行，便于汇报「这些城市整城未动」。
    covered = {r["platform_city"] for r in res if r.get("platform_city")}
    covered = {c for c in covered if c in plat_cities}
    # 被点名（有名单）的县级 header → district 全名集合
    named = {r["src_city"] for r in res}
    reported_county_districts = {
        d for d in county_to_city
        # ⚠️ 必须排除与地级市同名的县（长沙县 vs 长沙市）：母城被点名 ≠ 该县被点名
        if _county_key(d) in named
        and f"{_county_key(d)}市" not in plat_cities
        and _county_key(d) not in plat_cities}

    def in_scope(v) -> bool:
        """范围细化三条（close-direction-playbook §2）：市辖区随母城；县级市/县独立（仅当被点名）。"""
        d = (v.get("district") or "").strip()
        if not d or d.endswith("区"):
            return True
        return d in reported_county_districts

    mentioned = {r["venueId"] for r in res if r.get("venueId")}
    vm = {v["venueId"]: v for v in venues}

    def manual_flags(v) -> dict:
        """人工权威层级（V25）**仅用于标注**——门禁唯一实现在服务端，这里不参与任何过滤。

        2026-09-24 新增：此前脚本只认「字典守卫」，库侧的人工锁（MANUAL + statusLockedUntil）
        与永久豁免（dailySyncExempt）不出现在清单里 ⇒ 汇报时容易把「服务端将跳过的店」当成
        「该暂停的店」，用户看到 `skippedExempt` 会以为是漏跑（08-17/09-17 都靠人工补记）。
        字段来源 = export 自带，**null 时 key 直接不出现**（Jackson NON_NULL），别当成字段没实现。
        """
        f = {}
        if v.get("statusSource") == "MANUAL":
            f["manualSource"] = True          # 人工最后一次设定（**参与过滤**，见 suspend_manual_hold）
            f["manualLock"] = v.get("statusLockedUntil")
        if v.get("dailySyncExempt"):
            f["exempt"] = True
            if v.get("syncNote"):
                f["exemptNote"] = v["syncNote"]
        return f

    reversal_auto, reversal_manual, ref_only, new_cands = [], [], [], []
    for r in res:
        v = vm.get(r.get("venueId")) if r.get("venueId") else None
        if not v:
            if r["confidence"] == "UNMATCHED":
                g = (r["src_city"], r["name"]) in guard_news
                (ref_only if g else new_cands).append({**r, "guard": g})
            continue
        row = {"venueId": v["venueId"], "name": v["name"], "city": v["city"],
               "district": v.get("district"), "status": v["status"],
               "news": f"{r['src_city']}·{r['name']}", "confidence": r["confidence"],
               **manual_flags(v)}
        if v["status"] in ("CEASED", "SUSPENDED"):
            (reversal_auto if r["confidence"] in ("EXACT", "ALIAS") else reversal_manual).append(row)
        else:
            ref_only.append(row)

    suspend_all = [{**{"venueId": v["venueId"], "name": v["name"], "city": v["city"],
                       "district": v.get("district")}, **manual_flags(v)}
                   for v in venues
                   if v["status"] == "OPEN" and v["city"] in covered and in_scope(v)
                   and v["venueId"] not in mentioned and v["venueId"] not in guard_ids]
    # 人工护栏（2026-09-27 立规，P1）：statusSource=MANUAL 的门店**不进自动提交集**——
    # 人工锁有期限（默认 OPEN 3 天 / 停业 7 天），锁过期后服务端门禁不再拦它，
    # 但「这店的状态是人定的」这一事实并没有过期。若照常提交 = 用第三方舞讯静默推翻人工判断
    # （实证：#1166 77音乐酒吧 09-23 人工置营业，锁 09-22 到期后落进当日暂停候选）。
    # ⇒ 一律摘出进 `suspend_manual_hold`，由用户逐条决定「本次照关（写库后转 SYNC 回归自动）/
    # 本次跳过」。这既不永久豁免（该店仍可被用户确认后回归自动处理），也不静默覆盖。
    suspend = [s for s in suspend_all if not s.get("manualSource")]
    suspend_manual_hold = [s for s in suspend_all if s.get("manualSource")]

    # 🛡 暂停方向数据护栏（2026-09-29 固化）：疑似「被点名但匹配没挂上」⇒ 不做暂停推断
    city_mentions = defaultdict(list)
    for r in res:
        if r.get("platform_city"):
            city_mentions[r["platform_city"]].append(r)
    vm_all = {v["venueId"]: v for v in venues}
    suspect_hold, kept = [], []
    for s in suspend:
        strong, weak = suspects_of(vm_all[s["venueId"]], city_mentions)
        if strong:
            suspect_hold.append({**s, "suspects": strong,
                                 "why": "疑似同店（匹配漏判）⇒ 暂停方向保守剔除"})
        else:
            if weak:
                s["suspect_hints"] = weak
            kept.append(s)
    suspend = kept
    suspend_guard_dropped = [{"venueId": v["venueId"], "name": v["name"], "city": v["city"]}
                             for v in venues
                             if v["status"] == "OPEN" and v["city"] in covered and in_scope(v)
                             and v["venueId"] not in mentioned and v["venueId"] in guard_ids]
    # 人工权威标注（**标注用，不参与过滤**）：本轮清单/反转候选里带库侧锁或永久豁免的门店
    annotated = [(x["venueId"], x["name"], x.get("manualLock"), x.get("exempt"), x.get("exemptNote"))
                 for x in suspend + reversal_auto + reversal_manual
                 if x.get("manualLock") or x.get("exempt")]

    out = {"reportDate": M.get("reportDate"), "sources": M.get("sources", []),
           "coveredCities": sorted(covered), "noListHeaders": M.get("noListHeaders", []),
           "reportedCountyDistricts": sorted(reported_county_districts),
           "reversal_auto": reversal_auto, "reversal_manual": reversal_manual,
           "new_candidates": new_cands, "ref_only_count": len(ref_only),
           "suspend_items": suspend, "suspend_guard_dropped": suspend_guard_dropped,
           "suspend_manual_hold": suspend_manual_hold,
           "suspend_suspect_hold": suspect_hold,
           "manual_annotated": [{"venueId": i, "name": n, "manualLock": l, "exempt": e, "note": t}
                                for i, n, l, e, t in annotated]}
    if args.out:
        json.dump(out, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    # ── 提交前自检（close-direction-playbook §5） ──
    print(f"来源 {len(out['sources'])} 个 {out['sources']}"
          f"{'  ⚠️ 单源：两个方向都只能当待放行清单' if len(out['sources']) < 2 else ''}")
    print(f"覆盖城市 {len(covered)} 个；仅有城市名无名单 "
          f"{out['noListHeaders'] or '（未提供，见 mentions JSON.noListHeaders）'}（视同未覆盖）")
    print(f"县级 header 回挂母城并参与范围细化："
          f"{sorted(f'{d}→{county_to_city[d]}' for d in reported_county_districts)}")
    print(f"\n表① 反转候选（高置信）{len(reversal_auto)} 家 / 表② 低置信待放行 {len(reversal_manual)} 家：")
    for r in reversal_auto + reversal_manual:
        print(f"  #{r['venueId']} {r['name']}（{r['city']}·{r['district']}）{r['status']} "
              f"← {r['news']} [{r['confidence']}]")
    print(f"\n表③ 新店候选（只列不建）{len(new_cands)} 家：")
    for r in new_cands:
        print(f"  {r['src_city']}·{r['name']}  邻近候选={r.get('fuzzy_hints')} "
              f"{r.get('cross_check_candidates') or ''}")
    print(f"\n表⑤ 暂停候选 {len(suspend)} 家 / {len({s['city'] for s in suspend})} 城")
    for c, n in Counter(s["city"] for s in suspend).most_common():
        print(f"  {c}（{n}）")
    if suspend_manual_hold:
        print(f"\n🖐 人工状态待确认（statusSource=MANUAL，**已剔除、不进自动提交集**）"
              f"{len(suspend_manual_hold)} 家：")
        for s in suspend_manual_hold:
            print(f"  #{s['venueId']} {s['name']}（{s['city']}·{s.get('district')}）"
                  f" 锁至 {str(s.get('manualLock'))[:16] if s.get('manualLock') else '（已过期）'}"
                  f" —— 需用户决定：本次照关（写库后转 SYNC 回归自动）/ 本次跳过")
    if suspend_guard_dropped:
        print(f"  守卫豁免剔除 {len(suspend_guard_dropped)} 家："
              f"{[s['name'] + '#' + str(s['venueId']) for s in suspend_guard_dropped]}")
    if suspect_hold:
        print(f"\n🛡 疑似同店 · 暂停方向保守剔除 {len(suspect_hold)} 家"
              f"（**被点名但匹配没挂上** ⇒ 不做暂停推断；由 Agent 判是否加别名）：")
        for x in suspect_hold:
            ev = "；".join(f"「{s['news']}」[{s['src_city']}/{s['conf']}] 相似={s['ratio']}（{s['why']}）"
                           for s in x["suspects"])
            print(f"  #{x['venueId']} {x['name']}（{x['city']}·{x.get('district')}） ← {ev}")
    hinted = [x for x in suspend if x.get("suspect_hints")]
    if hinted:
        print(f"\n🔎 弱相似提示（**仍提交**，需 Agent 逐条判）{len(hinted)} 家：")
        for x in hinted:
            ev = "；".join(f"「{s['news']}」[{s['src_city']}/{s['conf']}] 相似={s['ratio']}"
                           for s in x["suspect_hints"])
            print(f"  #{x['venueId']} {x['name']}（{x['city']}） ← {ev}")
    print(f"\n🔒 人工权威标注（V25，**服务端将按锁/豁免跳过**，不参与本脚本过滤）{len(annotated)} 家：")
    for vid, nm, lock, ex, note in annotated:
        tag = "永久豁免" if ex else f"人工锁至 {str(lock)[:16]}"
        print(f"  #{vid} {nm} —— {tag}{'（' + note + '）' if note else ''}")
    if not annotated:
        print("  （无）")
    print(f"\n自检：暂停规模应在 40–80 家量级；跑到几百家先查城市名归一与范围细化。")
    if args.out:
        print(f"→ 已落盘 {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
