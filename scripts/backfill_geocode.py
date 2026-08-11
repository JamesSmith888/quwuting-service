#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
批量地理编码回写脚本：为 qwt_venues 中缺失经纬度的门店补全 gcj02 坐标。

背景（2026-08-11）：
- 前端导航（wx.openLocation）依赖 venue.latitude/longitude，缺失时提示"该舞厅暂无坐标信息"；
- 新增门店走 wx.chooseLocation 人工选点，存量 646 条缺坐标；
- 本脚本读库 → 拼接完整地址 → 调腾讯位置服务地理编码 API（输出 gcj02，与前端约定一致）
  → 幂等回写 UPDATE。

用法：
    export QWT_DB_URL='jdbc:postgresql://...'
    export QWT_DB_USER='postgres.xxx'
    export QWT_DB_PASSWORD='xxx'
    export QQMAP_KEY='你的腾讯位置服务 WebServiceKey'
    python3 scripts/backfill_geocode.py [--limit N] [--city 南通市] [--dry-run]

参数：
    --limit N      只处理前 N 条（默认全部）
    --city 城市名  只处理指定城市（可选）
    --dry-run      只查询不写入，打印将处理的条目
    --batch N      每批 commit 条数（默认 20）
    --sleep S      每次 API 调用间隔秒数（默认 0.25，腾讯个人版约 5QPS 上限）
    --retry N      失败重试次数（默认 2）

安全：
    - 只更新 latitude/longitude 为 NULL 的记录（幂等，可重复执行）；
    - 坐标范围校验：中国境内经纬度合法区间，越界不写入并记录；
    - 全程事务分批提交，失败回滚当批；
    - 输出处理报告：成功 / 跳过（无地址）/ 失败 / 越界。
"""

import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

import psycopg2
import psycopg2.extras


def parse_jdbc_url(url: str):
    """把 jdbc:postgresql://host:port/db?params 解析为 psycopg2 连接参数。"""
    rest = url[len("jdbc:postgresql://"):]
    # 去掉 query 部分
    if "?" in rest:
        rest, _query = rest.split("?", 1)
    host_port, _, dbname = rest.rpartition("/")
    if ":" in host_port:
        host, port = host_port.rsplit(":", 1)
    else:
        host, port = host_port, "5432"
    return {
        "host": host,
        "port": int(port),
        "dbname": dbname or "postgres",
    }


def connect():
    url = os.environ.get("QWT_DB_URL", "")
    user = os.environ.get("QWT_DB_USER", "")
    password = os.environ.get("QWT_DB_PASSWORD", "")
    if not url or not user or not password:
        print("缺少数据库连接环境变量：QWT_DB_URL / QWT_DB_USER / QWT_DB_PASSWORD",
              file=sys.stderr)
        print("示例：", file=sys.stderr)
        print("  export QWT_DB_URL='jdbc:postgresql://aws-0-ap-northeast-1.pooler.supabase.com:6543/postgres?sslmode=require'", file=sys.stderr)
        print("  export QWT_DB_USER='postgres.xxx'", file=sys.stderr)
        print("  export QWT_DB_PASSWORD='xxx'", file=sys.stderr)
        sys.exit(2)
    params = parse_jdbc_url(url)
    params.update({
        "user": user,
        "password": password,
        "sslmode": "require",
        "connect_timeout": 8,
    })
    return psycopg2.connect(**params)


def build_full_address(row):
    """拼接完整地址：优先用 address（可能已含省市区），否则 city+district+address。"""
    city = (row.get("city") or "").strip()
    district = (row.get("district") or "").strip()
    address = (row.get("address") or "").strip()
    if not address:
        return ""
    # address 可能已经包含省市（如 "江苏省南通市崇川区钟秀中路98号"），避免重复
    if city and city in address:
        return address
    return f"{city}{district}{address}".strip()


def geocode(address: str, key: str, retry: int = 2):
    """调用腾讯位置服务地理编码，返回 (lat, lng) 或抛异常。"""
    url = "https://apis.map.qq.com/ws/geocoder/v1/?" + urllib.parse.urlencode({
        "address": address,
        "key": key,
    })
    last_err = None
    for attempt in range(retry + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "quwuting-backfill/1.0"})
            with urllib.request.urlopen(req, timeout=8) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            if data.get("status") != 0:
                raise RuntimeError(f"geocode status={data.get('status')} msg={data.get('message')}")
            loc = data.get("result", {}).get("location")
            if not loc or loc.get("lat") is None or loc.get("lng") is None:
                raise RuntimeError("geocode result missing location")
            return float(loc["lat"]), float(loc["lng"])
        except Exception as e:  # noqa: BLE001
            last_err = e
            if attempt < retry:
                time.sleep(0.5 * (attempt + 1))
    raise RuntimeError(f"geocode failed: {last_err}")


