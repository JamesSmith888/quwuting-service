package org.quwuting.quwutingservice.wxacode.service;

/**
 * 小程序码规格 —— 生成一份小程序码所需的<b>全部</b>输入量（2026-09-19，V30）。
 * <p>
 * <b>为什么需要这个值对象</b>：微信 {@code getwxacodeunlimit} 的产物完全由
 * 四元组 {@code (appId, page, scene, envVersion)} 决定——四维相同则码内容相同、
 * 四维不同则码内容不同。把"内容标识"从散落的字符串拼接收进一个不可变类型，
 * 使「缓存键 / 资产主键漏维度」从"人要记得拼全"变成"结构上拼不全就编译不过"。
 * <p>
 * 根因而非洁癖：本项目已两次因派生键漏输入维度出事——51 号（距离 memo 键只有
 * 场所 id，漏了用户坐标维度）与本域 V30（缓存键 {@code page|scene} 漏
 * {@code env_version}：把生产 {@code wechat.qrcode-env} 改成 trial 后 24h 内仍返回
 * release 旧码、配置改了却静默不生效）。两次同族 ⇒ 判据升格为结构约束：
 * <b>任何由多输入派生的键，其派生函数必须与输入清单同时存在于一处</b>。
 * <p>
 * 指纹用可读拼接而非哈希：运维可直接 {@code SELECT asset_key} 看懂一行是什么码，
 * 且长度有界（appId 18 + env 7 + page 40 + scene 32 + 分隔 ≈ 100 字符）。
 * 分隔符 {@code |} 的注入风险由紧凑构造器显式挡掉（拼接指纹只有单射才成立）。
 *
 * @param appId      小程序 appid（多小程序共用一库时靠它区分）
 * @param page       落地页路径（如 {@code pages/index/index}）
 * @param scene      scene 参数（微信硬限制 ≤32 字符，由调用方保证）
 * @param envVersion release / trial / develop（码打开的版本，随环境配置变化）
 */
public record WxacodeSpec(String appId, String page, String scene, String envVersion) {

    /** 指纹字段分隔符（各维度值禁止包含它，见下） */
    private static final char SEPARATOR = '|';

    /**
     * 紧凑构造器：四维非空 + 不含分隔符。
     * <p>
     * 这些值全部由本服务内部构造（配置常量 / 数字 id / 枚举字面量），
     * 不含分隔符是**编程前提**而非用户输入约束——违反即代码缺陷，
     * 故抛异常而非静默降级（拼接指纹只有单射才配当内容标识）。
     */
    public WxacodeSpec {
        requireClean(appId, "appId");
        requireClean(page, "page");
        requireClean(scene, "scene");
        requireClean(envVersion, "envVersion");
    }

    /**
     * 内容指纹 = 全维度拼接，同时充当内存缓存键与资产表主键。
     * <p>
     * 顺序固定为 appId | envVersion | page | scene（env 前置便于同页多环境的行
     * 在 {@code ORDER BY asset_key} 下相邻）。此方法是指纹的<b>唯一</b>派生处：
     * 新增输入维度只改这里，缓存与资产自动同步（不存在"两处各拼一遍必漏一处"）。
     */
    public String fingerprint() {
        return appId + SEPARATOR + envVersion + SEPARATOR + page + SEPARATOR + scene;
    }

    /** 维度值校验：非空且不含分隔符（拼接单射的前提） */
    private static void requireClean(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("小程序码规格 " + field + " 不可为空");
        }
        if (value.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException("小程序码规格 " + field + " 不可含分隔符 " + SEPARATOR);
        }
    }
}
