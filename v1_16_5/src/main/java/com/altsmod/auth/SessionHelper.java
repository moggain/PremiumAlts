package com.altsmod.auth;

import com.altsmod.mixin.MinecraftClientAccessor;
import com.mojang.authlib.Environment;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Session;

import java.lang.reflect.Field;
import java.net.URL;

public final class SessionHelper {
    private static final boolean IS_ELY;

    static {
        boolean ely = false;
        try {
            Class.forName("by.ely.authlib.ElyEnvironment");
            ely = true;
        } catch (Throwable ignored) {}
        IS_ELY = ely;
    }

    private static final Environment MOJANG_PROD = new Environment() {
        @Override
        public String getAuthHost() {
            return "https://authserver.mojang.com";
        }

        @Override
        public String getAccountsHost() {
            return "https://api.mojang.com";
        }

        @Override
        public String getSessionHost() {
            return IS_ELY ? "https://sessionserver.mojang.com/session/minecraft" : "https://sessionserver.mojang.com";
        }

        @Override
        public String getServicesHost() {
            return "https://api.minecraftservices.com";
        }

        @Override
        public String getName() {
            return "PROD";
        }

        @Override
        public String asString() {
            return "PROD";
        }
    };

    private SessionHelper() {}

    public static void applySession(MinecraftClient mc, Session session) {
        MinecraftClientAccessor mca = (MinecraftClientAccessor) mc;
        mca.setSession(session);
        try {
            YggdrasilAuthenticationService authService = new YggdrasilAuthenticationService(mc.getNetworkProxy(), MOJANG_PROD);
            try {
                MinecraftSessionService sessionService = authService.createMinecraftSessionService();
                fixMojangUrls(sessionService);
                mca.setSessionService(sessionService);
                System.out.println("[AltsMod] Updated MinecraftSessionService to Mojang PROD environment: " + MOJANG_PROD.getSessionHost());
            } catch (Throwable t) {
                System.err.println("[AltsMod] Failed to update SessionService: " + t.getMessage());
                t.printStackTrace();
            }
        } catch (Throwable t) {
            System.err.println("[AltsMod] Error in applySession: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private static void fixMojangUrls(MinecraftSessionService sessionService) {
        if (!(sessionService instanceof YggdrasilMinecraftSessionService)) return;
        try {
            Field joinUrlField = findField(YggdrasilMinecraftSessionService.class, "joinUrl");
            if (joinUrlField != null) {
                joinUrlField.setAccessible(true);
                joinUrlField.set(sessionService, new URL("https://sessionserver.mojang.com/session/minecraft/join"));
                System.out.println("[AltsMod] Forced joinUrl to https://sessionserver.mojang.com/session/minecraft/join");
            }
            Field baseUrlField = findField(YggdrasilMinecraftSessionService.class, "baseUrl");
            if (baseUrlField != null) {
                baseUrlField.setAccessible(true);
                baseUrlField.set(sessionService, "https://sessionserver.mojang.com/session/minecraft/");
                System.out.println("[AltsMod] Forced baseUrl to https://sessionserver.mojang.com/session/minecraft/");
            }
        } catch (Throwable t) {
            System.err.println("[AltsMod] Warning: could not reflectively force joinUrl: " + t.getMessage());
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}