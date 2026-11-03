package io.nexora.identity;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Spring Security view of a NEXORA user; carries the stable principal id used by the ACL engine. */
public class NexoraUserDetails implements UserDetails {
    private final UUID id;
    private final String login;
    private final String displayName;
    private final String passwordHash;
    private final boolean admin;
    private final boolean enabled;

    public NexoraUserDetails(Principal p) {
        this.id = p.getId();
        this.login = p.getLogin();
        this.displayName = p.getDisplayName();
        this.passwordHash = p.getPasswordHash();
        this.admin = p.isAdmin();
        this.enabled = p.isEnabled();
    }

    public UUID getId() { return id; }
    public String getDisplayName() { return displayName; }
    public boolean isAdmin() { return admin; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return admin
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return login; }
    @Override public boolean isEnabled() { return enabled; }
}
