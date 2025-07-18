package me.SuperRonanCraft.BetterRTP.player.rtp;

import java.time.Instant;
import java.util.Arrays;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import io.papermc.lib.PaperLib;
import me.SuperRonanCraft.BetterRTP.BetterRTP;
import me.SuperRonanCraft.BetterRTP.player.rtp.effects.RTPEffect_Titles;
import me.SuperRonanCraft.BetterRTP.player.rtp.effects.RTPEffects;
import me.SuperRonanCraft.BetterRTP.references.PermissionNode;
import me.SuperRonanCraft.BetterRTP.references.customEvents.RTP_TeleportEvent;
import me.SuperRonanCraft.BetterRTP.references.customEvents.RTP_TeleportPostEvent;
import me.SuperRonanCraft.BetterRTP.references.customEvents.RTP_TeleportPreEvent;
import me.SuperRonanCraft.BetterRTP.references.messages.MessagesCore;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.worlds.WorldPlayer;

//---
//Credit to @PaperMC for PaperLib - https://github.com/PaperMC/PaperLib
//
//Use of asyncronous chunk loading and teleporting
//---

public class RTPTeleport {

    private final RTPEffects effects = new RTPEffects();

    void load() {
        effects.load();
    }

//    void cancel(Player p) { //Cancel loading chunks/teleporting
//        if (!playerLoads.containsKey(p)) return;
//        List<CompletableFuture<Chunk>> asyncChunks = playerLoads.get(p);
//        CompletableFuture.allOf(asyncChunks.toArray(new CompletableFuture[] {})).cancel(true);
//    }

    void sendPlayer(final CommandSender sendi, final Player p, final Location location, final WorldPlayer wPlayer,
                    final int attempts, RTP_TYPE type) throws NullPointerException {
        Location oldLoc = p.getLocation();
        loadingTeleport(p, sendi); //Send loading message to player who requested
        //List<CompletableFuture<Chunk>> asyncChunks = getChunks(location); //Get a list of chunks
        //playerLoads.put(p, asyncChunks);
        /*CompletableFuture.allOf(asyncChunks.toArray(new CompletableFuture[] {})).thenRun(() -> { //Async chunk load
            new BukkitRunnable() { //Run synchronously
                @Override
                public void run() {*/
        try {
            RTP_TeleportEvent event = new RTP_TeleportEvent(p, location, wPlayer.getWorldtype());
            getPl().getServer().getPluginManager().callEvent(event);
            // custom GoL code start
            World world = location.getWorld();
            if (world == null) {
                return;
            }
            String worldName = world.getName();
            // get keys
            NamespacedKey timestampKey = NamespacedKey.minecraft("checkpoint_timestamp" + worldName);
            NamespacedKey xyzKey = NamespacedKey.minecraft("checkpoint_xyz" + worldName);
            // get data from persistent data container
            PersistentDataContainer pdc = p.getPersistentDataContainer();
            Long oldTimestamp = pdc.get(timestampKey, PersistentDataType.LONG);
            int[] xyz = pdc.get(xyzKey, PersistentDataType.INTEGER_ARRAY);
            // setup variables
            long timestamp = Instant.now().getEpochSecond();
            Location loc;
            // check if the old location is valid and not expired (less than 24 hours old)
            if (xyz != null && xyz.length == 3 && oldTimestamp != null && timestamp - oldTimestamp < 86400) {
                // teleport to old location
                loc = new Location(world, xyz[0], xyz[1], xyz[2]);
            } else {
                // teleport to new location
                loc = event.getLocation();
                // write down new timestamp and coordinates
                pdc.set(timestampKey, PersistentDataType.LONG, timestamp);
                pdc.set(xyzKey, PersistentDataType.INTEGER_ARRAY, new int[] {loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()});
            }
            // custom GoL code end
            PaperLib.teleportAsync(p, loc).thenRun(new BukkitRunnable() { //Async teleport
                @Override
                public void run() {
                    afterTeleport(p, loc, wPlayer, attempts, oldLoc, type);
                    if (sendi != p) //Tell player who requested that the player rtp'd
                        sendSuccessMsg(sendi, p.getName(), loc, wPlayer, false, attempts);
                    getPl().getPInfo().getRtping().remove(p); //No longer rtp'ing
                    //Save respawn location if first join
                    if (type == RTP_TYPE.JOIN) //RTP Type was Join
                        if (BetterRTP.getInstance().getSettings().isRtpOnFirstJoin_SetAsRespawn()) //Save as respawn is enabled
                            p.setBedSpawnLocation(loc, true); //True means to force a respawn even without a valid bed
                }
            });
        } catch (Exception e) {
            getPl().getPInfo().getRtping().remove(p); //No longer rtp'ing (errored)
            e.printStackTrace();
        }
    }

    //Effects

