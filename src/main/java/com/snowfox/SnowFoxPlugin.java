package com.snowfox;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import java.io.*;
import java.util.*;
import org.bukkit.craftbukkit.entity.CraftFox;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowParentGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;

public final class SnowFoxPlugin extends JavaPlugin implements Listener {
    static final UUID ORIGINAL = UUID.fromString("3c2db120-d8f9-3718-bdc3-45e02d25bb6b");
    final Map<UUID, Pet> pets = new HashMap<>();
    final Map<UUID, UUID> owners = new HashMap<>();
    final Map<UUID, Pending> dropped = new HashMap<>();
    File file;
    double followStart = 4.0, followStop = 2.0, followSpeed = 1.15, teleportDistance = 24.0;
    int followRepathTicks = 10, stuckCheckTicks = 40, stuckChecksBeforeTeleport = 4;
    boolean snowflakeParticlesEnabled = true;
    boolean wolfStyleCombatEnabled = false;
    boolean jumpStyleCombat = true;
    int particleAmount = 1;
    boolean defaultAging = false;
    final SnowFoxSettings settings = new SnowFoxSettings(this);
    final PetStorage storage = new PetStorage(this);
    final SnowFoxCommands commands = new SnowFoxCommands(this);
    void loadSettings() { settings.loadSettings(); }
    void load() { storage.load(); }
    void save() { storage.save(); }
    long ticks;
    NamespacedKey petKey;
    final Map<UUID, Long> eatingUntil = new HashMap<>();
    final Map<UUID, net.minecraft.world.entity.animal.fox.Fox> wolfGoalsInstalled = new HashMap<>();
    final Map<UUID, UUID> combatTargets = new HashMap<>();
    final Map<UUID, Long> combatExpires = new HashMap<>();

