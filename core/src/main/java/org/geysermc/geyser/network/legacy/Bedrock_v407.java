package org.geysermc.geyser.network.legacy;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;

public final class Bedrock_v407 implements BedrockCodec {

    public static final Bedrock_v407 CODEC = new Bedrock_v407();

    private Bedrock_v407() {}

    @Override
    public int getProtocolVersion() {
        return 407;
    }

    @Override
    public String getMinecraftVersion() {
        return "1.16.201";
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
