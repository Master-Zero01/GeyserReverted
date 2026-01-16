/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC + Custom Legacy Fork
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.network;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;

// Modern codecs
import org.cloudburstmc.protocol.bedrock.codec.v844.Bedrock_v844;
import org.cloudburstmc.protocol.bedrock.codec.v859.Bedrock_v859;
import org.cloudburstmc.protocol.bedrock.codec.v860.Bedrock_v860;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;

// Legacy codecs (must exist in your forked protocol library)
import org.cloudburstmc.protocol.bedrock.codec.v407.Bedrock_v407; // 1.16.201
import org.cloudburstmc.protocol.bedrock.codec.v431.Bedrock_v431; // 1.17.x
import org.cloudburstmc.protocol.bedrock.codec.v448.Bedrock_v448; // 1.18.x

import org.geysermc.geyser.api.util.MinecraftVersion;
import org.geysermc.geyser.impl.MinecraftVersionImpl;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.PacketCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class GameProtocol {

    // Bedrock codecs
    private static final List<BedrockCodec> SUPPORTED_BEDROCK_CODECS = new ArrayList<>();
    public static final IntList SUPPORTED_BEDROCK_PROTOCOLS = new IntArrayList();
    public static final List<MinecraftVersion> SUPPORTED_BEDROCK_VERSIONS = new ArrayList<>();

    // Latest defaults
    public static final int DEFAULT_BEDROCK_PROTOCOL;
    public static final String DEFAULT_BEDROCK_VERSION;

    // Java codec (unchanged)
    private static final PacketCodec DEFAULT_JAVA_CODEC = MinecraftCodec.CODEC;

    static {
        // -----------------------
        // LEGACY BEDROCK SUPPORT
        // -----------------------
        register(Bedrock_v407.CODEC, "1.16.201"); // v407 protocol
        register(Bedrock_v431.CODEC, "1.17.0", "1.17.10"); // v431 protocol
        register(Bedrock_v448.CODEC, "1.18.0", "1.18.10"); // v448 protocol

        // -----------------------
        // MODERN BEDROCK SUPPORT
        // -----------------------
        register(Bedrock_v844.CODEC, "1.21.111", "1.21.112", "1.21.113", "1.21.114");
        register(Bedrock_v859.CODEC, "1.21.120", "1.21.121", "1.21.122", "1.21.123");
        register(Bedrock_v860.CODEC);
        register(Bedrock_v898.CODEC);

        // set defaults
        MinecraftVersion latestBedrock = SUPPORTED_BEDROCK_VERSIONS.get(SUPPORTED_BEDROCK_VERSIONS.size() - 1);
        DEFAULT_BEDROCK_VERSION = latestBedrock.versionString();
        DEFAULT_BEDROCK_PROTOCOL = latestBedrock.protocolVersion();
    }

    private static void register(BedrockCodec codec, String... minecraftVersions) {
        codec = CodecProcessor.processCodec(codec);

        SUPPORTED_BEDROCK_CODECS.add(codec);
        SUPPORTED_BEDROCK_PROTOCOLS.add(codec.getProtocolVersion());

        for (String version : minecraftVersions) {
            SUPPORTED_BEDROCK_VERSIONS.add(new MinecraftVersionImpl(version, codec.getProtocolVersion()));
        }
    }

    private static void register(BedrockCodec codec) {
        register(codec, codec.getMinecraftVersion());
    }

    public static @Nullable BedrockCodec getBedrockCodec(int protocolVersion) {
        for (BedrockCodec packetCodec : SUPPORTED_BEDROCK_CODECS) {
            if (packetCodec.getProtocolVersion() == protocolVersion) {
                return packetCodec;
            }
        }
        return null;
    }

    // Convenience helpers
    public static boolean is1_21_110orHigher(GeyserSession session) {
        return session.protocolVersion() >= Bedrock_v844.CODEC.getProtocolVersion();
    }

    public static boolean isLegacy1_16(GeyserSession session) {
        return session.protocolVersion() <= Bedrock_v407.CODEC.getProtocolVersion();
    }

    // Java edition helpers (unchanged)
    public static List<String> getJavaVersions() {
        return List.of(DEFAULT_JAVA_CODEC.getMinecraftVersion());
    }

    public static int getJavaProtocolVersion() {
        return DEFAULT_JAVA_CODEC.getProtocolVersion();
    }

    public static String getJavaMinecraftVersion() {
        return DEFAULT_JAVA_CODEC.getMinecraftVersion();
    }

    public static String getAllSupportedBedrockVersions() {
        return SUPPORTED_BEDROCK_VERSIONS.stream()
            .map(MinecraftVersion::versionString)
            .collect(Collectors.joining(", "));
    }

    public static String getAllSupportedJavaVersions() {
        return String.join(", ", getJavaVersions());
    }

    private GameProtocol() {
        // Prevent instantiation
    }
}
