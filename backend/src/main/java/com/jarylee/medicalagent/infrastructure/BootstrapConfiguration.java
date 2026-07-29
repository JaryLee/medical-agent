package com.jarylee.medicalagent.infrastructure;

import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.auth.IdentityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class BootstrapConfiguration implements ApplicationRunner {
    private final IdentityRepository repository;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public BootstrapConfiguration(IdentityRepository repository, PasswordEncoder encoder,
                                  @Value("${medical.bootstrap.admin-username:}") String username,
                                  @Value("${medical.bootstrap.admin-password:}") String password) {
        this.repository = repository;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!username.isBlank() && !password.isBlank()
                && repository.findUser(null, username).isEmpty()) {
            var admin = new IdentityRepository.UserData(UUID.randomUUID(), null, username,
                    encoder.encode(password), Set.of(Role.PLATFORM_ADMIN),
                    true, true, 0, null);
            repository.insertUser(admin);
        }
    }
}
