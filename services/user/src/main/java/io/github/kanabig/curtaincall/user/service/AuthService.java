package io.github.kanabig.curtaincall.user.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.user.dto.LoginRequest;
import io.github.kanabig.curtaincall.user.dto.TokenResponse;
import io.github.kanabig.curtaincall.user.entity.User;
import io.github.kanabig.curtaincall.user.error.UserErrorCode;
import io.github.kanabig.curtaincall.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** [USR-2, USR-3] 로그인, refresh token 회전, 로그아웃. 트랜잭션은 조회뿐이라 Repository 호출 단위로 충분하다. */
@Service
public class AuthService {

    /** 없는 이메일도 BCrypt 비교를 한 번 해서 응답 시간으로 가입 여부를 알아내지 못하게 한다. */
    private final String dummyHash;

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenStore refreshTokens;

    public AuthService(
            UserRepository repository,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokenIssuer,
            RefreshTokenStore refreshTokens) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokens = refreshTokens;
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

    /** 한 번 쓴 refresh token은 폐기된다. 같은 토큰으로 동시에 오면 하나만 성공한다. */
    public TokenResponse refresh(String refreshToken) {
        long userId = refreshTokens
                .consume(refreshToken)
                .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN));
        // 탈퇴·삭제된 회원이면 새 토큰을 주지 않는다. 역할은 재발급 시점의 값을 쓴다
        User user = repository
                .findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN));
        return issueTokens(user);
    }

    /** 없는 토큰이어도 성공으로 본다(멱등). */
    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    private TokenResponse issueTokens(User user) {
        return new TokenResponse(
                accessTokenIssuer.issue(user), refreshTokens.issue(user.getId()), accessTokenIssuer.expiresInSeconds());
    }
}
