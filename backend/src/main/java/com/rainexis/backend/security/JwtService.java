package com.rainexis.backend.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TUser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 令牌服务
 * 负责生成、解析和验证 JWT Token
 * Token 使用 HMAC-SHA256 签名，Payload 中包含用户身份信息和 CSRF Token
 */
@Service
public class JwtService {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private final ObjectMapper objectMapper;
    /** HMAC签名密钥 */
    private final byte[] secret;
    /** Token过期时间（秒） */
    private final long expirationSeconds;

    public JwtService(ObjectMapper objectMapper,
                      @Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-hours}") long expirationHours) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expirationSeconds = expirationHours * 3600;
    }

    /**
     * 为用户创建 JWT Token
     * Payload包含：用户ID、用户名、角色、姓名、班级、tokenVersion、CSRF Token、签发时间、过期时间
     */
    public String createToken(TUser user) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.getId());
        payload.put("username", user.getUsername());
        payload.put("role", user.getRole());
        payload.put("realName", user.getRealName());
        payload.put("className", user.getClassName());
        payload.put("tokenVersion", currentTokenVersion(user));
        payload.put("csrfToken", csrfToken(user));
        payload.put("iat", now);
        payload.put("exp", now + expirationSeconds);
        String body = encodeJson(header) + "." + encodeJson(payload);
        return body + "." + sign(body);
    }

    /**
     * 解析并验证 JWT Token
     * @return 解析后的认证用户信息
     * @throws BusinessException 如果Token无效或已过期
     */
    public AuthUser parse(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !sign(parts[0] + "." + parts[1]).equals(parts[2])) {
                throw BusinessException.unauthorized("Token 无效");
            }
            Map<String, Object> payload = objectMapper.readValue(URL_DECODER.decode(parts[1]), new TypeReference<>() {
            });
            long exp = ((Number) payload.get("exp")).longValue();
            if (Instant.now().getEpochSecond() > exp) {
                throw BusinessException.unauthorized("Token 已过期");
            }
            return new AuthUser(
                    ((Number) payload.get("sub")).longValue(),
                    stringValue(payload.get("username")),
                    stringValue(payload.get("role")),
                    stringValue(payload.get("realName")),
                    stringValue(payload.get("className")),
                    tokenVersion(payload.get("tokenVersion")),
                    stringValue(payload.get("csrfToken"))
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.unauthorized("Token 无效");
        }
    }

    /** 将 Map 序列化为 JSON 并进行 Base64URL 编码 */
    private String encodeJson(Map<String, Object> value) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT 序列化失败", ex);
        }
    }

    /** 使用 HMAC-SHA256 对数据进行签名 */
    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return URL_ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT 签名失败", ex);
        }
    }

    /** 安全获取对象字符串值 */
    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    /** 获取用户当前的 Token 版本号 */
    private int currentTokenVersion(TUser user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    /** 从Token解析的Payload中提取tokenVersion，为null表示密码已修改、旧Token失效 */
    private Integer tokenVersion(Object value) {
        if (value == null) {
            throw BusinessException.unauthorized("Token 已失效");
        }
        return ((Number) value).intValue();
    }

    /** 生成用户的 CSRF Token，用于防跨站请求伪造 */
    public String csrfToken(TUser user) {
        return sign("csrf:" + user.getId() + ":" + currentTokenVersion(user));
    }
}
