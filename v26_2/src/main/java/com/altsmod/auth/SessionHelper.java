package com.altsmod.auth;

import com.altsmod.mixin.MinecraftClientAccessor;
import com.mojang.authlib.Environment;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.server.Services;

public final class SessionHelper {
    private static final Environment MOJANG_PROD = new Environment(
            "https://sessionserver.mojang.com",
            "https://api.minecraftservices.com",
            "https://api.mojang.com",
            "PROD"
    );

    private SessionHelper() {}

    public static void applySession(Minecraft mc, User session) {
        MinecraftClientAccessor mca = (MinecraftClientAccessor) mc;
        mca.setSession(session);
        try {
            YggdrasilAuthenticationService authService = new YggdrasilAuthenticationService(mca.getProxy(), MOJANG_PROD);
            try {
                Services services = Services.create(authService, mc.gameDirectory);
                mca.setServices(services);
                System.out.println("[AltsMod] Updated Minecraft Services to Mojang PROD environment.");
            } catch (Throwable t) {
                System.err.println("[AltsMod] Failed to update Services: " + t.getMessage());
                t.printStackTrace();
            }

            UserApiService apiService = UserApiService.OFFLINE;
            try {
                if (session.getAccessToken() != null && !session.getAccessToken().isBlank()) {
                    apiService = authService.createUserApiService(session.getAccessToken());
                    System.out.println("[AltsMod] UserApiService created with access token.");
                }
            } catch (Throwable ignored) {}
            mca.setUserApiService(apiService);

            try {
                mca.setProfileKeys(ProfileKeyPairManager.create(apiService, session, mc.gameDirectory.toPath()));
            } catch (Throwable ignored) {}

            try {
                mca.setAbuseReportContext(ReportingContext.create(ReportEnvironment.local(), apiService));
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            System.err.println("[AltsMod] Error in applySession: " + t.getMessage());
            t.printStackTrace();
        }
    }
}
