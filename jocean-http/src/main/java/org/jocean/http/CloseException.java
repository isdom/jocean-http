package org.jocean.http;

public class CloseException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public CloseException() {
        super("close()");
    }
}
