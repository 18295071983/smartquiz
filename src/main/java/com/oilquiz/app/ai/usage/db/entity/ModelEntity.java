package com.oilquiz.app.ai.usage.db.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * AI 模型实体
 */
@Entity(
        tableName = "models",
        indices = {
                @Index(value = "providerId"),
                @Index(value = "modelId", unique = true)
        }
)
public class ModelEntity {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    private int id;

    @ColumnInfo(name = "providerId")
    private String providerId;

    @ColumnInfo(name = "modelId")
    private String modelId;

    @ColumnInfo(name = "name")
    private String name;

    @ColumnInfo(name = "displayName")
    private String displayName;

    @ColumnInfo(name = "family")
    private String family;

    @ColumnInfo(name = "capabilities")
    private String capabilities;

    @ColumnInfo(name = "maxInput")
    private int maxInput;

    @ColumnInfo(name = "maxOutput")
    private int maxOutput;

    @ColumnInfo(name = "inputPriceType")
    private String inputPriceType;

    @ColumnInfo(name = "outputPriceType")
    private String outputPriceType;

    @ColumnInfo(name = "description")
    private String description;

    public ModelEntity() {}

    public ModelEntity(String providerId, String modelId, String name, String displayName) {
        this.providerId = providerId;
        this.modelId = modelId;
        this.name = name;
        this.displayName = displayName;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getFamily() { return family; }
    public void setFamily(String family) { this.family = family; }

    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String capabilities) { this.capabilities = capabilities; }

    public int getMaxInput() { return maxInput; }
    public void setMaxInput(int maxInput) { this.maxInput = maxInput; }

    public int getMaxOutput() { return maxOutput; }
    public void setMaxOutput(int maxOutput) { this.maxOutput = maxOutput; }

    public String getInputPriceType() { return inputPriceType; }
    public void setInputPriceType(String inputPriceType) { this.inputPriceType = inputPriceType; }

    public String getOutputPriceType() { return outputPriceType; }
    public void setOutputPriceType(String outputPriceType) { this.outputPriceType = outputPriceType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
