package dev.zwo.modpanel.paper;

import dev.zwo.modpanel.util.Ips;
import io.papermc.paper.ban.BanListType;
import org.bukkit.BanEntry;
import org.bukkit.Bukkit;
import org.bukkit.ban.IpBanList;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.profile.PlayerProfile;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Acesso às banlists nativas do Paper. Usar somente na thread principal. */
public final class BanGateway {

    public record NativeProfileBan(UUID uuid, String name, String reason, String actor, long createdAt, Long expiresAt) {
        public boolean expired(long now) {
            return expiresAt != null && expiresAt <= now;
        }
    }

    public record NativeIpBan(String ip, String reason, String actor, long createdAt, Long expiresAt) {
        public boolean expired(long now) {
            return expiresAt != null && expiresAt <= now;
        }
    }

    private final ProfileBanList profiles;
    private final IpBanList ips;

    public BanGateway() {
        this.profiles = Bukkit.getBanList(BanListType.PROFILE);
        this.ips = Bukkit.getBanList(BanListType.IP);
    }

    public boolean banProfile(UUID uuid, String name, String reason, String source, Long expiresAt) {
        if (uuid == null) {
            return false;
        }
        com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(uuid, name);
        profiles.addBan(profile, reason, expiresAt == null ? null : new Date(expiresAt), source);
        return isProfileBanned(uuid, name);
    }

    public boolean pardonProfile(UUID uuid, String name) {
        List<BanEntry<?>> matches = new ArrayList<>();
        for (Object raw : profiles.getEntries()) {
            BanEntry<?> entry = (BanEntry<?>) raw;
            if (entry.getBanTarget() instanceof PlayerProfile profile && matches(profile, uuid, name)) {
                matches.add(entry);
            }
        }
        for (BanEntry<?> entry : matches) {
            entry.remove();
        }
        return !isProfileBanned(uuid, name);
    }

    public boolean isProfileBanned(UUID uuid, String name) {
        long now = System.currentTimeMillis();
        for (NativeProfileBan ban : profileBans()) {
            if (ban.expired(now)) {
                continue;
            }
            if (uuid != null && uuid.equals(ban.uuid())) {
                return true;
            }
            if (name != null && ban.name() != null && name.equalsIgnoreCase(ban.name())) {
                return true;
            }
        }
        return false;
    }

    public boolean banIp(String ip, String reason, String source, Long expiresAt) {
        InetAddress address = Ips.parseLiteral(ip).orElse(null);
        if (address == null) {
            return false;
        }
        ips.addBan(address, reason, expiresAt == null ? null : new Date(expiresAt), source);
        return ips.isBanned(address);
    }

    public boolean pardonIp(String ip) {
        InetAddress address = Ips.parseLiteral(ip).orElse(null);
        if (address == null) {
            return false;
        }
        ips.pardon(address);
        return !ips.isBanned(address);
    }

    public boolean isIpBanned(String ip) {
        return Ips.parseLiteral(ip).map(ips::isBanned).orElse(false);
    }

    public List<NativeProfileBan> profileBans() {
        List<NativeProfileBan> out = new ArrayList<>();
        for (Object raw : profiles.getEntries()) {
            BanEntry<?> entry = (BanEntry<?>) raw;
            if (entry.getBanTarget() instanceof PlayerProfile profile) {
                out.add(new NativeProfileBan(profile.getUniqueId(), profile.getName(), entry.getReason(),
                        entry.getSource(), time(entry.getCreated()), nullableTime(entry.getExpiration())));
            }
        }
        return out;
    }

    public List<NativeIpBan> ipBans() {
        List<NativeIpBan> out = new ArrayList<>();
        for (Object raw : ips.getEntries()) {
            BanEntry<?> entry = (BanEntry<?>) raw;
            if (entry.getBanTarget() instanceof InetAddress address) {
                out.add(new NativeIpBan(Ips.normalize(address), entry.getReason(), entry.getSource(),
                        time(entry.getCreated()), nullableTime(entry.getExpiration())));
            }
        }
        return out;
    }

    private static boolean matches(PlayerProfile profile, UUID uuid, String name) {
        if (uuid != null && uuid.equals(profile.getUniqueId())) {
            return true;
        }
        return name != null && profile.getName() != null && name.equalsIgnoreCase(profile.getName());
    }

    private static long time(Date date) {
        return date == null ? System.currentTimeMillis() : date.getTime();
    }

    private static Long nullableTime(Date date) {
        return date == null ? null : date.getTime();
    }
}
