package io.github.kanabig.curtaincall.user.dto;

import io.github.kanabig.curtaincall.user.entity.Role;
import io.github.kanabig.curtaincall.user.entity.User;

public record UserResponse(long id, String email, String name, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole());
    }
}
