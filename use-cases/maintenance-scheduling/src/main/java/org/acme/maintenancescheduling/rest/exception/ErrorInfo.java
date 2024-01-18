package org.acme.maintenancescheduling.rest.exception;

public record ErrorInfo(Long jobId, String message) {
}
