package com.example.vlesswarden.vless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class XrayConfigBuilder {
    private final ObjectMapper mapper;

    public XrayConfigBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String config(VlessInbound inbound, String privateKey, List<XrayClient> clients) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("log", Map.of(
                "loglevel", "warning",
                "access", "none",
                "error", "/var/log/vless-warden/error.log"));
        root.put("api", Map.of(
                "tag", "api",
                "listen", "127.0.0.1:10085",
                "services", List.of("HandlerService", "StatsService")));
        root.put("stats", Map.of());
        root.put("policy", Map.of(
                "levels", Map.of("0", Map.of(
                        "statsUserUplink", true,
                        "statsUserDownlink", true,
                        "statsUserOnline", true))));
        root.put("inbounds", List.of(inbound(inbound, privateKey, clients)));
        root.put("outbounds", List.of(Map.of("protocol", "freedom", "tag", "direct")));
        return write(root);
    }

    /**
     * Формат для `xray api adu`: полноценный inbound в конфиге,
     * из которого Xray возьмёт tag и пользователей.
     */
    public String userConfig(VlessInbound inbound, String privateKey, XrayClient client) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("inbounds", List.of(inbound(inbound, privateKey, List.of(client))));
        return write(root);
    }

    private Map<String, Object> inbound(VlessInbound inbound, String privateKey, List<XrayClient> clients) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("clients", clients.stream().map(this::client).toList());
        settings.put("decryption", "none");

        Map<String, Object> reality = new LinkedHashMap<>();
        reality.put("show", false);
        reality.put("target", inbound.getDest());
        reality.put("serverNames", inbound.serverNameList());
        reality.put("privateKey", privateKey);
        reality.put("shortIds", inbound.shortIdList());

        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("network", "raw");
        stream.put("security", "reality");
        stream.put("realitySettings", reality);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("listen", "0.0.0.0");
        result.put("port", inbound.getListenPort());
        result.put("protocol", "vless");
        result.put("tag", inbound.getTag());
        result.put("settings", settings);
        result.put("streamSettings", stream);
        return result;
    }

    private Map<String, Object> client(XrayClient client) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", client.id());
        value.put("email", client.email());
        if (client.flow() != null && !client.flow().isBlank()) value.put("flow", client.flow());
        return value;
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not build Xray configuration", e);
        }
    }

    static List<String> split(String value) {
        if (value == null || value.isBlank()) return new ArrayList<>();
        return new ArrayList<>(List.of(value.split(",")));
    }
}
