package io.nexora.api;

import io.nexora.api.Dtos.*;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.IdentityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class PrincipalController {
    private final IdentityService identity;

    public PrincipalController(IdentityService identity) {
        this.identity = identity;
    }

    /** People picker: users and groups matching {@code q}. */
    @GetMapping("/principals")
    public List<PrincipalRef> search(@RequestParam(defaultValue = "") String q) {
        return identity.search(q).stream().filter(p -> !io.nexora.identity.AnonymousAccess.LOGIN.equals(p.getLogin())).map(PrincipalRef::of).toList();
    }

    @GetMapping("/principals/{id}/members")
    public List<PrincipalRef> members(@PathVariable UUID id) {
        return identity.members(id).stream().map(PrincipalRef::of).toList();
    }

    @PostMapping("/principals/{id}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@PathVariable UUID id, @Valid @RequestBody AddMemberRequest body) {
        identity.addMember(CurrentUser.get(), id, body.principalId());
    }

    @DeleteMapping("/principals/{id}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID id, @PathVariable UUID memberId) {
        identity.removeMember(CurrentUser.get(), id, memberId);
    }

    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    public PrincipalRef createUser(@Valid @RequestBody CreateUserRequest body) {
        return PrincipalRef.of(identity.createUser(CurrentUser.get(), body.login(), body.displayName(), body.email(),
                body.password(), body.admin()));
    }

    @PostMapping("/admin/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public PrincipalRef createGroup(@Valid @RequestBody CreateGroupRequest body) {
        return PrincipalRef.of(identity.createGroup(CurrentUser.get(), body.name(), body.displayName()));
    }
}
