# 常见问题与接口坑（troubleshooting）

> 由 SKILL.md「常见问题」迁出的完整明细。高频 5 条在 SKILL.md 主文件里，本文件是全集。

## 拉取与登录

- **分页响应结构（2026-09-14 补齐，别猜字段名）**：export / announcements 分页返回 Spring `Page`，
  列表在 **`data.content`**，另有 `data.last`（终止判据）/ `data.totalElements` / `data.number`。
  ⚠️ 不是 `items` / `list` / `records`——猜错会静默拿到 0 条（09-14 实测踩过）。
- **export 一页装不下**：size 上限 500，按 page 递增拉完；Skill 场景一城一页足够。
- **token 相关（2026-09-14 起闭环）**：`login` 成功**自动写缓存** `/tmp/qw_token.json`（0600，含
  baseUrl），后续命令**自动读缓存**（`--token` > `ADMIN_TOKEN` > 缓存）；写 `TOKEN=` 无效，
  变量名必须是 `ADMIN_TOKEN`。⚠️ 缓存带 baseUrl 校验：**本地 token 不会被打到生产**
  （换环境要重新 login）。401 = token 过期，重新 login 即可。缓存路径可用 `QW_TOKEN_CACHE` 覆盖。
  有效性一句话验证：
  `curl -H "Authorization: Bearer $TOKEN" $BASE_URL/admin/venue-sync/reversals?limit=1`。
- **管理密码落点**：本地 develop = `quwuting-service/src/main/resources/application-mysql.yaml` 的
  `web-auth.password`（勿写进 SKILL，随仓库变）；生产 = 环境变量 `WEB_ADMIN_PASSWORD`。
- **翻页漏门店**：候选拉取必须稳定排序——export 自带 `id ASC`；降级用公开 `GET /venues` 时
  **必须带 `sort=newest`**（默认 recommended 无 id tie-break，热度分全 0 时漂移漏店）。

## 城市与匹配

- **城市名对不上**：舞讯常用简称（「蓉」「渝」「杭」「成都/成都市」），先映射到平台标准词表
  （`GET /venues/cities`）**并去「市」归一**——城市不匹配必然 UNMATCHED；多源不归一还会造成
  「整城静默不写库」（见 `matching-playbook.md §1`）。
- **怀疑「假新店」**：UNMATCHED 标记为新店候选前，若平台该城门店较多，用
  `GET /venues?keyword=<店名>` 交叉验证一次（keyword 匹配 name/address/description；能搜到同名校对
  但未进候选说明候选拉取有遗漏——检查排序稳定性或翻页完整性）。
  ⚠️ **keyword 参数必须 URL 编码**（中文直接拼 URL 会 400 Bad Request）：curl 需 `--data-urlencode`
  或手工 percent-encode；推荐 Python `urllib.parse.urlencode({'keyword': 店名, 'page': 0, 'size': 20,
  'sort': 'newest'})`（2026-09-01 实测「缤达→宾达舞厅」即靠此法验证）。
- **同城同名判重兜底**：batch-create 服务端归一化判重，返回 EXISTED 属预期（Step 3 漏判或归一化
  差异），不视为错误，如实汇报即可。
- **⚠️ 判门店「还在不在」勿用单查接口（2026-09-11 实测）**：`GET /venues/{id}` **不过滤软删**
  （已删行仍 200），会被误导成「未删除」；判存亡/判重口径 = 列表与同城加载
  （`GET /venues?city=` / `findByCityAndDeletedFalse`）或 `GET /venues?keyword=`——列表里消失
  即已软删。batch-create 判重同样按 deleted=false 过滤：同名旧档被软删后，重建会拿到新 ID
  （属正确行为，字典 venue_id 记得跟着改）。

## 状态写库

- **反转没生效**：后端只反转 `CEASED/SUSPENDED → OPEN`；平台已 OPEN 的条目静默跳过（正确行为）。
- **暂停没生效（status-suspend）**：后端只处理 `OPEN → SUSPENDED`；CEASED/CLOSED/RENOVATING 静默跳过。
  若「该暂停的没暂停」，先查它是否被误算进 `mentionedSet`（别名没归位 → 误以为上了榜），
  或该城根本不在 `coveredCities`（覆盖判定错）。
- **暂停规模异常大 / 小**：核对 `coveredCities` 与范围细化——舞讯里只有城市名却没名单的条目
  （「未有待更新」「待定」「无商家信息」）**绝不能**计入覆盖城市；反过来某城确有名单却漏进
  `coveredCities`，会漏掉该城应暂停的门店。
- **暂停要不要通知用户**：不用。后端状态真实变更会自动给关注者发站内信/订阅消息（既有语义）；
  但**不发数据更新公告**（公告只报新增/恢复）。大批量暂停时消息量较大，属预期。
- **status-reverse HTTP 500**：`confidence` 必须用后端枚举值 **EXACT/ALIAS/CONTAINED/FUZZY**，
  传 `HIGH` / `HIGH_CONFIDENCE` 等自定义值会 500（2026-09-02 实测；表① 用 EXACT，
  表② 用 CONTAINED + `forceReversal=true`）。
