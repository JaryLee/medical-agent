package com.jarylee.medicalagent.auth;

public interface CurrentUserProvider {
    AuthenticatedUser requireUser();
}
