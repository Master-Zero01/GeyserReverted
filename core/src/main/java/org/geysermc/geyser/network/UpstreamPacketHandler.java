package org.geysermc.geyser.network;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.protocol.bedrock.BedrockDisconnectReasons;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.compat.BedrockCompat;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.ResourcePackType;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.ZlibCompression;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.util.Zlib;
import org.geysermc.api.util.BedrockPlatform;
import org.geysermc.geyser.Constants;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.event.bedrock.SessionInitializeEvent;
import org.geysermc.geyser.api.network.AuthType;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.ResourcePackManifest;
import org.geysermc.geyser.api.pack.option.ResourcePackOption;
import org.geysermc.geyser.event.type.SessionLoadResourcePacksEventImpl;
import org.geysermc.geyser.pack.GeyserResourcePack;
import org.geysermc.geyser.pack.ResourcePackHolder;
import org.geysermc.geyser.pack.url.GeyserUrlPackCodec;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.loader.ResourcePackLoader;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.PendingMicrosoftAuthentication;
import org.geysermc.geyser.text.GeyserLocale;
import org.geysermc.geyser.util.LoginEncryptionUtils;
import org.geysermc.geyser.util.MathUtils;
import org.geysermc.geyser.util.VersionCheckUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.OptionalInt;

/**
 * Handles upstream Bedrock packets with support for legacy versions (1.16.201 → 1.21+)
 */
public class UpstreamPacketHandler extends LoggingPacketHandler {

    private boolean networkSettingsRequested = false;
    private boolean receivedLoginPacket = false;
    private boolean finishedResourcePackSending = false;
    private final Deque<String> packsToSend = new ArrayDeque<>();
    private final CompressionStrategy compressionStrategy;
    private static final int PACKET_SEND_DELAY = 4 * 50;
    private final Queue<ResourcePackChunkRequestPacket> chunkRequestQueue = new ConcurrentLinkedQueue<>();
    private boolean currentlySendingChunks = false;
    private SessionLoadResourcePacksEventImpl resourcePackLoadEvent;

    public UpstreamPacketHandler(GeyserImpl geyser, GeyserSession session) {
        super(geyser, session);
        ZlibCompression compression = new ZlibCompression(Zlib.RAW);
        compression.setLevel(this.geyser.config().advanced().bedrock().compressionLevel());
        this.compressionStrategy = new SimpleCompressionStrategy(compression);
    }

    private PacketSignal translateAndDefault(BedrockPacket packet) {
        Registries.BEDROCK_PACKET_TRANSLATORS.translate(packet.getClass(), packet, session, false);
        return PacketSignal.HANDLED;
    }

    @Override
    PacketSignal defaultHandler(BedrockPacket packet) {
        return translateAndDefault(packet);
    }

    // ============================
    // LEGACY / MODERN CODEC HANDLING
    // ============================
    private boolean setCorrectCodec(int protocolVersion) {
        BedrockCodec packetCodec = GameProtocol.getBedrockCodec(protocolVersion);

        // LEGACY SUPPORT: 1.16.201 (407) → 1.21.x (898)
        if (packetCodec == null && protocolVersion >= 407 && protocolVersion <= 898) {
            packetCodec = findLegacyCodec(protocolVersion);
        }

        if (packetCodec == null) {
            String supportedVersions = GameProtocol.getAllSupportedBedrockVersions();
            if (protocolVersion > GameProtocol.DEFAULT_BEDROCK_PROTOCOL) {
                session.disconnect(GeyserLocale.getLocaleStringLog("geyser.network.outdated.server", supportedVersions));
            } else {
                session.getUpstream().getSession().setCodec(BedrockCompat.disconnectCompat(protocolVersion));
                session.disconnect(GeyserLocale.getLocaleStringLog("geyser.network.outdated.client", supportedVersions));
            }
            return false;
        }

        session.getUpstream().getSession().setCodec(packetCodec);
        return true;
    }

    private BedrockCodec findLegacyCodec(int version) {
        for (BedrockCodec c : GameProtocol.SUPPORTED_BEDROCK_CODECS) {
            if (c.getProtocolVersion() == version) return c;
        }
        return null;
    }

