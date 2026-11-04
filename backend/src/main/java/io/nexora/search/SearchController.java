package io.nexora.search;

import io.nexora.identity.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

/** Full-text search for everyone, and its Central Administration page ({@code /api/v1/admin/**} needs the admin role). */
@RestController
@RequestMapping("/api/v1")
public class SearchController {
    private final SearchService search;
    private final SearchAdminService admin;

    public SearchController(SearchService search, SearchAdminService admin) {
        this.search = search;
        this.admin = admin;
    }

    @GetMapping("/search")
    public SearchService.Response search(@RequestParam(defaultValue = "") String q,
                                         @RequestParam(defaultValue = "") List<String> type,
                                         @RequestParam(defaultValue = "") List<String> site,
                                         @RequestParam(defaultValue = "") List<String> author,
                                         @RequestParam(required = false) String modified,
                                         @RequestParam(defaultValue = "relevance") String sort,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        SearchQuery.Sort order = sort.toLowerCase(Locale.ROOT).equals("modified")
                ? SearchQuery.Sort.MODIFIED : SearchQuery.Sort.RELEVANCE;
        return search.search(CurrentUser.get(), q, new SearchQuery.Filters(type, site, author, modified), order, page,
                Math.min(Math.max(size, 1), 50));
    }

    @GetMapping("/admin/search")
    public SearchAdminService.Status status() {
        return admin.status();
    }

    @PostMapping("/admin/search/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void rebuild() {
        admin.rebuild(CurrentUser.get());
    }

    @PostMapping("/admin/search/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry() {
        admin.retryUnread(CurrentUser.get());
    }
}
