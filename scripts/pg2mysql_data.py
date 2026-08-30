#!/usr/bin/env python3
"""PG COPY 数据 → MySQL INSERT 转换器（quwuting 生产数据迁移）。

输入：pg_dump --data-only 输出（/tmp/qwt_pg_data.sql）
输出：MySQL 兼容 INSERT 批量语句（/tmp/qwt_mysql_data.sql）
规则：
- 只处理 COPY public.qwt_* 块（跳过其他表）
- boolean 列（MySQL tinyint(1)）：'t'→1 / 'f'→0（列类型从 MySQL information_schema 获取）
- 反斜杠N → NULL；值内 ' → ''（SQL 标准转义）；PG COPY 转义与 MySQL 字符串转义兼容
- 列名保留字（key）加反引号
- id 显式保留；生成列（uk_key_*）不写入（MySQL 侧自动计算）
"""
import re
import subprocess

PG_DATA = '/tmp/qwt_pg_data.sql'
OUT = '/tmp/qwt_mysql_data.sql'
MYSQL = '/usr/local/opt/mysql@8.0/bin/mysql'
SOCK = '/tmp/mysql80.sock'


def get_mysql_columns():
    """查 MySQL 每表列类型（boolean 识别）与生成列。"""
    cmd = [MYSQL, f'--socket={SOCK}', '-u', 'root', 'qwt_mysql', '-N', '-B', '-e',
           "SELECT table_name, column_name, data_type FROM information_schema.columns "
           "WHERE table_schema='qwt_mysql' AND table_name LIKE 'qwt_%';"]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    cols = {}
    gen = {}
    for line in out.strip().splitlines():
        parts = line.split('\t')
        if len(parts) != 3:
            continue
        t, c, dt = parts
        cols.setdefault(t, {})[c] = dt
        if c.startswith('uk_key_'):
            gen.setdefault(t, []).append(c)
    return cols, gen


def unescape_field(f: str) -> str:
    """PG COPY 字段 → 原始值（处理 NULL 标记与转义序列）。"""
    if f == r'\N':
        return None
    f = f.replace('\\\\', '\\')
    f = f.replace('\\n', '\n')
    f = f.replace('\\r', '\r')
    f = f.replace('\\t', '\t')
    f = f.replace('\\b', '\b')
    f = f.replace('\\f', '\f')
    f = f.replace('\\v', '\v')
    f = re.sub(r'\\x([0-9a-fA-F]{2})', lambda m: chr(int(m.group(1), 16)), f)
    return f


def quote_mysql(v: str) -> str:
    """MySQL SQL 字符串字面量：' → ''。"""
    return "'" + v.replace("'", "''") + "'"


def main():
    cols_by_tbl, _ = get_mysql_columns()
    src = open(PG_DATA).read()
    out_lines = []
    total_rows = 0
    for m in re.finditer(
            r'^COPY public\.(qwt_\w+) \((.*?)\) FROM stdin;\n(.*?)^\\\.\n',
            src, re.M | re.S):
        tbl, col_str, body = m.group(1), m.group(2), m.group(3)
        if tbl not in cols_by_tbl:
            print(f'!! 跳过（MySQL 无此表）: {tbl}')
            continue
        pg_cols = [c.strip() for c in col_str.split(',')]
        col_types = cols_by_tbl[tbl]
        # 生成 MySQL 列清单（反引号保留字；MySQL 侧生成列不写入）
        mysql_cols = [f'`{c}`' if c.upper() in ('KEY',) else c for c in pg_cols]
        rows = []
        for line in body.splitlines():
            if not line.strip():
                continue
            fields = line.split('\t')
            vals = []
            for c, f in zip(pg_cols, fields):
                raw = unescape_field(f)
                if raw is None:
                    vals.append('NULL')
                else:
                    dt = col_types.get(c, '')
                    if dt == 'tinyint':
                        vals.append('1' if raw == 't' else '0')
                    else:
                        # 时间戳去时区后缀（如 +00）
                        raw = re.sub(r'\s*\+\d{2}:?\d{2}$', '', raw)
                        vals.append(quote_mysql(raw))
            rows.append('(' + ', '.join(vals) + ')')
        # 批量 INSERT（每 200 行一条）
        for i in range(0, len(rows), 200):
            chunk = rows[i:i + 200]
            out_lines.append(f"INSERT INTO `{tbl}` ({', '.join(mysql_cols)}) VALUES\n  " +
                             ',\n  '.join(chunk) + ';')
        total_rows += len(rows)
        print(f'  {tbl}: {len(rows)} rows')
    open(OUT, 'w').write('\n'.join(out_lines) + '\n')
    print(f'written {OUT}: {total_rows} rows total')


if __name__ == '__main__':
    main()
