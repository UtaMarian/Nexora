package io.nexora.office;

import io.nexora.content.DocumentService;
import io.nexora.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * The published version of a file, for readers of a library that hides drafts.
 *
 * <p>The editor joins everyone with the same WOPISrc into one live session. Readers therefore get this separate,
 * read-only address: on {@code /wopi/files} they would watch an editor's unsaved draft.
 */
@RestController
@RequestMapping("/wopi/published/{id}")
public class WopiPublishedController {
    private final OfficeService office;
    private final DocumentService documents;

    public WopiPublishedController(OfficeService office, DocumentService documents) {
        this.office = office;
        this.documents = documents;
    }

    /** CheckFileInfo */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> checkFileInfo(@PathVariable UUID id, HttpServletRequest req) {
        String token = WopiController.token(req);
        CurrentUser user = office.userFor(token, id);
        return office.publishedInfo(user, id, office.originFor(token, id));
    }

    /** GetFile */
    @GetMapping("/contents")
    public ResponseEntity<InputStreamResource> getFile(@PathVariable UUID id, HttpServletRequest req) {
        CurrentUser user = office.userFor(WopiController.token(req), id);
        DocumentService.Download d = documents.openPublished(user, id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(d.sizeBytes())
                .header("X-WOPI-ItemVersion", d.sha256())
                .body(new InputStreamResource(d.stream()));
    }
}
