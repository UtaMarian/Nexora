package io.nexora.search;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SearchConfig {

    @Bean
    public TextExtractor textExtractor(SearchProperties properties) {
        String url = properties.tika().url();
        return url == null || url.isBlank() ? new BasicTextExtractor()
                : new TikaTextExtractor(url, properties.tika().timeout());
    }
}