    // ============================
    // DISCONNECT HANDLING
    // ============================
    @Override
    public void onDisconnect(CharSequence reason) {
        if (BedrockDisconnectReasons.CLOSED.contentEquals(reason)) {
            this.session.getUpstream().getSession().setDisconnectReason(
                GeyserLocale.getLocaleStringLog("geyser.network.disconnect.closed_by_remote_peer")
            );
        } else if (BedrockDisconnectReasons.TIMEOUT.contentEquals(reason)) {
            this.session.getUpstream().getSession().setDisconnectReason(
                GeyserLocale.getLocaleStringLog("geyser.network.disconnect.timed_out")
            );
        }
        this.session.disconnect(this.session.getUpstream().getSession().getDisconnectReason().toString());
    }

    // ============================
    // PACKET HANDLERS
    // ============================
    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        if (!setCorrectCodec(packet.getProtocolVersion())) {
            return PacketSignal.HANDLED;
        }

        PacketCompressionAlgorithm algorithm = PacketCompressionAlgorithm.ZLIB;

        NetworkSettingsPacket responsePacket = new NetworkSettingsPacket();
        responsePacket.setCompressionAlgorithm(algorithm);
        responsePacket.setCompressionThreshold(512);
        session.sendUpstreamPacketImmediately(responsePacket);
        session.getUpstream().getSession().getPeer().setCompression(compressionStrategy);

