/*
 *     Copyright (C) 2025 proferabg
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package codecrafter47.bungeetablistplus.handler;

import codecrafter47.bungeetablistplus.protocol.PacketHandler;
import codecrafter47.bungeetablistplus.protocol.PacketListener;
import codecrafter47.bungeetablistplus.protocol.PacketListenerResult;
import codecrafter47.bungeetablistplus.protocol.Team;
import codecrafter47.bungeetablistplus.util.BitSet;
import codecrafter47.bungeetablistplus.util.ConcurrentBitSet;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.HeaderAndFooterPacket;
import com.velocitypowered.proxy.protocol.packet.LegacyPlayerListItemPacket;
import com.velocitypowered.proxy.protocol.packet.RemovePlayerInfoPacket;
import com.velocitypowered.proxy.protocol.packet.UpsertPlayerInfoPacket;
import com.velocitypowered.proxy.protocol.packet.chat.ComponentHolder;
import de.codecrafter47.taboverlay.Icon;
import de.codecrafter47.taboverlay.ProfileProperty;
import de.codecrafter47.taboverlay.config.misc.ChatFormat;
import de.codecrafter47.taboverlay.config.misc.Unchecked;
import de.codecrafter47.taboverlay.handler.ContentOperationMode;
import de.codecrafter47.taboverlay.handler.HeaderAndFooterHandle;
import de.codecrafter47.taboverlay.handler.HeaderAndFooterOperationMode;
import de.codecrafter47.taboverlay.handler.RectangularTabOverlay;
import de.codecrafter47.taboverlay.handler.SimpleTabOverlay;
import de.codecrafter47.taboverlay.handler.TabOverlayHandle;
import de.codecrafter47.taboverlay.handler.TabOverlayHandler;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.val;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_19_3;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_20_2;

public class OrderedTabOverlayHandler implements PacketHandler, TabOverlayHandler {

    // some options
    private static final boolean OPTION_ENABLE_CUSTOM_SLOT_UUID_COLLISION_CHECK = true;

    private static ComponentHolder EMPTY_COMPONENT;
    protected static final String[][] EMPTY_PROPERTIES_ARRAY = new String[0][];

    private static final ImmutableMap<RectangularTabOverlay.Dimension, BitSet> DIMENSION_TO_USED_SLOTS;
    private static final BitSet[] SIZE_TO_USED_SLOTS;

    private static final UUID[] CUSTOM_SLOT_UUID_STEVE;
    private static final UUID[] CUSTOM_SLOT_UUID_ALEX;
    @Nonnull
    private static final Set<UUID> CUSTOM_SLOT_UUIDS;

    static {

        // build the dimension to used slots map (for the rectangular tab overlay)
        val builder = ImmutableMap.<RectangularTabOverlay.Dimension, BitSet>builder();
        for (int columns = 1; columns <= 4; columns++) {
            for (int rows = 0; rows <= 20; rows++) {
                if (columns != 1 && rows != 0 && columns * rows <= (columns - 1) * 20)
                    continue;
                BitSet usedSlots = new BitSet(80);
                for (int column = 0; column < columns; column++) {
                    for (int row = 0; row < rows; row++) {
                        usedSlots.set(index(column, row));
                    }
                }
                builder.put(new RectangularTabOverlay.Dimension(columns, rows), usedSlots);
            }
        }
        DIMENSION_TO_USED_SLOTS = builder.build();

        // build the size to used slots map (for the simple tab overlay)
        SIZE_TO_USED_SLOTS = new BitSet[81];
        for (int size = 0; size <= 80; size++) {
            BitSet usedSlots = new BitSet(80);
            usedSlots.set(0, size);
            SIZE_TO_USED_SLOTS[size] = usedSlots;
        }

        // generate random uuids for our custom slots
        CUSTOM_SLOT_UUID_ALEX = new UUID[80];
        CUSTOM_SLOT_UUID_STEVE = new UUID[80];
        UUID base = UUID.randomUUID();
        long msb = base.getMostSignificantBits();
        long lsb = base.getLeastSignificantBits();
        lsb ^= base.hashCode();
        for (int i = 0; i < 80; i++) {
            CUSTOM_SLOT_UUID_STEVE[i] = new UUID(msb, lsb ^ (2 * i));
            CUSTOM_SLOT_UUID_ALEX[i] = new UUID(msb, lsb ^ (2 * i + 1));
        }
        if (OPTION_ENABLE_CUSTOM_SLOT_UUID_COLLISION_CHECK) {
            CUSTOM_SLOT_UUIDS = ImmutableSet.<UUID>builder()
                    .add(CUSTOM_SLOT_UUID_ALEX)
                    .add(CUSTOM_SLOT_UUID_STEVE).build();
        } else {
            CUSTOM_SLOT_UUIDS = Collections.emptySet();
        }
    }

    private final Logger logger;
    private final Executor eventLoopExecutor;

    private final Object2BooleanMap<UUID> serverPlayerListListed = new Object2BooleanOpenHashMap<>();
    @Nullable
    protected ComponentHolder serverHeader = null;
    @Nullable
    protected ComponentHolder serverFooter = null;

    private final Queue<AbstractContentOperationModeHandler<?>> nextActiveContentHandlerQueue = new ConcurrentLinkedQueue<>();
    private final Queue<AbstractHeaderFooterOperationModeHandler<?>> nextActiveHeaderFooterHandlerQueue = new ConcurrentLinkedQueue<>();
    private AbstractContentOperationModeHandler<?> activeContentHandler;
    private AbstractHeaderFooterOperationModeHandler<?> activeHeaderFooterHandler;

    private final AtomicBoolean updateScheduledFlag = new AtomicBoolean(false);
    private final Runnable updateTask = this::update;

    protected boolean active;

    private boolean logVersionMismatch = false;

    private final Player player;

    public OrderedTabOverlayHandler(Logger logger, Executor eventLoopExecutor, Player player) {
        this.logger = logger;
        this.eventLoopExecutor = eventLoopExecutor;
        this.player = player;
        this.activeContentHandler = new PassThroughContentHandler();
        this.activeHeaderFooterHandler = new PassThroughHeaderFooterHandler();
        EMPTY_COMPONENT = new ComponentHolder(player.getProtocolVersion(), Component.empty());
    }

    @SneakyThrows
    private void sendPacket(MinecraftPacket packet) {
        if (((packet instanceof UpsertPlayerInfoPacket) || (packet instanceof RemovePlayerInfoPacket)) && (player.getProtocolVersion().compareTo(MINECRAFT_1_19_3) < 0)) {
            // error
            if (!logVersionMismatch) {
                logVersionMismatch = true;
                logger.warning("Cannot correctly update tablist for player " + player.getUsername() + "\nThe client and server versions do not match. Client >= 1.19.3, server < 1.19.3.\nUse ViaVersion on the spigot server for the best experience.");
            }
//        }  else if (player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_2) >= 0) {
//            // queue packet?
//            ReflectionUtil.getChannelWrapper(player).write(packet);
        } else {
            PacketListener.sendPacket(player, packet);
        }
    }

    @Override
    public PacketListenerResult onPlayerListPacket(LegacyPlayerListItemPacket packet) {
        return PacketListenerResult.PASS;
    }

    @Override
    public PacketListenerResult onPlayerListUpdatePacket(UpsertPlayerInfoPacket packet) {

        if (!active) {
            active = true;
            scheduleUpdate();
        }

        if (packet.getActions().contains(UpsertPlayerInfoPacket.Action.ADD_PLAYER)) {
            for (UpsertPlayerInfoPacket.Entry item : packet.getEntries()) {
                if (OPTION_ENABLE_CUSTOM_SLOT_UUID_COLLISION_CHECK) {
                    if (CUSTOM_SLOT_UUIDS.contains(item.getProfileId())) {
                        throw new AssertionError("UUID collision " + item.getProfileId());
                    }
                }
                serverPlayerListListed.putIfAbsent(item.getProfileId(), false);
            }
        }
        if (packet.getActions().contains(UpsertPlayerInfoPacket.Action.UPDATE_LISTED)) {
            for (UpsertPlayerInfoPacket.Entry item : packet.getEntries()) {
                serverPlayerListListed.put(item.getProfileId(), item.isListed());
            }
        }

        try {
            return this.activeContentHandler.onPlayerListUpdatePacket(packet);
        } catch (Throwable th) {
            logger.log(Level.SEVERE, "Unexpected error", th);
            // try recover
            enterContentOperationMode(ContentOperationMode.PASS_TROUGH);
            return PacketListenerResult.PASS;
        }
    }

    @Override
    public PacketListenerResult onPlayerListRemovePacket(RemovePlayerInfoPacket packet) {
        for (UUID uuid : packet.getProfilesToRemove()) {
            serverPlayerListListed.removeBoolean(uuid);
        }
        return PacketListenerResult.PASS;
    }

    @Override
    public PacketListenerResult onTeamPacket(Team packet) {
        return PacketListenerResult.PASS;
    }

    @Override
    public PacketListenerResult onPlayerListHeaderFooterPacket(HeaderAndFooterPacket packet) {
        PacketListenerResult result = PacketListenerResult.PASS;
        try {
            result = this.activeHeaderFooterHandler.onPlayerListHeaderFooterPacket(packet);
            if (result == PacketListenerResult.MODIFIED) {
                throw new AssertionError("PacketListenerResult.MODIFIED must not be used");
            }
        } catch (Throwable th) {
            logger.log(Level.SEVERE, "Unexpected error", th);
            // try recover
            enterHeaderAndFooterOperationMode(HeaderAndFooterOperationMode.PASS_TROUGH);
        }

        this.serverHeader = packet.getHeader() != null ? packet.getHeader() : EMPTY_COMPONENT;
        this.serverFooter = packet.getFooter() != null ? packet.getFooter() : EMPTY_COMPONENT;

        return result;
    }

    @Override
    public void onServerSwitch(boolean is13OrLater) {

        try {
            this.activeContentHandler.onServerSwitch();
        } catch (Throwable th) {
            logger.log(Level.SEVERE, "Unexpected error", th);
        }
        try {
            this.activeHeaderFooterHandler.onServerSwitch();
        } catch (Throwable th) {
            logger.log(Level.SEVERE, "Unexpected error", th);
        }

        if (!serverPlayerListListed.isEmpty()) {
            RemovePlayerInfoPacket packet = new RemovePlayerInfoPacket();
            packet.setProfilesToRemove(serverPlayerListListed.keySet());
            sendPacket(packet);
        }

        serverPlayerListListed.clear();
        if (serverHeader != null) {
            serverHeader = EMPTY_COMPONENT;
        }
        if (serverFooter != null) {
            serverFooter = EMPTY_COMPONENT;
        }

        active = false;
    }

    @Override
    public <R> R enterContentOperationMode(ContentOperationMode<R> operationMode) {
        AbstractContentOperationModeHandler<?> handler;
        if (operationMode == ContentOperationMode.PASS_TROUGH) {
            handler = new PassThroughContentHandler();
        } else if (operationMode == ContentOperationMode.SIMPLE) {
            handler = new SimpleOperationModeHandler();
        } else if (operationMode == ContentOperationMode.RECTANGULAR) {
            handler = new RectangularSizeHandler();
        } else {
            throw new UnsupportedOperationException();
        }
        nextActiveContentHandlerQueue.add(handler);
        scheduleUpdate();
        return Unchecked.cast(handler.getTabOverlay());
    }

    @Override
    public <R> R enterHeaderAndFooterOperationMode(HeaderAndFooterOperationMode<R> operationMode) {
        AbstractHeaderFooterOperationModeHandler<?> handler;
        if (operationMode == HeaderAndFooterOperationMode.PASS_TROUGH) {
            handler = new PassThroughHeaderFooterHandler();
        } else if (operationMode == HeaderAndFooterOperationMode.CUSTOM) {
            handler = new CustomHeaderAndFooterOperationModeHandler();
        } else {
            throw new UnsupportedOperationException(Objects.toString(operationMode));
        }
        nextActiveHeaderFooterHandlerQueue.add(handler);
        scheduleUpdate();
        return Unchecked.cast(handler.getTabOverlay());
    }

    private void scheduleUpdate() {
        if (this.updateScheduledFlag.compareAndSet(false, true)) {
            try {
                eventLoopExecutor.execute(updateTask);
            } catch (RejectedExecutionException ignored) {
            }
        }
    }

    private void update() {
        updateScheduledFlag.set(false);

        MinecraftConnection connection = ((ConnectedPlayer) player).getConnection();
        if(!active || connection.isClosed() || connection.getState() != StateRegistry.PLAY){
            return;
        }

        // update content handler
        AbstractContentOperationModeHandler<?> contentHandler;
        while (null != (contentHandler = nextActiveContentHandlerQueue.poll())) {
            this.activeContentHandler.invalidate();
            contentHandler.onActivated(this.activeContentHandler);
            this.activeContentHandler = contentHandler;
        }
        this.activeContentHandler.update();

        // update header and footer handler
        AbstractHeaderFooterOperationModeHandler<?> heaerFooterHandler;
        while (null != (heaerFooterHandler = nextActiveHeaderFooterHandlerQueue.poll())) {
            this.activeHeaderFooterHandler.invalidate();
            heaerFooterHandler.onActivated(this.activeHeaderFooterHandler);
            this.activeHeaderFooterHandler = heaerFooterHandler;
        }
        this.activeHeaderFooterHandler.update();
    }

    private abstract static class AbstractContentOperationModeHandler<T extends AbstractContentTabOverlay> extends OperationModeHandler<T> {

        /**
         * Called when the player receives a {@link UpsertPlayerInfoPacket} packet.
         * <p>
         * This method is called after this {@link OrderedTabOverlayHandler} has updated the {@code serverPlayerList}.
         */
        abstract PacketListenerResult onPlayerListUpdatePacket(UpsertPlayerInfoPacket packet);

        /**
         * Called when the player switches the server.
         * <p>
         * This method is called before this {@link OrderedTabOverlayHandler} executes its own logic to clear the
         * server player list info.
         */
        abstract void onServerSwitch();

        abstract void update();

        final void invalidate() {
            getTabOverlay().invalidate();
            onDeactivated();
        }

        /**
         * Called when this {@link OperationModeHandler} is deactivated.
         * <p>
         * This method must put the client player list in the state expected by {@link #onActivated(AbstractContentOperationModeHandler)}. It must
         * especially remove all custom entries and players must be part of the correct teams.
         */
        abstract void onDeactivated();

        /**
         * Called when this {@link OperationModeHandler} becomes the active one.
         * <p>
         * State of the player list when this method is called:
         * - there are no custom entries on the client
         * - all entries from {@link #serverPlayerListListed} but may not be listed
         * - player list header/ footer may be wrong
         * <p>
         * Additional information about the state of the player list may be obtained from the previous handler
         *
         * @param previous previous handler
         */
        abstract void onActivated(AbstractContentOperationModeHandler<?> previous);
    }

    private abstract static class AbstractHeaderFooterOperationModeHandler<T extends AbstractHeaderFooterTabOverlay> extends OperationModeHandler<T> {

        /**
         * Called when the player receives a {@link HeaderAndFooterPacket} packet.
         * <p>
         * This method is called before this {@link OrderedTabOverlayHandler} executes its own logic to update the
         * server player list info.
         */
        abstract PacketListenerResult onPlayerListHeaderFooterPacket(HeaderAndFooterPacket packet);

        /**
         * Called when the player switches the server.
         * <p>
         * This method is called before this {@link OrderedTabOverlayHandler} executes its own logic to clear the
         * server player list info.
         */
        abstract void onServerSwitch();

        abstract void update();

        final void invalidate() {
            getTabOverlay().invalidate();
            onDeactivated();
        }

        /**
         * Called when this {@link OperationModeHandler} is deactivated.
         * <p>
         * This method must put the client player list in the state expected by {@link #onActivated(AbstractHeaderFooterOperationModeHandler)}. It must
         * especially remove all custom entries and players must be part of the correct teams.
         */
        abstract void onDeactivated();

        /**
         * Called when this {@link OperationModeHandler} becomes the active one.
         * <p>
         * State of the player list when this method is called:
         * - there are no custom entries on the client
         * - all entries from {@link #serverPlayerListListed} are known to the client, but might not be listed
         * - player list header/ footer may be wrong
         * <p>
         * Additional information about the state of the player list may be obtained from the previous handler
         *
         * @param previous previous handler
         */
        abstract void onActivated(AbstractHeaderFooterOperationModeHandler<?> previous);
    }

    private abstract static class AbstractContentTabOverlay implements TabOverlayHandle {
        private boolean valid = true;

        @Override
        public boolean isValid() {
            return valid;
        }

        final void invalidate() {
            valid = false;
        }
    }

    private abstract static class AbstractHeaderFooterTabOverlay implements TabOverlayHandle {
        private boolean valid = true;

        @Override
        public boolean isValid() {
            return valid;
        }

        final void invalidate() {
            valid = false;
        }
    }

    private final class PassThroughContentHandler extends AbstractContentOperationModeHandler<PassThroughContentTabOverlay> {

        @Override
        protected PassThroughContentTabOverlay createTabOverlay() {
            return new PassThroughContentTabOverlay();
        }

        @Override
        PacketListenerResult onPlayerListUpdatePacket(UpsertPlayerInfoPacket packet) {
            return PacketListenerResult.PASS;
        }

        @Override
        void onServerSwitch() {
            sendPacket(new HeaderAndFooterPacket(EMPTY_COMPONENT, EMPTY_COMPONENT));
        }

        @Override
        void update() {
            // nothing to do
        }

        @Override
        void onDeactivated() {
            // nothing to do
        }

        @Override
        void onActivated(AbstractContentOperationModeHandler<?> previous) {
            if (previous instanceof PassThroughContentHandler) {
                // we're lucky, nothing to do
                return;
            }

            // update visibility
            if (!serverPlayerListListed.isEmpty()) {
                List<UpsertPlayerInfoPacket.Entry> items = new ArrayList<>(serverPlayerListListed.size());
                for (Object2BooleanMap.Entry<UUID> entry : serverPlayerListListed.object2BooleanEntrySet()) {
                    UpsertPlayerInfoPacket.Entry item = new UpsertPlayerInfoPacket.Entry(entry.getKey());
                    item.setListed(entry.getBooleanValue());
                    items.add(item);
                }
                UpsertPlayerInfoPacket packet = new UpsertPlayerInfoPacket();
                packet.addAction(UpsertPlayerInfoPacket.Action.UPDATE_LISTED);
                packet.addAllEntries(items);
                sendPacket(packet);
            }
        }
    }

    private static final class PassThroughContentTabOverlay extends AbstractContentTabOverlay {

    }

    private final class PassThroughHeaderFooterHandler extends AbstractHeaderFooterOperationModeHandler<PassThroughHeaderFooterTabOverlay> {

        @Override
        protected PassThroughHeaderFooterTabOverlay createTabOverlay() {
            return new PassThroughHeaderFooterTabOverlay();
        }

        @Override
        PacketListenerResult onPlayerListHeaderFooterPacket(HeaderAndFooterPacket packet) {
            return PacketListenerResult.PASS;
        }

        @Override
        void onServerSwitch() {
            sendPacket(new HeaderAndFooterPacket(EMPTY_COMPONENT, EMPTY_COMPONENT));
        }

        @Override
        void update() {
            // nothing to do
        }

        @Override
        void onDeactivated() {
            // nothing to do
        }

        @Override
        void onActivated(AbstractHeaderFooterOperationModeHandler<?> previous) {
            if (previous instanceof PassThroughHeaderFooterHandler) {
                // we're lucky, nothing to do
                return;
            }

            // fix header/ footer
            sendPacket(new HeaderAndFooterPacket(serverHeader != null ? serverHeader : EMPTY_COMPONENT, serverFooter != null ? serverFooter : EMPTY_COMPONENT));
        }
    }

    private static final class PassThroughHeaderFooterTabOverlay extends AbstractHeaderFooterTabOverlay {

    }

    private abstract class CustomContentTabOverlayHandler<T extends CustomContentTabOverlay> extends AbstractContentOperationModeHandler<T> {

        @Nonnull
        BitSet usedSlots;
        BitSet dirtySlots;
        final SlotState[] slotState;
        /**
         * Uuid of the player list entry used for the slot.
         */
        final UUID[] slotUuid;

        private final List<UpsertPlayerInfoPacket.Entry> itemQueueAddPlayer;
        private final List<UUID> itemQueueRemovePlayer;
        private final List<UpsertPlayerInfoPacket.Entry> itemQueueUpdateDisplayName;
        private final List<UpsertPlayerInfoPacket.Entry> itemQueueUpdatePing;

        private CustomContentTabOverlayHandler() {
            this.dirtySlots = new BitSet(80);
            this.usedSlots = SIZE_TO_USED_SLOTS[0];
            this.slotState = new SlotState[80];
            Arrays.fill(this.slotState, SlotState.UNUSED);
            this.slotUuid = new UUID[80];
            this.itemQueueAddPlayer = new ArrayList<>(80);
            this.itemQueueRemovePlayer = new ArrayList<>(80);
            this.itemQueueUpdateDisplayName = new ArrayList<>(80);
            this.itemQueueUpdatePing = new ArrayList<>(80);
        }

        @Override
        PacketListenerResult onPlayerListUpdatePacket(UpsertPlayerInfoPacket packet) {

            if (packet.getActions().contains(UpsertPlayerInfoPacket.Action.UPDATE_LISTED)) {
                for (UpsertPlayerInfoPacket.Entry item : packet.getEntries()) {
                    item.setListed(false);
                }
            }
            return PacketListenerResult.MODIFIED;
        }

        @Override
        void onServerSwitch() {
            if (player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_2) >= 0){
                clearCustomSlots();
            }
        }

        @Override
        void onActivated(AbstractContentOperationModeHandler<?> previous) {

            // make all players unlisted
            if (!serverPlayerListListed.isEmpty()) {
                List<UpsertPlayerInfoPacket.Entry> items = new ArrayList<>(serverPlayerListListed.size());
                for (Object2BooleanMap.Entry<UUID> entry : serverPlayerListListed.object2BooleanEntrySet()) {
                    UpsertPlayerInfoPacket.Entry item = new UpsertPlayerInfoPacket.Entry(entry.getKey());
                    item.setListed(false);
                    items.add(item);
                }
                UpsertPlayerInfoPacket packet = new UpsertPlayerInfoPacket();
                packet.addAction(UpsertPlayerInfoPacket.Action.UPDATE_LISTED);
                packet.addAllEntries(items);
                sendPacket(packet);
            }
        }

        @Override
        void onDeactivated() {
            clearCustomSlots();
        }

        private void clearCustomSlots() {
            int customSlots = 0;
            for (int index = 0; index < 80; index++) {
                if (slotState[index] != SlotState.UNUSED) {
                    customSlots++;
                    dirtySlots.set(index);
                }
            }

            int i = 0;
            if (customSlots > 0) {
                UUID[] uuids = new UUID[customSlots];
                for (int index = 0; index < 80; index++) {
                    // switch slot from custom to unused
                    if (slotState[index] == SlotState.CUSTOM) {
                        uuids[i++] = slotUuid[index];
                    }
                }
                RemovePlayerInfoPacket packet = new RemovePlayerInfoPacket();
                packet.setProfilesToRemove(Arrays.asList(uuids));
                sendPacket(packet);
            }
        }

        @Override
        void update() {

            T tabOverlay = getTabOverlay();

            if (tabOverlay.dirtyFlagSize) {
                tabOverlay.dirtyFlagSize = false;
                updateSize();
            }

            // update icons
            dirtySlots.orAndClear(tabOverlay.dirtyFlagsIcon);
            for (int index = dirtySlots.nextSetBit(0); index >= 0; index = dirtySlots.nextSetBit(index + 1)) {
                if (slotState[index] == SlotState.CUSTOM) {
                    // remove item
                    itemQueueRemovePlayer.add(slotUuid[index]);
                    slotState[index] = SlotState.UNUSED;
                }
                
                if (usedSlots.get(index)) {
                    Icon icon = tabOverlay.icon[index];
                    UUID customSlotUuid;
                    if (icon.isAlex()) {
                        customSlotUuid = CUSTOM_SLOT_UUID_ALEX[index];
                    } else { // steve
                        customSlotUuid = CUSTOM_SLOT_UUID_STEVE[index];
                    }
                    tabOverlay.dirtyFlagsText.clear(index);
                    tabOverlay.dirtyFlagsPing.clear(index);
                    slotState[index] = SlotState.CUSTOM;
                    slotUuid[index] = customSlotUuid;
                    UpsertPlayerInfoPacket.Entry item = new UpsertPlayerInfoPacket.Entry(customSlotUuid);
                    GameProfile profile = new GameProfile(customSlotUuid, "", toPropertiesList(icon.getTextureProperty()));
                    item.setProfile(profile);
                    item.setDisplayName(new ComponentHolder(player.getProtocolVersion(), tabOverlay.text[index]));
                    item.setLatency(tabOverlay.ping[index]);
                    item.setGameMode(0);
                    item.setListed(true);
                    item.setListOrder(-index);
                    itemQueueAddPlayer.add(item);
                }
            }

            // update text
            dirtySlots.copyAndClear(tabOverlay.dirtyFlagsText);
            for (int index = dirtySlots.nextSetBit(0); index >= 0; index = dirtySlots.nextSetBit(index + 1)) {
                if (slotState[index] != SlotState.UNUSED) {
                    UpsertPlayerInfoPacket.Entry item = new UpsertPlayerInfoPacket.Entry(slotUuid[index]);
                    item.setDisplayName(new ComponentHolder(player.getProtocolVersion(), tabOverlay.text[index]));
                    itemQueueUpdateDisplayName.add(item);
                }
            }

            // update ping
            dirtySlots.copyAndClear(tabOverlay.dirtyFlagsPing);
            for (int index = dirtySlots.nextSetBit(0); index >= 0; index = dirtySlots.nextSetBit(index + 1)) {
                if (slotState[index] != SlotState.UNUSED) {
                    UpsertPlayerInfoPacket.Entry item = new UpsertPlayerInfoPacket.Entry(slotUuid[index]);
                    item.setLatency(tabOverlay.ping[index]);
                    itemQueueUpdatePing.add(item);
                }
            }

            dirtySlots.clear();

            // send packets
            sendQueuedItems();
        }

        private void sendQueuedItems() {
            if (!itemQueueRemovePlayer.isEmpty()) {
                RemovePlayerInfoPacket packet = new RemovePlayerInfoPacket();
                packet.setProfilesToRemove(itemQueueRemovePlayer);
                sendPacket(packet);
                itemQueueRemovePlayer.clear();
            }
            if (!itemQueueAddPlayer.isEmpty()) {
                UpsertPlayerInfoPacket packet = new UpsertPlayerInfoPacket();
                packet.addAllActions(EnumSet.of(UpsertPlayerInfoPacket.Action.ADD_PLAYER, UpsertPlayerInfoPacket.Action.UPDATE_DISPLAY_NAME, UpsertPlayerInfoPacket.Action.UPDATE_LATENCY, UpsertPlayerInfoPacket.Action.UPDATE_LISTED, UpsertPlayerInfoPacket.Action.UPDATE_LIST_ORDER));
                packet.addAllEntries(itemQueueAddPlayer);
                sendPacket(packet);
                itemQueueAddPlayer.clear();
            }
            if (!itemQueueUpdateDisplayName.isEmpty()) {
                UpsertPlayerInfoPacket packet = new UpsertPlayerInfoPacket();
                packet.addAction(UpsertPlayerInfoPacket.Action.UPDATE_DISPLAY_NAME);
                packet.addAllEntries(itemQueueUpdateDisplayName);
                sendPacket(packet);
                itemQueueUpdateDisplayName.clear();
            }
            if (!itemQueueUpdatePing.isEmpty()) {
                UpsertPlayerInfoPacket packet = new UpsertPlayerInfoPacket();
                packet.addAction(UpsertPlayerInfoPacket.Action.UPDATE_LATENCY);
                packet.addAllEntries(itemQueueUpdatePing);
                sendPacket(packet);
                itemQueueUpdatePing.clear();
            }
        }

        /**
         * Updates the usedSlots BitSet. Sets the {@link #dirtySlots uuid dirty flag} for all added
         * and removed slots.
         */
        abstract void updateSize();
    }

    private abstract class CustomContentTabOverlay extends AbstractContentTabOverlay implements TabOverlayHandle.BatchModifiable {
        final Icon[] icon;
        final Component[] text;
        final int[] ping;

        final AtomicInteger batchUpdateRecursionLevel;
        volatile boolean dirtyFlagSize;
        final ConcurrentBitSet dirtyFlagsIcon;
        final ConcurrentBitSet dirtyFlagsText;
        final ConcurrentBitSet dirtyFlagsPing;

        private CustomContentTabOverlay() {
            this.icon = new Icon[80];
            Arrays.fill(this.icon, Icon.DEFAULT_STEVE);
            this.text = new Component[80];
            Arrays.fill(this.text, Component.empty());
            this.ping = new int[80];
            this.batchUpdateRecursionLevel = new AtomicInteger(0);
            this.dirtyFlagSize = true;
            this.dirtyFlagsIcon = new ConcurrentBitSet(80);
            this.dirtyFlagsText = new ConcurrentBitSet(80);
            this.dirtyFlagsPing = new ConcurrentBitSet(80);
        }

        @Override
        public void beginBatchModification() {
            if (isValid()) {
                if (batchUpdateRecursionLevel.incrementAndGet() < 0) {
                    throw new AssertionError("Recursion level overflow");
                }
            }
        }

        @Override
        public void completeBatchModification() {
            if (isValid()) {
                int level = batchUpdateRecursionLevel.decrementAndGet();
                if (level == 0) {
                    scheduleUpdate();
                } else if (level < 0) {
                    throw new AssertionError("Recursion level underflow");
                }
            }
        }

        void scheduleUpdateIfNotInBatch() {
            if (batchUpdateRecursionLevel.get() == 0) {
                scheduleUpdate();
            }
        }

        void setIconInternal(int index, @Nonnull @NonNull Icon icon) {
            if (!icon.equals(this.icon[index])) {
                this.icon[index] = icon;
                dirtyFlagsIcon.set(index);
                scheduleUpdateIfNotInBatch();
            }
        }

        void setTextInternal(int index, @Nonnull @NonNull String text) {
            Component component = GsonComponentSerializer.gson().deserialize(ChatFormat.formattedTextToJson(text));
            if (!component.equals(this.text[index])) {
                this.text[index] = component;
                dirtyFlagsText.set(index);
                scheduleUpdateIfNotInBatch();
            }
        }

        void setPingInternal(int index, int ping) {
            if (ping != this.ping[index]) {
                this.ping[index] = ping;
                dirtyFlagsPing.set(index);
                scheduleUpdateIfNotInBatch();
            }
        }
    }

    private class RectangularSizeHandler extends CustomContentTabOverlayHandler<RectangularTabOverlayImpl> {

        @Override
        void updateSize() {
            RectangularTabOverlayImpl tabOverlay = getTabOverlay();
            RectangularTabOverlay.Dimension size = tabOverlay.getSize();
            BitSet newUsedSlots = DIMENSION_TO_USED_SLOTS.get(size);
            dirtySlots.orXor(usedSlots, newUsedSlots);
            usedSlots = newUsedSlots;
        }

        @Override
        protected RectangularTabOverlayImpl createTabOverlay() {
            return new RectangularTabOverlayImpl();
        }
    }

    private class RectangularTabOverlayImpl extends CustomContentTabOverlay implements RectangularTabOverlay {

        @Nonnull
        private Dimension size;

        private RectangularTabOverlayImpl() {
            Optional<Dimension> dimensionZero = getSupportedSizes().stream().filter(size -> size.getSize() == 0).findAny();
            if (!dimensionZero.isPresent()) {
                throw new AssertionError();
            }
            this.size = dimensionZero.get();
        }

        @Nonnull
        @Override
        public Dimension getSize() {
            return size;
        }

        @Override
        public Collection<Dimension> getSupportedSizes() {
            return DIMENSION_TO_USED_SLOTS.keySet();
        }

        @Override
        public void setSize(@Nonnull Dimension size) {
            if (!getSupportedSizes().contains(size)) {
                throw new IllegalArgumentException("Unsupported size " + size);
            }
            if (isValid() && !this.size.equals(size)) {
                BitSet oldUsedSlots = DIMENSION_TO_USED_SLOTS.get(this.size);
                BitSet newUsedSlots = DIMENSION_TO_USED_SLOTS.get(size);
                for (int index = newUsedSlots.nextSetBit(0); index >= 0; index = newUsedSlots.nextSetBit(index + 1)) {
                    if (!oldUsedSlots.get(index)) {
                        icon[index] = Icon.DEFAULT_STEVE;
                        text[index] = Component.empty();
                        ping[index] = 0;
                    }
                }
                this.size = size;
                this.dirtyFlagSize = true;
                scheduleUpdateIfNotInBatch();
                for (int index = oldUsedSlots.nextSetBit(0); index >= 0; index = oldUsedSlots.nextSetBit(index + 1)) {
                    if (!newUsedSlots.get(index)) {
                        icon[index] = Icon.DEFAULT_STEVE;
                        text[index] = Component.empty();
                        ping[index] = 0;
                    }
                }
            }
        }

        @Override
        public void setSlot(int column, int row, @Nullable UUID uuid, @Nonnull Icon icon, @Nonnull String text, int ping) {
            setSlot(column, row, icon, text, ping);
        }

        @Override
        public void setSlot(int column, int row, @Nonnull Icon icon, @Nonnull String text, int ping) {
            if (isValid()) {
                Preconditions.checkElementIndex(column, size.getColumns(), "column");
                Preconditions.checkElementIndex(row, size.getRows(), "row");
                beginBatchModification();
                try {
                    int index = index(column, row);
                    setIconInternal(index, icon);
                    setTextInternal(index, text);
                    setPingInternal(index, ping);
                } finally {
                    completeBatchModification();
                }
            }
        }

        @Override
        public void setUuid(int column, int row, @Nullable UUID uuid) {
            // no op
        }

        @Override
        public void setIcon(int column, int row, @Nonnull Icon icon) {
            if (isValid()) {
                Preconditions.checkElementIndex(column, size.getColumns(), "column");
                Preconditions.checkElementIndex(row, size.getRows(), "row");
                setIconInternal(index(column, row), icon);
            }
        }

        @Override
        public void setText(int column, int row, @Nonnull String text) {
            if (isValid()) {
                Preconditions.checkElementIndex(column, size.getColumns(), "column");
                Preconditions.checkElementIndex(row, size.getRows(), "row");
                setTextInternal(index(column, row), text);
            }
        }

        @Override
        public void setPing(int column, int row, int ping) {
            if (isValid()) {
                Preconditions.checkElementIndex(column, size.getColumns(), "column");
                Preconditions.checkElementIndex(row, size.getRows(), "row");
                setPingInternal(index(column, row), ping);
            }
        }
    }

    private class SimpleOperationModeHandler extends CustomContentTabOverlayHandler<SimpleTabOverlayImpl> {

        private int size = 0;

        @Override
        void updateSize() {
            int newSize = getTabOverlay().size;
            if (newSize > size) {
                dirtySlots.set(size, newSize);
            } else if (newSize < size) {
                dirtySlots.set(newSize, size);
            }
            usedSlots = SIZE_TO_USED_SLOTS[newSize];
            size = newSize;
        }

        @Override
        protected SimpleTabOverlayImpl createTabOverlay() {
            return new SimpleTabOverlayImpl();
        }
    }

    private class SimpleTabOverlayImpl extends CustomContentTabOverlay implements SimpleTabOverlay {
        int size = 0;

        @Override
        public int getSize() {
            return size;
        }

        @Override
        public int getMaxSize() {
            return 80;
        }

        @Override
        public void setSize(int size) {
            if (size < 0 || size > 80) {
                throw new IllegalArgumentException("size");
            }
            this.size = size;
            dirtyFlagSize = true;
            scheduleUpdateIfNotInBatch();
        }

        @Override
        public void setSlot(int index, @Nullable UUID uuid, @Nonnull Icon icon, @Nonnull String text, int ping) {
            if (isValid()) {
                Preconditions.checkElementIndex(index, size, "index");
                beginBatchModification();
                try {
                    setIconInternal(index, icon);
                    setTextInternal(index, text);
                    setPingInternal(index, ping);
                } finally {
                    completeBatchModification();
                }
            }
        }

        @Override
        public void setSlot(int index, @Nonnull Icon icon, @Nonnull String text, int ping) {
            if (isValid()) {
                Preconditions.checkElementIndex(index, size, "index");
                beginBatchModification();
                try {
                    setIconInternal(index, icon);
                    setTextInternal(index, text);
                    setPingInternal(index, ping);
                } finally {
                    completeBatchModification();
                }
            }
        }

        @Override
        public void setUuid(int index, UUID uuid) {
            // no op
        }

        @Override
        public void setIcon(int index, @Nonnull @NonNull Icon icon) {
            if (isValid()) {
                Preconditions.checkElementIndex(index, size, "index");
                setIconInternal(index, icon);
            }
        }

        @Override
        public void setText(int index, @Nonnull @NonNull String text) {
            if (isValid()) {
                Preconditions.checkElementIndex(index, size, "index");
                setTextInternal(index, text);
            }
        }

        @Override
        public void setPing(int index, int ping) {
            if (isValid()) {
                Preconditions.checkElementIndex(index, size, "index");
                setPingInternal(index, ping);
            }
        }
    }

    private final class CustomHeaderAndFooterOperationModeHandler extends AbstractHeaderFooterOperationModeHandler<CustomHeaderAndFooterImpl> {

        @Override
        protected CustomHeaderAndFooterImpl createTabOverlay() {
            return new CustomHeaderAndFooterImpl();
        }

        @Override
        PacketListenerResult onPlayerListHeaderFooterPacket(HeaderAndFooterPacket packet) {
            return PacketListenerResult.CANCEL;
        }

        @Override
        void onServerSwitch() {
            // do nothing
        }

        @Override
        void onDeactivated() {
            //do nothing
        }

        @Override
        void onActivated(AbstractHeaderFooterOperationModeHandler<?> previous) {
            // remove header/ footer
            sendPacket(new HeaderAndFooterPacket(EMPTY_COMPONENT, EMPTY_COMPONENT));
        }

        @Override
        void update() {
            CustomHeaderAndFooterImpl tabOverlay = getTabOverlay();
            if (tabOverlay.headerOrFooterDirty) {
                tabOverlay.headerOrFooterDirty = false;
                sendPacket(new HeaderAndFooterPacket(tabOverlay.header, tabOverlay.footer));
            }
        }
    }

    private final class CustomHeaderAndFooterImpl extends AbstractHeaderFooterTabOverlay implements HeaderAndFooterHandle {
        private ComponentHolder header = EMPTY_COMPONENT;
        private ComponentHolder footer = EMPTY_COMPONENT;

        private volatile boolean headerOrFooterDirty = false;

        final AtomicInteger batchUpdateRecursionLevel = new AtomicInteger(0);

        @Override
        public void beginBatchModification() {
            if (isValid()) {
                if (batchUpdateRecursionLevel.incrementAndGet() < 0) {
                    throw new AssertionError("Recursion level overflow");
                }
            }
        }

        @Override
        public void completeBatchModification() {
            if (isValid()) {
                int level = batchUpdateRecursionLevel.decrementAndGet();
                if (level == 0) {
                    scheduleUpdate();
                } else if (level < 0) {
                    throw new AssertionError("Recursion level underflow");
                }
            }
        }

        void scheduleUpdateIfNotInBatch() {
            if (batchUpdateRecursionLevel.get() == 0) {
                scheduleUpdate();
            }
        }

        @Override
        public void setHeaderFooter(@Nullable String header, @Nullable String footer) {
            this.header = new ComponentHolder(player.getProtocolVersion(), ChatFormat.formattedTextToJson(header));
            this.footer = new ComponentHolder(player.getProtocolVersion(), ChatFormat.formattedTextToJson(footer));
            headerOrFooterDirty = true;
            scheduleUpdateIfNotInBatch();
        }

        @Override
        public void setHeader(@Nullable String header) {
            this.header = new ComponentHolder(player.getProtocolVersion(), ChatFormat.formattedTextToJson(header));
            headerOrFooterDirty = true;
            scheduleUpdateIfNotInBatch();
        }

        @Override
        public void setFooter(@Nullable String footer) {
            this.footer = new ComponentHolder(player.getProtocolVersion(), ChatFormat.formattedTextToJson(footer));
            headerOrFooterDirty = true;
            scheduleUpdateIfNotInBatch();
        }
    }

    private static int index(int column, int row) {
        return column * 20 + row;
    }

    private static List<GameProfile.Property> toPropertiesList(ProfileProperty textureProperty) {
        if (textureProperty == null) {
            return new ArrayList<>();
        } else if (textureProperty.isSigned()) {
            return List.of(new GameProfile.Property(textureProperty.getName(), textureProperty.getValue(), textureProperty.getSignature()));
        } else {
            return List.of(new GameProfile.Property(textureProperty.getName(), textureProperty.getValue(), ""));
        }
    }

    private enum SlotState {
        UNUSED, CUSTOM
    }
}
