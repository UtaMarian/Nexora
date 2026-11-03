package io.nexora.identity;

import io.nexora.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** The authenticated caller, as seen by services. */
public record CurrentUser(UUID id, String login, String displayName, boolean admin) {

    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof NexoraPrincipal u) {
            return new CurrentUser(u.getId(), u.getLogin(), u.getDisplayName(), u.isAdmin());
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Not signed in");
    }

    /** For background jobs and bootstrap code that run outside a request. */
    public static CurrentUser system(Principal p) {
        return new CurrentUser(p.getId(), p.getLogin(), p.getDisplayName(), true);
    }
}
