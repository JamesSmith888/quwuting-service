package org.quwuting.quwutingservice.venue.enums;


/**
 * 门店类型（2026-09-13 新增「歌友会」品类，见 docs/agents/04-venue-domain.md）。
 * <p>
 * 语义 = 名录的分类维度（用户筛选门店的口径），与营业状态 {@link VenueStatus} 正交：
 * 状态管"现在开不开"，类型管"它是什么"。初始值三档（舞厅 / KTV / 歌友会），
 * 后续扩展只需在此加枚举值（列表筛选、管理端表单、前端快捷筛选均由此驱动）。
 * <p>
 * <b>地址可见性由类型派生（本设计的核心约束）</b>：
 * <ul>
 *   <li>{@code cityOnlyAddress=true}（当前仅歌友会）：门店<b>不落精确地址</b>，
 *       公开响应只下发到城市级（district / address / 经纬度 一律剥离），
 *       用户若想前往须<b>联系获取</b>——私密聚拢型场所不公开门牌是业务前提，
 *       平台连"存了再藏"都不做（存了就有泄漏面）。</li>
 *   <li>{@code cityOnlyAddress=false}（舞厅 / KTV）：常规完整地址 + 导航 + 距离排序。</li>
 * </ul>
 * 派生而非另设开关：一个类型是否公开地址是它的<b>固有性质</b>，新增类型时必须
 * 显式回答这个问题（编译期强制），避免"加了类型却忘了它该不该藏地址"。
 */
public enum VenueType {

    /** 舞厅（默认，存量门店全量回退值） */
    HALL("舞厅", false),

    /** KTV */
    KTV("KTV", false),

    /** 歌友会：私密聚拢型，地址只到城市，前往需联系获取 */
    SONG_CLUB("歌友会", true);

    private final String displayName;

    /** 是否只公开到城市级地址（剥离 district / address / 经纬度） */
    private final boolean cityOnlyAddress;

    VenueType(String displayName, boolean cityOnlyAddress) {
        this.displayName = displayName;
        this.cityOnlyAddress = cityOnlyAddress;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 是否只公开到城市级地址。
     * <p>
     * 判据：该类型的场所没有可供公开的固定门牌（私密/流动场地），公开精确地址
     * 既无意义也侵害经营方隐私。true 时 {@code VenueResponseMapper} 必须在
     * 公开响应中剥离精确地址字段（唯一脱敏闸门，命中缓存的是脱敏后副本）。
     */
    public boolean isCityOnlyAddress() {
        return cityOnlyAddress;
    }

    /**
     * JPQL 全限定枚举字面量列表（供 {@code VenueRepository} 的 {@code @Query} 常量拼接）。
     * <p>
     * 用途：半径谓词对"城市级类型"放行——歌友会无坐标，距离表达式为 NULL，默认 300km
     * 可达圈会把它们静默过滤掉（用户明确要求：300km 内永远包含歌友会）。
     * <p>
     * <b>为什么是字面量常量而不是从 {@link #isCityOnlyAddress()} 派生</b>：
     * 注解元素值必须是<b>编译期常量表达式</b>，而方法调用 / {@code Class#getName()}
     * 都不是常量——用派生写法会让 {@code @Query} 整体失去常量性，编译直接失败
     * （"element value must be a constant expression"）。故此处保留字面量。
     * <p>
     * <b>维护契约（无法编译期强制，注释即契约）</b>：新增 {@code cityOnlyAddress=true}
     * 的类型时，<b>必须同步把它的全限定字面量加进本常量</b>，否则该类型会被默认
     * 300km 可达圈静默过滤（无坐标 ⇒ 距离 NULL ⇒ 谓词为假）。
     */
    public static final String CITY_ONLY_HQL_IN_LIST =
            "org.quwuting.quwutingservice.venue.enums.VenueType.SONG_CLUB";
}
