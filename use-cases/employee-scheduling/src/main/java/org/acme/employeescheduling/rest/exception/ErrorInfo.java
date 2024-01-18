package org.acme.employeescheduling.rest.exception;

public record ErrorInfo(Long jobId, String message) {
}
