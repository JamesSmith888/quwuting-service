package org.quwuting.quwutingservice.dancer;

/**
 * 舞伴标签字典（后台维护，不允许用户自由创建——与 ReactionCode / RatingDimensions 同模式）。
 * <p>
 * 标签来源 = 用户<b>认可行为</b>：认可舞伴时从本字典勾选（每次最多
 * {@link org.quwuting.quwutingservice.dancer.service.DancerService#MAX_TAGS_PER_RECOGNITION} 个），
 * 舞伴主页标签云 = 全部认可记录携带标签的聚合计数。
 * <p>
 * <b>全部为正向信号（刻意设计）</b>：舞伴是真实个人，负向标签（如"态度差"）属于对个人的
 * 公开负面评价，存在诽谤/骚扰风险且难以核验——负面体验应走场所 feedback 等既有通道，
 * 不在个人主页开放。本字典只表达"认可的理由"，与产品定位（用户认可、非打赏/排行）一致。
 * <p>
 * emoji/label 是本字典的唯一来源（无管理后台 UI），后续新增/调整只需修改本枚举，
 * 前端通过接口返回的 emoji/label 自动同步（前端静态字典 constants/dancer-tags.ts 需同步镜像，
 * 见前端 AGENTS.md「舞伴生态体系」章节）。
 */
public enum DancerTagCode {
    DANCE("💃", "舞姿优秀"),
    EASY_TALK("😊", "容易交流"),
    GOOD_VIBE("🔥", "氛围感强"),
    BEGINNER_FRIENDLY("🌟", "新手友好"),
    PATIENT("🤝", "耐心带舞"),
    GENTLE("🎩", "有风度"),
    FUNNY("😄", "风趣幽默"),
    PUNCTUAL("⏰", "守时靠谱");

    private final String emoji;
    private final String label;

    DancerTagCode(String emoji, String label) {
        this.emoji = emoji;
        this.label = label;
    }

    public String getEmoji() {
        return emoji;
    }

    public String getLabel() {
        return label;
    }

    /** 校验字符串是否为合法的舞伴标签代码，避免 valueOf 抛出未受控异常 */
    public static boolean isValid(String tag) {
        if (tag == null) return false;
        for (DancerTagCode value : values()) {
            if (value.name().equals(tag)) return true;
        }
        return false;
    }
}
