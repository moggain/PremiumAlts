package com.altsmod.gui;

import com.altsmod.auth.AccountStorage;
import com.altsmod.auth.MicrosoftOAuth;
import com.altsmod.auth.SessionHelper;
import com.altsmod.mixin.MinecraftClientAccessor;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.Session;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class AltManagerScreen extends Screen {
    private final Screen parent;
    private final List<AccountEntry> accounts = new ArrayList<>();
    private final List<AccountEntry> filteredAccounts = new ArrayList<>();

    private AccountEntry selectedAccount = null;
    private TextFieldWidget searchBox;
    private ButtonWidget loginButton;

    private int scrollOffset = 0;
    private final int itemHeight = 24;
    private long lastClickTime = 0;
    private AccountEntry lastClickedAccount = null;

    private String status = "§7Готов к работе";
    private boolean authenticating = false;

    public AltManagerScreen(Screen parent) {
        super(new LiteralText("Alt Manager"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        loadAccounts();

        int listX = 20;
        int listWidth = 190;

        // Search Box above the list
        this.searchBox = new TextFieldWidget(this.textRenderer, listX, 30, listWidth, 18, new LiteralText("Поиск..."));
        this.searchBox.setSuggestion("Поиск аккаунта...");
        this.searchBox.setChangedListener(this::onSearchChanged);
        this.addChild(this.searchBox);

        // Center of right area
        int contentCenterX = (listX + listWidth + 20 + this.width) / 2;
        int btnW = 200;
        int btnX = contentCenterX - (btnW / 2);
        int btnStartY = 70;

        // Button: LOGIN WITH SELECTED ACCOUNT
        this.loginButton = new ButtonWidget(
                btnX, btnStartY, btnW, 22,
                new LiteralText("▶ ВОЙТИ"),
                btn -> loginWithSelectedAccount()
        );
        this.loginButton.active = (selectedAccount != null);
        this.addButton(this.loginButton);

        // Button: Select file
        this.addButton(new ButtonWidget(
                btnX, btnStartY + 26, btnW, 20,
                new LiteralText("📁 Добавить файл (Cookie / Token)"),
                btn -> selectFileAndLogin()
        ));

        // Button: Login via browser
        this.addButton(new ButtonWidget(
                btnX, btnStartY + 50, btnW, 20,
                new LiteralText("🌐 Войти через браузер"),
                btn -> loginViaBrowser()
        ));

        // Button: Open Alts folder
        this.addButton(new ButtonWidget(
                btnX, btnStartY + 74, btnW, 20,
                new LiteralText("📂 Папка Alts"),
                btn -> openAltsFolder()
        ));

        // Button: Refresh list
        this.addButton(new ButtonWidget(
                btnX, btnStartY + 98, btnW, 20,
                new LiteralText("🔄 Обновить список"),
                btn -> {
                    loadAccounts();
                    setStatus("§7Список аккаунтов обновлен.");
                }
        ));

        // Button: Back
        this.addButton(new ButtonWidget(
                btnX, btnStartY + 126, btnW, 20,
                new LiteralText("Назад"),
                btn -> {
                    if (this.client != null) {
                        this.client.openScreen(this.parent);
                    }
                }
        ));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.searchBox != null) {
            this.searchBox.tick();
        }
    }

    private void onSearchChanged(String query) {
        if (this.searchBox != null) {
            this.searchBox.setSuggestion(query.isEmpty() ? "Поиск аккаунта..." : "");
        }
        updateFilteredAccounts();
    }

    private void loadAccounts() {
        this.accounts.clear();
        if (this.client != null) {
            this.accounts.addAll(AccountStorage.loadAccounts(this.client));
        }
        updateFilteredAccounts();
    }

    private void updateFilteredAccounts() {
        this.filteredAccounts.clear();
        String query = searchBox != null ? searchBox.getText().trim().toLowerCase(Locale.ROOT) : "";
        for (AccountEntry entry : this.accounts) {
            if (query.isEmpty() || entry.displayName().toLowerCase(Locale.ROOT).contains(query)) {
                this.filteredAccounts.add(entry);
            }
        }
    }

    private void updateLoginButton() {
        if (this.loginButton != null) {
            if (this.selectedAccount != null) {
                this.loginButton.setMessage(new LiteralText("§a▶ ВОЙТИ: " + this.selectedAccount.displayName()));
                this.loginButton.active = true;
            } else {
                this.loginButton.setMessage(new LiteralText("▶ ВОЙТИ (Выберите аккаунт)"));
                this.loginButton.active = false;
            }
        }
    }

    private void loginWithSelectedAccount() {
        if (this.selectedAccount == null || authenticating) return;
        if (this.selectedAccount.credential() != null && !this.selectedAccount.credential().isBlank()) {
            authenticateFromCredential(this.selectedAccount.credential(), this.selectedAccount.displayName());
        } else if (this.selectedAccount.path() != null && Files.exists(this.selectedAccount.path())) {
            authenticateFromFile(this.selectedAccount.path());
        }
    }

    private void authenticateFromCredential(String credential, String name) {
        setAuthenticating(true);
        setStatus("§eАвторизация [" + name + "]...");
        MicrosoftOAuth.loginFromStoredCredential(credential).whenComplete((login, error) -> {
            setAuthenticating(false);
            if (error != null) {
                System.err.println("[AltsMod] Login error: " + MicrosoftOAuth.friendlyMessage(error));
                error.printStackTrace();
                setStatus("§cОшибка входа: " + MicrosoftOAuth.friendlyMessage(error));
            } else if (login != null) {
                applyLoginResult(login);
            }
        });
    }

    private void selectFileAndLogin() {
        if (authenticating) return;
        setStatus("§eВыбор файла...");
        NativeTextFilePicker.chooseCredentialFile().whenComplete((optPath, error) -> {
            if (error != null) {
                setStatus("§cОшибка: " + error.getMessage());
                return;
            }
            if (optPath.isEmpty()) {
                setStatus("§7Выбор файла отменен.");
                return;
            }
            authenticateFromFile(optPath.get());
        });
    }

    private void authenticateFromFile(Path path) {
        setAuthenticating(true);
        setStatus("§eАвторизация [" + path.getFileName() + "]...");
        MicrosoftOAuth.loginFromCredentialFile(path).whenComplete((login, error) -> {
            setAuthenticating(false);
            if (error != null) {
                System.err.println("[AltsMod] Login error: " + MicrosoftOAuth.friendlyMessage(error));
                error.printStackTrace();
                setStatus("§cОшибка входа: " + MicrosoftOAuth.friendlyMessage(error));
            } else if (login != null) {
                applyLoginResult(login);
            }
        });
    }

    private void loginViaBrowser() {
        if (authenticating) return;
        setAuthenticating(true);
        setStatus("§eОжидание входа в браузере...");
        MicrosoftOAuth.loginInBrowser().whenComplete((login, error) -> {
            setAuthenticating(false);
            if (error != null) {
                System.err.println("[AltsMod] Browser login error: " + MicrosoftOAuth.friendlyMessage(error));
                error.printStackTrace();
                setStatus("§cОшибка входа: " + MicrosoftOAuth.friendlyMessage(error));
            } else if (login != null) {
                applyLoginResult(login);
            }
        });
    }

    private void openAltsFolder() {
        CompletableFuture.runAsync(() -> {
            try {
                if (this.client == null) return;
                Path altsDir = AccountStorage.getAltsDir(this.client);
                new ProcessBuilder("explorer.exe", altsDir.toAbsolutePath().toString()).start();
            } catch (Throwable e) {
                setStatus("§cНе удалось открыть папку: " + e.getMessage());
            }
        });
    }

    private void applyLoginResult(MicrosoftOAuth.LoginResult login) {
        MinecraftClient mc = this.client;
        if (mc == null) return;
        mc.execute(() -> {
            try {
                Session session = new Session(
                        login.username(),
                        login.uuid().toString(),
                        login.minecraftAccessToken(),
                        "mojang"
                );
                SessionHelper.applySession(mc, session);

                // Save to alts directory
                AccountStorage.saveAccount(mc, login.username(), login.uuid(), login.refreshToken());
                loadAccounts();

                for (AccountEntry a : this.accounts) {
                    if (a.name().equalsIgnoreCase(login.username())) {
                        this.selectedAccount = a;
                        break;
                    }
                }

                setStatus("§aУспешный вход! Ник: §f" + login.username());
                System.out.println("[AltsMod] Logged in as: " + login.username() + " (" + login.uuid() + ")");
                updateLoginButton();
            } catch (Throwable e) {
                System.err.println("[AltsMod] Session apply error: " + e.getMessage());
                e.printStackTrace();
                setStatus("§cОшибка применения сессии: " + e.getMessage());
            }
        });
    }

    private void setStatus(String message) {
        this.status = message;
    }

    private void setAuthenticating(boolean state) {
        this.authenticating = state;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int listX = 20;
        int listWidth = 190;
        int listY = 56;
        int listHeight = this.height - 76;

        if (mouseX >= listX && mouseX <= listX + listWidth
                && mouseY >= listY && mouseY <= listY + listHeight) {
            int relativeY = (int) (mouseY - listY + scrollOffset);
            int index = relativeY / itemHeight;
            if (index >= 0 && index < this.filteredAccounts.size()) {
                this.selectedAccount = this.filteredAccounts.get(index);
                setStatus("§7Выбран: §e" + this.selectedAccount.displayName());
                updateLoginButton();
                long now = System.currentTimeMillis();
                if (this.selectedAccount == lastClickedAccount && (now - lastClickTime) < 350) {
                    loginWithSelectedAccount();
                }
                lastClickTime = now;
                lastClickedAccount = this.selectedAccount;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int listX = 20;
        int listWidth = 190;
        int listY = 56;
        int listHeight = this.height - 76;

        if (mouseX >= listX && mouseX <= listX + listWidth
                && mouseY >= listY && mouseY <= listY + listHeight) {
            int maxScroll = Math.max(0, this.filteredAccounts.size() * itemHeight - listHeight);
            this.scrollOffset = Math.max(0, Math.min(maxScroll, (int) (this.scrollOffset - (amount * 18))));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public void onClose() {
        if (this.client != null) {
            this.client.openScreen(this.parent);
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        // Dark background
        fill(matrices, 0, 0, this.width, this.height, 0xD0000000);

        int listX = 20;
        int listWidth = 190;
        int listY = 56;
        int listHeight = this.height - 76;

        // List Header
        this.textRenderer.drawWithShadow(matrices, "§fСписок аккаунтов (§7" + this.filteredAccounts.size() + "§f)", listX, 18, 0xFFFFFF);

        // Account List Background
        fill(matrices, listX, listY, listX + listWidth, listY + listHeight, 0x80101010);
        fill(matrices, listX - 1, listY - 1, listX + listWidth + 1, listY, 0xFF333333);
        fill(matrices, listX - 1, listY + listHeight, listX + listWidth + 1, listY + listHeight + 1, 0xFF333333);
        fill(matrices, listX - 1, listY, listX, listY + listHeight, 0xFF333333);
        fill(matrices, listX + listWidth, listY, listX + listWidth + 1, listY + listHeight, 0xFF333333);

        // Draw Account items: ONLY SKIN HEAD AND NICKNAME!
        String currentNick = this.client != null && this.client.getSession() != null ? this.client.getSession().getUsername() : "";
        for (int i = 0; i < this.filteredAccounts.size(); i++) {
            int itemY = listY + (i * itemHeight) - scrollOffset;
            if (itemY + itemHeight < listY || itemY > listY + listHeight) continue;

            AccountEntry entry = this.filteredAccounts.get(i);
            boolean isSelected = entry == this.selectedAccount;
            boolean isHovered = mouseX >= listX && mouseX <= listX + listWidth
                    && mouseY >= Math.max(listY, itemY) && mouseY <= Math.min(listY + listHeight, itemY + itemHeight);

            int drawHeight = Math.min(itemHeight, listY + listHeight - itemY);
            if (drawHeight <= 0) continue;

            if (isSelected) {
                fill(matrices, listX + 1, itemY, listX + listWidth - 1, itemY + drawHeight, 0x802563EB);
            } else if (isHovered) {
                fill(matrices, listX + 1, itemY, listX + listWidth - 1, itemY + drawHeight, 0x2AFFFFFF);
            }

            // Draw player skin head (16x16)
            try {
                Identifier skinTex = DefaultSkinHelper.getTexture();
                if (this.client != null) {
                    this.client.getTextureManager().bindTexture(skinTex);
                    drawTexture(matrices, listX + 4, itemY + 4, 16, 16, 8.0F, 8.0F, 8, 8, 64, 64);
                    drawTexture(matrices, listX + 4, itemY + 4, 16, 16, 40.0F, 8.0F, 8, 8, 64, 64);
                }
            } catch (Throwable ignored) {}

            boolean isCurrentActive = entry.displayName().equalsIgnoreCase(currentNick);
            String nameText = (isCurrentActive ? "§a✔ " : (isSelected ? "§b" : "§f")) + entry.displayName();
            // Nickname only, placed right next to head (listX + 24)
            this.textRenderer.drawWithShadow(matrices, nameText, listX + 24, itemY + 8, 0xFFFFFF);
        }

        // Draw Search input
        if (this.searchBox != null) {
            this.searchBox.render(matrices, mouseX, mouseY, delta);
        }

        // RIGHT SIDE
        int contentCenterX = (listX + listWidth + 20 + this.width) / 2;

        // BIG ANIMATED GOLDEN GRADIENT: Made by PremiumAlts
        String prefix = "Made by ";
        String brand = "PremiumAlts";
        int prefixW = this.textRenderer.getWidth(prefix);
        int brandW = this.textRenderer.getWidth(brand);
        int fullW = prefixW + brandW;

        matrices.push();
        float scale = 1.35f;
        matrices.translate(contentCenterX, 18, 0);
        matrices.scale(scale, scale, 1.0f);

        int baseX = -fullW / 2;
        this.textRenderer.drawWithShadow(matrices, prefix, baseX, 0, 0xFFFFFF);

        long time = System.currentTimeMillis();
        int brandStartX = baseX + prefixW;
        for (int i = 0; i < brand.length(); i++) {
            char ch = brand.charAt(i);
            int charOffset = this.textRenderer.getWidth(brand.substring(0, i));
            double wave = Math.sin((time * 0.004) + (i * 0.4));
            float factor = (float) (0.5 + 0.5 * wave);
            int r = 255;
            int g = (int) (165 + (242 - 165) * factor);
            int b = (int) (0 + (117 - 0) * factor);
            int goldColor = 0xFF000000 | (r << 16) | (g << 8) | b;
            this.textRenderer.drawWithShadow(matrices, String.valueOf(ch), brandStartX + charOffset, 0, goldColor);
        }
        matrices.pop();

        // Active Session Info
        String activeText = "§7Текущий аккаунт: §a" + (currentNick.isEmpty() ? "Offline" : currentNick);
        this.textRenderer.drawWithShadow(matrices, activeText, contentCenterX - this.textRenderer.getWidth(activeText) / 2.0f, 40, 0xFFFFFF);

        // Selected Account info
        String selText = "§7Выбран: §e" + (this.selectedAccount != null ? this.selectedAccount.displayName() : "не выбран");
        this.textRenderer.drawWithShadow(matrices, selText, contentCenterX - this.textRenderer.getWidth(selText) / 2.0f, 52, 0xFFFFFF);

        // Status text at bottom
        if (this.status != null) {
            this.textRenderer.drawWithShadow(matrices, this.status, contentCenterX - this.textRenderer.getWidth(this.status) / 2.0f, this.height - 24, 0xFFFFFF);
        }

        super.render(matrices, mouseX, mouseY, delta);
    }
}
