package com.altsmod.mixin;

import com.altsmod.gui.AltManagerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void altsmod$addAltsButton(CallbackInfo ci) {
        // Add "Alts" button next to Multiplayer button
        this.addRenderableWidget(Button.builder(
                Component.literal("Alts"),
                btn -> this.minecraft.gui.setScreen(new AltManagerScreen(this))
        ).bounds(this.width / 2 + 104, this.height / 4 + 48 + 24 * 1, 55, 20).build());
    }
}
