package io.github.kanabig.curtaincall.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kanabig.curtaincall.testsupport.TestContainersConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;

/** [USR-1~4] 가입, 로그인, 재발급·폐기, 조회. 컨테이너는 공유되므로 테스트마다 새 이메일을 쓴다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthIntegrationTests {

    static final String PASSWORD = "S3cret-pass-word";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    StringRedisTemplate redis;

    @Value("${curtaincall.jwt.access-secret}")
    String secret;

    @Test
    @DisplayName("[USR-1] 가입하면 201과 회원 정보를 돌려주고 비밀번호는 응답에 없다")
    void signUp(CapturedOutput output) {
        String email = email();

        ResponseEntity<String> response = signUpRaw(email, PASSWORD, "홍길동");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .contains("\"role\":\"USER\"")
                .contains(email)
                .doesNotContain(PASSWORD);
        assertThat(response.getBody()).doesNotContainIgnoringCase("password");
        assertThat(output.getAll()).doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("[USR-1] 이메일은 대소문자를 구분하지 않아 중복 가입은 409 EMAIL_DUPLICATED")
    void duplicatedEmail() {
        String email = email();
        signUpRaw(email, PASSWORD, "홍길동");

        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/api/v1/users",
                body("email", email.toUpperCase(), "password", PASSWORD, "name", "다른 사람"),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("EMAIL_DUPLICATED");
    }

    @Test
    @DisplayName("[USR-1] 같은 이메일로 동시에 가입하면 하나만 201이고 나머지는 409")
    void concurrentSignUp() throws Exception {
        String email = email();

        List<HttpStatus> results = runConcurrently(
                8,
                () -> HttpStatus.valueOf(
                        signUpRaw(email, PASSWORD, "동시").getStatusCode().value()));

        assertThat(results).filteredOn(s -> s == HttpStatus.CREATED).hasSize(1);
        assertThat(results).filteredOn(s -> s == HttpStatus.CONFLICT).hasSize(7);
    }

    @Test
    @DisplayName("[USR-1] 형식이 틀린 입력은 400 INVALID_REQUEST")
    void invalidInput() {
        assertThat(signUpRaw("not-an-email", PASSWORD, "a").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(signUpRaw(email(), "short", "a").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 한글은 글자당 3바이트라 24자면 72바이트를 넘는다
        ResponseEntity<JsonNode> tooLong = rest.postForEntity(
                "/api/v1/users", body("email", email(), "password", "가".repeat(25), "name", "a"), JsonNode.class);
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("[USR-1] 비밀번호는 BCrypt 해시로 저장된다")
    void passwordIsHashed(@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc) {
        String email = email();
        signUpRaw(email, PASSWORD, "홍길동");

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);

        assertThat(hash).startsWith("$2a$10$").doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("[USR-2] 로그인하면 HS256 access token(30분, sub·role·jti)과 refresh token을 받는다")
    void login(CapturedOutput output) {
        String email = email();
        signUpRaw(email, PASSWORD, "홍길동");

        ResponseEntity<JsonNode> response = login(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("expiresIn").asLong()).isEqualTo(1800);
        Jwt jwt = decoder().decode(body.get("accessToken").asText());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(jwt.getSubject()).matches("\\d+");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(1800));
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(body.get("refreshToken").asText()).isNotBlank();
        assertThat(output.getAll())
                .doesNotContain(PASSWORD)
                .doesNotContain(body.get("refreshToken").asText());
    }

    @Test
    @DisplayName("[USR-2] 틀린 비밀번호와 없는 이메일은 같은 401 INVALID_CREDENTIALS")
    void invalidCredentials() {
        String email = email();
        signUpRaw(email, PASSWORD, "홍길동");

        ResponseEntity<JsonNode> wrongPassword = login(email, "wrong-password-1");
        ResponseEntity<JsonNode> unknownEmail = login(email(), PASSWORD);

        for (ResponseEntity<JsonNode> response : List.of(wrongPassword, unknownEmail)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        }
    }

    @Test
    @DisplayName("[USR-3] 재발급하면 새 토큰이 나오고 쓴 refresh token은 폐기된다")
    void refreshRotates() {
        JsonNode first = signUpAndLogin();

        ResponseEntity<JsonNode> second = refresh(first.get("refreshToken").asText());
        ResponseEntity<JsonNode> reuse = refresh(first.get("refreshToken").asText());

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("refreshToken").asText())
                .isNotEqualTo(first.get("refreshToken").asText());
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuse.getBody().get("code").asText()).isEqualTo("INVALID_REFRESH_TOKEN");
        // 새 토큰은 계속 쓸 수 있다
        assertThat(refresh(second.getBody().get("refreshToken").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @RepeatedTest(10)
    @DisplayName("[USR-3] 같은 refresh token으로 동시에 재발급하면 하나만 성공한다")
    void concurrentRefresh() throws Exception {
        String token = signUpAndLogin().get("refreshToken").asText();

        List<HttpStatus> results = runConcurrently(
                6, () -> HttpStatus.valueOf(refresh(token).getStatusCode().value()));

        assertThat(results).filteredOn(s -> s == HttpStatus.OK).hasSize(1);
        assertThat(results).filteredOn(s -> s == HttpStatus.UNAUTHORIZED).hasSize(5);
    }

    @Test
    @DisplayName("[USR-3] 로그아웃하면 refresh token을 쓸 수 없고, 다시 로그아웃해도 204")
    void logout() {
        String token = signUpAndLogin().get("refreshToken").asText();

        ResponseEntity<Void> first = rest.postForEntity("/api/v1/auth/logout", body("refreshToken", token), Void.class);
        ResponseEntity<Void> again = rest.postForEntity("/api/v1/auth/logout", body("refreshToken", token), Void.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("[USR-2] refresh token은 14일 TTL로 해시 키에만 저장된다")
    void refreshTokenStoredHashedWithTtl() {
        String token = signUpAndLogin().get("refreshToken").asText();

        assertThat(redis.keys("user:refresh:*" + token + "*")).isEmpty();
        Long ttl = redis.getExpire("user:refresh:" + sha256(token), java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ttl).isBetween(14L * 24 * 3600 - 60, 14L * 24 * 3600);
    }

    @Test
    @DisplayName("[USR-4] ADMIN은 시드 계정으로만 존재하고 access token의 role이 ADMIN이다")
    void adminSeed() {
        ResponseEntity<JsonNode> response = login("admin@curtaincall.test", "admin-password-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Jwt jwt = decoder().decode(response.getBody().get("accessToken").asText());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("[USR-4] 가입 요청에 role을 실어도 USER로 만들어진다")
    void roleInRequestIsIgnored() {
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/api/v1/users",
                body("email", email(), "password", PASSWORD, "name", "x", "role", "ADMIN"),
                JsonNode.class);

        assertThat(response.getBody().get("role").asText()).isEqualTo("USER");
    }

    @Test
    @DisplayName("내 정보와 내부 조회")
    void meAndInternal() {
        String email = email();
        signUpRaw(email, PASSWORD, "홍길동");
        long id = idOf(email);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", String.valueOf(id));
        ResponseEntity<JsonNode> me = rest.exchange(
                "/api/v1/users/me", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        ResponseEntity<JsonNode> internal = rest.getForEntity("/internal/users/" + id, JsonNode.class);
        ResponseEntity<JsonNode> missing = rest.getForEntity("/internal/users/999999999", JsonNode.class);

        assertThat(me.getBody().get("email").asText()).isEqualTo(email);
        assertThat(internal.getBody().get("name").asText()).isEqualTo("홍길동");
        assertThat(internal.getBody().has("role")).isFalse();
        assertThat(internal.getBody().toString()).doesNotContainIgnoringCase("password");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("code").asText()).isEqualTo("USER_NOT_FOUND");
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long idOf(String email) {
        return jdbc.queryForObject("SELECT user_id FROM users WHERE email = ?", Long.class, email);
    }

    private JsonNode signUpAndLogin() {
        String email = email();
        signUpRaw(email, PASSWORD, "홍길동");
        return login(email, PASSWORD).getBody();
    }

    private ResponseEntity<String> signUpRaw(String email, String password, String name) {
        return rest.postForEntity(
                "/api/v1/users", body("email", email, "password", password, "name", name), String.class);
    }

    private ResponseEntity<JsonNode> login(String email, String password) {
        return rest.postForEntity("/api/v1/auth/login", body("email", email, "password", password), JsonNode.class);
    }

    private ResponseEntity<JsonNode> refresh(String token) {
        return rest.postForEntity("/api/v1/auth/refresh", body("refreshToken", token), JsonNode.class);
    }

    private NimbusJwtDecoder decoder() {
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private static String email() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static Map<String, String> body(String... keyValues) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
