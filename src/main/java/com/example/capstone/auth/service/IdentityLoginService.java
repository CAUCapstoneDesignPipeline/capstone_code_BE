package com.example.capstone.auth.service;

import java.time.Clock;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.capstone.user.domain.AppUser;
import com.example.capstone.user.domain.UserIdentity;
import com.example.capstone.user.repository.AppUserRepository;
import com.example.capstone.user.repository.UserIdentityRepository;

@Service
public class IdentityLoginService {
    private final AppUserRepository users;
    private final UserIdentityRepository identities;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public IdentityLoginService(AppUserRepository users, UserIdentityRepository identities,
            Clock clock, PlatformTransactionManager manager) {
        this.users = users;
        this.identities = identities;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    public UUID login(String provider, String subject, String email, boolean verified, String name,
            boolean signupAllowed) {
        try {
            return transaction.execute(status -> {
                var existing = identities.findByProviderAndProviderSubject(provider, subject);
                if (existing.isPresent()) {
                    UUID id = existing.get().getUserId();
                    users.lockById(id).orElseThrow().loggedIn(clock.instant());
                    existing.get().loggedIn(clock.instant());
                    return id;
                }
                if (!signupAllowed) {
                    throw new SignupNotAllowedException();
                }
                var user = users.saveAndFlush(new AppUser(email, name, clock.instant()));
                identities.saveAndFlush(new UserIdentity(user.getId(), provider, subject, email, verified, clock.instant()));
                return user.getId();
            });
        } catch (DataIntegrityViolationException exception) {
            // The losing transaction rolls back its app_user before resolving the winning identity.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint
                        && "user_identity_subject_unique".equals(constraint.getConstraintName())) {
                    return transaction.execute(status -> identities.findByProviderAndProviderSubject(provider, subject)
                            .orElseThrow().getUserId());
                }
            }
            throw exception;
        }
    }

    public static class SignupNotAllowedException extends RuntimeException {
        public SignupNotAllowedException() { super("Signup is not allowed"); }
    }
}
