package org.quwuting.quwutingservice.dancer.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建/编辑舞伴资料请求体（创建与编辑共用同一领域对象 = 全量覆盖可编辑字段，
 * 与 venue 域 CreateVenueRequest 复用于 create/update 的模式一致——资料是可演进
 * 的持续对象，不是一次性表单；见 AGENTS.md「舞伴生态体系 · 本人编辑」）。
 * <p>
 * 三条通道共用本请求：
 * <ul>
 *   <li>{@code POST /dancers}（舞伴主动注册）：createdBy = 当前用户，status = PENDING（审核中）；</li>
 *   <li>{@code PUT /dancers/{id}}（舞伴本人/管理员编辑）：全量覆盖可编辑字段；
 *       REJECTED 资料编辑后自动回到 PENDING（重新送审，兑现驳回通知"可修改资料后重新提交"承诺）；</li>
 *   <li>{@code POST /admin/dancers}（后台创建）：createdBy = 管理员，status = NORMAL（可信来源直通）。</li>
 * </ul>
 * 隐私边界：昵称/简介/常驻城市均为公开展示资料，不采集联系方式与私人信息；
 * homeVenueId 为可选常驻舞厅（校验存在性后落 DancerVenue 关系表，编辑时 = HOME 关系的
 * 完整替换语义：传 null 清除全部 HOME）。
 */
public record UpsertDancerRequest(
        @NotBlank(message = "昵称不能为空")
        @Size(max = 30, message = "昵称最长30个字符")
        String nickname,

        @Size(max = 500, message = "头像地址过长")
        String avatarUrl,

        @Size(max = 300, message = "简介最长300个字符")
        String bio,

        /** 性别（可选，null = 未声明，前端不展示） */
        @Size(max = 20, message = "性别字段过长")
        String gender,

        @Size(max = 50, message = "城市最长50个字符")
        String city,

        /**
         * 多城市（2026-08-14 新增，最多 3 个，可空 = 回退 city 单值兼容旧客户端）：
         * 城市选择器支持一个舞伴常驻/活跃于最多 3 个城市；首个 = 主城市
         * （service 冗余同步 dancer.city，列表/详情/分享展示位零改动）。
         * 服务层负责去重/去空/上限校验（子表 qwt_dancer_cities，V29 迁移）。
         */
        List<@Size(max = 50, message = "城市最长50个字符") String> cities,

        /**
         * 当前所在城市（2026-08-26 新增，V48 迁移；可空 = 未填城市/存量舞伴）：
         * 从已选常驻城市（cities）中再选中一个作为舞伴当前所在城市——用户在
         * 「解锁联系方式-是否同城」时据此判断是否同城。服务层校验：cities 非空
         * 时须为已选城市之一（显式非法值 → 1001「当前所在城市须为常驻城市之一」；
         * 缺省回退主城市兼容旧客户端/存量数据）；cities 为空（纯线上舞伴）时
         * 本字段须为空。
         */
        @Size(max = 50, message = "城市最长50个字符")
        String currentCity,

        /**
         * 联系方式图片（2026-08-14 新增，二维码等，可选；null/空串 = 不填）。
         * 与 contact 同一门槛/遮挡语义（详情页三态一致）；入库前必须经
         * storage/ImageContentValidator 内容校验（08-12 安全加固约定）。
         */
        @Size(max = 500, message = "联系方式图片地址过长")
        String contactImageUrl,

        /**
         * 联系方式（2026-08-14 新增，微信号等，可选；null/空串 = 不填）。
         * 属于资料的一部分随审核流程走（管理员可见）；公开后是否直接展示由
         * 积分门槛决定（qwt_points_gates，target_type='DANCER_CONTACT'——
         * 门槛设置独立走 POST /points/gates，不在本请求内）。
         */
        @Size(max = 100, message = "联系方式最长100个字符")
        String contact,

        /**
         * 联系方式遮挡开关（2026-08-14 新增，null = 默认遮挡 true）：
         * true = 详情页打码展示（无门槛点击直显 / 有门槛积分解锁后显示）；
         * false = 不遮挡，联系方式直接展示。与积分门槛正交（见 V28 迁移）。
         */
        Boolean hideContact,

        /**
         * 创作者收益计划开关（2026-08-14 新增，null = 关闭）：
         * 开启后详情页接入激励视频广告，收益平台线下转账结算（见 AGENTS.md
         * 「舞伴生态 · 创作者收益计划」）。属资料配置，随资料审核流程走。
         */
        Boolean earningsEnabled,

        /**
         * 加好友需告知位置（2026-08-26 新增，null = 关闭）：
         * 开启后用户获取联系方式前须二选一表态「同城 / 非同城·自行前往」
         * （相对关系而非真实地址，不收集坐标/区划/门牌——合规安全，
         * 见 UserLocationOption javadoc）。per-dancer 开关，仅需要确认用户
         * 能否到达服务地点的舞伴开启（服务范围 location_scope 配套）。
         */
        Boolean requireUserLocation,

        /**
         * 资料标签（2026-08-24 新增，管理员设置）：通用标签字典 {@code qwt_tag_dict} 的
         * id 数组（如 [1,3]），可选；null/空列表 = 无标签。编辑为<b>全量覆盖语义</b>
         * （传空 = 清除全部标签，与多城市/常驻舞厅同「编辑 = 变更而非追加」约定）。
         * 服务层负责去重/去空/存在性校验（不存在或重复的 id 剔除，不阻断提交）。
         */
        List<Long> profileTags,

        /** 常驻舞厅 ID（可选；落 DancerVenue HOME 关系，仅作"常去"展示，不构成强绑定） */
        Long homeVenueId
) {}