    @Override public void onEnable() {
        getDataFolder().mkdirs();
        petKey = new NamespacedKey(this, "owner");
        file = new File(getDataFolder(), "pets.yml");
        saveDefaultConfig();
        loadSettings();
        load();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (String name : List.of("snowfox","snowfoxrename","snowfoxstay","snowfoxadopt","snowfoxfix","snowfoxtrust","snowfoxreset")) {
            PluginCommand command = getCommand(name);
            if (command != null) { command.setExecutor(commands); command.setTabCompleter(commands); }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 5, 5);
        Bukkit.getScheduler().runTaskTimer(this, this::save, 1200, 1200);
    }
    @Override public void onDisable() { save(); }
    void tell(Player p, String s) { p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&bSnowFox &7» " + s)); }
    void notifyMovement(Player p, Pet pet, String message) {
        if (pet == null || pet.movementMessages) tell(p, message);
    }
    void notifyItem(Player p, Pet pet, String message) {
        if (pet == null || pet.itemMessages) tell(p, message);
    }
    boolean allowed(Player p) { return p.getUniqueId().equals(ORIGINAL) || p.hasPermission("snowfox.use"); }
    Fox find(Pet pet, boolean loadChunk) {
        if (pet == null || pet.fox == null) return null;
        Entity e = Bukkit.getEntity(pet.fox);
        if (!(e instanceof Fox) && loadChunk && pet.last != null && pet.last.getWorld() != null) {
            pet.last.getChunk().load();
            e = Bukkit.getEntity(pet.fox);
        }
        return e instanceof Fox fox && fox.isValid() ? fox : null;
    }
    void protect(Fox fox, Pet pet) {
        fox.getPersistentDataContainer().set(petKey, PersistentDataType.STRING, pet.owner.toString());
        fox.setInvulnerable(true);
        fox.setPersistent(true);
        fox.setRemoveWhenFarAway(false);
        fox.setCanPickupItems(true);
        fox.setAgeLock(!pet.aging);
        fox.setFoxType(pet.variant);
        fox.setCustomName(pet.name);
        fox.setCustomNameVisible(false);
        installWolfGoals(fox, pet);
        fox.setAI(true);
        fox.setGravity(true);
        Player p = Bukkit.getPlayer(pet.owner);
        if (p != null) fox.setFirstTrustedPlayer(p);
    }
    void installWolfGoals(Fox fox, Pet pet) {
        net.minecraft.world.entity.animal.fox.Fox handle = ((CraftFox) fox).getHandle();
        if (wolfGoalsInstalled.get(fox.getUniqueId()) == handle) return;
        wolfGoalsInstalled.put(fox.getUniqueId(), handle);
        // Baby foxes should follow their player, not the vanilla nearest adult fox.
        // Remove only the vanilla parent-following goal; retain hunting and other fox AI.
        for (var wrapped : new ArrayList<>(handle.goalSelector.getAvailableGoals())) {
            if (wrapped.getGoal() instanceof FollowParentGoal) {
                handle.goalSelector.removeGoal(wrapped.getGoal());
            }
        }
        handle.goalSelector.addGoal(0, new FoxStayGoal(this, handle, pet));
        handle.goalSelector.addGoal(2, new FoxOwnerCombatGoal(handle, pet));
        handle.goalSelector.addGoal(4, new FoxFollowOwnerGoal(this, handle, pet));
        handle.goalSelector.addGoal(7, new LookAtPlayerGoal(handle, net.minecraft.world.entity.LivingEntity.class, 8.0F));
        handle.goalSelector.addGoal(8, new RandomLookAroundGoal(handle));
    }

    final class FoxOwnerCombatGoal extends Goal {
        private final net.minecraft.world.entity.animal.fox.Fox fox;
        private final Pet pet;
        private int cooldown;
        FoxOwnerCombatGoal(net.minecraft.world.entity.animal.fox.Fox fox, Pet pet) {
            this.fox = fox;
            this.pet = pet;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
        }
        private LivingEntity target() {
            if (!wolfStyleCombatEnabled || !pet.combat || pet.stay) return null;
            UUID id = combatTargets.get(fox.getUUID());
            if (id == null || ticks >= combatExpires.getOrDefault(fox.getUUID(), 0L)) return null;
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof LivingEntity victim) || victim instanceof Player || victim instanceof Fox
                    || !victim.isValid() || victim.isDead()
                    || victim.getWorld() != fox.getBukkitEntity().getWorld()
                    || victim.getLocation().distanceSquared(fox.getBukkitEntity().getLocation()) > 256) return null;
            return victim;
        }
        @Override public boolean canUse() { return target() != null; }
        @Override public boolean canContinueToUse() { return target() != null; }
        @Override public void stop() { fox.getNavigation().stop(); }
        @Override public void tick() {
            LivingEntity victim = target();
            if (victim == null) return;
            fox.getLookControl().setLookAt(victim.getLocation().getX(), victim.getEyeLocation().getY(), victim.getLocation().getZ());
            fox.getNavigation().moveTo(victim.getLocation().getX(), victim.getLocation().getY(), victim.getLocation().getZ(), 1.3);
            if (cooldown > 0) cooldown--;
            double distance = victim.getLocation().distanceSquared(fox.getBukkitEntity().getLocation());
            if (jumpStyleCombat && distance < 16 && fox.onGround()) fox.getJumpControl().jump();
            if (distance < 3.0 && cooldown == 0) {
                victim.damage(2.0, (Fox) fox.getBukkitEntity());
                cooldown = 20;
            }
        }
    }

    void sit(Fox fox, Pet pet, boolean value) {
        pet.stay = value;
        pet.seat = value ? fox.getLocation().clone() : null;
        pet.previous = null; pet.lastGoal = null; pet.stuck = 0; pet.repath = 0;
        fox.getPathfinder().stopPathfinding();
        if (value) {
            var motion = ((CraftFox) fox).getHandle().getDeltaMovement();
            ((CraftFox) fox).getHandle().setDeltaMovement(0.0, motion.y, 0.0);
        }
        fox.setSitting(value);
        fox.setAI(true);
        fox.setGravity(true);
        save();
    }
    void summon(Player p) { summon(p, Fox.Type.SNOW); }
    void summon(Player p, Fox.Type variant) {
        Pet pet = pets.computeIfAbsent(p.getUniqueId(), Pet::new);
        Fox fox = find(pet, true);
        if (pet.fox == null) { pet.variant = variant; pet.aging = defaultAging; }
        if (pet.fox != null && fox == null) {
            tell(p, "&cRegistered fox missing. Use /snowfoxreset only if it was deleted."); return;
        }
        if (fox == null) {
            fox = p.getWorld().spawn(p.getLocation(), Fox.class);
            pet.fox = fox.getUniqueId();
            owners.put(pet.fox, pet.owner);
        } else fox.teleport(p.getLocation());
        protect(fox, pet);
        sit(fox, pet, false);
        pet.last = fox.getLocation().clone();
        tell(p, "&aYour fox is here!");
    }
    void despawn(Player p, Pet pet, Fox fox) {
        if (pet == null || pet.fox == null) { tell(p, "&cNo registered fox."); return; }
        if (fox == null) { tell(p, "&cFox is unavailable. Registration kept to avoid losing a fox."); return; }
        UUID id = pet.fox;
        ItemStack held = fox.getEquipment().getItemInMainHand();
        if (held != null && !held.getType().isAir()) fox.getWorld().dropItemNaturally(fox.getLocation(), held.clone());
        fox.remove();
        wolfGoalsInstalled.remove(id);
        combatTargets.remove(id);
        combatExpires.remove(id);
        eatingUntil.remove(id);
        owners.remove(id);
        pet.fox = null;
        pet.stay = false;
        pet.seat = null;
        pet.last = null;
        pet.previous = null;
        save();
        tell(p, "&aFox despawned. You can summon a different variant.");
    }
    void dropHeldItem(Player p, Fox fox) {
        if (fox == null) { tell(p, "&cFox unavailable."); return; }
        ItemStack held = fox.getEquipment().getItemInMainHand();
        if (held == null || held.getType().isAir()) { tell(p, "&eYour fox isn't holding anything."); return; }
        fox.getEquipment().setItemInMainHand(null);
        fox.getWorld().dropItemNaturally(fox.getLocation(), held.clone());
        tell(p, "&aYour fox dropped its item.");
    }
    void tick() {
        ticks += 5;
        dropped.entrySet().removeIf(e -> ticks >= e.getValue().expires());
        eatingUntil.entrySet().removeIf(e -> { if (ticks < e.getValue()) return false; Entity entity = Bukkit.getEntity(e.getKey()); if (entity instanceof Fox fox) setEating(fox, false); return true; });
        for (Pet pet : pets.values()) {
            Fox fox = find(pet, false);
            if (fox == null) continue;
            protect(fox, pet);
            Player owner = Bukkit.getPlayer(pet.owner);
            pet.last = fox.getLocation().clone();
            if (owner == null || pet.stay) {
                fox.getPathfinder().stopPathfinding();
                var motion = ((CraftFox) fox).getHandle().getDeltaMovement();
                ((CraftFox) fox).getHandle().setDeltaMovement(0.0, motion.y, 0.0);
                fox.setSitting(true);
            } else {
                fox.setSitting(false);
                if (owner.getWorld() != fox.getWorld()
                        || fox.getLocation().distanceSquared(owner.getLocation()) > teleportDistance * teleportDistance) {
                    fox.teleport(owner.getLocation());
                    pet.stuck = 0;
                    pet.previous = null;
                } else if (ticks % (5L * Math.max(1, (stuckCheckTicks + 4) / 5)) == 0) {
                    double distance = fox.getLocation().distanceSquared(owner.getLocation());
                    if (distance > followStart * followStart && pet.previous != null
                            && pet.previous.getWorld() == fox.getWorld()
                            && pet.previous.distanceSquared(fox.getLocation()) < 0.16) pet.stuck++;
                    else pet.stuck = 0;
                    pet.previous = fox.getLocation().clone();
                    if (pet.stuck >= stuckChecksBeforeTeleport) {
                        fox.teleport(owner.getLocation());
                        pet.stuck = 0;
                    }
                }
            }
            if (snowflakeParticlesEnabled && pet.particles && particleAmount > 0 && ticks % 20 == 0) fox.getWorld().spawnParticle(Particle.SNOWFLAKE, fox.getLocation().add(0, 0.5, 0), particleAmount, .15, .15, .15, .005);
        }
        for (UUID id : new ArrayList<>(dropped.keySet())) {
            Pending pending = dropped.get(id);
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Item item) || !item.isValid()) { dropped.remove(id); continue; }
            Fox fox = find(pets.get(pending.owner()), false);
            if (fox == null || fox.getWorld() != item.getWorld()) continue;
            BoundingBox box = fox.getBoundingBox().expand(.12, .15, .12);
            if (!box.overlaps(item.getBoundingBox())) continue;
            ItemStack incoming = item.getItemStack().clone();
            ItemStack old = fox.getEquipment().getItemInMainHand().clone();
            fox.getEquipment().setItemInMainHand(incoming);
            if (pets.get(pending.owner()).stay && isChicken(incoming)) {
                eatChicken(fox, pets.get(pending.owner()));
                UUID foxId = fox.getUniqueId();
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    Entity current = Bukkit.getEntity(foxId);
                    if (current instanceof Fox live && isChicken(live.getEquipment().getItemInMainHand())) live.getEquipment().setItemInMainHand(null);
                }, 30L);
            }
            item.remove();
            dropped.remove(id);
            Player recipient = Bukkit.getPlayer(pending.owner());
            if (recipient != null) notifyItem(recipient, pets.get(pending.owner()), "&aItem swapped!");
            if (!old.getType().isAir()) {
                Item returned = fox.getWorld().dropItemNaturally(fox.getLocation(), old);
                returned.setPickupDelay(20);
            }
        }
    }
    boolean isManaged(Fox fox) {
        return owners.containsKey(fox.getUniqueId()) || fox.getPersistentDataContainer().has(petKey, PersistentDataType.STRING);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Pet pet = pets.get(e.getPlayer().getUniqueId());
        if (pet != null && pet.fox != null) dropped.put(e.getItemDrop().getUniqueId(), new Pending(pet.owner, ticks + 100));
    }
    @EventHandler public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Fox fox) || !isManaged(fox)) return;
        if (dropped.containsKey(e.getItem().getUniqueId())) { e.setCancelled(true); return; }
        if (e.getItem().getItemStack().getType() != Material.CHICKEN
                && e.getItem().getItemStack().getType() != Material.COOKED_CHICKEN) e.setCancelled(true);
    }
    @EventHandler public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Fox fox && owners.containsKey(fox.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler public void onTarget(EntityTargetEvent e) {
        if (e.getEntity() instanceof Fox fox && owners.containsKey(fox.getUniqueId())) {
            if (e.getTarget() instanceof Player || e.getTarget() instanceof Fox
                    || (pets.get(owners.get(fox.getUniqueId())) != null
                    && pets.get(owners.get(fox.getUniqueId())).stay)) e.setCancelled(true);
        }
    }
    @EventHandler(ignoreCancelled = true)
    public void onWolfStyleCombat(EntityDamageByEntityEvent event) {
        if (!wolfStyleCombatEnabled) return;
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) attacker = shooter;
        if (attacker instanceof Player owner && event.getEntity() instanceof LivingEntity target) {
            directFoxAttack(owner, target);
        }
        if (event.getEntity() instanceof Player owner && attacker instanceof LivingEntity target) {
            directFoxAttack(owner, target);
        }
    }
    void directFoxAttack(Player owner, LivingEntity target) {
        if (target instanceof Player || target instanceof Fox || !target.isValid() || target.isDead()) return;
        Pet pet = pets.get(owner.getUniqueId());
        if (pet == null || pet.stay || !pet.combat) return;
        Fox fox = find(pet, false);
        if (fox == null || fox.getWorld() != target.getWorld()
                || fox.getLocation().distanceSquared(target.getLocation()) > 16 * 16) return;
        combatTargets.put(fox.getUniqueId(), target.getUniqueId());
        combatExpires.put(fox.getUniqueId(), ticks + 200);
        fox.setTarget(target);
    }
    @EventHandler public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Fox fox)) return;
        UUID id = owners.get(fox.getUniqueId());
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!id.equals(p.getUniqueId())) { tell(p, "&cThis fox belongs to another player."); return; }
        Pet pet = pets.get(id);
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (pet != null && pet.stay && isChicken(hand)) {
            if (hand.getAmount() == 1) p.getInventory().setItemInMainHand(null);
            else hand.setAmount(hand.getAmount() - 1);
            eatChicken(fox, pet);
            notifyItem(p, pet, "&aSnowy enjoyed the chicken!");
            return;
        }
        if (!hand.getType().isAir()) return;
        sit(fox, pet, !pet.stay);
        notifyMovement(p, pet, pet.stay ? "&eSnowy is sitting!" : "&aSnowy is following!");
    }
    boolean isChicken(ItemStack item) {
        if (item == null) return false;
        return switch (item.getType()) {
            case CHICKEN, COOKED_CHICKEN -> true;
            default -> false;
        };
    }
    void setEating(Fox fox, boolean eating) {
        try { fox.getClass().getMethod("setEating", boolean.class).invoke(fox, eating); }
        catch (ReflectiveOperationException ignored) { }
    }
    void eatChicken(Fox fox, Pet pet) {
        if (!pet.stay) return;
        setEating(fox, true);
        eatingUntil.put(fox.getUniqueId(), ticks + 30);
        fox.getWorld().playSound(fox.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.65f, 1.3f);
        fox.getWorld().spawnParticle(Particle.ITEM, fox.getLocation().add(0, .4, 0), 6, .12, .12, .12, .02, new ItemStack(Material.COOKED_CHICKEN));
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Pet pet = pets.get(e.getPlayer().getUniqueId());
        Fox fox = find(pet, false);
        if (fox != null) { fox.getPathfinder().stopPathfinding(); fox.setSitting(true); pet.last = fox.getLocation().clone(); }
        save();
    }
    @EventHandler public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Fox fox)) return;
        UUID id = owners.remove(fox.getUniqueId());
        if (id != null) {
            Pet pet = pets.get(id);
            if (pet != null) pet.fox = null;
            save();
        }
    }

}