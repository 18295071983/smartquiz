package com.oilquiz.app.ai.gpu;

import java.util.HashMap;
import java.util.Map;

public class GpuDatabase {
    private static volatile GpuDatabase INSTANCE;

    private final Map<String, GpuProfile> gpuProfiles;

    private GpuDatabase() {
        gpuProfiles = new HashMap<>();
        initializeProfiles();
    }

    public static GpuDatabase getInstance() {
        if (INSTANCE == null) {
            synchronized (GpuDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = new GpuDatabase();
                }
            }
        }
        return INSTANCE;
    }

    private void initializeProfiles() {
        gpuProfiles.put("Adreno 740", new GpuProfile(
            "Adreno 740", GpuSeries.ADRENO_700, "A7xx", "1.3", 12, 1.5f, 35, 4,
            true, true, true,
            new GpuConfig(28, 512, true, true, true, true, true, true, 1024, 65536, false, false)
        ));

        gpuProfiles.put("Adreno 730", new GpuProfile(
            "Adreno 730", GpuSeries.ADRENO_700, "A7xx", "1.2", 10, 1.2f, 30, 3,
            true, true, true,
            new GpuConfig(22, 256, true, true, true, true, true, true, 1024, 65536, false, true)
        ));

        gpuProfiles.put("Adreno 720", new GpuProfile(
            "Adreno 720", GpuSeries.ADRENO_700, "A7xx", "1.2", 8, 1.0f, 25, 2,
            true, true, true,
            new GpuConfig(20, 256, true, true, true, true, true, true, 1024, 65536, false, false)
        ));

        gpuProfiles.put("Adreno 710", new GpuProfile(
            "Adreno 710", GpuSeries.ADRENO_700, "A7xx", "1.2", 6, 0.8f, 22, 2,
            true, false, true,
            new GpuConfig(18, 256, true, true, true, false, true, true, 1024, 65536, false, false)
        ));

        gpuProfiles.put("Adreno 650", new GpuProfile(
            "Adreno 650", GpuSeries.ADRENO_600, "A6xx", "1.1", 8, 0.8f, 20, 2,
            true, false, true,
            new GpuConfig(15, 128, false, true, true, false, true, true, 1024, 65536, true, false)
        ));

        gpuProfiles.put("Adreno 640", new GpuProfile(
            "Adreno 640", GpuSeries.ADRENO_600, "A6xx", "1.1", 6, 0.6f, 18, 2,
            true, false, true,
            new GpuConfig(12, 128, false, true, true, false, true, true, 1024, 65536, true, false)
        ));

        gpuProfiles.put("Adreno 630", new GpuProfile(
            "Adreno 630", GpuSeries.ADRENO_600, "A6xx", "1.1", 6, 0.5f, 16, 1,
            true, false, true,
            new GpuConfig(10, 64, false, true, true, false, true, true, 1024, 65536, true, false)
        ));

        gpuProfiles.put("Adreno 620", new GpuProfile(
            "Adreno 620", GpuSeries.ADRENO_600, "A6xx", "1.1", 4, 0.4f, 14, 1,
            true, false, true,
            new GpuConfig(8, 64, false, true, true, false, true, true, 1024, 65536, true, false)
        ));

        gpuProfiles.put("Adreno 619", new GpuProfile(
            "Adreno 619", GpuSeries.ADRENO_600, "A6xx", "1.1", 4, 0.4f, 12, 1,
            true, false, false,
            new GpuConfig(5, 64, false, true, false, false, false, false, 1024, 65536, false, false)
        ));

        gpuProfiles.put("Adreno 540", new GpuProfile(
            "Adreno 540", GpuSeries.ADRENO_500, "A5xx", "1.0", 6, 0.4f, 12, 1,
            true, false, false,
            new GpuConfig(0, 64, false, false, false, false, false, false, 1024, 65536, false, false)
        ));

        gpuProfiles.put("Immortalis-G715", new GpuProfile(
            "Immortalis-G715", GpuSeries.IMMORTALIS_G700, "Valhall 5th Gen", "1.3", 10, 1.8f, 38, 4,
            true, true, true,
            new GpuConfig(30, 512, true, true, true, true, true, true, 1024, 65536, false, false)
        ));

        gpuProfiles.put("Mali-G710", new GpuProfile(
            "Mali-G710", GpuSeries.MALI_G700, "Valhall", "1.2", 8, 1.4f, 30, 3,
            true, false, true,
            new GpuConfig(22, 256, true, true, true, false, true, true, 1024, 65536, false, false)
        ));

        gpuProfiles.put("Mali-G610", new GpuProfile(
            "Mali-G610", GpuSeries.MALI_G600, "Valhall", "1.2", 6, 1.0f, 25, 2,
            true, false, true,
            new GpuConfig(18, 256, true, true, true, false, true, true, 1024, 65536, false, false)
        ));

        gpuProfiles.put("Mali-G510", new GpuProfile(
            "Mali-G510", GpuSeries.MALI_G500, "Valhall", "1.1", 4, 0.6f, 20, 1,
            true, false, false,
            new GpuConfig(12, 128, false, true, false, false, true, true, 1024, 65536, false, false)
        ));
    }

    public GpuProfile getProfile(String gpuName) {
        return gpuProfiles.getOrDefault(gpuName, null);
    }

    public GpuProfile getClosestProfile(String gpuName) {
        for (Map.Entry<String, GpuProfile> entry : gpuProfiles.entrySet()) {
            if (gpuName.contains(entry.getKey().replace(" ", ""))) {
                return entry.getValue();
            }
        }
        return null;
    }

    public GpuTier getTier(String gpuName) {
        GpuProfile profile = getClosestProfile(gpuName);
        if (profile == null) {
            return GpuTier.UNKNOWN;
        }
        if (profile.series == GpuSeries.ADRENO_700 || profile.series == GpuSeries.IMMORTALIS_G700) {
            return GpuTier.HIGH;
        } else if (profile.series == GpuSeries.ADRENO_600 || profile.series == GpuSeries.MALI_G700) {
            return GpuTier.MID;
        }
        return GpuTier.LOW;
    }

    public GpuConfig getConfigForModel(GpuProfile profile, long modelSizeMB) {
        if (profile == null) {
            return GpuConfig.conservative();
        }

        GpuConfig baseConfig = profile.recommendedConfig;
        int gpuLayers = baseConfig.gpuLayers;

        if (modelSizeMB > 4096) {
            gpuLayers = Math.max(0, gpuLayers - 8);
        } else if (modelSizeMB > 2048) {
            gpuLayers = Math.max(0, gpuLayers - 4);
        }

        return new GpuConfig(
            gpuLayers,
            baseConfig.batchSize,
            baseConfig.kvCacheOnGpu,
            baseConfig.useFp16,
            baseConfig.useVulkan,
            baseConfig.enableTensorCores,
            baseConfig.enableMemoryPrefetch,
            baseConfig.enableAsyncCompute,
            baseConfig.maxComputeWorkGroupSize,
            baseConfig.preferredSharedMemorySize,
            baseConfig.conservativeSync,
            baseConfig.tileBasedRendering
        );
    }

    public int getRecommendedGpuLayers(GpuProfile profile, long modelSizeMB) {
        if (profile == null) {
            return 0;
        }

        int baseLayers = profile.recommendedConfig.gpuLayers;

        if (modelSizeMB > 4096) {
            return Math.max(0, baseLayers - 8);
        } else if (modelSizeMB > 2048) {
            return Math.max(0, baseLayers - 4);
        }

        return baseLayers;
    }
}