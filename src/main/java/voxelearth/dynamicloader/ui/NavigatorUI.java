package voxelearth.dynamicloader.ui;

import com.velocitypowered.api.proxy.Player;
import dev.simplix.protocolize.api.Protocolize;
import dev.simplix.protocolize.api.chat.ChatElement;
import dev.simplix.protocolize.api.inventory.Inventory;
import dev.simplix.protocolize.api.inventory.PlayerInventory;
import dev.simplix.protocolize.api.item.BaseItemStack;
import dev.simplix.protocolize.api.item.ItemStack;
import dev.simplix.protocolize.api.player.ProtocolizePlayer;
import dev.simplix.protocolize.data.ItemType;
import dev.simplix.protocolize.data.inventory.InventoryType;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class NavigatorUI {

    public record FamousPlace(String name, String visitArg) {}

    public enum SettingsAction {
        VISIT_RADIUS_MINUS, VISIT_RADIUS_PLUS, MOVELOAD_TOGGLE, MOVE_RADIUS_MINUS, MOVE_RADIUS_PLUS
    }

    public enum PartyAction {
        CREATE_JOIN, INVITE_HELP, LEAVE, DISBAND
    }

    public interface Callback {
        void onPlaceChosen(UUID playerId, FamousPlace place);
        void onSettingsAction(UUID playerId, SettingsAction action);
        void onPartyAction(UUID playerId, PartyAction action);
    }

    private static final int NAV_HOTBAR_INDEX = 8;                    // 0..8
    private static final int NAV_MC_SLOT_ID   = 36 + NAV_HOTBAR_INDEX; // 44 (slot 9)

    private final List<FamousPlace> places;
    private final Callback cb;

    // small scheduler to re-apply the hotbar item after backend inventory syncs
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NavigatorUI-scheduler");
        t.setDaemon(true);
        return t;
    });

    public NavigatorUI(List<FamousPlace> places, Callback cb) {
        this.places = places;
        this.cb     = cb;
    }

    /** Install global per-player hooks (give item on construct, intercept right-click). */
    public void installHooks() {
        Protocolize.playerProvider().onConstruct(pp -> {
            // Give immediately and then a couple retries to beat backend re-syncs
            giveDirect(pp);
            retryGive(pp, 200);
            retryGive(pp, 900);
            retryGive(pp, 2000);

            // Lock & re-assert slot 9 forever
            startGuard(pp);

            // Right-click with our star opens the main menu
            pp.onInteract(interact -> {
                PlayerInventory inv = pp.proxyInventory();
                BaseItemStack stack = safeItem(inv, NAV_MC_SLOT_ID);
                if (stack != null && stack.itemType() == ItemType.NETHER_STAR) {
                    try { interact.cancelled(true); } catch (Throwable ignored) {}
                    openMain(pp);
                }
            });
        });
    }

    /** Public entry from your Velocity events: safe outside onConstruct. */
    public void give(Player player) {
        ProtocolizePlayer pp = Protocolize.playerProvider().player(player.getUniqueId());
        if (pp == null) return;
        giveDirect(pp);
    }

    /** Force visibility: give + a few timed retries after server connect. */
    public void giveWithRetries(Player player) {
        ProtocolizePlayer pp = Protocolize.playerProvider().player(player.getUniqueId());
        if (pp == null) return;
        giveDirect(pp);
        retryGive(pp, 200);
        retryGive(pp, 900);
        retryGive(pp, 2000);
    }

    private void retryGive(ProtocolizePlayer pp, long delayMs) {
        scheduler.schedule(() -> {
            try { giveDirect(pp); } catch (Throwable ignored) {}
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Internal: write item directly via ProtocolizePlayer to avoid provider recursion. */
    private void giveDirect(ProtocolizePlayer pp) {
        PlayerInventory pinv = pp.proxyInventory();
        ItemStack nav = new ItemStack(ItemType.NETHER_STAR);
        try { nav.displayName(ChatElement.ofLegacyText("§bNavigator §7(Right-Click)")); } catch (Throwable ignored) {}
        pinv.item(NAV_MC_SLOT_ID, nav);
        pinv.update(); // force send to client
    }

    /* ===================== Menus ===================== */

    private void openMain(ProtocolizePlayer pp) {
        Inventory inv = new Inventory(InventoryType.GENERIC_9X3);
        inv.title(ChatElement.ofLegacyText("Voxel Earth • Main"));

        // Better icons
        inv.item(11, named(ItemType.COMPASS, "§bFamous Places"));
        inv.item(13, named(ItemType.REPEATER, "§bRadius & Settings"));
        inv.item(15, named(ItemType.PLAYER_HEAD, "§bParty"));

        inv.onClick(click -> {
            try { click.cancelled(true); } catch (Throwable ignored) {}
            int slot = click.slot();
            if (slot == 11) openPlaces(pp);
            else if (slot == 13) openSettings(pp);
            else if (slot == 15) openParty(pp);
        });

        pp.openInventory(inv);
    }

    private void openPlaces(ProtocolizePlayer pp) {
        final int BACK_SLOT = 22;
        final int MAX_SLOTS = 27;

        Inventory inv = new Inventory(InventoryType.GENERIC_9X3);
        inv.title(ChatElement.ofLegacyText("Voxel Earth • Famous Places"));

        Map<Integer, FamousPlace> slotToPlace = new HashMap<>();
        int slot = 0;
        for (FamousPlace place : places) {
            if (slot >= MAX_SLOTS) {
                break;
            }
            if (slot == BACK_SLOT) {
                slot++;
            }
            if (slot >= MAX_SLOTS) {
                break;
            }

            ItemType icon = iconFor(place.name());
            String displayName = (place.visitArg() == null || place.visitArg().isBlank())
                    ? "§fCustom (use /visit <address>)"
                    : "§f" + place.name();
            inv.item(slot, named(icon, displayName));
            slotToPlace.put(slot, place);
            slot++;
        }

        // Back button consistent with other menus
        inv.item(BACK_SLOT, named(ItemType.ARROW, "§7Back"));

        inv.onClick(click -> {
            try { click.cancelled(true); } catch (Throwable ignored) {}
            int clicked = click.slot();
            if (clicked == BACK_SLOT) { openMain(pp); return; }
            FamousPlace place = slotToPlace.get(clicked);
            if (place != null) {
                cb.onPlaceChosen(pp.uniqueId(), place);
                pp.closeInventory();
            }
        });

        pp.openInventory(inv);
    }

    /** Periodically ensures the star is in slot 9, and nowhere else. */
    private void startGuard(ProtocolizePlayer pp) {
        retryGive(pp, 500);
        retryGive(pp, 1500);
        scheduler.scheduleAtFixedRate(() -> {
            try { enforceSlot(pp); } catch (Throwable ignored) {}
        }, 2500, 2500, TimeUnit.MILLISECONDS);
    }

    private void enforceSlot(ProtocolizePlayer pp) {
        PlayerInventory inv = pp.proxyInventory();
        BaseItemStack s9 = safeItem(inv, NAV_MC_SLOT_ID);
        boolean ok = s9 != null && s9.itemType() == ItemType.NETHER_STAR;
        if (!ok) {
            giveDirect(pp);
        }

        final int MAX_SLOTS = 54;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (i == NAV_MC_SLOT_ID) continue;
            BaseItemStack it = safeItem(inv, i);
            if (it != null && it.itemType() == ItemType.NETHER_STAR) {
                inv.item(i, null);
            }
        }

        inv.update();
    }

    private static ItemType iconFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("pyramid") || n.contains("giza") || n.contains("sahara")) return ItemType.SAND;
        if (n.contains("eiffel") || n.contains("paris")) return ItemType.IRON_BARS;
        if (n.contains("statue of liberty")) return ItemType.EMERALD_BLOCK;
        if (n.contains("taj") || n.contains("agra")) return ItemType.QUARTZ_BLOCK;
        if (n.contains("sydney") || n.contains("opera")) return ItemType.WHITE_WOOL;
        if (n.contains("redeemer") || n.contains("rio")) return ItemType.STONE;
        if (n.contains("everest")) return ItemType.SNOW_BLOCK;
        if (n.contains("grand canyon")) return ItemType.RED_SAND;
        if (n.contains("great wall")) return ItemType.STONE_BRICKS;
        if (n.contains("colosseum") || n.contains("rome")) return ItemType.SANDSTONE;
        if (n.contains("machu") || n.contains("picchu")) return ItemType.MOSSY_COBBLESTONE;
        if (n.contains("burj") || n.contains("dubai")) return ItemType.GLASS;
        if (n.contains("golden gate")) return ItemType.RED_WOOL;
        if (n.contains("big ben") || n.contains("westminster")) return ItemType.CLOCK;
        if (n.contains("niagara")) return ItemType.WATER_BUCKET;
        if (n.contains("santorini") || n.contains("oia")) return ItemType.BLUE_WOOL;
    if (n.contains("custom")) return ItemType.BOOK;
    if (n.contains("petra")) return ItemType.RED_SANDSTONE;
        if (n.contains("angkor")) return ItemType.COBBLESTONE;
        return ItemType.MAP;
    }

    private void openSettings(ProtocolizePlayer pp) {
        Inventory inv = new Inventory(InventoryType.GENERIC_9X3);
        inv.title(ChatElement.ofLegacyText("Voxel Earth • Radius & Settings"));

        // clearer +50 / -50 buttons with better icons
        inv.item(10, named(ItemType.REDSTONE, "§fVisit Radius: -50"));
        inv.item(11, named(ItemType.EMERALD,  "§fVisit Radius: +50"));
        inv.item(13, named(ItemType.LEVER,    "§fAuto MoveLoad: Toggle"));
        inv.item(15, named(ItemType.REDSTONE, "§fMove Radius: -50"));
        inv.item(16, named(ItemType.EMERALD,  "§fMove Radius: +50"));
        // Back button
        inv.item(22, named(ItemType.ARROW,    "§7Back"));

        inv.onClick(click -> {
            try { click.cancelled(true); } catch (Throwable ignored) {}
            int slot = click.slot();
            UUID uid = pp.uniqueId();

            switch (slot) {
                case 10 -> cb.onSettingsAction(uid, SettingsAction.VISIT_RADIUS_MINUS);
                case 11 -> cb.onSettingsAction(uid, SettingsAction.VISIT_RADIUS_PLUS);
                case 13 -> cb.onSettingsAction(uid, SettingsAction.MOVELOAD_TOGGLE);
                case 15 -> cb.onSettingsAction(uid, SettingsAction.MOVE_RADIUS_MINUS);
                case 16 -> cb.onSettingsAction(uid, SettingsAction.MOVE_RADIUS_PLUS);
                case 22 -> { openMain(pp); return; }
                default -> {}
            }
            pp.closeInventory();
        });

        pp.openInventory(inv);
    }

    private void openParty(ProtocolizePlayer pp) {
        Inventory inv = new Inventory(InventoryType.GENERIC_9X3);
        inv.title(ChatElement.ofLegacyText("Voxel Earth • Party"));

        inv.item(10, named(ItemType.EMERALD, "§fCreate / Join"));
        inv.item(12, named(ItemType.BOOK,    "§fInvite (use /party invite <name>)"));
        inv.item(14, named(ItemType.BARRIER, "§fLeave"));
        inv.item(16, named(ItemType.TNT,     "§fDisband (leader)"));
        // Back button
        inv.item(22, named(ItemType.ARROW,   "§7Back"));

        inv.onClick(click -> {
            try { click.cancelled(true); } catch (Throwable ignored) {}
            int slot = click.slot();
            UUID uid = pp.uniqueId();

            switch (slot) {
                case 10 -> cb.onPartyAction(uid, PartyAction.CREATE_JOIN);
                case 12 -> cb.onPartyAction(uid, PartyAction.INVITE_HELP);
                case 14 -> cb.onPartyAction(uid, PartyAction.LEAVE);
                case 16 -> cb.onPartyAction(uid, PartyAction.DISBAND);
                case 22 -> { openMain(pp); return; }
                default -> {}
            }
            pp.closeInventory();
        });

        pp.openInventory(inv);
    }

    private static ItemStack named(ItemType type, String name) {
        ItemStack it = new ItemStack(type);
        try { it.displayName(ChatElement.ofLegacyText(name)); } catch (Throwable ignored) {}
        return it;
    }

    private static BaseItemStack safeItem(PlayerInventory inv, int idx) {
        try { return inv.item(idx); } catch (Throwable ignored) { return null; }
    }

    // Optional external entry point
    public void openMainFor(Player player) {
        ProtocolizePlayer pp = Protocolize.playerProvider().player(player.getUniqueId());
        if (pp != null) openMain(pp);
    }
}
