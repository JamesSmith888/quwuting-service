package org.quwuting.quwutingservice.recruitment.enums;

/**
 * 性别限定（中性维度；「限女」是舞伴招工高频真实约束，ANY 不下发不渲染）。
 */
public enum RecruitGenderLimit {

    ANY("不限"),
    MALE("男"),
    FEMALE("女");

    private final String label;

    RecruitGenderLimit(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
