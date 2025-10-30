package voxelearth.dynamicloader;

import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PartyManager {

    public static class Party {
        public final UUID leader;
        public final Set<UUID> members = ConcurrentHashMap.newKeySet(); // includes leader
        Party(UUID leader) { this.leader = leader; this.members.add(leader); }
    }

    static class PendingInvite {
        final UUID leader;
        final Instant expiresAt;
        PendingInvite(UUID leader, Instant expiresAt) { this.leader = leader; this.expiresAt = expiresAt; }
        boolean expired() { return Instant.now().isAfter(expiresAt); }
    }

    private final ProxyServer proxy;
    private final Map<UUID, Party> partiesByLeader = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> leaderByMember = new ConcurrentHashMap<>();
    private final Map<UUID, PendingInvite> invitesByTarget = new ConcurrentHashMap<>();
    private static final Duration INVITE_TTL = Duration.ofMinutes(5);

    public PartyManager(ProxyServer proxy) { this.proxy = proxy; }

    public Optional<Party> getPartyOf(UUID playerId) {
        UUID leader = leaderByMember.getOrDefault(playerId, playerId);
        Party p = partiesByLeader.get(leader);
        if (p == null) return Optional.empty();
        return Optional.of(p);
    }

    public boolean isLeader(UUID playerId) {
        Party p = getPartyOf(playerId).orElse(null);
        return p != null && p.leader.equals(playerId);
    }

    public Party createOrGetParty(UUID leader) {
        return partiesByLeader.computeIfAbsent(leader, Party::new);
    }

    public void disband(UUID leader) {
        Party p = partiesByLeader.remove(leader);
        if (p == null) return;
        for (UUID m : p.members) {
            leaderByMember.remove(m);
            proxy.getPlayer(m).ifPresent(pl ->
                    pl.sendMessage(Component.text("Party disbanded.", NamedTextColor.YELLOW)));
        }
    }

    public void leave(UUID playerId) {
        Optional<Party> opt = getPartyOf(playerId);
        if (opt.isEmpty()) return;
        Party p = opt.get();
        if (p.leader.equals(playerId)) {
            disband(playerId);
        } else {
            p.members.remove(playerId);
            leaderByMember.remove(playerId);
            proxy.getPlayer(playerId).ifPresent(pl ->
                    pl.sendMessage(Component.text("You left the party.", NamedTextColor.YELLOW)));
        }
    }

    public boolean invite(UUID leader, UUID target) {
        Party p = createOrGetParty(leader);
        if (p.members.contains(target)) return false;
        invitesByTarget.put(target, new PendingInvite(leader, Instant.now().plus(INVITE_TTL)));
        return true;
    }

    public boolean accept(UUID target) {
        PendingInvite inv = invitesByTarget.remove(target);
        if (inv == null || inv.expired()) return false;
        Party p = createOrGetParty(inv.leader);
        p.members.add(target);
        leaderByMember.put(target, inv.leader);
        return true;
    }

    public UUID leaderOf(UUID member) {
        return leaderByMember.getOrDefault(member, member);
    }
}
