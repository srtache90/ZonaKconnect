package com.zonak.portal.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtCookieService {
    public static final String COOKIE_NAME = "ZONAK_AUTH";

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long ttlSeconds;
    private final boolean secureCookie;

    public JwtCookieService(
            ObjectMapper objectMapper,
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.ttl-seconds:28800}") long ttlSeconds,
            @Value("${security.cookie.secure:false}") boolean secureCookie
    ) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
        this.secureCookie = secureCookie;
    }

    public String createToken(AuthenticatedUser user) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.username());
        claims.put("uid", user.id().toString());
        claims.put("role", user.role());
        claims.put("tenantIds", user.tenantIds().stream().map(Object::toString).toList());
        claims.put("iat", now);
        claims.put("exp", now + ttlSeconds);

        String unsignedToken = base64Json(header) + "." + base64Json(claims);
        return unsignedToken + "." + base64Url(sign(unsignedToken));
    }

    public Map<String, Object> verifyToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("JWT inválido");
            }

            String unsignedToken = parts[0] + "." + parts[1];
            String expectedSignature = base64Url(sign(unsignedToken));
            if (!constantTimeEquals(expectedSignature, parts[2])) {
                throw new IllegalArgumentException("firma JWT inválida");
            }

            Map<String, Object> claims = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[1]),
                    new TypeReference<>() {
                    }
            );
            Number exp = (Number) claims.get("exp");
            if (exp == null || exp.longValue() < Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException("JWT expirado");
            }

            return claims;
        } catch (Exception ex) {
            throw new IllegalArgumentException("JWT inválido", ex);
        }
    }

    public String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }

    public void writeAuthCookie(HttpServletResponse response, String token) {
        response.addHeader("Set-Cookie", "%s=%s; Path=/; Max-Age=%d; HttpOnly; SameSite=Lax%s".formatted(
                COOKIE_NAME,
                token,
                ttlSeconds,
                secureCookie ? "; Secure" : ""
        ));
    }

    public void clearAuthCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", "%s=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax%s".formatted(
                COOKIE_NAME,
                secureCookie ? "; Secure" : ""
        ));
    }

    @SuppressWarnings("unchecked")
    public List<String> tenantIds(Map<String, Object> claims) {
        Object tenantIds = claims.get("tenantIds");
        return tenantIds instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();
    }

    private String base64Json(Map<String, Object> value) {
        try {
            return base64Url(objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible serializar JWT", ex);
        }
    }

    private byte[] sign(String unsignedToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible firmar JWT", ex);
        }
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigestSafe.equals(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static final class MessageDigestSafe {
        private static boolean equals(byte[] left, byte[] right) {
            if (left.length != right.length) {
                return false;
            }

            int result = 0;
            for (int i = 0; i < left.length; i++) {
                result |= left[i] ^ right[i];
            }

            return result == 0;
        }
    }
}
