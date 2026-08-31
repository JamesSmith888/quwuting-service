package org.quwuting.quwutingservice.webauth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.auth.dto.response.LoginResponse;
import org.quwuting.quwutingservice.auth.service.WechatService;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.JwtUtil;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.mapper.UserInfoMapper;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.webauth.dto.response.CreateSessionResponse;
import org.quwuting.quwutingservice.webauth.dto.response.PollSessionResponse;
import org.quwuting.quwutingservice.webauth.entity.WebLoginSession;
import org.quwuting.quwutingservice.webauth.repository.WebLoginSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Web 管理后台登录服务（2026-08-31）。
 * <p>
 * 双通道：
 * <ol>
 *   <li><b>扫码登录（主）</b>：createSession 生成会话 + 小程序码 → 用户微信扫码打开
 *       小程序「确认登录」页 → confirm（校验 ADMIN）签发 JWT 暂存 → 网页 poll 取走
 *       （一次性，防重放）。</li>
 *   <li><b>账号密码（兜底）</b>：passwordLogin 校验服务器配置
 *       web-auth.username / web-auth.password（密码走环境变量 WEB_ADMIN_PASSWORD），
 *       不依赖用户表，为扫码链路故障时的应急通道。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebAuthService {

    /** 会话 TTL：5 分钟，超时需重新扫码 */
    static final int SESSION_TTL_SECONDS = 5 * 60;
    /** 会话状态 */
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_CONFIRMED = "CONFIRMED";
    static final String STATUS_REJECTED = "REJECTED";
    static final String STATUS_EXPIRED = "EXPIRED";
    /**
     * 小程序码 scene 前缀。scene 格式：单字符前缀 + 29 位 hex 会话 ID = 30 字符
     * （微信 getwxacodeunlimit 的 scene 上限 32 字符；前缀用于校验 scene 为我方生成）。
     */
    private static final String SCENE_PREFIX = "w";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebLoginSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final WechatService wechatService;
    private final JwtUtil jwtUtil;
    private final UserInfoMapper userInfoMapper;

    @Value("${web-auth.username:admin}")
    private String webAuthUsername;

    /** 空 = 禁用密码登录（只允许扫码） */
    @Value("${web-auth.password:}")
    private String webAuthPassword;

    @Value("${web-auth.qrcode-page:pages/admin-web-login/admin-web-login}")
    private String qrcodePage;

    @Value("${web-auth.qrcode-env:release}")
    private String qrcodeEnv;

    /** 生成新会话 + 小程序码。 */
    @Transactional
    public CreateSessionResponse createSession() {
        String sessionId = newSessionId();
        WebLoginSession session = new WebLoginSession();
        session.setSessionId(sessionId);
        session.setStatus(STATUS_PENDING);
        session.setExpiresAt(LocalDateTime.now().plusSeconds(SESSION_TTL_SECONDS));
        sessionRepository.save(session);

        byte[] png = wechatService.getUnlimitedQrCode(SCENE_PREFIX + sessionId, qrcodePage, qrcodeEnv);
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        return new CreateSessionResponse(sessionId, dataUrl, SESSION_TTL_SECONDS);
    }

    /** 小程序内确认登录（仅平台管理员；普通用户扫码确认被拒）。 */
    @Transactional
    public void confirm(String sessionId) {
        UserContext.requireAdmin(); // 非 ADMIN 直接 403
        WebLoginSession session = requirePendingSession(sessionId);
        Long userId = UserContext.getCurrentUserId();

        String token = jwtUtil.generateToken(userId, UserRole.ADMIN);
        session.setStatus(STATUS_CONFIRMED);
        session.setUserId(userId);
        session.setTokenIssued(token);
        log.info("[webauth] session {} confirmed by uid={}", sessionId, userId);
    }

    /** 小程序内拒绝登录。 */
    @Transactional
    public void reject(String sessionId) {
        UserContext.requireAuth();
        WebLoginSession session = requirePendingSession(sessionId);
        session.setStatus(STATUS_REJECTED);
        log.info("[webauth] session {} rejected by uid={}", sessionId, UserContext.getCurrentUserId());
    }

    /** 网页轮询会话状态；CONFIRMED 且未取走过 token 时返回 token（一次性）。 */
    @Transactional
    public PollSessionResponse poll(String sessionId) {
        WebLoginSession session = sessionRepository.findBySessionIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(404, "登录会话不存在或已失效"));

        // 惰性过期：PENDING 且超时 → EXPIRED
        if (STATUS_PENDING.equals(session.getStatus())
                && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus(STATUS_EXPIRED);
            return new PollSessionResponse(STATUS_EXPIRED, null, null, null);
        }

        if (STATUS_CONFIRMED.equals(session.getStatus())) {
            String token = session.getTokenIssued();
            if (token != null) {
                // 一次性下发：取走即清空，防重放
                session.setTokenIssued(null);
                String nickname = session.getUserId() == null ? null
                        : userRepository.findById(session.getUserId())
                            .map(User::getNickname).orElse(null);
                return new PollSessionResponse(STATUS_CONFIRMED, token, session.getUserId(), nickname);
            }
            // token 已被取走过（上一次轮询拿到）：不再重复下发
            return new PollSessionResponse(STATUS_CONFIRMED, null, session.getUserId(), null);
        }

        return new PollSessionResponse(session.getStatus(), null, session.getUserId(), null);
    }

    /** 账号密码登录（扫码链路兜底）。 */
    @Transactional(readOnly = true)
    public LoginResponse passwordLogin(String username, String password) {
        if (webAuthPassword == null || webAuthPassword.isBlank()) {
            throw new BusinessException(1003, "密码登录未启用（扫码登录为主通道）");
        }
        if (!constantTimeEquals(username, webAuthUsername)
                || !constantTimeEquals(password, webAuthPassword)) {
            log.warn("[webauth] password login failed (bad credentials)");
            throw new BusinessException(1003, "账号或密码错误");
        }
        User admin = userRepository.findFirstByRoleAndDeletedFalse(UserRole.ADMIN)
                .orElseThrow(() -> new BusinessException(1003, "平台管理员账号不存在"));
        String token = jwtUtil.generateToken(admin.getId(), admin.getRole());
        log.info("[webauth] password login success: uid={}", admin.getId());
        return new LoginResponse(token, userInfoMapper.toResponse(admin));
    }

    // ---- 辅助 ----

    private WebLoginSession requirePendingSession(String sessionId) {
        WebLoginSession session = sessionRepository.findBySessionIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(404, "登录会话不存在或已失效"));
        if (!STATUS_PENDING.equals(session.getStatus())) {
            throw new BusinessException(409, "登录会话已被处理（" + session.getStatus() + "），请重新扫码");
        }
        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus(STATUS_EXPIRED);
            throw new BusinessException(408, "登录会话已过期，请重新扫码");
        }
        return session;
    }

    /** 生成 29 位随机 hex（+ 1 字符前缀 = 30 ≤ 32，满足 scene 上限；防枚举）。 */
    private static String newSessionId() {
        byte[] bytes = new byte[15];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(29);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.substring(0, 29);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ba, bb);
    }
}
