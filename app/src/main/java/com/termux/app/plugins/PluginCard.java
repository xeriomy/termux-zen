package com.termux.app.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable presentation model for a plugin card shown by {@link PluginManagerActivity}. */
public final class PluginCard {

    public enum Kind {
        INSTALLED,
        BUNDLED,
        REPOSITORY
    }

    public enum Status {
        ENABLED,
        DISABLED,
        NEEDS_ATTENTION,
        OVERRIDDEN,
        INERT,
        INSTALLED,
        AVAILABLE,
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        INCOMPATIBLE
    }

    public enum PrimaryAction {
        NONE,
        ENABLE,
        DISABLE,
        INSTALL,
        UPDATE
    }

    public final Kind kind;
    public final String id;
    public final String name;
    public final String version;
    public final String type;
    public final String description;
    public final String metadata;
    public final String statusDetail;
    public final List<String> permissions;
    public final List<String> dependencies;
    public final Status status;
    public final PrimaryAction primaryAction;
    public final boolean detailsAvailable;
    public final int apiVersion;

    public PluginCard(Kind kind, String id, String name, String version, String type,
                      String description, String metadata, String statusDetail,
                      List<String> permissions, List<String> dependencies, Status status,
                      PrimaryAction primaryAction, boolean detailsAvailable, int apiVersion) {
        this.kind = kind;
        this.id = id == null ? "" : id;
        this.name = name == null ? this.id : name;
        this.version = version == null ? "" : version;
        this.type = type == null ? "" : type;
        this.description = description == null ? "" : description;
        this.metadata = metadata == null ? "" : metadata;
        this.statusDetail = statusDetail == null ? "" : statusDetail;
        this.permissions = immutableCopy(permissions);
        this.dependencies = immutableCopy(dependencies);
        this.status = status;
        this.primaryAction = primaryAction;
        this.detailsAvailable = detailsAvailable;
        this.apiVersion = apiVersion;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PluginCard)) return false;
        PluginCard card = (PluginCard) other;
        return kind == card.kind && id.equals(card.id) && name.equals(card.name)
            && version.equals(card.version) && type.equals(card.type)
            && description.equals(card.description) && metadata.equals(card.metadata)
            && statusDetail.equals(card.statusDetail) && permissions.equals(card.permissions)
            && dependencies.equals(card.dependencies) && status == card.status
            && primaryAction == card.primaryAction && detailsAvailable == card.detailsAvailable
            && apiVersion == card.apiVersion;
    }

    @Override
    public int hashCode() {
        int result = kind.hashCode();
        result = 31 * result + id.hashCode();
        result = 31 * result + name.hashCode();
        result = 31 * result + version.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + description.hashCode();
        result = 31 * result + metadata.hashCode();
        result = 31 * result + statusDetail.hashCode();
        result = 31 * result + permissions.hashCode();
        result = 31 * result + dependencies.hashCode();
        result = 31 * result + status.hashCode();
        result = 31 * result + primaryAction.hashCode();
        result = 31 * result + (detailsAvailable ? 1 : 0);
        result = 31 * result + apiVersion;
        return result;
    }

    public boolean matches(String query) {
        if (query == null || query.trim().isEmpty()) return true;
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        return contains(name, needle) || contains(id, needle) || contains(version, needle)
            || contains(type, needle) || contains(description, needle) || contains(metadata, needle)
            || contains(statusDetail, needle)
            || contains(statusSearchText(), needle)
            || containsList(permissions, needle) || containsList(dependencies, needle);
    }

    /** Human-readable status words used by both the card and search. */
    public String statusSearchText() {
        return status == null ? "" : status.name().toLowerCase(java.util.Locale.ROOT)
            .replace('_', ' ');
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    private static boolean containsList(List<String> values, String needle) {
        for (String value : values) if (contains(value, needle)) return true;
        return false;
    }

    private static List<String> immutableCopy(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
