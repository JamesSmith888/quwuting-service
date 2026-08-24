package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.dancer.enums.DancerServiceCategory;
import org.quwuting.quwutingservice.dancer.enums.DancerServiceSubCategory;

import java.util.List;

/**
 * 舞伴服务范围条目（详情页「服务范围」卡 / 需求弹层 chip 数据源，2026-08-24；
 * 2026-08-24 晚类别改版：新增子类别；2026-08-25 晚二轮：子类别<b>多选</b>——
 * subCategories/subCategoryLabels 列表，按枚举声明序，与 label 默认拼接序一致；
 * 2026-08-26：label 服务端权威派生 + 新增 negotiable）。
 * label 恒非空（消息拼接的唯一文案来源，服务端按类别派生）；subCategories 仅
 * PACKAGE 有值（空列表 = 非包时类别）；priceText/locationScope/advanceNotice/rules
 * 可为空串（未声明字段前端不渲染行）；negotiable = 回头客/熟人可谈（默认 true）。
 */
public record DancerServiceResponse(
        Long id,
        /** 服务类别（PACKAGE/DANCE/ONLINE_CHAT/OTHER） */
        DancerServiceCategory category,
        /** 类别默认标签（前端类别分组展示；与 label 不同源，区分语义） */
        String categoryLabel,
        /** 包时子类别列表（仅 PACKAGE 有值：BAR/DANCE_HALL/PRIVATE_CINEMA/KTV/OTHER，可多选） */
        List<DancerServiceSubCategory> subCategories,
        /** 包时子类别默认标签列表（「酒吧」等，与 subCategories 一一对应） */
        List<String> subCategoryLabels,
        /** 短标签（需求弹层 chip / 添加好友需求消息拼接的唯一文案来源；服务端权威派生） */
        String label,
        /** 服务价格/计费方式（如「300元/小时起」；空串 = 未声明） */
        String priceText,
        /** 服务地点范围（如「5KM左右」「本区舞厅」；空串 = 未声明） */
        String locationScope,
        /** 提前预约要求（如「提前 2 小时」；空串 = 未声明） */
        String advanceNotice,
        /** 服务规则和限制（如「不含酒水」；空串 = 未声明） */
        String rules,
        /** 回头客/熟人可谈（per-service，默认 true；价格可私下与舞伴协商，与平台无关） */
        boolean negotiable
) {}
