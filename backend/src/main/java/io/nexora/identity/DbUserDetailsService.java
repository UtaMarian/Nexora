package io.nexora.identity;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Local-account authentication. Directory users (AD/LDAP via Keycloak, planned) have no password
 * hash here and cannot sign in with a local password.
 */
@Service
public class DbUserDetailsService implements UserDetailsService {
    private final PrincipalRepository principals;

    public DbUserDetailsService(PrincipalRepository principals) {
        this.principals = principals;
    }

    @Override
    public UserDetails loadUserByUsername(String login) {
        Principal p = principals.findByKindAndLogin(Principal.Kind.USER, login)
                .filter(u -> u.getPasswordHash() != null)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        return new NexoraUserDetails(p);
    }
}
