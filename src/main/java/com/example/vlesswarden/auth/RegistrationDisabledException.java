package com.example.vlesswarden.auth;

public class RegistrationDisabledException extends RuntimeException {

    public RegistrationDisabledException() {
        super("registration is disabled");
    }
}
