package io.nexora.office;

import io.nexora.common.ApiException;
import io.nexora.content.DocumentService;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * WOPI host endpoints called server-to-server by the Office editor (not by browsers).
 * Authentication is the {@code access_token} issued by {@link OfficeService#open}; every call re-checks
 * the user's current permissions and goes through {@link DocumentService}, so versioning, check-out rules
 * and the audit log apply exactly as for uploads.
 */
@RestController
@RequestMapping("/wopi/files/{id}")
public class WopiController {
    private final OfficeService office;
    private final DocumentService documents;

    public WopiController(OfficeService office, DocumentService documents) {
        this.office = office;
        this.documents = documents;
    }

    /** CheckFileInfo */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> checkFileInfo(@PathVariable UUID id, HttpServletRequest req) {
        String token = token(req);
        CurrentUser user = office.userFor(token, id);
        return office.fileInfo(user, id, office.originFor(token, id));
    }

    /** GetFile */
    @GetMapping("/contents")
    public ResponseEntity<InputStreamResource> getFile(@PathVariable UUID id, HttpServletRequest req) {
        CurrentUser user = office.userFor(token(req), id);
        DocumentService.Download d = documents.open(user, id, null);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(d.sizeBytes())
                .header("X-WOPI-ItemVersion", d.sha256())
                .body(new InputStreamResource(d.stream()));
    }

    /** PutFile: each save from the editor becomes a new version (or updates the user's private draft). */
    @PostMapping("/contents")
    public ResponseEntity<Map<String, Object>> putFile(@PathVariable UUID id, HttpServletRequest req,
                                                       @RequestHeader HttpHeaders headers) {
        CurrentUser user = office.userFor(token(req), id);
        Node file = documents.requireFile(user, id);
        if (!office.canWrite(user, file)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Read-only"));
        }

        String expected = first(headers, "X-COOL-WOPI-Timestamp", "X-LOOL-WOPI-Timestamp");
        if (expected != null && !office.ownDraft(user, file) && !sameInstant(expected, office.timestamp(file))) {
            // Someone else changed the file outside this editing session: let the editor ask the user.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("COOLStatusCode", 1010));
        }

        boolean autosave = "true".equalsIgnoreCase(first(headers, "X-COOL-WOPI-IsAutosave", "X-LOOL-WOPI-IsAutosave"));
        Path tmp = spool(req);
        try {
            Node saved = documents.saveContent(user, id, null, tmp,
                    autosave ? "Autosaved in the browser editor" : "Saved in the browser editor");
            return ResponseEntity.ok(Map.of("LastModifiedTime", office.timestamp(saved)));
        } catch (ApiException e) {
            if (e.status() == HttpStatus.LOCKED) return ResponseEntity.status(HttpStatus.CONFLICT).build();
            throw e;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort temp cleanup
            }
        }
    }

    /** Lock operations: locks are not supported (SupportsLocks=false), so the editor should not send these. */
    @PostMapping
    public ResponseEntity<Void> fileOperation(@PathVariable UUID id, HttpServletRequest req,
                                              @RequestHeader(value = "X-WOPI-Override", required = false) String op) {
        office.userFor(token(req), id);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    private static String token(HttpServletRequest req) {
        String t = req.getParameter("access_token");
        if (t == null) {
            String auth = req.getHeader(HttpHeaders.AUTHORIZATION);
            if (auth != null && auth.startsWith("Bearer ")) t = auth.substring(7);
        }
        return t;
    }

    private static String first(HttpHeaders h, String... names) {
        for (String n : names) {
            String v = h.getFirst(n);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static boolean sameInstant(String a, String b) {
        try {
            return Instant.parse(a).toEpochMilli() == Instant.parse(b).toEpochMilli();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Path spool(HttpServletRequest req) {
        try {
            Path tmp = Files.createTempFile("nexora-wopi-", ".bin");
            try (InputStream in = req.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            return tmp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
