#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
去舞厅 Supabase Storage 对象迁移脚本（旧项目 → 新项目，东京 ap-northeast-1）。

背景（2026-08-17）：
- 切换 Supabase 项目到日本区；业务数据另迁，本脚本只负责**对象存储迁移**；
- 对象路径两桶一致（{分类前缀}/{userId}/{uuid}.{ext}），拷贝后只需在库内
  改写 URL 前缀（见 migrate_supabase_storage_url_rewrite.sql）。

用法（建议用 service_role key 或新项目 anon key；密钥只在本机使用）：
    export OLD_SUPABASE_URL='https://<老ref>.supabase.co'
    export OLD_SUPABASE_ANON='<老项目 anon key>'
    export NEW_SUPABASE_URL='https://<新ref>.supabase.co'
    export NEW_SUPABASE_KEY='<新项目 service_role 或 anon key>'
    python3 scripts/migrate_supabase_storage.py [--bucket qwt-public] [--concurrency 8]

也可用命令行参数 --old-url/--old-anon/--new-url/--new-key 代替环境变量。

参数：
    --bucket NAME       桶名（默认 qwt-public，两项目必须同名）
    --concurrency N     并发拷贝数（默认 8）
    --limit N           只迁移前 N 个对象（调试用，默认全部）
    --dry-run           只枚举不拷贝，打印统计
    --objects-file F    只迁移文件 F 中的对象名（每行一个，配合失败重试）
    --failures-file F   失败对象名输出文件（默认 storage-migrate-failures.txt）

前置条件：
    1. 新项目已建桶（建桶 + RLS SQL 见上一轮交付，须先执行）；
    2. 新 key 用 anon 时，新桶需有 anon INSERT 策略（建桶 SQL 已含）；
       用 service_role 时绕过 RLS，无需策略；
    3. 旧桶须可匿名读（公开桶，publicUrl 直读）。

幂等：上传带 x-upsert:true，重复执行覆盖同名对象，安全。
安全：密钥不写入文件；脚本仅直连两项目 Storage API。
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

LIST_PAGE = 200

MIME_BY_EXT = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
}


def http_request(method, url, headers=None, body=None, timeout=60):
    """发 HTTP 请求，返回 (status, bytes)。body 为 dict 时 JSON 序列化，bytes 时原样。"""
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8") if not isinstance(body, (bytes, bytearray)) else bytes(body)
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:  # 网络层错误
        return 0, str(e).encode("utf-8")


def list_objects(base, bucket, anon, prefix, limit=LIST_PAGE, offset=0):
    """列出一个前缀下的对象（文件夹条目 name 以 '/' 结尾，文件为完整路径）。"""
    url = f"{base}/storage/v1/object/list/{bucket}"
    headers = {"Authorization": f"Bearer {anon}", "Content-Type": "application/json"}
    status, raw = http_request("POST", url, headers, {"prefix": prefix, "limit": limit, "offset": offset})
    if status not in (200, 201):
        # 旧版 storage-api 只支持 GET query 方式
        q = urllib.parse.urlencode({"prefix": prefix, "limit": limit, "offset": offset})
        status, raw = http_request("GET", f"{url}?{q}", {"Authorization": f"Bearer {anon}"})
    if status not in (200, 201):
        raise RuntimeError(f"list prefix={prefix!r} failed: HTTP {status} {raw[:200]!r}")
    return json.loads(raw.decode("utf-8"))


def enumerate_all(base, bucket, anon):
    """递归枚举桶内全部对象名（兼容"平铺返回全部"与"目录式返回文件夹"两种行为）。"""
    names, queue, visited = [], [""], set()
    while queue:
        prefix = queue.pop(0)
        if prefix in visited:
            continue
        visited.add(prefix)
        offset = 0
        while True:
            items = list_objects(base, bucket, anon, prefix, offset=offset)
            if not items:
                break
            for it in items:
                name = it.get("name") or ""
                if name.endswith("/"):
                    queue.append(name)  # 目录 → 继续下钻
                else:
                    names.append(name)
            offset += len(items)
            if len(items) < LIST_PAGE:
                break
    return names


