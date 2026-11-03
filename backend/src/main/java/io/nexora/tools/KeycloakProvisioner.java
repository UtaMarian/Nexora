package io.nexora.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * One-shot, idempotent Keycloak setup for NEXORA, driven by environment variables. Runs in its own container
 * (the backend image with a different main class), so Keycloak's master admin password never reaches the
 * long-running NEXORA server.
 *
 * <pre>
 * KC_URL, KC_ADMIN, KC_ADMIN_PASSWORD   Keycloak (internal URL incl. /auth) and its bootstrap admin
 * REALM, PUBLIC_URL                     realm name; public NEXORA URL (https://nexora.example.com)
 * OIDC_CLIENT_SECRET, SYNC_CLIENT_SECRET
 * ADMIN_GROUP                           group whose members become NEXORA administrators (default "NEXORA Admins")
 * LDAP_URL, LDAP_VENDOR (ad|other), LDAP_BIND_DN, LDAP_BIND_PASSWORD, LDAP_USERS_DN, LDAP_GROUPS_DN, LDAP_USER_FILTER
 * KERBEROS_REALM, KERBEROS_PRINCIPAL, KERBEROS_KEYTAB    SPNEGO single sign-on through the LDAP provider
 * ADFS_METADATA_URL, ADFS_ALIAS, ADFS_DISPLAY_NAME       ADFS as a SAML 2.0 identity provider
 * </pre>
 */
public final class KeycloakProvisioner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STORAGE = "org.keycloak.storage.UserStorageProvider";
    private static final String LDAP_MAPPER = "org.keycloak.storage.ldap.mappers.LDAPStorageMapper";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, String> env;
    private final String kc;
    private final String realm;
    private final String admin;
    private String token;

    KeycloakProvisioner(Map<String, String> env) {
        this.env = env;
        this.kc = trim(req("KC_URL"));
        this.realm = env.getOrDefault("REALM", "nexora");
        this.admin = kc + "/admin/realms/" + realm;
    }

    public static void main(String[] args) throws Exception {
        new KeycloakProvisioner(System.getenv()).run();
    }

    void run() throws Exception {
        waitForKeycloak();
        login();
        String publicUrl = trim(req("PUBLIC_URL"));

        // ---- realm ----
        ObjectNode realmRep = obj().put("realm", realm).put("enabled", true).put("displayName", "NEXORA")
                .put("sslRequired", "external").put("loginWithEmailAllowed", true).put("duplicateEmailsAllowed", false)
                .put("rememberMe", true).put("bruteForceProtected", true);
        if (status("GET", kc + "/admin/realms/" + realm) == 404) {
            send("POST", kc + "/admin/realms", realmRep);
            log("created realm " + realm);
        } else {
            send("PUT", admin, realmRep);
        }
        allowUnmanagedAttributes();

        // ---- login client ----
        ObjectNode client = obj().put("clientId", "nexora").put("name", "NEXORA").put("enabled", true)
                .put("protocol", "openid-connect").put("publicClient", false).put("clientAuthenticatorType", "client-secret")
                .put("secret", req("OIDC_CLIENT_SECRET")).put("standardFlowEnabled", true).put("directAccessGrantsEnabled", true)
                .put("implicitFlowEnabled", false).put("serviceAccountsEnabled", false).put("frontchannelLogout", true);
        client.putArray("redirectUris").add(publicUrl + "/login/oauth2/code/*");
        client.putArray("webOrigins").add(publicUrl);
        client.putObject("attributes").put("post.logout.redirect.uris", publicUrl + "/*")
                .put("pkce.code.challenge.method", "S256");
        String clientId = upsertClient(client);
        mapper(clientId, "groups", "oidc-group-membership-mapper", Map.of("full.path", "true", "claim.name", "groups",
                "id.token.claim", "true", "access.token.claim", "true", "userinfo.token.claim", "true"));
        mapper(clientId, "nexora-audience", "oidc-audience-mapper", Map.of("included.client.audience", "nexora",
                "access.token.claim", "true", "id.token.claim", "false"));
        mapper(clientId, "directory_groups", "oidc-usermodel-attribute-mapper", Map.of("user.attribute", "directory_groups",
                "claim.name", "directory_groups", "multivalued", "true", "jsonType.label", "String",
                "id.token.claim", "true", "access.token.claim", "true", "userinfo.token.claim", "true"));

        // ---- sync client (read-only service account) ----
        ObjectNode sync = obj().put("clientId", "nexora-sync").put("name", "NEXORA directory sync").put("enabled", true)
                .put("protocol", "openid-connect").put("publicClient", false).put("secret", req("SYNC_CLIENT_SECRET"))
                .put("serviceAccountsEnabled", true).put("standardFlowEnabled", false).put("directAccessGrantsEnabled", false);
        String syncId = upsertClient(sync);
        grantRealmManagement(syncId, List.of("view-users", "query-users", "query-groups"));

        // ---- admin group (with a directory, the group comes from AD/LDAP instead) ----
        String adminGroup = env.getOrDefault("ADMIN_GROUP", "NEXORA Admins");
        if (!has("LDAP_URL")) {
        JsonNode found = get(admin + "/groups?exact=true&search=" + enc(adminGroup));
        boolean exists = false;
        for (JsonNode g : found) exists |= g.path("name").asText().equals(adminGroup);
        if (!exists) {
            send("POST", admin + "/groups", obj().put("name", adminGroup));
            log("created group " + adminGroup);
        }
        }

        if (has("LDAP_URL")) ldap();
        if (has("ADFS_METADATA_URL")) adfs();
        log("Keycloak realm '" + realm + "' is ready");
    }

    // ---- LDAP / Active Directory / Kerberos ----

    private void ldap() throws Exception {
        boolean ad = !"other".equalsIgnoreCase(env.getOrDefault("LDAP_VENDOR", "ad"));
        String realmId = get(admin).path("id").asText();
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("enabled", "true");
        cfg.put("priority", "0");
        cfg.put("vendor", ad ? "ad" : "other");
        cfg.put("connectionUrl", req("LDAP_URL"));
        cfg.put("authType", "simple");
        cfg.put("bindDn", req("LDAP_BIND_DN"));
        cfg.put("bindCredential", req("LDAP_BIND_PASSWORD"));
        cfg.put("usersDn", req("LDAP_USERS_DN"));
        cfg.put("usernameLDAPAttribute", ad ? "sAMAccountName" : "uid");
        cfg.put("rdnLDAPAttribute", ad ? "cn" : "uid");
        cfg.put("uuidLDAPAttribute", ad ? "objectGUID" : "entryUUID");
        cfg.put("userObjectClasses", ad ? "person, organizationalPerson, user" : "inetOrgPerson, organizationalPerson");
        cfg.put("customUserSearchFilter", env.getOrDefault("LDAP_USER_FILTER",
                ad ? "(!(userAccountControl:1.2.840.113556.1.4.803:=2))" : ""));   // AD: skip disabled accounts
        cfg.put("searchScope", "2");
        cfg.put("editMode", "READ_ONLY");
        cfg.put("importEnabled", "true");
        cfg.put("syncRegistrations", "false");
        cfg.put("trustEmail", "true");
        cfg.put("pagination", "true");
        cfg.put("connectionPooling", "true");
        cfg.put("batchSizeForSync", "1000");
        cfg.put("fullSyncPeriod", "3600");
        cfg.put("changedSyncPeriod", "300");
        cfg.put("cachePolicy", "DEFAULT");
        cfg.put("useTruststoreSpi", "always");
        boolean kerberos = has("KERBEROS_REALM");
        cfg.put("allowKerberosAuthentication", String.valueOf(kerberos));
        if (kerberos) {
            cfg.put("kerberosRealm", req("KERBEROS_REALM"));
            cfg.put("serverPrincipal", req("KERBEROS_PRINCIPAL"));
            cfg.put("keyTab", env.getOrDefault("KERBEROS_KEYTAB", "/opt/keycloak/conf/nexora.keytab"));
            cfg.put("useKerberosForPasswordAuthentication", "false");
        }
        String ldapId = upsertComponent(realmId, "ldap", "ldap", STORAGE, cfg);

        Map<String, String> g = new LinkedHashMap<>();
        g.put("groups.dn", req("LDAP_GROUPS_DN"));
        g.put("group.name.ldap.attribute", "cn");
        g.put("group.object.classes", ad ? "group" : "groupOfNames");
        g.put("preserve.group.inheritance", "true");
        g.put("ignore.missing.groups", "true");
        g.put("membership.ldap.attribute", "member");
        g.put("membership.attribute.type", "DN");
        g.put("membership.user.ldap.attribute", ad ? "sAMAccountName" : "uid");
        g.put("mode", "READ_ONLY");
        g.put("user.roles.retrieve.strategy", ad ? "LOAD_GROUPS_BY_MEMBER_ATTRIBUTE_RECURSIVELY" : "LOAD_GROUPS_BY_MEMBER_ATTRIBUTE");
        g.put("memberof.ldap.attribute", "memberOf");
        g.put("drop.non.existing.groups.during.sync", "true");
        g.put("groups.path", "/");
        String mapperId = upsertComponent(ldapId, "groups", "group-ldap-mapper", LDAP_MAPPER, g);

        // Keycloak's default for generic LDAP maps the first name from cn (the full name): use givenName
        for (JsonNode m : get(admin + "/components?parent=" + ldapId + "&type=" + enc(LDAP_MAPPER) + "&name=" + enc("first name"))) {
            ObjectNode fixed = ((ObjectNode) m).deepCopy();
            ((ObjectNode) fixed.path("config")).putArray("ldap.attribute").add("givenName");
            send("PUT", admin + "/components/" + m.path("id").asText(), fixed);
        }

        send("POST", admin + "/user-storage/" + ldapId + "/mappers/" + mapperId + "/sync?direction=fedToKeycloak", null);
        JsonNode result = sendForJson("POST", admin + "/user-storage/" + ldapId + "/sync?action=triggerFullSync");
        log("directory synced: " + (result == null ? "" : result.path("status").asText(result.toString()))
                + (kerberos ? " (Kerberos SSO on)" : ""));
    }

    // ---- ADFS (SAML 2.0) ----

    private void adfs() throws Exception {
        String alias = env.getOrDefault("ADFS_ALIAS", "adfs");
        JsonNode imported = sendForJson("POST", admin + "/identity-provider/import-config",
                obj().put("providerId", "saml").put("fromUrl", req("ADFS_METADATA_URL")));
        ObjectNode config = imported == null ? obj() : ((ObjectNode) imported).deepCopy();
        config.put("syncMode", "FORCE").put("nameIDPolicyFormat", "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified")
                .put("principalType", "SUBJECT").put("postBindingResponse", "true").put("postBindingAuthnRequest", "true")
                .put("postBindingLogout", "true").put("wantAssertionsSigned", "true").put("validateSignature", "true")
                .put("loginHint", "true").put("signatureAlgorithm", "RSA_SHA256");
        ObjectNode idp = obj().put("alias", alias).put("displayName", env.getOrDefault("ADFS_DISPLAY_NAME", "ADFS"))
                .put("providerId", "saml").put("enabled", true).put("trustEmail", true).put("storeToken", false)
                .put("firstBrokerLoginFlowAlias", "first broker login");
        idp.set("config", config);
        if (status("GET", admin + "/identity-provider/instances/" + alias) == 404) {
            send("POST", admin + "/identity-provider/instances", idp);
            log("created identity provider " + alias);
        } else {
            send("PUT", admin + "/identity-provider/instances/" + alias, idp);
        }
        String claims = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/";
        idpMapper(alias, "email", "saml-user-attribute-idp-mapper", Map.of("attribute.name", claims + "emailaddress", "user.attribute", "email"));
        idpMapper(alias, "first name", "saml-user-attribute-idp-mapper", Map.of("attribute.name", claims + "givenname", "user.attribute", "firstName"));
        idpMapper(alias, "last name", "saml-user-attribute-idp-mapper", Map.of("attribute.name", claims + "surname", "user.attribute", "lastName"));
        idpMapper(alias, "groups", "saml-user-attribute-idp-mapper",
                Map.of("attribute.name", "http://schemas.xmlsoap.org/claims/Group", "user.attribute", "directory_groups"));
        idpMapper(alias, "username", "saml-username-idp-mapper",
                Map.of("template", "${ATTRIBUTE." + claims + "upn}", "target", "LOCAL"));
        log("ADFS relying-party metadata for this realm: " + trim(req("PUBLIC_URL")) + "/auth/realms/" + realm
                + "/broker/" + alias + "/endpoint/descriptor");
    }

    // ---- helpers ----

    private void allowUnmanagedAttributes() throws Exception {
        // ADFS group claims are stored as an unmanaged user attribute (directory_groups)
        JsonNode profile = get(admin + "/users/profile");
        if (profile instanceof ObjectNode p && !"ADMIN_EDIT".equals(p.path("unmanagedAttributePolicy").asText())) {
            p.put("unmanagedAttributePolicy", "ADMIN_EDIT");
            send("PUT", admin + "/users/profile", p);
        }
    }

    private String upsertClient(ObjectNode rep) throws Exception {
        String clientId = rep.path("clientId").asText();
        JsonNode existing = get(admin + "/clients?clientId=" + enc(clientId));
        if (existing.isEmpty()) {
            send("POST", admin + "/clients", rep);
            existing = get(admin + "/clients?clientId=" + enc(clientId));
            log("created client " + clientId);
        } else {
            send("PUT", admin + "/clients/" + existing.get(0).path("id").asText(), rep);
        }
        return existing.get(0).path("id").asText();
    }

    private void mapper(String clientUuid, String name, String type, Map<String, String> config) throws Exception {
        ObjectNode m = obj().put("name", name).put("protocol", "openid-connect").put("protocolMapper", type);
        ObjectNode c = m.putObject("config");
        config.forEach(c::put);
        String base = admin + "/clients/" + clientUuid + "/protocol-mappers/models";
        for (JsonNode existing : get(base)) {
            if (existing.path("name").asText().equals(name)) {
                m.put("id", existing.path("id").asText());
                send("PUT", base + "/" + existing.path("id").asText(), m);
                return;
            }
        }
        send("POST", base, m);
    }

    private void idpMapper(String alias, String name, String type, Map<String, String> config) throws Exception {
        ObjectNode m = obj().put("name", name).put("identityProviderAlias", alias).put("identityProviderMapper", type);
        ObjectNode c = m.putObject("config");
        c.put("syncMode", "FORCE");
        config.forEach(c::put);
        String base = admin + "/identity-provider/instances/" + alias + "/mappers";
        for (JsonNode existing : get(base)) {
            if (existing.path("name").asText().equals(name)) {
                m.put("id", existing.path("id").asText());
                send("PUT", base + "/" + existing.path("id").asText(), m);
                return;
            }
        }
        send("POST", base, m);
    }

    private void grantRealmManagement(String clientUuid, List<String> roles) throws Exception {
        String user = get(admin + "/clients/" + clientUuid + "/service-account-user").path("id").asText();
        String rm = get(admin + "/clients?clientId=realm-management").get(0).path("id").asText();
        ArrayNode wanted = JSON.createArrayNode();
        for (String r : roles) wanted.add(get(admin + "/clients/" + rm + "/roles/" + r));
        send("POST", admin + "/users/" + user + "/role-mappings/clients/" + rm, wanted);
    }

    private String upsertComponent(String parentId, String name, String providerId, String type, Map<String, String> config)
            throws Exception {
        ObjectNode rep = obj().put("name", name).put("providerId", providerId).put("providerType", type).put("parentId", parentId);
        ObjectNode c = rep.putObject("config");
        config.forEach((k, v) -> c.putArray(k).add(v));
        JsonNode existing = get(admin + "/components?parent=" + parentId + "&type=" + enc(type) + "&name=" + enc(name));
        if (existing.isEmpty()) {
            send("POST", admin + "/components", rep);
            existing = get(admin + "/components?parent=" + parentId + "&type=" + enc(type) + "&name=" + enc(name));
            log("created " + providerId + " component '" + name + "'");
        } else {
            rep.put("id", existing.get(0).path("id").asText());
            send("PUT", admin + "/components/" + existing.get(0).path("id").asText(), rep);
        }
        return existing.get(0).path("id").asText();
    }

    private void waitForKeycloak() throws InterruptedException {
        for (int i = 0; i < 90; i++) {
            try {
                if (http.send(HttpRequest.newBuilder(URI.create(kc + "/realms/master")).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return;
            } catch (Exception ignored) {
                // not up yet
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Keycloak did not become ready at " + kc);
    }

    private void login() throws Exception {
        String form = "grant_type=password&client_id=admin-cli&username=" + enc(req("KC_ADMIN"))
                + "&password=" + enc(req("KC_ADMIN_PASSWORD"));
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(kc + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("Keycloak admin login failed: HTTP " + res.statusCode());
        token = JSON.readTree(res.body()).path("access_token").asText();
    }

    private JsonNode get(String url) throws Exception {
        HttpResponse<String> res = http.send(auth(HttpRequest.newBuilder(URI.create(url))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("GET " + url + " → HTTP " + res.statusCode() + " " + res.body());
        return JSON.readTree(res.body());
    }

    private int status(String method, String url) throws Exception {
        return http.send(auth(HttpRequest.newBuilder(URI.create(url))).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void send(String method, String url, JsonNode body) throws Exception {
        HttpResponse<String> res = raw(method, url, body);
        if (res.statusCode() >= 300) throw new IllegalStateException(method + " " + url + " → HTTP " + res.statusCode() + " " + res.body());
    }

    private JsonNode sendForJson(String method, String url) throws Exception {
        return sendForJson(method, url, null);
    }

    private JsonNode sendForJson(String method, String url, JsonNode body) throws Exception {
        HttpResponse<String> res = raw(method, url, body);
        if (res.statusCode() >= 300) throw new IllegalStateException(method + " " + url + " → HTTP " + res.statusCode() + " " + res.body());
        return res.body() == null || res.body().isBlank() ? null : JSON.readTree(res.body());
    }

    private HttpResponse<String> raw(String method, String url, JsonNode body) throws Exception {
        HttpRequest.Builder b = auth(HttpRequest.newBuilder(URI.create(url))).timeout(Duration.ofMinutes(5));
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder auth(HttpRequest.Builder b) {
        return b.header("Authorization", "Bearer " + token);
    }

    private static ObjectNode obj() { return JSON.createObjectNode(); }

    private boolean has(String key) { return env.get(key) != null && !env.get(key).isBlank(); }

    private String req(String key) {
        if (!has(key)) throw new IllegalArgumentException("Missing environment variable " + key);
        return env.get(key);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String trim(String u) { return u.endsWith("/") ? u.substring(0, u.length() - 1) : u; }

    private static void log(String msg) { System.out.println("[keycloak-provision] " + msg); }
}
