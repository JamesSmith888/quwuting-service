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

## 维护约定

- **新增实证/踩坑 → 写进对应 reference 文档**（选不准就放 `run-history.md`），主文件只更新
  口径与步骤一句话 + 指针。
- 改完先写**项目路径**，再整体 `cp -R` 到 `~/.workbuddy/skills/quwuting-venue-daily-sync/`
  （详见 SKILL.md「双位置同步约定」）。