def guess_mime(name):
    ext = os.path.splitext(name)[1].lower()
    return MIME_BY_EXT.get(ext, "application/octet-stream")


def copy_one(old_base, new_base, bucket, new_key, name):
    """下载旧对象 → 上传新对象。成功返回 (name, None)，失败返回 (name, 原因)。"""
    quoted = urllib.parse.quote(name, safe="/")
    dl_url = f"{old_base}/storage/v1/object/public/{bucket}/{quoted}"
    status, data = http_request("GET", dl_url)
    if status != 200:
        return name, f"download failed HTTP {status}"

    up_url = f"{new_base}/storage/v1/object/{bucket}/{quoted}"
    headers = {
        "Authorization": f"Bearer {new_key}",
        "x-upsert": "true",
        "Content-Type": guess_mime(name),
    }
    status, raw = http_request("POST", up_url, headers, data)
    if status not in (200, 201):
        return name, f"upload failed HTTP {status} {raw[:200]!r}"
    return name, None


def main():
    ap = argparse.ArgumentParser(description="Supabase Storage 对象迁移（旧项目→新项目）")
    ap.add_argument("--old-url", default=os.environ.get("OLD_SUPABASE_URL"))
    ap.add_argument("--old-anon", default=os.environ.get("OLD_SUPABASE_ANON"))
    ap.add_argument("--new-url", default=os.environ.get("NEW_SUPABASE_URL"))
    ap.add_argument("--new-key", default=os.environ.get("NEW_SUPABASE_KEY"))
    ap.add_argument("--bucket", default="qwt-public")
    ap.add_argument("--concurrency", type=int, default=8)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--objects-file")
    ap.add_argument("--failures-file", default="storage-migrate-failures.txt")
    args = ap.parse_args()

    for label, val in (("--old-url", args.old_url), ("--old-anon", args.old_anon),
                       ("--new-url", args.new_url), ("--new-key", args.new_key)):
        if not val:
            sys.exit(f"缺少 {label}（可用环境变量或参数提供）")

    print(f"[1/2] 枚举旧桶 {args.bucket} 对象 …")
    if args.objects_file:
        with open(args.objects_file, encoding="utf-8") as f:
            names = [ln.strip() for ln in f if ln.strip()]
        print(f"      从文件读取 {len(names)} 个对象名")
    else:
        names = enumerate_all(args.old_url, args.bucket, args.old_anon)
        print(f"      枚举完成：共 {len(names)} 个对象")
        if args.limit > 0:
            names = names[: args.limit]
            print(f"      --limit {args.limit}：仅迁移前 {args.limit} 个")

    if args.dry_run:
        print("[dry-run] 不执行拷贝。前 10 个对象：")
        for n in names[:10]:
            print(f"  - {n}")
        return

    if not names:
        print("没有需要迁移的对象。")
        return

    print(f"[2/2] 并发拷贝 {len(names)} 个对象（concurrency={args.concurrency}）…")
    done = ok = 0
    failures = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = {pool.submit(copy_one, args.old_url, args.new_url, args.bucket, args.new_key, n): n
                   for n in names}
        for fut in as_completed(futures):
            name, err = fut.result()
            done += 1
            if err:
                failures.append((name, err))
            else:
                ok += 1
            if done % 50 == 0 or done == len(names):
                print(f"      进度 {done}/{len(names)}，成功 {ok}，失败 {len(failures)}")

    if failures:
        with open(args.failures_file, "w", encoding="utf-8") as f:
            for n, err in failures:
                f.write(f"{n}\t{err}\n")
        print(f"完成：成功 {ok}，失败 {len(failures)}（明细已写入 {args.failures_file}；"
              f"可用 --objects-file 提取失败名重跑）")
        sys.exit(1)
    print(f"完成：{ok} 个对象全部迁移成功。")
    print("下一步：在库内改写 URL 前缀（migrate_supabase_storage_url_rewrite.sql），"
          "然后切换后端 supabase.storage 配置。")


if __name__ == "__main__":
    main()
