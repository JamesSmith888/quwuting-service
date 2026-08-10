package org.quwuting.quwutingservice.venuefeedback.enums;

/**
 * 信息纠错的目标字段（2026-08-10 新增，结构化纠错载荷的字段维度）。
 * <p>
 * 背景（根因）：门店数据经 OCR 批量导入，票价/电话/营业时间/地址等字段系统性
 * 错误，只能靠终端用户发现。旧上报载荷只有自由文本 {@code note}，把"哪里错了"
 * 与"正确值"两种语义混在一起，管理员无法机器可读地核对纠错建议。本枚举把
 * "哪个字段有误"结构化为受控词汇表——管理端按字段聚合核对、按字段跳转核实。
 * <p>
 * 词汇表契约：与前端 {@code MISSING_INFO_FIELDS} 的 data-key 词汇同源
 * （hours/contact/address/description/wechat/ticket/partner），并补充缺失场景
 * 不涉及的字段（name 门店名称——名称只可能"错"不可能"缺失"）与 OTHER 兜底。
 * 词汇覆盖详情页全部可见的可纠错字段；新增可纠错字段 = 两端各加一项，禁止
 * 散落硬编码。{@code field} 为可空列（非纠错场景（缺失/状态/其他）不填，
 * 由 {@code note} 承载说明）。
 * <p>
 * 与 FeedbackType 的关系：INACCURATE（信息有误）类型的结构化载荷 =
 * field（哪个字段）+ correctedValue（正确值）；其余类型无 field 语义。
 */
public enum FeedbackField {
    NAME("门店名称"),
    ADDRESS("地址"),
    HOURS("营业时间"),
    TICKET("门票价格"),
    PARTNER("舞伴费用"),
    CONTACT("联系电话"),
    WECHAT("微信联系"),
    DESCRIPTION("简介"),
    OTHER("其他信息");

    private final String displayName;

    FeedbackField(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
