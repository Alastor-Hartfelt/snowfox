package com.snowfox;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import net.minecraft.world.entity.ai.goal.Goal;

final class FoxStayGoal extends Goal {
        private final net.minecraft.world.entity.animal.fox.Fox fox;
    private final Pet pet;
    private final SnowFoxPlugin plugin;
        FoxStayGoal(SnowFoxPlugin plugin, net.minecraft.world.entity.animal.fox.Fox fox, Pet pet) {
            this.plugin = plugin;
            this.fox = fox;
            this.pet = pet;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP, Goal.Flag.TARGET));
        }
        @Override public boolean canUse() { return pet.stay || Bukkit.getPlayer(pet.owner) == null; }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public void start() { holdPosition(); }
        @Override public void tick() { holdPosition(); }
        private void holdPosition() {
            fox.getNavigation().stop();
            fox.setTarget(null);
            var motion = fox.getDeltaMovement();
            fox.setDeltaMovement(0.0, motion.y, 0.0);
            fox.getMoveControl().setWantedPosition(fox.getX(), fox.getY(), fox.getZ(), 0.0);
        }
    }
