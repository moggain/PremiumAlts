package com.altsmod.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Microsoft OAuth authentication supporting browser login, refresh tokens, and cookie files. */
public final class MicrosoftOAuth {
    private static final String CLIENT_ID = System.getProperty(
            "altsmod.microsoft.clientId", "54fd49e4-2103-4044-9603-2b028c814ec3");
    private static final String SCOPE = "XboxLive.signin XboxLive.offline_access";
    private static final String COOKIE_CLIENT_ID = "00000000402b5328";
    private static final String COOKIE_REDIRECT = "https://login.live.com/oauth20_desktop.srf";
    private static final String COOKIE_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    private static final String COOKIE_CREDENTIAL_PREFIX = "cookies-v1:";
    private static final String SISU_AUTH_URL = "https://sisu.xboxlive.com/connect/XboxLive/"
            + "?state=login&cobrandId=8058f65d-ce06-4c30-9559-473c9275a65d"
            + "&tid=896928775&ru=https%3A%2F%2Fwww.minecraft.net%2Fen-us%2Flogin"
            + "&aid=1142970254";
    private static final String CALLBACK_PATH = "/in_game_account_switcher_long_enough_uri_"
            + "to_prevent_accidental_leaks_on_screensharing_even_if_you_have_like_extremely_big_"
            + "screen_though_it_might_not_mork_but_we_will_try_it_anyway_to_prevent_funny_"
            + "things_from_happening_or_something";
    private static final String USER_AGENT = "AltsMod/1.0";
    private static final String COOKIE_AUTH_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) "
            + "Gecko/20100101 Firefox/146.0";
    private static final long MAX_CREDENTIAL_FILE_BYTES = 2L * 1024L * 1024L;
    private static final Set<String> MICROSOFT_COOKIE_HOSTS = Set.of(
            "live.com", "login.live.com", "account.live.com",
            "microsoft.com", "account.microsoft.com", "login.microsoftonline.com");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private MicrosoftOAuth() {}

    public static void openUri(URI uri) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", uri.toString()).start();
            } else {
                new ProcessBuilder("xdg-open", uri.toString()).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static CompletableFuture<LoginResult> loginInBrowser() {
        CompletableFuture<LoginResult> result = new CompletableFuture<>();
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int port = server.getAddress().getPort();
            String redirect = "http://localhost:" + port + CALLBACK_PATH;
            String state = randomState();
            AtomicBoolean callbackHandled = new AtomicBoolean();
            server.createContext(CALLBACK_PATH,
                    exchange -> acceptCallback(exchange, redirect, state, callbackHandled, result));
            server.setExecutor(CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS));
            server.start();

            result.orTimeout(5, TimeUnit.MINUTES).whenComplete((ignored, error) -> server.stop(0));
            String authUrl = "https://login.live.com/oauth20_authorize.srf"
                    + "?client_id=" + encode(CLIENT_ID)
                    + "&response_type=code"
                    + "&scope=" + encode(SCOPE)
                    + "&redirect_uri=" + encode(redirect)
                    + "&prompt=select_account"
                    + "&state=" + encode(state);
            openUri(URI.create(authUrl));
        } catch (Exception error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    public static CompletableFuture<LoginResult> refresh(String refreshToken) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loginWithRefreshToken(refreshToken);
            } catch (Exception error) {
                throw new RuntimeException(friendlyMessage(error), error);
            }
        });
    }

    /** Imports either a Microsoft refresh token or an exported Microsoft cookie file. */
    public static CompletableFuture<LoginResult> loginFromCredentialFile(Path path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (path == null || !Files.isRegularFile(path)) {
                    throw new IOException("The selected credential file does not exist.");
                }
                long size = Files.size(path);
                if (size <= 0L || size > MAX_CREDENTIAL_FILE_BYTES) {
                    throw new IOException("The credential file must be between 1 byte and 2 MB.");
                }
                String text = Files.readString(path, StandardCharsets.UTF_8).trim();
                return loginFromCredentialText(text);
            } catch (Exception error) {
                throw new RuntimeException(friendlyMessage(error), error);
            }
        });
    }

    /** Re-authenticates a raw credential or base64 cookie payload. */
    public static CompletableFuture<LoginResult> loginFromStoredCredential(String credential) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String text = credential;
                if (text != null && text.startsWith(COOKIE_CREDENTIAL_PREFIX)) {
                    text = new String(Base64.getDecoder().decode(
                            text.substring(COOKIE_CREDENTIAL_PREFIX.length())),
                            StandardCharsets.UTF_8);
                }
                return loginFromCredentialText(text == null ? "" : text.trim());
            } catch (Exception error) {
                throw new RuntimeException(friendlyMessage(error), error);
            }
        });
    }

    public static LoginResult loginFromCredentialText(String text) throws Exception {
        String refreshToken = findRefreshToken(text);
        if (refreshToken != null) return loginWithRefreshToken(refreshToken);
        List<ImportedCookie> cookies = parseCookies(text);
        if (cookies.isEmpty()) {
            throw new IOException("Unsupported file format. Select Microsoft cookies or a refresh token.");
        }
        String persistentCredential = COOKIE_CREDENTIAL_PREFIX
                + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        TokenPair tokens;
        try {
            tokens = exchangeCookiesForTokenPair(cookies);
        } catch (Exception error) {
            try {
                return finishLogin(exchangeCookiesForAccessToken(cookies), persistentCredential,
                        false, COOKIE_CLIENT_ID);
            } catch (Exception fallbackError) {
                try {
                    return finishLoginFromSisu(cookies, persistentCredential);
                } catch (Exception sisuError) {
                    IOException failure = new IOException(cookieFailureReason(
                            error, fallbackError, sisuError), sisuError);
                    failure.addSuppressed(error);
                    failure.addSuppressed(fallbackError);
                    throw failure;
                }
            }
        }
        return finishLogin(tokens.accessToken(), tokens.refreshToken(),
                false, COOKIE_CLIENT_ID);
    }

    private static TokenPair exchangeCookiesForTokenPair(List<ImportedCookie> cookies)
            throws Exception {
        Map<String, String> result = authorizeWithCookies(cookies, "code");
        String code = result.get("code");
        if (code == null || code.isBlank()) {
            throw new IOException("Microsoft returned no authorization code for these cookies.");
        }
        return tokenPair(postForm("https://login.live.com/oauth20_token.srf", Map.of(
                "client_id", COOKIE_CLIENT_ID,
                "redirect_uri", COOKIE_REDIRECT,
                "code", code,
                "grant_type", "authorization_code",
                "scope", COOKIE_SCOPE), "Microsoft cookie token exchange"));
    }

    private static Map<String, String> authorizeWithCookies(List<ImportedCookie> cookies,
                                                             String responseType)
            throws Exception {
        if (cookies.stream().noneMatch(cookie ->
                isAllowedMicrosoftHost(normalizeDomain(cookie.domain())))) {
            throw new IOException("The file contains no usable Microsoft cookies.");
        }

        URI current = URI.create("https://login.live.com/oauth20_authorize.srf"
                + "?client_id=" + encode(COOKIE_CLIENT_ID)
                + "&response_type=" + encode(responseType)
                + "&scope=" + encode(COOKIE_SCOPE)
                + "&redirect_uri=" + encode(COOKIE_REDIRECT)
                + "&prompt=none&display=touch&locale=en");
        HttpClient cookieClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        for (int redirects = 0; redirects < 12; redirects++) {
            String cookieHeader = cookieHeader(cookies, current);
            if (cookieHeader.isBlank()) {
                throw new IOException("The file has no cookies for " + current.getHost() + ".");
            }
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", COOKIE_AUTH_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.8")
                    .header("Cookie", cookieHeader)
                    .GET().build();
            HttpResponse<String> response = cookieClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 300 || response.statusCode() >= 400) {
                throw new IOException("Microsoft did not accept the cookie session for Minecraft OAuth "
                        + "(HTTP " + response.statusCode() + ").");
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("Microsoft returned an invalid redirect."));
            URI next = current.resolve(location);
            if (COOKIE_REDIRECT.equals(next.getScheme() + "://" + next.getAuthority()
                    + next.getPath())) {
                Map<String, String> values = new HashMap<>(parseQuery(next.getRawQuery()));
                values.putAll(parseQuery(next.getRawFragment()));
                String error = values.get("error");
                if (error != null && !error.isBlank()) {
                    throw new IOException(values.getOrDefault("error_description", error));
                }
                return values;
            }
            if (!"https".equalsIgnoreCase(next.getScheme())
                    || !isAllowedMicrosoftHost(next.getHost())) {
                throw new IOException("Microsoft redirected cookie login to an unsupported host.");
            }
            current = next;
        }
        throw new IOException("Microsoft cookie login used too many redirects.");
    }

    private static LoginResult loginWithRefreshToken(String refreshToken) throws Exception {
        TokenPair tokens;
        try {
            tokens = refreshMicrosoft(refreshToken);
        } catch (Exception error) {
            tokens = tokenPair(postForm(
                    "https://login.live.com/oauth20_token.srf", Map.of(
                            "client_id", COOKIE_CLIENT_ID,
                            "redirect_uri", COOKIE_REDIRECT,
                            "refresh_token", refreshToken,
                            "grant_type", "refresh_token",
                            "scope", COOKIE_SCOPE),
                    "Xbox/Minecraft token refresh"));
            return finishLogin(tokens.accessToken(), tokens.refreshToken(),
                    false, COOKIE_CLIENT_ID);
        }
        return finishLogin(tokens);
    }

    private static String exchangeCookiesForAccessToken(List<ImportedCookie> cookies)
            throws Exception {
        if (cookies.stream().noneMatch(cookie ->
                isAllowedMicrosoftHost(normalizeDomain(cookie.domain())))) {
            throw new IOException("The file contains no usable Microsoft cookies.");
        }

        URI current = URI.create("https://login.live.com/oauth20_authorize.srf"
                + "?client_id=" + encode(COOKIE_CLIENT_ID)
                + "&response_type=" + encode(responseTypeForToken())
                + "&scope=" + encode(COOKIE_SCOPE)
                + "&redirect_uri=" + encode(COOKIE_REDIRECT)
                + "&display=touch&locale=en");
        HttpClient cookieClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        for (int redirects = 0; redirects < 12; redirects++) {
            String cookieHeader = cookieHeader(cookies, current);
            if (cookieHeader.isBlank()) {
                throw new IOException("The file has no cookies for " + current.getHost() + ".");
            }
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", COOKIE_AUTH_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.8")
                    .header("Cookie", cookieHeader)
                    .GET().build();
            HttpResponse<String> response = cookieClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 300 || response.statusCode() >= 400) {
                throw new IOException("The Microsoft cookies are expired or require browser confirmation.");
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("Microsoft returned an invalid redirect."));
            URI next = current.resolve(location);
            if (COOKIE_REDIRECT.equals(next.getScheme() + "://" + next.getAuthority()
                    + next.getPath())) {
                Map<String, String> fragment = parseQuery(next.getRawFragment());
                String accessToken = fragment.get("access_token");
                if (accessToken == null || accessToken.isBlank()) {
                    String detail = fragment.getOrDefault("error_description",
                            fragment.getOrDefault("error", "Cookie login failed."));
                    throw new IOException(detail);
                }
                return accessToken;
            }
            if (!"https".equalsIgnoreCase(next.getScheme())
                    || !isAllowedMicrosoftHost(next.getHost())) {
                throw new IOException("Microsoft redirected cookie login to an unsupported host.");
            }
            current = next;
        }
        throw new IOException("Microsoft cookie login used too many redirects.");
    }

    private static String responseTypeForToken() {
        return "token";
    }

    private static LoginResult finishLoginFromSisu(List<ImportedCookie> cookies,
                                                    String persistentCredential)
            throws Exception {
        XToken xsts = exchangeCookiesForXstsViaSisu(cookies);
        return finishMinecraftLogin(xsts, persistentCredential, COOKIE_CLIENT_ID);
    }

    private static XToken exchangeCookiesForXstsViaSisu(List<ImportedCookie> cookies)
            throws Exception {
        URI current = URI.create(SISU_AUTH_URL);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        for (int hop = 0; hop < 10; hop++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", COOKIE_AUTH_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.8")
                    .GET();
            if (hop > 0) {
                String header = cookieHeaderForSisu(cookies, current);
                if (!header.isBlank()) builder.header("Cookie", header);
            }
            HttpResponse<Void> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 300 || response.statusCode() >= 400) {
                throw new IOException("Xbox SISU rejected the cookie session (HTTP "
                        + response.statusCode() + ").");
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("Xbox SISU returned no redirect."));
            URI next = current.resolve(location.replace(" ", "%20"));
            if (!isAllowedSisuRedirect(next)) {
                throw new IOException("Xbox SISU redirected to an unsafe host.");
            }
            String encoded = parseQuery(next.getRawQuery()).get("accessToken");
            if (encoded == null || encoded.isBlank()) {
                encoded = parseQuery(next.getRawFragment()).get("accessToken");
            }
            if (encoded != null && !encoded.isBlank()) return parseSisuXsts(encoded);
            current = next;
        }
        throw new IOException("Xbox SISU returned no Minecraft authorization token.");
    }

    private static String cookieHeaderForSisu(List<ImportedCookie> cookies, URI target) {
        String host = normalizeDomain(target.getHost());
        if (!(host.equals("login.live.com") || host.endsWith(".login.live.com")
                || host.equals("live.com") || host.endsWith(".live.com")
                || host.equals("xboxlive.com") || host.endsWith(".xboxlive.com"))) return "";
        String requestPath = target.getPath();
        if (requestPath == null || requestPath.isBlank()) requestPath = "/";
        long now = Instant.now().getEpochSecond();
        List<String> values = new ArrayList<>();
        for (ImportedCookie cookie : cookies) {
            String domain = normalizeDomain(cookie.domain());
            String path = cookie.path().isBlank() ? "/" : cookie.path();
            if (!(host.equals(domain) || host.endsWith("." + domain))
                    || !requestPath.startsWith(path)
                    || (cookie.secure() && !"https".equalsIgnoreCase(target.getScheme()))
                    || (cookie.expiresAt() > 0L && cookie.expiresAt() <= now)) continue;
            values.add(cookie.name() + "=" + cookie.value());
        }
        return String.join("; ", values);
    }

    private static boolean isAllowedSisuRedirect(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = normalizeDomain(uri.getHost());
        return host.equals("live.com") || host.endsWith(".live.com")
                || host.equals("microsoftonline.com") || host.endsWith(".microsoftonline.com")
                || host.equals("xboxlive.com") || host.endsWith(".xboxlive.com")
                || host.equals("minecraft.net") || host.endsWith(".minecraft.net");
    }

    private static XToken parseSisuXsts(String encoded) throws IOException {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException standardError) {
            try {
                bytes = Base64.getUrlDecoder().decode(encoded);
            } catch (IllegalArgumentException urlError) {
                throw new IOException("Xbox SISU returned an invalid authorization token.", urlError);
            }
        }
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        int relyingParty = decoded.indexOf("\"rp://api.minecraftservices.com/\",");
        if (relyingParty < 0) {
            throw new IOException("Xbox SISU token does not grant Minecraft access.");
        }
        String minecraftGrant = decoded.substring(relyingParty);
        String userHash = valueAfter(minecraftGrant,
                "{\"DisplayClaims\":{\"xui\":[{\"uhs\":\"");
        String token = valueAfter(minecraftGrant, "\"Token\":\"");
        String xuid = optionalValueAfter(minecraftGrant, "\"xid\":\"");
        if (userHash.isBlank() || token.isBlank()) {
            throw new IOException("Xbox SISU token is missing XSTS credentials.");
        }
        return new XToken(token, userHash, xuid);
    }

    private static String valueAfter(String text, String marker) throws IOException {
        String value = optionalValueAfter(text, marker);
        if (value.isBlank()) throw new IOException("Xbox SISU returned incomplete credentials.");
        return value;
    }

    private static String optionalValueAfter(String text, String marker) {
        int start = text.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = text.indexOf('"', start);
        return end < 0 ? "" : text.substring(start, end);
    }

    private static String cookieHeader(List<ImportedCookie> cookies, URI target) {
        String host = normalizeDomain(target.getHost());
        String requestPath = target.getPath();
        if (requestPath == null || requestPath.isBlank()) requestPath = "/";
        long now = Instant.now().getEpochSecond();
        List<String> values = new ArrayList<>();
        for (ImportedCookie cookie : cookies) {
            String domain = normalizeDomain(cookie.domain());
            String path = cookie.path().isBlank() ? "/" : cookie.path();
            if (!isAllowedMicrosoftHost(domain)
                    || !(host.equals(domain) || host.endsWith("." + domain))
                    || !requestPath.startsWith(path)
                    || (cookie.secure() && !"https".equalsIgnoreCase(target.getScheme()))
                    || (cookie.expiresAt() > 0L && cookie.expiresAt() <= now)) continue;
            values.add(cookie.name() + "=" + cookie.value());
        }
        return String.join("; ", values);
    }

    private static boolean isAllowedMicrosoftHost(String host) {
        if (host == null) return false;
        String normalized = normalizeDomain(host);
        return MICROSOFT_COOKIE_HOSTS.stream().anyMatch(allowed ->
                normalized.equals(allowed) || normalized.endsWith("." + allowed));
    }

    private static String normalizeDomain(String domain) {
        String value = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith(".")) value = value.substring(1);
        return value;
    }

    public static String findRefreshToken(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String fromJson = findRefreshToken(JsonParser.parseString(text));
            if (fromJson != null) return fromJson;
        } catch (Exception ignored) {}
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            int split = trimmed.indexOf('=');
            if (split <= 0) split = trimmed.indexOf(':');
            if (split <= 0) continue;
            String key = trimmed.substring(0, split).trim()
                    .replace("\"", "").replace("'", "");
            if (!key.equalsIgnoreCase("refresh_token")
                    && !key.equalsIgnoreCase("refreshToken")) continue;
            String value = trimmed.substring(split + 1).trim()
                    .replaceFirst("[,;]$", "");
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isBlank()) return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        String prefixedCandidate = null;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.contains("\t")) continue;
            int split = trimmed.indexOf(':');
            if (split <= 0 || split > 64) continue;
            String value = trimmed.substring(split + 1).trim();
            if (value.length() < 64 || value.matches("(?s).*\\s+.*")) continue;
            if (prefixedCandidate != null) return null;
            prefixedCandidate = value;
        }
        if (prefixedCandidate != null) return prefixedCandidate;
        if (!text.contains("\t") && !text.matches("(?s).*\\s+.*") && text.length() >= 64) {
            return text;
        }
        return null;
    }

    private static String findRefreshToken(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : object.entrySet()) {
                if ((entry.getKey().equalsIgnoreCase("refresh_token")
                        || entry.getKey().equalsIgnoreCase("refreshToken"))
                        && entry.getValue().isJsonPrimitive()) {
                    String value = entry.getValue().getAsString().trim();
                    if (!value.isBlank()) return value;
                }
                String nested = findRefreshToken(entry.getValue());
                if (nested != null) return nested;
            }
        } else if (element.isJsonArray()) {
            for (com.google.gson.JsonElement child : element.getAsJsonArray()) {
                String nested = findRefreshToken(child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    public static List<ImportedCookie> parseCookies(String text) {
        List<ImportedCookie> result = new ArrayList<>();
        for (String original : text.split("\\R")) {
            String line = original.trim();
            if (line.isEmpty() || (line.startsWith("#")
                    && !line.startsWith("#HttpOnly_"))) continue;
            if (line.startsWith("#HttpOnly_")) line = line.substring(10);
            String[] fields = line.split("\\t", -1);
            if (fields.length < 7) continue;
            try {
                result.add(new ImportedCookie(fields[0], fields[2], fields[5], fields[6],
                        Boolean.parseBoolean(fields[3]), Long.parseLong(fields[4])));
            } catch (RuntimeException ignored) {}
        }
        if (!result.isEmpty()) return result;
        try {
            collectJsonCookies(JsonParser.parseString(text), result);
        } catch (Exception ignored) {}
        return result;
    }

    private static void collectJsonCookies(com.google.gson.JsonElement element,
                                           List<ImportedCookie> result) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (com.google.gson.JsonElement child : element.getAsJsonArray()) {
                collectJsonCookies(child, result);
            }
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        if (object.has("name") && object.has("value") && object.has("domain")) {
            String domain = object.get("domain").getAsString();
            String path = object.has("path") ? object.get("path").getAsString() : "/";
            boolean secure = object.has("secure") && object.get("secure").getAsBoolean();
            long expires = 0L;
            if (object.has("expirationDate")) expires = object.get("expirationDate").getAsLong();
            else if (object.has("expiration")) expires = object.get("expiration").getAsLong();
            result.add(new ImportedCookie(domain, path, object.get("name").getAsString(),
                    object.get("value").getAsString(), secure, expires));
            return;
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry : object.entrySet()) {
            collectJsonCookies(entry.getValue(), result);
        }
    }

    private static void acceptCallback(HttpExchange exchange, String redirect,
                                       String expectedState, AtomicBoolean callbackHandled,
                                       CompletableFuture<LoginResult> result) throws IOException {
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
            sendPage(exchange, 403, "Request rejected");
            return;
        }
        if (!callbackHandled.compareAndSet(false, true)) {
            sendPage(exchange, 409, "Login callback was already handled");
            return;
        }
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String actualState = query.getOrDefault("state", "");
            if (!MessageDigest.isEqual(expectedState.getBytes(StandardCharsets.UTF_8),
                    actualState.getBytes(StandardCharsets.UTF_8))) {
                sendPage(exchange, 400, "Invalid login state");
                result.completeExceptionally(new IllegalStateException("Invalid Microsoft login state."));
                return;
            }
            String error = query.get("error");
            if (error != null) {
                sendPage(exchange, 400, "Login was cancelled");
                result.completeExceptionally(new IllegalStateException("Microsoft login was cancelled."));
                return;
            }
            String code = query.get("code");
            if (code == null || code.isBlank()) {
                sendPage(exchange, 400, "Login code is missing");
                result.completeExceptionally(new IllegalStateException("Microsoft returned no login code."));
                return;
            }
            sendPage(exchange, 200, "Login complete! You can return to Minecraft.");
            CompletableFuture.supplyAsync(() -> {
                try {
                    return finishLogin(exchangeCode(code, redirect));
                } catch (Exception authError) {
                    throw new RuntimeException(friendlyMessage(authError), authError);
                }
            }).whenComplete((login, authError) -> {
                if (authError == null) result.complete(login);
                else result.completeExceptionally(authError);
            });
        } catch (Throwable error) {
            result.completeExceptionally(error);
            if (error instanceof IOException io) throw io;
            throw new IOException("Unable to process Microsoft login callback.", error);
        }
    }

    private static TokenPair exchangeCode(String code, String redirect) throws Exception {
        JsonObject json = postForm("https://login.live.com/oauth20_token.srf", Map.of(
                "client_id", CLIENT_ID,
                "code", code,
                "grant_type", "authorization_code",
                "redirect_uri", redirect,
                "scope", SCOPE), "Microsoft token exchange");
        return tokenPair(json);
    }

    private static TokenPair refreshMicrosoft(String refreshToken) throws Exception {
        Map<String, String> form = Map.of(
                "client_id", CLIENT_ID, "refresh_token", refreshToken,
                "grant_type", "refresh_token", "scope", SCOPE);
        try {
            return tokenPair(postForm("https://login.live.com/oauth20_token.srf",
                    form, "Microsoft token refresh"));
        } catch (Exception legacyError) {
            return tokenPair(postForm(
                    "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                    form, "Microsoft v2 token refresh"));
        }
    }

    private static LoginResult finishLogin(TokenPair microsoft) throws Exception {
        return finishLogin(microsoft.accessToken(), microsoft.refreshToken(),
                true, CLIENT_ID);
    }

    private static LoginResult finishLogin(String microsoftAccessToken,
                                           String persistentCredential,
                                           boolean modernRpsTicket,
                                           String sessionClientId) throws Exception {
        JsonObject xblProperties = new JsonObject();
        xblProperties.addProperty("AuthMethod", "RPS");
        xblProperties.addProperty("SiteName", "user.auth.xboxlive.com");
        xblProperties.addProperty("RpsTicket",
                (modernRpsTicket ? "d=" : "") + microsoftAccessToken);
        JsonObject xblRequest = new JsonObject();
        xblRequest.add("Properties", xblProperties);
        xblRequest.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblRequest.addProperty("TokenType", "JWT");
        JsonObject xbl = postJson("https://user.auth.xboxlive.com/user/authenticate",
                xblRequest, "Xbox Live authentication");
        XToken xblToken = xToken(xbl);

        JsonObject xstsProperties = new JsonObject();
        xstsProperties.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(xblToken.token());
        xstsProperties.add("UserTokens", userTokens);
        JsonObject xstsRequest = new JsonObject();
        xstsRequest.add("Properties", xstsProperties);
        xstsRequest.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsRequest.addProperty("TokenType", "JWT");
        JsonObject xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize",
                xstsRequest, "Xbox Secure Token authentication");
        XToken xstsToken = xToken(xsts);
        if (!xblToken.userHash().equals(xstsToken.userHash())) {
            throw new IllegalStateException("Xbox authentication returned mismatched accounts.");
        }

        return finishMinecraftLogin(xstsToken, persistentCredential, sessionClientId);
    }

    private static LoginResult finishMinecraftLogin(XToken xstsToken,
                                                     String persistentCredential,
                                                     String sessionClientId) throws Exception {
        JsonObject minecraftRequest = new JsonObject();
        minecraftRequest.addProperty("identityToken",
                "XBL3.0 x=" + xstsToken.userHash() + ";" + xstsToken.token());
        JsonObject minecraft = postJson(
                "https://api.minecraftservices.com/authentication/login_with_xbox",
                minecraftRequest, "Minecraft authentication");
        String minecraftAccess = requiredString(minecraft, "access_token",
                "Minecraft authentication returned no access token.");

        HttpRequest profileRequest = HttpRequest.newBuilder(
                        URI.create("https://api.minecraftservices.com/minecraft/profile"))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + minecraftAccess)
                .GET().build();
        JsonObject profile = sendMinecraftProfile(profileRequest);
        String username = requiredString(profile, "name", "Minecraft profile has no username.");
        String rawId = requiredString(profile, "id", "Minecraft profile has no UUID.");
        UUID uuid = parseUuid(rawId);
        return new LoginResult(username, uuid, minecraftAccess, persistentCredential,
                xstsToken.xuid(), sessionClientId);
    }

    private static JsonObject sendMinecraftProfile(HttpRequest request) throws Exception {
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception invalidJson) {
            throw new IOException("Minecraft Java profile check returned invalid data (HTTP "
                    + response.statusCode() + ").");
        }
        if (response.statusCode() == 404) {
            throw new IOException("Microsoft/Xbox login succeeded, but this account has no "
                    + "Minecraft Java profile (HTTP 404).");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = firstString(json, "errorMessage", "error", "message");
            throw new IOException("Minecraft Java profile check failed (HTTP "
                    + response.statusCode() + ")" + (detail.isBlank() ? "." : ": " + detail));
        }
        return json;
    }

    private static JsonObject postForm(String url, Map<String, String> values,
                                       String stage) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!body.isEmpty()) body.append('&');
            body.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return sendJson(request, stage);
    }

    private static JsonObject postJson(String url, JsonObject body,
                                       String stage) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return sendJson(request, stage);
    }

    private static JsonObject sendJson(HttpRequest request, String stage) throws Exception {
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception invalidJson) {
            throw new IOException(stage + " returned invalid data (HTTP "
                    + response.statusCode() + ").");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = firstString(json, "errorMessage", "error_description", "error",
                    "Message", "message");
            if (json.has("XErr") && !json.get("XErr").isJsonNull()) {
                String xerr = json.get("XErr").getAsString();
                detail = detail.isBlank() ? "Xbox error " + xerr
                        : detail + " (Xbox error " + xerr + ")";
            }
            if (response.statusCode() == 429) {
                detail = "request rate limit reached; wait before retrying";
            }
            throw new IOException(stage + " failed (HTTP " + response.statusCode() + ")"
                    + (detail.isBlank() ? "." : ": " + detail));
        }
        return json;
    }

    private static TokenPair tokenPair(JsonObject json) {
        return new TokenPair(
                requiredString(json, "access_token", "Microsoft returned no access token."),
                requiredString(json, "refresh_token", "Microsoft returned no refresh token."));
    }

    private static XToken xToken(JsonObject json) {
        String token = requiredString(json, "Token", "Xbox returned no token.");
        JsonObject claim = json.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject();
        String hash = requiredString(claim, "uhs", "Xbox returned no user hash.");
        String xuid = claim.has("xid") ? claim.get("xid").getAsString() : "";
        return new XToken(token, hash, xuid);
    }

    private static String requiredString(JsonObject object, String key, String message) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            throw new IllegalStateException(message);
        }
        String value = object.get(key).getAsString();
        if (value.isBlank()) throw new IllegalStateException(message);
        return value;
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                String value = object.get(key).getAsString();
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> values = new HashMap<>();
        if (query == null || query.isBlank()) return values;
        for (String part : query.split("&")) {
            int split = part.indexOf('=');
            String key = split < 0 ? part : part.substring(0, split);
            String value = split < 0 ? "" : part.substring(split + 1);
            values.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private static void sendPage(HttpExchange exchange, int status, String message)
            throws IOException {
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Alts Mod</title>"
                + "<style>body{margin:0;background:#0c0c0c;color:#fff;font:16px sans-serif;"
                + "display:grid;place-items:center;height:100vh}div{padding:28px 36px;"
                + "border-radius:18px;background:#181818;box-shadow:0 8px 24px rgba(0,0,0,0.5)}</style></head><body><div>"
                + message + "</div></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String randomState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static UUID parseUuid(String raw) {
        String value = raw.replace("-", "");
        if (value.length() != 32) throw new IllegalArgumentException("Invalid Minecraft UUID.");
        return UUID.fromString(value.substring(0, 8) + '-' + value.substring(8, 12) + '-'
                + value.substring(12, 16) + '-' + value.substring(16, 20) + '-'
                + value.substring(20));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String cookieFailureReason(Throwable codeFlow, Throwable implicitFlow,
                                              Throwable sisuFlow) {
        String code = rootMessage(codeFlow);
        String implicit = rootMessage(implicitFlow);
        String sisu = rootMessage(sisuFlow);
        if (code.toLowerCase(Locale.ROOT).contains("login_required")) {
            return "Microsoft rejected the cookie session: interactive sign-in is required.";
        }
        if (code.toLowerCase(Locale.ROOT).contains("consent_required")) {
            return "Microsoft rejected the cookie session: Minecraft OAuth consent is required.";
        }
        return "Cookie authentication failed: " + sisu;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null
                && current.getCause() != current) current = current.getCause();
        String message = current == null ? "unknown error" : current.getMessage();
        return message == null || message.isBlank() ? "unknown error" : message;
    }

    public static String friendlyMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current == null ? null : current.getMessage();
        return message == null || message.isBlank() ? rootMessage(error) : message;
    }

    public record TokenPair(String accessToken, String refreshToken) {}
    public record XToken(String token, String userHash, String xuid) {}
    public record ImportedCookie(String domain, String path, String name, String value,
                                  boolean secure, long expiresAt) {}

    public record LoginResult(String username, UUID uuid, String minecraftAccessToken,
                              String refreshToken, String xuid, String clientId) {}
}
