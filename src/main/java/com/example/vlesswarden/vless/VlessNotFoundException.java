package com.example.vlesswarden.vless;

public class VlessNotFoundException extends RuntimeException {
    public VlessNotFoundException() {
        super("VLESS resource not found");
    }
}
