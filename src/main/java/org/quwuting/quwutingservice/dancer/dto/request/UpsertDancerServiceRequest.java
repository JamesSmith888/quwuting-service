package org.quwuting.quwutingservice.dancer.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.dancer.enums.DancerServiceCategory;
import org.quwuting.quwutingservice.dancer.enums.DancerServiceSubCategory;

import java.util.List;

/**
 * 管理端服务范围录入请求（2026-08-24，admin 直发 = 黄页内容平台代发模型；
 * 2026-08-24 晚类别改版：类别平铺快捷选择 + 包时子类别；2026-08-25 晚二轮：
 * 子类别<b>多选</b> + 新增 KTV/其他；类别删除酒吧；2026-08-26：label 改
 * <b>服务端权威派生</b>——仅 OTHER 传手动录入的服务内容 + 新增 negotiable）。
 * <ul>
 *   <li>{@code category} 必填服务类别（PACKAGE 包时 / DANCE 舞厅跳舞 /
 *       ONLINE_CHAT 线上陪聊 / OTHER 其他）；</li>
 *   <li>{@code subCategories} 包时子类别（仅 category=PACKAGE 必填 ≥1：
 *       酒吧/舞厅/私影/KTV/其他，可多选；其余类别忽略）；</li>
 *   <li>{@code label} 短标签（2026-08-26 起服务端权威派生：PACKAGE = 子类别名
 *       顿号连接+「包时」，DANCE/ONLINE_CHAT = 类别名；仅 OTHER 类别必填 =
 *       admin 手动录入的「服务内容」，如「户外露营」，其余类别传空串即可）；
 *       同舞伴下唯一（库内部分唯一索引兜底，SQLState 23505 → 1001 冲突提示）；</li>
 *   <li>{@code priceText/locationScope/advanceNotice/rules} 可选（空串 = 未声明，
 *       详情页不渲染对应行）；</li>
 *   <li>{@code negotiable} 回头客/熟人可谈（per-service 开关，缺省 true =
 *       价格可私下与舞伴协商，与平台无关；详情页服务卡「可谈」行展示）；</li>
 *   <li>{@code sortOrder} 展示顺序（缺省 0；同舞伴内递增）。</li>
 * </ul>
 */
public record UpsertDancerServiceRequest(
        @Size(max = 20, message = "服务内容最长 20 字")
        String label,

        @NotNull(message = "服务类别不能为空")
        DancerServiceCategory category,

        /** 包时子类别（仅 PACKAGE 必填 ≥1，可多选；其余类别忽略） */
        @Size(max = 5, message = "包时子类别最多 5 项")
        List<DancerServiceSubCategory> subCategories,

        @Size(max = 100, message = "计费方式最长 100 字")
        String priceText,

        @Size(max = 100, message = "地点范围最长 100 字")
        String locationScope,

        @Size(max = 100, message = "预约要求最长 100 字")
        String advanceNotice,

        @Size(max = 300, message = "服务规则最长 300 字")
        String rules,

        /** 回头客/熟人可谈（per-service 开关，缺省 true；价格可私下与舞伴协商，与平台无关） */
        Boolean negotiable,

        Integer sortOrder
) {}
