package com.snowfox;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import net.minecraft.world.entity.ai.goal.Goal;

final class FoxFollowOwnerGoal extends Goal {
        private final net.minecraft.world.entity.animal.fox.Fox fox;
    private final Pet pet;
    private final SnowFoxPlugin plugin;
        private int recalc;
        FoxFollowOwnerGoal(SnowFoxPlugin plugin, net.minecraft.world.entity.animal.fox.Fox fox, Pet pet) {
            this.plugin = plugin;
            this.fox = fox;
            this.pet = pet;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }
        private Player owner() { return Bukkit.getPlayer(pet.owner); }
        @Override public boolean canUse() {
            Player p = owner();
            return !pet.stay && fox.getTarget() == null && p != null && p.getWorld().getUID().equals(fox.getBukkitEntity().getWorld().getUID())
                    && fox.distanceToSqr(((CraftPlayer) p).getHandle()) > plugin.followStart * plugin.followStart;
        }
        @Override public boolean canContinueToUse() {
            Player p = owner();
            return !pet.stay && fox.getTarget() == null && p != null && p.getWorld().getUID().equals(fox.getBukkitEntity().getWorld().getUID())
                    && fox.distanceToSqr(((CraftPlayer) p).getHandle()) > plugin.followStop * plugin.followStop;
        }
        @Override public void start() { recalc = 0; }
        @Override public void stop() { if (fox.getTarget() == null) fox.getNavigation().stop(); }
        @Override public void tick() {
            Player p = owner();
            if (p == null || pet.stay) { fox.getNavigation().stop(); return; }
            if (fox.getTarget() != null) return;
            var target = ((CraftPlayer) p).getHandle();
            fox.getLookControl().setLookAt(target, 10.0f, fox.getMaxHeadXRot());
            if (--recalc <= 0) {
                recalc = plugin.followRepathTicks;
                fox.getNavigation().moveTo(target, plugin.followSpeed);
            }
        }
    }
