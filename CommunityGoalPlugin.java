package dev.domin8r.communitygoal;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * CommunityGoal - a shared server goal with a boss bar.
 * Everyone in the survival worlds fills one bar together. When it fills up,
 * every helper gets a reward that depends on how much of the work they did.
 * Rewards are NOT given directly: players claim them with /reward
 * (offline players get a reminder when they come back).
 */
public final class CommunityGoalPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Set<Material> CROPS = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART);
    private static final int[] MILESTONES = {25, 50, 75};

    enum Type { ORES, MOBS, FISH, CROPS }

    record GoalDef(String id, Type type, String display, BossBar.Color color,
                   long baseTarget, long perPlayer) { }

    /** items are stored as "MATERIAL:amount" strings. */
    record Tier(double minPercent, String label, List<String> items) { }

    record Pending(String goal, double percent, String tier, List<String> items) { }

    private final List<GoalDef> goals = new ArrayList<>();
    private final List<Tier> tiers = new ArrayList<>();
    private final Set<String> worlds = new HashSet<>();
    private final Map<String, Long> placedOres = new HashMap<>();
    private final Map<UUID, Long> contributors = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, List<Pending>> pending = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private final Set<Integer> milestonesHit = new HashSet<>();
    private final Random random = new Random();

    private BossBar bar;
    private BukkitTask tickTask;
    private BukkitTask saveTask;
    private File dataFile;

    private int goalIndex = 0;
    private long progress = 0;
    private long target = 0;
    private long nextStartMs = 0;
    private int completed = 0;
    private boolean cooldown = false;
    private int tickCounter = 0;

    private boolean randomOrder = false;
    private long delayMs = 600_000L;
    private long placedWindowMs = 600_000L;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        bar = BossBar.bossBar(Component.text("Community Goal"), 0f,
                BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);

        loadSettings();
        if (goals.isEmpty()) {
            getLogger().severe("No valid goals found in config.yml - disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadData();
        if (goalIndex < 0 || goalIndex >= goals.size()) {
            goalIndex = 0;
            target = 0;
        }
        if (!cooldown && target <= 0) {
            startGoal(goalIndex, false);
        }

        getServer().getPluginManager().registerEvents(this, this);
        for (String name : new String[]{"communitygoal", "reward"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }

        tickTask = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        saveTask = Bukkit.getScheduler().runTaskTimer(this, this::saveData, 20L * 60, 20L * 60);
        getLogger().info("CommunityGoal enabled: " + goals.size() + " goals, " + tiers.size() + " reward tiers.");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (saveTask != null) saveTask.cancel();
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.hideBossBar(bar);
            }
        }
        viewers.clear();
        if (!goals.isEmpty()) saveData();
    }

    // ------------------------------------------------------------------ config

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();

        worlds.clear();
        worlds.addAll(c.getStringList("worlds"));

        randomOrder = c.getBoolean("random-order", false);
        delayMs = Math.max(0, c.getLong("delay-between-goals-minutes", 10)) * 60_000L;
        placedWindowMs = Math.max(0, c.getLong("ignore-recently-placed-minutes", 10)) * 60_000L;

        try {
            bar.overlay(BossBar.Overlay.valueOf(
                    c.getString("bar-style", "NOTCHED_10").toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            bar.overlay(BossBar.Overlay.NOTCHED_10);
        }

        goals.clear();
        ConfigurationSection gs = c.getConfigurationSection("goals");
        if (gs != null) {
            for (String id : gs.getKeys(false)) {
                ConfigurationSection s = gs.getConfigurationSection(id);
                if (s == null) continue;
                Type type;
                try {
                    type = Type.valueOf(s.getString("type", "ORES").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("Goal '" + id + "' has an unknown type, skipping.");
                    continue;
                }
                BossBar.Color color;
                try {
                    color = BossBar.Color.valueOf(s.getString("color", "YELLOW").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    color = BossBar.Color.YELLOW;
                }
                goals.add(new GoalDef(
                        id, type,
                        s.getString("display", id),
                        color,
                        Math.max(1, s.getLong("base-target", 100)),
                        Math.max(0, s.getLong("per-player", 0))));
            }
        }

        tiers.clear();
        for (Map<?, ?> m : c.getMapList("reward-tiers")) {
            double min = toDouble(m.get("min-percent"), 1.0);
            Object labelObj = m.get("label");
            String label = labelObj == null ? (min + "%") : String.valueOf(labelObj);
            List<String> items = new ArrayList<>();
            Object itemsObj = m.get("items");
            if (itemsObj instanceof List<?> list) {
                for (Object o : list) {
                    String spec = parseItem(String.valueOf(o));
                    if (spec != null) items.add(spec);
                }
            }
            if (!items.isEmpty()) tiers.add(new Tier(min, label, items));
        }
        tiers.sort(Comparator.comparingDouble(Tier::minPercent));
    }

    /** Validates "MATERIAL:amount". Netherite is always blocked. Returns null if invalid. */
    private String parseItem(String raw) {
        String[] parts = raw.trim().split(":");
        Material mat = Material.matchMaterial(parts[0].trim());
        if (mat == null || !mat.isItem()) {
            getLogger().warning("Reward item '" + raw + "' is not a valid item, ignoring it.");
            return null;
        }
        if (mat.name().contains("NETHERITE")) {
            getLogger().warning("Reward item '" + raw + "' is netherite, which is not allowed. Ignoring it.");
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ex) {
                getLogger().warning("Reward item '" + raw + "' has a bad amount, using 1.");
            }
        }
        amount = Math.max(1, Math.min(64, amount));
        return mat.name() + ":" + amount;
    }

    private static double toDouble(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return def;
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private String msg(String key) {
        return getConfig().getString("messages." + key, key);
    }

    // ------------------------------------------------------------------ data

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        goalIndex = y.getInt("goal-index", 0);
        progress = y.getLong("progress", 0);
        target = y.getLong("target", 0);
        cooldown = y.getBoolean("cooldown", false);
        nextStartMs = y.getLong("next-start", 0);
        completed = y.getInt("completed", 0);

        contributors.clear();
        ConfigurationSection cs = y.getConfigurationSection("contributors");
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                try {
                    contributors.put(UUID.fromString(k), cs.getLong(k));
                } catch (IllegalArgumentException ignored) {
                    // skip broken entries
                }
            }
        }

        names.clear();
        ConfigurationSection ns = y.getConfigurationSection("names");
        if (ns != null) {
            for (String k : ns.getKeys(false)) {
                try {
                    names.put(UUID.fromString(k), String.valueOf(ns.getString(k, "Unknown")));
                } catch (IllegalArgumentException ignored) {
                    // skip broken entries
                }
            }
        }

        pending.clear();
        ConfigurationSection ps = y.getConfigurationSection("pending");
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                UUID id;
                try {
                    id = UUID.fromString(k);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                List<Pending> list = new ArrayList<>();
                for (Map<?, ?> m : y.getMapList("pending." + k)) {
                    List<String> items = new ArrayList<>();
                    Object itemsObj = m.get("items");
                    if (itemsObj instanceof List<?> l) {
                        for (Object o : l) items.add(String.valueOf(o));
                    }
                    list.add(new Pending(
                            String.valueOf(m.get("goal")),
                            toDouble(m.get("percent"), 0),
                            String.valueOf(m.get("tier")),
                            items));
                }
                if (!list.isEmpty()) pending.put(id, list);
            }
        }

        milestonesHit.clear();
        if (target > 0) {
            long pct = progress * 100 / target;
            for (int m : MILESTONES) {
                if (pct >= m) milestonesHit.add(m);
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("goal-index", goalIndex);
        y.set("progress", progress);
        y.set("target", target);
        y.set("cooldown", cooldown);
        y.set("next-start", nextStartMs);
        y.set("completed", completed);
        for (Map.Entry<UUID, Long> e : contributors.entrySet()) {
            y.set("contributors." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            y.set("names." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, List<Pending>> e : pending.entrySet()) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Pending pd : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("goal", pd.goal());
                m.put("percent", pd.percent());
                m.put("tier", pd.tier());
                m.put("items", new ArrayList<>(pd.items()));
                out.add(m);
            }
            y.set("pending." + e.getKey(), out);
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ goal logic

    private GoalDef current() {
        return goals.get(Math.min(goalIndex, goals.size() - 1));
    }

    private int onlineInWorlds() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (worlds.contains(p.getWorld().getName())) n++;
        }
        return n;
    }

    private int nextGoalIndex() {
        if (goals.size() <= 1) return 0;
        if (randomOrder) {
            int next;
            do {
                next = random.nextInt(goals.size());
            } while (next == goalIndex);
            return next;
        }
        return (goalIndex + 1) % goals.size();
    }

    private void startGoal(int index, boolean announce) {
        goalIndex = index;
        GoalDef g = current();
        target = g.baseTarget() + g.perPlayer() * Math.max(1, onlineInWorlds());
        progress = 0;
        cooldown = false;
        contributors.clear();
        milestonesHit.clear();
        refreshBar();
        saveData();
        if (announce) {
            Bukkit.broadcast(MM.deserialize(msg("goal-started")
                    .replace("{goal}", g.display())
                    .replace("{target}", fmt(target))));
        }
    }

    private void contribute(Player p, long amount) {
        if (cooldown || amount <= 0) return;
        if (!worlds.contains(p.getWorld().getName())) return;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        names.put(p.getUniqueId(), p.getName());
        addProgress(amount, p.getUniqueId());
    }

    /** credit may be null (used by the admin "add" command). */
    private void addProgress(long amount, UUID credit) {
        if (cooldown) return;
        progress += amount;
        if (credit != null) contributors.merge(credit, amount, Long::sum);

        if (progress >= target) {
            progress = target;
            complete();
            return;
        }
        long pct = progress * 100 / target;
        for (int m : MILESTONES) {
            if (pct >= m && milestonesHit.add(m)) {
                Bukkit.broadcast(MM.deserialize(msg("milestone")
                        .replace("{percent}", String.valueOf(m))
                        .replace("{goal}", current().display())));
            }
        }
    }

    private Tier pickTier(double sharePercent) {
        Tier best = null;
        for (Tier t : tiers) {
            if (sharePercent >= t.minPercent()) best = t;
        }
        return best;
    }

    private void complete() {
        GoalDef g = current();
        cooldown = true;
        completed++;
        nextStartMs = System.currentTimeMillis() + delayMs;

        Bukkit.broadcast(MM.deserialize(msg("goal-complete")
                .replace("{goal}", g.display())
                .replace("{top}", topLine(3))));

        for (Map.Entry<UUID, Long> e : new ArrayList<>(contributors.entrySet())) {
            double share = target <= 0 ? 0 : e.getValue() * 100.0 / target;
            Tier tier = pickTier(share);
            if (tier == null) continue;

            pending.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                    .add(new Pending(g.display(), share, tier.label(), tier.items()));

            Player online = Bukkit.getPlayer(e.getKey());
            if (online != null && worlds.contains(online.getWorld().getName())) {
                announceReward(online, share);
            }
        }
        refreshBar();
        saveData();
    }

    private void announceReward(Player p, double share) {
        Bukkit.broadcast(MM.deserialize(msg("reward-announce")
                .replace("{player}", p.getName())
                .replace("{percent}", pct(share))));
        p.showTitle(Title.title(
                MM.deserialize(msg("reward-title")),
                MM.deserialize(msg("reward-subtitle"))));
        p.playSound(Sound.sound(Key.key("entity.player.levelup"), Sound.Source.MASTER, 1f, 1f));
    }

    private void notifyPending(Player p) {
        List<Pending> list = pending.get(p.getUniqueId());
        if (list == null || list.isEmpty()) return;
        p.sendMessage(MM.deserialize(msg("reward-pending")
                .replace("{player}", p.getName())
                .replace("{count}", String.valueOf(list.size()))));
        p.playSound(Sound.sound(Key.key("entity.player.levelup"), Sound.Source.MASTER, 1f, 1f));
    }

    private void claim(Player p) {
        if (!worlds.contains(p.getWorld().getName())) {
            p.sendMessage(MM.deserialize(msg("reward-wrong-world")));
            return;
        }
        List<Pending> list = pending.remove(p.getUniqueId());
        if (list == null || list.isEmpty()) {
            p.sendMessage(MM.deserialize(msg("reward-none")));
            return;
        }

        Map<Material, Integer> total = new LinkedHashMap<>();
        for (Pending pd : list) {
            for (String spec : pd.items()) {
                String[] parts = spec.split(":");
                Material mat = Material.matchMaterial(parts[0]);
                if (mat == null) continue;
                int amount = 1;
                if (parts.length > 1) {
                    try {
                        amount = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                        amount = 1;
                    }
                }
                total.merge(mat, amount, Integer::sum);
            }
        }

        StringBuilder given = new StringBuilder();
        for (Map.Entry<Material, Integer> e : total.entrySet()) {
            int left = e.getValue();
            int stackMax = Math.max(1, e.getKey().getMaxStackSize());
            while (left > 0) {
                int n = Math.min(stackMax, left);
                left -= n;
                Map<Integer, ItemStack> overflow = p.getInventory().addItem(new ItemStack(e.getKey(), n));
                for (ItemStack extra : overflow.values()) {
                    Item dropped = p.getWorld().dropItem(p.getLocation(), extra);
                    dropped.setOwner(p.getUniqueId());
                }
            }
            if (given.length() > 0) given.append("<gray>, ");
            given.append("<white>").append(e.getValue()).append("x <yellow>").append(pretty(e.getKey()));
        }

        p.sendMessage(MM.deserialize(msg("reward-claimed").replace("{items}", given.toString())));
        p.playSound(Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.MASTER, 1f, 1f));
        saveData();
    }

    private void tick() {
        tickCounter++;
        if (cooldown && System.currentTimeMillis() >= nextStartMs) {
            startGoal(nextGoalIndex(), true);
        }
        refreshBar();

        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean inWorld = worlds.contains(p.getWorld().getName());
            if (inWorld) {
                if (viewers.add(p.getUniqueId())) p.showBossBar(bar);
            } else if (viewers.remove(p.getUniqueId())) {
                p.hideBossBar(bar);
            }
        }

        if (tickCounter % 300 == 0) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, Long>> it = placedOres.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > placedWindowMs) it.remove();
            }
        }
    }

    private void refreshBar() {
        if (goals.isEmpty()) return;
        GoalDef g = current();
        if (cooldown) {
            long left = Math.max(0, nextStartMs - System.currentTimeMillis());
            bar.name(MM.deserialize(msg("bar-cooldown").replace("{time}", fmtTime(left))));
            bar.progress(1f);
            bar.color(BossBar.Color.GREEN);
        } else {
            double ratio = target <= 0 ? 0 : Math.min(1.0, (double) progress / target);
            bar.name(MM.deserialize(msg("bar-title")
                    .replace("{goal}", g.display())
                    .replace("{progress}", fmt(progress))
                    .replace("{target}", fmt(target))
                    .replace("{percent}", String.valueOf((int) (ratio * 100)))));
            bar.progress((float) ratio);
            bar.color(g.color());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String fmt(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static String pct(double d) {
        return String.format(Locale.US, "%.1f", d);
    }

    private static String fmtTime(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    private static String pretty(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String part : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private String nameOf(UUID id) {
        String known = names.get(id);
        if (known != null) return known;
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : "Unknown";
    }

    private String topLine(int limit) {
        List<Map.Entry<UUID, Long>> list = new ArrayList<>(contributors.entrySet());
        list.sort(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()));
        if (list.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, list.size()); i++) {
            if (i > 0) sb.append("<gray>, ");
            sb.append("<white>").append(nameOf(list.get(i).getKey()))
                    .append(" <yellow>(").append(fmt(list.get(i).getValue())).append(")");
        }
        return sb.toString();
    }

    private static boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
    }

    private static String blockKey(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isOre(e.getBlockPlaced().getType())) {
            placedOres.put(blockKey(e.getBlockPlaced()), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (cooldown || goals.isEmpty()) return;
        Block b = e.getBlock();
        Type type = current().type();

        if (type == Type.ORES && isOre(b.getType())) {
            Long placedAt = placedOres.remove(blockKey(b));
            boolean recent = placedAt != null && System.currentTimeMillis() - placedAt < placedWindowMs;
            if (!recent) contribute(e.getPlayer(), 1);
        } else if (type == Type.CROPS && CROPS.contains(b.getType())
                && b.getBlockData() instanceof Ageable crop
                && crop.getAge() >= crop.getMaximumAge()) {
            contribute(e.getPlayer(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        if (cooldown || goals.isEmpty() || current().type() != Type.MOBS) return;
        LivingEntity dead = e.getEntity();
        if (!(dead instanceof Enemy)) return;
        Player killer = dead.getKiller();
        if (killer != null) contribute(killer, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (cooldown || goals.isEmpty() || current().type() != Type.FISH) return;
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            contribute(e.getPlayer(), 1);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && worlds.contains(p.getWorld().getName())) notifyPending(p);
        }, 100L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        boolean cameFromOutside = !worlds.contains(e.getFrom().getName());
        if (cameFromOutside && worlds.contains(p.getWorld().getName())) {
            notifyPending(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        viewers.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("reward")) {
            if (sender instanceof Player p) {
                claim(p);
            } else {
                sender.sendMessage(Component.text("Only players can claim rewards."));
            }
            return true;
        }

        String sub = args.length == 0 ? "progress" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "progress" -> {
                if (cooldown) {
                    long left = Math.max(0, nextStartMs - System.currentTimeMillis());
                    sender.sendMessage(MM.deserialize(msg("cmd-cooldown").replace("{time}", fmtTime(left))));
                } else {
                    long mine = sender instanceof Player p ? contributors.getOrDefault(p.getUniqueId(), 0L) : 0L;
                    sender.sendMessage(MM.deserialize(msg("cmd-progress")
                            .replace("{goal}", current().display())
                            .replace("{progress}", fmt(progress))
                            .replace("{target}", fmt(target))
                            .replace("{mine}", fmt(mine))
                            .replace("{completed}", String.valueOf(completed))));
                }
            }
            case "top" -> sender.sendMessage(MM.deserialize(msg("cmd-top").replace("{top}", topLine(5))));
            case "reload" -> {
                if (!sender.hasPermission("communitygoal.admin")) { noPerm(sender); return true; }
                loadSettings();
                if (goals.isEmpty()) {
                    sender.sendMessage(Component.text("No valid goals in config.yml"));
                    return true;
                }
                if (goalIndex >= goals.size()) startGoal(0, true);
                refreshBar();
                sender.sendMessage(MM.deserialize(msg("cmd-reloaded")));
            }
            case "skip" -> {
                if (!sender.hasPermission("communitygoal.admin")) { noPerm(sender); return true; }
                startGoal(nextGoalIndex(), true);
            }
            case "add" -> {
                if (!sender.hasPermission("communitygoal.admin")) { noPerm(sender); return true; }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /" + label + " add <amount>"));
                    return true;
                }
                try {
                    long n = Long.parseLong(args[1]);
                    UUID credit = null;
                    if (sender instanceof Player p) {
                        credit = p.getUniqueId();
                        names.put(credit, p.getName());
                    }
                    addProgress(n, credit);
                    refreshBar();
                } catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text("That is not a number."));
                }
            }
            default -> sender.sendMessage(Component.text("Usage: /" + label + " [progress|top]"));
        }
        return true;
    }

    private void noPerm(CommandSender s) {
        s.sendMessage(MM.deserialize(msg("no-permission")));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("communitygoal") || args.length != 1) return List.of();
        List<String> out = new ArrayList<>(List.of("progress", "top"));
        if (sender.hasPermission("communitygoal.admin")) out.addAll(List.of("reload", "skip", "add"));
        String prefix = args[0].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(prefix));
        return out;
    }
}
