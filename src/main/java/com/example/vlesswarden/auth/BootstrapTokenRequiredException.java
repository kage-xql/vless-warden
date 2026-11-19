package com.example.vlesswarden.auth;

public class BootstrapTokenRequiredException extends RuntimeException {

    public BootstrapTokenRequiredException() {
        super("first account requires a valid setup token");
    }
}
