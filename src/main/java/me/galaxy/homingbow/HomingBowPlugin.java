package me.galaxy.homingbow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
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

    // particles
    private boolean particlesEnabled;
    private Particle particleType;
    private int particleCount;
    private double offX, offY, offZ;

    private final MiniMessage mm = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadCfg();
        key = new NamespacedKey(this, "homing");
        Bukkit.getPluginManager().registerEvents(this, this);
        startTask();
        getLogger().info("HomingBow ENABLED");
    }

    private void loadCfg() {
        reloadConfig();
        FileConfiguration c = getConfig();

        customModelData = c.getInt("bow_match.custom_model_data", 0);

        range = c.getDouble("homing.range", 40.0);
        turnRate = c.getDouble("homing.turn_rate", 0.35);
        arrowSpeed = c.getDouble("homing.arrow_speed", 2.2);
        lifetimeMillis = c.getLong("homing.lifetime_seconds", 10) * 1000L;

        damageAmount = c.getDouble("damage.amount", 200.0);

        particlesEnabled = c.getBoolean("particles.enabled", true);
        String p = c.getString("particles.type", "END_ROD");
        try {
            particleType = Particle.valueOf(p.toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            particleType = Particle.END_ROD;
        }
        particleCount = Math.max(0, c.getInt("particles.count", 2));
        offX = c.getDouble("particles.offset.x", 0.03);
        offY = c.getDouble("particles.offset.y", 0.03);
        offZ = c.getDouble("particles.offset.z", 0.03);
    }

    // ====== Only change the item when user runs /lunarfix (so ItemsAdder texture won't break) ======
    private boolean isLunarBow(ItemStack item) {
        if (item == null || item.getType() != Material.BOW) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasCustomModelData() && meta.getCustomModelData() == customModelData;
    }

  private void applyLunarMeta(ItemStack bow) {
    if (!isLunarBow(bow)) return;

    ItemMeta meta = bow.getItemMeta();
    if (meta == null) return;

    int cmd = meta.getCustomModelData();

    // Durability OFF
    meta.setUnbreakable(true);
    meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
    if (meta instanceof Damageable dmg) dmg.setDamage(0);

    // Infinity I (látszik)
    meta.addEnchant(Enchantment.INFINITY, 1, true);

    // ✅ NÉV
    meta.displayName(
        mm.deserialize("<yellow><bold>Luni íj</bold>")
    );

    // ✅ LORE
    List<Component> lore = List.of(
        Component.empty(),
        mm.deserialize("<gray>• Sebzés: <white>" + (int) damageAmount + " <red>❤"),
        Component.empty(),
        mm.deserialize("<gold><bold>PASSZÍV</bold> <dark_gray>-</dark_gray> <yellow>🏹 Nyomkövetés <green>[+]</green>"),
        mm.deserialize("<gray>- Nyílvessző követi az ellenséges mobokat."),
        mm.deserialize("<dark_gray>(<white>40 blokk<dark_gray>)"),
        Component.empty(),
        Component.empty(),
        mm.deserialize("<gold><bold>LEGENDÁS FEGYVER</bold>")
    );
    meta.lore(lore);

    // CMD vissza (textúra védelem)
    meta.setCustomModelData(cmd);

    bow.setItemMeta(meta);
}

    // ================= SHOOT =================
    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        ItemStack bow = e.getBow();
        if (bow == null) return;

        // IMPORTANT: Do NOT edit item meta here (keeps ItemsAdder texture stable)
        if (!isLunarBow(bow)) return;

        if (!(e.getProjectile() instanceof AbstractArrow arrow)) return;

        arrow.setGravity(false);

        // Safe velocity (avoid NaN)
        Vector v = arrow.getVelocity();
        if (v.lengthSquared() < 1e-6) v = arrow.getLocation().getDirection();
        arrow.setVelocity(v.normalize().multiply(arrowSpeed));

        arrow.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        arrows.add(arrow.getUniqueId());
        expireAt.put(arrow.getUniqueId(), System.currentTimeMillis() + lifetimeMillis);
    }

    // ================= HIT =================
    @EventHandler
    public void onHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow arrow)) return;

        Byte tag = arrow.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        if (tag == null || tag != (byte) 1) return;

        // MOB HIT -> fixed damage + remove arrow
        if (e.getHitEntity() instanceof LivingEntity target) {
            Object s = arrow.getShooter();
            if (s instanceof LivingEntity shooter) target.damage(damageAmount, shooter);
            else target.damage(damageAmount);

            arrow.remove();
            arrows.remove(arrow.getUniqueId());
            expireAt.remove(arrow.getUniqueId());
            return;
        }

        // BLOCK HIT -> nudge so it doesn't stick
        arrow.setGravity(false);
        Vector push = (e.getHitBlockFace() != null)
                ? e.getHitBlockFace().getDirection()
                : new Vector(0, 1, 0);

        arrow.teleport(arrow.getLocation().add(push.multiply(0.25)));
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

                // particle trail
                if (particlesEnabled) {
                    arrow.getWorld().spawnParticle(
                            particleType,
                            arrow.getLocation(),
                            particleCount,
                            offX, offY, offZ,
                            0
                    );
                }

                LivingEntity target = findTarget(arrow);
                if (target == null) {
                    // keep moving forward safely
                    Vector v = arrow.getVelocity();
                    if (v.lengthSquared() < 1e-6) v = arrow.getLocation().getDirection();
                    arrow.setVelocity(v.normalize().multiply(arrowSpeed));
                    continue;
                }

                Vector to = target.getEyeLocation().toVector().subtract(arrow.getLocation().toVector());
                if (to.lengthSquared() < 1e-6) continue;

                Vector desired = to.normalize();
                Vector curVel = arrow.getVelocity();
                if (curVel.lengthSquared() < 1e-6) curVel = desired.multiply(0.01);

                Vector newDir = curVel.normalize().multiply(1.0 - turnRate).add(desired.multiply(turnRate));
                if (newDir.lengthSquared() < 1e-6) continue;

                arrow.setVelocity(newDir.normalize().multiply(arrowSpeed));
            }
        }, 1L, 1L);
    }

    private LivingEntity findTarget(AbstractArrow arrow) {
        double best = range * range;
        LivingEntity chosen = null;

        for (LivingEntity e : arrow.getWorld().getLivingEntities()) {
            if (e instanceof Player) continue;
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

    // ================= COMMAND: /lunarfix =================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("lunarfix")) return false;

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Csak játékos használhatja.");
            return true;
        }

        ItemStack inHand = p.getInventory().getItemInMainHand();
        if (!isLunarBow(inHand)) {
            p.sendMessage(Component.text("A kezedben a Lunar Bow legyen (ItemsAdder, CMD: " + customModelData + ")."));
            return true;
        }

        applyLunarMeta(inHand);
        p.sendMessage(Component.text("Lunar Bow frissítve: lore + Infinity I + unbreakable."));
        return true;
    }
}
