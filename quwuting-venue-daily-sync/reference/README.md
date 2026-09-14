# reference/ — 去舞厅每日舞讯 Skill 参考资料

主文件 `../SKILL.md` 已瘦身为「**红线 + 当前口径 + 操作步骤**」。历轮实证、踩坑、沿革全在这里。
按需查阅，**不要**把实证细节再写回主文件。

| 文档 | 内容 |
|---|---|
| `matching-playbook.md` | 城市名归一、判定优先级、归一化规则、UNMATCHED 两道兜底、别名回写、主名括号禁令 |
| `close-direction-playbook.md` | 关门方向范围细化三条规则、历轮暂停家数、单源覆盖城市预期、提交前自检清单 |
| `build-playbook.md` | 城市首覆判定、状态口径映射表、batch-create + 营业时间回填、字典回写 |
| `announcement-playbook.md` | 公告分级沿革、分发规则表、发布前检查、模板与大批量渲染、「确认营业门店」版式 |
| `troubleshooting.md` | 常见问题全集（拉取/登录、城市与匹配、状态写库、请求体与响应、数据治理） |
| `run-history.md` | 口径变更时间线、真实事故复盘、历轮跑批实录、已知未决项 |
| `xianbao360-venue-dict.json` | 数据源匹配字典：`entries`（已确认映射）/ `uncertain_entries`（存疑/定性）/ `removed_duplicates`（用户确认删除的重复份） |

脚本（`../scripts/`）：

| 脚本 | 用途 |
|---|---|
| `qw_api.py` | 后端 API 封装：`login` / `export` / `cities` / `batch-create` / `status-reverse` / `status-suspend` |
| `qw_match.py` | **Step 2+3 比对引擎**（2026-09-14 新增）：`mentions JSON` → 命中 JSON；别名域/字典/规则层/三道兜底齐备 |
| `qw_analyze.py` | **Step 3B + 五表分析**（2026-09-14 新增）：命中 JSON + 平台全量 → 表①/②/③/⑤ 清单 + 提交前自检；范围细化数据驱动（不手抄县级映射） |

## 维护约定

- **新增实证/踩坑 → 写进对应 reference 文档**（选不准就放 `run-history.md`），主文件只更新
  口径与步骤一句话 + 指针。
- **改完先写项目路径，再跑 `bash quwuting-service/scripts/sync-skills.sh`** 落镜像
  （单向 项目 → 运行时，详见 SKILL.md「双位置同步约定」）。
- 🔁 **改 `scripts/` 里任一脚本后必须做回归比对（2026-09-14 立）**：拿上一轮已人工核对过的
  `mentions JSON` + 当时的 export 快照重跑，比对「命中 venueId / 置信度 / 五表家数」是否与
  人工结论一致。09-14 实证价值：新写的 `qw_match.py` 回归后**多解出 2 条**（别名双写升 EXACT），
  说明双写闭环生效；若回归少了条目，就是改坏了。**没有历史快照时，先存一份 export 再改脚本。**
- 📸 **建议每轮留存输入快照**（`mentions JSON` + export）→ 这是回归测试与「下一轮回溯」的唯一依据。
- 📝 **每轮跑完补 `run-history.md` 跑批实录**（见 SKILL.md Step 5 末条）。