def is_valid_cn_coord(lat: float, lng: float) -> bool:
    """中国境内经纬度粗校验（gcj02 火星坐标）。"""
    return 18.0 <= lat <= 54.0 and 73.0 <= lng <= 135.0


def main():
    parser = argparse.ArgumentParser(description="批量补全门店 gcj02 坐标")
    parser.add_argument("--limit", type=int, default=0, help="只处理前 N 条（0=全部）")
    parser.add_argument("--city", type=str, default="", help="只处理指定城市")
    parser.add_argument("--dry-run", action="store_true", help="只查询不写入")
    parser.add_argument("--batch", type=int, default=20, help="每批 commit 条数")
    parser.add_argument("--sleep", type=float, default=0.25, help="API 调用间隔秒数")
    parser.add_argument("--retry", type=int, default=2, help="失败重试次数")
    args = parser.parse_args()

    key = os.environ.get("QQMAP_KEY", "")
    if not key:
        print("缺少腾讯位置服务 key：请先 export QQMAP_KEY='你的key'", file=sys.stderr)
        print("申请地址：https://lbs.qq.com/ 控制台 → 创建应用 → WebService API → 获取 Key", file=sys.stderr)
        sys.exit(2)

    conn = connect()
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    sql = """
        SELECT id, name, city, district, address
        FROM qwt_venues
        WHERE deleted = false
          AND (latitude IS NULL OR longitude IS NULL)
          AND address IS NOT NULL AND address <> ''
    """
    params = []
    if args.city:
        sql += " AND city = %s"
        params.append(args.city)
    sql += " ORDER BY id"
    if args.limit > 0:
        sql += " LIMIT %s"
        params.append(args.limit)

    cur.execute(sql, params)
    rows = cur.fetchall()
    print(f"待处理：{len(rows)} 条缺坐标记录", flush=True)

    stats = {"ok": 0, "skip": 0, "fail": 0, "invalid": 0}
    failures = []

    for i, row in enumerate(rows, 1):
        address = build_full_address(row)
        if not address:
            stats["skip"] += 1
            print(f"[{i}/{len(rows)}] SKIP id={row['id']} {row['name']}（无地址）", flush=True)
            continue

        if args.dry_run:
            print(f"[{i}/{len(rows)}] DRY id={row['id']} {row['name']} 地址={address}", flush=True)
            continue

        try:
            lat, lng = geocode(address, key, retry=args.retry)
        except Exception as e:  # noqa: BLE001
            stats["fail"] += 1
            failures.append((row["id"], row["name"], str(e)))
            print(f"[{i}/{len(rows)}] FAIL id={row['id']} {row['name']}: {e}", flush=True)
            time.sleep(args.sleep)
            continue

        if not is_valid_cn_coord(lat, lng):
            stats["invalid"] += 1
            failures.append((row["id"], row["name"], f"坐标越界 ({lat},{lng})"))
            print(f"[{i}/{len(rows)}] INVALID id={row['id']} {row['name']} ({lat},{lng})", flush=True)
            time.sleep(args.sleep)
            continue

        cur.execute(
            "UPDATE qwt_venues SET latitude=%s, longitude=%s, updated_at=now() WHERE id=%s",
            (lat, lng, row["id"]),
        )
        stats["ok"] += 1
        print(f"[{i}/{len(rows)}] OK id={row['id']} {row['name']} → ({lat:.6f}, {lng:.6f})", flush=True)

        if i % args.batch == 0:
            conn.commit()

    if not args.dry_run:
        conn.commit()

    print("\n===== 处理报告 =====")
    print(f"成功：{stats['ok']}  跳过(无地址)：{stats['skip']}  失败：{stats['fail']}  越界：{stats['invalid']}")
    if failures:
        print("\n失败/越界明细：")
        for vid, name, reason in failures:
            print(f"  id={vid} {name}：{reason}")

    cur.close()
    conn.close()
    sys.exit(0 if stats["fail"] == 0 and stats["invalid"] == 0 else 1)


if __name__ == "__main__":
    main()
