package org.quwuting.quwutingservice.venuepresence.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.spend.enums.WireEnums;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.user.service.UserCode;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.enums.VenueType;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuepresence.dto.request.ReportPresenceRequest;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminUserConsentResponse;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminUserTrackResponse;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminUserVisitRecord;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminUserVisitsResponse;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminUserVisitsResponse.AdminUserVisitVenueGroup;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminVenueVisitorItem;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminVenueVisitorPage;
import org.quwuting.quwutingservice.venuepresence.dto.response.PresenceReportResponse;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceConsentStats;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenuePresenceStats;
import org.quwuting.quwutingservice.venuepresence.entity.VenuePresenceConsent;
import org.quwuting.quwutingservice.venuepresence.enums.CoLocatedAttribution;
import org.quwuting.quwutingservice.venuepresence.enums.ConsentSource;
import org.quwuting.quwutingservice.venuepresence.enums.PresenceConsentState;
import org.quwuting.quwutingservice.venuepresence.enums.PresenceTrackGrade;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresenceConsentRepository;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 门店到访痕迹服务（2026-09-29，V33；文档 = docs/agents/52-venue-presence.md）。
 * <p>
 * <b>定位</b>：一行 ping = 一次「可证实的到店事实」，只服务 admin 展示与后续打标，
 * <b>不进热度公式</b>（到店数天然随曝光增长，线性进公式即马太闭环——热度四问判据
 * 第 3 问不过；待数据量起来后按 05 号文档流程另行评估）。
 * <p>
 * <b>写宽松读严格</b>：写入侧只做协议限幅（防脏数据），「到访 / 附近 / 同址 / 附近展示 / 营业归因」口径
 * （{@link #HIT_RADIUS_M} / {@link #HIT_MAX_ACCURACY_M} / {@link #NEARBY_RADIUS_M} /
 * {@link #CO_LOCATED_RADIUS_M} / {@link #NEARBY_TRACE_RADIUS_M} / {@link #isInOperation}）全部在查询侧判定——门店坐标是地址级
 * 地理编码（同楼多店坐标重合，室内偏离 50~150m），阈值定错时历史数据可回溯（2026-10-03 即据此回溯修复）。
 * <p>
 * <b>同意门禁（2026-10-03 五轮）</b>：只收「最新一条状态确立是用户显式开启」的用户的 ping
 * （{@link #isExplicitlyEnabled}）——服务端是「先有同意、后有足迹」证据链的唯一收敛点，
 * 旧版默认开启端未经询问的采集在上线即被拒收，不依赖端上升级覆盖率（52 号 §5）。
 * <p>
 * <b>隐私形态（2026-10-08 V46 修订）</b>：本域数据面 = (venueId, distanceM, accuracyM)
 * 三个标量 + 定位快照坐标（gcj02，2026-10-08 起随行）+ 开关偏好布尔（V34 consent 流水）。
 * 原「坐标在协议上不存在」的红线经用户裁决修订为「主动同意 + 用途限定」形态：
 * 采集门禁不变（仅显式同意用户），用途限定 = admin 内部统计与轨迹分析，同意文案
 * 四处已同批改真；见 V46 迁移头注与 52 号文档。
 * <p>
 * <b>信任边界</b>：distance_m 是端侧自报值，服务端不复算——V46 后坐标虽已随行
 * （复算在技术上可行），但复算会让新旧行口径不同源，属独立决策，暂不做。
 * 防刷面 = 伪造 distance 刷「到访」；缓解 = 15 分钟桶幂等（本表唯一约束）+ 每用户
 * 滑动窗口频控 + admin 侧距离分布观察。完整论证见 52 号文档 §「信任边界」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenuePresenceService {

    /*
     * 采集总开关（常量定义在 OpsConfigService#KEY_PRESENCE_COLLECT_ENABLED，
     * V33 迁移插入默认行 true）：关闭后上报端点返回 accepted=false(DISABLED)
     * 而非报错——客户端对失败静默，开关只影响「新数据是否进库」，历史数据不受影响。
     * 提审/隐私争议时可一键停采。
     */

    /**
     * 写幂等桶宽度（分钟，协议常量）：采样主力 = onShow（每次打开一次），典型会话
     * 数分钟级；15 分钟 ≈ 会话粒度上界，桶内重复 onShow 一律吸收。调整需发版
     * （唯一键语义随桶宽变化，运行期改宽会出现「同桶已写、新桶再写」的口径漂移）。
     */
    public static final int WRITE_WINDOW_MINUTES = 15;

    /**
     * 写入距离限幅（米，协议常量）：与 /venues/nearby 的采集半径同量级——超出
     * 500m 的「命中」不可能是真到店（更可能是端侧伪造或逻辑错），拒收防脏数据。
     * 口径过滤（150m/300m）在查询侧，本限幅只是写侧卫生。
     */
    public static final int WRITE_MAX_DISTANCE_M = 500;

    /** 写入精度限幅（米，协议常量）：wx 端 accuracy 常见 5~65m，>500m 的定位无判定价值 */
    public static final int WRITE_MAX_ACCURACY_M = 500;

    /**
     * 到访命中半径（米，口径参数 = 「用户 ↔ 门店坐标」的容差）。
     * <p>
     * <b>2026-10-03 由 20m 改为 150m（根因修复，52 号 §4.1）</b>：原 20m 把两个不同的量
     * 混成了一个——它的论据「20m 内有邻居的店仅 5.4%」是<b>门店 ↔ 门店</b>间距统计
     * （回答「归因唯一吗」），却被用作<b>用户 ↔ 门店</b>的命中容差（回答「人在店里时
     * 离坐标多远」），而后者的误差预算完全不同：
     * <ul>
     *   <li>门店坐标是<b>地址级地理编码</b>（商场/大楼的锚点），不是原假设的
     *       「wx.chooseLocation 人工选点 10~30m」——同楼多店坐标完全重合即为铁证；</li>
     *   <li>室内定位（商场 3 层、无 GPS 直视）的实际偏离远大于 wx 回报的 accuracy。</li>
     * </ul>
     * 现网证据（截至 2026-10-03 共 21 条 ping）：需求方 10-02 夜在南通五洲国际广场现场
     * 连续 4 条均为 86~91m，其余疑似在店样本 56~147m；20m 口径只留下 3 条（2 个 UV），
     * <b>到访统计几乎全 0</b>。
     * 距离分布在 147m 与 159m 之间出现自然断点（之后是 202m / 301m 的路过样本），
     * 且 150m 落在 Android 地理围栏官方建议下限 100~150m 区间内。
     * <p>
     * 写宽松读严格 ⇒ 改值对历史 ping 立即回溯生效，无需重采。复核方法（数据量起来后
     * 按此重标定，禁凭直觉改）：见 52 号 §4.3 标定 SQL。
     * <p>
     * <b>镜像</b>（改值三处同改，52 号 §4 参数表）：admin-web {@code PRESENCE_HIT_RADIUS_M}（列表口径
     * 提示）、小程序 {@code ARRIVAL_PROMPT_RADIUS_M}（「真正到店」才首问的触发半径）。
     * <p>⚠️ C 侧「附近的足迹」展示取数自 2026-10-09 起已改用 {@link #NEARBY_RADIUS_M}（300m）——
     * 本值仍是「命中 / 到访」判定与上述两处镜像的唯一来源，⛔ 勿据展示口径回改本值。
     */
    public static final int HIT_RADIUS_M = 150;

    /**
     * 到访命中的精度门槛（米）：<b>派生于 {@link #HIT_RADIUS_M}，不是独立口径参数</b>。
     * 判据 = 定位自身的不确定半径大于判定圆时，这次定位在物理上就无法回答「在不在
     * 圈内」——门槛只该排除这类「无判定能力」的定位。
     * <p>
     * 原值 30m（「超过即城市级误差」）是错误前提：wx 端室内 accuracy 常见 30~65m
     * （iOS 无 GPS 时回报 65、Android 常见 35），30m 门槛恰好系统性剔除了最该被
     * 统计的室内样本。NULL 精度视为达标（判据见 Repository 口径注释）。
     */
    public static final int HIT_MAX_ACCURACY_M = HIT_RADIUS_M;

    /**
     * 同址半径（米，口径参数 = 「门店 ↔ 门店」的<b>定位不可分辨</b>距离；2026-10-03 新增，同日 20 → 50m）。
     * <p>
     * 两家店坐标间距 ≤ 本值 ⇒ 手机定位判断不了用户在哪一家，归因改用定位以外的事实（营业状态，
     * 52 号 §4.4），分不出时共享（组内用户并集）——而不是让 nearby 的「最近一家」替用户掷骰子。
     * 这里的「同址」= <b>定位上分不开</b>，不等于同一门牌：同楼不同层、同楼不同门牌、紧邻的两栋楼，
     * 对归因是同一个问题。
     * <p>
     * <b>20 → 50m 的根因（52 号 §4.2，勿重蹈）</b>：20m 的前提「同楼的店坐标重合；20~50m 已进入定位可分辨
     * 的量级」只对<b>同一地址字符串</b>编码出的坐标成立（丽莎 / 一壶淡泊逐位相同）。同一栋楼用不同写法的地址
     * 编码时锚点会散开：南通京扬广场「寻梦缘（校西路…京扬广场2楼）/ 抖舞（人民中路209号京扬数码城B座）/
     * 南来北往（人民中路209号）」实际同层、寻梦缘与抖舞面对面不到 10m，坐标却两两相距 27 / 39 / 44m——
     * 20m 把它们当成三家可区分的店，在楼里的用户（331：距南来北往坐标 20m、精度 15m）被记给已停业的
     * 南来北往。与命中半径 20m 是同一类错误：阈值的前提从未对数据检验过。
     * <p>
     * <b>标定（现网 1301 家有坐标门店，2026-10-03，复核 SQL = 52 号 §4.3 ④）</b>：
     * <ul>
     *   <li>地址指向同一楼 / 商场、且至少一家在营的门店对，坐标间距 27~44m（京扬 27/39/44、富江商业广场 32、
     *       书院万达 44）⇒ 取上界向上到 10m 档 = 50m；</li>
     *   <li>同楼散布的长尾（65~92m：阳光天地、力宝广场、杉杉 IN 象、联盛广场）目前<b>双方都不在营</b>，
     *       不影响归因——有在营门店落进这一段时按 §4.3 ④ 重标定；</li>
     *   <li>代价 = 都在营、只能共享的门店对：20m 2 对 → 50m 5 对；按营业状态即可分清的对 15 → 22。
     *       100m 会共享 12 对，多为确实不同楼的邻居，故不取。</li>
     * </ul>
     * 不变量：本值 &lt; {@link #HIT_RADIUS_M}（两个量回答的问题不同，见命中半径注释）。
     */
    public static final int CO_LOCATED_RADIUS_M = 50;

    /**
     * C 侧「附近的足迹」展示半径（米，<b>展示口径</b>参数 = 「门店 ↔ 门店」的<b>声明不可分辨</b>
     * 距离；2026-10-08 新增，只服务 {@link #nearbyVisitSummaries}）。
     * <p>
     * 语义：两家店相距 ≤ 本值 ⇒ C 侧「到店足迹」行把它们的证据视作「同一片附近」——
     * 范围内<b>所有</b>门店（含停业店）显示同一句「感谢 N 位舞友 · N 次到过这附近的足迹」。
     * 它回答的问题是「我们敢不敢替系统说这些足迹属于哪家店」——<b>不敢</b>
     * （经纬度距离对线下用户在哪家店没有判定力），所以不归属、只声明「附近」。
     *
     * <h3>⛔ 为什么不是 {@link #CO_LOCATED_RADIUS_M}（=50m）：两个问题两个值，禁合并</h3>
     * <ul>
     *   <li><b>50m（同址）管排序分摊公平</b>——「手机分不开」才共享/让渡，紧了才对
     *       （同楼双吃是排序事故）；</li>
     *   <li><b>150m（附近展示）管展示声明诚实</b>——「分不清哪家」就都说「附近」，松了才不冤枉：
     *       漏显示一家 = 该看到的贡献者看不到（2026-10-08 事故正是这种），
     *       多显示一家只多一句「附近」（声明强度极低，代价可忽略）。
     *       用户裁决原话：「我们没有承诺用户百分之百是这家店，我们只是承诺是附近」。</li>
     * </ul>
     * 与 {@link #HIT_RADIUS_M} 数值相同但<b>语义独立</b>（那是 用户↔门店 的命中容差，本值是
     * 门店↔门店 的展示半径）；数值趋同是标定巧合，⛔ 不得互相派生、各自重标定。
     *
     * <h3>标定（2026-10-08 生产只读，1,714 家在库门店；方法 = 52 号 §4.3 ④ 同款）</h3>
     * <ul>
     *   <li><b>触发事故</b>：南通五洲国际广场「一壶淡泊（F2）/ 丽莎（3 层）」地址级编码锚点相距
     *       <b>95m</b>——坐标维护（单店更正）后脱离 50m 同址组，全部历史足迹的展示从丽莎
     *       「移动」到一壶淡泊（用户报障「算到一壶淡泊头上了」）；</li>
     *   <li><b>同综合体/同楼对的上界实测 146m</b>（嘉兴桐乡新世界广场 990↔1156；柳州声福国际
     *       1253↔1254 = 141m）——同商场多店经不同地址写法编码后可散布百余米，50m/100m 均漏；</li>
     *   <li>100~150m 段同时混有确实不同楼的邻居（如张家口 1324↔1329 = 117m）——单一距离无法
     *       完全分开「同楼/不同楼」，但展示口径只声明「附近」，对此不敏感（宁多勿漏）；</li>
     *   <li><b>不取 300m</b>（{@link #NEARBY_RADIUS_M}，采集准入的「片区」）：那会把整个商圈的
     *       门店合成一个展示单元（150~300m 段全量上百对），功能退化成"商圈徽章"。</li>
     * </ul>
     * 改值影响面：只影响 C 侧「到店足迹」行；<b>不影响</b>排序（{@link #CO_LOCATED_RADIUS_M} /
     * {@link #visitSharesForRanking}）、admin 归因（{@code attributionsFor}）与邻近提示等任何既有口径。
     */
    public static final int NEARBY_TRACE_RADIUS_M = 150;

    /**
     * 「附近」覆盖半径（米，口径参数）：对齐 GET /venues/nearby 的缺省 300m——
     * 同一「附近」语义在采集与统计两侧共用一个值，避免第二份真值。
     * <p>
     * <b>2026-10-09 起兼任 C 侧「附近的足迹」展示取数半径</b>（用户裁决）：展示数字 =
     * {@link #NEARBY_TRACE_RADIUS_M} 邻域（门店↔门店 ±150m）内各店 <b>≤ 本值</b> 的足迹并集——
     * 原取数只到命中带（150m），150~300m「附近」段被截掉（判例 = user 210 在丽莎 295m 上报，
     * 「唯一想看的样本」既不进到访也不进徽章）。⛔ 与「共享圈不取 300m」不矛盾：被禁的是把
     * 门店邻域扩到 300m（会把商圈合成一个展示单元）；这里扩的是「人↔店」是否算「这附近」的
     * 距离带，两层正交（标定论证见 52 号 §4.5）。
     */
    public static final int NEARBY_RADIUS_M = 300;

    /**
     * 「在营」状态集（2026-10-03，52 号 §4.4 营业状态消歧）：可被到访归因的门店状态。
     * <p>
     * 判据 = 「人此刻可能在这家店里」：OPEN 显然；CLOSED（休息中）是<b>短期态</b>（今天不开 ≠ 这家店
     * 不存在），按在营处理——否则一次休息日就把整个 30 天窗口的证据让给邻居。RENOVATING / SUSPENDED /
     * CEASED 是长期不在营：同址仍有在营门店时，该位置的证据不可能属于它们。
     * <p>
     * 已知局限（接受）：按<b>当前</b>状态归因、不按 ping 当时的状态——门店状态变更低频，且由每日同步
     * 维持新鲜度；查询侧归因让状态更正（如 CEASED→OPEN 反转）立即回溯生效。状态数据本身失真时归因随之
     * 失真，admin 详情页同屏展示同址门店状态，运营可据此识别。
     */
    private static final Set<VenueStatus> IN_OPERATION_STATUSES = EnumSet.of(VenueStatus.OPEN, VenueStatus.CLOSED);

    /** 每用户写频控（次/窗口，协议常量）：桶幂等已限 (user, venue) 粒度，此处限用户总写入速率 */
    private static final int WRITE_RATE_LIMIT = 10;
    private static final long WRITE_RATE_WINDOW_MS = 60_000L;

    /**
     * 排序口径的到访窗口（天，2026-10-06 V38）：与热度公式其余各项同窗（近 30 天滚动）。
     * 改本值必须与 V38 注释 / 52 号文档 / {@code VenueHeatWeights.VISIT} 论证同步。
     */
    private static final int RANKING_WINDOW_DAYS = 30;

    /** 展示用的近 7 天窗口（天）：物化表同列下发，**不进公式**（避免第二处时间项） */
    private static final int VISIT_RECENT_WINDOW_DAYS = 7;

    /**
     * 排除集合为空时的哨兵（2026-10-06）：{@code userId NOT IN :excludedUserIds} 在空集合上
     * 会生成 {@code NOT IN ()} 语法错误。公式侧由 {@code HeatAccountExclusionService} 恒非空保证，
     * 本类不依赖该服务（分层），故自带同款防御——哨兵是负数 id，任何真实用户都不可能命中。
     */
    private static final long NO_EXCLUSION_SENTINEL = -1L;

    /**
     * 名单下钻的时间窗（天，2026-10-06）：缺省值 = {@link #RANKING_WINDOW_DAYS}，
     * 使「点进名单看到的总人数」与「列表行写的 30 天人数」<b>同值</b>。
     * 可由请求放大（运营查历史），但<b>不能小于 30</b>——否则与列表行的数字对不上，
     * 「点进去比列表写的人少」是一个无法解释的不一致。
     */
    private static final int VISITOR_WINDOW_MIN_DAYS = 30;

    /** 名单分页上限（防深翻页拖库；admin-web 端 PAGE_SIZE=20，上限 100 兜住误传） */
    private static final int VISITOR_PAGE_MAX_SIZE = 100;

    /**
     * 单次「用户到访足迹」响应的<b>到访次数上限</b>（2026-10-06）。
     * <p>
     * 到访是稀疏信号（2026-10-06 现网全网 52 条 ping / 9 家店），正常用户远达不到；
     * 上限只防「长期高频到店 + 大窗口」的极端组合把响应撑爆。超限时置
     * {@code truncated=true} 让前端明说，⛔ 禁静默截断（那会让运营以为「就这些次」）。
     */
    private static final int USER_VISIT_MAX_RECORDS = 200;

    /**
     * 单次「用户到访足迹」响应中<b>开关变更流水</b>的条数上限（2026-10-07）。
     * <p>
     * 开关是低频动作（正常用户一生个位数次），上限只防异常端循环上报把响应撑爆；
     * 超限置 {@code historyTruncated=true} 让前端明说，⛔ 同 {@link #USER_VISIT_MAX_RECORDS}
     * 禁静默截断（否则上限会被读成「他只改过这么多次」）。
     */
    private static final int USER_CONSENT_HISTORY_MAX = 50;

    /**
     * 单次「用户位置轨迹」响应中的<b>轨迹点上限</b>（2026-10-08 V46）。
     * <p>
     * 轨迹点是逐采样明细（每次打开至多 1 条 + 店内每 15 分钟补采），比到访次数稠密：
     * 一个整晚泡店的用户一夜可达十余点，90 天窗口理论上限在数百级。上限只防
     * 「长期高频 + 大窗口」把响应撑爆；超限保留<b>最近</b>点并置 {@code truncated=true}
     * 让前端明说（⛔ 禁静默截断——同 {@link #USER_VISIT_MAX_RECORDS} 纪律）。
     */
    private static final int USER_TRACK_MAX_POINTS = 800;

    /** 轨迹窗口上限（天，2026-10-08 V46）：缺省 7（controller），服务端钳制 1~本值 */
    private static final int TRACK_WINDOW_MAX_DAYS = 90;

    /**
     * 一次到店 = 连续命中桶的合并阈值（2026-10-06，单位 = 桶）。
     * <p>
     * <b>为什么需要合并</b>：采集主力 = 每次打开小程序（onShow）+ 店内每 15 分钟补采一次
     * （{@code WRITE_WINDOW_MINUTES}），同一次到店必然留下<b>多个 15 分钟桶</b>。
     * 不合并的后果很具体：跳一支舞 3 小时（18:00~21:00）≈ 12 个桶 ⇒ 被记成 12 次到店，
     * 「到访次数」会从「来过几次」退化成「停留了几小时」（后者还随补采频率线性放大——
     * 改一次采样间隔就改一次「次数」，这是最坏的指标性质）。
     * <p>
     * <b>为什么阈值 = 2 桶（30 分钟）</b>：相邻桶之间隔 ≤ 2 桶才认为是同一次到店。
     * <ul>
     *   <li>正常连场：补采间隔 15 分钟 = 1 桶 ⇒ 恒 ≤ 阈值，合并为一次（这是本值要保证的）；</li>
     *   <li>阈值取 1 太紧：只要有<b>一次</b>补采抖动（onShow 未触发 / 定位超时 /
     *       网络失败，当次周期没补上）就断成两次到店，把「到店次数」放大成噪声；</li>
     *   <li>阈值取 3 太松：用户离店 15~30 分钟后又回来（结账、买水、挪车、见朋友）
     *       会被并成一次——舞厅场景里「出去一趟又回来」很常见，30 分钟是最容易踩到的边界；</li>
     *   <li>本值只影响<b>足迹明细的展示</b>，不影响任何聚合数字（人数/次数/排序份额
     *       各自走独立口径，见 {@code countVisitEvents}），改它不需要迁移、不影响历史数据。</li>
     * </ul>
     */
    private static final int VISIT_SESSION_GAP_BUCKETS = 2;


    private final VenuePresencePingRepository pingRepository;
    private final VenuePresenceConsentRepository consentRepository;
    private final VenueRepository venueRepository;
    private final OpsConfigService opsConfigService;
    /**
     * 用户仓储（2026-10-06 到访名单下钻的唯一新增依赖）。
     * <p>
     * <b>为什么名单要读用户表</b>：聚合统计只需计数（到访域可自闭环），
     * 但「这 12 个人是谁」必须 join 用户公开资料。⚠️ 只取<b>公开字段</b>
     * （id / 昵称 / 头像 / 角色 / 审核标记），⛔ {@code openId} 等敏感字段绝不下发
     * （同 {@code AdminUserItem} 的展示边界）。
     */
    private final UserRepository userRepository;

    /**
     * 每用户写入频控（滑动窗口）：键 = userId，值 = 窗口内写入时刻队列。
     * 15 分钟桶幂等挡不住「跨店狂刷」，此处兜底用户级速率。合法流量（每次打开
     * 至多 1 条）距上限差两个数量级。
     */
    private final Cache<Long, Deque<Long>> writeRateCache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    // ── 写侧 ────────────────────────────────────────────────────────────────────

    /**
     * 上报一次到访痕迹（POST /venues/{venueId}/presence 的实现）。
     * 调用方必须已 {@code UserContext.requireAuth()}（重放安全不变量：鉴权在副作用之前）。
     * <p>
     * 判定序：运营总开关 → 用户写频控 → <b>同意门禁</b> → 门店与参数校验
     * （距离 / 精度 / 坐标成对与域）→ 桶幂等写入。
     * 门禁不通过返回 {@code accepted=false(CONSENT_REQUIRED)} 而非错误码：旧版默认开启端对失败
     * 静默，拒收不该在它们的日志里制造 4xx 噪音。
     */
    @Transactional
    public PresenceReportResponse report(Long venueId, Long userId, ReportPresenceRequest request) {
        if (!opsConfigService.isEnabled(OpsConfigService.KEY_PRESENCE_COLLECT_ENABLED, true)) {
            return new PresenceReportResponse(false, PresenceReportResponse.REASON_DISABLED);
        }
        if (exceedsWriteRate(userId)) {
            throw new BusinessException(1022, "上报过于频繁，请稍后再试");
        }
        if (!hasExplicitConsent(userId)) {
            return new PresenceReportResponse(false, PresenceReportResponse.REASON_CONSENT_REQUIRED);
        }
        Venue venue = venueRepository.findById(venueId)
                .filter(v -> !v.isDeleted())
                .orElseThrow(() -> new BusinessException(1022, "门店不存在"));
        // 歌友会（cityOnlyAddress）坐标在写路径被主动清空，端侧经 nearby 永远拿不到它；
        // 直连接口伪造 distance 的路径在此封死——无坐标门店不在采集范围（52 号文档 §边界）
        if (venue.getVenueType() == VenueType.SONG_CLUB
                || venue.getLatitude() == null || venue.getLongitude() == null) {
            throw new BusinessException(1022, "该门店不参与到访采集");
        }
        Integer distanceM = request.distanceMeters();
        if (distanceM == null || distanceM < 0 || distanceM > WRITE_MAX_DISTANCE_M) {
            throw new BusinessException(1022, "距离参数越界");
        }
        Integer accuracyM = request.accuracyMeters();
        if (accuracyM != null && (accuracyM < 0 || accuracyM > WRITE_MAX_ACCURACY_M)) {
            throw new BusinessException(1022, "精度参数越界");
        }
        // 坐标（V46，2026-10-08）：非空必须成对（半套坐标 = 端侧 bug，拒收暴露之，不静默丢）；
        // 域内校验防脏数据。两值全缺 = 旧端请求，照常收（协议向后兼容）。
        Double latitude = request.latitude();
        Double longitude = request.longitude();
        if ((latitude == null) != (longitude == null)) {
            throw new BusinessException(1022, "坐标参数不完整");
        }
        if (latitude != null
                && (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)) {
            throw new BusinessException(1022, "坐标参数越界");
        }
        // UTC epoch 分钟派生，与时区无关（V33 迁移头注 §防刷与幂等）
        long bucket = System.currentTimeMillis() / 60_000L / WRITE_WINDOW_MINUTES;
        pingRepository.upsertInBucket(userId, venueId, bucket, distanceM, accuracyM,
                latitude, longitude, LocalDateTime.now());
        return new PresenceReportResponse(true, null);
    }

    /**
     * 记录一次状态确立（POST /venues/presence-consent 的实现）：设置页拨动开关（USER）或到店首问
     * 回答（PROMPT）各插一行流水（不 upsert——保留变更历史才能回答「近期变更热度」与「首问效果」；
     * 当前态由查询侧「每用户最新一条」口径派生）。
     * <p>
     * enabled 缺失按 1022 拒绝（禁猜默认值）；来源解析见 {@link #resolveReportedSource}。
     */
    @Transactional
    public void recordConsent(Long userId, Boolean enabled, String sourceRaw) {
        if (enabled == null) {
            throw new BusinessException(1022, "缺少开关状态");
        }
        VenuePresenceConsent consent = new VenuePresenceConsent();
        consent.setUserId(userId);
        consent.setEnabled(enabled);
        consent.setSource(resolveReportedSource(sourceRaw));
        // created_at/updated_at 由 BaseEntity 的 @CreationTimestamp/@UpdateTimestamp 托管
        consentRepository.save(consent);
    }

    /**
     * 端上声明的确立来源：缺失 = USER（协议历史——10-03 前的端只在设置页上报且不带来源，这不是猜测）；
     * 可识别的显式来源原样采用；DEFAULT（服务端历史补记来源，端上无权声明）或无法识别的值按 1022 拒绝。
     */
    static ConsentSource resolveReportedSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return ConsentSource.USER;
        }
        ConsentSource parsed = WireEnums.parse(ConsentSource.class, raw);
        if (parsed == null || !parsed.isExplicit()) {
            throw new BusinessException(1022, "非法的开关来源");
        }
        return parsed;
    }

    /**
     * 同意判据（单点）：最新一条状态确立是<b>用户显式开启</b>——采集门禁与 admin「已允许」口径共用。
     * DEFAULT 来源（默认开启期补记、用户从未被问过）即使 enabled=true 也不构成同意。
     */
    public static boolean isExplicitlyEnabled(Boolean enabled, ConsentSource source) {
        return Boolean.TRUE.equals(enabled) && source != null && source.isExplicit();
    }

    private boolean hasExplicitConsent(Long userId) {
        return consentRepository.findFirstByUserIdAndDeletedFalseOrderByCreatedAtDescIdDesc(userId)
                .map(c -> isExplicitlyEnabled(c.getEnabled(), c.getSource()))
                .orElse(false);
    }

    // ── 开关统计 ────────────────────────────────────────────────────────────────

    /**
     * 开关统计（admin 门店列表页头）。当前态 = 每用户最新一条 consent 行，按
     * {@link #isExplicitlyEnabled} 分为 已允许 / 已关闭 / 待补问（最新态仍是历史 DEFAULT）；
     * 另附首问回答分布与近 30 天设置变更次数。数据量级 = 用户数 × 变更次数（千级行），
     * native 窗口函数一条 SQL 出分布，见
     * {@code VenuePresenceConsentRepository#countLatestByEnabledAndSource}。
     */
    public VenuePresenceConsentStats consentStats() {
        long enabledUsers = 0;
        long disabledUsers = 0;
        long legacyDefaultUsers = 0;
        for (Object[] row : consentRepository.countLatestByEnabledAndSource()) {
            boolean enabled = Boolean.TRUE.equals(row[0]);
            ConsentSource source = WireEnums.parse(ConsentSource.class, String.valueOf(row[1]));
            long users = ((Number) row[2]).longValue();
            // 判据走 consentStateOf 单点：门禁 / 单用户展示 / 本分布三处必须同数
            switch (consentStateOf(enabled, source)) {
                case ENABLED -> enabledUsers += users;
                case PENDING_PROMPT -> legacyDefaultUsers += users;
                default -> disabledUsers += users;
            }
        }
        long promptAllowed = 0;
        long promptDeclined = 0;
        for (Object[] row : consentRepository.countPromptAnswersByEnabled()) {
            long users = ((Number) row[1]).longValue();
            if (Boolean.TRUE.equals(row[0])) {
                promptAllowed += users;
            } else {
                promptDeclined += users;
            }
        }
        long changes30d = consentRepository.countUserChangesSince(LocalDateTime.now().minusDays(30));
        return new VenuePresenceConsentStats(enabledUsers, disabledUsers, legacyDefaultUsers,
                promptAllowed, promptDeclined, changes30d);
    }

    // ── 读侧：到访统计 ──────────────────────────────────────────────────────────

    /**
     * 单店到访统计（admin 详情卡）。到访人数 / 最近到访 = {@link #visitSummaries} 同一计算；
     * 附近人数是片区语义（这一带出现过多少人），归属范围恒为「本店 + 全部同址门店」、不做营业归因。
     * 口径参数与同址归因随响应回显，admin 端展示时必须与数值同屏（统计量不带口径 = 邀请误读）。
     */
    public VenuePresenceStats statsFor(Long venueId) {
        LocalDateTime now = LocalDateTime.now();
        Attribution attribution = attributionsFor(List.of(venueId)).get(venueId);
        Map<Long, Map<Long, LocalDateTime>> hitLastSeen = lastSeenByVenue(attribution.evidenceVenueIds(), HIT_RADIUS_M);
        VenueVisitSummary summary = summarize(attribution, hitLastSeen, now);
        Map<Long, Map<Long, LocalDateTime>> nearbyLastSeen = lastSeenByVenue(attribution.areaVenueIds(), NEARBY_RADIUS_M);
        long nearby30d = countSince(unionLastSeen(attribution.areaVenueIds(), nearbyLastSeen), now.minusDays(30));
        List<VenuePresenceStats.CoLocatedVenue> peers = attribution.peers().stream()
                .map(p -> new VenuePresenceStats.CoLocatedVenue(
                        p.id(), p.name(), displayOf(p.status()), isInOperation(p.status())))
                .toList();
        return new VenuePresenceStats(summary.visitUsers7d(), summary.visitUsers30d(), nearby30d,
                summary.lastVisitAt(), HIT_RADIUS_M, NEARBY_RADIUS_M, CO_LOCATED_RADIUS_M,
                summary.coLocatedAttribution(), peers);
    }

    /**
     * 批量到访摘要（admin 列表一页）：同址解析 1 次 + 命中查询 1 次覆盖整页，防 N+1。
     * 返回<b>每个</b>入参门店的摘要（无到访也有一行——归因方式本身是要展示的信息）。
     */
    public Map<Long, VenueVisitSummary> visitSummaries(Collection<Long> venueIds) {
        if (venueIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Attribution> attributions = attributionsFor(venueIds);
        Set<Long> evidence = new LinkedHashSet<>();
        attributions.values().forEach(a -> evidence.addAll(a.evidenceVenueIds()));
        Map<Long, Map<Long, LocalDateTime>> lastSeen = lastSeenByVenue(evidence, HIT_RADIUS_M);
        Map<Long, VenueVisitSummary> result = new LinkedHashMap<>();
        attributions.forEach((id, a) -> result.put(id, summarize(a, lastSeen, now)));
        return result;
    }

    /**
     * 全部「有过到访」门店的摘要（admin 按足迹排序 / 只看有足迹，52 号 §6.1）：从全量命中证据出发，
     * 候选 = 有证据的门店 + 它们的同址邻居（邻居可能经共享 / 并入获得到访），再走与
     * {@link #visitSummaries} 完全相同的归因与汇总——两条路径对同一家店必须给出同一组数字。
     * 只返回 lastVisitAt 非空的门店（稀疏结果；缺席 = 从无到访）。
     */
    public Map<Long, VenueVisitSummary> visitedVenueSummaries() {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Map<Long, LocalDateTime>> lastSeen =
                toLastSeenByVenue(pingRepository.findVisitorLastSeen(HIT_RADIUS_M, HIT_MAX_ACCURACY_M));
        if (lastSeen.isEmpty()) {
            return Map.of();
        }
        Set<Long> candidates = new LinkedHashSet<>(lastSeen.keySet());
        for (VenueRepository.CoLocatedVenueRow row
                : venueRepository.findCoLocatedPairs(lastSeen.keySet(), CO_LOCATED_RADIUS_M)) {
            candidates.add(row.getCoLocatedId());
        }
        Map<Long, VenueVisitSummary> result = new LinkedHashMap<>();
        attributionsFor(candidates).forEach((id, a) -> {
            VenueVisitSummary summary = summarize(a, lastSeen, now);
            if (summary.lastVisitAt() != null) {
                result.put(id, summary);
            }
        });
        return result;
    }

    /** 在营判定（可被到访归因的门店状态，{@link #IN_OPERATION_STATUSES}）；状态无法识别时按在营处理（不凭未知让渡证据） */
    public static boolean isInOperation(VenueStatus status) {
        return status == null || IN_OPERATION_STATUSES.contains(status);
    }

    // ── 读侧：C 侧「附近的足迹」展示（2026-10-08 重定义；2026-10-09 取数扩至附近带 300m；
    //    唯一的消费方 = VenueVisitBadgeService） ──

    /**
     * C 侧「附近的足迹」展示事实（2026-10-08 重定义；文档 = 52 号「C 侧展示：附近的足迹」节）。
     *
     * <p><b>语义</b>：一家店的「附近」= 本店 + 与它相距 ≤ {@link #NEARBY_TRACE_RADIUS_M} 的
     * 在库门店；返回其上的<b>「附近带」ping</b>（距店 ≤ {@link #NEARBY_RADIUS_M}，2026-10-09
     * 由命中的 150m 扩至 300m）的<b>用户并集</b>与 <b>(人, 自然日) 并集</b>（30 天窗）。
     * <b>不看营业状态</b>——足迹是已发生的事实，展示只声明「附近」，停业店的位置同样可能有足迹
     * （营业状态时常变换，今天关门、昨天有人去过两件事同时为真）。
     *
     * <p><b>为什么不再做门店级归属</b>（2026-10-08 用户裁决；事故复盘见 52 号）：经纬度距离
     * 无法判定线下用户真实在哪家店（门店坐标是地址级地理编码、同商场可散布百余米；端侧
     * 「500m 内最近一家」只是几何顺序，不是事实）。旧展示实现把一组门店的证据「归属」给某一家
     * （按营业状态 ABSORBED/YIELDED），一次单店坐标维护就让同一批足迹「算到另一家头上」
     * ⇒ 新口径：范围内<b>所有</b>门店都显示同一句「附近的足迹」，由用户自行判断是哪家店。
     *
     * <p><b>与既有口径的关系（都读同一份 ping 事实，分叉刻意，⛔ 禁互相"对齐"）</b>：
     * <ul>
     *   <li>本方法（C 侧展示）= 附近并集、无归属、无状态过滤、无分摊；</li>
     *   <li>admin 展示（{@link #visitSummaries} / {@link #visitedVenueSummaries}）= 同址归因明细
     *       （NONE/SHARED/ABSORBED/YIELDED），供运营核查；</li>
     *   <li>排序（{@link #visitSharesForRanking}）= 1/k 分摊 + 不在营记 0。</li>
     * </ul>
     *
     * <p><b>取数</b>：一次同址查询（入参集合 × 全表）+ 一次命中并集查询 + 一次到访日查询，
     * 页面级批量、无 N+1（与 admin 单页路径同量级）。排除集合与排序口径同一来源（调用方传入；
     * admin 展示"不排除"的分叉保持原样）。无命中足迹的门店<b>不在返回 map 里</b>
     * （调用方按缺席处理：前端不渲染，零布局变化）。
     *
     * <p>量级边界：每次调用 3 条小查询（命中表稀疏 + {@code (venue_id, created_at)} 索引前缀）；
     * ping 表到 10^6 行 / 门店到万级时改为物化（触发条件与 admin 侧登记同款）。
     *
     * @param venueIds         当页门店（城市列表 / 收藏列表的一页）
     * @param excludedUserIds  排除账号集合（恒非空由调用方保证——{@code HeatAccountExclusionService}
     *                         契约；空集合退化为"谁都不排除"，不会产生 SQL 语法问题，本方法在 Java 侧过滤）
     */
    @Transactional(readOnly = true)
    public Map<Long, NearbyVisitSummary> nearbyVisitSummaries(Collection<Long> venueIds,
                                                              Collection<Long> excludedUserIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusDays(RANKING_WINDOW_DAYS);
        // ① 每店的「附近门店集合」= {本店} ∪ {与它相距 ≤ NEARBY_TRACE_RADIUS_M 的在库门店}。
        //    只做一跳（不做传递闭包）：展示单元是「这家店的附近」而非「连成一片的商圈」。
        Map<Long, Set<Long>> vicinityByVenue = new LinkedHashMap<>();
        for (Long id : venueIds) {
            vicinityByVenue.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(id);
        }
        for (VenueRepository.CoLocatedVenueRow row
                : venueRepository.findCoLocatedPairs(venueIds, NEARBY_TRACE_RADIUS_M)) {
            // 查询过滤 a.id IN :venueIds ⇒ row.getVenueId() 恒在 map；防御性判空只为
            // 未来若扩大查询范围的场景（不让新增行静默漏并）
            Set<Long> vicinity = vicinityByVenue.get(row.getVenueId());
            if (vicinity != null) {
                vicinity.add(row.getCoLocatedId());
            }
        }
        // ② 一次批量取事实：范围内全部门店上的「附近带」ping（距店 ≤ NEARBY_RADIUS_M=300m，
        //    2026-10-09 由命中带 150m 扩至 300m；⛔ 与 admin / 排序路径同表**不同谓词**——
        //    那两处仍取命中带 150m，分叉刻意、禁对齐）。精度门槛不随半径放宽（数据质量门槛恒定）。
        Set<Long> evidenceStores = new LinkedHashSet<>();
        vicinityByVenue.values().forEach(evidenceStores::addAll);
        Map<Long, Map<Long, LocalDateTime>> lastSeen = toLastSeenByVenue(
                pingRepository.findVisitorLastSeenByVenueIds(evidenceStores, NEARBY_RADIUS_M, HIT_MAX_ACCURACY_M));
        Map<Long, Map<Long, Set<LocalDate>>> visitDays = toVisitDaysByVenue(
                pingRepository.findVisitorDaysByVenueIdsSince(evidenceStores, windowStart,
                        NEARBY_RADIUS_M, HIT_MAX_ACCURACY_M));
        // ③ 排除集合单点剔除（两张映射的 user 维同时过滤——「同一集合、两种消费面」，见方法注释）
        if (excludedUserIds != null && !excludedUserIds.isEmpty()) {
            lastSeen.values().forEach(m -> m.keySet().removeAll(excludedUserIds));
            visitDays.values().forEach(m -> m.keySet().removeAll(excludedUserIds));
        }
        // ④ 逐店求并集：组内同一用户 / 同一天只计一次（⛔ 不能各店相加——52 号 §4.2 第 1 条）
        Map<Long, NearbyVisitSummary> result = new LinkedHashMap<>();
        vicinityByVenue.forEach((venueId, stores) -> {
            long users = countSince(unionLastSeen(stores, lastSeen), windowStart);
            if (users <= 0) {
                return; // 本店附近无足迹 ⇒ 缺席 = 不渲染（「无足迹不渲染」是唯一硬约束）
            }
            result.put(venueId, new NearbyVisitSummary(users, countVisitEvents(stores, visitDays)));
        });
        return result;
    }

    // ── 读侧：到访名单下钻（2026-10-06，admin 名单页 / 用户足迹页） ─────────────────

    /**
     * 单店到访用户名单（admin 名单页，GET /admin/venues/{venueId}/visitors 的实现）。
     * <p>
     * <b>与 {@link #statsFor} / {@link #visitSummaries} 同源</b>：同一个
     * {@link #attributionsFor} 归因 + 同一个 {@link #unionLastSeen} 用户并集，
     * ⛔ 禁在本方法里另写一份口径。否则会出现「列表写 5 人、点进去 8 人」——
     * 运营无法判断哪个对，只能怀疑系统（52 号 §4 的同源纪律）。
     * <p>
     * <b>窗口语义</b>：名单默认窗口 = {@link #RANKING_WINDOW_DAYS}（30 天），
     * 与列表行 {@code visitUsers30d} <b>逐人相等</b>（同一个并集 + 同一个
     * {@code lastSeenAt >= 窗口起点} 判定）；{@code windowDays} 可放大（查历史），
     * 但被钳到 ≥ 30 天——放大后名单变长是可以解释的，变短则与列表行冲突。
     * <p>
     * <b>分页在内存切</b>：候选 = 命中证据的用户并集（百级，同
     * {@link #findVisitorLastSeenByVenueIds} 的量级边界），排序键 = 最近到访倒序 +
     * userId 升序（并列时翻页确定）。
     * <p>
     * <b>不排除内部账号</b>（{@code AdminVenueVisitorItem#internalAccount} 打标签）：
     * admin 展示要完整，与排序口径的排除<b>有意不同</b>。
     */
    @Transactional(readOnly = true)
    public AdminVenueVisitorPage visitorsFor(Long venueId, int windowDays, int page, int size) {
        LocalDateTime now = LocalDateTime.now();
        int window = Math.max(windowDays, VISITOR_WINDOW_MIN_DAYS);
        int sizeBound = Math.min(Math.max(size, 1), VISITOR_PAGE_MAX_SIZE);
        int pageBound = Math.max(page, 0);
        Attribution attribution = attributionsFor(List.of(venueId)).get(venueId);
        Map<Long, Map<Long, LocalDateTime>> lastSeen =
                lastSeenByVenue(attribution.evidenceVenueIds(), HIT_RADIUS_M);
        Map<Long, LocalDateTime> users = unionLastSeen(attribution.evidenceVenueIds(), lastSeen);
        LocalDateTime windowStart = now.minusDays(window);
        // 窗口内访客：与 countSince 同一条判据（最近命中时刻 ≥ 窗口起点）⇒ 与 visitUsers30d 逐人相等
        Map<Long, LocalDateTime> inWindow = new HashMap<>();
        users.forEach((user, at) -> {
            if (!at.isBefore(windowStart)) {
                inWindow.put(user, at);
            }
        });
        // 次数列：同址组内 (user, day) 取并集（⛔ 不能各店 count 相加，52 号 §4.2 第 1 条）
        Map<Long, Set<LocalDate>> daysByUser = new HashMap<>();
        if (!inWindow.isEmpty()) {
            for (Object[] row : pingRepository.findVisitorDaysByVenueIdsSince(
                    attribution.evidenceVenueIds(), windowStart, HIT_RADIUS_M, HIT_MAX_ACCURACY_M)) {
                Long user = (Long) row[1];
                if (inWindow.containsKey(user)) {
                    daysByUser.computeIfAbsent(user, k -> new HashSet<>()).add(toLocalDate(row[2]));
                }
            }
        }
        List<Map.Entry<Long, LocalDateTime>> ordered = new ArrayList<>(inWindow.entrySet());
        ordered.sort((a, b) -> {
            int byTime = b.getValue().compareTo(a.getValue());
            return byTime != 0 ? byTime : Long.compare(a.getKey(), b.getKey());
        });
        long totalElements = ordered.size();
        int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + sizeBound - 1) / sizeBound);
        int from = Math.min(pageBound * sizeBound, ordered.size());
        int to = Math.min(from + sizeBound, ordered.size());
        Map<Long, User> profiles = userRepository.findAllById(
                ordered.subList(from, to).stream().map(Map.Entry::getKey).toList())
                .stream().collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        List<AdminVenueVisitorItem> content = ordered.subList(from, to).stream()
                .map(entry -> toVisitorItem(entry.getKey(), entry.getValue(),
                        daysByUser.getOrDefault(entry.getKey(), Set.of()), profiles))
                .toList();
        List<VenuePresenceStats.CoLocatedVenue> peers = attribution.peers().stream()
                .map(p -> new VenuePresenceStats.CoLocatedVenue(
                        p.id(), p.name(), displayOf(p.status()), isInOperation(p.status())))
                .toList();
        return new AdminVenueVisitorPage(content, totalElements, totalPages, pageBound, sizeBound,
                from + sizeBound >= totalElements, window, HIT_RADIUS_M, attribution.kind(), peers);
    }

    /**
     * 名单行装配：<b>用户资料缺失也要出行</b>（软删账号的到访痕迹是运营核查线索，
     * 静默丢行会让 {@code totalElements} 与行数对不上，又变成一个说不清的不一致）。
     * 缺资料时只给代号（{@code UserCode.format} 纯派生，不依赖用户行存在）。
     */
    private static AdminVenueVisitorItem toVisitorItem(Long userId, LocalDateTime lastVisitAt,
                                                       Set<LocalDate> visitDays,
                                                       Map<Long, User> profiles) {
        User user = profiles.get(userId);
        boolean custom = UserCode.isCustomNickname(user);
        return new AdminVenueVisitorItem(
                userId,
                UserCode.format(userId),
                user == null ? null : user.getNickname(),
                custom,
                user == null ? null : user.getAvatarUrl(),
                visitDays.size(),
                lastVisitAt,
                isInternalAccount(user));
    }

    /**
     * 内部账号判据（名单打标签用，单点定义）：ADMIN 运营号或微信审核号。
     * <p>
     * <b>为什么展示口径要标它、排序口径要排它</b>：到访是低基数信号
     * （2026-10-06 现网 51 条 ping 里 ADMIN 一人占 26 条），排序不排除 = 平台自己人刷分；
     * 而 admin 名单若不标它，运营看到「这家店只有一个用户来过」时无法判断那是真舞友
     * 还是自己人测试留下的。<b>排除集合、可见性是两个决策</b>，不是一个口径的两种实现。
     */
    static boolean isInternalAccount(User user) {
        return user == null || user.getRole() == UserRole.ADMIN
                || Boolean.TRUE.equals(user.getWechatReview());
    }

    /**
     * 某用户的到访足迹（admin 用户详情页「到访足迹」卡，GET /admin/users/{userId}/visits 的实现）。
     * <p>
     * <b>逐桶合并为「一次次到店」</b>：命中桶按 {@code writeBucket} 升序，
     * 相邻桶间隔 ≤ {@link #VISIT_SESSION_GAP_BUCKETS} 视为同一次到店（同一次跳舞的连续补采），
     * 合并后取首桶 {@code created_at} = 到店时刻、末桶 {@code updated_at} = 最后被记录时刻、
     * 桶数 = 采样次数、桶内最小 {@code distance_m} = 距店最近距离。
     * <p>
     * <b>为什么不用聚合查询直接出「次数」</b>：聚合能回答「来过几次」，
     * 但回答不了「每次多久 / 什么时候」——那些信息在 {@code MAX(created_at)} 里已被抹掉
     * （同 V39 注释）。要展示逐次明细就必须取桶序列在内存里合并。
     * <p>
     * <b>超限截断</b>：超过 {@link #USER_VISIT_MAX_RECORDS} 次时保留最近的若干次并置
     * {@code truncated=true}——⛔ 禁静默截断（运营会把上限读成「他就这么多次来过」）。
     * <p>
     * 用户不存在 / 已软删 → 1004（与 {@code AdminUserService} 同码）。
     */
    @Transactional(readOnly = true)
    public AdminUserVisitsResponse visitsFor(Long userId, int windowDays) {
        if (!userRepository.findByIdAndDeletedFalse(userId).isPresent()) {
            throw new BusinessException(1004, "用户不存在");
        }
        int window = Math.max(windowDays, 1);
        LocalDateTime windowStart = LocalDateTime.now().minusDays(window);
        List<Object[]> rows = pingRepository.findHitsByUserIdSince(
                userId, windowStart, HIT_RADIUS_M, HIT_MAX_ACCURACY_M);
        // 门店名/状态：一次批量取回，避免逐条记录查库（N+1）。
        // ⛔ 空集合必须短路：原生 IN () 是语法错误（同 NO_EXCLUSION_SENTINEL 防御的同款理由）
        Map<Long, Venue> venues = rows.isEmpty() ? Map.of()
                : venueRepository.findByIdInAndDeletedFalse(
                        rows.stream().map(row -> (Long) row[0]).distinct().toList())
                    .stream().collect(java.util.stream.Collectors.toMap(Venue::getId, v -> v));
        Map<Long, List<AdminUserVisitRecord>> recordsByVenue = new LinkedHashMap<>();
        long keptTotal = 0;
        boolean truncated = false;
        outer:
        for (List<List<Object[]>> sessions : mergeBucketsIntoSessions(rows).values()) {
            for (List<Object[]> session : sessions) {
                // 全局上限：按 (店, 到店时刻) 倒序保留最近次，超出即截断并明说
                if (keptTotal >= USER_VISIT_MAX_RECORDS) {
                    truncated = true;
                    break outer;
                }
                Long venueId = (Long) session.get(0)[0];
                Venue venue = venues.get(venueId);
                LocalDateTime arrivedAt = (LocalDateTime) session.get(0)[2];
                LocalDateTime lastSeenAt = (LocalDateTime) session.get(session.size() - 1)[3];
                int minDistance = Integer.MAX_VALUE;
                for (Object[] bucket : session) {
                    minDistance = Math.min(minDistance, (Integer) bucket[4]);
                }
                recordsByVenue.computeIfAbsent(venueId, k -> new ArrayList<>())
                        .add(new AdminUserVisitRecord(
                                venueId,
                                venue == null ? null : venue.getName(),
                                venue == null ? null : venue.getCity(),
                                venue == null ? null : displayOf(venue.getStatus()),
                                arrivedAt,
                                lastSeenAt,
                                stayMinutes(arrivedAt, lastSeenAt),
                                session.size(),
                                minDistance == Integer.MAX_VALUE ? null : minDistance));
                keptTotal++;
            }
        }
        // 会话已在 mergeBucketsIntoSessions 内按到店倒序 ⇒ 组内天然「最近在前」；
        // 组间按最近一次到访倒序（records 首条即该店最近一次）
        List<AdminUserVisitVenueGroup> groups = new ArrayList<>();
        recordsByVenue.forEach((venueId, records) -> {
            Venue venue = venues.get(venueId);
            groups.add(new AdminUserVisitVenueGroup(venueId,
                    venue == null ? null : venue.getName(),
                    venue == null ? null : venue.getCity(),
                    venue == null ? null : displayOf(venue.getStatus()),
                    records.size(), records.get(0).arrivedAt(), records));
        });
        groups.sort((a, b) -> b.lastVisitAt().compareTo(a.lastVisitAt()));
        LocalDateTime lastVisitAt = groups.isEmpty() ? null : groups.get(0).lastVisitAt();
        return new AdminUserVisitsResponse(userId, groups, groups.size(), keptTotal,
                lastVisitAt, window, HIT_RADIUS_M, truncated, consentFor(userId));
    }

    /**
     * 某用户的位置轨迹（admin 用户详情页「位置轨迹」卡，GET /admin/users/{userId}/track 的实现）。
     * <p>
     * <b>与 {@link #visitsFor} 的分工</b>：到访足迹 = 命中口径的「一次次到店」（分组 / 停留 /
     * 采样数）；本方法 = <b>全部</b>采样点的原始轨迹（含 150m 外的「附近 / 留痕」带）——
     * 2026-10-08 判例（user 210 在丽莎 295m 上报、超命中线不入到访）正是它要回答的问题。
     * <p>
     * <b>取数形态</b>：原始行**倒序**取最近 {@link #USER_TRACK_MAX_POINTS} 条（超限保留最近的，
     * 同截断方向纪律），内存反转为时间升序（绘制序）。坐标缺失的历史行**保留在 points**
     * （坐标 null：地图跳过绘制、列表照常展示，店名解析所需门店一并收集），并以
     * {@code pointsWithoutCoordinates} 显式计数（⛔ 禁静默丢——2026-10-08 user 210 判例：
     * 唯一想看的 295m 样本恰是无坐标行，「只计数不展示」等于想看的记录仍不可见）。
     * <p>
     * <b>分级恒为距离带</b>（{@link PresenceTrackGrade}：≤150 命中 / ≤300 附近 / 其余留痕）——
     * 只表达「距最近门店多远」，不是到访判定的替代（到访另需精度达标 + 同址归因）。
     * <p>
     * 用户不存在 / 已软删 → 1004（与 {@link #visitsFor} 同码）。
     */
    @Transactional(readOnly = true)
    public AdminUserTrackResponse trackFor(Long userId, int windowDays) {
        if (!userRepository.findByIdAndDeletedFalse(userId).isPresent()) {
            throw new BusinessException(1004, "用户不存在");
        }
        int window = Math.min(Math.max(windowDays, 1), TRACK_WINDOW_MAX_DAYS);
        LocalDateTime windowStart = LocalDateTime.now().minusDays(window);
        // 倒序 + 上限 +1：多取一条只用于判超限（同 consent 流水取 N+1 同款），保留最近点
        List<Object[]> rows = pingRepository.findTrackByUserIdSince(
                userId, windowStart, PageRequest.of(0, USER_TRACK_MAX_POINTS + 1));
        boolean truncated = rows.size() > USER_TRACK_MAX_POINTS;
        List<Object[]> kept = truncated ? rows.subList(0, USER_TRACK_MAX_POINTS) : rows;
        int noCoordinate = 0;
        List<AdminUserTrackResponse.TrackPoint> points = new ArrayList<>(kept.size());
        Set<Long> venueIds = new LinkedHashSet<>();
        // 倒序遍历 = 时间升序输出（不引入 Collections 依赖，也让「升序」与取数方向解耦）
        for (int i = kept.size() - 1; i >= 0; i--) {
            Object[] row = kept.get(i);
            Long venueId = (Long) row[1];
            Double latitude = (Double) row[4];
            Double longitude = (Double) row[5];
            if (latitude == null || longitude == null) {
                noCoordinate++;
            }
            // 无坐标行照常进入 points 与门店集合：「不能画」≠「不存在」——列表要展示它、
            // 店名解析也依赖门店集合；只有地图侧跳过（⛔ 禁静默丢，见方法注释）
            venueIds.add(venueId);
            int distanceM = (Integer) row[2];
            PresenceTrackGrade grade = PresenceTrackGrade.ofDistance(distanceM);
            points.add(new AdminUserTrackResponse.TrackPoint(
                    (LocalDateTime) row[6], latitude, longitude, (Integer) row[3],
                    venueId, distanceM, grade.name(), grade.getDisplayName()));
        }
        // 门店：一次批量取回（防 N+1）；⛔ 空集合必须短路（原生 IN () 是语法错误，同 NO_EXCLUSION_SENTINEL 理由）
        Map<Long, Venue> venues = venueIds.isEmpty() ? Map.of()
                : venueRepository.findByIdInAndDeletedFalse(new ArrayList<>(venueIds))
                    .stream().collect(java.util.stream.Collectors.toMap(Venue::getId, v -> v));
        List<AdminUserTrackResponse.TrackVenue> venueItems = venueIds.stream()
                .map(id -> {
                    Venue venue = venues.get(id);
                    return new AdminUserTrackResponse.TrackVenue(
                            id,
                            venue == null ? null : venue.getName(),
                            venue == null ? null : venue.getLatitude(),
                            venue == null ? null : venue.getLongitude(),
                            venue == null ? null : displayOf(venue.getStatus()));
                })
                .toList();
        return new AdminUserTrackResponse(userId, window, HIT_RADIUS_M, NEARBY_RADIUS_M,
                points, venueItems, noCoordinate, truncated);
    }

    // ── 读侧：单用户开关态（2026-10-07，零迁移 = 读既有 qwt_venue_presence_consents） ──

    /**
     * 采集态派生（<b>单点</b>，2026-10-07）：{@code (enabled, source)} → 四态。
     * <p>
     * 三个消费方共用本判据，⛔ 禁各自重写：
     * <ol>
     *   <li>采集门禁（{@link #hasExplicitConsent}）——只认 {@link PresenceConsentState#ENABLED}；</li>
     *   <li>分布统计（{@link #consentStats()}）——ENABLED / DISABLED / PENDING_PROMPT 三档；</li>
     *   <li>admin 单用户展示（{@link #consentFor}）——含 NEVER_ASKED（无行）。</li>
     * </ol>
     * 三处同数是纪律：任一处自行判断就会出现「列表写着已允许、门禁却在拒收」这类无法排查的分裂。
     * <p>
     * ⚠️ {@code enabled=null} 只可能来自「无行」调用方（{@link #consentFor}），
     * 其余调用方的列非空；为免 NPE，此处按 {@code false} 处理。
     */
    public static PresenceConsentState consentStateOf(Boolean enabled, ConsentSource source) {
        if (source == null) {
            return PresenceConsentState.NEVER_ASKED;
        }
        if (isExplicitlyEnabled(enabled, source)) {
            return PresenceConsentState.ENABLED;
        }
        return Boolean.TRUE.equals(enabled)
                ? PresenceConsentState.PENDING_PROMPT
                : PresenceConsentState.DISABLED;
    }

    /**
     * 单用户的开关当前态 + 变更流水（admin 足迹卡的开关区，2026-10-07）。
     * <p>
     * <b>零迁移</b>：只读既有 {@code qwt_venue_presence_consents}，与足迹读的是不同的表
     * （ping = 位置痕迹 / consent = 授权证据），两者<b>刻意不 join</b>——一个用户可能从未到过店
     * 却在设置页关过开关（consent 有行、ping 无行），也可能到过店却从未确立状态（旧版端）。
     * join 会把这两类事实都吃掉。
     * <p>
     * 流水取 {@code USER_CONSENT_HISTORY_MAX + 1} 条：多取一条只用于判断是否超限，
     * ⛔ 不用「取 N 条再猜有没有更多」（那会让恰好 N 条时被误标为截断）。
     * 包含 DEFAULT 历史行是<b>刻意</b>的：运营需要看见「他曾被默认开启、后来才被问到」这段，
     * 抹掉它就只剩一行「已允许」，把一次合规缺陷读成了正常状态。
     */
    private AdminUserConsentResponse consentFor(Long userId) {
        List<VenuePresenceConsent> rows = consentRepository
                .findByUserIdAndDeletedFalseOrderByCreatedAtDescIdDesc(
                        userId, PageRequest.of(0, USER_CONSENT_HISTORY_MAX + 1));
        boolean historyTruncated = rows.size() > USER_CONSENT_HISTORY_MAX;
        List<VenuePresenceConsent> kept = historyTruncated
                ? rows.subList(0, USER_CONSENT_HISTORY_MAX)
                : rows;
        List<AdminUserConsentResponse.ConsentChange> history = new ArrayList<>(kept.size());
        for (VenuePresenceConsent c : kept) {
            PresenceConsentState state = consentStateOf(c.getEnabled(), c.getSource());
            history.add(new AdminUserConsentResponse.ConsentChange(
                    c.getEnabled(), c.getSource().name(), c.getSource().getDisplayName(),
                    state.name(), state.getDisplayName(), c.getCreatedAt()));
        }
        VenuePresenceConsent latest = kept.isEmpty() ? null : kept.get(0);
        PresenceConsentState state = consentStateOf(
                latest == null ? null : latest.getEnabled(),
                latest == null ? null : latest.getSource());
        return new AdminUserConsentResponse(
                userId,
                state.name(), state.getDisplayName(),
                latest == null ? null : latest.getEnabled(),
                latest == null ? null : latest.getSource().name(),
                latest == null ? null : latest.getSource().getDisplayName(),
                latest == null ? null : latest.getCreatedAt(),
                history, historyTruncated,
                opsConfigService.isEnabled(OpsConfigService.KEY_PRESENCE_COLLECT_ENABLED, true));
    }

    /**
     * 逐桶明细 → 按门店分组的「一次次到店」（同一次到店的连续桶合并为一条）。
     * <p>
     * 返回 {@code venueId → 该店的到店会话列表}，每个会话 = 一次到店的全部命中桶。
     * 会话内与门店间<b>均按到店时刻倒序</b>（最近的在前）——这不只是展示顺序：
     * 调用方按此顺序取到 {@link #USER_VISIT_MAX_RECORDS} 上限，<b>保留的必然是最近的那些次</b>
     * （截断语义正确；若按升序截断，被留下的会是三年前的记录，而最近的被丢掉）。
     * <p>
     * 输入已按 {@code (venueId, writeBucket)} 升序（{@code findHitsByUserIdSince} 的 ORDER BY），
     * 因此每个门店内桶天然按时间有序，只需线性扫描判「间隔是否超过阈值」。
     * 桶内 {@code created_at} 恒 ≤ {@code updated_at}（后者是桶内末次触发时刻），
     * 但仍显式钳非负——「停留时长为负」在页面上是一个无法解释的数字。
     */
    private static Map<Long, List<List<Object[]>>> mergeBucketsIntoSessions(List<Object[]> rows) {
        Map<Long, List<List<Object[]>>> byVenue = new LinkedHashMap<>();
        Long currentVenue = null;
        List<Object[]> currentSession = null;
        long previousBucket = Long.MIN_VALUE;
        for (Object[] row : rows) {
            Long venueId = (Long) row[0];
            long bucket = (Long) row[1];
            boolean newSession = currentSession == null
                    || !venueId.equals(currentVenue)
                    || previousBucket == Long.MIN_VALUE
                    || bucket - previousBucket > VISIT_SESSION_GAP_BUCKETS;
            if (newSession) {
                currentSession = new ArrayList<>();
                byVenue.computeIfAbsent(venueId, k -> new ArrayList<>()).add(currentSession);
                currentVenue = venueId;
            }
            currentSession.add(row);
            previousBucket = bucket;
        }
        byVenue.values().forEach(sessions -> sessions.sort(
                Comparator.comparing((List<Object[]> s) -> (LocalDateTime) s.get(0)[2]).reversed()));
        return byVenue;
    }

    /** 已观测停留时长（分钟，≥ 0）：桶内首见 → 桶内末次触发 */
    private static long stayMinutes(LocalDateTime arrivedAt, LocalDateTime lastSeenAt) {
        if (arrivedAt == null || lastSeenAt == null) {
            return 0L;
        }
        return Math.max(0L, java.time.Duration.between(arrivedAt, lastSeenAt).toMinutes());
    }

    // ── 读侧：排序口径到访份额（2026-10-06，V38） ─────────────────────────────────

    /**
     * 排序口径的到访份额：**可配排除账号 + 同址分摊 + 不在营记 0**，供定时刷新任务写入
     * {@code qwt_venue_visit_metrics}（唯一消费方 = {@code VenueVisitMetricsScheduler}）。
     * <p>
     * <b>为什么必须由本类产出、不能由公式侧自己算</b>：到访人数是派生量
     * （命中谓词 × 同址组几何 × 双方营业状态 × 用户并集），其中归因是 Java 侧计算，
     * JPQL 无 FROM 派生表能力 ⇒ 公式只能读物化结果。见 {@code VenueVisitMetric} 类注释。
     * <p>
     * <b>与 admin 展示口径 {@link #visitedVenueSummaries()} 的三处分叉</b>（有意，见
     * {@link VenueVisitShare}）：排除集合、分摊（1/k）而非共享、不在营门店不产出。
     * <p>
     * 时间窗只取 30 天（{@link #RANKING_WINDOW_DAYS}）：7 天份额由同一份命中集在内存里
     * 二次判定，不额外查库（"用户在某窗口内到访过 ⟺ 其最近命中时刻 ≥ 窗口起点"）。
     *
     * @param excludedUserIds 排除账号集合（恒非空由调用方保证；本方法对空集合退化为
     *                        哨兵值，避免 {@code NOT IN ()} 语法错误——与公式侧同款防御）
     * @return venueId → 份额；<b>缺席 = 排序记 0</b>（无到访 / 已让渡 / 不在营）
     */
    @Transactional(readOnly = true)
    public Map<Long, VenueVisitShare> visitSharesForRanking(Collection<Long> excludedUserIds) {
        LocalDateTime now = LocalDateTime.now();
        Collection<Long> exclusions = (excludedUserIds == null || excludedUserIds.isEmpty())
                ? List.of(NO_EXCLUSION_SENTINEL) : excludedUserIds;
        LocalDateTime windowStart = now.minusDays(RANKING_WINDOW_DAYS);
        Map<Long, Map<Long, LocalDateTime>> lastSeen = toLastSeenByVenue(
                pingRepository.findVisitorLastSeenSinceExcluding(
                        windowStart, HIT_RADIUS_M, HIT_MAX_ACCURACY_M, exclusions));
        if (lastSeen.isEmpty()) {
            return Map.of();
        }
        // 到访次数（V39）：单独取「去重到访日」明细。⛔ 不能从 lastSeen 推——那里面一天
        // 的多次命中已被压成 MAX(createdAt)，"来过几天"的信息不可恢复（同 PingRepository
        // 注释）。与人数共用同一套排除集与窗口起点，两列因此永远同窗。
        Map<Long, Map<Long, Set<LocalDate>>> visitDays = toVisitDaysByVenue(
                pingRepository.findVisitorDaysSinceExcluding(
                        windowStart, HIT_RADIUS_M, HIT_MAX_ACCURACY_M, exclusions));
        // 候选 = 有证据的门店 + 它们的同址邻居（邻居可能经共享 / 并入获得到访），
        // 与 admin 全量路径同一手法（52 号 §6.1）
        Set<Long> candidates = new LinkedHashSet<>(lastSeen.keySet());
        for (VenueRepository.CoLocatedVenueRow row
                : venueRepository.findCoLocatedPairs(lastSeen.keySet(), CO_LOCATED_RADIUS_M)) {
            candidates.add(row.getCoLocatedId());
        }
        Map<Long, VenueVisitShare> result = new LinkedHashMap<>();
        attributionsFor(candidates).forEach((id, attribution) -> {
            // ⚠️ **不在营的门店也要产出份额**（2026-10-06 六轮变更，理由见下）——
            // 旧行为是此处直接 return，导致停业门店在物化表里**连行都没有**，
            // 于是列表页永远拿不到它的到访数据（这与"展示门槛"是另一层问题，降门槛也救不回来）。
            //
            // **为什么改**：营业状态时常变换（用户 2026-10-06 原话），"今天关门、
            // 昨天有人去过"两件事同时为真；到访是**已发生的事实**，不该被状态字段抹掉。
            // 现网 608 家 CEASED 中已有 1 家带到访记录（钜之淋，1 人）——按旧口径它永不可见。
            //
            // **排序侧的正确性由谁保证**：不是这里，而是**热度公式读数那一步**——
            // 本方法产出的是"事实"，"该不该给分"是排序决策。⚠️ 因此**必须**确认
            // HEAT_BEHAVIOR 侧对不在营门店另有守卫（见 VenueRepository#HEAT_BEHAVIOR
            // 与 05 号「到访项」：门店状态 ∉ {OPEN,CLOSED} 时到访项不生效），
            // 否则停业店会凭这份份额在榜单上浮——那是**排序回归**，不是展示问题。
            Map<Long, LocalDateTime> users = unionLastSeen(attribution.evidenceVenueIds(), lastSeen);
            if (users.isEmpty()) {
                return;
            }
            // 分摊分母 = 组内在营门店数（含本店，≥1）：同址组都在营 ⇒ 每位用户 1/k。
            // ⚠️ 停业店自己不在营时，inOperationCount 可能是 0 ⇒ 用 max(1,·) 兜底为
            // "不摊薄"（整楼都停业时没有可分摊的在营店，按人数原样记给自己）。
            //
            // ⚠️ 同址归因的 YIELDED（本店停业 + 同址有在营店）**证据为空** ⇒ users 为空
            // ⇒ 上面 return，本店仍记0，这与52 号 §4.4 的归因结论一致，**不因本次改动而变**。
            // 与「共享」（每家都记满）的差别正是本表要防的"同楼双吃"（52 号 §1.1 第 1 条）
            double share = 1.0 / Math.max(1, attribution.inOperationCount());
            // 次数 = 证据门店的「(人, 日) 二元组并集」大小 × 同一 share（⛔ 不是日期并集：
            // 那会把"谁来的"压掉 —— 6 位用户散在 5 天会被并成 5 次，见 countVisitEvents）
            long eventCount = countVisitEvents(attribution.evidenceVenueIds(), visitDays);
            result.put(id, new VenueVisitShare(
                    scaleVisits(countSince(users, windowStart) * share),
                    scaleVisits(countSince(users, now.minusDays(VISIT_RECENT_WINDOW_DAYS)) * share),
                    scaleVisits(eventCount * share),
                    attribution.areaVenueIds().size(),
                    // 被分摊 ⇔ 分摊系数 < 1（组内 ≥2 家在营，每位用户只算 1/k）。
                    // ⚠️ 2026-10-08 起本标记**无消费方**：它原供文案层决定用词（V40：
                    // 未分摊可断言"到这家店"），而 C 侧展示已改为无条件「附近」语义
                    // （门店级归属断言整体退役，见 nearbyVisitSummaries）——保留随行仅为
                    // 查询/调试可读；⛔ 不得据此重建"未分摊 ⇒ 真实到店"类用词分支。
                    share >= 1.0));
        });
        return result;
    }

    /** 份额落库精度（decimal(8,2)）：1/k 分之后四舍五入到 2 位，避免把 1/3 存成无限小数 */
    private static BigDecimal scaleVisits(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    // ── 同址归因 ────────────────────────────────────────────────────────────────

    /** 同址邻居（几何 + 状态事实，来自 {@link VenueRepository#findCoLocatedPairs}） */
    private record Peer(Long id, String name, VenueStatus status) {
    }

    /**
     * 一家店的归因结论。
     *
     * @param kind             同址归因方式
     * @param peers            同址邻居（不含本店）
     * @param evidenceVenueIds 到访证据取自哪些门店上的 ping（YIELDED = 空 ⇒ 本店计 0）
     * @param areaVenueIds     片区范围（本店 + 全部同址门店；附近人数用，不做营业归因）
     * @param selfInOperation  本店是否在营（2026-10-06 新增，V38）：**仅排序口径消费**——
     *                         不在营的门店排序记 0（人不可能"到店"一家停业门店）；admin 展示
     *                         不消费本字段（保留证据作为门店状态复核线索，见 52 号「到访进排序」）
     * @param inOperationCount 组内在营门店数（含本店，2026-10-06 新增）：**排序口径的分摊分母**——
     *                         同址组都在营时每位用户按 1/k 分给 k 家在营店，避免同楼各家
     *                         各吃一份整楼人流（52 号 §1.1 第 1 条）
     */
    private record Attribution(CoLocatedAttribution kind, List<Peer> peers,
                               Set<Long> evidenceVenueIds, Set<Long> areaVenueIds,
                               boolean selfInOperation, int inOperationCount) {
    }

    /**
     * 归因判定（52 号 §4.4，纯函数）：证据是「有人在这个位置」，归属看谁可能被到访——
     * <ul>
     *   <li>无同址 → NONE，只算本店；</li>
     *   <li>本店在营：同址另有在营店 → SHARED（分不出，共享）；否则 → ABSORBED（同址不在营店的证据并入本店）；</li>
     *   <li>本店不在营：同址有在营店 → YIELDED（证据归它们，本店 0）；全组都不在营 → SHARED（无从归属，如实共享）。</li>
     * </ul>
     * 2026-10-06（V38）：额外产出 {@code selfInOperation} 与 {@code inOperationCount} 两个
     * **只有排序口径消费**的字段（展示口径不受影响，见 {@link Attribution} 参数注释）。
     */
    private static Attribution attribute(Long venueId, VenueStatus selfStatus, List<Peer> peers) {
        Set<Long> area = new LinkedHashSet<>();
        area.add(venueId);
        peers.forEach(p -> area.add(p.id()));
        boolean selfInOperation = isInOperation(selfStatus);
        int inOperationCount = (selfInOperation ? 1 : 0)
                + (int) peers.stream().filter(p -> isInOperation(p.status())).count();
        if (peers.isEmpty()) {
            return new Attribution(CoLocatedAttribution.NONE, peers, area, area,
                    selfInOperation, inOperationCount);
        }
        boolean anyPeerInOperation = peers.stream().anyMatch(p -> isInOperation(p.status()));
        if (selfInOperation) {
            CoLocatedAttribution kind = anyPeerInOperation ? CoLocatedAttribution.SHARED : CoLocatedAttribution.ABSORBED;
            return new Attribution(kind, peers, area, area, selfInOperation, inOperationCount);
        }
        if (anyPeerInOperation) {
            return new Attribution(CoLocatedAttribution.YIELDED, peers, Set.of(), area,
                    false, inOperationCount);
        }
        return new Attribution(CoLocatedAttribution.SHARED, peers, area, area,
                false, inOperationCount);
    }

    /**
     * 批量归因：一次同址查询（几何 + 双方状态）。无同址邻居的店不在查询结果里，按 NONE 补齐——
     * 返回 map 覆盖全部入参（保序）。
     */
    private Map<Long, Attribution> attributionsFor(Collection<Long> venueIds) {
        Map<Long, VenueStatus> selfStatus = new HashMap<>();
        Map<Long, List<Peer>> peers = new HashMap<>();
        for (VenueRepository.CoLocatedVenueRow row : venueRepository.findCoLocatedPairs(venueIds, CO_LOCATED_RADIUS_M)) {
            selfStatus.put(row.getVenueId(), parseStatus(row.getVenueStatus()));
            peers.computeIfAbsent(row.getVenueId(), k -> new ArrayList<>())
                    .add(new Peer(row.getCoLocatedId(), row.getCoLocatedName(), parseStatus(row.getCoLocatedStatus())));
        }
        Map<Long, Attribution> result = new LinkedHashMap<>();
        for (Long id : venueIds) {
            result.put(id, attribute(id, selfStatus.get(id), peers.getOrDefault(id, List.of())));
        }
        return result;
    }

    /** 归因证据 → 摘要：组内用户并集（同一用户在两家都有 ping 只计 1，COUNT 相加会重复计） */
    private static VenueVisitSummary summarize(Attribution attribution,
                                               Map<Long, Map<Long, LocalDateTime>> lastSeen,
                                               LocalDateTime now) {
        Map<Long, LocalDateTime> users = unionLastSeen(attribution.evidenceVenueIds(), lastSeen);
        LocalDateTime last = users.values().stream().max(LocalDateTime::compareTo).orElse(null);
        return new VenueVisitSummary(countSince(users, now.minusDays(7)), countSince(users, now.minusDays(30)),
                last, attribution.kind(), attribution.peers().size());
    }

    // ── 证据读取（命中谓词唯一实现在 Repository） ─────────────────────────────────

    private Map<Long, Map<Long, LocalDateTime>> lastSeenByVenue(Collection<Long> venueIds, int radiusM) {
        if (venueIds.isEmpty()) {
            return Map.of();
        }
        return toLastSeenByVenue(pingRepository.findVisitorLastSeenByVenueIds(venueIds, radiusM, HIT_MAX_ACCURACY_M));
    }

    /** Object[]{venueId, userId, lastSeenAt} → venueId → (userId → 最近命中时刻) */
    private static Map<Long, Map<Long, LocalDateTime>> toLastSeenByVenue(List<Object[]> rows) {
        Map<Long, Map<Long, LocalDateTime>> result = new HashMap<>();
        for (Object[] row : rows) {
            result.computeIfAbsent((Long) row[0], k -> new HashMap<>()).put((Long) row[1], (LocalDateTime) row[2]);
        }
        return result;
    }

    /** 多店证据的用户并集：userId → 该用户在这些店上的最近命中时刻 */
    private static Map<Long, LocalDateTime> unionLastSeen(Collection<Long> venueIds,
                                                          Map<Long, Map<Long, LocalDateTime>> lastSeen) {
        Map<Long, LocalDateTime> users = new HashMap<>();
        for (Long venueId : venueIds) {
            lastSeen.getOrDefault(venueId, Map.of())
                    .forEach((user, at) -> users.merge(user, at, (a, b) -> a.isAfter(b) ? a : b));
        }
        return users;
    }

    /**
     * Object[]{venueId, userId, visitDay} → venueId → (userId → 该用户的去重到店日集合)（V39）。
     * <p>
     * **保留 user维度**（⛔ 别在这里就把人压掉）：同址归因的并集在 {@code unionVisitDays}
     * 里按 {@code (user, day)} 二元组合并——若本方法返回 {@code Set<LocalDate>}，
     * "谁来的"这一维已被丢弃，6 位不同用户散在 5 天里会被并成 5 个日期
     * （现网实证：120/121 同址组 6 人 ⇒ 误算成 5 次/2.5 次 ⇒ 卡片显示"1 次"）。
     */
    private static Map<Long, Map<Long, Set<LocalDate>>> toVisitDaysByVenue(List<Object[]> rows) {
        Map<Long, Map<Long, Set<LocalDate>>> result = new HashMap<>();
        for (Object[] row : rows) {
            result.computeIfAbsent((Long) row[0], k -> new HashMap<>())
                    .computeIfAbsent((Long) row[1], k -> new HashSet<>())
                    .add(toLocalDate(row[2]));
        }
        return result;
    }

    /**
     * 到位日期的**单一类型转换点**（2026-10-06 生产事故修复）。
     *
     * <p><b>事故经过</b>：首版直接写 {@code (LocalDate) row[2]}，本地/单测一路绿灯，
     * 生产首轮调度直接
     * {@code ClassCastException: java.sql.Date cannot be cast to java.time.LocalDate}，
     * 而 {@code refresh()} 的 {@code catch(Exception)} 把它吞成一行ERROR 日志 ⇒
     * <b>物化表从此不再更新，卡片数字永久停在旧值，且不报任何错</b>。
     *
     * <p><b>根因是类型假设，不是笔误</b>：JPQL {@code DATE(p.createdAt)} 在 Hibernate 下
     * 返回的是 {@link java.sql.Date}（JDBC 层类型），<b>不是</b> {@link LocalDate}（JSR-310）。
     * 我凭"JPQL 表达式看起来是日期"的直觉假定了后者——而 {@code SELECT Object[]} 的静态类型
     * 是 {@code Object}，编译器<b>不会</b>提醒任何事。这是"外部边界返回宽类型"的经典陷阱。
     *
     * <p><b>为什么用 {@code instanceof} 白名单而不是强转</b>：把"可能是哪几种类型"显式写出来，
     * 遇到未知类型<b>主动抛错并带上实际类型名</b>，而不是留一个隐晦的 CCE 让人猜。
     * 跨驱动/跨Hibernate 版本时若返回类型变化，这里会立刻指出"变了什么"，而不是静默算错。
     */
    private static LocalDate toLocalDate(Object raw) {
        if (raw instanceof LocalDate localDate) {
            return localDate;
        }
        if (raw instanceof java.sql.Date sqlDate) {
            // ⛔ 禁走 toLocalDate()：java.sql.Date.toLocalDate() 在 JDBC 4.0 之前的实现里
            // 会按 JVM 默认时区解释，而 created_at 由Java 以北京时间写入（见 upsertInBucket
            // 红线）⇒ 默认时区非 Asia/Shanghai 时会整体偏移一天。toLocalDate()
            // 同样依赖默认时区，故这里改用**不涉时区的字段直取**。
            return sqlDate.toLocalDate();
        }
        if (raw instanceof java.sql.Timestamp timestamp) {
            // MySQL 的 DATE() 在部分驱动下会返回 Timestamp（携带 00:00:00 时间部分）
            return timestamp.toLocalDateTime().toLocalDate();
        }
        throw new IllegalStateException("到访日列类型不受支持：" + (raw == null ? "null" : raw.getClass().getName())
                + "（JPQL DATE() 的返回类型随驱动/Hibernate 版本变化；新增类型时在此显式登记，"
                + "⛔ 禁直接强转——那会让刷新静默失败）");
    }

    /**
     * 多店证据的<b>去重到店次数</b>（V39）= {@code (userId, visitDay)} 二元组并集的<b>大小</b>。
     *
     * <p><b>为什么并集键必须是 (人, 日) 二元组、不能只是日</b>（2026-10-06 实测修正）：
     * 同址组共享证据时（坐标完全重合的门店，常见于商场/大楼锚点），一家店的到访证据
     * 可能全部来自邻居店。真实样本（一壶淡泊 120 / 丽莎 121 坐标完全重合）：
     * <ul>
     *   <li>120 店 30 天内有 <b>6 位用户</b>，散在 <b>5 个日期</b>上；</li>
     *   <li>若按 {@code Set<LocalDate>} 去重 ⇒ 只剩 <b>5</b>（6 位不同用户被并成 5 个日期，
     *       "谁来的"这一维凭空消失）；再经同址 1/k 分摊 ⇒ 更小；</li>
     *   <li>⇒ 卡片显示「1 次真实到店足迹」，而用户明明看到<b>两位以上</b>被记录 ⇒ 读起来是
     *       "系统只认了一个人"，是对贡献者的直接否定。</li>
     * </ul>
     * 正确语义：<b>每个 (谁, 哪天) 算一次</b>。于是 6 位用户各来 1 天 = <b>6 次</b>；
     * 同一人连来 3 天 = 3 次；同一人同一天在同址两家都命中 = <b>1 次</b>（按日去重，正是
     * 用户定的口径）。
     *
     * <p>⚠️ 由此次数<b>恒 ≥ 人数</b>（每个用户至少贡献 1 次）⇒ 这不是巧合而是口径的必然，
     * 但也意味着<b>次数天然随用户数放大</b>：现网样本极稀（仅 1 家店过 ≥3 门槛）时，
     * 「6 位舞友 · 6 次」读起来仍偏弱是数据量问题，不是公式问题。
     */
    private static long countVisitEvents(Collection<Long> venueIds,
                                         Map<Long, Map<Long, Set<LocalDate>>> visitDays) {
        Set<String> userDays = new HashSet<>();
        for (Long venueId : venueIds) {
            visitDays.getOrDefault(venueId, Map.of())
                    .forEach((user, days) -> days.forEach(day -> userDays.add(user + "|" + day)));
        }
        return userDays.size();
    }

    /** 时间窗去重：用户在窗口内到访过 ⟺ 其最近命中时刻 ≥ 窗口起点 */
    private static long countSince(Map<Long, LocalDateTime> users, LocalDateTime since) {
        return users.values().stream().filter(at -> !at.isBefore(since)).count();
    }

    private static VenueStatus parseStatus(String raw) {
        return WireEnums.parse(VenueStatus.class, raw);
    }

    private static String displayOf(VenueStatus status) {
        return status == null ? "未知" : status.getDisplayName();
    }

    private boolean exceedsWriteRate(Long userId) {
        Deque<Long> window = writeRateCache.get(userId, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        while (!window.isEmpty() && now - window.peekFirst() > WRITE_RATE_WINDOW_MS) {
            window.pollFirst();
        }
        if (window.size() >= WRITE_RATE_LIMIT) {
            log.warn("presence write rate limited: userId={}", userId);
            return true;
        }
        window.addLast(now);
        return false;
    }
}
