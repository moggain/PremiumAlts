package com.altsmod.gui;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public class AccountEntry {
    private final String name;
    private final UUID uuid;
    private final Path path;
    private final String credential;

    public AccountEntry(String name, UUID uuid, Path path, String credential) {
        this.name = name;
        this.uuid = uuid;
        this.path = path;
        this.credential = credential;
    }

    public String name() { return name; }
    public UUID uuid() { return uuid; }
    public Path path() { return path; }
    public String credential() { return credential; }
    public String displayName() { return name; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AccountEntry that = (AccountEntry) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
