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
import difflib
import json
import os
import re
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


# ── 暂停方向数据护栏（2026-09-29 固化） ───────────────────────────────────────
# 为什么必须有：**暂停方向误伤营业店的代价不对称**。而「疑似同店」本质是**匹配漏判**
# （形近字 / 后缀差异 / 分隔符粘连），不是「该店今日未上榜」——把它当关门处理就是纯误伤。
# 2026-09-29 实证 4 家（**全部靠人工跑 difflib 才发现，本节就是把那次弯路固化成最短路径**）：
#   #1243 莎莎音乐酒吧 ←「莎莎音乐茶餐」(0.67) · #1276 金沙歌舞厅 ←「金莎」· #1381 秘粿酒馆 ←「秘稞莎莎」
#   · #686 樱桃酒吧 ←「樱桃·润SS」(点名串含分隔符，首段 = 本店名前缀 = 两家粘连)
# 其中前 3 家随后 `alias-import` 加别名 + 重跑回读**证实确为点名店** ⇒ 自动剔除判据有效。
_SPLIT_RE = re.compile(r"[·•/、+－\-]")      # 点名串里的分隔符（两家店粘连的指纹）
_STRONG_RATIO = 0.8                          # 强相似阈值（自动剔除）
_WEAK_RATIO = 0.2                            # 弱相似阈值（只提示，仍提交）
# 品类通用词：算相似度前先剥掉。**不剥会误剔**——09-29 首版用 raw ratio ≥0.5 时，
# 「夜肆音乐酒吧 vs 思夜音乐」(0.60) /「蓝堡音乐餐吧 vs 乐瘾音乐餐」(0.55) /「国潮酒吧 vs 浦东国潮」(0.50)
# 三家全是**共享「音乐/酒吧」通用词**造成的假阳性（平台里它们各自是两家不同的店）。
# 剥掉通用词后比「核心词」：莎莎音乐酒吧 ↔ 莎莎音乐茶餐 → 都是「莎莎」⇒ 真命中。
_COMMON = ["音乐茶吧", "音乐餐吧", "音乐酒吧", "音乐舞厅", "音乐茶座", "音乐酒馆", "音乐餐厅", "音乐餐",
           "音乐吧", "音乐", "歌舞厅", "歌舞城", "大舞厅", "舞厅", "舞汇", "舞会", "舞吧", "歌舞",
           "酒吧", "酒馆", "茶吧", "茶座", "茶楼", "茶餐", "俱乐部", "会所", "娱乐", "演艺", "清吧",
           "派对", "棋牌", "文化", "中心", "餐厅", "餐吧", "ktv", "club", "pub"]


def _ratio(a: str, b: str) -> float:
    return difflib.SequenceMatcher(None, a, b).ratio()


def _core(s: str) -> str:
    """剥品类通用词后的「核心词」（<2 字视为无核心，不参与同一性判定）。"""
    t = (s or "").lower()
    for w in _COMMON:
        t = t.replace(w, "")
    return t.strip()


