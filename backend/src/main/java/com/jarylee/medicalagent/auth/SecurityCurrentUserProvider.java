package com.jarylee.medicalagent.auth;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityCurrentUserProvider implements CurrentUserProvider {
    @Override
    public AuthenticatedUser requireUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthenticatedUser user) return user;
        throw new org.springframework.security.access.AccessDeniedException("未认证");
    }
}
