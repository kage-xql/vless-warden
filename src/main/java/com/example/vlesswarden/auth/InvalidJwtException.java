package com.example.vlesswarden.auth;

public class InvalidJwtException extends RuntimeException {

    public InvalidJwtException(Throwable cause) {
        super("invalid token", cause);
    }
}
