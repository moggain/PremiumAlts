package com.altsmod.gui;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Native Windows OpenFileDialog using PowerShell STA, bypassing Java headless restrictions. */
public final class NativeTextFilePicker {
    private NativeTextFilePicker() {}

    public static CompletableFuture<Optional<Path>> chooseCredentialFile() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path preferred = Path.of(System.getProperty("user.home"), "OneDrive", "Desktop", "Alts", "cookies");
                if (!Files.exists(preferred)) {
                    preferred = Path.of(System.getProperty("user.home"), "Desktop", "Alts", "cookies");
                }
                if (!Files.exists(preferred)) {
                    preferred = Path.of(System.getProperty("user.home"), "OneDrive", "Desktop", "Alts");
                }
                if (!Files.exists(preferred)) {
                    preferred = Path.of(System.getProperty("user.home"), "Desktop", "Alts");
                }
                String initDir = Files.exists(preferred) ? preferred.toAbsolutePath().toString().replace("'", "''") : "";

                String script = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;"
                        + "Add-Type -AssemblyName System.Windows.Forms | Out-Null;"
                        + "$f=New-Object System.Windows.Forms.OpenFileDialog;"
                        + "$f.Title='Выберите файл с куки или токеном';"
                        + "$f.Filter='Файлы аккаунтов (*.txt;*.json)|*.txt;*.json|Все файлы (*.*)|*.*';"
                        + (initDir.isEmpty() ? "" : "$f.InitialDirectory='" + initDir + "';")
                        + "$f.Multiselect=$false;"
                        + "if($f.ShowDialog() -eq 'OK'){[Console]::Write($f.FileName)}";

                Process process = new ProcessBuilder("powershell.exe", "-NoProfile",
                        "-ExecutionPolicy", "Bypass", "-STA", "-Command", script)
                        .redirectErrorStream(true).start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) output.append(line);
                }
                if (process.waitFor() != 0) {
                    throw new IllegalStateException("PowerShell file dialog process returned error.");
                }
                String selected = output.toString().trim();
                if (selected.isEmpty()) return Optional.empty();
                File file = new File(selected);
                return file.isFile() ? Optional.of(file.toPath()) : Optional.empty();
            } catch (Exception error) {
                throw new RuntimeException("Не удалось открыть окно выбора файла: " + error.getMessage(), error);
            }
        });
    }
}
