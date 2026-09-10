#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""去舞厅 · 舞讯采集 Skill 的后端 API 封装（Python3 标准库，零依赖）。

用法（子命令，输出 JSON 便于 Agent 解析）：
  # 登录换 JWT（密码交互输入或 --password；成功后导出 ADMIN_TOKEN 复用）
  python3 qw_api.py login --base-url http://localhost:8080 [--password xxx]

  # 候选门店按量加载（city/status 精确筛选；size 上限 500）
  python3 qw_api.py export --base-url http://localhost:8080 [--city 成都市] [--status OPEN] [--page 0] [--size 500]

  # 平台城市词表（公开接口，无需 token）
  python3 qw_api.py cities --base-url http://localhost:8080

  # 批量新增门店（items 为 JSON 数组字符串或 @文件路径）
  python3 qw_api.py batch-create --base-url http://localhost:8080 --items '[{"name":"xx","city":"成都市"}]'
  python3 qw_api.py batch-create --base-url http://localhost:8080 --items @/tmp/items.json

  # 批量状态反转（复用 /admin/venue-daily-openings/batch；仅 OPEN+停业/歇业→营业）
  # 自动注入 "source":"AGENT_BATCH"（变更来源标识：Agent+Skill 批量更新，审计/管理后台展示用）
  python3 qw_api.py status-reverse --base-url http://localhost:8080 --items '[...]'

  # 批量置「暂停营业」（白名单口径，2026-09-10；仅 OPEN→SUSPENDED，非 OPEN 静默跳过）
  # items 元素只需 {"venueId":123}——reportDate/sourceId/source 由脚本统一注入
  python3 qw_api.py status-suspend --base-url http://localhost:8080 --items '[...]' --report-date 2026-09-10

token 获取顺序：--token 参数 > ADMIN_TOKEN 环境变量 > 报错（提示先 login）。
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

UA = "quwuting-venue-daily-sync/1.0"


def _request(base_url: str, method: str, path: str, token: str | None,
             body: dict | None = None) -> dict:
    url = f"{base_url.rstrip('/')}{path}"
    headers = {"User-Agent": UA}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="ignore")[:300]
        if e.code == 401:
            sys.exit(f"[error] HTTP 401 token 无效或过期，请重新 login（{detail}）")
        sys.exit(f"[error] HTTP {e.code}: {detail}")
    except Exception as e:
        sys.exit(f"[error] 请求失败（{url}）: {e}")
    if payload.get("code") != 0:
        sys.exit(f"[error] 业务错误 code={payload.get('code')}: {payload.get('message')}")
    return payload["data"]


def _load_items(raw: str) -> list:
    if raw.startswith("@"):
        with open(raw[1:], encoding="utf-8") as f:
            return json.load(f)
    return json.loads(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description="去舞厅舞讯 Skill API 封装")
    parser.add_argument("command", choices=["login", "export", "cities", "batch-create", "status-reverse", "status-suspend"])
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--token", default=os.environ.get("ADMIN_TOKEN", ""))
    parser.add_argument("--password")
    parser.add_argument("--city")
    parser.add_argument("--status")
    parser.add_argument("--page", type=int, default=0)
    parser.add_argument("--size", type=int, default=500)
    parser.add_argument("--items", help="JSON 数组字符串或 @文件路径")
    parser.add_argument("--report-date", help="舞讯报告日期 YYYY-MM-DD（status-reverse 必填）")
    parser.add_argument("--source-id", default="xianbao360", help="渠道标识（status-reverse）")
    parser.add_argument("--change-source", default="AGENT_BATCH",
                        help="变更来源标识（status-reverse 写入审计日志，默认 AGENT_BATCH=Agent 批量更新）")
    args = parser.parse_args()

    if args.command == "login":
        password = args.password or getpass.getpass("管理密码: ")
        data = _request(args.base_url, "POST", "/web-auth/password-login",
                        None, {"username": os.environ.get("WEB_ADMIN_USERNAME", "admin"),
                               "password": password})
        print(json.dumps({"token": data.get("token")}, ensure_ascii=False))
        print("# 后续命令请带上 --token <上值> 或 export ADMIN_TOKEN=<上值>", file=sys.stderr)
        return 0

    if args.command == "cities":
        data = _request(args.base_url, "GET", "/venues/cities", None)
        print(json.dumps(data, ensure_ascii=False))
        return 0

    token = args.token or os.environ.get("ADMIN_TOKEN", "")
    if not token:
        print("[error] 需要 ADMIN token：先 login 或用 --token / ADMIN_TOKEN 环境变量", file=sys.stderr)
        return 1

    if args.command == "export":
        params = []
        if args.city:
            params.append(f"city={urllib.parse.quote(args.city)}")
        if args.status:
            params.append(f"status={args.status}")
        params.append(f"page={args.page}")
        params.append(f"size={args.size}")
        query = "&".join(params)
        data = _request(args.base_url, "GET", f"/admin/venue-sync/venues/export?{query}", token)
        print(json.dumps(data, ensure_ascii=False))
        return 0

    if args.command == "batch-create":
        items = _load_items(args.items or "[]")
        data = _request(args.base_url, "POST", "/admin/venue-sync/venues/batch-create",
                        token, {"items": items})
        print(json.dumps(data, ensure_ascii=False))
        return 0

    if args.command == "status-reverse":
        if not args.report_date:
            print("[error] status-reverse 需要 --report-date YYYY-MM-DD", file=sys.stderr)
            return 1
        items = _load_items(args.items or "[]")
        for it in items:
            it.setdefault("reportDate", args.report_date)
            it.setdefault("sourceId", args.source_id)
            it.setdefault("source", args.change_source)  # 批量更新标识（审计/管理后台展示）
        data = _request(args.base_url, "POST", "/admin/venue-daily-openings/batch",
                        token, {"items": items})
        print(json.dumps(data, ensure_ascii=False))
        return 0

    if args.command == "status-suspend":
        # 白名单口径反向通道（2026-09-10）：被舞讯覆盖城市内、未上榜门店 OPEN → SUSPENDED。
        # ⚠️ 城市范围由调用方（Agent）保证：只提交「点名覆盖城市」内的门店，未覆盖城市不动。
        if not args.report_date:
            print("[error] status-suspend 需要 --report-date YYYY-MM-DD", file=sys.stderr)
            return 1
        items = _load_items(args.items or "[]")
        for it in items:
            it.setdefault("reportDate", args.report_date)
            it.setdefault("sourceId", args.source_id)
            it.setdefault("source", args.change_source)
        data = _request(args.base_url, "POST", "/admin/venue-daily-openings/batch-suspend",
                        token, {"items": items})
        print(json.dumps(data, ensure_ascii=False))
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())
