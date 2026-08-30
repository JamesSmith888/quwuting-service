package org.quwuting.quwutingservice.recruitment.enums;

/**
 * 招工状态机：DRAFT →（发布）→ PUBLISHED →（手动下架）→ OFFLINE，可重新发布。
 * 过期不落状态——查询谓词 expires_at > now() 硬过滤（僵尸信息是信任杀手），
 * 过期记录保留（审计/举报回溯），管理端「已过期」视图 = PUBLISHED 且已过有效期，可一键续期。
 */
public enum RecruitStatus {

    DRAFT("草稿"),
    PUBLISHED("已发布"),
    OFFLINE("已下架");

    private final String label;

    RecruitStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