    public void afterTeleport(Player p, Location loc, WorldPlayer wPlayer, int attempts, Location oldLoc, RTP_TYPE type) {
        String playerName = p.getName();
        // custom GoL code start
        Bukkit.getGlobalRegionScheduler().run(BetterRTP.getInstance(), (task) -> {
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            if (!p.hasPermission("open_world")) {
                Bukkit.dispatchCommand(console, "lp user " + playerName + " permission set open_world true");
            }
            Bukkit.dispatchCommand(console, "interface update-text-bar " + playerName);
        });
        // custom GoL code end
        //Only a successful rtp should run this OR '/rtp test'
        effects.getSounds().playTeleport(p);
        effects.getParticles().display(p);
        effects.getPotions().giveEffects(p);
        effects.getTitles().showTitle(RTPEffect_Titles.RTP_TITLE_TYPE.TELEPORT, p, loc, attempts, 0);
        if (effects.getTitles().sendMsg(RTPEffect_Titles.RTP_TITLE_TYPE.TELEPORT))
            sendSuccessMsg(p, playerName, loc, wPlayer, true, attempts);
        getPl().getServer().getPluginManager().callEvent(new RTP_TeleportPostEvent(p, loc, oldLoc, wPlayer, type));
    }

    public boolean beforeTeleportInstant(CommandSender sendi, Player p) {
        RTP_TeleportPreEvent event = new RTP_TeleportPreEvent(p);
        getPl().getServer().getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            effects.getSounds().playDelay(p);
            effects.getTitles().showTitle(RTPEffect_Titles.RTP_TITLE_TYPE.NODELAY, p, p.getLocation(), 0, 0);
            if (effects.getTitles().sendMsg(RTPEffect_Titles.RTP_TITLE_TYPE.NODELAY))
                MessagesCore.SUCCESS_TELEPORT.send(sendi);
        }
        return event.isCancelled();
    }

    public boolean beforeTeleportDelay(Player p, int delay) { //Only Delays should call this
        RTP_TeleportPreEvent event = new RTP_TeleportPreEvent(p);
        getPl().getServer().getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            effects.getSounds().playDelay(p);
            effects.getTitles().showTitle(RTPEffect_Titles.RTP_TITLE_TYPE.DELAY, p, p.getLocation(), 0, delay);
            if (effects.getTitles().sendMsg(RTPEffect_Titles.RTP_TITLE_TYPE.DELAY))
                MessagesCore.DELAY.send(p, delay);
        }
        return event.isCancelled();
    }

    public void cancelledTeleport(Player p) { //Only Delays should call this
        effects.getTitles().showTitle(RTPEffect_Titles.RTP_TITLE_TYPE.CANCEL, p, p.getLocation(), 0, 0);
        if (effects.getTitles().sendMsg(RTPEffect_Titles.RTP_TITLE_TYPE.CANCEL))
            MessagesCore.MOVED.send(p);
    }

    private void loadingTeleport(Player p, CommandSender sendi) {
        effects.getTitles().showTitle(RTPEffect_Titles.RTP_TITLE_TYPE.LOADING, p, p.getLocation(), 0, 0);
        if (effects.getTitles().sendMsg(RTPEffect_Titles.RTP_TITLE_TYPE.LOADING) && sendStatusMessage()) { //Show msg if enabled or if not same player
            if (p == sendi)
                MessagesCore.SUCCESS_LOADING.send(sendi);
            MessagesCore.SUCCESS_LOADING.send(p);
        }
    }

    public void failedTeleport(Player p, CommandSender sendi) {
        effects.getTitles().showTitle(RTPEffect_Titles.RTP_TITLE_TYPE.FAILED, p, p.getLocation(), 0, 0);
        if (effects.getTitles().sendMsg(RTPEffect_Titles.RTP_TITLE_TYPE.FAILED))
            if (p == sendi)
                MessagesCore.FAILED_NOTSAFE.send(p, BetterRTP.getInstance().getRTP().maxAttempts);
            else
                MessagesCore.OTHER_NOTSAFE.send(sendi, Arrays.asList(
                        BetterRTP.getInstance().getRTP().maxAttempts,
                        p.getName()));
    }

    //Processing

    /*private List<CompletableFuture<Chunk>> getChunks(Location loc) { //List all chunks in range to load
        List<CompletableFuture<Chunk>> asyncChunks = new ArrayList<>();
        int range = Math.round(Math.max(0, Math.min(16, getPl().getSettings().getPreloadRadius())));
        for (int x = -range; x <= range; x++)
            for (int z = -range; z <= range; z++) {
                Location locLoad = new Location(loc.getWorld(), loc.getX() + (x * 16), loc.getY(), loc.getZ() + (z * 16));
                CompletableFuture<Chunk> chunk = PaperLib.getChunkAtAsync(locLoad, true);
                asyncChunks.add(chunk);
            }
        return asyncChunks;
    }*/

    private void sendSuccessMsg(CommandSender sendi, String player, Location loc, WorldPlayer wPlayer, boolean sameAsPlayer, int attempts) {
        if (sameAsPlayer) {
            if (wPlayer.getPrice() == 0 || PermissionNode.BYPASS_ECONOMY.check(sendi))
                MessagesCore.SUCCESS_BYPASS.send(sendi, Arrays.asList(loc, attempts));
            else
                MessagesCore.SUCCESS_PAID.send(sendi, Arrays.asList(loc, wPlayer, attempts));
        } else
            MessagesCore.OTHER_SUCCESS.send(sendi, Arrays.asList(loc, player, attempts));
    }

    private boolean sendStatusMessage() {
        return getPl().getSettings().isStatusMessages();
    }

    private BetterRTP getPl() {
        return BetterRTP.getInstance();
    }
}
