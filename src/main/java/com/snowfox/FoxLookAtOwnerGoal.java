package com.snowfox;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import net.minecraft.world.entity.ai.goal.Goal;

final class FoxLookAtOwnerGoal extends Goal {
        private final net.minecraft.world.entity.animal.fox.Fox fox;
    private final Pet pet;
    private final SnowFoxPlugin plugin;
        FoxLookAtOwnerGoal(SnowFoxPlugin plugin, net.minecraft.world.entity.animal.fox.Fox fox, Pet pet) {
            this.plugin = plugin;
            this.fox = fox;
            this.pet = pet;
            setFlags(EnumSet.of(Goal.Flag.LOOK));
        }
        @Override public boolean canUse() {
            Player p = Bukkit.getPlayer(pet.owner);
            if (p == null || !p.getWorld().getUID().equals(fox.getBukkitEntity().getWorld().getUID())
                    || fox.distanceToSqr(((CraftPlayer) p).getHandle()) >= 64.0) return false;
            if (!pet.stay && (fox.getTarget() != null || preyNearby())) return false;
            return true;
        }
        private boolean preyNearby() {
            org.bukkit.entity.Entity bukkitFox = fox.getBukkitEntity();
            for (org.bukkit.entity.Entity nearby : bukkitFox.getNearbyEntities(10, 5, 10)) {
                if (nearby instanceof org.bukkit.entity.Chicken && nearby.isValid()) return true;
            }
            return false;
        }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public void tick() {
            if (!canUse()) return;
            Player p = Bukkit.getPlayer(pet.owner);
            if (p != null) fox.getLookControl().setLookAt(((CraftPlayer) p).getHandle(), 10.0f, fox.getMaxHeadXRot());
        }
    }
