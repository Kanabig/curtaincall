package io.github.kanabig.curtaincall.user.dto;

/** {@code expiresIn}은 access token 유효 시간(초). */
public record TokenResponse(String accessToken, String refreshToken, long expiresIn) {

    @Override
    public String toString() {
        return "TokenResponse[expiresIn=" + expiresIn + "]";
    }
}
