package me.galaxy.homingbow;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HomingBowPlugin extends JavaPlugin implements Listener {

    private NamespacedKey homingKey;
    private final Set<UUID> homingProjectiles = ConcurrentHashMap.newKeySet();

    // bow match
    private boolean matchCmd;
    private int customModelData;
    private boolean useNameContains;
    private String nameContains;
    private boolean useLoreContains;
    private String loreContains;

    // homing
    private boolean enabled;
    private int tickInterval;
    private double range;
    private double turnRate;
    private boolean keepSpeed;
    private double speedMultiplier;
    private boolean requireLineOfSight;

    // targets
    private boolean targetPlayers;
    private boolean targetMobs;
    private boolean ignoreShooter;
    private boolean ignoreTamed;
    private boolean ignoreArmorStands;

    // debug
    private boolean logShoot;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        homingKey = new NamespacedKey(this, "homing");
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        startTickTask();
        getLogger().info("HomingBow enabled.");
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();

        matchCmd = c.getBoolean("bow_match.use_custom_model_data", true);
        customModelData = c.getInt("bow_match.custom_model_data", 0);

        useNameContains = c.getBoolean("bow_match.use_name_contains", false);
        nameContains = color(c.getString("bow_match.name_contains", "Homing Bow"));

        useLoreContains = c.getBoolean("bow_match.use_lore_contains", false);
        loreContains = color(c.getString("bow_match.lore_contains", "Tracking"));

        enabled = c.getBoolean("homing.enabled", true);
        tickInterval = Math.max(1, c.getInt("homing.tick_interval", 1));
        range = c.getDouble("homing.range", 25.0);
        turnRate = clamp(c.getDouble("homing.turn_rate", 0.22), 0.01, 0.95);
        keepSpeed = c.getBoolean("homing.keep_speed", true);
        speedMultiplier = c.getDouble("homing.speed_multiplier", 1.0);
        requireLineOfSight = c.getBoolean("homing.require_line_of_sight", false);

        targetPlayers = c.getBoolean("targets.target_players", true);
        targetMobs = c.getBoolean("targets.target_mobs", true);
        ignoreShooter = c.getBoolean("targets.ignore_shooter", true);
        ignoreTamed = c.getBoolean("targets.ignore_tamed", true);
        ignoreArmorStands = c.getBoolean("targets.ignore_armor_stands", true);

        logShoot = c.getBoolean("debug.log_shoot", false);
    }

    private void startTickTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!enabled) return;

            Iterator<UUID> it = homingProjectiles.iterator();
            while (it.hasNext()) {
                UUID id = it.next();
                Projectile p = findProjectileById(id);
                if (p == null || p.isDead() || !p.isValid()) {
                    it.remove();
                    continue;
                }

                if (!(p instanceof AbstractArrow arrow)) {
                    it.remove();
                    continue;
                }

                ProjectileSource src = arrow.getShooter();
                LivingEntity shooter = (src instanceof LivingEntity le) ? le : null;

                LivingEntity target = findTarget(arrow, shooter);
                if (target == null) continue;

                steerArrowTowards(arrow, target);
            }
        }, 1L, tickInterval);
    }

    private Projectile findProjectileById(UUID id) {
        for (World w : Bukkit.getWorlds()) {
            for (Projectile p : w.getEntitiesByClass(Projectile.class)) {
                if (p.getUniqueId().equals(id)) return p;
            }
        }
        return null;
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        if (!enabled) return;

        ItemStack bow = e.getBow();
        if (bow == null) return;
        if (!isOurBow(bow)) return;

        if (!(e.getProjectile() instanceof Projectile proj)) return;

        proj.getPersistentDataContainer().set(homingKey, PersistentDataType.BYTE, (byte) 1);
        homingProjectiles.add(proj.getUniqueId());

        if (logShoot) getLogger().info("Homing projectile tagged: " + proj.getUniqueId());
    }

    private boolean isOurBow(ItemStack item) {
        String type = item.getType().name();
        if (!type.contains("BOW") && !type.contains("CROSSBOW")) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        if (matchCmd) {
            if (!meta.hasCustomModelData()) return false;
            if (meta.getCustomModelData() != customModelData) return false;
        }

        if (useNameContains) {
            String dn = meta.hasDisplayName() ? meta.getDisplayName() : "";
            if (!stripColor(dn).toLowerCase(Locale.ROOT)
                    .contains(stripColor(nameContains).toLowerCase(Locale.ROOT))) return false;
        }

        if (useLoreContains) {
            if (meta.getLore() == null) return false;
            String needle = stripColor(loreContains).toLowerCase(Locale.ROOT);
            boolean ok = meta.getLore().stream()
                    .map(HomingBowPlugin::stripColor)
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .anyMatch(s -> s.contains(needle));
            if (!ok) return false;
        }

        return true;
    }

    private LivingEntity findTarget(AbstractArrow arrow, LivingEntity shooter) {
        double best = range * range;
        LivingEntity bestEnt = null;

        for (LivingEntity le : arrow.getWorld().getLivingEntities()) {
            if (!isValidTarget(le, shooter)) continue;

            double d2 = le.getLocation().distanceSquared(arrow.getLocation());
            if (d2 > best) continue;

            if (requireLineOfSight && !arrow.hasLineOfSight(le)) continue;

            best = d2;
            bestEnt = le;
        }
        return bestEnt;
    }

    private boolean isValidTarget(LivingEntity le, LivingEntity shooter) {
        if (ignoreArmorStands && le instanceof ArmorStand) return false;
        if (ignoreShooter && shooter != null && le.getUniqueId().equals(shooter.getUniqueId())) return false;

        if (le instanceof Player) {
            if (!targetPlayers) return false;
        } else {
            if (!targetMobs) return false;
        }

        if (ignoreTamed) {
            try {
                if (le instanceof org.bukkit.entity.Tameable t && t.isTamed()) return false;
            } catch (Throwable ignored) {}
        }

        return !le.isDead() && le.isValid();
    }

    private void steerArrowTowards(AbstractArrow arrow, LivingEntity target) {
        Vector pos = arrow.getLocation().toVector();
        Vector targetPos = target.getEyeLocation().toVector();
        Vector desired = targetPos.subtract(pos);

        if (desired.lengthSquared() < 1e-6) return;
        desired.normalize();

        Vector currentVel = arrow.getVelocity();
        double speed = currentVel.length();
        if (speed < 0.01) speed = 0.01;

        Vector currentDir = currentVel.clone().normalize();

        Vector newDir = currentDir.multiply(1.0 - turnRate).add(desired.multiply(turnRate));
        if (newDir.lengthSquared() < 1e-6) return;
        newDir.normalize();

        double newSpeed = (keepSpeed ? speed : speed) * speedMultiplier;
        arrow.setVelocity(newDir.multiply(newSpeed));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("homingbowreload")) {
            if (!sender.hasPermission("homingbow.admin")) {
                sender.sendMessage(ChatColor.RED + "Nincs jogod.");
                return true;
            }
            loadSettings();
            sender.sendMessage(ChatColor.GREEN + "HomingBow config újratöltve.");
            return true;
        }
        return false;
    }

    private static String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static String stripColor(String s) {
        return ChatColor.stripColor(s == null ? "" : s);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
