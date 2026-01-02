package me.galaxy.homingbow;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HomingBowPlugin extends JavaPlugin implements Listener {

    private NamespacedKey homingKey;
    private final Set<UUID> arrows = ConcurrentHashMap.newKeySet();

    private int customModelData;
    private boolean enabled;
    private double range;
    private double turnRate;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        homingKey = new NamespacedKey(this, "homing");
        loadConfigValues();
        Bukkit.getPluginManager().registerEvents(this, this);
        startTask();
    }

    private void loadConfigValues() {
        FileConfiguration c = getConfig();
        enabled = c.getBoolean("homing.enabled", true);
        range = c.getDouble("homing.range", 25.0);
        turnRate = c.getDouble("homing.turn_rate", 0.22);
        customModelData = c.getInt("bow_match.custom_model_data", 0);
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        if (!enabled) return;
        if (!(e.getEntity() instanceof Player)) return;

        ItemStack bow = e.getBow();
        if (bow == null) return;

        ItemMeta meta = bow.getItemMeta();
        if (meta == null || !meta.hasCustomModelData()) return;
        if (meta.getCustomModelData() != customModelData) return;

        if (e.getProjectile() instanceof AbstractArrow arrow) {
            arrow.getPersistentDataContainer().set(homingKey, PersistentDataType.BYTE, (byte) 1);
            arrows.add(arrow.getUniqueId());
        }
    }

    private void startTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            Iterator<UUID> it = arrows.iterator();
            while (it.hasNext()) {
                UUID id = it.next();
                AbstractArrow arrow = findArrow(id);
                if (arrow == null || arrow.isDead()) {
                    it.remove();
                    continue;
                }

                LivingEntity target = findTarget(arrow);
                if (target == null) continue;

                Vector dir = target.getEyeLocation().toVector()
                        .subtract(arrow.getLocation().toVector())
                        .normalize();

                double speed = arrow.getVelocity().length();
                Vector newVel = arrow.getVelocity().multiply(1 - turnRate)
                        .add(dir.multiply(turnRate))
                        .normalize()
                        .multiply(speed);

                arrow.setVelocity(newVel);
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

    private LivingEntity findTarget(AbstractArrow arrow) {
        double best = range * range;
        LivingEntity chosen = null;

        for (LivingEntity e : arrow.getWorld().getLivingEntities()) {
            if (e instanceof ArmorStand) continue;
            if (e.isDead()) continue;

            double d = e.getLocation().distanceSquared(arrow.getLocation());
            if (d < best) {
                best = d;
                chosen = e;
            }
        }
        return chosen;
    }
}
