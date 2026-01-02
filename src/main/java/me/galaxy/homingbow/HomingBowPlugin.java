package me.galaxy.homingbow;

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
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HomingBowPlugin extends JavaPlugin implements Listener {

    private final Set<UUID> arrows = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> expireAt = new ConcurrentHashMap<>();

    private NamespacedKey key;

    private int customModelData;

    private boolean enabled;
    private double range;
    private double turnRate;
    private double arrowSpeed;
    private long lifetimeMillis;
    private boolean hoverWhenNoTarget;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        key = new NamespacedKey(this, "homing");
        Bukkit.getPluginManager().registerEvents(this, this);
        startTask();
        getLogger().info("HomingBow enabled");
    }

    private void loadConfigValues() {
        FileConfiguration c = getConfig();
        customModelData = c.getInt("bow_match.custom_model_data", 0);
        enabled = c.getBoolean("homing.enabled", true);
        range = c.getDouble("homing.range", 40.0);
        turnRate = c.getDouble("homing.turn_rate", 0.35);
        arrowSpeed = c.getDouble("homing.arrow_speed", 2.2);
        lifetimeMillis = c.getLong("homing.lifetime_seconds", 10) * 1000L;
        hoverWhenNoTarget = c.getBoolean("homing.hover_when_no_target", true);
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
            arrow.setGravity(false);
            arrow.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            arrows.add(arrow.getUniqueId());
            expireAt.put(arrow.getUniqueId(),
                    System.currentTimeMillis() + lifetimeMillis);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow arrow)) return;

        Byte tag = arrow.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        if (tag == null || tag != (byte) 1) return;

        arrow.setGravity(false);
        Vector n = (e.getHitBlockFace() != null)
                ? e.getHitBlockFace().getDirection()
                : new Vector(0, 1, 0);

        arrow.teleport(arrow.getLocation().add(n.multiply(0.25)));
        arrow.setVelocity(new Vector(0, 0, 0));
    }

    private void startTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            Iterator<UUID> it = arrows.iterator();
            while (it.hasNext()) {
                UUID id = it.next();
                AbstractArrow arrow = findArrow(id);

                Long exp = expireAt.get(id);
                if (arrow == null || exp == null || System.currentTimeMillis() > exp) {
                    if (arrow != null) arrow.remove();
                    it.remove();
                    expireAt.remove(id);
                    continue;
                }

                arrow.setGravity(false);

                LivingEntity target = findNearestMob(arrow);
                if (target == null) {
                    if (hoverWhenNoTarget) {
                        arrow.setVelocity(new Vector(0, 0, 0));
                    }
                    continue;
                }

                Vector dir = target.getEyeLocation().toVector()
                        .subtract(arrow.getLocation().toVector())
                        .normalize();

                arrow.setVelocity(dir.multiply(arrowSpeed));
            }
        }, 1L, 1L);
    }

    private LivingEntity findNearestMob(AbstractArrow arrow) {
        double best = range * range;
        LivingEntity chosen = null;

        for (LivingEntity e : arrow.getWorld().getLivingEntities()) {
            if (e instanceof Player || e instanceof ArmorStand) continue;
            double d = e.getLocation().distanceSquared(arrow.getLocation());
            if (d < best) {
                best = d;
                chosen = e;
            }
        }
        return chosen;
    }

    private AbstractArrow findArrow(UUID id) {
        for (World w : Bukkit.getWorlds()) {
            for (AbstractArrow a : w.getEntitiesByClass(AbstractArrow.class)) {
                if (a.getUniqueId().equals(id)) return a;
            }
        }
        return null;
    }
}
