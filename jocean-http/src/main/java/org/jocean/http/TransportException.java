package org.jocean.http;

public class TransportException extends RuntimeException {

    private static final long serialVersionUID = 1620281485023205687L;
    
    public TransportException(final String message) {
        super(message);
    }
    
    public TransportException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
