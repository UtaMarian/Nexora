package io.nexora.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads a SharePoint Server farm (2013 SP1, 2016, 2019, Subscription Edition) through its REST API
 * ({@code /_api}), with the JSON "nometadata" format.
 *
 * <p>Authentication: NTLM (Windows authentication, the farm default), Basic, a bearer token, or a cookie
 * header (for example FedAuth from a farm signed in through AD FS). Throttling answers (429 / 503) are retried
 * after the server's Retry-After.
 */
public class SharePointClient {
    public enum Auth { NTLM, BASIC, BEARER, COOKIE, NONE }

    static {
        // a farm offering Negotiate and NTLM: use NTLM, which works without a Kerberos setup on this host
        if (System.getProperty("http.auth.preference") == null) System.setProperty("http.auth.preference", "NTLM");
    }

    private static final int MAX_RETRIES = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String base;
    private final Auth auth;
    private final String user;
    private final String domain;
    private final String secret;

    public SharePointClient(String baseUrl, Auth auth, String user, String domain, String secret) {
        String b = baseUrl.strip();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.auth = auth == null ? Auth.NTLM : auth;
        this.user = user;
        this.domain = domain;
        this.secret = secret;
    }

    public String base() {
        return base;
    }

    // ---- URLs ----------------------------------------------------------------------------------

    /** Absolute REST URL for a site: {@code web("/sites/hr", "/_api/web/lists")}. */
    public String web(String webPath, String rest) {
        String w = webPath == null || webPath.isBlank() || webPath.equals("/") ? "" : encodePath(trimSlash(webPath));
        return base + w + rest;
    }

    /** Absolute URL of a file by its server-relative URL, e.g. a historic version under {@code _vti_history}. */
    public String absolute(String webPath, String relativeToWeb) {
        String w = webPath == null || webPath.isBlank() || webPath.equals("/") ? "" : encodePath(trimSlash(webPath));
        return base + w + "/" + encodePath(relativeToWeb.startsWith("/") ? relativeToWeb.substring(1) : relativeToWeb);
    }

    /** An OData string literal for a parameter alias: {@code @u='/sites/hr/Shared Documents'}. */
    public static String literal(String value) {
        return "'" + URLEncoder.encode(value.replace("'", "''"), StandardCharsets.UTF_8).replace("+", "%20") + "'";
    }

    public static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String encodePath(String path) {
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/", -1)) out.add(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        return String.join("/", out);
    }

    private static String trimSlash(String p) {
        String s = p.startsWith("/") ? p : "/" + p;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ---- requests ------------------------------------------------------------------------------

    public JsonNode json(String url) throws IOException {
        HttpURLConnection c = open(url, "application/json;odata=nometadata");
        try (InputStream in = c.getInputStream()) {
            return JSON.readTree(in);
        } finally {
            c.disconnect();
        }
    }

    /** All rows of a collection, following {@code odata.nextLink} paging. */
    public List<JsonNode> all(String url) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        String next = url;
        int pages = 0;
        while (next != null && pages++ < 10_000) {
            JsonNode page = json(next);
            JsonNode value = page.path("value");
            if (value.isArray()) value.forEach(out::add);
            String link = page.path("odata.nextLink").asText(null);
            next = link == null || link.isBlank() ? null : link.startsWith("http") ? link : base + (link.startsWith("/") ? "" : "/") + link;
        }
        return out;
    }

    public long download(String url, Path target) throws IOException {
        HttpURLConnection c = open(url, "*/*");
        try (InputStream in = c.getInputStream()) {
            return Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String url, String accept) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(300_000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", accept);
            c.setRequestProperty("User-Agent", "NEXORA-Migration/1.0");
            switch (auth) {
                case NTLM -> {
                    String login = domain == null || domain.isBlank() ? user : domain + "\\" + user;
                    char[] pw = secret == null ? new char[0] : secret.toCharArray();
                    c.setAuthenticator(new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(login, pw);
                        }
                    });
                }
                case BASIC -> {
                    String login = domain == null || domain.isBlank() ? user : domain + "\\" + user;
                    c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString((login + ":" + (secret == null ? "" : secret)).getBytes(StandardCharsets.UTF_8)));
                }
                case BEARER -> c.setRequestProperty("Authorization", "Bearer " + secret);
                case COOKIE -> c.setRequestProperty("Cookie", secret);
                case NONE -> { }
            }
            int status = c.getResponseCode();
            if (status == 429 || status == 503) {
                long wait = retryAfter(c.getHeaderField("Retry-After"), attempt);
                c.disconnect();
                last = new IOException("SharePoint is throttling requests (HTTP " + status + ")");
                sleep(wait);
                continue;
            }
            if (status >= 400) {
                String body = "";
                try (InputStream err = c.getErrorStream()) {
                    if (err != null) body = new String(err.readNBytes(600), StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // no body
                }
                c.disconnect();
                throw new SharePointException(status, url, message(status, body));
            }
            return c;
        }
        throw last;
    }

    private static String message(int status, String body) {
        String detail = "";
        try {
            JsonNode n = JSON.readTree(body);
            detail = n.path("odata.error").path("message").path("value").asText(n.path("error").path("message").asText(""));
        } catch (Exception ignored) {
            // not JSON
        }
        String what = switch (status) {
            case 401 -> "Sign-in to SharePoint failed (check the account, password and authentication method)";
            case 403 -> "The account has no access to this SharePoint content";
            case 404 -> "Not found in SharePoint";
            default -> "SharePoint answered HTTP " + status;
        };
        return detail.isBlank() ? what : what + ": " + detail;
    }

    private static long retryAfter(String header, int attempt) {
        try {
            if (header != null) return Math.min(120, Long.parseLong(header.strip())) * 1000;
        } catch (NumberFormatException ignored) {
            // HTTP date form: use back-off
        }
        return (long) Math.min(60_000, 1000 * Math.pow(2, attempt));
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    /** An error answer from SharePoint. */
    public static class SharePointException extends IOException {
        private final int status;

        public SharePointException(int status, String url, String message) {
            super(message + " [" + url + "]");
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