def suspects_of(v: dict, city_mentions: dict) -> tuple[list, list]:
    """把「该店被舞讯点名、只是匹配没挂上」的疑似证据捞出来。

    返回 (strong, weak)：strong = 自动从暂停候选剔除；weak = 仅标注提示（仍提交）。
    分层判据（口径：暂停方向**宁可漏关也不误关**）：
      strong: ⓐ核心词相同（剥品类通用词后 ≥2 字且全等）ⓑ几乎同字（ratio ≥0.8）
              ⓒ互相包含且短的一方 ≥3 字 ⓓ已命中写法含分隔符且首段 = 本店名前缀（两家粘连）
      weak  : ratio ≥0.2 或 首字相同
    """
    nm, strong, weak = v["name"], [], []
    cnm = _core(nm)
    for r in city_mentions.get(v["city"], []):
        news = (r.get("name") or "").strip()
        if not news or news == nm:
            continue
        ra = _ratio(nm, news)
        cns = _core(news)
        hit = {"news": news, "src_city": r.get("src_city"), "conf": r.get("confidence"),
               "via": r.get("via"), "ratio": round(ra, 2)}
        if r.get("confidence") in ("UNMATCHED", "CONTAINED"):
            if (len(cnm) >= 2 and cnm == cns) or ra >= _STRONG_RATIO \
                    or (news in nm and len(news) >= 3) or (nm in news and len(nm) >= 3):
                strong.append({**hit, "why": f"核心词同一（「{cnm}」）" if cnm and cnm == cns
                                             else "同城存在未挂上的近似写法"})
                continue
            if ra >= _WEAK_RATIO or (news[:1] and nm[:1] and news[0] == nm[0]):
                weak.append({**hit, "why": "弱相似（首字同 / 相似度中）"})
                continue
        if _SPLIT_RE.search(news):                       # 粘连：点名串含分隔符
            head = _SPLIT_RE.split(news)[0]
            if len(head) >= 2 and nm.startswith(head):
                strong.append({**hit, "why": f"疑似两家粘连（点名串首段「{head}」= 本店名前缀）"})
    return strong, weak


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

    # ── 同城 mention 索引（暂停方向护栏用；含未挂上的写法） ──
    city_mentions: dict[str, list] = defaultdict(list)
    for m, r in zip(mentions, RES):
        if r.get("platform_city"):
            city_mentions[r["platform_city"]].append({**r, "src": m["src"]})

    t1, t2, t3, t4, t5, t5_single, t5_manual_hold, t5_suspect_hold, guard_drop = (
        [], [], [], [], [], [], [], [], [])
    scope_drop = []          # 范围细化排除明细（2026-10-08 增：审计「为什么没有更多暂停」）
    for r in RES:
        v = vm.get(r.get("venueId")) if r.get("venueId") else None
        if not v:
            if r["confidence"] == "UNMATCHED":
                key = (county_key(r["src_city"]), r["name"])
                g = key in guard_news
                nb = key in no_build
                row = {"src_city": r["src_city"], "name": r["name"],
                       "platform_city": r.get("platform_city"),
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
        if v["status"] != "OPEN" or v["city"] not in covered:
            continue
        if v["venueId"] in mentioned_ids:
            continue
        if not in_scope(v):
            # 范围细化排除（县级市/县/镇未报到 ⇒ 不随母城参与推断，close-direction-playbook §2）：
            # 只记录「本该进入候选、仅因 scope 被挡」的明细用于审计输出 —— 收集顺序放在
            # mentioned 检查之后（否则被点名的店会虚高进列表；判定行为与旧版一致，见 2026-10-08）。
            scope_drop.append({"venueId": v["venueId"], "name": v["name"], "city": v["city"],
                               "district": v.get("district"), "S": sorted(S[v["city"]])})
            continue
        if v["venueId"] in mentioned_ids:
            continue
        row = {"venueId": v["venueId"], "name": v["name"], "city": v["city"],
               "district": v.get("district"), "S": sorted(S[v["city"]]),
               "statusSource": v.get("statusSource"), "lockedUntil": v.get("statusLockedUntil"),
               "exempt": v.get("dailySyncExempt")}
        if v["venueId"] in guard_ids:
            guard_drop.append({**row, "why": "字典守卫条目"})
            continue
        elif v.get("statusSource") == "MANUAL":
            # 🖐 P1 护栏（2026-09-29 补齐，与 qw_analyze.py 的 suspend_manual_hold 对齐）：
            # 人工锁有期限（置 OPEN 3 天 / 停业 7 天），锁到期后服务端门禁不再拦它，
            # 但「这店的状态是人定的」这一事实没有过期 —— 照常提交 = 用第三方舞讯
            # 静默推翻人工判断。⇒ 摘出、不进自动提交集，由用户逐条决定。
            # 本文件此前缺此段（09-29 实证：#1166 77音乐酒吧 锁 09-22 已过期、
            # 仍落进 t5_suspend；单源日走 qw_analyze.py 时才被摘出 ⇒ 双源日的静默缺口）。
            t5_manual_hold.append({**row, "why": "人工状态店（statusSource=MANUAL）"})
            continue
        # 🛡 数据护栏（2026-09-29 固化）：疑似「被点名但匹配没挂上」⇒ 不做暂停推断
        strong, weak = suspects_of(v, city_mentions)
        if strong:
            t5_suspect_hold.append({**row, "suspects": strong,
                                    "why": "疑似同店（匹配漏判）⇒ 暂停方向保守剔除"})
            continue
        if weak:
            row["suspect_hints"] = weak          # 仅提示，仍提交（由 Agent 逐条判）
        if len(S[v["city"]]) >= 2:
            t5.append(row)
        else:
            t5_single.append({**row, "why": f"单源覆盖城市 |S|={len(S[v['city']])}"})

    # 全源一致确认营业中（零动作日的公告素材：仅双源城市）
    confirm = sorted({vid for vid, ms in Mv.items()
                      if vm.get(vid) and vm[vid]["status"] == "OPEN"
                      and ms == S.get(vm[vid]["city"]) and len(S.get(vm[vid]["city"], ())) >= 2})
    # 表②/③ 去重（同一店被两源各点名一次会各出一行）
    # ⚠️ 表① 也必须去重（2026-09-29 固化）：双源日同一店在 t1 里**每源各出一行**
    #    （实证 #412 出现两次）⇒ 照抄去提交 = 重复提交同一个 venueId。
    for lst in (t1, t2, t3):
        seen, uniq = set(), []
        for x in lst:
            k = x.get("venueId") or (x["src_city"], x["name"])
            if k in seen:
                continue
            seen.add(k)
            uniq.append(x)
        lst[:] = uniq

    # ── 表③ 侧护栏（2026-10-05 固化）：UNMATCHED ↔ 同城「未点名 OPEN 门店」相似度检测 ──
    # 为什么需要：2026-10-05 实证「芜湖·Ls丽莎」是 #1494 Is丽莎酒馆 的形近错字（I/L），
    # 靠**人肉交叉表③×表⑤**才发现 —— 它同时制造「1 个假新店」+「1 家营业店误进暂停候选」
    # 两道错（同一根因、两个方向）。本文件此前只做「被点名但没挂上」那一侧（suspects_of），
    # **没做 UNMATCHED 这一侧** ⇒ 缺口已补。
    # ⚠️ 只提示、**不自动动作**：判为同店即 `alias-import` 灌「舞讯原写法」+ 重跑回读（应升 EXACT）。
    open_unmentioned: dict[str, list] = defaultdict(list)
    for v in VEN:
        if v["status"] == "OPEN" and v["venueId"] not in mentioned_ids:
            open_unmentioned[v["city"]].append(v)
    t3_suspect_pairs = []
    for row in t3:
        pc, n = row.get("platform_city"), row["name"]
        if not pc:
            continue
        for v in open_unmentioned.get(pc, []):
            cn, cv = _core(n), _core(v["name"])
            rt = _ratio(n.lower(), v["name"].lower())
            if (cn and cn == cv) or rt >= 0.5:
                t3_suspect_pairs.append(
                    {"src_city": row["src_city"], "name": n,
                     "venueId": v["venueId"], "venue_name": v["name"],
                     "district": v.get("district"), "ratio": round(rt, 2),
                     "core_same": bool(cn and cn == cv)})

    out = {"reportDate": META.get("reportDate"), "sources": META.get("sources", []),
           "coveredCities": sorted(covered),
           "singleSourceCities": sorted(c for c in covered if len(S[c]) < 2),
           "noListHeaders": META.get("noListHeaders", []),
           "reportedCountyDistricts": sorted(reported_county),
           "t1_reversal": t1, "t2_manual": t2, "t3_new": t3, "t4_ref_count": len(t4),
           "t5_suspend": t5, "t5_single_source": t5_single,
           "t5_manual_hold": t5_manual_hold, "t5_suspect_hold": t5_suspect_hold,
           "t3_suspect_pairs": t3_suspect_pairs,
           "scope_dropped": scope_drop,
           "guard_dropped": guard_drop,
           "confirm_open_ids": confirm}
    if args.out:
        json.dump(out, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print(f"来源 {len(out['sources'])} 个 {out['sources']}；覆盖城市 {len(covered)}"
          f"（双源 {len(covered) - len(out['singleSourceCities'])} / "
          f"单源 {len(out['singleSourceCities'])}）")
    print(f"单源城市: {out['singleSourceCities']}")
    # ℹ️ 单源提示（2026-10-07 改制）：收到即执行、不等第二更 —— 见 SKILL.md Step 0
    if len(out["sources"]) < 2:
        print("ℹ️ **单源日**（2026-10-07 口径：收到即执行、**不等第二更**）：关门方向（表⑤/表⑤′）照常提交。\n"
              "    沿革：10-03 曾立「等第二更」挂起口径；10-07 用户拍板「一天通常只更新一次、一次完成」⇒ 作废。\n"
              "    第二更仅在用户主动要求时处理（重跑 + 回滚 + 公告 §3 应急）。")
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
    if t3_suspect_pairs:
        print(f"\n🆕 表③ 疑似同店对 {len(t3_suspect_pairs)} 组（**UNMATCHED ↔ 同城未点名 OPEN 门店**；"
              f"只提示 ⇒ 判为同店请 `alias-import` 灌「舞讯原写法」+ 重跑回读）：")
        for x in t3_suspect_pairs:
            print(f"  「{x['src_city']}·{x['name']}」 ⇄ #{x['venueId']} {x['venue_name']}"
                  f"（{x['district']}）相似={x['ratio']}"
                  f"{' 核心词同一' if x['core_same'] else ''}")
    print(f"\n表⑤ 关门（|S|>=2）{len(t5)} 家 / {len({x['city'] for x in t5})} 城: "
          f"{dict(Counter(x['city'] for x in t5))}")
    print(f"表⑤′ 关门（|S|<2，**单源城市，09-29 起一并直接执行**）{len(t5_single)} 家 / "
          f"{len({x['city'] for x in t5_single})} 城: {dict(Counter(x['city'] for x in t5_single))}")
    if t5_suspect_hold:
        print(f"\n🛡 疑似同店 · 暂停方向保守剔除 {len(t5_suspect_hold)} 家"
              f"（**被点名但匹配没挂上** ⇒ 不做暂停推断；由 Agent 判是否加别名）：")
        for x in t5_suspect_hold:
            ev = "；".join(f"「{s['news']}」[{s['src_city']}/{s['conf']}] 相似={s['ratio']}（{s['why']}）"
                           for s in x["suspects"])
            print(f"  #{x['venueId']} {x['name']}（{x['city']}·{x.get('district')}） ← {ev}")
    hinted = [x for x in t5 + t5_single if x.get("suspect_hints")]
    if hinted:
        print(f"\n🔎 弱相似提示（**仍提交**，需 Agent 逐条判）{len(hinted)} 家：")
        for x in hinted:
            ev = "；".join(f"「{s['news']}」[{s['src_city']}/{s['conf']}] 相似={s['ratio']}"
                           for s in x["suspect_hints"])
            print(f"  #{x['venueId']} {x['name']}（{x['city']}） ← {ev}")
    if guard_drop:
        print(f"守卫豁免剔除 {len(guard_drop)} 家: {[x['name'] + '#' + str(x['venueId']) for x in guard_drop]}")
    if scope_drop:
        from collections import Counter as _C
        print(f"\n🧭 范围细化排除 {len(scope_drop)} 家（县级市/县/镇未报到 ⇒ 不参与推断；"
              f"审计用，含县级分布 {dict(_C((x.get('district') or '?') for x in scope_drop))}）：")
        for x in scope_drop:
            print(f"  #{x['venueId']} {x['name']}（{x['city']}·{x.get('district')}）")
    if t5_manual_hold:
        print(f"\n🖐 人工状态待确认（statusSource=MANUAL，**已剔除、不进自动提交集**）"
              f"{len(t5_manual_hold)} 家：")
        for x in t5_manual_hold:
            lock = str(x.get("lockedUntil"))[:16] if x.get("lockedUntil") else "（已过期）"
            print(f"  #{x['venueId']} {x['name']}（{x['city']}·{x.get('district')}）锁至 {lock}"
                  f" —— 需用户决定：本次照关（写库后转 SYNC 回归自动）/ 本次跳过")
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
