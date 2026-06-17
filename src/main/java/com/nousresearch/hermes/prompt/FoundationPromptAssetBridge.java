package com.nousresearch.hermes.prompt;

/** Small bridge used by foundation validators that only need prompt ref existence checks. */
public interface FoundationPromptAssetBridge {
    boolean exists(String workspaceId, String assetId, Integer version);
}
