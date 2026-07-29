package com.jarylee.medicalagent.auth;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID hospitalId, String username,
                                Set<Role> roles, boolean forcePasswordChange) {
    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
