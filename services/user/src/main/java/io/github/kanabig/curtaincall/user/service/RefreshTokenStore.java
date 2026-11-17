package io.github.kanabig.curtaincall.user.service;

import io.github.kanabig.curtaincall.user.config.UserProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * [USR-2, USR-3] refresh token 저장소. 토큰은 추측할 수 없는 난수 문자열이고 Redis에는 SHA-256 해시를 키로 둔다(덤프가 새도 토큰을 못 쓴다).
 *
 * <p>로그인마다 토큰 계열(family)이 하나 생기고, 회전해도 같은 계열을 유지한다. 키는 셋이다.
 *
 * <ul>
 *   <li>{@code user:refresh:<hash>} = {@code userId:familyId}. 지금 쓸 수 있는 토큰. 계열 도입 전 토큰은 값이 {@code userId}뿐이다.
 *   <li>{@code user:refresh:used:<hash>} = {@code familyId:회전시각(ms, Redis TIME)}. 회전으로 폐기된 토큰의 흔적. 재사용을 알아보는 용도다.
 *   <li>{@code user:refresh:family:<familyId>} = 계열의 최신 토큰 해시. 계열을 폐기할 때 살아 있는 토큰을 찾는 용도다.
 * </ul>
 *
 * 회전(조회, 폐기, 흔적, 새 토큰 등록)과 재사용 판정·계열 폐기는 각각 Lua 스크립트 하나로 실행해 Redis 안에서 원자적이다. 같은 토큰으로 동시에 요청하면 하나만 새 토큰을 받고, 나머지는 재사용으로
 * 판정되지만, 회전 직후 유예 시간 안이면 계열은 두고 그 요청만 거절한다(탭 두 개가 동시에 회전하는 경우). 유예가 지난 뒤의 재사용은 계열 전체를 폐기한다.
 */
@Component
public class RefreshTokenStore {

    static final String KEY_PREFIX = "user:refresh:";
    static final String USED_PREFIX = "user:refresh:used:";
    static final String FAMILY_PREFIX = "user:refresh:family:";

    // 계열 키·현재 토큰 키를 ARGV 접두사로 만들어 접근하므로 단일 Redis 전제다(Redis Cluster에서는 동작하지 않는다).
    /** KEYS: 제시된 토큰, 흔적, 새 토큰. ARGV: TTL 초, 새 계열 ID(계열이 없는 토큰용), 계열 키 접두사, 새 토큰 해시, 토큰 키 접두사, 유예 밀리초. 시각은 Redis TIME. */
    private static final String ROTATE_SCRIPT = """
            local value = redis.call('GETDEL', KEYS[1])
            if value then
              local sep = string.find(value, ':', 1, true)
              local userId = value
              local family = ARGV[2]
              if sep then
                userId = string.sub(value, 1, sep - 1)
                family = string.sub(value, sep + 1)
              end
              local t = redis.call('TIME')
              local now = t[1] * 1000 + math.floor(t[2] / 1000)
              redis.call('SET', KEYS[2], family .. ':' .. now, 'EX', ARGV[1])
              redis.call('SET', KEYS[3], userId .. ':' .. family, 'EX', ARGV[1])
              redis.call('SET', ARGV[3] .. family, ARGV[4], 'EX', ARGV[1])
              return {'ROTATED', userId}
            end
            local used = redis.call('GET', KEYS[2])
            if used then
              local sep = string.find(used, ':', 1, true)
              local family = used
              local rotatedAt = nil
              if sep then
                family = string.sub(used, 1, sep - 1)
                rotatedAt = tonumber(string.sub(used, sep + 1))
              end
              local t = redis.call('TIME')
              local now = t[1] * 1000 + math.floor(t[2] / 1000)
              if rotatedAt and now - rotatedAt <= tonumber(ARGV[6]) then
                return {'GRACE', family}
              end
              local current = redis.call('GET', ARGV[3] .. family)
              if current then redis.call('DEL', ARGV[5] .. current) end
              redis.call('DEL', ARGV[3] .. family)
              return {'REUSED', family}
            end
            return {'UNKNOWN'}
            """;

