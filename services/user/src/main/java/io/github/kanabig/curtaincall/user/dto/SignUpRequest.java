package io.github.kanabig.curtaincall.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignUpRequest(
        @NotBlank @Email @Size(max = 254) String email,
        // BCrypt는 72바이트까지만 해시한다. 길이는 서비스에서 바이트 기준으로 한 번 더 확인한다
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 50) String name) {

    /** 로그에 비밀번호가 찍히지 않게 한다. */
    @Override
    public String toString() {
        return "SignUpRequest[email=" + email + ", password=****, name=" + name + "]";
    }
}
