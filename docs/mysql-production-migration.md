# MySQL 生产迁移上线清单（阿里云 RDS）

> 更新：2026-08-30 · 分支：feature/mysql-migration（MySQL 方言代码）
> 前置：代码层改造（a69606f）+ 真实数据回归修复（00a3267）+ 迁移工具沉淀（39e6156）均已完成
> 本地验证库：MySQL 8.0.46，qwt_mysql 库 9891 行生产数据已导入并回归通过

---

## 一、RDS 购买勾选项（下单前逐项核对）

| 项 | 建议 | 说明 |
|---|---|---|
| 数据库类型/版本 | MySQL 8.0 | 与本地验证环境一致（Flyway/驱动已验证） |
| 系列 | 基础版 | 单节点无主备；可接受（低流量），后续可升级高可用版 |
| 规格 | 2核 2GB | 当前数据 ~1 万行，余量巨大 |
| 存储 | 50GB 高性能云盘 | 足够 |
| 可用区 | 杭州 可用区I | ✅ 与目标一致 |
| **VPC** | ⚠️ **必须与 ECS 114.55.0.14 同 VPC** | ✅ 已确认：ECS 与页面默认均为 `vpc-bp1hxa69h74f62fgplmj6`，直接按默认下单 |
| 交换机 | 自动创建 | 与 ECS 同可用区（或任一，RDS 内网地址与可用区无关） |
| 可用区 | ECS 在 J（cn-hangzhou-j），RDS 页面默认 I | 同地域 VPC 内跨 AZ 互通（~1ms），不阻塞；下单时若能选 J 则同 AZ 最优 |
| 字符集 | **utf8mb4**（建库时指定） | 页面创建实例后建库时选 utf8mb4 / utf8mb4_unicode_ci |
| 时区 | Asia/Shanghai（参数组） | 保证 DB 端 now()/CURRENT_TIMESTAMP 为北京时间（应用时间由 JVM 生成不受影响） |

> 付费方式：包年包月 ¥99/年（优惠活动），页面显示 774→675→99 属正常折扣叠加。

## 二、购买后 RDS 初始化（一次性）

```sql
-- ① 建库（字符集 utf8mb4）
CREATE DATABASE qwt_mysql CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ② 建业务账号（禁 root）
CREATE USER 'qwt_app'@'%' IDENTIFIED BY '<强密码>';
GRANT ALL PRIVILEGES ON qwt_mysql.* TO 'qwt_app'@'%';
FLUSH PRIVILEGES;
```

- **白名单**：RDS 控制台「白名单与安全组」添加 ECS 内网 IP **172.21.240.255**（已确认，同 VPC 内网互访走该地址）；
  本地直连调试可临时加本机公网 IP（用完即删）。
- **参数组**：`time_zone = Asia/Shanghai`（如实例默认 UTC 需改）。

## 三、代码部署（feature/mysql-migration）

```bash
# ① 打包（服务器与本地同源）
./mvnw -o clean package -DskipTests   # 或 ./mvnw package

# ② 服务器（ssh aliyun）：
scp target/quwuting-service-0.0.1-SNAPSHOT.jar aliyun:/opt/quwuting/
# application-prod.yaml 已内置 MySQL 数据源（${DB_URL}/${DB_USERNAME}/${DB_PASSWORD} 环境变量注入）

# ③ systemd（/etc/systemd/system/quwuting-service.service）改造：
#    Environment=SPRING_PROFILES_ACTIVE=prod
#    Environment=DB_URL=jdbc:mysql://<RDS内网地址>:3306/qwt_mysql?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=8000&rewriteBatchedStatements=true
#    Environment=DB_USERNAME=qwt_app
#    Environment=DB_PASSWORD=<强密码>
#    其余环境变量（WECHAT_SECRET/JWT_SECRET/SUPABASE_*）保持不变
systemctl daemon-reload
```

