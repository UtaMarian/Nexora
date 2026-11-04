package io.nexora.search;

import java.io.IOException;
import java.io.InputStream;

/** Reads the words out of a file. */
public interface TextExtractor {

    /** The file itself cannot be read: encrypted, corrupt or an unknown format. Trying again will not help. */
    class RejectedException extends Exception {
        public RejectedException(String message) { super(message); }
    }

    /** The extractor is down. The file is fine and is tried again later. */
    class UnavailableException extends Exception {
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    String name();

    /** True when this extractor can read the file type. */
    boolean supports(String mimeType, String extension);

    /** @return up to {@code maxChars} of raw text, possibly empty */
    String extract(InputStream data, String mimeType, String extension, int maxChars)
            throws IOException, RejectedException, UnavailableException;

    /** A short status line; throws when the extractor is down. */
    String status() throws IOException, UnavailableException;

    /**
     * Makes extracted text safe to store and index: PostgreSQL rejects NUL characters and broken surrogate pairs, and
     * the private-use characters that mark matches in snippets must not occur in the text.
     */
    static String clean(String raw) {
        StringBuilder b = new StringBuilder(raw.length());
        boolean space = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < raw.length() && Character.isLowSurrogate(raw.charAt(i + 1))) {
                b.append(c).append(raw.charAt(++i));
                space = false;
            } else if (Character.isWhitespace(c) || Character.isISOControl(c) || Character.isSurrogate(c)
                    || c == '' || c == '') {
                if (!space && !b.isEmpty()) b.append(' ');
                space = true;
            } else {
                b.append(c);
                space = false;
            }
        }
        return b.toString().strip();
    }
}
