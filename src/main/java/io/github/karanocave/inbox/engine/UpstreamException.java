package io.github.karanocave.inbox.engine;

public class UpstreamException extends RuntimeException {
    private final String code;
    private final boolean uncertain;
    private final long retryAfterSeconds;

    public UpstreamException(String code, String message, boolean uncertain, long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.uncertain = uncertain;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String code() {
        return code;
    }

    public boolean uncertain() {
        return uncertain;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
