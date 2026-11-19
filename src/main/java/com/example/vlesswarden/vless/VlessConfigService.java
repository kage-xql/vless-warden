package com.example.vlesswarden.vless;

import com.example.vlesswarden.crypto.CryptoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Собирает config.json из записей БД и расшифровывает приватные ключи
 * только в памяти процесса панели.
 */
@Service
public class VlessConfigService {
    private final VlessClientRepository clients;
    private final CryptoService crypto;
    private final XrayConfigBuilder builder;

    public VlessConfigService(VlessClientRepository clients, CryptoService crypto, XrayConfigBuilder builder) {
        this.clients = clients;
        this.crypto = crypto;
        this.builder = builder;
    }

    public String uuid(VlessClient client) {
        return new String(crypto.decrypt(client.getEncryptedUuid()), StandardCharsets.UTF_8);
    }

    public byte[] encryptUuid(String uuid) {
        return crypto.encrypt(uuid.getBytes(StandardCharsets.UTF_8));
    }

    public String privateKey(VlessInbound inbound) {
        return new String(crypto.decrypt(inbound.getEncryptedPrivateKey()), StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public List<VlessClient> all(VlessInbound inbound) {
        return clients.findAllByInboundIdOrderByCreatedAtDesc(inbound.getId());
    }

    @Transactional(readOnly = true)
    public String fullConfig(VlessInbound inbound) {
        return configFor(inbound, all(inbound).stream().filter(VlessClient::isEnabled).toList(), Map.of());
    }

    public String configFor(VlessInbound inbound, List<VlessClient> selected, Map<UUID, String> uuidOverrides) {
        List<XrayClient> active = selected.stream()
                .map(client -> new XrayClient(
                        uuidOverrides.getOrDefault(client.getId(), uuid(client)),
                        client.getEmail(), inbound.getFlow()))
                .toList();
        return builder.config(inbound, privateKey(inbound), active);
    }

    public String clientConfig(VlessInbound inbound, VlessClient client, String uuid) {
        return builder.userConfig(inbound, privateKey(inbound),
                new XrayClient(uuid, client.getEmail(), inbound.getFlow()));
    }
}
