package me.galaxy.homingbow;

import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HomingBowPlugin extends JavaPlugin implements Listener {

    private final Set<UUID> arrows = ConcurrentHashMap.newKeySet();
    private NamespacedKey key;

    private int customModelData;

    private boolean enabled;
    private double range;
    private double turnRate;

    private boolean ignoreShooter;
    private boolean ignoreTamed;
    private boolean ignoreArmorStands;

    private boolean avoidPlayersEnabled;
    private double avoidPlayersRadius;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "homing");
        loadConfigValues();
        Bukkit.getPluginManager().registerEvents(this, this);
        startTask();
        getLogger().info("HomingBow 1.0.1-mobonly-fixed enabled.");
    }
    
@EventHandler
public void onProjectileHit(ProjectileHitEvent e) {
    if (!(e.getEntity() instanceof AbstractArrow arrow)) return;

    // csak a mi homing nyilaink
    Byte tag = arrow.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
    if (tag == null || tag != (byte) 1) return;

    // lebegjen tovább, ne “ragadjon be” blokkba
    arrow.setGravity(false);

    // ha van ilyen a te Paper buildeden, jó — ha nincs, a try-catch megfogja
    try { arrow.setInBlock(false); } catch (Throwable ignored) {}

    // “reset” hogy újra keressen és ne álljon meg
    arrow.setVelocity(new Vector(0, 0, 0));
}

    private void loadConfigValues() {
        reloadConfig();
        FileConfiguration c = getConfig();

        customModelData = c.getInt("bow_match.custom_model_data", 0);

        enabled = c.getBoolean("homing.enabled", true);
        range = c.getDouble("homing.range", 25.0);
        turnRate = clamp(c.getDouble("homing.turn_rate", 0.22), 0.01, 0.95);

        ignoreShooter = c.getBoolean("targets.ignore_shooter", true);
        ignoreTamed = c.getBoolean("targets.ignore_tamed", true);
        ignoreArmorStands = c.getBoolean("targets.ignore_armor_stands", true);

        avoidPlayersEnabled = c.getBoolean("avoid_players.enabled", true);
        avoidPlayersRadius = c.getDouble("avoid_players.radius", 1.5);
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        if (!enabled) return;

        ItemStack bow = e.getBow();
        if (bow == null) return;

        ItemMeta meta = bow.getItemMeta();
        if (meta == null || !meta.hasCustomModelData()) return;
        if (meta.getCustomModelData() != customModelData) return;

        if (e.getProjectile() instanceof AbstractArrow arrow) {
            arrow.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            arrows.add(arrow.getUniqueId());
        }
    }

    private void startTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!enabled) return;

            Iterator<UUID> it = arrows.iterator();
            while (it.hasNext()) {
                UUID id = it.next();
                AbstractArrow arrow = findArrow(id);

                if (arrow == null || arrow.isDead() || !arrow.isValid()) {
                    it.remove();
                    continue;
                }

                LivingEntity target = findMobTarget(arrow);
                if (target == null) continue;

                Vector to = target.getEyeLocation().toVector()
                        .subtract(arrow.getLocation().toVector());

                if (to.lengthSquared() < 1e-6) continue;

                Vector desired = to.normalize();

                Vector vel = arrow.getVelocity();
                double speed = Math.max(0.01, vel.length());

                Vector currentDir = vel.clone().normalize();
                Vector newDir = currentDir.multiply(1.0 - turnRate).add(desired.multiply(turnRate));

                if (newDir.lengthSquared() < 1e-6) continue;

                newDir.normalize();
                arrow.setVelocity(newDir.multiply(speed));
            }
        }, 1L, 1L);
    }

    private AbstractArrow findArrow(UUID id) {
        for (World w : Bukkit.getWorlds()) {
            for (AbstractArrow a : w.getEntitiesByClass(AbstractArrow.class)) {
                if (a.getUniqueId().equals(id)) return a;
            }
        }
        return null;
    }

    // HARD LOCK: only mobs. Never target players.
    private LivingEntity findMobTarget(AbstractArrow arrow) {
        double best = range * range;
        LivingEntity chosen = null;

        ProjectileSource src = arrow.getShooter();
        LivingEntity shooter = (src instanceof LivingEntity le) ? le : null;

        for (LivingEntity e : arrow.getWorld().getLivingEntities()) {

            // never target players
            if (e instanceof Player) continue;

            if (ignoreArmorStands && e instanceof ArmorStand) continue;
            if (e.isDead() || !e.isValid()) continue;

            if (ignoreShooter && shooter != null && e.getUniqueId().equals(shooter.getUniqueId())) continue;

            if (ignoreTamed) {
                try {
                    if (e instanceof org.bukkit.entity.Tameable t && t.isTamed()) continue;
                } catch (Throwable ignored) {}
            }

            double d2 = e.getLocation().distanceSquared(arrow.getLocation());
            if (d2 >= best) continue;

            // extra safety: don't choose a mob if the arrow->mob line passes too close to any player
            if (avoidPlayersEnabled && segmentNearAnyPlayer(
                    arrow.getLocation().toVector(),
                    e.getEyeLocation().toVector(),
                    avoidPlayersRadius,
                    arrow.getWorld())) {
                continue;
            }

            best = d2;
            chosen = e;
        }

        return chosen;
    }

    private boolean segmentNearAnyPlayer(Vector a, Vector b, double radius, World world) {
        Vector ab = b.clone().subtract(a);
        double abLen2 = ab.lengthSquared();
        if (abLen2 < 1e-6) return false;

        double radius2 = radius * radius;

        for (Player p : world.getPlayers()) {
            Vector pPos = p.getEyeLocation().toVector();
            Vector ap = pPos.clone().subtract(a);

            double t = ap.dot(ab) / abLen2;
            if (t < 0.0) t = 0.0;
            if (t > 1.0) t = 1.0;

            Vector closest = a.clone().add(ab.clone().multiply(t));
            double dist2 = pPos.distanceSquared(closest);

            if (dist2 <= radius2) return true;
        }
        return false;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // command: /homingbowreload
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("homingbowreload")) {
            if (!sender.hasPermission("homingbow.admin")) {
                sender.sendMessage("§cNincs jogod.");
                return true;
            }
            loadConfigValues();
            sender.sendMessage("§aHomingBow újratöltve.");
            return true;
        }
        return false;
    }
}
