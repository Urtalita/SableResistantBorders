package org.portality.sableresistantborders;

import com.simibubi.create.AllDamageTypes;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EnderRedstoneField {
    private static final double range = 20;

    private final double maxIntencity;
    private final double minIntencity;

    private double intencity = 1.5;
    private RandomSource source = RandomSource.createNewThreadLocalInstance();

    private double targetIntencity = 2;

    private HashMap<UUID, Double> portalDistortionMap = new HashMap<>();

    EnderRedstoneField(double maxIntencity, double minIntencity){
        this.maxIntencity = maxIntencity;
        this.minIntencity = minIntencity;
    }

    private void intencityRandomStep(ServerLevel serverLevel){
        double range = maxIntencity - minIntencity;

        if(serverLevel.getGameTime() % 100 == 0){
            targetIntencity = source.nextFloat() * range + minIntencity;
        }

        double step = range / 100;

        float direction;

        if(intencity > targetIntencity){
            direction = source.nextFloat() - 0.56f;
        } else {
            direction = source.nextFloat() - 0.44f;
        }
        double finalStep = direction * step;

        intencity = Mth.clamp(intencity + finalStep, minIntencity, maxIntencity);
    }

    private double getDistanceToWorldBorder(ServerLevel serverLevel, double x, double z){
        return Math.abs(serverLevel.getWorldBorder().getDistanceToBorder(x, z));
    }

    private boolean isOutsideWorldBorder(ServerLevel serverLevel, double x, double z){
        return !serverLevel.getWorldBorder().isWithinBounds(x, z);
    }

    private Vec3 getOppositeWbDirection(ServerLevel serverLevel, double x, double z){
        double shiftedX = x - serverLevel.getWorldBorder().getCenterX();
        double shiftedZ = z - serverLevel.getWorldBorder().getCenterZ();

        double px = 0;
        double pz = 0;

        if(Math.abs(shiftedX) > shiftedZ && shiftedX > 0) px = -1;
        if(Math.abs(shiftedX) > shiftedZ && shiftedX < 0) px = 1;

        if(Math.abs(shiftedZ) > shiftedX && shiftedZ > 0) pz = -1;
        if(Math.abs(shiftedZ) > shiftedX && shiftedZ < 0) pz = 1;

        return new Vec3(px, 0, pz);
    }

    private Vec3 getWbDirection(ServerLevel serverLevel, double x, double z){
        Vec3 opposite = getOppositeWbDirection(serverLevel, x, z);
        return new Vec3(-opposite.x, -opposite.y, -opposite.z);
    }

    public double getIntensityAt(ServerLevel serverLevel , double x, double z){
        double distance = getDistanceToWorldBorder(serverLevel, x, z);
        distance = Math.max(distance, 1);

        if(Double.isNaN(Math.sqrt(distance))) return 0;
        if(Double.isInfinite(Math.sqrt(distance))) return 0;
        if(isOutsideWorldBorder(serverLevel, x, z)) return intencity;
        if(distance > range) return 0;

        return intencity / Math.sqrt(distance);
    }

    public boolean hasFieldAt(ServerLevel serverLevel ,double x, double z){
        double intensity = getIntensityAt(serverLevel, x, z);
        return !(intensity < 0.001);
    }

    public void removePortalDistortion(ServerPlayer player){
        if(!portalDistortionMap.containsKey(player.getUUID())){
            return;
        }

        Double value = portalDistortionMap.get(player.getUUID());
        value -= 1d / 60d / 20d / 3d;

        portalDistortionMap.put(player.getUUID(), value);
        if(value <= 0) portalDistortionMap.remove(player.getUUID());
    }

    public void tick(ServerLevel level){
        intencityRandomStep(level);

        if(level.getGameTime() % 500 == 0){
            for (Entity entity : level.getAllEntities()){
                if(entity instanceof LivingEntity){
                    if(entity instanceof Player){continue;}
                    if(isOutsideWorldBorder(level, entity.getX(), entity.getZ())){
                        entity.kill();
                    }
                }
            }
        }

        for (ServerPlayer serverPlayer : level.players()){
            double x = serverPlayer.position().x;
            double z = serverPlayer.position().z;

            if(!hasFieldAt(level, x, z)) {
                removePortalDistortion(serverPlayer);
                continue;
            }

            double intensity = getIntensityAt(level, x, z);

            if(handleBackTank(serverPlayer)) continue;

            if(!portalDistortionMap.containsKey(serverPlayer.getUUID())){
                portalDistortionMap.put(serverPlayer.getUUID(), 0d);
                continue;
            }

            Double value = portalDistortionMap.get(serverPlayer.getUUID());
            value = Math.min(value + (intensity * intensity) / 200, 1);

            portalDistortionMap.put(serverPlayer.getUUID(), value);
            serverPlayer.sendSystemMessage(Component.literal("Напряжённость поля: " + prepareNumberForComponent(intensity) +
                    " ↸╎ᓭℸ\uD835\uDE79∷ℸ╎\uD835\uDE79リ: ").append(prepareComponent(value)), true);
        }

        for(Map.Entry<UUID, Double> entry : portalDistortionMap.entrySet()){
            Entity entity = level.getEntity(entry.getKey());
            if(!(entity instanceof ServerPlayer serverPlayer)) continue;

            double x = serverPlayer.position().x;
            double z = serverPlayer.position().z;

            if(!hasFieldAt(level, x, z)) {
                serverPlayer.sendSystemMessage(Component.literal("↸╎ᓭℸ\uD835\uDE79∷ℸ╎\uD835\uDE79リ: ").append(prepareComponent(entry.getValue())), true);
            }

            handlePortalDistortion(serverPlayer, level);
        }
    }

    private boolean handleBackTank(ServerPlayer player){
        if(!hasBacktank(player)) return false;

        ItemStack backTank = player.getItemBySlot(EquipmentSlot.CHEST);
        double air = backTank.getOrDefault(AllDataComponents.BACKTANK_AIR, 0);

        air -= getIntensityAt(player.serverLevel(), player.getX(), player.getZ()) / 40;
        if(air <= 0) return false;

        backTank.set(AllDataComponents.BACKTANK_AIR, (int) air);

        player.sendSystemMessage(Component.literal("Расход воздуха в секунду: " + prepareNumberForComponent(intencity)), true);

        return true;
    }

    private boolean hasBacktank(ServerPlayer player){
        ItemStack stack = player.getItemBySlot(EquipmentSlot.CHEST);
        return AllItems.COPPER_BACKTANK.isIn(stack) || AllItems.NETHERITE_BACKTANK.isIn(stack);
    }

    private void handlePortalDistortion(ServerPlayer serverPlayer, ServerLevel serverLevel){
        Double distortion = portalDistortionMap.get(serverPlayer.getUUID());
        if(serverPlayer.isCreative()) return;
        if(distortion == null) return;

        int timeBetweenTeleports = 1000;
        timeBetweenTeleports = (int) Math.max(timeBetweenTeleports * (1 - distortion) , 100);

        if(serverLevel.getGameTime() % Math.max(timeBetweenTeleports, 1) == 0){
            serverPlayer.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 2));
            serverPlayer.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 80, 1));
            teleport(serverLevel, serverPlayer);
        }

        serverLevel.sendParticles(
                ParticleTypes.DRAGON_BREATH, // Тип частиц
                serverPlayer.getX(), serverPlayer.getY() + serverPlayer.getEyeHeight() / 2, serverPlayer.getZ(),
                (int) (3 * distortion),
                0.5, 0.5, 0.5,       // Разброс
                0.02 * (distortion * 2)                 // Скорость
        );

        if(distortion > 0.4 && serverLevel.getGameTime() % 5 == 0){
            BlockPos center = serverPlayer.blockPosition();
            Iterable<BlockPos> blockPoses = BlockPos.betweenClosed(
                    center.offset(-2, -2, -2),
                    center.offset(2, 2, 2)
            );

            for (BlockPos mutablePos : blockPoses) {
                BlockPos pos = mutablePos.immutable();

                if (serverLevel.isInWorldBounds(pos)) {
                    BlockState state = serverLevel.getBlockState(pos);
                    if (state.hasProperty(BlockStateProperties.POWERED)) {
                        BlockState newState = state.cycle(BlockStateProperties.POWERED);
                        serverLevel.setBlock(pos, newState, Block.UPDATE_ALL);
                    }

                    if (state.hasProperty(BlockStateProperties.LIT)) {
                        BlockState newState = state.cycle(BlockStateProperties.LIT);
                        serverLevel.setBlock(pos, newState, Block.UPDATE_ALL);
                    }

                    if (state.hasProperty(BlockStateProperties.POWER)) {
                        BlockState newState = state.setValue(BlockStateProperties.POWER, source.nextInt(0, 15));
                        serverLevel.setBlock(pos, newState, Block.UPDATE_ALL);
                    }
                }
            }

        }
    }

    private void teleport(ServerLevel level, ServerPlayer entityLiving){
        if (!level.isClientSide) {
            for(int i = 0; i < 16; ++i) {
                double d0 = entityLiving.getX() + (entityLiving.getRandom().nextDouble() - (double)0.5F) * (double)16.0F;
                double d1 = Mth.clamp(entityLiving.getY() + (double)(entityLiving.getRandom().nextInt(16) - 8), (double)level.getMinBuildHeight(), (double)(level.getMinBuildHeight() + ((ServerLevel)level).getLogicalHeight() - 1));
                double d2 = entityLiving.getZ() + (entityLiving.getRandom().nextDouble() - (double)0.5F) * (double)16.0F;
                if (entityLiving.isPassenger()) {
                    entityLiving.stopRiding();
                }

                Vec3 vec3 = entityLiving.position();
                EntityTeleportEvent.ChorusFruit event = EventHooks.onChorusFruitTeleport(entityLiving, d0, d1, d2);

                if (event.isCanceled()) {
                    return;
                }

                if(!level.getWorldBorder().isWithinBounds(event.getTargetX(), event.getTargetZ())){
                    return;
                }

                if (entityLiving.randomTeleport(event.getTargetX(), event.getTargetY(), event.getTargetZ(), true)) {
                    level.gameEvent(GameEvent.TELEPORT, vec3, GameEvent.Context.of(entityLiving));
                    SoundSource soundsource;
                    SoundEvent soundevent;

                    soundevent = SoundEvents.CHORUS_FRUIT_TELEPORT;
                    soundsource = SoundSource.PLAYERS;

                    level.playSound((Player)null, entityLiving.getX(), entityLiving.getY(), entityLiving.getZ(), soundevent, soundsource);
                    entityLiving.resetFallDistance();
                    break;
                }
            }

            Player player = (Player) entityLiving;
            player.resetCurrentImpulseContext();
        }
    }

    private Component prepareComponent(double number){
        int level = Math.toIntExact(Math.round(number * 10));
        if(level == 0) level = 1;
        Component inner = Component.literal("#".repeat(level)).withStyle(ChatFormatting.DARK_AQUA);
        return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY).append(inner).append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));
    }

    private String prepareNumberForComponent(double number){
        String measuredIntensity = String.valueOf(Math.round(number * 100) / 100d);
        if(measuredIntensity.length() == 3) measuredIntensity = measuredIntensity + "0";
        if(measuredIntensity.length() == 1) measuredIntensity = measuredIntensity + ".00";
        return measuredIntensity;
    }
}
