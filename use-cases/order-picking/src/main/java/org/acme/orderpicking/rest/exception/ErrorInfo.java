package org.acme.orderpicking.rest.exception;

public record ErrorInfo(Long jobId, String message) {
}
