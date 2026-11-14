package io.github.kanabig.curtaincall.user.controller;

import io.github.kanabig.curtaincall.user.dto.InternalUserResponse;
import io.github.kanabig.curtaincall.user.dto.SignUpRequest;
import io.github.kanabig.curtaincall.user.dto.UserResponse;
import io.github.kanabig.curtaincall.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** [USR-1, USR-4] 가입과 내 정보. 사용자 ID는 gateway가 검증해 넣은 헤더를 믿는다. */
@RestController
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signUp(@Valid @RequestBody SignUpRequest request) {
        return UserResponse.from(service.signUp(request));
    }

    @GetMapping("/api/v1/users/me")
    public UserResponse me(@RequestHeader("X-User-Id") long userId) {
        return UserResponse.from(service.get(userId));
    }

    /** [NTF-1] notification 전용. gateway는 /internal/**을 외부에 노출하지 않는다. */
    @GetMapping("/internal/users/{id}")
    public InternalUserResponse internal(@PathVariable long id) {
        return InternalUserResponse.from(service.get(id));
    }
}
