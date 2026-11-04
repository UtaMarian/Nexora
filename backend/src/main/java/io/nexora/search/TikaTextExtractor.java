package io.nexora.search;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends files to an Apache Tika server, which reads PDF, Office and hundreds of other formats and runs OCR on images
 * and scanned pages. Tika runs in its own container so that a hostile or broken file can never take the API down.
 * OCR languages and limits are set in the Tika server's own configuration.
 */
public class TikaTextExtractor implements TextExtractor {
    private final String url;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public TikaTextExtractor(String url, Duration timeout) {
        this.url = url.replaceAll("/+$", "");
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "Apache Tika with OCR";
    }

    @Override
    public boolean supports(String mimeType, String extension) {
        return true;
    }

    @Override
    public String extract(InputStream data, String mimeType, String extension, int maxChars)
            throws IOException, RejectedException, UnavailableException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + "/tika")).timeout(timeout)
                .header("Accept", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> data));
        if (mimeType != null && !mimeType.equals("application/octet-stream")) request.header("Content-Type", mimeType);
        try {
            HttpResponse<InputStream> r = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = r.body()) {
                int status = r.statusCode();
                if (status == 204) return "";
                if (status == 415) throw new RejectedException("Unsupported file type");
                if (status == 422) throw new RejectedException("The file is encrypted or damaged");
                if (status == 502 || status == 503) throw new UnavailableException("Tika answered " + status, null);
                if (status != 200) throw new IOException("Tika answered " + status);
                return read(body, maxChars);
            }
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new UnavailableException("Tika cannot be reached at " + url, e);
        } catch (HttpTimeoutException e) {
            throw new IOException("Tika took longer than " + timeout.toMinutes() + " minutes", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static String read(InputStream body, int maxChars) throws IOException {
        Reader reader = new InputStreamReader(body, StandardCharsets.UTF_8);
        char[] buffer = new char[8192];
        StringBuilder out = new StringBuilder();
        int n;
        while (out.length() < maxChars && (n = reader.read(buffer, 0, Math.min(buffer.length, maxChars - out.length()))) > 0) {
            out.append(buffer, 0, n);
        }
        return out.toString();
    }

    @Override
    public String status() throws IOException, UnavailableException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/version")).timeout(Duration.ofSeconds(5)).GET().build();
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IOException("Tika answered " + r.statusCode());
            return r.body().strip();
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new UnavailableException("Tika cannot be reached at " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