- **CLOSED 门店恢复营业（2026-09-04 帝境路径）**：CLOSED 不在批量反转范围（后端仅
  CEASED/SUSPENDED → OPEN），须走 `POST /venues/{id}/update` 全量回填（body=CreateVenueRequest，
  防 businessHours/经纬度被清成 null）。**全字段来源 = 公开接口 `GET /venues/{id}`（无需 token）**
  ——返回 name/status/city/district/address/longitude/latitude/businessHours 全量现值，
  把 status 换成 OPEN 后原样回填，再核验字段无丢失。
- **重复写库**：同一门店被重复提交（如与 Web 后台「门店同步」同日处理）时，状态以最后执行为准
  （`applyBatch` / `applyBatchSuspend` 幂等早退：非目标状态静默跳过）；新增以 batch-create 同城同名
  判重兜底（EXISTED），无锁冲突风险。

## 请求体与响应

- **批量新增裸数组报 5000**：请求体必须是 `{"items":[...]}` 包装，裸数组会让后端反序列化失败
  返回 **code=5000（不是 400，具有迷惑性）**（2026-09-07 实测）——优先走 `qw_api.py` 封装。
- **详情 GET 响应嵌套**：`GET /venues/{id}` 的字段在 `data.venue` 子对象，不在 data 顶层
  （2026-09-06 首轮 15 家全 FAIL「name/city 不能为空」即因此）。正确：`cur = data["venue"]`。
- **export 的 V25 三字段 null 时不出现在 JSON（2026-09-14 实测）**：`VenueExportItem` 声明了
  `statusSource` / `statusLockedUntil` / `syncNote`，但序列化配了 NON_NULL ⇒ 无人工改动/无锁/无备注
  时**整个 key 消失**（只有原始类型 `dailySyncExempt` 恒出现）。差异表标注时**不要**把「key 不存在」
  误判成「后端没实现这三个字段」；判据 = 有取值时才出现（`dailySyncExempt=true` 或 `statusLockedUntil` 非空）。
- **单源日的写库边界（2026-09-14 确立）**：用户转述的名单若与源站正文逐条一致 = **同一来源**，
  不计两源 ⇒ 全源一致门不满足 ⇒ 两个方向都只列清单。用户口头放行**只覆盖他明确点到的那几条**
  （例：只确认两家门店「营业中」+ 要求发公告 ≠ 同时放行暂停方向）。
- 统一响应包 `{code, message, data}`，`code=0` 成功；401 = token 过期，重新登录。

## 数据治理

- **多源交叉验证**（09-02 起推荐，09-10 收敛为「全源一致」）：用户给多份舞讯时先同城合并去重，
  各源**逐店**核对点名与否（Step 3B 的 M/S 判定）；注意「某源没有该城名单 = 弃权」，不要当成反对。
- **字典 `uncertain_entries` 段**（09-02 用户要求主动维护）：用户「不确定但暂按映射处理」或
  「攻略疑似写错」的条目，除 entries 登记外同时写入 `uncertain_entries`
  （`status`: `tentatively_mapped` / `misprint` / `unverified` / `user-confirmed-closed` /
  `resolved-notfound` + `reason` + `next_action`），后续舞讯出现时按 `next_action` 复核。
- **门店删除 / 清理重复（2026-09-08 紫茂 #381/#382 实证）**：后端**无门店删除 HTTP 接口**
  （已核对 `/admin/venue-sync/venues` 仅 export/batch-create；项目禁 PUT/DELETE），removed 重复数据
  由用户在管理后台/库内自理——Agent 识别到同名同址重复条目（两 venueId 同 district 同 address）时，
  **不代删、不自动反转**，呈「疑似重复」提示用户决策删除哪条，确认后由用户删除；
  字典 `removed_duplicates` 段仅登记用户已删确认的店（防下次舞讯再把删除份当新店）。

## `/venues?keyword=` 列表接口 500（2026-09-16 实证，未修，待后端裁决）

- **复现**：`GET /venues?keyword=金莎舞厅&city=东莞市&size=20` → **500**；不带 city 时 `keyword=金莎`
  在 **size≤11 → 200 / size≥12 → 必 500**（同请求连打 3 次全 500，确定性；`sort` 有无不影响）。
- **嫌疑单点**：库内含「金莎」11 家，列表能吐出前 11 家，**#716 金莎舞厅（东莞市·南城街道）**
  是唯一没被吐出的；其单店详情接口 `GET /venues/716` 正常 ⇒ 炸的是**列表路径**（列表独有逻辑：
  matchedHint 装配 / KW_MATCH 多字段分支）。东莞 district 是「南城街道/沙田镇」这类**街道/镇**，
  非「区/县」，`district.endswith("区")` 类判据都会走非常规分支。
- **后果**：用户在小程序搜「金莎」即报错；`qw_match.py` 的 UNMATCHED keyword 交叉验证
  （size=20）撞上必 500 —— **已改成单条容错**（落盘 `cross_check_error` + 汇总打印），不再中断整轮。
- **修复方向（待拍板）**：先抓 500 堆栈定位到行；重点查 matchedHint 装配对 `text=null` /
  非常规 district 的处理。
