package io.github.kanabig.curtaincall.user.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.common.web.metrics.MetricNames;
import io.github.kanabig.curtaincall.user.dto.LoginRequest;
import io.github.kanabig.curtaincall.user.dto.TokenResponse;
import io.github.kanabig.curtaincall.user.entity.User;
import io.github.kanabig.curtaincall.user.error.UserErrorCode;
import io.github.kanabig.curtaincall.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** [USR-2, USR-3] 로그인, refresh token 회전(재사용 감지 포함), 로그아웃. 트랜잭션은 조회뿐이라 Repository 호출 단위로 충분하다. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 없는 이메일도 BCrypt 비교를 한 번 해서 응답 시간으로 가입 여부를 알아내지 못하게 한다. */
    private final String dummyHash;

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenStore refreshTokens;
    private final MeterRegistry meters;

    public AuthService(
            UserRepository repository,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokenIssuer,
            RefreshTokenStore refreshTokens,
            MeterRegistry meters) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokens = refreshTokens;
        this.meters = meters;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    public TokenResponse login(LoginRequest request) {
        User user =
                repository.findByEmail(UserService.normalize(request.email())).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !matches) {
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }
        return issueTokens(user);
    }

    /**
     * 한 번 쓴 refresh token은 폐기되고 같은 계열의 새 토큰이 나간다. 같은 토큰으로 동시에 오면 하나만 성공한다. 이미 회전된 토큰이 다시 오면 탈취를 의심해 그 계열의 토큰을 모두 폐기하고 같은
     * 401로 응답한다.
     */
    public TokenResponse refresh(String refreshToken) {
        RefreshTokenStore.Rotation rotation = refreshTokens.rotate(refreshToken);
        switch (rotation.outcome()) {
            case REUSED -> {
                // 토큰 값은 남기지 않는다. 계열 ID도 앞 8자만
                log.warn("refresh token reuse detected, family revoked family={}", shortId(rotation.familyId()));
                meters.counter(MetricNames.USER_REFRESH_REUSE_DETECTED).increment();
                throw new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN);
            }
            case UNKNOWN -> throw new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN);
            default -> {}
        }
        // 탈퇴·삭제된 회원이면 새 토큰을 주지 않는다. 역할은 재발급 시점의 값을 쓴다
        User user = repository.findById(rotation.userId()).orElse(null);
        if (user == null) {
            refreshTokens.revoke(rotation.newToken());
            throw new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN);
        }
        return new TokenResponse(
                accessTokenIssuer.issue(user), rotation.newToken(), accessTokenIssuer.expiresInSeconds());
    }

    /** 이 토큰의 계열을 폐기한다. 없는 토큰이어도 성공으로 본다(멱등). */
    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    private static String shortId(String id) {
        return id == null ? "-" : id.substring(0, Math.min(8, id.length()));
    }

    private TokenResponse issueTokens(User user) {
        return new TokenResponse(
                accessTokenIssuer.issue(user), refreshTokens.issue(user.getId()), accessTokenIssuer.expiresInSeconds());
    }
}