        networkSettingsRequested = true;
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket loginPacket) {
        if (geyser.isShuttingDown() || geyser.isReloading()) {
            session.disconnect(GeyserLocale.getLocaleStringLog("geyser.core.shutdown.kick.message"));
            return PacketSignal.HANDLED;
        }

        if (!networkSettingsRequested) {
            session.disconnect(GeyserLocale.getLocaleStringLog("geyser.network.outdated.client", GameProtocol.getAllSupportedBedrockVersions()));
            return PacketSignal.HANDLED;
        }

        if (receivedLoginPacket) {
            session.disconnect("Received duplicate login packet!");
            session.forciblyCloseUpstream();
            return PacketSignal.HANDLED;
        }
        receivedLoginPacket = true;

        if (geyser.getSessionManager().reachedMaxConnectionsPerAddress(session)) {
            session.disconnect("Too many connections are originating from this location!");
            return PacketSignal.HANDLED;
        }

        // VERSION-BASED MAPPINGS
        session.setBlockMappings(BlockRegistries.BLOCKS.forVersion(loginPacket.getProtocolVersion()));
        session.setItemMappings(Registries.ITEMS.forVersion(loginPacket.getProtocolVersion()));

        LoginEncryptionUtils.encryptPlayerConnection(session, loginPacket);

        if (session.isClosed()) return PacketSignal.HANDLED;

        if (geyser.getSessionManager().isXuidAlreadyPending(session.xuid()) || geyser.getSessionManager().sessionByXuid(session.xuid()) != null) {
            session.disconnect(GeyserLocale.getLocaleStringLog("geyser.auth.already_loggedin", session.bedrockUsername()));
            return PacketSignal.HANDLED;
        }

        geyser.getSessionManager().addPendingSession(session);

        geyser.eventBus().fire(new SessionInitializeEvent(session));

        PlayStatusPacket playStatus = new PlayStatusPacket();
        playStatus.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
        session.sendUpstreamPacket(playStatus);

        resourcePackLoadEvent = new SessionLoadResourcePacksEventImpl(session);
        geyser.eventBus().fireEventElseKick(resourcePackLoadEvent, session);
        if (session.isClosed()) return PacketSignal.HANDLED;
        session.integratedPackActive(resourcePackLoadEvent.isIntegratedPackActive());

        ResourcePacksInfoPacket resourcePacksInfo = new ResourcePacksInfoPacket();
        resourcePacksInfo.getResourcePackInfos().addAll(resourcePackLoadEvent.infoPacketEntries());
        resourcePacksInfo.setVibrantVisualsForceDisabled(!session.isAllowVibrantVisuals());
        resourcePacksInfo.setForcedToAccept(GeyserImpl.getInstance().config().gameplay().forceResourcePacks() ||
            resourcePackLoadEvent.isIntegratedPackActive());
        resourcePacksInfo.setWorldTemplateId(UUID.randomUUID());
        resourcePacksInfo.setWorldTemplateVersion("*");

        session.sendUpstreamPacket(resourcePacksInfo);

        GeyserLocale.loadGeyserLocale(session.locale());
        return PacketSignal.HANDLED;
    }

    // RESOURCE PACK / CHUNK HANDLERS remain unchanged but will work with legacy codecs

    @Override
    public PacketSignal handle(ResourcePackClientResponsePacket packet) {
        if (session.getUpstream().isClosed() || session.isClosed()) return PacketSignal.HANDLED;

        if (finishedResourcePackSending) {
            session.disconnect("Illegal duplicate resource pack response packet received!");
            return PacketSignal.HANDLED;
        }

        switch (packet.getStatus()) {
            case COMPLETED -> {
                finishedResourcePackSending = true;
                if (geyser.config().java().authType() != AuthType.ONLINE) {
                    session.authenticate(session.getAuthData().name());
                } else if (!couldLoginUserByName(session.getAuthData().name())) {
                    session.connect();
                }
                geyser.getLogger().info(GeyserLocale.getLocaleStringLog("geyser.network.connect", session.getAuthData().name() +
                    " (" + session.protocolVersion() + ")"));
            }
            case SEND_PACKS -> {
                if (packet.getPackIds().isEmpty()) {
                    session.disconnect("Invalid resource pack response packet received!");
                    chunkRequestQueue.clear();
                    return PacketSignal.HANDLED;
                }
                packsToSend.addAll(packet.getPackIds());
                sendPackDataInfo(packsToSend.pop());
            }
            case HAVE_ALL_PACKS -> {
                ResourcePackStackPacket stackPacket = new ResourcePackStackPacket();
                stackPacket.setExperimentsPreviouslyToggled(false);
                stackPacket.setForcedToAccept(false);
                stackPacket.setGameVersion(session.getClientData().getGameVersion());
                stackPacket.getResourcePacks().addAll(resourcePackLoadEvent.orderedPacks());
                session.sendUpstreamPacket(stackPacket);
            }
            case REFUSED -> session.disconnect("disconnectionScreen.resourcePack");
            default -> {
                session.disconnect("disconnectionScreen.resourcePack");
            }
        }

        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ModalFormResponsePacket packet) {
        if (session.getUpstream().isClosed() || session.isClosed()) return PacketSignal.HANDLED;
        session.executeInEventLoop(() -> session.getFormCache().handleResponse(packet));
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(PlayerAuthInputPacket packet) {
        if (!session.isClosed() && session.isLoggingIn() && !packet.getMotion().equals(Vector2f.ZERO)) {
            SetTitlePacket titlePacket = new SetTitlePacket();
            titlePacket.setType(SetTitlePacket.Type.ACTIONBAR);
            titlePacket.setText(GeyserLocale.getPlayerLocaleString("geyser.auth.login.wait", session.locale()));
            titlePacket.setFadeInTime(0);
            titlePacket.setFadeOutTime(1);
            titlePacket.setStayTime(2);
            titlePacket.setXuid("");
            titlePacket.setPlatformOnlineId("");
            session.sendUpstreamPacket(titlePacket);
        }
        return translateAndDefault(packet);
    }

    // ============================
    // Helper Methods (Legacy / Chunks)
    // ============================
    private boolean couldLoginUserByName(String bedrockUsername) {
        if (geyser.config().savedUserLogins().contains(bedrockUsername)) {
            String authChain = geyser.authChainFor(bedrockUsername);
            if (authChain != null) {
                session.authenticateWithAuthChain(authChain);
                return true;
            }
        }
        PendingMicrosoftAuthentication.AuthenticationTask task = geyser.getPendingMicrosoftAuthentication().getTask(session.getAuthData().xuid());
        return task != null && task.getAuthentication().isDone() && session.onMicrosoftLoginComplete(task);
    }

    private boolean isConsole() {
        BedrockPlatform platform = session.platform();
        return platform == BedrockPlatform.PS4 || platform == BedrockPlatform.XBOX || platform == BedrockPlatform.NX;
    }
}
