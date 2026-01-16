package org.geysermc.geyser.network.legacy;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;

public final class Bedrock_v448 implements BedrockCodec {

    public static final Bedrock_v448 CODEC = new Bedrock_v448();

    private Bedrock_v448() {}

    @Override
    public int getProtocolVersion() {
        return 448;
    }

    @Override
    public String getMinecraftVersion() {
        return "1.18.0";
    }

    @Override
    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public void registerPackets(BedrockCodecHelper helper) {
        // Legacy packets will be handled by CodecProcessor
    }
}
