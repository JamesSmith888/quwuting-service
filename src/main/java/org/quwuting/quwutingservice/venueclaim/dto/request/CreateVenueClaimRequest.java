package org.quwuting.quwutingservice.venueclaim.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 门店认领申请请求体。
 * <p>
 * 申请材料（2026-08-11 决策定稿，见需求「认领舞厅」）：
 * <ul>
 *   <li>realName 真实姓名（必填）——一次性身份核验材料，仅入认领工单表
 *       （决策 D1），不写 qwt_users；</li>
 *   <li>contactPhone 手机号（必填）——审核联系用，11 位中国大陆手机号格式；</li>
 *   <li>contactWechat 微信号（选填）——替代/补充手机号的联系通道；</li>
 *   <li>licenseUrls 营业执照照片 URL 列表（选填，最多 3 张）——证明与门店
 *       的经营归属关系；</li>
 *   <li>note 补充说明（选填，最多 200 字）。</li>
 * </ul>
 * 门店基础信息（名称/城市/地址）<b>不在认领表单中提交</b>——认领是身份归属
 * 申请而非数据编辑，门店数据由平台维护，认领通过后才获得编辑权（走
 * venue-create 编辑模式）。表单中的门店信息为只读展示，防止申请人借认领
 * 篡改平台数据（2026-08-11 设计决策，见前端 AGENTS.md「认领舞厅」）。
 */
public record CreateVenueClaimRequest(
        @NotBlank(message = "真实姓名不能为空")
        @Size(max = 50, message = "真实姓名最多 50 字")
        String realName,

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1\\d{10}$", message = "请输入正确的手机号")
        String contactPhone,

        @Size(max = 50, message = "微信号最多 50 字")
        String contactWechat,

        @Size(max = 3, message = "营业执照最多上传 3 张")
        List<String> licenseUrls,

        @Size(max = 200, message = "补充说明最多 200 字")
        String note
) {}
