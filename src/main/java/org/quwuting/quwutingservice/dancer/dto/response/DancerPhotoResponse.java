package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.dancer.enums.DancerPhotoKind;
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoStatus;

import java.time.LocalDateTime;

/**
 * 舞伴相册照片条目（详情页/编辑页数据源）。
 * <p>
 * 可见性已在服务层过滤：非本人请求仅返回 PUBLIC；本人/管理员返回全部状态
 * （本人编辑页据 status 渲染「待审核/已公开/已驳回」徽标并可删除）。
 * status 前端渲染徽标用（非本人视角恒 PUBLIC）。
 * <p>
 * 积分解锁（2026-08-14 公共模块）：
 * <ul>
 *   <li>{@code cost}：查看该照片所需积分（0 = 无门槛，直接可见）；</li>
 *   <li>{@code unlocked}：当前用户是否已解锁（本人/管理员恒 true——管理者
 *       天然可见自己内容；已解锁/无门槛时 url 恒下发）；</li>
 *   <li><b>有门槛且未解锁 → url 置 null</b>（不下发原图，防绕过），前端渲染
 *       <b>模糊图（blurUrl）</b>——上传时前端 canvas 降采样生成的低分辨率版本
 *       （"模糊可见轮廓"，Telegram 打遮罩语义，不可还原为原图）；blurUrl 缺失
 *       （旧数据/生成失败）时前端回退纯锁占位；解锁成功经 POST /points/unlock
 *       响应返回原图 URL。</li>
 * </ul>
 */
public record DancerPhotoResponse(
        Long id,
        String url,
        DancerPhotoStatus status,
        int sortOrder,
        LocalDateTime createdAt,
        /** 查看所需积分（0 = 无门槛；本人/管理员视角恒可看，cost 仅为展示） */
        int cost,
        /** 当前用户是否已解锁（本人/管理员恒 true；匿名恒 false） */
        boolean unlocked,
        /** 模糊图 URL（未解锁时的遮罩占位图；可空 = 回退纯锁占位） */
        String blurUrl,
        /** 媒体类型（2026-08-22：PHOTO 照片 / VIDEO 短视频；展示端据此分支渲染） */
        DancerPhotoKind kind,
        /** 视频封面帧图 URL（2026-08-22 新增，仅 kind=VIDEO 有值；可空 = 回退虚焦占位） */
        String coverUrl,
        /** 视频时长（秒，2026-08-22 新增，仅 kind=VIDEO 有值；零值 = 未知不展示） */
        int durationSeconds
) {}
