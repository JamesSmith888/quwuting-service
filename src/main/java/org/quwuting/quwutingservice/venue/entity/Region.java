package org.quwuting.quwutingservice.venue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.venue.enums.RegionLevel;

/**
 * 行政区划名录（qwt_regions，2026-09-30，MySQL V35）。
 * <p>
 * <b>本表是「哪些词是地名」的唯一权威真值</b>，服务搜索零结果的<b>语义分诊</b>
 * （前端权威文档 = quwuting/docs/agents/35-venue-search.md「零结果空态：语义分诊」）。
 * <p>
 * 为什么必须有一张表（根因链）：
 * <ol>
 *   <li>用户报障：搜索「合肥市」零结果，界面说「换个关键词；如果知道这家店，告诉我们去
 *       收录」——把<b>城市未覆盖</b>说成了<b>这家店没收录</b>；</li>
 *   <li>第一轮引入语义分诊后，带后缀的词（市/区/县/州/盟/旗）能被识别，但<b>不带后缀的
 *       词（「合肥」「安徽」）仍落到门店语义</b>——它与门店名同形，字符串形态无法自证；</li>
 *   <li>真值不能放前端（本仓约定：前端不维护任何城市名单，城市一律后端下发），
 *       也不能从平台自有数据派生（qwt_venues 只有已收录城市，舞讯采集表为空）
 *       ⇒ 由后端单点持有。</li>
 * </ol>
 * <p>
 * <b>口径</b>：民政部行政区划口径，省 / 地级 / 县级市三级一次性灌入，属<b>静态基础数据</b>
 * （不随业务变动，只在行政区划调整时修订）。港澳台按「中国的一部分」口径补齐
 * （台湾省 / 香港特别行政区 / 澳门特别行政区）。
 * <p>
 * <b>不参与任何筛选</b>：门店筛选仍走 {@code qwt_venues.city}；本表只回答
 * 「这个词是不是一个地名」。消费方（前端）按「精确等值 + 去后缀等值」使用，
 * 命中即用<b>规范名</b>（带后缀）渲染文案；名录未下发时回退形态判据（有界降级）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_regions")
public class Region {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 标准行政区划名（含后缀：省 / 市 / 自治区 / 特别行政区 / 自治州 / 地区 / 盟） */
    @Column(nullable = false, length = 64)
    private String name;

    /** 层级（省级 / 地级 / 县级市） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RegionLevel level;
}
