package voxelearth.dynamicloader;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.event.ClickEvent;
import voxelearth.dynamicloader.PartyManager.Party;
import voxelearth.dynamicloader.net.RconClient;
import voxelearth.dynamicloader.ui.NavigatorUI;
import voxelearth.dynamicloader.ui.NavigatorUI.FamousPlace;
import voxelearth.dynamicloader.ui.NavigatorUI.PartyAction;
import voxelearth.dynamicloader.ui.NavigatorUI.SettingsAction;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Velocity-only DynamicLoader:
 * - Slot 9 Navigator (client-side) + chest GUIs using Protocolize v2
 * - Per-party world spawn, leader-first connect, auto-pull friends
 * - Backend commands via RCON (fallback: clickable)
 * - One-time void-proof platform on new worlds
 */
@Plugin(
        id = "dynamicloader",
        name = "DynamicLoader",
        version = "1.6.2",
        description = "Spawns per-party servers and provides GUI & lobby management from Velocity.",
        dependencies = { @Dependency(id = "protocolize") }
)
public class DynamicLoader {

    private final ProxyServer proxy;
    private final ComponentLogger logger;

    // One world per party leader
    private final Map<UUID, ServerSession> sessionsByLeader = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> leaderOfMember = new ConcurrentHashMap<>();

    // Settings state (tracked per leader on the proxy to support ±50 buttons)
    private final Map<UUID, Integer> visitRadius = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> moveRadius  = new ConcurrentHashMap<>();
    private static final int DEFAULT_RADIUS = 256;
    private static final int RADIUS_STEP    = 50;
    private static final int RADIUS_MIN     = 50;
    private static final int RADIUS_MAX     = 1024;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private static final String LOBBY_NAME = "lobby";