> ⚠ 生产此前以 dev profile 运行（PG 连接串在服务器 application-dev.yaml）。切 MySQL 后
> **必须改 systemd 的 SPRING_PROFILES_ACTIVE=prod**（prod 已指向 RDS MySQL），
> 并确认服务器上不再存在会导致覆盖的 application-dev.yaml 或将其改名退役。

## 四、数据迁移（PG → RDS MySQL）

两种来源二选一：

**A. 从本地验证库（最快，数据已导入且回归过）**
```bash
# 本地 MySQL 导出
/usr/local/opt/mysql@8.0/bin/mysqldump --socket=/tmp/mysql80.sock -u root qwt_mysql \
  --default-character-set=utf8mb4 --no-create-info --skip-add-locks --skip-extended-insert \
  --single-transaction > /tmp/qwt_mysql_dump.sql
scp /tmp/qwt_mysql_dump.sql aliyun:/tmp/
# 服务器上导入（RDS）
mysql -h <RDS内网地址> -u qwt_app -p qwt_mysql --default-character-set=utf8mb4 < /tmp/qwt_mysql_dump.sql
```

**B. 从线上 PG 重导（数据源权威路径，含最新数据）**
```bash
# ① 导出（本机，libpq 已装）：参照 docs/pg-to-mysql-migration-analysis.md 的 -t 参数生成
pg_dump --data-only --no-owner -t qwt_xxx ... > /tmp/qwt_pg_data.sql
# ② 转换（scripts/pg2mysql_data.py，已沉淀；列类型查询指向目标 MySQL 即可）
python3 scripts/pg2mysql_data.py   # 输入 /tmp/qwt_pg_data.sql → /tmp/qwt_mysql_data.sql
# ③ 导入 RDS
mysql -h <RDS内网地址> -u qwt_app -p qwt_mysql --default-character-set=utf8mb4 < /tmp/qwt_mysql_data.sql
```

> 两种方式导入后都要重置 AUTO_INCREMENT（见 scripts 备注：逐表 ALTER AUTO_INCREMENT=MAX(id)+1）。

## 五、上线切换与冒烟

```bash
systemctl restart quwuting-service
journalctl -u quwuting-service -f   # 观察 Flyway: Successfully applied 1 migration + Started
```

冒烟清单（与本地回归同口径）：
- [ ] GET /venues?size=5 → total 1018（业务数据）
- [ ] GET /venues/cities → 城市分布（成都市 107 等）
- [ ] GET /venues/{id}（详情，投影 Long 修复验证）
- [ ] GET /venues/{id}/heat → 31 天趋势非零日
- [ ] GET /dancers?city=南通（不带市）→ total 7（qwt_city_key CHAR_LENGTH 修复验证）
- [ ] GET /dancers/{id}/stats → demandStats（JSON_TABLE）
- [ ] GET /recruitments → 数据
- [ ] 小程序真机：列表/详情/热度/统计/打卡/认可/解锁 主链路

## 六、回滚方案

- **代码回滚**：`git checkout master` 重新打包部署（PG 方言代码）+ systemd 恢复 dev profile + 恢复 application-dev.yaml。
- **数据回滚**：RDS 库保留至观察期结束（7 天）再清理；期间线上 PG 不删除、Supabase 项目保留。

## 七、已知差异（上线前阅读）

1. 迁移分支代码为 **MySQL 方言，PG 环境不可运行**——master 与 feature/mysql-migration 双轨；
2. `LENGTH()` 字节 vs `CHAR_LENGTH()` 字符——MySQL 字符串截断一律 CHAR_LENGTH；
3. 投影接口中布尔表达式（EXISTS 等）返回整数——已修 DetailStats，后续新增查询注意；
4. 已落库迁移脚本禁改字节——MySQL 基线 V1 落库后如需修复走 V2 增量迁移；
5. Supabase Storage 继续使用（与 DB 解耦），RLS 脚本仅 Supabase 侧有效。
