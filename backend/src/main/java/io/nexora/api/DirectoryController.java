package io.nexora.api;

import io.nexora.common.ApiException;
import io.nexora.identity.sso.DirectorySyncService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Administration of the directory (AD/LDAP via Keycloak) sync. Admin only (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/directory")
public class DirectoryController {
    private final DirectorySyncService sync;

    public DirectoryController(DirectorySyncService sync) {
        this.sync = sync;
    }

    public record DirectoryStatus(boolean enabled, DirectorySyncService.Status status) {}

    @GetMapping
    public DirectoryStatus status() {
        return new DirectoryStatus(sync.enabled(), sync.status());
    }

    @PostMapping("/sync")
    public DirectoryStatus syncNow() {
        if (!sync.enabled()) throw new ApiException(HttpStatus.CONFLICT, "sync_disabled", "Directory sync is not configured");
        return new DirectoryStatus(true, sync.run());
    }
}
