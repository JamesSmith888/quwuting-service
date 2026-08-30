package org.quwuting.quwutingservice.recruitment.enums;

/**
 * 薪资类型（salary_text 缺省时的兜底文案来源，如「面议」）。
 */
public enum RecruitSalaryType {

    HOURLY("时薪"),
    DAILY("日薪"),
    MONTHLY("月薪"),
    COMMISSION("提成"),
    NEGOTIABLE("面议");

    private final String label;

    RecruitSalaryType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
