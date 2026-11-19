package com.example.vlesswarden.vless;

import com.example.vlesswarden.server.Server;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class VlessUriBuilder {
    public String uri(Server server, VlessInbound inbound, VlessClient client, String uuid) {
        String host = endpointHost(server.getHost());
        StringBuilder query = new StringBuilder();
        query.append("encryption=none");
        if (inbound.getFlow() != null && !inbound.getFlow().isBlank()) {
            query.append("&flow=").append(encode(inbound.getFlow()));
        }
        query.append("&security=reality")
                .append("&sni=").append(encode(inbound.primaryServerName()))
                .append("&fp=").append(encode(inbound.getFingerprint()))
                .append("&pbk=").append(encode(inbound.getPublicKey()))
                .append("&sid=").append(encode(inbound.shortIdList().isEmpty() ? "" : inbound.shortIdList().get(0)))
                .append("&spx=").append(encode("/"))
                .append("&type=tcp");
        return "vless://" + uuid + "@" + host + ":" + inbound.getListenPort()
                + "?" + query + "#" + encode(client.getName());
    }

    private String endpointHost(String host) {
        if (host == null) return "";
        if (host.contains(":") && !host.startsWith("[")) return "[" + host + "]";
        return host;
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
