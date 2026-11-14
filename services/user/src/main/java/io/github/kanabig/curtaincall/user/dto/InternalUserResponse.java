package io.github.kanabig.curtaincall.user.dto;

import io.github.kanabig.curtaincall.user.entity.User;

/** notification이 발송에 쓰는 최소 정보. */
public record InternalUserResponse(long id, String email, String name) {

    public static InternalUserResponse from(User user) {
        return new InternalUserResponse(user.getId(), user.getEmail(), user.getName());
    }
}
