#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
去舞厅 Supabase Storage → 阿里云 OSS 对象迁移脚本（东京 → 华东1杭州）。

背景（2026-09-17，对象存储切回国内）：
- DB 已迁阿里云 RDS MySQL（08-31），对象存储是最后一项海外依赖；
- 本脚本把 Supabase qwt-public 桶全部对象拷贝到 OSS 同路径
  （{分类前缀}/{userId}/{uuid}.{ext}，路径保持不变），拷贝完成后在库内
  改写 URL 前缀（migrate_oss_url_rewrite_mysql.sql）。

用法（密钥只在本机/服务器使用，不写入任何文件）：
    export SUPABASE_URL='https://ijhuwkpumjnqxmfwobog.supabase.co'
    export SUPABASE_ANON='<项目 anon key（公开桶可匿名 list/read）>'
    export OSS_ENDPOINT='oss-cn-hangzhou.aliyuncs.com'
    export OSS_BUCKET='<bucket 名称>'
    # 凭证二选一：
    #   a) 在 ECS 上跑（推荐）：先给实例绑定角色，加 --use-instance-role --role-name <角色名>
    #   b) 本机跑：export OSS_ACCESS_KEY_ID='<RAM 子账号 AK>' / OSS_ACCESS_KEY_SECRET='<SK>'
    python3 scripts/migrate_supabase_to_oss.py [--dry-run] [--limit N]

也可用命令行参数代替环境变量（--supabase-url/--supabase-anon/--oss-endpoint/
--oss-bucket/--oss-ak/--oss-sk）。

上传通道：OSS PUT + V1 头签名（HMAC-SHA1，Content-MD5 保证完整性），
零第三方依赖（纯标准库），与 migrate_supabase_storage.py 同款工程约定。

参数：
    --concurrency N     并发拷贝数（默认 8）
    --limit N           只迁移前 N 个对象（调试用，默认全部）
    --dry-run           只枚举不拷贝，打印统计
    --objects-file F    只迁移文件 F 中的对象名（每行一个，配合失败重试）
    --failures-file F   失败对象名输出文件（默认 oss-migrate-failures.txt）

前置条件：
    1. OSS bucket 已创建（华东1杭州、公共读、阻止公共访问已关闭）；
    2. RAM 子账号已授权 oss:PutObject（最小权限策略见 docs/agents/11-storage.md）；
    3. Supabase 桶可匿名读（公开桶，publicUrl 直读）。

