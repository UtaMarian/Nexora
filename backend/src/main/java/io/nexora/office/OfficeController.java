package io.nexora.office;

import io.nexora.identity.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

/** Browser-facing API for Office editing. */
@RestController
@RequestMapping("/api/v1")
public class OfficeController {
    private final OfficeService office;
    private final WopiDiscovery discovery;
    private final OfficeProperties props;

    public OfficeController(OfficeService office, WopiDiscovery discovery, OfficeProperties props) {
        this.office = office;
        this.discovery = discovery;
        this.props = props;
    }

    public record Capabilities(boolean enabled, boolean available, Set<String> edit, Set<String> view) {}

    public record SessionRequest(String origin, Boolean view) {}

    /** Which formats can be opened in the browser editor. */
    @GetMapping("/office")
    public Capabilities capabilities() {
        boolean available = discovery.available();
        return new Capabilities(props.enabled(), available,
                available ? discovery.extensions("edit") : Set.of(),
                available ? discovery.extensions("view") : Set.of());
    }

    /**
     * Starts or joins a browser editing session. The response is posted by the web app into the editor's
     * iframe (WOPI host page pattern); everyone editing the same file lands in one co-authoring session.
     */
    @PostMapping("/nodes/{id}/office-session")
    public OfficeService.Session session(@PathVariable UUID id, @RequestBody(required = false) SessionRequest body) {
        return office.open(CurrentUser.get(), id, body == null ? null : body.origin(),
                body != null && Boolean.TRUE.equals(body.view()));
    }
}