    private final PartyManager parties;
    private final Set<String> platformInitialized = ConcurrentHashMap.newKeySet();
    private final Deque<ServerSession> warmPool = new ConcurrentLinkedDeque<>();
    private final ScheduledExecutorService warmKeeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DynamicLoader-warm");
        t.setDaemon(true);
        return t;
    });
    private static final int WARM_BUFFER = 2;

    private NavigatorUI nav;

    @Inject
    public DynamicLoader(ProxyServer proxy, ComponentLogger logger) {
        this.proxy = proxy;
        this.logger = logger;
        this.parties = new PartyManager(proxy);

        proxy.getCommandManager().register("earth", new VoxelearthCommand());
        proxy.getCommandManager().register("lobby", new LobbyCommand());
        proxy.getCommandManager().register("party", new PartyCommand());
        proxy.getCommandManager().register("help", new HelpCommand()); // override help
        proxy.getCommandManager().register("visit", new VisitCommand());
        proxy.getCommandManager().register("visitradius", new VisitRadiusCommand());
        proxy.getCommandManager().register("visitradiusother", new VisitRadiusOtherCommand());
        proxy.getCommandManager().register("moveradius", new MoveRadiusCommand());
        proxy.getCommandManager().register("moveradiusother", new MoveRadiusOtherCommand());
        proxy.getCommandManager().register("moveload", new MoveLoadCommand());
        proxy.getCommandManager().register("moveloadother", new MoveLoadOtherCommand());
    }

    /** Initialize Protocolize-driven UI after the proxy + dependencies are ready. */
    @Subscribe
    public void onProxyInit(ProxyInitializeEvent e) {
        if (!protocolizeAvailable()) {
            logger.error("Protocolize not found. Install protocolize-velocity (2.4.x) into the /plugins folder.");
            return;
        }
        this.nav = new NavigatorUI(
                List.of(
                        new FamousPlace("Great Pyramids of Giza, Egypt",        "great pyramids of giza egypt"),
                        new FamousPlace("Eiffel Tower, Paris France",           "eiffel tower paris"),
                        new FamousPlace("Statue of Liberty, NYC USA",           "statue of liberty new york"),
                        new FamousPlace("Taj Mahal, Agra India",                "taj mahal agra"),
                        new FamousPlace("Sydney Opera House, Australia",        "sydney opera house"),
                        new FamousPlace("Christ the Redeemer, Rio Brazil",      "christ the redeemer rio de janeiro"),
                        new FamousPlace("Mount Everest Base Camp, Nepal",       "everest base camp nepal"),
                        new FamousPlace("Grand Canyon South Rim, USA",          "grand canyon south rim"),
                        new FamousPlace("Great Wall Mutianyu, China",           "great wall mutianyu"),
                        new FamousPlace("Colosseum, Rome Italy",                "colosseum rome"),
                        new FamousPlace("Machu Picchu, Peru",                   "machu picchu"),
                        new FamousPlace("Burj Khalifa, Dubai UAE",              "burj khalifa dubai"),
                        new FamousPlace("Golden Gate Bridge, San Francisco",    "golden gate bridge san francisco"),
                        new FamousPlace("Big Ben, London UK",                   "big ben london"),
                        new FamousPlace("Niagara Falls, USA Canada",            "niagara falls"),
                        new FamousPlace("Santorini - Oia, Greece",              "oia santorini"),
                        new FamousPlace("Custom",                               ""),
                        new FamousPlace("Angkor Wat, Siem Reap Cambodia",       "angkor wat siem reap")
                ),
                new NavigatorUI.Callback() {
                    @Override
                    public void onPlaceChosen(UUID playerId, FamousPlace place) {
                        proxy.getPlayer(playerId).ifPresent(p -> handleVisitRequest(p, place.visitArg()));
                    }

                    @Override
                    public void onSettingsAction(UUID playerId, SettingsAction action) {
                        proxy.getPlayer(playerId).ifPresent(p -> handleSettingsAction(p, action));
                    }

                    @Override
                    public void onPartyAction(UUID playerId, PartyAction action) {
                        proxy.getPlayer(playerId).ifPresent(p -> handlePartyAction(p, action));
                    }
                }
        );
        this.nav.installHooks(); // register onConstruct/onInteract
        logger.info("Navigator UI initialized.");

        warmKeeper.scheduleAtFixedRate(() -> {
            try {
                int target = Math.min(8, proxy.getAllPlayers().size() + WARM_BUFFER);
                while (warmPool.size() < target) {
                    warmPool.add(spawnWarm());
                }
                while (warmPool.size() > target) {
                    shutdownWarm(warmPool.pollLast());
                }
            } catch (Throwable t) {
                logger.warn("Warm pool upkeep failed", t);
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    private boolean protocolizeAvailable() {
        try {
            Class.forName("dev.simplix.protocolize.api.Protocolize", false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static class ServerSession {
        String name;
        int port;
        int rconPort;
        String rconPass;
        Process process;
        Path folder;
        ServerInfo info;
        boolean connecting = false;
        UUID leader;
        final Set<UUID> members = ConcurrentHashMap.newKeySet(); // includes leader
    }

    // /help (override)
    private class HelpCommand implements SimpleCommand {
        @Override public void execute(Invocation in) {
            if (!(in.source() instanceof Player p)) return;
            p.sendMessage(Component.text("Voxel Earth Help", NamedTextColor.AQUA));
            p.sendMessage(Component.text("• Right-click the Nether Star in slot 9 to open the Navigator.", NamedTextColor.GRAY));
            p.sendMessage(Component.text("• /earth — create/join your personal Earth", NamedTextColor.GREEN));
            p.sendMessage(Component.text("• /party — create, invite, accept, leave, disband", NamedTextColor.GREEN));
            p.sendMessage(Component.text("• /lobby — return to the main lobby", NamedTextColor.GREEN));
            p.sendMessage(Component.text("Inside your Earth server:", NamedTextColor.AQUA));
            p.sendMessage(Component.text("• /visit <address>  • /visitradius <value>  • /moveload on|off  • /moveradius <value>", NamedTextColor.GRAY));
        }
    }

    // /earth
    private class VoxelearthCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) return;
            UUID playerId = player.getUniqueId();

            UUID leader = leaderFor(playerId);
            Party party = parties.getPartyOf(playerId).orElseGet(() -> parties.createOrGetParty(playerId));
            Collection<UUID> members = new ArrayList<>(party.members);

            ServerSession existing = sessionsByLeader.get(leader);
            if (existing != null) {
                player.sendMessage(Component.text("Reconnecting to your personal Earth...", NamedTextColor.YELLOW));
                connectToExistingServer(player, existing);
                return;
            }

            if (!leader.equals(playerId)) {
                player.sendMessage(Component.text("Only the party leader can start the world. Ask them to run /earth.", NamedTextColor.RED));
                return;
            }

            ServerSession warm = adoptWarmSession(leader, members);
            if (warm != null) {
                player.sendMessage(Component.text("🌍 Connecting you to your personal Earth...", NamedTextColor.AQUA));
                logger.info("[Session] Adopting warm server {} for leader {}", warm.name, leader);
                executor.submit(() -> {
                    if (connectLeader(player, warm, true)) {
                        pullPartyMembers(warm);
                    }
                });
                return;
            }

            String name = "voxelearth-" + leader.toString().substring(0, 8);
            int port = 30070 + ThreadLocalRandom.current().nextInt(1000);
            int rconPort = port + 10;
            String rconPass = generateRconPassword();

            ServerSession session = new ServerSession();
            session.name = name;
            session.port = port;
            session.rconPort = rconPort;
            session.rconPass = rconPass;
            session.folder = Paths.get("servers", name);
            session.info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", port));
            session.connecting = true;
            session.leader = leader;
            session.members.addAll(members);

            sessionsByLeader.put(leader, session);
            for (UUID m : members) leaderOfMember.put(m, leader);

            // initialize default settings for this leader
            visitRadius.putIfAbsent(leader, DEFAULT_RADIUS);
            moveRadius.putIfAbsent(leader,  DEFAULT_RADIUS);

            player.sendMessage(Component.text("🌍 Preparing your personal Earth...", NamedTextColor.AQUA));
            logger.info("[Session] Spawning dedicated server {} for leader {} (port {}, RCON {})", name, leader, port, rconPort);
            executor.submit(() -> spawnAndConnectLeaderThenParty(player, session));
        }
    }

    // /lobby
    private class LobbyCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) return;
            Optional<RegisteredServer> lobby = proxy.getServer(LOBBY_NAME);
            if (lobby.isEmpty()) {
                player.sendMessage(Component.text("⚠ Lobby server is not available.", NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text("🏠 Sending you to the lobby...", NamedTextColor.GREEN));
            player.createConnectionRequest(lobby.get()).connectWithIndication();
        }
    }

    // /party
    private class PartyCommand implements SimpleCommand {
        @Override
        public void execute(Invocation in) {
            if (!(in.source() instanceof Player p)) return;
            String[] args = in.arguments();
            if (args.length == 0) {
                p.sendMessage(Component.text("Party: /party create | invite <player> | accept | leave | disband | list", NamedTextColor.YELLOW));
                return;
            }
            switch (args[0].toLowerCase()) {
                case "create" -> {
                    parties.createOrGetParty(p.getUniqueId());
                    p.sendMessage(Component.text("Party created. Invite friends with /party invite <player>.", NamedTextColor.GREEN));
                }
                case "invite" -> {
                    if (args.length < 2) {
                        p.sendMessage(Component.text("Usage: /party invite <player>", NamedTextColor.RED));
                        return;
                    }
                    if (!parties.isLeader(p.getUniqueId())) {
                        p.sendMessage(Component.text("Only the leader can invite.", NamedTextColor.RED));
                        return;
                    }
                    proxy.getPlayer(args[1]).ifPresentOrElse(target -> {
                        boolean ok = parties.invite(p.getUniqueId(), target.getUniqueId());
                        if (ok) {
                            p.sendMessage(Component.text("Invited " + target.getUsername() + " (expires in 5m).", NamedTextColor.GREEN));
                            target.sendMessage(Component.text(p.getUsername() + " invited you to a party. Type /party accept", NamedTextColor.AQUA));
                        } else {
                            p.sendMessage(Component.text("They’re already in your party.", NamedTextColor.YELLOW));
                        }
                    }, () -> p.sendMessage(Component.text("Player not found.", NamedTextColor.RED)));
                }
                case "accept" -> {
                    boolean ok = parties.accept(p.getUniqueId());
                    if (ok) {
                        UUID leader = parties.leaderOf(p.getUniqueId());
                        p.sendMessage(Component.text("You joined the party.", NamedTextColor.GREEN));
                        ServerSession s = sessionsByLeader.get(leader);
                        if (s != null && !s.connecting) {
                            s.members.add(p.getUniqueId());
                            connectToExistingServer(p, s);
                        }
                    } else {
                        p.sendMessage(Component.text("No pending invite.", NamedTextColor.RED));
                    }
                }
                case "leave" -> parties.leave(p.getUniqueId());
                case "disband" -> {
                    if (parties.isLeader(p.getUniqueId())) {
                        ServerSession s = sessionsByLeader.remove(p.getUniqueId());
                        if (s != null) executor.submit(() -> cleanupSession(null, s));
                        parties.disband(p.getUniqueId());
                    } else {
                        p.sendMessage(Component.text("Only the leader can disband.", NamedTextColor.RED));
                    }
                }
                case "list" -> {
                    parties.getPartyOf(p.getUniqueId()).ifPresentOrElse(pt -> {
                        String names = String.join(", ",
                                pt.members.stream()
                                        .map(id -> proxy.getPlayer(id).map(Player::getUsername).orElse(id.toString().substring(0,8)))
                                        .toList());
                        p.sendMessage(Component.text("Party members: " + names, NamedTextColor.GOLD));
                    }, () -> p.sendMessage(Component.text("You’re not in a party.", NamedTextColor.YELLOW)));
                }
                default -> {}
            }
        }
    }

    private class VisitCommand implements SimpleCommand {
        @Override
        public void execute(Invocation in) {
            if (!(in.source() instanceof Player player)) return;
            String[] args = in.arguments();
            if (args.length == 0) {
                player.sendMessage(Component.text("Usage: /visit <address>", NamedTextColor.RED));
                return;
            }
            handleVisitRequest(player, String.join(" ", args));
        }
    }

    private class VisitRadiusCommand implements SimpleCommand {
        @Override
        public void execute(Invocation in) {
            if (!(in.source() instanceof Player player)) return;
            String[] args = in.arguments();
            if (args.length == 0) {
                player.sendMessage(Component.text("Usage: /visitradius <value>", NamedTextColor.RED));
                return;
            }
            try {
                int value = Integer.parseInt(args[0]);
                handleVisitRadiusCommand(player, value);
            } catch (NumberFormatException ex) {
                player.sendMessage(Component.text("Radius must be a whole number.", NamedTextColor.RED));
            }
        }
    }

    private class VisitRadiusOtherCommand implements SimpleCommand {
        @Override
        public void execute(Invocation in) {
            if (!(in.source() instanceof Player player)) return;
            String[] args = in.arguments();
            if (args.length < 2) {
                player.sendMessage(Component.text("Usage: /visitradiusother <player> <value>", NamedTextColor.RED));
                return;
            }
            forwardPlayerCommand(player, leaderFor(player.getUniqueId()), "visitradiusother", args);
        }
    }

    private class MoveRadiusCommand implements SimpleCommand {
        @Override
        public void execute(Invocation in) {
            if (!(in.source() instanceof Player player)) return;
            String[] args = in.arguments();
            if (args.length == 0) {
                player.sendMessage(Component.text("Usage: /moveradius <value>", NamedTextColor.RED));
                return;
            }
            try {
                int value = Integer.parseInt(args[0]);
                handleMoveRadiusCommand(player, value);
            } catch (NumberFormatException ex) {
                player.sendMessage(Component.text("Radius must be a whole number.", NamedTextColor.RED));
            }
        }
    }

    private class MoveRadiusOtherCommand implements SimpleCommand {
        @Override
        public void execute(Invocation in) {
            if (!(in.source() instanceof Player player)) return;
            String[] args = in.arguments();
            if (args.length < 2) {
                player.sendMessage(Component.text("Usage: /moveradiusother <player> <value>", NamedTextColor.RED));
                return;
            }
            forwardPlayerCommand(player, leaderFor(player.getUniqueId()), "moveradiusother", args);
        }
    }

    private class MoveLoadCommand implements SimpleCommand {
        @Override
        public void execute(Invocation in) {
            if (!(in.source() instanceof Player player)) return;
            String[] args = in.arguments();
            handleMoveLoadCommand(player, args);
        }
    }

    private class MoveLoadOtherCommand implements SimpleCommand {
        @Override
        public void execute(Invocation in) {
            if (!(in.source() instanceof Player player)) return;
            String[] args = in.arguments();
            if (args.length < 2) {
                player.sendMessage(Component.text("Usage: /moveloadother <player> <on|off|toggle>", NamedTextColor.RED));
                return;
            }
            forwardPlayerCommand(player, leaderFor(player.getUniqueId()), "moveloadother", args);
        }
    }

    /* ========= GUI callbacks ========= */

    private void handleVisitRequest(Player player, String visitArg) {
        UUID playerId = player.getUniqueId();
        UUID leader = leaderFor(playerId);

        if (visitArg == null || visitArg.isBlank()) {
            player.sendMessage(Component.text("Use ", NamedTextColor.GRAY)
                    .append(Component.text("/visit <address>", NamedTextColor.GREEN))
                    .append(Component.text(" to jump to any location.", NamedTextColor.GRAY)));
            return;
        }

        String cleanedVisit = sanitizeVisitInput(visitArg);
        if (cleanedVisit.isBlank()) {
            player.sendMessage(Component.text("That address looks invalid. Use letters and numbers only.", NamedTextColor.RED));
            return;
        }
        String playerCmd = sanitizeForSpoof("visit " + cleanedVisit);
        if (playerCmd.isBlank()) {
            player.sendMessage(Component.text("That address looks invalid. Use letters and numbers only.", NamedTextColor.RED));
            return;
        }

        ServerSession session = sessionsByLeader.get(leader);
        if (session == null) {
            if (!leader.equals(playerId)) {
                player.sendMessage(Component.text("Ask your party leader to start the world with /earth.", NamedTextColor.RED));
                return;
            }
            Collection<UUID> members = partyMembersFor(leader);
            ServerSession warm = adoptWarmSession(leader, members);
            if (warm != null) {
                player.sendMessage(Component.text("🌍 Connecting you to your personal Earth...", NamedTextColor.AQUA));
                logger.info("[Session] Adopting warm server {} for /visit leader {}", warm.name, leader);
                executor.submit(() -> {
                    if (connectLeader(player, warm, false)) {
                        scheduleBackendCommandAfterConnect(player, warm, playerCmd);
                        pullPartyMembers(warm);
                    }
                });
                return;
            }
            String name = "voxelearth-" + leader.toString().substring(0, 8);
            int port = 30070 + ThreadLocalRandom.current().nextInt(1000);
            int rconPort = port + 10;
            String rconPass = generateRconPassword();

            session = new ServerSession();
            session.name = name;
            session.port = port;
            session.rconPort = rconPort;
            session.rconPass = rconPass;
            session.folder = Paths.get("servers", name);
            session.info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", port));
            session.connecting = true;
            session.leader = leader;
            session.members.addAll(members);

            sessionsByLeader.put(leader, session);
            for (UUID member : members) {
                leaderOfMember.put(member, leader);
            }

            visitRadius.putIfAbsent(leader, DEFAULT_RADIUS);
            moveRadius.putIfAbsent(leader,  DEFAULT_RADIUS);

            player.sendMessage(Component.text("🌍 Spinning up your personal Earth...", NamedTextColor.AQUA));
            logger.info("[Session] Spawning new server {} for /visit leader {} (port {}, RCON {})", name, leader, port, rconPort);
            ServerSession finalSession = session;
            executor.submit(() -> spawnAndConnectThenRun(player, finalSession, playerCmd));
        } else {
            if (!isOnSessionServer(player, session)) {
                connectToExistingServer(player, session);
            }
            scheduleBackendCommandAfterConnect(player, session, playerCmd);
        }
    }

    private void handleSettingsAction(Player player, SettingsAction action) {
        UUID leader = leaderFor(player.getUniqueId());

        switch (action) {
            case VISIT_RADIUS_MINUS -> {
                int v = clamp(visitRadius.getOrDefault(leader, DEFAULT_RADIUS) - RADIUS_STEP);
                if (forwardPlayerCommand(player, leader, "visitradius", String.valueOf(v))) {
                    visitRadius.put(leader, v);
                    player.sendActionBar(Component.text("Visit radius set to " + v + " blocks", NamedTextColor.AQUA));
                }
            }
            case VISIT_RADIUS_PLUS -> {
                int v = clamp(visitRadius.getOrDefault(leader, DEFAULT_RADIUS) + RADIUS_STEP);
                if (forwardPlayerCommand(player, leader, "visitradius", String.valueOf(v))) {
                    visitRadius.put(leader, v);
                    player.sendActionBar(Component.text("Visit radius set to " + v + " blocks", NamedTextColor.AQUA));
                }
            }
            case MOVELOAD_TOGGLE -> forwardPlayerCommand(player, leader, "moveload", "toggle");
            case MOVE_RADIUS_MINUS -> {
                int v = clamp(moveRadius.getOrDefault(leader, DEFAULT_RADIUS) - RADIUS_STEP);
                if (forwardPlayerCommand(player, leader, "moveradius", String.valueOf(v))) {
                    moveRadius.put(leader, v);
                    player.sendActionBar(Component.text("Move radius set to " + v + " blocks", NamedTextColor.AQUA));
                }
            }
            case MOVE_RADIUS_PLUS -> {
                int v = clamp(moveRadius.getOrDefault(leader, DEFAULT_RADIUS) + RADIUS_STEP);
                if (forwardPlayerCommand(player, leader, "moveradius", String.valueOf(v))) {
                    moveRadius.put(leader, v);
                    player.sendActionBar(Component.text("Move radius set to " + v + " blocks", NamedTextColor.AQUA));
                }
            }
            default -> {}
        }
    }

    private void handleVisitRadiusCommand(Player player, int desired) {
        UUID leader = leaderFor(player.getUniqueId());
        int value = clamp(desired);
        if (forwardPlayerCommand(player, leader, "visitradius", String.valueOf(value))) {
            visitRadius.put(leader, value);
            player.sendActionBar(Component.text("Visit radius set to " + value + " blocks", NamedTextColor.AQUA));
        }
    }

    private void handleMoveRadiusCommand(Player player, int desired) {
        UUID leader = leaderFor(player.getUniqueId());
        int value = clamp(desired);
        if (forwardPlayerCommand(player, leader, "moveradius", String.valueOf(value))) {
            moveRadius.put(leader, value);
            player.sendActionBar(Component.text("Move radius set to " + value + " blocks", NamedTextColor.AQUA));
        }
    }

    private void handleMoveLoadCommand(Player player, String[] args) {
        UUID leader = leaderFor(player.getUniqueId());
        String[] payload = (args == null || args.length == 0) ? new String[]{"toggle"} : args;
        forwardPlayerCommand(player, leader, "moveload", payload);
    }

    private UUID leaderFor(UUID playerId) {
        return parties.leaderOf(playerId);
    }

    private Collection<UUID> partyMembersFor(UUID leader) {
        return parties.getPartyOf(leader)
                .map(p -> new ArrayList<>(p.members))
                .orElseGet(() -> {
                    ArrayList<UUID> single = new ArrayList<>();
                    single.add(leader);
                    return single;
                });
    }

    private ServerSession adoptWarmSession(UUID leader, Collection<UUID> members) {
        ServerSession warm = warmPool.poll();
        if (warm == null) {
            return null;
        }

        String oldName = warm.name;
        String newName = "voxelearth-" + leader.toString().substring(0, 8);

        try {
            proxy.unregisterServer(warm.info);
        } catch (Exception ignored) {
        }

        ServerInfo alias = new ServerInfo(newName, warm.info.getAddress());
        warm.name = newName;
        warm.info = alias;
        warm.leader = leader;
        warm.members.clear();
        warm.members.addAll(members);

        proxy.registerServer(alias);

        logger.info("[Warm] Warm session {} renamed and adopted as {} for leader {}", oldName, newName, leader);
        sessionsByLeader.put(leader, warm);
        for (UUID member : members) {
            leaderOfMember.put(member, leader);
        }

        platformInitialized.remove(oldName);
        platformInitialized.add(newName);

        visitRadius.putIfAbsent(leader, DEFAULT_RADIUS);
        moveRadius.putIfAbsent(leader, DEFAULT_RADIUS);

        return warm;
    }

    private boolean forwardPlayerCommand(Player player, UUID leader, String label, String... args) {
        String playerCommand = sanitizeCommandLine(label, args);
        if (playerCommand.isBlank()) {
            player.sendMessage(Component.text("Invalid command parameters.", NamedTextColor.RED));
            return false;
        }
        return forwardCommandToSession(player, leader, playerCommand);
    }

    private boolean forwardCommandToSession(Player player, UUID leader, String playerCommand) {
        UUID resolvedLeader = leader != null ? leader : player.getUniqueId();
        ServerSession session = sessionsByLeader.get(resolvedLeader);
        if (session == null) {
            player.sendMessage(Component.text("Start your world with /earth first.", NamedTextColor.YELLOW));
            return false;
        }

        session.members.add(player.getUniqueId());

        boolean alreadyThere = isOnSessionServer(player, session);
        if (!alreadyThere) {
            connectToExistingServer(player, session);
            if (session.connecting) {
                executor.submit(() -> {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    connectToExistingServer(player, session);
                });
            }
        }

        String safeCommand = sanitizeForSpoof(playerCommand);
        if (safeCommand.isBlank()) {
            player.sendMessage(Component.text("Invalid command parameters.", NamedTextColor.RED));
            return false;
        }
        scheduleBackendCommandAfterConnect(player, session, safeCommand);
        return true;
    }

private ServerSession spawnWarm() {
    ServerSession session = new ServerSession();
    session.name = "voxelearth-warm-" + UUID.randomUUID().toString().substring(0, 4);
    session.port = 30070 + ThreadLocalRandom.current().nextInt(1000);
    session.rconPort = session.port + 10;
    session.rconPass = generateRconPassword();
    session.folder = Paths.get("servers", session.name); // kept for cleanup; Python picks actual on disk
    session.info = new ServerInfo(session.name, new InetSocketAddress("127.0.0.1", session.port));
    session.connecting = true;

    // Resolve absolute paths relative to the proxy’s true runtime CWD
    Path workdir   = Paths.get("").toAbsolutePath();
    Path spawner   = workdir.resolve("spawn_server.py");
    Path template  = workdir.resolve("voxelearth.zip");
    Path baseDir   = workdir.resolve("templates").resolve("voxelearth-base");
    Path spawnLog  = workdir.resolve("spawn_server.log"); // single rolling log is fine; rotate if you prefer

    logger.info("[Warm] Spawning {} on port {} (RCON {})", session.name, session.port, session.rconPort);
    logger.info("[Spawn] workdir={} spawner={} template={} base={}", workdir, spawner, template, baseDir);

    executor.submit(() -> {
        try {
            // Build a fully-qualified command; pass absolute paths so Python never guesses
            ProcessBuilder pb = new ProcessBuilder(
                    "python3", spawner.toString(),
                    "warm",
                    String.valueOf(session.port),
                    "--rcon-port", String.valueOf(session.rconPort),
                    "--rcon-pass", session.rconPass,
                    "--template", template.toString(),
                    "--base-dir", baseDir.toString(),
                    "--empty-world"
            );
            pb.directory(workdir.toFile());               // explicit working dir (critical) :contentReference[oaicite:1]{index=1}
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(spawnLog.toFile()));
            // reduce “buffered output” surprises from Python
            pb.environment().put("PYTHONUNBUFFERED", "1");

            session.process = pb.start();

            // Quickly detect “exited immediately” so failures are obvious in logs
            try {
                Thread.sleep(1500);
                if (!session.process.isAlive()) {
                    int code = session.process.exitValue();
                    logger.warn("[Spawn] {} spawner exited early (code {}). See {}", session.name, code, spawnLog);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (IllegalThreadStateException ignored) {
                // process still alive
            }

            proxy.registerServer(session.info);

            boolean reached = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    proxy.getServer(session.name).orElseThrow().ping().join();
                    reached = true;
                    break;
                } catch (Exception ignored) {
                    try { Thread.sleep(500); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }

            session.connecting = false;

            if (!reached) {
                logger.warn("[Warm] {} did not respond to pings within 60s. Cleaning up. See {}",
                        session.name, spawnLog);
                warmPool.remove(session);
                cleanupSession(null, session);
                return;
            }

            logger.info("[Warm] {} responding to pings", session.name);
            if (platformInitialized.add(session.name)) {
                ensureSpawnPlatformViaRcon(session);
            }

        } catch (Exception e) {
            logger.warn("Warm spawn failed for {}: {}. See {}", session.name, e.toString(), spawnLog);
            warmPool.remove(session);
            cleanupSession(null, session);
        }
    });

    return session;
}


    private void shutdownWarm(ServerSession session) {
        if (session == null) {
            return;
        }
        executor.submit(() -> cleanupSession(null, session));
    }

    private boolean connectLeader(Player leaderPlayer, ServerSession session, boolean sendSuccessMessage) {
        boolean ok = waitForBackendAndConnect(leaderPlayer, session.name, Duration.ofSeconds(90));
        if (!ok) {
            leaderPlayer.sendMessage(Component.text("❌ Failed to connect — server took too long.", NamedTextColor.RED));
            cleanupSession(leaderPlayer, session);
            return false;
        }

        session.connecting = false;
        if (platformInitialized.add(session.name)) {
            ensureSpawnPlatformViaRcon(session);
        }

        ensurePlayerSupported(session, leaderPlayer.getUsername());
        if (sendSuccessMessage) {
            leaderPlayer.sendMessage(Component.text("✅ Connected to your personal Earth!", NamedTextColor.GREEN));
        }
        return true;
    }

    private void pullPartyMembers(ServerSession session) {
        for (UUID memberId : session.members) {
            if (session.leader != null && memberId.equals(session.leader)) {
                continue;
            }
            proxy.getPlayer(memberId).ifPresent(member -> executor.submit(() ->
                    tryConnect(member, session.name, 12, 2_000)
            ));
        }
    }

    private boolean waitForBackendAndConnect(Player player, String serverName, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Optional<RegisteredServer> opt = proxy.getServer(serverName);
                if (opt.isEmpty()) {
                    Thread.sleep(500);
                    continue;
                }
                RegisteredServer server = opt.get();
                server.ping().join();
                boolean alreadyThere = player.getCurrentServer()
                        .map(cs -> cs.getServerInfo().getName().equalsIgnoreCase(serverName))
                        .orElse(false);
                if (!alreadyThere) {
                    player.createConnectionRequest(server).connectWithIndication().join();
                }
                return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception ignored) {
                try {
                    Thread.sleep(750);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private int clamp(int v) { return Math.max(RADIUS_MIN, Math.min(RADIUS_MAX, v)); }

    private String sanitizeVisitInput(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean lastSpace = false;
        for (char c : input.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                lastSpace = false;
            } else if (c == ' ') {
                if (!lastSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastSpace = true;
                }
            } else if (c == ',' || c == '.' || c == '-' || c == '/') {
                sb.append(c);
                lastSpace = false;
            }
        }
        int len = sb.length();
        if (len == 0) {
            return "";
        }
        if (lastSpace) {
            sb.setLength(len - 1);
        }
        return sb.toString().trim();
    }

    private String sanitizeCommandToken(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String sanitizeCommandLine(String label, String... args) {
        String cleanLabel = sanitizeCommandToken(label);
        if (cleanLabel.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add(cleanLabel);
        if (args != null) {
            for (String arg : args) {
                String clean = sanitizeCommandToken(arg);
                if (!clean.isBlank()) {
                    parts.add(clean);
                }
            }
        }
        return String.join(" ", parts);
    }

    private String sanitizeForSpoof(String command) {
        if (command == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean lastSpace = false;
        for (char c : command.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ',' || c == '/') {
                sb.append(c);
                lastSpace = false;
            } else if (c == ' ') {
                if (!lastSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastSpace = true;
                }
            }
        }
        int len = sb.length();
        if (len == 0) {
            return "";
        }
        if (lastSpace) {
            sb.setLength(len - 1);
        }
        return sb.toString().trim();
    }

    private void handlePartyAction(Player p, PartyAction action) {
        switch (action) {
            case CREATE_JOIN -> {
                parties.createOrGetParty(p.getUniqueId());
                p.sendMessage(Component.text("Party ready. Invite friends with /party invite <player>.", NamedTextColor.GREEN));
            }
            case LEAVE -> parties.leave(p.getUniqueId());
            case DISBAND -> {
                if (parties.isLeader(p.getUniqueId())) {
                    ServerSession s = sessionsByLeader.remove(p.getUniqueId());
                    if (s != null) executor.submit(() -> cleanupSession(null, s));
                    parties.disband(p.getUniqueId());
                } else {
                    p.sendMessage(Component.text("Only the leader can disband.", NamedTextColor.RED));
                }
            }
            case INVITE_HELP -> p.sendMessage(Component.text("Use /party invite <player> to invite.", NamedTextColor.YELLOW));
            default -> {}
        }
    }

    /* ========= Spawn/connect ========= */

    private void spawnAndConnectLeaderThenParty(Player leaderPlayer, ServerSession session) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "spawn_server.py",
                    session.leader.toString(),
                    String.valueOf(session.port),
                    "--rcon-port", String.valueOf(session.rconPort),
                    "--rcon-pass", session.rconPass
            );
            pb.redirectErrorStream(true);
            pb.directory(new File("."));
            session.process = pb.start();

            proxy.registerServer(session.info);
            if (!connectLeader(leaderPlayer, session, true)) {
                return;
            }

            logger.info("[Session] Server {} ready for leader {}", session.name, session.leader);
            pullPartyMembers(session);

        } catch (Exception e) {
            logger.error("Failed to spawn dynamic server for {}", leaderPlayer.getUsername(), e);
            leaderPlayer.sendMessage(Component.text("❌ Error while creating your world.", NamedTextColor.RED));
            cleanupSession(leaderPlayer, session);
        }
    }

    private void spawnAndConnectThenRun(Player leaderPlayer, ServerSession session, String playerCmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "spawn_server.py",
                    session.leader.toString(),
                    String.valueOf(session.port),
                    "--rcon-port", String.valueOf(session.rconPort),
                    "--rcon-pass", session.rconPass
            );
            pb.redirectErrorStream(true);
            pb.directory(new File("."));
            session.process = pb.start();

            proxy.registerServer(session.info);

            if (!connectLeader(leaderPlayer, session, false)) {
                return;
            }

            logger.info("[Session] Server {} ready for leader {} (queued command)", session.name, session.leader);
            pullPartyMembers(session);
            scheduleBackendCommandAfterConnect(leaderPlayer, session, playerCmd);

        } catch (Exception e) {
            logger.error("Spawn/connect failed for {}", leaderPlayer.getUsername(), e);
            leaderPlayer.sendMessage(Component.text("❌ Error while creating your world.", NamedTextColor.RED));
            cleanupSession(leaderPlayer, session);
        }
    }

    private boolean tryConnect(Player player, String serverName, int attempts, long backoffMs) {
        for (int i = 0; i < attempts; i++) {
            try {
                Optional<RegisteredServer> target = proxy.getServer(serverName);
                if (target.isEmpty()) { Thread.sleep(backoffMs); continue; }
                // Avoid reconnect if already there
                if (player.getCurrentServer().map(cs -> cs.getServerInfo().getName().equalsIgnoreCase(serverName)).orElse(false)) {
                    return true;
                }
                player.createConnectionRequest(target.get()).connectWithIndication().join();
                player.sendMessage(Component.text("Joined " + serverName, NamedTextColor.GREEN));
                return true;
            } catch (Exception ignored) {
                try { Thread.sleep(backoffMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        return false;
    }

    private boolean isOnSessionServer(Player player, ServerSession session) {
        return player.getCurrentServer()
                .map(cs -> cs.getServerInfo().getName().equalsIgnoreCase(session.name))
                .orElse(false);
    }

    private void connectToExistingServer(Player player, ServerSession session) {
        if (session.connecting) {
            player.sendMessage(Component.text("⏳ Your personal Earth is still starting up...", NamedTextColor.YELLOW));
            return;
        }
        // Skip reconnect if already there (prevents "You are already connected" spam)
        if (isOnSessionServer(player, session)) return;

        proxy.getServer(session.name).ifPresent(server ->
                player.createConnectionRequest(server).connectWithIndication()
        );
    }

    private void scheduleBackendCommandAfterConnect(Player player, ServerSession session, String playerCommand) {
        executor.submit(() -> {
            try {
                Thread.sleep(4_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }

            if (!waitForPlayerOnServer(player, session, Duration.ofSeconds(45))) {
                logger.warn("Player {} did not reach {} in time; showing fallback", player.getUsername(), session.name);
                showClickableFallback(player, playerCommand);
                return;
            }

        String safeCommand = sanitizeForSpoof(playerCommand);
        if (safeCommand.isBlank()) {
        logger.warn("Sanitized command for {} came back empty; showing fallback", player.getUsername());
        showClickableFallback(player, playerCommand);
        return;
        }

        String chatCommand = "/" + safeCommand;
        UUID playerId = player.getUniqueId();
        proxy.getScheduler().buildTask(this, () ->
            proxy.getPlayer(playerId).ifPresentOrElse(
                target -> target.spoofChatInput(chatCommand),
                () -> logger.warn("Player {} went offline before spoofing {}", playerId, chatCommand)
            )
        ).schedule();
        });
    }

    private boolean waitForPlayerOnServer(Player player, ServerSession session, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isOnSessionServer(player, session)) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean waitForRcon(ServerSession session, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long backoff = 150;
        while (System.nanoTime() < deadline) {
            try (RconClient rc = new RconClient("127.0.0.1", session.rconPort, session.rconPass)) {
                rc.connect();
                rc.command("list");
                return true;
            } catch (Exception e) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                backoff = Math.min(1_500, backoff * 2);
            }
        }
        return false;
    }

    private boolean waitForPlayerEntity(ServerSession session, String playerName, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long backoff = 200;
        String command = "data get entity " + playerName + " Pos[0]";
        while (System.nanoTime() < deadline) {
            try (RconClient rc = new RconClient("127.0.0.1", session.rconPort, session.rconPass)) {
                rc.connect();
                String response = rc.command(command);
                if (response != null && !response.toLowerCase(Locale.ROOT).contains("no entity")) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
            backoff = Math.min(2_000, backoff + 200);
        }
        return false;
    }

    private boolean sendRconWithRetry(ServerSession session, String consoleCommand, int attempts, long firstBackoffMs) {
        if (session.rconPort <= 0 || session.rconPass == null) {
            return false;
        }

        long backoff = firstBackoffMs;
        for (int i = 0; i < attempts; i++) {
            try (RconClient rc = new RconClient("127.0.0.1", session.rconPort, session.rconPass)) {
                rc.connect();
                rc.command(consoleCommand);
                return true;
            } catch (Exception e) {
                if (i == attempts - 1) {
                    break;
                }
                long jitter = ThreadLocalRandom.current().nextInt(60);
                try {
                    Thread.sleep(backoff + jitter);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                backoff = Math.min(2_000, backoff * 2);
            }
        }
        return false;
    }

    private void showClickableFallback(Player player, String playerCommand) {
        String safeCommand = sanitizeForSpoof(playerCommand);
        if (safeCommand.isBlank()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        proxy.getScheduler().buildTask(this, () ->
                proxy.getPlayer(playerId).ifPresent(target ->
                        target.sendMessage(
                                Component.text("Command queued. ", NamedTextColor.YELLOW)
                                        .append(Component.text("[Click if nothing happens]", NamedTextColor.AQUA)
                                                .clickEvent(ClickEvent.runCommand("/" + safeCommand)))))
        ).schedule();
    }

    private static String generateRconPassword() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void ensureSpawnPlatformViaRcon(ServerSession session) {
        int attempts = 3;
        long backoff = 500;
        for (int i = 1; i <= attempts; i++) {
            if (!waitForRcon(session, Duration.ofSeconds(30))) {
                logger.warn("Unable to initialize platform for {} — RCON unavailable (attempt {}/{})", session.name, i, attempts);
            } else {
                boolean fill = sendRconWithRetry(session, "fill -5 180 -5 5 180 5 glass", 12, 200);
                boolean spawn = sendRconWithRetry(session, "setworldspawn 0 181 0", 12, 200);
                if (fill && spawn) {
                    logger.info("[Platform] Spawn platform initialized for {}", session.name);
                    return;
                }
                logger.warn("Spawn platform commands failed for {} on attempt {}/{}", session.name, i, attempts);
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff *= 2;
        }
        logger.warn("Failed to initialize spawn platform for {} after {} attempts", session.name, attempts);
    }

    private void ensurePlayerSupported(ServerSession session, String playerName) {
        if (!waitForRcon(session, Duration.ofSeconds(30))) {
            logger.warn("Unable to ensure support for {} on {} — RCON unavailable", playerName, session.name);
            return;
        }

        if (!waitForPlayerEntity(session, playerName, Duration.ofSeconds(20))) {
            logger.warn("Entity for {} not ready on {} when attempting support", playerName, session.name);
            return;
        }

        boolean support = sendRconWithRetry(session,
                "execute as " + playerName + " at @s if block ~ ~-1 ~ air run setblock ~ ~-1 ~ glass",
                6,
                200);
        boolean nudge = sendRconWithRetry(session,
                "execute as " + playerName + " at @s run tp @s ~ ~0.2 ~",
                6,
                200);
        if (!support || !nudge) {
            logger.warn("Support commands for {} on {} did not complete", playerName, session.name);
        }
    }

    /* ========= Welcome / cleanup ========= */

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();

        // (Re)give the client-side Navigator item (slot 9) with retries to beat backend inventory sync
        if (nav != null) nav.giveWithRetries(player);

        if (serverName.equalsIgnoreCase(LOBBY_NAME)) {
            player.sendMessage(
                    Component.text("🌎 Welcome to ", NamedTextColor.GOLD)
                            .append(Component.text("Voxel Earth", NamedTextColor.AQUA))
                            .append(Component.text("!")).append(Component.newline())
                            .append(Component.text("• Right-click the Nether Star in slot 9 to open the Navigator.", NamedTextColor.GRAY)).append(Component.newline())
                            .append(Component.text("• Use ", NamedTextColor.GRAY))
                            .append(Component.text("/earth ", NamedTextColor.GREEN))
                            .append(Component.text("to create or join your personal Earth.")).append(Component.newline())
                            .append(Component.text("• Use ", NamedTextColor.GRAY))
                            .append(Component.text("/party ", NamedTextColor.GREEN))
                            .append(Component.text("to create/invite friends.")).append(Component.newline())
                            .append(Component.text("• Use ", NamedTextColor.GRAY))
                            .append(Component.text("/lobby ", NamedTextColor.GREEN))
                            .append(Component.text("anytime to return here.", NamedTextColor.GRAY))
            );
        } else if (serverName.startsWith("voxelearth-")) {
            // place support under this player too (handles direct joins and party pulls)
            sessionsByLeader.values().stream()
                    .filter(s -> s.name.equalsIgnoreCase(serverName))
                    .findFirst()
                    .ifPresent(s -> executor.submit(() -> {
                        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                        ensurePlayerSupported(s, player.getUsername());
                    }));

            player.sendMessage(
                    Component.text("🌍 Welcome to your ", NamedTextColor.GOLD)
                            .append(Component.text("personal Earth!", NamedTextColor.AQUA))
                            .append(Component.newline())
                            .append(Component.text("• Right-click the Nether Star in slot 9 to open the Navigator.", NamedTextColor.GRAY)).append(Component.newline())
                            .append(Component.text("• Commands you can use here: ", NamedTextColor.GRAY))
                            .append(Component.text("/visit <address>, /visitradius <value>, /moveload, /moveradius <value>", NamedTextColor.GREEN)).append(Component.newline())
                            .append(Component.text("• Use ", NamedTextColor.GRAY))
                            .append(Component.text("/lobby ", NamedTextColor.GREEN))
                            .append(Component.text("to return to the main lobby at any time.", NamedTextColor.GRAY))
            );
        }
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        UUID leader = leaderOfMember.getOrDefault(playerId, playerId);
        ServerSession session = sessionsByLeader.get(leader);
        if (session == null) return;

        boolean someoneOnline = session.members.stream().anyMatch(id -> proxy.getPlayer(id).isPresent());
        if (!someoneOnline) {
            logger.info("Cleaning up dynamic server for party leader {}", leader);
            sessionsByLeader.remove(leader);
            executor.submit(() -> cleanupSession(player, session));
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Proxy shutdown detected. Stopping warm pool and cleaning dynamic servers.");
        try {
            warmKeeper.shutdownNow();
        } catch (Exception ex) {
            logger.warn("Warm pool scheduler shutdown encountered an issue", ex);
        }

        cleanupAllSessions();

        executor.shutdownNow();
        clearServersDirectory();
    }

    private void cleanupSession(Player player, ServerSession session) {
        try {
            if (session.process != null && session.process.isAlive()) {
                session.process.destroy();
                session.process.waitFor(5, TimeUnit.SECONDS);
                if (session.process.isAlive()) session.process.destroyForcibly();
            }
            proxy.unregisterServer(session.info);

            if (Files.exists(session.folder)) {
                logger.info("Deleting folder {}", session.folder);
                Files.walk(session.folder)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (Exception e) {
            logger.error("Error cleaning up server {}", session.name, e);
        } finally {
            for (UUID m : session.members) leaderOfMember.remove(m);
            platformInitialized.remove(session.name);
            if (session.leader != null) {
                sessionsByLeader.remove(session.leader, session);
                visitRadius.remove(session.leader);
                moveRadius.remove(session.leader);
            }
            warmPool.remove(session);
        }
    }

    private void cleanupAllSessions() {
        Set<ServerSession> unique = Collections.newSetFromMap(new ConcurrentHashMap<>());
        unique.addAll(sessionsByLeader.values());
        unique.addAll(warmPool);

        for (ServerSession session : unique) {
            if (session == null) {
                continue;
            }
            try {
                cleanupSession(null, session);
            } catch (Exception ex) {
                logger.warn("Error while cleaning session {} during shutdown", session.name, ex);
            }
        }

        warmPool.clear();
        sessionsByLeader.clear();
        leaderOfMember.clear();
        visitRadius.clear();
        moveRadius.clear();
        platformInitialized.clear();
    }

    private void clearServersDirectory() {
        Path root = Paths.get("servers");
        if (!Files.exists(root)) {
            return;
        }

        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            logger.warn("Failed to delete {} during shutdown cleanup", path, ex);
                        }
                    });
        } catch (IOException ex) {
            logger.warn("Unable to walk servers directory for cleanup", ex);
        }

        try {
            if (Files.exists(root)) {
                Files.deleteIfExists(root);
            }
            Files.createDirectories(root);
        } catch (IOException ex) {
            logger.warn("Unable to reset servers directory after cleanup", ex);
        }
    }
}
