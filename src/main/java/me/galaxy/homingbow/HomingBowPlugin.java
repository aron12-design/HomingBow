package me.galaxy.homingbow;

import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HomingBowPlugin extends JavaPlugin implements Listener {

    private final Set<UUID> arrows = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> expireAt = new ConcurrentHashMap<>();

    private NamespacedKey key;

    private int customModelData;
    private double range;
    private double turnRate;
    private double arrowSpeed;
    private long lifetimeMillis;
    private double damageAmount;

    // Particles
    private boolean particlesEnabled;
    private Particle particleType;
    private int particleCount;
    private double offX, offY, offZ;
    private double particleSpeed;
    private boolean onlyWhenHoming;
    private Particle.DustOptions dustOptions; // REDSTONE-hoz

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        key = new NamespacedKey(this, "homing");
        Bukkit.getPluginManager().registerEvents(this, this);
        startTask();
        getLogger().info("HomingBow ENABLED");
    }

    private void loadConfigValues() {
        reloadConfig();
        FileConfiguration c = getConfig();

        customModelData = c.getInt("bow_match.custom_model_data", 0);

        range = c.getDouble("homing.range", 40.0);
        turnRate = c.getDouble("homing.turn_rate", 0.35);
        arrowSpeed = c.getDouble("homing.arrow_speed", 2.2);
        lifetimeMillis = c.getLong("homing.lifetime_seconds", 10) * 1000L;

        damageAmount = c.getDouble("damage.amount", 200.0);

        // Particles
        particlesEnabled = c.getBoolean("particles.enabled", false);
        String typeStr = c.getString("particles.type", "REDSTONE");
        particleType = Particle.valueOf(typeStr.toUpperCase(Locale.ROOT));

        particleCount = Math.max(0, c.getInt("particles.count", 2));
        offX = c.getDouble("particles.offset.x", 0.05);
        offY = c.getDouble("particles.offset.y", 0.05);
        offZ = c.getDouble("particles.offset.z", 0.05);
        particleSpeed = c.getDouble("particles.speed", 0.0);
        onlyWhenHoming = c.getBoolean("particles.only_when_homing", true);

        // REDSTONE dust szín
        double r = clamp01(c.getDouble("particles.dust.color.red", 1.0));
        double g = clamp01(c.getDouble("particles.dust.color.green", 0.0));
        double b = clamp01(c.getDouble("particles.dust.color.blue", 0.0));
        float size = (float) Math.max(0.1, c.getDouble("particles.dust.size", 1.0));

        // Bukkit Color 0-255
        Color color = Color.fromRGB((int) Math.round(r * 255), (int) Math.round(g * 255), (int) Math.round(b * 255));
        dustOptions = new Particle.DustOptions(color, size);
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    // ================= SHOOT =================
    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        ItemStack bow = e.getBow();
        if (bow == null) return;

        // ✅ durability bar OFF
        ItemMeta meta = bow.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            if (meta instanceof Damageable dmg) dmg.setDamage(0);
            bow.setItemMeta(meta);
        }

        meta = bow.getItemMeta();
        if (meta == null || !meta.hasCustomModelData()) return;
        if (meta.getCustomModelData() != customModelData) return;

        if (!(e.getProjectile() instanceof AbstractArrow arrow)) return;

        arrow.setGravity(false);

        // Safe initial velocity (avoid NaN)
        Vector v = arrow.getVelocity();
        if (v.lengthSquared() < 1e-6) v = arrow.getLocation().getDirection();
        arrow.setVelocity(v.normalize().multiply(arrowSpeed));

        arrow.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        arrows.add(arrow.getUniqueId());
        expireAt.put(arrow.getUniqueId(), System.currentTimeMillis() + lifetimeMillis);
    }

    // ================= HIT =================
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow arrow)) return;

        Byte tag = arrow.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        if (tag == null || tag != (byte) 1) return;

        // MOB HIT -> FIX damage + remove arrow
        if (e.getHitEntity() instanceof LivingEntity target) {
            Object shooterObj = arrow.getShooter();
            if (shooterObj instanceof LivingEntity shooter) target.damage(damageAmount, shooter);
            else target.damage(damageAmount);

            arrow.remove();
            arrows.remove(arrow.getUniqueId());
            expireAt.remove(arrow.getUniqueId());
            return;
        }

        // BLOCK HIT -> nudge out so it does not stick
        arrow.setGravity(false);
        Vector push = (e.getHitBlockFace() != null) ? e.getHitBlockFace().getDirection() : new Vector(0, 1, 0);
        arrow.teleport(arrow.getLocation().add(push.multiply(0.30)));
        arrow.setVelocity(push.multiply(0.10));
    }

    // ================= HOMING LOOP =================
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

                LivingEntity target = findTarget(arrow);

                // Particle
                if (particlesEnabled && (!onlyWhenHoming || target != null)) {
                    spawnParticle(arrow);
                }

                if (target == null) {
                    // keep moving forward
                    Vector v = arrow.getVelocity();
                    if (v.lengthSquared() < 1e-6) v = arrow.getLocation().getDirection();
                    arrow.setVelocity(v.normalize().multiply(arrowSpeed));
                    continue;
                }

                Vector toTarget = target.getEyeLocation().toVector().subtract(arrow.getLocation().toVector());
                if (toTarget.lengthSquared() < 1e-6) continue;
                Vector desired = toTarget.normalize();

                Vector currentVel = arrow.getVelocity();
                if (currentVel.lengthSquared() < 1e-6) currentVel = arrow.getLocation().getDirection().multiply(0.01);
                Vector current = currentVel.normalize();

                Vector newDir = current.multiply(1.0 - turnRate).add(desired.multiply(turnRate));
                if (newDir.lengthSquared() < 1e-6) continue;

                arrow.setVelocity(newDir.normalize().multiply(arrowSpeed));
            }
        }, 1L, 1L);
    }

    private void spawnParticle(AbstractArrow arrow) {
        Location loc = arrow.getLocation();
        World w = loc.getWorld();
        if (w == null) return;

       // Border-dust look: use DUST + DustOptions (compatible)
if (particleType == Particle.DUST) {
    w.spawnParticle(Particle.DUST, loc, particleCount, offX, offY, offZ, particleSpeed, dustOptions);
    return;
}

        // Generic particles (no extra data)
        w.spawnParticle(particleType, loc, particleCount, offX, offY, offZ, particleSpeed);
    }

    private LivingEntity findTarget(AbstractArrow arrow) {
        double best = range * range;
        LivingEntity chosen = null;

        for (LivingEntity e : arrow.getWorld().getLivingEntities()) {
            if (e instanceof Player) continue;       // never target players
            if (e instanceof ArmorStand) continue;
            if (e.isDead() || !e.isValid()) continue;

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
