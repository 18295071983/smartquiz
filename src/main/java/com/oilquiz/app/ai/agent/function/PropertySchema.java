package com.oilquiz.app.ai.agent.function;

import com.google.gson.annotations.SerializedName;

public class PropertySchema {
    @SerializedName("type")
    private String type;

    @SerializedName("description")
    private String description;

    @SerializedName("enum")
    private String[] enumValues;

    public PropertySchema() {}

    public PropertySchema(String type, String description) {
        this.type = type;
        this.description = description;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String[] getEnumValues() { return enumValues; }
    public void setEnumValues(String[] enumValues) { this.enumValues = enumValues; }
}