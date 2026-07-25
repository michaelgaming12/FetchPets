package com.mikauri.fetchpets;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class FetchPets extends JavaPlugin implements CommandExecutor, TabCompleter {

    // One located pet (from disk or live). Position is its last-known location.
    private static final class PetInfo {
        final int worldIndex;
        final UUID uuid;
        final String type;   // pretty display type, e.g. "Wolf"
        final String name;   // custom name or null
        final double x, y, z;
        final boolean loaded;
        PetInfo(int worldIndex, UUID uuid, String type, String name,
                double x, double y, double z, boolean loaded) {
            this.worldIndex = worldIndex; this.uuid = uuid; this.type = type;
            this.name = name; this.x = x; this.y = y; this.z = z; this.loaded = loaded;
        }
    }

    private static final int LOCATE_LIMIT = 60;

    private boolean standUp;
    private boolean includeLoaded;
    private long cooldownMs;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        if (getCommand("fetchpets") != null) {
            getCommand("fetchpets").setExecutor(this);
            getCommand("fetchpets").setTabCompleter(this);
        }
        getLogger().info("FetchPets enabled (v" + getDescription().getVersion() + ").");
    }

    private void reloadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        standUp = c.getBoolean("settings.stand-up-sitting-pets", true);
        includeLoaded = c.getBoolean("settings.include-loaded", true);
        cooldownMs = Math.max(0L, c.getLong("settings.cooldown-seconds", 5L)) * 1000L;
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && (args[0].equalsIgnoreCase("help")
                || args[0].equals("?") || args[0].equalsIgnoreCase("usage"))) {
            sendHelp(sender, label);
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("fetchpets.others")) {
                sender.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }
            reloadSettings();
            sender.sendMessage(ChatColor.GREEN + "FetchPets config reloaded.");
            return true;
        }

        boolean locate = args.length >= 1
                && (args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("locate"));
        String nameArg = locate ? (args.length >= 2 ? args[1] : null)
                                : (args.length >= 1 ? args[0] : null);

        // Resolve the target player (whose pets, and where they go for a fetch).
        final Player target;
        if (nameArg != null) {
            if (!sender.hasPermission("fetchpets.others")) {
                sender.sendMessage(ChatColor.RED + "You can only act on your own pets.");
                return true;
            }
            target = Bukkit.getPlayerExact(nameArg);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "No online player named '" + nameArg + "'."
                        + ChatColor.GRAY + " See /" + label + " help.");
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Console must specify a player, e.g. /fetchpets list <player>");
                return true;
            }
            if (!sender.hasPermission("fetchpets.use")) {
                sender.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }
            target = (Player) sender;
        }

        if (cooldownMs > 0 && sender instanceof Player) {
            UUID id = ((Player) sender).getUniqueId();
            long now = System.currentTimeMillis();
            Long last = lastUse.get(id);
            if (last != null && now - last < cooldownMs) {
                long leftS = (cooldownMs - (now - last) + 999) / 1000;
                sender.sendMessage(ChatColor.RED + "Wait " + leftS + "s before using FetchPets again.");
                return true;
            }
            lastUse.put(id, now);
        }

        final UUID ownerId = target.getUniqueId();
        final int[] ownerInts = uuidToIntArray(ownerId);
        if (locate) runLocate(sender, target, ownerId, ownerInts);
        else runFetch(sender, target, ownerId, ownerInts);
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        String c = "/" + label;
        sender.sendMessage(ChatColor.GOLD + "FetchPets " + ChatColor.GRAY + "v" + getDescription().getVersion()
                + ChatColor.DARK_GRAY + " - brings your tamed pets home from anywhere, even unloaded chunks.");
        if (sender instanceof Player && sender.hasPermission("fetchpets.use")) {
            sender.sendMessage(ChatColor.YELLOW + c + ChatColor.WHITE + " - teleport your pets to you");
            sender.sendMessage(ChatColor.YELLOW + c + " list" + ChatColor.WHITE + " - list your pets and where they are (no teleport)");
        }
        if (sender.hasPermission("fetchpets.others")) {
            sender.sendMessage(ChatColor.YELLOW + c + " <player>" + ChatColor.WHITE + " - teleport that player's pets to them");
            sender.sendMessage(ChatColor.YELLOW + c + " list <player>" + ChatColor.WHITE + " - list that player's pets");
            sender.sendMessage(ChatColor.YELLOW + c + " reload" + ChatColor.WHITE + " - reload the config");
        }
        sender.sendMessage(ChatColor.GRAY + "Covers wolves, cats, parrots, horses, donkeys, mules, llamas and more.");
    }

    // ------------------------------------------------------------------ fetch

    private void runFetch(CommandSender sender, Player target, UUID ownerId, int[] ownerInts) {
        sender.sendMessage(ChatColor.YELLOW + "Searching the whole server for " + target.getName()
                + "'s pets... this may take a moment.");

        new BukkitRunnable() {
            @Override public void run() {
                final List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
                final List<PetInfo> disk = new ArrayList<>();
                final int scanned = scanDisk(worlds, ownerInts, disk);

                // Reduce to the unique chunks we actually need to load.
                final List<int[]> chunks = uniqueChunks(disk);

                new BukkitRunnable() {
                    @Override public void run() {
                        if (!target.isOnline()) return;
                        Location dest = target.getLocation();
                        int moved = 0;
                        Set<UUID> done = new HashSet<>();

                        for (int[] t : chunks) {
                            World w = worlds.get(t[0]);
                            Chunk ch = w.getChunkAt(t[1], t[2]); // load just this chunk
                            for (Entity e : ch.getEntities()) {
                                if (matches(e, ownerId) && teleport(e, dest)) {
                                    moved++; done.add(e.getUniqueId());
                                }
                            }
                        }

                        if (includeLoaded) {
                            for (World w : worlds) {
                                for (Entity e : w.getEntities()) {
                                    if (done.contains(e.getUniqueId())) continue;
                                    if (matches(e, ownerId) && teleport(e, dest)) {
                                        moved++; done.add(e.getUniqueId());
                                    }
                                }
                            }
                        }

                        if (moved == 0) {
                            sender.sendMessage(ChatColor.RED + "No tamed pets found for " + target.getName()
                                    + " (scanned " + scanned + " region files).");
                        } else {
                            sender.sendMessage(ChatColor.GREEN + "Brought " + moved + " pet" + (moved == 1 ? "" : "s")
                                    + " home to " + target.getName() + ".");
                            if (target != sender) {
                                target.sendMessage(ChatColor.GREEN + "" + moved + " of your pet" + (moved == 1 ? "" : "s")
                                        + " teleported to you.");
                            }
                        }
                    }
                }.runTask(FetchPets.this);
            }
        }.runTaskAsynchronously(this);
    }

    // ----------------------------------------------------------------- locate

    private void runLocate(CommandSender sender, Player target, UUID ownerId, int[] ownerInts) {
        sender.sendMessage(ChatColor.YELLOW + "Locating " + target.getName() + "'s pets...");

        new BukkitRunnable() {
            @Override public void run() {
                final List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
                final List<PetInfo> disk = new ArrayList<>();
                scanDisk(worlds, ownerInts, disk);

                new BukkitRunnable() {
                    @Override public void run() {
                        // Live pets first (authoritative position), then disk-only ones.
                        List<PetInfo> all = new ArrayList<>();
                        Set<UUID> seen = new HashSet<>();
                        for (int wi = 0; wi < worlds.size(); wi++) {
                            World w = worlds.get(wi);
                            for (Entity e : w.getEntities()) {
                                if (!matches(e, ownerId)) continue;
                                Location l = e.getLocation();
                                all.add(new PetInfo(wi, e.getUniqueId(), prettifyType(e.getType().name()),
                                        e.getCustomName(), l.getX(), l.getY(), l.getZ(), true));
                                seen.add(e.getUniqueId());
                            }
                        }
                        for (PetInfo d : disk) if (seen.add(d.uuid)) all.add(d);

                        if (all.isEmpty()) {
                            sender.sendMessage(ChatColor.RED + "No tamed pets found for " + target.getName() + ".");
                            return;
                        }

                        sender.sendMessage(ChatColor.GOLD + "" + target.getName() + " has "
                                + ChatColor.WHITE + all.size() + ChatColor.GOLD + " pet" + (all.size() == 1 ? "" : "s") + ":");
                        int shown = 0;
                        for (PetInfo p : all) {
                            if (shown++ >= LOCATE_LIMIT) {
                                sender.sendMessage(ChatColor.GRAY + "  ...and " + (all.size() - LOCATE_LIMIT) + " more.");
                                break;
                            }
                            String wName = worlds.get(p.worldIndex).getName();
                            String env = envLabel(worlds.get(p.worldIndex));
                            String nm = (p.name != null && !p.name.isEmpty())
                                    ? ChatColor.AQUA + " '" + p.name + "'" + ChatColor.GRAY : "";
                            ChatColor stateColor = p.loaded ? ChatColor.GREEN : ChatColor.YELLOW;
                            sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + p.type + nm
                                    + ChatColor.GRAY + " @ " + (int) Math.floor(p.x) + ", " + (int) Math.floor(p.y)
                                    + ", " + (int) Math.floor(p.z) + ChatColor.GRAY + " in " + env
                                    + ChatColor.DARK_GRAY + " (" + wName + ") "
                                    + stateColor + (p.loaded ? "[loaded]" : "[unloaded]"));
                        }
                        sender.sendMessage(ChatColor.GRAY + "Run " + ChatColor.WHITE + "/fetchpets"
                                + (target != sender ? " " + target.getName() : "")
                                + ChatColor.GRAY + " to bring them in.");
                    }
                }.runTask(FetchPets.this);
            }
        }.runTaskAsynchronously(this);
    }

    // ------------------------------------------------------------- pet helpers

    private boolean teleport(Entity e, Location dest) {
        try {
            if (standUp) clearSitting(e);
            return e.teleport(dest);
        } catch (Exception ex) {
            return false;
        }
    }

    // There was no shared "Sittable" interface before 1.19.4, so we call
    // isSitting()/setSitting() reflectively. This keeps the plugin compatible all
    // the way down to the 1.17 floor while still working on modern versions.
    private void clearSitting(Entity e) {
        try {
            Object sitting = e.getClass().getMethod("isSitting").invoke(e);
            if (Boolean.TRUE.equals(sitting)) {
                e.getClass().getMethod("setSitting", boolean.class).invoke(e, false);
            }
        } catch (ReflectiveOperationException ignored) {
            // Entity can't sit (horses, llamas, etc.) - nothing to stand up.
        }
    }

    // A live entity matches if it's a tamed pet owned by ownerId.
    private boolean matches(Entity e, UUID ownerId) {
        if (!(e instanceof Tameable)) return false;
        Tameable t = (Tameable) e;
        return t.isTamed() && t.getOwner() != null && ownerId.equals(t.getOwner().getUniqueId());
    }

    private List<int[]> uniqueChunks(List<PetInfo> pets) {
        Set<String> seen = new HashSet<>();
        List<int[]> out = new ArrayList<>();
        for (PetInfo p : pets) {
            int cx = ((int) Math.floor(p.x)) >> 4;
            int cz = ((int) Math.floor(p.z)) >> 4;
            String k = p.worldIndex + ":" + cx + ":" + cz;
            if (seen.add(k)) out.add(new int[]{p.worldIndex, cx, cz});
        }
        return out;
    }

    // ------------------------------------------------------ world file lookup

    private List<File> findEntityDirs(File worldFolder) {
        List<File> dirs = new ArrayList<>();
        addIfDir(dirs, new File(worldFolder, "entities"));
        addIfDir(dirs, new File(new File(worldFolder, "DIM-1"), "entities"));
        addIfDir(dirs, new File(new File(worldFolder, "DIM1"), "entities"));
        return dirs;
    }

    private void addIfDir(List<File> list, File f) {
        if (f.isDirectory()) list.add(f);
    }

    // ----------------------------------------------------- region (.mca) scan

    // Scan every world's entity region files, appending a PetInfo for each pet
    // owned by ownerInts. Returns the number of region files scanned.
    private int scanDisk(List<World> worlds, int[] ownerInts, List<PetInfo> out) {
        int scanned = 0;
        for (int wi = 0; wi < worlds.size(); wi++) {
            World w = worlds.get(wi);
            for (File entDir : findEntityDirs(w.getWorldFolder())) {
                File[] regions = entDir.listFiles((d, n) -> n.startsWith("r.") && n.endsWith(".mca"));
                if (regions == null) continue;
                for (File region : regions) {
                    scanned++;
                    try {
                        collectOwnedPets(region, ownerInts, wi, out);
                    } catch (Exception ex) {
                        getLogger().warning("Skipped region " + region.getName() + ": " + ex);
                    }
                }
            }
        }
        return scanned;
    }

    private void collectOwnedPets(File region, int[] ownerInts, int worldIndex, List<PetInfo> out) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(region, "r")) {
            long len = raf.length();
            if (len < 8192) return; // no header => empty region
            byte[] header = new byte[4096];
            raf.seek(0);
            raf.readFully(header);

            for (int i = 0; i < 1024; i++) {
                int b = i * 4;
                int offset = ((header[b] & 0xFF) << 16) | ((header[b + 1] & 0xFF) << 8) | (header[b + 2] & 0xFF);
                int sectors = header[b + 3] & 0xFF;
                if (offset == 0 || sectors == 0) continue;

                long pos = (long) offset * 4096L;
                if (pos + 5 > len) continue;
                raf.seek(pos);
                int dataLen = raf.readInt();
                if (dataLen <= 0 || pos + 4 + dataLen > len) continue;
                int compType = raf.readUnsignedByte();
                byte[] payload = new byte[dataLen - 1];
                raf.readFully(payload);

                DataInputStream in;
                ByteArrayInputStream bin = new ByteArrayInputStream(payload);
                if (compType == 1) in = new DataInputStream(new GZIPInputStream(bin));
                else if (compType == 2) in = new DataInputStream(new InflaterInputStream(bin));
                else if (compType == 3) in = new DataInputStream(bin);
                else continue;

                Object root;
                try {
                    root = readNamedRoot(in);
                } catch (Exception ex) {
                    continue; // skip a single bad chunk, not the whole region
                } finally {
                    try { in.close(); } catch (IOException ignored) {}
                }
                if (!(root instanceof Map)) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> rootMap = (Map<String, Object>) root;
                Object entitiesObj = rootMap.get("Entities");
                if (!(entitiesObj instanceof List)) continue;

                for (Object eo : (List<?>) entitiesObj) {
                    if (!(eo instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ent = (Map<String, Object>) eo;
                    if (!ownerMatches(ent, ownerInts)) continue;

                    Object idObj = ent.get("id");
                    String typeId = (idObj instanceof String) ? (String) idObj : "minecraft:pet";

                    Object posObj = ent.get("Pos");
                    if (!(posObj instanceof List) || ((List<?>) posObj).size() != 3) continue;
                    List<?> p = (List<?>) posObj;
                    double x = toD(p.get(0)), y = toD(p.get(1)), z = toD(p.get(2));

                    UUID u = readUuid(ent.get("UUID"));
                    String name = cleanName(ent.get("CustomName"));

                    out.add(new PetInfo(worldIndex, u, prettifyType(typeId), name, x, y, z, false));
                }
            }
        }
    }

    private boolean ownerMatches(Map<String, Object> ent, int[] ownerInts) {
        Object owner = ent.get("Owner");
        if (owner == null) owner = ent.get("OwnerUUID");
        if (owner instanceof int[]) {
            int[] o = (int[]) owner;
            return o.length == 4 && o[0] == ownerInts[0] && o[1] == ownerInts[1]
                    && o[2] == ownerInts[2] && o[3] == ownerInts[3];
        }
        return false;
    }

    private double toD(Object o) { return (o instanceof Number) ? ((Number) o).doubleValue() : 0.0; }

    private UUID readUuid(Object o) {
        if (o instanceof int[] && ((int[]) o).length == 4) {
            int[] a = (int[]) o;
            long msb = ((long) a[0] << 32) | (a[1] & 0xFFFFFFFFL);
            long lsb = ((long) a[2] << 32) | (a[3] & 0xFFFFFFFFL);
            return new UUID(msb, lsb);
        }
        return UUID.randomUUID(); // unknown id: still unique so dedupe works
    }

    // CustomName may be a JSON text component or a plain quoted string.
    private String cleanName(Object o) {
        if (!(o instanceof String)) return null;
        String s = ((String) o).trim();
        if (s.isEmpty()) return null;
        int idx = s.indexOf("\"text\"");
        if (idx >= 0) {
            int colon = s.indexOf(':', idx);
            int q1 = colon >= 0 ? s.indexOf('"', colon + 1) : -1;
            if (q1 >= 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = q1 + 1; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c == '\\') { if (i + 1 < s.length()) { sb.append(s.charAt(i + 1)); i++; } continue; }
                    if (c == '"') break;
                    sb.append(c);
                }
                return sb.length() == 0 ? null : sb.toString();
            }
        }
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // "minecraft:skeleton_horse" / "SKELETON_HORSE" -> "Skeleton Horse"
    private String prettifyType(String id) {
        String s = id;
        int c = s.indexOf(':');
        if (c >= 0) s = s.substring(c + 1);
        s = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (String part : s.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.length() == 0 ? id : sb.toString();
    }

    private String envLabel(World w) {
        switch (w.getEnvironment()) {
            case NETHER:  return "Nether";
            case THE_END: return "The End";
            case NORMAL:  return "Overworld";
            default:      return w.getEnvironment().name();
        }
    }

    // ------------------------------------------------------------- NBT reader
    // Minimal NBT decoder. Returns nested Map / List / primitives / int[] etc.

    private Object readNamedRoot(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        if (type == 0) return null;          // TAG_End
        in.readUTF();                        // root name (usually empty)
        return readPayload(in, type);
    }

    private Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1:  return in.readByte();
            case 2:  return in.readShort();
            case 3:  return in.readInt();
            case 4:  return in.readLong();
            case 5:  return in.readFloat();
            case 6:  return in.readDouble();
            case 7: {
                int n = in.readInt();
                byte[] a = new byte[n];
                in.readFully(a);
                return a;
            }
            case 8:  return in.readUTF();
            case 9: {
                int elem = in.readUnsignedByte();
                int n = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) list.add(readPayload(in, elem));
                return list;
            }
            case 10: {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;       // TAG_End
                    String name = in.readUTF();
                    map.put(name, readPayload(in, t));
                }
                return map;
            }
            case 11: {
                int n = in.readInt();
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                int n = in.readInt();
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("Unknown NBT tag type: " + type);
        }
    }

    // --------------------------------------------------------------- UUID util

    // Minecraft stores UUIDs as int[4]: [msbHigh, msbLow, lsbHigh, lsbLow].
    private int[] uuidToIntArray(UUID u) {
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        return new int[]{(int) (msb >> 32), (int) msb, (int) (lsb >> 32), (int) lsb};
    }

    // ------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            List<String> subs = sender.hasPermission("fetchpets.others")
                    ? Arrays.asList("list", "help", "reload") : Arrays.asList("list", "help");
            for (String opt : subs) {
                if (opt.startsWith(pre)) out.add(opt);
            }
            if (sender.hasPermission("fetchpets.others")) addPlayers(out, pre);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("list")
                && sender.hasPermission("fetchpets.others")) {
            addPlayers(out, args[1].toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private void addPlayers(List<String> out, String pre) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getName());
        }
    }
}