package org.quwuting.quwutingservice.appfeedback;

/**
 * 平台级意见反馈分类（2026-08-28 新增，意见反馈页类型 chips 的权威源）。
 * <p>
 * 与门店维度上报（venuefeedback FeedbackType）独立：本枚举面向"对整个小程序的
 * 意见"，语义 = BUG（遇到的问题）/ 功能建议 / 夸奖鼓励 / 其他，四类覆盖用户
 * 表达的全部动机（低门槛设计：分类即结构化，文字可空，用户零动力也能一键提交）。
 * <p>
 * 新增分类 = 本枚举追加值 + 前端 types/appFeedback.ts 同步（label 双端镜像，
 * 展示文案以后端 categoryDisplay 为权威源）。
 */
public enum AppFeedbackCategory {
    /** 遇到了 BUG（页面异常/功能失效/数据错误等） */
    BUG("遇到的问题"),
    /** 功能建议（希望新增/改进的功能） */
    SUGGESTION("功能建议"),
    /** 夸奖鼓励（觉得好用/想鼓励运营） */
    PRAISE("夸奖鼓励"),
    /** 其他（以上都不符合的意见） */
    OTHER("其他");

    private final String displayName;

    AppFeedbackCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
