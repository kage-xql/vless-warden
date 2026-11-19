package com.example.vlesswarden.server;

import com.example.vlesswarden.crypto.CryptoService;
import com.example.vlesswarden.ssh.SshCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class ServerCredentialService {
    private final CryptoService crypto;
    private final ObjectMapper mapper;

    public ServerCredentialService(CryptoService crypto, ObjectMapper mapper) {
        this.crypto = crypto;
        this.mapper = mapper;
    }

    public SshCredentials decrypt(Server server) {
        try {
            return mapper.readValue(new String(crypto.decrypt(server.getEncryptedCredentials()), StandardCharsets.UTF_8),
                    SshCredentials.class);
        } catch (Exception e) {
            throw new IllegalStateException("could not read SSH credentials", e);
        }
    }

    public byte[] encrypt(ServerRequest.Credentials credentials) {
        try {
            return crypto.encrypt(mapper.writeValueAsBytes(credentials));
        } catch (Exception e) {
            throw new IllegalStateException("could not encrypt SSH credentials", e);
        }
    }

    public SshCredentials toCredentials(ServerRequest.Credentials credentials) {
        return new SshCredentials(credentials.password(), credentials.privateKey(), credentials.passphrase());
    }
}
