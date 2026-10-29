package com.waka.identity.files;

/** Fixed internal codes; never carries a file name, a path, file content or a database cause. */
public final class FileException extends RuntimeException {
    public enum Code { INVALID_REQUEST, INVALID_FILE, NOT_FOUND, IDEMPOTENCY_KEY_REUSED, NOT_EDITABLE, SERVICE_UNAVAILABLE,
        NOT_AUTHORIZED, INVALID_ADMIN_SESSION }
    private final Code code;
    public FileException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
