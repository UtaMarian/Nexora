package io.nexora.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading real files with a real Tika server (deploy/tika):
 * {@code NEXORA_TEST_TIKA_URL=http://localhost:9998 ./mvnw test -Dtest=TikaTextExtractorIT}
 */
@EnabledIfEnvironmentVariable(named = "NEXORA_TEST_TIKA_URL", matches = ".+")
class TikaTextExtractorIT {
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final TikaTextExtractor tika = new TikaTextExtractor(System.getenv("NEXORA_TEST_TIKA_URL"), Duration.ofMinutes(2));

    private String read(byte[] data, String mime) throws Exception {
        return tika.extract(new ByteArrayInputStream(data), mime, "", 100_000);
    }

    @Test
    void readsOfficeAndPdf() throws Exception {
        assertThat(read(SearchFixtures.docx("The zebrafish policies"), DOCX)).contains("zebrafish policies");
        assertThat(read(SearchFixtures.textPdf("Kangaroo logistics agreement"), "application/pdf")).contains("Kangaroo logistics");
    }

    @Test
    void readsImagesAndScannedPagesWithOcr() throws Exception {
        assertThat(read(SearchFixtures.image("png", "Mercury shipment for Rotterdam"), "image/png")).contains("Rotterdam");
        assertThat(read(SearchFixtures.scannedPdf("Contract with Tangerine Holdings"), "application/pdf")).contains("Tangerine");
    }

    @Test
    void detectsTheFormatWhenTheTypeIsUnknown() throws Exception {
        assertThat(read(SearchFixtures.textPdf("Detected kangaroo"), "application/octet-stream")).contains("kangaroo");
    }

    @Test
    void rejectsDamagedFilesAndReportsItsVersion() throws Exception {
        assertThatThrownBy(() -> read("%PDF-1.4 not really".getBytes(StandardCharsets.US_ASCII), "application/pdf"))
                .isInstanceOf(TextExtractor.RejectedException.class);
        assertThat(tika.status()).containsIgnoringCase("tika");
    }

    @Test
    void reportsWhenTheServerIsDown() {
        TikaTextExtractor down = new TikaTextExtractor("http://127.0.0.1:9", Duration.ofSeconds(5));
        assertThatThrownBy(() -> down.extract(new ByteArrayInputStream(new byte[1]), "text/plain", "txt", 10))
                .isInstanceOf(TextExtractor.UnavailableException.class);
    }

    @Test
    void stopsAtTheTextLimit() throws Exception {
        String text = tika.extract(new ByteArrayInputStream("word ".repeat(1000).getBytes(StandardCharsets.US_ASCII)),
                "text/plain", "txt", 100);
        assertThat(text).hasSizeLessThanOrEqualTo(100);
    }
}
