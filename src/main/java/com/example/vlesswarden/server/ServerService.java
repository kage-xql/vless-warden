package com.example.vlesswarden.server;

import com.example.vlesswarden.ssh.SshClientService;
import com.example.vlesswarden.ssh.SshConnectionException;
import com.example.vlesswarden.vless.VlessInbound;
import com.example.vlesswarden.vless.VlessInboundRepository;
import com.example.vlesswarden.vless.VlessOperationLock;
import com.example.vlesswarden.vless.VlessRemoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ServerService {
    private static final Logger log = LoggerFactory.getLogger(ServerService.class);
    private final ServerRepository repository;
    private final VlessInboundRepository inbounds;
    private final ServerCredentialService credentials;
    private final SshClientService ssh;
    private final VlessRemoteService vless;
    private final VlessOperationLock lock;

    public ServerService(ServerRepository repository, VlessInboundRepository inbounds,
                         ServerCredentialService credentials, SshClientService ssh,
                         VlessRemoteService vless, VlessOperationLock lock) {
        this.repository = repository;
        this.inbounds = inbounds;
        this.credentials = credentials;
        this.ssh = ssh;
        this.vless = vless;
        this.lock = lock;
    }

    @Transactional(readOnly = true)
    public List<ServerResponse> list(UUID user) {
        return repository.findAllByUserIdOrderByCreatedAtDesc(user).stream().map(ServerResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ServerResponse get(UUID user, UUID id) {
        return ServerResponse.from(owned(user, id));
    }

    @Transactional(readOnly = true)
    public Server server(UUID user, UUID id) {
        return owned(user, id);
    }

    public ServerResponse create(UUID user, ServerRequest request) {
        validateHost(request.host());
        validateCredentials(request.credentials());
        Server server = repository.save(new Server(user, request.name().trim(), request.host().trim(),
                request.sshPort(), request.sshUser().trim(), credentials.encrypt(request.credentials())));
        try {
            var result = ssh.probe(server.getHost(), server.getSshPort(), server.getSshUser(),
                    credentials.toCredentials(request.credentials()), null);
            server.connectionResult(result.fingerprint(), result.operatingSystem());
        } catch (RuntimeException e) {
            server.markError(connectionError(e));
        }
        repository.save(server);
        return ServerResponse.from(server);
    }

    public ServerResponse update(UUID user, UUID id, ServerRequest request) {
        validateHost(request.host());
        return lock.withLock(id, () -> {
            Server server = owned(user, id);
            byte[] encrypted = null;
            if (request.credentials() != null) {
                validateCredentials(request.credentials());
                encrypted = credentials.encrypt(request.credentials());
            }
            server.update(request.name().trim(), request.host().trim(), request.sshPort(),
                    request.sshUser().trim(), encrypted);
            repository.save(server);
            var sshCredentials = request.credentials() == null
                    ? credentials.decrypt(server)
                    : credentials.toCredentials(request.credentials());
            // trustNewHostKey — явное действие пользователя после переустановки VPS.
            String pinned = Boolean.TRUE.equals(request.trustNewHostKey()) ? null : server.getHostKeyFingerprint();
            try {
                var result = ssh.probe(server.getHost(), server.getSshPort(), server.getSshUser(), sshCredentials, pinned);
                server.connectionResult(result.fingerprint(), result.operatingSystem());
            } catch (RuntimeException e) {
                server.markError(connectionError(e));
            }
            repository.save(server);
            return ServerResponse.from(server);
        });
    }

    public void delete(UUID user, UUID id) {
        lock.withLock(id, () -> {
            Server server = owned(user, id);
            // Сначала отзываем VLESS-доступ на самом VPS. Если это не удалось,
            // запись не удаляем, чтобы пользователь не считал доступ закрытым.
            removeVless(server);
            repository.delete(server);
        });
    }

    private void removeVless(Server server) {
        List<VlessInbound> list = inbounds.findAllByServerIdOrderByCreatedAtAsc(server.getId());
        if (list.isEmpty()) return;
        if (server.getHostKeyFingerprint() == null) {
            throw new IllegalStateException("server host key is not pinned; refusing to delete without remote cleanup");
        }
        RuntimeException failure = null;
        for (VlessInbound inbound : list) {
            try {
                vless.teardown(server, inbound);
            } catch (RuntimeException e) {
                failure = e;
                log.warn("VLESS cleanup failed for server {} inbound {}", server.getId(), inbound.getId());
            }
        }
        if (failure != null) {
            throw new SshConnectionException("could not revoke VLESS access on the server; server was not deleted", failure);
        }
    }

    private Server owned(UUID user, UUID id) {
        return repository.findByIdAndUserId(id, user).orElseThrow(ServerNotFoundException::new);
    }

    private void validateCredentials(ServerRequest.Credentials c) {
        if (c == null || !c.valid()) throw new IllegalArgumentException("provide exactly one of password or privateKey");
    }

    private void validateHost(String raw) {
        String host = raw == null ? "" : raw.trim();
        if (host.isBlank() || host.length() > 255 || host.contains("/") || host.contains("\\")
                || host.chars().anyMatch(Character::isWhitespace)
                || !Pattern.matches("^[A-Za-z0-9][A-Za-z0-9.:-]*[A-Za-z0-9]$|^[A-Za-z0-9]$", host)) {
            throw new IllegalArgumentException("host must be a valid hostname or IP address");
        }
    }

    private String connectionError(RuntimeException error) {
        return error instanceof SshConnectionException
                ? error.getMessage()
                : "SSH connection failed; check credentials, host key and server availability";
    }
}
