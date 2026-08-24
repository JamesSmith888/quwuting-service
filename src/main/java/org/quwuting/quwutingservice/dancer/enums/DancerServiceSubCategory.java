package org.quwuting.quwutingservice.dancer.enums;

import org.quwuting.quwutingservice.exception.BusinessException;

/**
 * 包时子类别（2026-08-24 晚服务范围改版；2026-08-25 晚二轮：新增 KTV/其他 +
 * <b>多选</b>，qwt_dancer_services.sub_category 存逗号连接的枚举 code 串）。
 * 仅类别 {@link DancerServiceCategory#PACKAGE 包时} 有意义——包时为"按小时包场"
 * 大类，子选项 = 具体场景：酒吧 / 舞厅 / 私影 / KTV / 其他，<b>可多选</b>（一条
 * 服务可覆盖多个包时场景，如「酒吧、KTV包时」）。label 短标签默认名 = 子类别名
 * 顿号连接 + 「包时」，admin 可自定义覆盖。
 * <p>
 * 复用旧类别码 DANCE_HALL/PRIVATE_CINEMA（历史数据迁移平滑映射）；解析非法值
 * 抛 1001（枚举列无 CHECK 约束，应用层防御，同 DancerPhotoStatus 模式）。
 */
public enum DancerServiceSubCategory {

    /** 酒吧 */
    BAR("酒吧"),
    /** 舞厅 */
    DANCE_HALL("舞厅"),
    /** 私影 */
    PRIVATE_CINEMA("私影"),
    /** KTV */
    KTV("KTV"),
    /** 其他（label 自定义具体内容） */
    OTHER("其他");

    private final String defaultLabel;

    DancerServiceSubCategory(String defaultLabel) {
        this.defaultLabel = defaultLabel;
    }

    /** 子类别默认标签（「酒吧」等；包时默认服务名 = 子类别名顿号连接 + 包时） */
    public String defaultLabel() {
        return defaultLabel;
    }

    /** 解析子类别代码（非法值 → 1001「无效的包时子类别」） */
    public static DancerServiceSubCategory parse(String code) {
        try {
            return DancerServiceSubCategory.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(1001, "无效的包时子类别");
        }
    }
}
