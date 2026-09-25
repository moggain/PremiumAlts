package com.altsmod.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.ProfileKeys;
import net.minecraft.client.session.Session;
import net.minecraft.client.session.report.AbuseReportContext;
import net.minecraft.util.ApiServices;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.net.Proxy;

@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Mutable
    @Accessor("session")
    void setSession(Session session);

    @Accessor("networkProxy")
    Proxy getNetworkProxy();

    @Mutable
    @Accessor("apiServices")
    void setApiServices(ApiServices apiServices);

    @Mutable
    @Accessor("userApiService")
    void setUserApiService(UserApiService apiService);

    @Mutable
    @Accessor("profileKeys")
    void setProfileKeys(ProfileKeys profileKeys);

    @Mutable
    @Accessor("abuseReportContext")
    void setAbuseReportContext(AbuseReportContext abuseReportContext);
}
