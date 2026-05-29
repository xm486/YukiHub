package com.yuki.yukihub.model;

public enum EngineType {
    AUTO("Auto"),
    KIRIKIRI("Kirikiri"),
    ONS("ONScripter"),
    TYRANO("Tyrano"),
    ARTEMIS("Artemis"),
    WINLATOR("Winlator"),
    GAMEHUB("GameHub"),
    UNKNOWN("Unknown");

    private final String displayName;

    EngineType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static EngineType fromString(String value) {
        if (value == null) return UNKNOWN;
        for (EngineType type : values()) {
            if (type.name().equalsIgnoreCase(value) || type.displayName.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
