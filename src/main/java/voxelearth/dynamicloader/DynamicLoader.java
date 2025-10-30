package voxelearth.dynamicloader;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import voxelearth.dynamicloader.PartyManager.Party;
import voxelearth.dynamicloader.net.RconClient;
import voxelearth.dynamicloader.ui.NavigatorUI;
import voxelearth.dynamicloader.ui.NavigatorUI.FamousPlace;
import voxelearth.dynamicloader.ui.NavigatorUI.PartyAction;
import voxelearth.dynamicloader.ui.NavigatorUI.SettingsAction;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.*;
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
    private static final int RADIUS_MIN     = 64;
    private static final int RADIUS_MAX     = 1024;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private static final String LOBBY_NAME = "lobby";

    private final PartyManager parties;
    private final Set<String> platformInitialized = ConcurrentHashMap.newKeySet();

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
    }

    /** Initialize Protocolize-driven UI after the proxy + dependencies are ready. */
    @Subscribe
    public void onProxyInit(ProxyInitializeEvent e) {
        if (!protocolizeAvailable()) {
            logger.error("Protocolize not found. Install protocolize-velocity (2.4.x) into the /plugins folder.");
            return;
        }
        this.nav = new NavigatorUI(
                proxy,
                List.of(
            new FamousPlace("Great Pyramids of Giza (Egypt)",      "great pyramids of giza egypt"),
            new FamousPlace("Eiffel Tower (Paris, France)",         "eiffel tower paris"),
            new FamousPlace("Statue of Liberty (NYC, USA)",         "statue of liberty new york"),
            new FamousPlace("Taj Mahal (Agra, India)",              "taj mahal agra"),
            new FamousPlace("Sydney Opera House (Australia)",       "sydney opera house"),
            new FamousPlace("Christ the Redeemer (Rio, Brazil)",    "christ the redeemer rio de janeiro"),
            new FamousPlace("Mount Everest Base Camp (Nepal)",      "everest base camp nepal"),
            new FamousPlace("Grand Canyon South Rim (USA)",         "grand canyon south rim"),
            new FamousPlace("Great Wall (Mutianyu, China)",         "great wall mutianyu"),
            new FamousPlace("Colosseum (Rome, Italy)",              "colosseum rome"),
            new FamousPlace("Machu Picchu (Peru)",                  "machu picchu"),
            new FamousPlace("Burj Khalifa (Dubai, UAE)",            "burj khalifa dubai"),
            new FamousPlace("Golden Gate Bridge (San Francisco)",   "golden gate bridge san francisco"),
            new FamousPlace("Big Ben (London, UK)",                 "big ben london"),
            new FamousPlace("Niagara Falls (USA/Canada)",           "niagara falls"),
            new FamousPlace("Santorini – Oia (Greece)",             "oia santorini"),
            new FamousPlace("Petra (Jordan)",                       "petra jordan"),
            new FamousPlace("Angkor Wat (Siem Reap, Cambodia)",     "angkor wat siem reap")
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
            p.sendMessage(Component.text("• /visit <place>  • /visitradius <x,y>  • /moveload on|off  • /moveradius <x,y>", NamedTextColor.GRAY));
        }
    }

    // /earth
    private class VoxelearthCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) return;
            UUID playerId = player.getUniqueId();

            UUID leader = parties.isLeader(playerId) ? playerId : parties.leaderOf(playerId);
            Party party = parties.getPartyOf(playerId).orElseGet(() -> parties.createOrGetParty(playerId));

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
            session.members.addAll(party.members);

            sessionsByLeader.put(leader, session);
            for (UUID m : party.members) leaderOfMember.put(m, leader);

            // initialize default settings for this leader
            visitRadius.putIfAbsent(leader, DEFAULT_RADIUS);
            moveRadius.putIfAbsent(leader,  DEFAULT_RADIUS);

            player.sendMessage(Component.text("🌍 Preparing your personal Earth...", NamedTextColor.AQUA));
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

    /* ========= GUI callbacks ========= */

    private void handleVisitRequest(Player player, String visitArg) {
        UUID playerId = player.getUniqueId();
        UUID leader = parties.isLeader(playerId) ? playerId : parties.leaderOf(playerId);

        ServerSession session = sessionsByLeader.get(leader);
        if (session == null) {
            if (!leader.equals(playerId)) {
                player.sendMessage(Component.text("Ask your party leader to start the world with /earth.", NamedTextColor.RED));
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
            session.members.add(playerId);

            sessionsByLeader.put(leader, session);
            leaderOfMember.put(playerId, leader);

            visitRadius.putIfAbsent(leader, DEFAULT_RADIUS);
            moveRadius.putIfAbsent(leader,  DEFAULT_RADIUS);

            player.sendMessage(Component.text("🌍 Spinning up your personal Earth...", NamedTextColor.AQUA));
            ServerSession finalSession = session;
            String user = player.getUsername();
            String quoted = visitArg.indexOf(' ') >= 0 ? "\"" + visitArg.replace("\"", "\\\"") + "\"" : visitArg;
            String consoleCmd = "visitother " + user + " " + quoted;
            String playerCmd  = "visitother " + user + " " + quoted;
            executor.submit(() -> spawnAndConnectThenRun(player, finalSession, consoleCmd, playerCmd));
        } else {
            if (!isOnSessionServer(player, session)) {
                connectToExistingServer(player, session);
            }
            String user = player.getUsername();
            String quoted = visitArg.indexOf(' ') >= 0 ? "\"" + visitArg.replace("\"", "\\\"") + "\"" : visitArg;
            scheduleBackendCommandAfterConnect(player, session,
                    "visitother " + user + " " + quoted,
                    "visitother " + user + " " + quoted);
        }
    }

    private void handleSettingsAction(Player player, SettingsAction action) {
        UUID leader = parties.isLeader(player.getUniqueId()) ? player.getUniqueId() : parties.leaderOf(player.getUniqueId());
        ServerSession session = sessionsByLeader.get(leader);
        if (session == null) {
            player.sendMessage(Component.text("Start your world with /earth first.", NamedTextColor.YELLOW));
            return;
        }

        String consoleCmd = null, playerCmd = null;

        switch (action) {
            case VISIT_RADIUS_MINUS -> {
                int v = clamp(visitRadius.getOrDefault(leader, DEFAULT_RADIUS) - RADIUS_STEP);
                visitRadius.put(leader, v);
                consoleCmd = "execute as " + player.getUsername() + " run visitradius " + v + "," + v;
                playerCmd  = "visitradius " + v + "," + v;
                player.sendActionBar(Component.text("Visit radius set to " + v + "x" + v, NamedTextColor.AQUA));
            }
            case VISIT_RADIUS_PLUS -> {
                int v = clamp(visitRadius.getOrDefault(leader, DEFAULT_RADIUS) + RADIUS_STEP);
                visitRadius.put(leader, v);
                consoleCmd = "execute as " + player.getUsername() + " run visitradius " + v + "," + v;
                playerCmd  = "visitradius " + v + "," + v;
                player.sendActionBar(Component.text("Visit radius set to " + v + "x" + v, NamedTextColor.AQUA));
            }
            case MOVELOAD_TOGGLE -> {
                consoleCmd = "execute as " + player.getUsername() + " run moveload toggle";
                playerCmd  = "moveload toggle";
            }
            case MOVE_RADIUS_MINUS -> {
                int v = clamp(moveRadius.getOrDefault(leader, DEFAULT_RADIUS) - RADIUS_STEP);
                moveRadius.put(leader, v);
                consoleCmd = "execute as " + player.getUsername() + " run moveradius " + v + "," + v;
                playerCmd  = "moveradius " + v + "," + v;
                player.sendActionBar(Component.text("Move radius set to " + v + "x" + v, NamedTextColor.AQUA));
            }
            case MOVE_RADIUS_PLUS -> {
                int v = clamp(moveRadius.getOrDefault(leader, DEFAULT_RADIUS) + RADIUS_STEP);
                moveRadius.put(leader, v);
                consoleCmd = "execute as " + player.getUsername() + " run moveradius " + v + "," + v;
                playerCmd  = "moveradius " + v + "," + v;
                player.sendActionBar(Component.text("Move radius set to " + v + "x" + v, NamedTextColor.AQUA));
            }
            default -> {}
        }

        if (consoleCmd != null) {
            if (!isOnSessionServer(player, session)) {
                connectToExistingServer(player, session);
            }
            scheduleBackendCommandAfterConnect(player, session, consoleCmd, playerCmd);
        }
    }

    private int clamp(int v) { return Math.max(RADIUS_MIN, Math.min(RADIUS_MAX, v)); }

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

            // Fake loading bar for ~30 seconds
            showLoadingBar(leaderPlayer, 30);

            // Then try connecting every 10 seconds
            boolean ok = tryConnect(leaderPlayer, session.name, /*attempts*/24, /*backoffMs*/10_000);
            if (!ok) {
                leaderPlayer.sendMessage(Component.text("❌ Failed to connect — server took too long.", NamedTextColor.RED));
                cleanupSession(leaderPlayer, session);
                return;
            }
            session.connecting = false;

            if (platformInitialized.add(session.name)) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                ensureSpawnPlatformViaRcon(session);
            }

            // Ensure safe footing for the leader immediately upon join
            ensurePlayerSupported(session, leaderPlayer.getUsername());

            for (UUID memberId : session.members) {
                if (memberId.equals(session.leader)) continue;
                proxy.getPlayer(memberId).ifPresent(m -> {
                    tryConnect(m, session.name, 12, 10_000);
                });
                Thread.sleep(150);
            }

            leaderPlayer.sendMessage(Component.text("✅ Connected to your personal Earth!", NamedTextColor.GREEN));

        } catch (Exception e) {
            logger.error("Failed to spawn dynamic server for {}", leaderPlayer.getUsername(), e);
            leaderPlayer.sendMessage(Component.text("❌ Error while creating your world.", NamedTextColor.RED));
            cleanupSession(leaderPlayer, session);
        }
    }

    private void spawnAndConnectThenRun(Player leaderPlayer, ServerSession session, String consoleCmd, String playerCmd) {
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

            // Fake loading bar for ~30 seconds
            showLoadingBar(leaderPlayer, 30);

            // Then try connecting every 10 seconds
            boolean ok = tryConnect(leaderPlayer, session.name, 24, 10_000);
            if (!ok) {
                leaderPlayer.sendMessage(Component.text("❌ Failed to connect — server took too long.", NamedTextColor.RED));
                cleanupSession(leaderPlayer, session);
                return;
            }
            session.connecting = false;

            if (platformInitialized.add(session.name)) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                ensureSpawnPlatformViaRcon(session);
            }

            // ensure safe footing and then attempt action
            ensurePlayerSupported(session, leaderPlayer.getUsername());
            scheduleBackendCommandAfterConnect(leaderPlayer, session, consoleCmd, playerCmd);

        } catch (Exception e) {
            logger.error("Spawn/connect failed for {}", leaderPlayer.getUsername(), e);
            leaderPlayer.sendMessage(Component.text("❌ Error while creating your world.", NamedTextColor.RED));
            cleanupSession(leaderPlayer, session);
        }
    }

    private void showLoadingBar(Player player, int seconds) throws InterruptedException {
        final int total = seconds;
        for (int i = 0; i <= total; i++) {
            int filled = i;
            int empty  = total - i;
            String bar = "Loading [" + "=".repeat(filled) + " ".repeat(Math.max(0, empty)) + "]";
            if (player.isActive()) player.sendActionBar(Component.text(bar, NamedTextColor.GREEN));
            Thread.sleep(1000);
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

    private void scheduleBackendCommandAfterConnect(Player player, ServerSession session, String consoleCommand, String playerCommand) {
        executor.submit(() -> {
            try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
            runRconOrFallback(player, session, consoleCommand, playerCommand);
        });
    }

    private void runRconOrFallback(Player player, ServerSession session, String consoleCommand, String playerCommand) {
        logger.info("RCON-> {}:{} :: {}", session.name, session.rconPort, consoleCommand);
        boolean wrote = false;
        if (session.rconPort > 0 && session.rconPass != null) {
            try (RconClient rc = new RconClient("127.0.0.1", session.rconPort, session.rconPass)) {
                rc.connect();
                rc.commandNoReply(consoleCommand);
                wrote = true;
            } catch (Exception e) {
                logger.warn("RCON error {}:{} :: {}", session.name, session.rconPort, e.toString());
            }
        }
        // Always provide a fallback click so players can retrigger if the backend lags.
        // player.sendMessage(
        //         Component.text("Teleporting… ", NamedTextColor.YELLOW)
        //                 .append(Component.text("[Click if not teleported in ~10s]", NamedTextColor.AQUA)
        //                         .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/" + playerCommand))))
        // ;

        if (wrote) {
            logger.info("RCON write succeeded (no reply expected).");
        }
    }

    private static String generateRconPassword() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void ensureSpawnPlatformViaRcon(ServerSession s) {
        try (RconClient rc = new RconClient("127.0.0.1", s.rconPort, s.rconPass)) {
            rc.connect();
            rc.command("fill -5 180 -5 5 180 5 glass");
            rc.command("setworldspawn 0 181 0");
        } catch (Exception e) {
            logger.warn("Could not initialize spawn platform for {}: {}", s.name, e.toString());
        }
    }

    private void ensurePlayerSupported(ServerSession s, String playerName) {
        try (RconClient rc = new RconClient("127.0.0.1", s.rconPort, s.rconPass)) {
            rc.connect();
            // place a block under the player if needed, then nudge them up 0.2
            rc.command("execute as " + playerName + " at @s if block ~ ~-1 ~ air run setblock ~ ~-1 ~ glass");
            rc.command("execute as " + playerName + " at @s run tp @s ~ ~0.2 ~");
        } catch (Exception e) {
            logger.warn("Could not ensure support for {} in {}: {}", playerName, s.name, e.toString());
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
                            .append(Component.text("/visit, /visitradius, /moveload, /moveradius", NamedTextColor.GREEN)).append(Component.newline())
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
            visitRadius.remove(session.leader);
            moveRadius.remove(session.leader);
        }
    }
}
