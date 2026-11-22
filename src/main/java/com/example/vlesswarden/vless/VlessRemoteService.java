package com.example.vlesswarden.vless;

import com.example.vlesswarden.server.Server;
import com.example.vlesswarden.server.ServerCredentialService;
import com.example.vlesswarden.ssh.SshClientService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class VlessRemoteService {
    private final SshClientService ssh;
    private final ServerCredentialService credentials;
    private final String xrayVersion;

    public VlessRemoteService(SshClientService ssh, ServerCredentialService credentials,
                              @Value("${app.vless.xray-version:v26.11.9}") String xrayVersion) {
        this.ssh = ssh;
        this.credentials = credentials;
        this.xrayVersion = xrayVersion;
    }

    public record ProvisionResult(String version, boolean firewallAdded) {
    }

    public ProvisionResult provision(Server server, int port, Integer oldFirewallPort, String configJson) {
        return provision(server, port, oldFirewallPort, configJson, TimeUnit.MINUTES.toMillis(10));
    }

    public ProvisionResult provision(Server server, int port, Integer oldFirewallPort, String configJson,
                                     long timeoutMillis) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid listen port");
        int previousPort = oldFirewallPort != null && oldFirewallPort >= 1 && oldFirewallPort <= 65535
                ? oldFirewallPort : 0;
        String baseUrl = "https://github.com/XTLS/Xray-core/releases/download/" + safeVersion();
        String script = PROVISION_SCRIPT
                .replace("{{BASE_URL}}", baseUrl)
                .replace("{{PORT}}", Integer.toString(port))
                .replace("{{OLD_PORT}}", Integer.toString(previousPort))
                .replace("{{CONFIG_B64}}", java.util.Base64.getEncoder().encodeToString(
                        configJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String output = run(server, script, timeoutMillis);
        String version = value(output, "VLESS_VERSION");
        if (version.isBlank()) throw new IllegalStateException("Xray provisioning returned no version");
        return new ProvisionResult(version, "1".equals(value(output, "VLESS_FIREWALL")));
    }

    public void addClient(Server server, VlessInbound inbound, String configJson,
                          String userConfigJson, String email) {
        String script = CLIENT_SCRIPT
                .replace("{{CONFIG_B64}}", base64(configJson))
                .replace("{{USER_B64}}", base64(userConfigJson))
                .replace("{{TAG}}", inbound.getTag());
        run(server, script, TimeUnit.MINUTES.toMillis(2));
    }

    public void removeClient(Server server, VlessInbound inbound, String configJson, String email) {
        String script = REMOVE_SCRIPT
                .replace("{{CONFIG_B64}}", base64(configJson))
                .replace("{{TAG}}", inbound.getTag())
                .replace("{{EMAIL}}", safeEmail(email));
        run(server, script, TimeUnit.MINUTES.toMillis(2));
    }

    public void replaceClient(Server server, VlessInbound inbound, String configJson,
                              String userConfigJson, String email) {
        String script = REPLACE_SCRIPT
                .replace("{{CONFIG_B64}}", base64(configJson))
                .replace("{{USER_B64}}", base64(userConfigJson))
                .replace("{{TAG}}", inbound.getTag())
                .replace("{{EMAIL}}", safeEmail(email));
        run(server, script, TimeUnit.MINUTES.toMillis(2));
    }

    public void teardown(Server server, VlessInbound inbound) {
        Integer firewallPort = inbound.getFirewallPort();
        String script = TEARDOWN_SCRIPT
                .replace("{{FIREWALL_PORT}}", firewallPort == null ? "0" : Integer.toString(firewallPort));
        run(server, script, TimeUnit.MINUTES.toMillis(2));
    }

    /**
     * Заменяет runtime-таблицу routing-правил Xray. Пустой список снимает
     * все IP-баны. Правила живут в памяти и теряются при рестарте Xray.
     */
    public void applyIpRules(Server server, String rulesJson) {
        String script = APPLY_RULES_SCRIPT.replace("{{RULES_B64}}", base64(rulesJson));
        run(server, script, TimeUnit.MINUTES.toMillis(1));
    }

    private String run(Server server, String script, long timeoutMillis) {
        if (server.getHostKeyFingerprint() == null) {
            throw new IllegalStateException("server host key is not pinned; reconnect and verify the server first");
        }
        return ssh.runSudoScript(server.getHost(), server.getSshPort(), server.getSshUser(),
                credentials.decrypt(server), server.getHostKeyFingerprint(), script, timeoutMillis);
    }

    private String safeVersion() {
        if (xrayVersion == null || !xrayVersion.matches("v[0-9][A-Za-z0-9._-]{0,31}")) {
            throw new IllegalStateException("invalid Xray version");
        }
        return xrayVersion;
    }

    private String safeEmail(String email) {
        if (email == null || !email.matches("vw_[a-f0-9]{32}")) {
            throw new IllegalStateException("invalid client email");
        }
        return email;
    }

    private String base64(String value) {
        return java.util.Base64.getEncoder().encodeToString(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String value(String output, String key) {
        return output.lines()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.substring(key.length() + 1).trim())
                .findFirst()
                .orElse("");
    }

    private static final String PROVISION_SCRIPT = """
            set -euo pipefail
            export DEBIAN_FRONTEND=noninteractive
            exec 9>/run/lock/vless-warden.lock
            flock -x 9
            PORT={{PORT}}
            OLD_PORT={{OLD_PORT}}
            BIN_DIR=/opt/vless-warden/bin
            XRAY="$BIN_DIR/xray"
            CONFIG_DIR=/etc/vless-warden
            CONFIG="$CONFIG_DIR/config.json"
            LOG_DIR=/var/log/vless-warden
            SERVICE=vless-warden

            if ! id "$SERVICE" >/dev/null 2>&1; then
              useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE"
            fi
            install -d -m 755 /opt/vless-warden "$BIN_DIR"
            install -d -m 750 -o root -g "$SERVICE" "$CONFIG_DIR"
            # vless-warden должен писать лог: каталог логов и error.log доступны пользователю сервиса.
            install -d -m 770 -o root -g "$SERVICE" "$LOG_DIR"
            chmod 770 "$LOG_DIR" 2>/dev/null || true
            touch "$LOG_DIR/error.log" 2>/dev/null || true
            chown "$SERVICE:$SERVICE" "$LOG_DIR/error.log" 2>/dev/null || true

            if command -v ss >/dev/null 2>&1; then
              if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${PORT}$"; then
                # Порт может быть занят только самим Xray из прошлого provisioning.
                if [ ! -f "$CONFIG" ] || ! grep -qE "port[^0-9]{1,12}${PORT}([^0-9]|$)" "$CONFIG"; then
                  echo "TCP port ${PORT} is already in use by another service" >&2
                  exit 30
                fi
              fi
            fi

            if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1 || ! command -v sha256sum >/dev/null 2>&1; then
              apt-get -o DPkg::Lock::Timeout=180 update >/dev/null
              apt-get -o DPkg::Lock::Timeout=180 install -y curl unzip ca-certificates coreutils >/dev/null
            fi

            case "$(uname -m)" in
              x86_64|amd64) ARCH=64 ;;
              aarch64|arm64) ARCH=arm64-v8a ;;
              armv7l|armv7) ARCH=arm32-v7a ;;
              *) echo "unsupported CPU architecture" >&2; exit 34 ;;
            esac

            if [ ! -x "$XRAY" ]; then
              tmp=$(mktemp -d)
              trap 'rm -rf "$tmp"' EXIT
              URL="{{BASE_URL}}/Xray-linux-${ARCH}.zip"
              curl -fsSL --retry 3 -o "$tmp/xray.zip" "$URL"
              curl -fsSL --retry 3 -o "$tmp/xray.zip.dgst" "$URL.dgst"
              expected=$(grep -oE '[A-Fa-f0-9]{64}' "$tmp/xray.zip.dgst" | head -n1)
              actual=$(sha256sum "$tmp/xray.zip" | awk '{print $1}')
              if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
                echo "Xray checksum mismatch" >&2
                exit 31
              fi
              unzip -o -j "$tmp/xray.zip" xray -d "$BIN_DIR" >/dev/null
              chmod 755 "$XRAY"
            fi
            "$XRAY" version

            printf '%s' '{{CONFIG_B64}}' | base64 -d > "$CONFIG.new"
            chown root:"$SERVICE" "$CONFIG.new"
            chmod 640 "$CONFIG.new"
            # Xray определяет формат конфига по имени файла — сначала .new переименуем в $CONFIG.
            mv "$CONFIG.new" "$CONFIG"
            "$XRAY" -test -config "$CONFIG" >/dev/null

            cat > /etc/systemd/system/vless-warden.service <<'UNIT'
            [Unit]
            Description=vless-warden Xray VLESS/REALITY
            After=network-online.target
            Wants=network-online.target

            [Service]
            Type=simple
            User=vless-warden
            Group=vless-warden
            ExecStart=/opt/vless-warden/bin/xray run -config /etc/vless-warden/config.json
            Restart=on-failure
            RestartSec=2
            AmbientCapabilities=CAP_NET_BIND_SERVICE
            CapabilityBoundingSet=CAP_NET_BIND_SERVICE
            NoNewPrivileges=true
            PrivateTmp=true
            ProtectHome=true
            ProtectSystem=strict
            ReadWritePaths=/etc/vless-warden /var/log/vless-warden
            LimitNOFILE=65535

            [Install]
            WantedBy=multi-user.target
            UNIT

            systemctl daemon-reload
            systemctl enable "$SERVICE" >/dev/null
            systemctl restart "$SERVICE"
            sleep 1
            if ! systemctl is-active --quiet "$SERVICE"; then
              systemctl --no-pager --lines=20 status "$SERVICE" >&2 || true
              exit 32
            fi

            firewall_added=0
            if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q 'Status: active'; then
              if ! ufw status 2>/dev/null | grep -qE "(^|[[:space:]])${PORT}/tcp[[:space:]]+ALLOW"; then
                if ufw allow "${PORT}/tcp" >/dev/null 2>&1; then firewall_added=1; fi
              fi
            fi

            # После успешной смены порта убираем только то правило, которое
            # панель добавила сама для предыдущего порта.
            if [ "$OLD_PORT" != "0" ] && [ "$OLD_PORT" != "$PORT" ] \\
               && command -v ufw >/dev/null 2>&1; then
              ufw delete allow "${OLD_PORT}/tcp" >/dev/null 2>&1 || true
            fi

            version=$("$XRAY" version 2>/dev/null | head -n1 || true)
            version=${version:0:64}
            printf 'VLESS_VERSION=%s\\n' "$version"
            printf 'VLESS_FIREWALL=%s\\n' "$firewall_added"
            """;

    private static final String CLIENT_SCRIPT = """
            set -euo pipefail
            exec 9>/run/lock/vless-warden.lock
            flock -x 9
            CONFIG=/etc/vless-warden/config.json
            XRAY=/opt/vless-warden/bin/xray
            SERVICE=vless-warden
            backup=$(mktemp)
            user_file=$(mktemp)
            cp -p "$CONFIG" "$backup"
            trap 'rm -f "$backup" "$user_file" "$CONFIG.new"' EXIT

            printf '%s' '{{CONFIG_B64}}' | base64 -d > "$CONFIG.new"
            chown root:"$SERVICE" "$CONFIG.new"
            chmod 640 "$CONFIG.new"
            # Xray определяет формат конфига по имени файла — сначала .new переименуем в $CONFIG.
            mv "$CONFIG.new" "$CONFIG"
            "$XRAY" -test -config "$CONFIG" >/dev/null

            printf '%s' '{{USER_B64}}' | base64 -d > "$user_file"
            chmod 600 "$user_file"
            # xray api adu завершается 0 даже при ошибке, поэтому успех проверяем
            # по выводу: если нет "result: ok", перезапускаем Xray.
            if ! add_output=$("$XRAY" api adu --server=127.0.0.1:10085 "$user_file" 2>&1) \\
               || ! printf '%s\\n' "$add_output" | grep -q 'result: ok'; then
              if ! systemctl restart "$SERVICE"; then
                cp -p "$backup" "$CONFIG"
                systemctl restart "$SERVICE" >/dev/null 2>&1 || true
                echo "could not add Xray client" >&2
                exit 40
              fi
            fi
            sleep 1
            if ! systemctl is-active --quiet "$SERVICE"; then
              cp -p "$backup" "$CONFIG"
              systemctl restart "$SERVICE" >/dev/null 2>&1 || true
              echo "Xray stopped after adding a client" >&2
              exit 41
            fi
            """;

    private static final String REMOVE_SCRIPT = """
            set -euo pipefail
            exec 9>/run/lock/vless-warden.lock
            flock -x 9
            CONFIG=/etc/vless-warden/config.json
            XRAY=/opt/vless-warden/bin/xray
            SERVICE=vless-warden
            backup=$(mktemp)
            cp -p "$CONFIG" "$backup"
            trap 'rm -f "$backup" "$CONFIG.new"' EXIT

            printf '%s' '{{CONFIG_B64}}' | base64 -d > "$CONFIG.new"
            chown root:"$SERVICE" "$CONFIG.new"
            chmod 640 "$CONFIG.new"
            # Xray определяет формат конфига по имени файла — сначала .new переименуем в $CONFIG.
            mv "$CONFIG.new" "$CONFIG"
            "$XRAY" -test -config "$CONFIG" >/dev/null

            # xray api rmu завершается 0 даже при ошибке: проверяем "Removed N user".
            if ! remove_output=$("$XRAY" api rmu --server=127.0.0.1:10085 -tag={{TAG}} '{{EMAIL}}' 2>&1) \\
               || ! printf '%s\\n' "$remove_output" | grep -qE 'Removed [1-9][0-9]* user(s)?'; then
              if ! systemctl restart "$SERVICE"; then
                cp -p "$backup" "$CONFIG"
                systemctl restart "$SERVICE" >/dev/null 2>&1 || true
                echo "could not remove Xray client" >&2
                exit 42
              fi
            fi
            sleep 1
            if ! systemctl is-active --quiet "$SERVICE"; then
              cp -p "$backup" "$CONFIG"
              systemctl restart "$SERVICE" >/dev/null 2>&1 || true
              echo "Xray stopped after removing a client" >&2
              exit 43
            fi
            """;

    private static final String REPLACE_SCRIPT = """
            set -euo pipefail
            exec 9>/run/lock/vless-warden.lock
            flock -x 9
            CONFIG=/etc/vless-warden/config.json
            XRAY=/opt/vless-warden/bin/xray
            SERVICE=vless-warden
            backup=$(mktemp)
            user_file=$(mktemp)
            cp -p "$CONFIG" "$backup"
            trap 'rm -f "$backup" "$user_file" "$CONFIG.new"' EXIT

            printf '%s' '{{CONFIG_B64}}' | base64 -d > "$CONFIG.new"
            chown root:"$SERVICE" "$CONFIG.new"
            chmod 640 "$CONFIG.new"
            # Xray определяет формат конфига по имени файла — сначала .new переименуем в $CONFIG.
            mv "$CONFIG.new" "$CONFIG"
            "$XRAY" -test -config "$CONFIG" >/dev/null

            # Проверяем оба шага: если хотя бы один не подтвердился выводом,
            # перезапускаем Xray.
            remove_failed=0
            remove_output=$("$XRAY" api rmu --server=127.0.0.1:10085 -tag={{TAG}} '{{EMAIL}}' 2>&1) \\
               || remove_failed=1
            printf '%s' '{{USER_B64}}' | base64 -d > "$user_file"
            chmod 600 "$user_file"
            add_failed=0
            add_output=$("$XRAY" api adu --server=127.0.0.1:10085 "$user_file" 2>&1) \\
               || add_failed=1
            if [ "$remove_failed" = 1 ] \\
               || [ "$add_failed" = 1 ] \\
               || ! printf '%s\\n' "$remove_output" | grep -qE 'Removed [1-9][0-9]* user(s)?' \\
               || ! printf '%s\\n' "$add_output" | grep -q 'result: ok'; then
              if ! systemctl restart "$SERVICE"; then
                cp -p "$backup" "$CONFIG"
                systemctl restart "$SERVICE" >/dev/null 2>&1 || true
                echo "could not rotate Xray client" >&2
                exit 44
              fi
            fi
            sleep 1
            if ! systemctl is-active --quiet "$SERVICE"; then
              cp -p "$backup" "$CONFIG"
              systemctl restart "$SERVICE" >/dev/null 2>&1 || true
              echo "Xray stopped after rotating a client" >&2
              exit 45
            fi
            """;

    private static final String APPLY_RULES_SCRIPT = """
            set -euo pipefail
            exec 9>/run/lock/vless-warden.lock
            flock -x 9
            XRAY=/opt/vless-warden/bin/xray
            rules_file=$(mktemp)
            trap 'rm -f "$rules_file"' EXIT

            printf '%s' '{{RULES_B64}}' | base64 -d > "$rules_file"
            chmod 600 "$rules_file"
            # adrules без -append заменяет всю таблицу правил. Проверяем и
            # exit code, и текст: часть версий Xray печатает ошибку, но
            # завершается успешно.
            if ! output=$("$XRAY" api adrules --server=127.0.0.1:10085 "$rules_file" 2>&1); then
              printf '%s\\n' "$output" >&2
              echo "could not apply Xray routing rules" >&2
              exit 50
            fi
            if printf '%s\\n' "$output" | grep -qiE 'failed|unknown service|not found'; then
              printf '%s\\n' "$output" >&2
              echo "Xray rejected routing rules" >&2
              exit 51
            fi
            """;

    private static final String TEARDOWN_SCRIPT = """
            set -euo pipefail
            systemctl disable --now vless-warden >/dev/null 2>&1 || true
            rm -f /etc/systemd/system/vless-warden.service
            systemctl daemon-reload
            rm -f /etc/vless-warden/config.json /etc/vless-warden/config.json.new
            rmdir /etc/vless-warden 2>/dev/null || true
            rm -rf /var/log/vless-warden /opt/vless-warden
            if [ "{{FIREWALL_PORT}}" != "0" ] && command -v ufw >/dev/null 2>&1; then
              ufw delete allow {{FIREWALL_PORT}}/tcp >/dev/null 2>&1 || true
            fi
            if id vless-warden >/dev/null 2>&1; then
              userdel vless-warden >/dev/null 2>&1 || true
            fi
            """;
}
