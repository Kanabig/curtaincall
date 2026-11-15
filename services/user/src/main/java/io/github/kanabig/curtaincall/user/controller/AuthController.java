package io.github.kanabig.curtaincall.user.controller;

import io.github.kanabig.curtaincall.user.dto.LoginRequest;
import io.github.kanabig.curtaincall.user.dto.RefreshTokenRequest;
import io.github.kanabig.curtaincall.user.dto.TokenResponse;
import io.github.kanabig.curtaincall.user.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** [USR-2, USR-3] */
@RestController
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return service.login(request);
    }

    @PostMapping("/api/v1/auth/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return service.refresh(request.refreshToken());
    }

    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        service.logout(request.refreshToken());
    }
}
