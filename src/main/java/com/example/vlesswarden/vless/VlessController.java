package com.example.vlesswarden.vless;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class VlessController {
    private final VlessProvisioningService provisioning;
    private final VlessClientService clients;

    public VlessController(VlessProvisioningService provisioning, VlessClientService clients) {
        this.provisioning = provisioning;
        this.clients = clients;
    }

    @GetMapping("/servers/{serverId}/vless")
    public VlessInboundResponse inbound(Authentication authentication, @PathVariable UUID serverId) {
        return provisioning.get(user(authentication), serverId);
    }

    @PostMapping("/servers/{serverId}/provisioning/vless")
    public VlessInboundResponse provision(Authentication authentication, @PathVariable UUID serverId,
                                          @Valid @RequestBody(required = false) VlessProvisionRequest request) {
        return provisioning.provision(user(authentication), serverId, request);
    }

    @GetMapping("/servers/{serverId}/vless/clients")
    public List<VlessClientResponse> list(Authentication authentication, @PathVariable UUID serverId) {
        return clients.list(user(authentication), serverId);
    }

    @PostMapping("/servers/{serverId}/vless/clients")
    @ResponseStatus(HttpStatus.CREATED)
    public VlessClientCreationResponse create(Authentication authentication, @PathVariable UUID serverId,
                                              @Valid @RequestBody VlessClientRequest request) {
        return clients.create(user(authentication), serverId, request);
    }

    @GetMapping("/vless/clients/{clientId}")
    public VlessClientConfigResponse config(Authentication authentication, @PathVariable UUID clientId) {
        return clients.config(user(authentication), clientId);
    }

    @PutMapping("/vless/clients/{clientId}")
    public VlessClientCreationResponse update(Authentication authentication, @PathVariable UUID clientId,
                                              @Valid @RequestBody VlessClientUpdateRequest request) {
        return clients.update(user(authentication), clientId, request);
    }

    @DeleteMapping("/vless/clients/{clientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable UUID clientId) {
        clients.delete(user(authentication), clientId);
    }

    private UUID user(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }
}
