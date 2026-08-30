package org.quwuting.quwutingservice.recruitment.enums;

/**
 * 合作周期（对标真实招工样例「合作周期：长期」）。
 */
public enum RecruitTerm {

    LONG_TERM("长期合作"),
    SHORT_TERM("短期"),
    NEGOTIABLE("面议");

    private final String label;

    RecruitTerm(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
