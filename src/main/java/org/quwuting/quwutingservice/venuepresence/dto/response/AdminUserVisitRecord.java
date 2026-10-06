package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 管理端「某用户的一次到店」记录（2026-10-06，GET /admin/users/{userId}/visits 的行；仅 ADMIN）。
 * <p>
 * 定位：把到访从<b>计数</b>还原成<b>一次次具体到店</b>——什么时候到的、在店里待了多久、
 * 期间采样了几次。这是 admin 侧第一次能回答「他到底来过几次、每次多久」，
 * 此前只能看到「近 30 天 N 人」这样的聚合数。
 * <p>
 * <b>一次到店 = 连续命中桶的合并</b>（不是「一条 ping」也不是「一个自然日」）：
 * 采集主力是每次打开小程序（onShow）+ 店内每 15 分钟补采一次，
 * 同一次到店会在库里留下<b>多个 15 分钟桶</b>；若不合并，一次跳舞 3 小时会被记成 12 次到店。
 * 合并规则 = 相邻桶间隔 ≤ {@code VISIT_SESSION_GAP_BUCKETS}（服务端常量），
 * 阈值语义与论证见 {@code VenuePresenceService}。
 * <p>
 * ⛔ 全字段 {@code @JsonInclude(ALWAYS)}（全局 non_null 会删 null，35 号教训）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminUserVisitRecord(
        Long venueId,
        /** 门店名（运营据名字回忆现场；跨城同名由 city 消歧） */
        String venueName,
        /** 门店所在城市（同名门店消歧用；可能为 null） */
        String venueCity,
        /** 门店营业状态展示文案（服务端权威）——到访发生在那时，与<b>当前</b>状态可能不同 */
        String venueStatusDisplay,
        /**
         * 本次到店的到店时刻 = 首个命中桶的 {@code created_at}（桶内首见时刻，非采样时刻）。
         * <p>
         * ⚠️ 这是<b>首次被记录到</b>的时刻，不是物理上跨进店门的时刻——用户到店后
         * 第一次打开小程序才留痕。没打开小程序的到店<b>不在此列</b>
         * （这也是全站到访数字「显著低于真实到店量」的根因，52 号 §信任边界）。
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime arrivedAt,
        /**
         * 本次到店最后一次被记录到的时刻 = 末个命中桶的 {@code updated_at}（桶内末次触发）。
         * 与 {@link #arrivedAt} 之差 = <b>已观测停留时长</b>，下界是真实时长
         * （最后一次采样后人还在店里，库内无从得知）。
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastSeenAt,
        /**
         * 已观测停留时长（分钟，= lastSeenAt − arrivedAt，<b>至少 0</b>）。
         * 单桶记录必为 0——桶内多次触发会刷新 {@code updated_at}，但只有 15 分钟窗宽。
         */
        long stayMinutes,
        /**
         * 本次到店覆盖的命中桶数 = 已观测采样次数。
         * <b>1 = 只被记录过一次</b>（可能只是路过时点开了一下小程序，停留时长不可考）。
         */
        int sampleCount,
        /**
         * 本次到店的最小距离（米，端侧自报）。
         * <p>
         * <b>为什么下发距离</b>：命中半径 150m 内仍可能是「在商场里、店在另一层」，
         * 距离是运营判断「是否真在店里」唯一的直接证据（52 号 §信任边界论证依赖它）。
         * 它<b>不是</b>坐标——用户经纬度在协议上不存在，距离是端侧自报的标量。
         */
        Integer minDistanceM) {
}
