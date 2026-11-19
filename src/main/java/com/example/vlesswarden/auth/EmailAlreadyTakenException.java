package com.example.vlesswarden.auth;

public class EmailAlreadyTakenException extends RuntimeException {

    public EmailAlreadyTakenException() {
        super("email already registered");
    }
}
