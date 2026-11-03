package io.nexora.identity.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pulls users, groups and memberships from Keycloak's admin API, so AD/LDAP groups can be given access in
 * NEXORA before their members ever sign in, and people who left are disabled. Keycloak itself syncs from
 * AD/LDAP (its LDAP federation runs periodic full and changed-user syncs).
 */
@Service
public class DirectorySyncService {
    private static final Logger log = LoggerFactory.getLogger(DirectorySyncService.class);
    private static final int PAGE = 200;

    private final IdentityProperties props;
    private final DirectoryProvisioner provisioner;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Status status = new Status(null, null, 0, 0, 0, null);

    public record Status(Instant lastRun, Instant lastSuccess, int users, int groups, int retired, String error) {}

    public DirectorySyncService(IdentityProperties props, DirectoryProvisioner provisioner, TransactionTemplate tx,
                                ObjectMapper json) {
        this.props = props;
        this.provisioner = provisioner;
        this.tx = tx;
        this.json = json;
    }

    public boolean enabled() {
        return props.oidc().enabled() && props.sync().enabled();
    }

    public Status status() {
        return status;
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    void tick() {
        if (!enabled()) return;
        Instant last = status.lastRun();
        if (last == null || last.plus(props.sync().interval()).isBefore(Instant.now())) run();
    }

    /** Runs one sync now (no-op if one is already running). */
    public Status run() {
        if (!enabled()) throw new IllegalStateException("Directory sync is not enabled");
        if (!running.compareAndSet(false, true)) return status;
        Instant started = Instant.now();
        try {
            Snapshot snap = fetch();
            int retired = tx.execute(t -> apply(snap));
            status = new Status(started, Instant.now(), snap.users.size(), snap.groups.size(), retired, null);
            log.info("Directory sync: {} users, {} groups, {} retired", snap.users.size(), snap.groups.size(), retired);
        } catch (Exception e) {
            status = new Status(started, status.lastSuccess(), status.users(), status.groups(), 0, e.getMessage());
            log.warn("Directory sync failed: {}", e.toString());
        } finally {
            running.set(false);
        }
        return status;
    }

    // ---- snapshot ----

    record User(String id, String username, String name, String email, boolean enabled) {}

    record Snapshot(List<User> users, Set<String> groups, Map<String, Set<String>> groupsByUser) {}

    private int apply(Snapshot snap) {
        snap.groups.forEach(provisioner::ensureGroupPath);
        Set<String> live = new HashSet<>();
        for (User u : snap.users) {
            live.add(u.id());
            try {
                provisioner.provision(new DirectoryProvisioner.DirectoryUser(u.id(), u.username(), u.name(), u.email(),
                        u.enabled(), snap.groupsByUser.getOrDefault(u.id(), Set.of()), null));
            } catch (DirectoryProvisioner.AccountConflictException e) {
                log.warn("Directory sync skipped '{}': {}", u.username(), e.getMessage());
            }
        }
        return provisioner.retire(live, snap.groups);
    }

    private Snapshot fetch() throws Exception {
        String token = token();
        String base = admin();
        List<User> users = new ArrayList<>();
        for (int first = 0; ; first += PAGE) {
            JsonNode page = get(token, base + "/users?briefRepresentation=true&first=" + first + "&max=" + PAGE);
            for (JsonNode u : page) {
                String name = (u.path("firstName").asText("") + " " + u.path("lastName").asText("")).strip();
                users.add(new User(u.path("id").asText(), u.path("username").asText(), name.isEmpty() ? null : name,
                        u.path("email").asText(null), u.path("enabled").asBoolean(true)));
            }
            if (page.size() < PAGE) break;
        }

        Set<String> paths = new LinkedHashSet<>();
        Map<String, Set<String>> byUser = new HashMap<>();
        Deque<JsonNode> todo = new ArrayDeque<>();
        get(token, base + "/groups?briefRepresentation=true&first=0&max=1000").forEach(todo::add);
        while (!todo.isEmpty()) {
            JsonNode g = todo.pop();
            String id = g.path("id").asText();
            String path = g.path("path").asText("/" + g.path("name").asText());
            paths.add(path);
            for (int first = 0; ; first += PAGE) {
                JsonNode members = get(token, base + "/groups/" + id + "/members?briefRepresentation=true&first=" + first + "&max=" + PAGE);
                for (JsonNode m : members) byUser.computeIfAbsent(m.path("id").asText(), k -> new HashSet<>()).add(path);
                if (members.size() < PAGE) break;
            }
            if (g.path("subGroupCount").asInt(0) > 0 || g.path("subGroups").size() > 0) {
                get(token, base + "/groups/" + id + "/children?briefRepresentation=true&first=0&max=1000").forEach(todo::add);
            }
        }
        return new Snapshot(users, paths, byUser);
    }

    private String admin() {
        IdentityProperties.Oidc c = props.oidc();
        return (c.internalUrl() == null ? c.publicUrl() : c.internalUrl()) + "/admin/realms/" + c.realm();
    }

    private String token() throws Exception {
        String form = "grant_type=client_credentials&client_id=" + enc(props.sync().clientId())
                + "&client_secret=" + enc(props.sync().clientSecret());
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(props.oidc().tokenUri()))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("Sync client cannot get a token (HTTP " + res.statusCode() + ")");
        return json.readTree(res.body()).path("access_token").asText();
    }

    private JsonNode get(String token, String url) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("Keycloak admin API " + url + " → HTTP " + res.statusCode());
        return json.readTree(res.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
