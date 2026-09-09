package org.quwuting.quwutingservice.spend.dto;

import java.util.List;

/**
 * 批量同步请求（POST /spend/entries/sync）。单次上限 200 条（客户端 FIFO 分批），
 * 部分成功不整体回滚——逐条校验，非法条目跳过计数（rejected），客户端下次整批重放
 * 仍幂等（合法条目已收敛，重放无副作用）。
 */
public record SpendSyncRequest(
        List<SpendEntryItem> entries
) {
}
