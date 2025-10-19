package voxelearth.dynamicloader;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * DynamicLoader - handles player world creation, cleanup, and movement between lobby & instances.
 */
@Plugin(
        id = "dynamicloader",
        name = "DynamicLoader",
        version = "1.3",
        description = "Spawns per-player servers and provides world/lobby management."
)
public class DynamicLoader {

    private final ProxyServer proxy;
    private final ComponentLogger logger;
    private final Map<UUID, ServerSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final String LOBBY_NAME = "lobby";

    @Inject
    public DynamicLoader(ProxyServer proxy, ComponentLogger logger) {
        this.proxy = proxy;
        this.logger = logger;

        proxy.getCommandManager().register("earth", new VoxelearthCommand());
        proxy.getCommandManager().register("lobby", new LobbyCommand());
    }

    private static class ServerSession {
        String name;
        int port;
        Process process;
        Path folder;
        ServerInfo info;
        boolean connecting = false;
    }

    // === COMMAND: /earth ===
    private class VoxelearthCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) return;

            UUID uuid = player.getUniqueId();

            if (sessions.containsKey(uuid)) {
                ServerSession existing = sessions.get(uuid);
                player.sendMessage(Component.text("Reconnecting to your world...", NamedTextColor.YELLOW));
                connectToExistingServer(player, existing);
                return;
            }

            String name = "voxelearth-" + uuid.toString().substring(0, 8);
            int port = 30070 + new Random().nextInt(1000);

            ServerSession session = new ServerSession();
            session.name = name;
            session.port = port;
            session.folder = Paths.get("servers", name);
            session.info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", port));
            session.connecting = true;
            sessions.put(uuid, session);

            player.sendMessage(Component.text("🌍 Preparing your Earth instance...", NamedTextColor.AQUA));
            executor.submit(() -> spawnAndConnect(player, session));
        }
    }

    // === COMMAND: /lobby ===
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

    // === SPAWNING LOGIC ===
    private void spawnAndConnect(Player player, ServerSession session) {
        try {
            // Start spawn script
            ProcessBuilder pb = new ProcessBuilder("python3", "spawn_server.py",
                    player.getUniqueId().toString(),
                    String.valueOf(session.port));
            pb.redirectErrorStream(true);
            pb.directory(new File("."));
            Process process = pb.start();
            session.process = process;

            // Run chmod for cuda_voxelizer inside folder before server starts
            executor.submit(() -> {
                try {
                    Path voxelizer = session.folder.resolve("cuda_voxelizer");
                    if (Files.exists(voxelizer)) {
                        new ProcessBuilder("chmod", "777", voxelizer.toString()).start().waitFor();
                    }
                } catch (Exception e) {
                    logger.warn("Failed to chmod cuda_voxelizer for {}", player.getUsername());
                }
            });

            proxy.registerServer(session.info);

            // Simulate loading bar
            for (int i = 0; i <= 20; i++) {
                player.sendActionBar(Component.text("Loading [" + "=".repeat(i) + " ".repeat(20 - i) + "]", NamedTextColor.GREEN));
                Thread.sleep(1000);
            }

            Optional<RegisteredServer> target = proxy.getServer(session.name);
            boolean connected = false;

            for (int i = 0; i < 30; i++) {
                try {
                    player.createConnectionRequest(target.get()).connectWithIndication().join();
                    connected = true;
                    player.sendMessage(Component.text("✅ Connected to your personal Earth!", NamedTextColor.GREEN));
                    session.connecting = false;
                    break;
                } catch (Exception e) {
                    Thread.sleep(10000);
                }
            }

            if (!connected) {
                player.sendMessage(Component.text("❌ Failed to connect — server took too long.", NamedTextColor.RED));
                cleanupSession(player, session);
            }

        } catch (Exception e) {
            logger.error("Failed to spawn dynamic server for " + player.getUsername(), e);
            player.sendMessage(Component.text("❌ Error while creating your world.", NamedTextColor.RED));
            cleanupSession(player, session);
        }
    }

    private void connectToExistingServer(Player player, ServerSession session) {
        if (session.connecting) {
            player.sendMessage(Component.text("⏳ Your world is still starting up...", NamedTextColor.YELLOW));
            return;
        }
        proxy.getServer(session.name).ifPresent(server ->
                player.createConnectionRequest(server).connectWithIndication()
        );
    }

    // === PLAYER WELCOME MESSAGE (Lobby + Personal World) ===
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();

        if (serverName.equalsIgnoreCase(LOBBY_NAME)) {
            player.sendMessage(
                    Component.text("🌎 Welcome to ", NamedTextColor.GOLD)
                            .append(Component.text("Voxel Earth", NamedTextColor.AQUA))
                            .append(Component.text("!")).append(Component.newline())
                            .append(Component.text("• Explore portals around the lobby to visit worlds!", NamedTextColor.GRAY)).append(Component.newline())
                            .append(Component.text("• Use ", NamedTextColor.GRAY))
                            .append(Component.text("/earth ", NamedTextColor.GREEN))
                            .append(Component.text("to create or join your personal Earth.")).append(Component.newline())
                            .append(Component.text("• Use ", NamedTextColor.GRAY))
                            .append(Component.text("/lobby ", NamedTextColor.GREEN))
                            .append(Component.text("anytime to return here.", NamedTextColor.GRAY))
            );
        } else if (serverName.startsWith("voxelearth-")) {
            player.sendMessage(
                    Component.text("🌍 Welcome to your ", NamedTextColor.GOLD)
                            .append(Component.text("personal Earth!", NamedTextColor.AQUA))
                            .append(Component.newline())
                            .append(Component.text("• Use ", NamedTextColor.GRAY))
                            .append(Component.text("/visit <location> ", NamedTextColor.GREEN))
                            .append(Component.text("to explore other regions.")).append(Component.newline())
                            .append(Component.text("• Use ", NamedTextColor.GRAY))
                            .append(Component.text("/visitradius <x,y> ", NamedTextColor.GREEN))
                            .append(Component.text("to change area size.")).append(Component.newline())
                            .append(Component.text("• Use ", NamedTextColor.GRAY))
                            .append(Component.text("/moveload on/off ", NamedTextColor.GREEN))
                            .append(Component.text("to toggle auto-loading.")).append(Component.newline())
                            .append(Component.text("• Use ", NamedTextColor.GRAY))
                            .append(Component.text("/moveradius <x,y> ", NamedTextColor.GREEN))
                            .append(Component.text("to set your move load radius.")).append(Component.newline())
                            .append(Component.text("• Use ", NamedTextColor.GRAY))
                            .append(Component.text("/lobby ", NamedTextColor.GREEN))
                            .append(Component.text("to return to the main lobby.", NamedTextColor.GRAY))
            );
        }
    }

    // === CLEANUP ===
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ServerSession session = sessions.remove(uuid);
        if (session == null) return;

        logger.info("Cleaning up dynamic server for {}", player.getUsername());
        executor.submit(() -> cleanupSession(player, session));
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
            logger.error("Error cleaning up server for " + player.getUsername(), e);
        }
    }
}
