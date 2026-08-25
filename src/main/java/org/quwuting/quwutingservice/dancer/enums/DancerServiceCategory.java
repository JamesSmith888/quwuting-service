package org.quwuting.quwutingservice.dancer.enums;

import org.quwuting.quwutingservice.exception.BusinessException;

/**
 * 舞伴服务类别（2026-08-24 晚服务范围改版；2026-08-25 晚二轮：删除 BAR——
 * 酒吧不再单列类别，收编为按时段子类别；2026-08-26：label 改服务端权威派生——
 * 仅 OTHER 允许手动录入服务内容；2026-08-26 合规用词：包时→按时段、
 * 线上陪聊→线上聊天、私影→影咖——避开微信审核灰产敏感词）。
 * <ul>
 *   <li>{@link #PACKAGE} 按时段（大类，必带 ≥1 个子类别 {@link DancerServiceSubCategory}：
 *       酒吧 / 舞厅 / 影咖 / KTV / 其他，可多选；计费快捷 200/300/400）</li>
 *   <li>{@link #DANCE} 舞厅跳舞</li>
 *   <li>{@link #ONLINE_CHAT} 线上聊天（原 ONLINE，2026-08-26 由「线上陪聊」改名）</li>
 *   <li>{@link #OTHER} 其他（具体内容由 admin 在 label 手动录入，如「户外露营」）</li>
 * </ul>
 * 类别默认标签 = 需求弹层 chip 与服务范围卡的缺省展示名；服务短标签 label 由
 * 服务端按类别权威派生（PACKAGE = 子类别名顿号连接+「按时段」，DANCE/ONLINE_CHAT =
 * 类别名，仅 OTHER = 手动录入的服务内容）——消息拼接恒用服务自身的 label。
 * 解析非法值抛 1001（枚举列无 CHECK 约束，应用层防御，同 DancerPhotoStatus 模式）。
 */
public enum DancerServiceCategory {

    /** 按时段（大类，子类别多选：酒吧/舞厅/影咖/KTV/其他；默认标签） */
    PACKAGE("按时段"),
    /** 舞厅跳舞 */
    DANCE("舞厅跳舞"),
    /** 线上聊天 */
    ONLINE_CHAT("线上聊天"),
    /** 其他场景（label 手动录入具体内容） */
    OTHER("其他");

    private final String defaultLabel;

    DancerServiceCategory(String defaultLabel) {
        this.defaultLabel = defaultLabel;
    }

    /** 类别默认标签（展示名/消息拼接缺省名） */
    public String defaultLabel() {
        return defaultLabel;
    }

    /** 解析类别代码（非法值 → 1001「无效的服务类别」） */
    public static DancerServiceCategory parse(String code) {
        try {
            return DancerServiceCategory.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(1001, "无效的服务类别");
        }
    }
}
