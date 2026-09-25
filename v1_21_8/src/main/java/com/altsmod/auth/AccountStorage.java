package com.altsmod.auth;

import com.altsmod.gui.AccountEntry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AccountStorage {
    private AccountStorage() {}

    public static Path getAltsDir(MinecraftClient mc) {
        Path dir = mc.runDirectory.toPath().resolve("alts");
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (Exception ignored) {}
        return dir;
    }

    public static List<AccountEntry> loadAccounts(MinecraftClient mc) {
        Path dir = getAltsDir(mc);
        List<AccountEntry> list = new ArrayList<>();
        if (!Files.isDirectory(dir)) return list;
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String fn = p.getFileName().toString();
                if (fn.endsWith(".json")) {
                    try {
                        String content = Files.readString(p, StandardCharsets.UTF_8);
                        JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
                        String username = obj.has("username") ? obj.get("username").getAsString() : fn.replace(".json", "");
                        UUID uuid = null;
                        if (obj.has("uuid")) {
                            try {
                                uuid = UUID.fromString(obj.get("uuid").getAsString());
                            } catch (Exception ignored) {}
                        }
                        String cred = obj.has("credential") ? obj.get("credential").getAsString() : content;
                        list.add(new AccountEntry(username, uuid, p, cred));
                    } catch (Exception ignored) {}
                } else if (fn.endsWith(".txt")) {
                    String cleanName = fn.replaceAll("\\.txt$", "");
                    list.add(new AccountEntry(cleanName, null, p, null));
                }
            });
        } catch (Exception ignored) {}
        return list;
    }

    public static void saveAccount(MinecraftClient mc, String username, UUID uuid, String credential) {
        try {
            Path dir = getAltsDir(mc);
            Path target = dir.resolve(username + ".json");
            JsonObject obj = new JsonObject();
            obj.addProperty("username", username);
            if (uuid != null) obj.addProperty("uuid", uuid.toString());
            obj.addProperty("credential", credential != null ? credential : "");
            obj.addProperty("savedAt", System.currentTimeMillis());
            Files.writeString(target, obj.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