幂等：OSS PUT 同名覆盖（对象 key 唯一），重复执行安全。
时序：本脚本在「后端 provider 翻成 oss 之后」执行——过渡窗口期落在 Supabase
的直传对象也会被本次枚举覆盖；执行后立即跑 URL 改写 SQL。
"""

import argparse
import base64
import datetime
import hashlib
import hmac
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from email.utils import formatdate

LIST_PAGE = 200
METADATA_BASE = "http://100.100.100.200/latest/meta-data/ram/security-credentials/"

MIME_BY_EXT = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
    ".mp4": "video/mp4",
    ".mov": "video/quicktime",
}


def http_request(method, url, headers=None, body=None, timeout=120):
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


def oss_put(bucket, endpoint, ak, sk, key, data, content_type, security_token=None):
    """V1 头签名 PUT 上传单个对象（支持 STS 临时凭证）。返回 (status, body)。"""
    quoted = urllib.parse.quote(key, safe="/")
    url = f"https://{bucket}.{endpoint}/{quoted}"
    date = formatdate(usegmt=True)
    content_md5 = base64.b64encode(hashlib.md5(data).digest()).decode("utf-8")
    # StringToSign = VERB \n Content-MD5 \n Content-Type \n Date \n CanonicalizedOSSHeaders \n /{bucket}/{key}
    # STS 临时凭证时 CanonicalizedOSSHeaders 含 x-oss-security-token（小写、冒号后无空格、整行以 \n 结尾）
    canonical_headers = f"x-oss-security-token:{security_token}\n" if security_token else ""
    string_to_sign = f"PUT\n{content_md5}\n{content_type}\n{date}\n{canonical_headers}/{bucket}/{key}"
    digest = hmac.new(sk.encode("utf-8"), string_to_sign.encode("utf-8"), hashlib.sha1).digest()
    signature = base64.b64encode(digest).decode("utf-8")
    headers = {
        "Date": date,
        "Content-MD5": content_md5,
        "Content-Type": content_type,
        "Authorization": f"OSS {ak}:{signature}",
    }
    if security_token:
        headers["x-oss-security-token"] = security_token
    return http_request("PUT", url, headers, data)


def fetch_instance_role_credentials(role_name):
    """从 ECS 实例元数据端点取 STS 临时凭证（阿里云最佳实践：无长期 AK）。"""
    url = METADATA_BASE + role_name
    status, raw = http_request("GET", url, timeout=10)
    if status != 200:
        raise RuntimeError(f"实例角色凭证拉取失败 HTTP {status}（检查 ECS 是否绑定角色 {role_name}）")
    doc = json.loads(raw.decode("utf-8"))
    if doc.get("Code") != "Success":
        raise RuntimeError(f"实例角色凭证响应异常 Code={doc.get('Code')!r}")
    return doc["AccessKeyId"], doc["AccessKeySecret"], doc["SecurityToken"], doc.get("Expiration")


def copy_one(old_base, old_bucket, old_anon, oss, name):
    """下载 Supabase 对象 → 签名 PUT 到 OSS 同路径。成功返回 (name, None)，失败返回 (name, 原因)。"""
    bucket, endpoint, creds = oss
    quoted = urllib.parse.quote(name, safe="/")
    dl_url = f"{old_base}/storage/v1/object/public/{old_bucket}/{quoted}"
    status, data = http_request("GET", dl_url)
    if status != 200:
        return name, f"download failed HTTP {status}"
    if not data:
        return name, "downloaded empty body"
    ak, sk, token = creds()
    up_status, up_raw = oss_put(bucket, endpoint, ak, sk, name, data, guess_mime(name), token)
    if up_status != 200:
        return name, f"oss put failed HTTP {up_status} {up_raw[:200]!r}"
    return name, None


def parse_iso_ts(s):
    """解析 STS Expiration（ISO8601）为 epoch 秒；解析失败退化为 1h（届时按过期重取）。"""
    try:
        return datetime.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    except Exception:
        return time.time() + 3600


def make_creds_provider(args):
    """凭证提供器：实例角色模式（自动刷新 STS）或显式 AK 模式。返回 () -> (ak, sk, token)。"""
    if not args.use_instance_role:
        return lambda: (args.oss_ak, args.oss_sk, None)
    if not args.role_name:
        sys.exit("--use-instance-role 需要 --role-name（或环境变量 OSS_INSTANCE_ROLE_NAME）")
    lock = threading.Lock()
    state = {"exp": 0.0, "creds": None}

    def provider():
        with lock:
            if state["creds"] is None or state["exp"] - time.time() < 300:
                ak, sk, token, expiration = fetch_instance_role_credentials(args.role_name)
                state["creds"] = (ak, sk, token)
                state["exp"] = parse_iso_ts(expiration) if expiration else time.time() + 3600
            return state["creds"]
    return provider


def main():
    ap = argparse.ArgumentParser(description="Supabase Storage → 阿里云 OSS 对象迁移")
    ap.add_argument("--supabase-url", default=os.environ.get("SUPABASE_URL"))
    ap.add_argument("--supabase-anon", default=os.environ.get("SUPABASE_ANON"))
    ap.add_argument("--bucket", default="qwt-public", help="Supabase 源桶名")
    ap.add_argument("--oss-endpoint", default=os.environ.get("OSS_ENDPOINT"))
    ap.add_argument("--oss-bucket", default=os.environ.get("OSS_BUCKET"))
    ap.add_argument("--oss-ak", default=os.environ.get("OSS_ACCESS_KEY_ID"),
                    help="RAM 子账号 AK（--use-instance-role 时不需要）")
    ap.add_argument("--oss-sk", default=os.environ.get("OSS_ACCESS_KEY_SECRET"),
                    help="RAM 子账号 SK（--use-instance-role 时不需要）")
    ap.add_argument("--use-instance-role", action="store_true",
                    help="在 ECS 上运行时用实例角色 STS 临时凭证上传（阿里云最佳实践）")
    ap.add_argument("--role-name", default=os.environ.get("OSS_INSTANCE_ROLE_NAME"),
                    help="ECS 实例角色名（--use-instance-role 时必填）")
    ap.add_argument("--concurrency", type=int, default=8)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--objects-file")
    ap.add_argument("--failures-file", default="oss-migrate-failures.txt")
    args = ap.parse_args()

    for label, val in (("--supabase-url", args.supabase_url), ("--supabase-anon", args.supabase_anon),
                       ("--oss-endpoint", args.oss_endpoint), ("--oss-bucket", args.oss_bucket)):
        if not val:
            sys.exit(f"缺少 {label}（可用环境变量或参数提供）")
    if not args.use_instance_role and (not args.oss_ak or not args.oss_sk):
        sys.exit("缺少 --oss-ak/--oss-sk（或在 ECS 上加 --use-instance-role --role-name <角色名>）")

    print(f"[1/2] 枚举 Supabase 桶 {args.bucket} 对象 …")
    if args.objects_file:
        with open(args.objects_file, encoding="utf-8") as f:
            names = [ln.strip() for ln in f if ln.strip()]
        print(f"      从文件读取 {len(names)} 个对象名")
    else:
        names = enumerate_all(args.supabase_url, args.bucket, args.supabase_anon)
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

    oss = (args.oss_bucket, args.oss_endpoint, make_creds_provider(args))
    print(f"[2/2] 并发拷贝 {len(names)} 个对象 → https://{args.oss_bucket}.{args.oss_endpoint}/"
          f"（concurrency={args.concurrency}，凭证={'实例角色 STS' if args.use_instance_role else 'RAM AK'}）…")
    done = ok = 0
    total_bytes = 0
    failures = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = {pool.submit(copy_one, args.supabase_url, args.bucket, args.supabase_anon, oss, n): n
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
    print("下一步：执行 migrate_oss_url_rewrite_mysql.sql 改写库内 URL 前缀，"
          "再核对 OSS 控制台对象数/总字节与源桶对账。")


if __name__ == "__main__":
    main()
