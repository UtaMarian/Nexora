package io.nexora.workflow;

import io.nexora.identity.CurrentUser;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The account rules act as. It cannot sign in. Its changes never trigger rules, which is what makes a rule that changes
 * an item safe: no rule can start a loop, however rules are combined.
 */
@Component
public class WorkflowIdentity {
    public static final String LOGIN = "nexora-workflow";
    private static final String NAME = "Workflow";

    private final PrincipalRepository principals;
    private volatile CurrentUser user;

    public WorkflowIdentity(PrincipalRepository principals) {
        this.principals = principals;
    }

    public CurrentUser user() {
        CurrentUser u = user;
        if (u == null) {
            Principal p = principals.findByKindAndLogin(Principal.Kind.USER, LOGIN).orElseGet(() -> {
                Principal created = Principal.user(LOGIN, NAME, null);
                created.setEnabled(false);
                return principals.save(created);
            });
            u = user = new CurrentUser(p.getId(), p.getLogin(), p.getDisplayName(), true);
        }
        return u;
    }

    public boolean is(UUID id) {
        return id != null && id.equals(user().id());
    }
}
