package io.nexora.api;

import io.nexora.api.Dtos.*;
import io.nexora.common.ApiException;
import io.nexora.content.DocumentService;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Upload, download, check-out/in and version history. */
@RestController
@RequestMapping("/api/v1/nodes/{id}")
public class FileController {
    private final DocumentService documents;
    private final NodeMapper mapper;

    public FileController(DocumentService documents, NodeMapper mapper) {
        this.documents = documents;
        this.mapper = mapper;
    }

    /** Uploads one or more files into a library or folder ({@code multipart/form-data}, field {@code file}). */
    @PostMapping(path = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<NodeDto> upload(@PathVariable UUID id,
                                @RequestParam("file") List<MultipartFile> files,
                                @RequestParam(defaultValue = "false") boolean overwrite,
                                @RequestParam(required = false) String comment) {
        CurrentUser user = CurrentUser.get();
        List<Node> saved = new ArrayList<>();
        for (MultipartFile f : files) {
            Path tmp = spool(f);
            try {
                saved.add(documents.upload(user, id, f.getOriginalFilename(), f.getContentType(), tmp, overwrite, comment));
            } finally {
                deleteQuietly(tmp);
            }
        }
        return mapper.many(user, saved);
    }

    /** Creates a blank Word, Excel or PowerPoint file, or a copy of a template. */
    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeDto newDocument(@PathVariable UUID id, @RequestBody io.nexora.api.Dtos.NewDocumentRequest body) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, documents.createDocument(user, id, body.type(), body.name(), body.templateId()));
    }

    /** Replaces a file's content with the raw request body (handy for scripts and, later, WebDAV PUT). */
    @PutMapping("/content")
    public NodeDto replaceContent(@PathVariable UUID id, HttpServletRequest request,
                                  @RequestHeader(value = "X-Nexora-Comment", required = false) String comment) {
        CurrentUser user = CurrentUser.get();
        Path tmp = spool(request);
        try {
            return mapper.one(user, documents.saveContent(user, id, request.getContentType(), tmp, comment));
        } finally {
            deleteQuietly(tmp);
        }
    }

    @GetMapping("/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id,
                                                        @RequestParam(required = false) UUID versionId,
                                                        @RequestParam(defaultValue = "false") boolean download) {
        return stream(documents.open(CurrentUser.get(), id, versionId), download);
    }

    @PostMapping("/checkout")
    public NodeDto checkOut(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, documents.checkOut(user, id));
    }

    @PostMapping("/checkin")
    public NodeDto checkIn(@PathVariable UUID id, @RequestBody(required = false) CheckInRequest body) {
        CurrentUser user = CurrentUser.get();
        CheckInRequest b = body == null ? new CheckInRequest(null, false) : body;
        return mapper.one(user, documents.checkIn(user, id, b.comment(), b.major()));
    }

    @PostMapping("/discard-checkout")
    public NodeDto discardCheckOut(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, documents.discardCheckOut(user, id));
    }

    @PostMapping("/publish")
    public NodeDto publish(@PathVariable UUID id, @RequestBody(required = false) CommentRequest body) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, documents.publish(user, id, body == null ? null : body.comment()));
    }

    @GetMapping("/versions")
    public List<VersionDto> versions(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        Node file = documents.requireFile(user, id);
        return mapper.versions(file, documents.history(user, id));
    }

    @PostMapping("/versions/{versionId}/restore")
    public NodeDto restore(@PathVariable UUID id, @PathVariable UUID versionId,
                           @RequestBody(required = false) CommentRequest body) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, documents.restoreVersion(user, id, versionId, body == null ? null : body.comment()));
    }

    @DeleteMapping("/versions/{versionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVersion(@PathVariable UUID id, @PathVariable UUID versionId) {
        documents.deleteVersion(CurrentUser.get(), id, versionId);
    }

    // ---- helpers ----

    private static ResponseEntity<InputStreamResource> stream(DocumentService.Download d, boolean attachment) {
        ContentDisposition cd = (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(d.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Nexora-Version", d.versionLabel())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox") // uploaded HTML/SVG must never run as our origin
                .eTag("\"" + d.sha256() + "\"")
                .contentType(MediaType.parseMediaType(d.mimeType() == null ? "application/octet-stream" : d.mimeType()))
                .contentLength(d.sizeBytes())
                .body(new InputStreamResource(d.stream()));
    }

    private static Path spool(MultipartFile f) {
        if (f.getOriginalFilename() == null || f.getOriginalFilename().isBlank()) {
            throw ApiException.badRequest("invalid_name", "Uploaded file has no name");
        }
        try {
            Path tmp = Files.createTempFile("nexora-upload-", ".bin");
            try (InputStream in = f.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            return tmp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path spool(HttpServletRequest request) {
        try {
            Path tmp = Files.createTempFile("nexora-upload-", ".bin");
            try (InputStream in = request.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            return tmp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temp file cleanup is best-effort
        }
    }
}
