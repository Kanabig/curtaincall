package io.github.kanabig.curtaincall.user.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.common.web.error.CommonErrorCode;
import io.github.kanabig.curtaincall.user.dto.SignUpRequest;
import io.github.kanabig.curtaincall.user.entity.Role;
import io.github.kanabig.curtaincall.user.entity.User;
import io.github.kanabig.curtaincall.user.error.UserErrorCode;
import io.github.kanabig.curtaincall.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder, Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** [USR-1] 일반 가입은 항상 USER. */
    @Transactional
    public User signUp(SignUpRequest request) {
        return create(request.email(), request.password(), request.name(), Role.USER);
    }

    /** [USR-4] ADMIN은 시드 계정으로만 만든다. 이미 있으면 건드리지 않는다. 동시에 시드하면 EMAIL_DUPLICATED가 나갈 수 있다. */
    @Transactional
    public void seedAdmin(String email, String password) {
        if (repository.existsByEmail(normalize(email))) {
            return;
        }
        create(email, password, "관리자", Role.ADMIN);
    }

    public User get(long userId) {
        return repository.findById(userId).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    /** 이메일은 소문자·trim으로 정규화해 저장·조회한다. */
    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private User create(String rawEmail, String password, String name, Role role) {
        // BCrypt는 72바이트 뒤를 무시한다. 잘리는 비밀번호를 받아 주지 않는다
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "비밀번호는 72바이트 이하여야 한다");
        }
        String email = normalize(rawEmail);
        if (repository.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_DUPLICATED);
        }
        User user = new User(email, passwordEncoder.encode(password), name.trim(), role, clock.instant());
        try {
            return repository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // existsByEmail과 insert 사이에 같은 이메일이 들어온 경우
            throw new BusinessException(UserErrorCode.EMAIL_DUPLICATED);
        }
    }
}