    /** KEYS: 제시된 토큰, 흔적. ARGV: 계열 키 접두사, 토큰 키 접두사. 현재 토큰이든 이미 쓴 토큰이든 그 계열을 폐기한다. */
    private static final String REVOKE_SCRIPT = """
            local family
            local value = redis.call('GET', KEYS[1])
            if value then
              local sep = string.find(value, ':', 1, true)
              if sep then family = string.sub(value, sep + 1) end
              redis.call('DEL', KEYS[1])
            else
              local used = redis.call('GET', KEYS[2])
              if used then
                local sep = string.find(used, ':', 1, true)
                family = sep and string.sub(used, 1, sep - 1) or used
              end
            end
            if family then
              local current = redis.call('GET', ARGV[1] .. family)
              if current then redis.call('DEL', ARGV[2] .. current) end
              redis.call('DEL', ARGV[1] .. family)
            end
            return 1
            """;

    /** 회전 결과. 새 토큰은 ROTATED일 때만 있고, 재사용이면 계열이 이미 폐기됐다. */
    public record Rotation(Outcome outcome, Long userId, String newToken, String familyId) {

        public enum Outcome {
            ROTATED,
            /** 회전된 지 오래된 토큰이 다시 왔다. 계열이 폐기됐다. */
            REUSED,
            /** 회전된 직후(유예 시간 안)에 다시 왔다. 계열은 그대로이고 이 요청만 거절한다. */
            GRACE,
            UNKNOWN
        }
    }

    private final StringRedisTemplate redis;
    private final UserProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final DefaultRedisScript<List> rotateScript = new DefaultRedisScript<>(ROTATE_SCRIPT, List.class);
    private final DefaultRedisScript<Long> revokeScript = new DefaultRedisScript<>(REVOKE_SCRIPT, Long.class);

    public RefreshTokenStore(StringRedisTemplate redis, UserProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 로그인: 새 계열의 첫 토큰. */
    public String issue(long userId) {
        String token = newToken();
        String family = newToken();
        redis.opsForValue().set(key(token), userId + ":" + family, properties.refreshTtl());
        redis.opsForValue().set(FAMILY_PREFIX + family, hash(token), properties.refreshTtl());
        return token;
    }

    /** 토큰을 폐기하고 같은 계열의 새 토큰을 발급한다. 이미 회전된 토큰이면 계열 전체를 폐기한다. */
    @SuppressWarnings("unchecked")
    public Rotation rotate(String token) {
        String newToken = newToken();
        String newHash = hash(newToken);
        List<Object> result = redis.execute(
                rotateScript,
                List.of(key(token), USED_PREFIX + hash(token), KEY_PREFIX + newHash),
                String.valueOf(properties.refreshTtl().toSeconds()),
                newToken(),
                FAMILY_PREFIX,
                newHash,
                KEY_PREFIX,
                String.valueOf(properties.refreshReuseGrace().toMillis()));
        String outcome = String.valueOf(result.get(0));
        return switch (outcome) {
            case "ROTATED" ->
                new Rotation(Rotation.Outcome.ROTATED, Long.valueOf(String.valueOf(result.get(1))), newToken, null);
            case "REUSED" -> new Rotation(Rotation.Outcome.REUSED, null, null, String.valueOf(result.get(1)));
            case "GRACE" -> new Rotation(Rotation.Outcome.GRACE, null, null, String.valueOf(result.get(1)));
            default -> new Rotation(Rotation.Outcome.UNKNOWN, null, null, null);
        };
    }

    /** 로그아웃: 이 토큰의 계열을 폐기한다. 없는 토큰이어도 성공으로 본다. */
    public void revoke(String token) {
        redis.execute(revokeScript, List.of(key(token), USED_PREFIX + hash(token)), FAMILY_PREFIX, KEY_PREFIX);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String key(String token) {
        return KEY_PREFIX + hash(token);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
