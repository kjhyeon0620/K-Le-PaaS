package klepaas.backend.user.dto;

import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;

import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String email,
        String name,
        Role role,
        LocalDateTime createdAt
) {
    public static UserResponse from(User entity) {
        return new UserResponse(
                entity.getId(),
                entity.getEmail(),
                entity.getName(),
                entity.getRole(),
                entity.getCreatedAt()
        );
    }
}
