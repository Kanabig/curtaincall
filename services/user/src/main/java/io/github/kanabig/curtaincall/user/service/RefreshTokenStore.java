package io.github.kanabig.curtaincall.user.service;

import io.github.kanabig.curtaincall.user.config.UserProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * [USR-2, USR-3] refresh token 저장소. 토큰은 추측할 수 없는 난수 문자열이고 Redis에는 SHA-256 해시를 키로 둔다(덤프가 새도 토큰을 못 쓴다).
 * 회전은 GETDEL 한 번으로 조회와 폐기를 묶어, 같은 토큰으로 동시에 요청해도 하나만 성공한다.
 */
@Component
public class RefreshTokenStore {

    static final String KEY_PREFIX = "user:refresh:";

    private final StringRedisTemplate redis;
    private final UserProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenStore(StringRedisTemplate redis, UserProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public String issue(long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(token), String.valueOf(userId), properties.refreshTtl());
        return token;
    }

    /** 토큰을 폐기하며 소유자를 돌려준다. 없거나 이미 쓴 토큰이면 비어 있다. */
    public Optional<Long> consume(String token) {
        String userId = redis.opsForValue().getAndDelete(key(token));
        return Optional.ofNullable(userId).map(Long::valueOf);
    }

    public void revoke(String token) {
        redis.delete(key(token));
    }

    private static String key(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
