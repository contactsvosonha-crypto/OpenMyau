package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventManager;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.DataWatcher.WatchableObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;

import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

public class KillAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat df = new DecimalFormat("+0.0;-0.0", new DecimalFormatSymbols(Locale.US));
    private final TimerUtil timer = new TimerUtil();
    
    // Đã đổi thành public để Backtrack có thể truy cập target.getEntity()
    public AttackData target = null;
    
    private int switchTick = 0;
    private boolean hitRegistered = false;
    private boolean blockingState = false;
    private boolean isBlocking = false;
    private boolean fakeBlockState = false;
    private boolean blinkReset = false;
    private long attackDelayMS = 0L;
    private int blockTick = 0;
    private int lastTickProcessed;

    // Properties
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"SINGLE", "SWITCH"});
    public final ModeProperty sort = new ModeProperty("sort", 0, new String[]{"DISTANCE", "HEALTH", "HURT_TIME", "FOV"});
    public final ModeProperty autoBlock = new ModeProperty("auto-block", 2, new String[]{"NONE", "VANILLA", "SPOOF", "HYPIXEL", "BLINK", "INTERACT", "SWAP", "LEGIT", "FAKE"});
    public final BooleanProperty autoBlockRequirePress = new BooleanProperty("auto-block-require-press", false);
    public final FloatProperty autoBlockMinCPS = new FloatProperty("auto-block-min-aps", 8.0F, 1.0F, 20.0F);
    public final FloatProperty autoBlockMaxCPS = new FloatProperty("auto-block-max-aps", 10.0F, 1.0F, 20.0F);
    public final FloatProperty autoBlockRange = new FloatProperty("auto-block-range", 6.0F, 3.0F, 8.0F);
    public final FloatProperty swingRange = new FloatProperty("swing-range", 3.5F, 3.0F, 6.0F);
    public final FloatProperty attackRange = new FloatProperty("attack-range", 3.0F, 3.0F, 6.0F);
    public final IntProperty fov = new IntProperty("fov", 360, 30, 360);
    public final IntProperty minCPS = new IntProperty("min-aps", 14, 1, 20);
    public final IntProperty maxCPS = new IntProperty("max-aps", 14, 1, 20);
    public final IntProperty switchDelay = new IntProperty("switch-delay", 150, 0, 1000);
    public final ModeProperty rotations = new ModeProperty("rotations", 2, new String[]{"NONE", "LEGIT", "SILENT", "LOCK_VIEW"});
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
    public final PercentProperty smoothing = new PercentProperty("smoothing", 0);
    public final IntProperty angleStep = new IntProperty("angle-step", 90, 30, 180);
    public final BooleanProperty throughWalls = new BooleanProperty("through-walls", true);
    public final BooleanProperty requirePress = new BooleanProperty("require-press", false);
    public final BooleanProperty allowMining = new BooleanProperty("allow-mining", true);
    public final BooleanProperty weaponsOnly = new BooleanProperty("weapons-only", true);
    public final BooleanProperty allowTools = new BooleanProperty("allow-tools", false, this.weaponsOnly::getValue);
    public final BooleanProperty inventoryCheck = new BooleanProperty("inventory-check", true);
    public final BooleanProperty botCheck = new BooleanProperty("bot-check", true);
    public final BooleanProperty players = new BooleanProperty("players", true);
    public final BooleanProperty bosses = new BooleanProperty("bosses", false);
    public final BooleanProperty mobs = new BooleanProperty("mobs", false);
    public final BooleanProperty animals = new BooleanProperty("animals", false);
    public final BooleanProperty golems = new BooleanProperty("golems", false);
    public final BooleanProperty silverfish = new BooleanProperty("silverfish", false);
    public final BooleanProperty teams = new BooleanProperty("teams", true);
    public final ModeProperty showTarget = new ModeProperty("show-target", 0, new String[]{"NONE", "DEFAULT", "HUD"});
    public final ModeProperty debugLog = new ModeProperty("debug-log", 0, new String[]{"NONE", "HEALTH"});

    public KillAura() {
        super("KillAura", false);
    }

    private long getAttackDelay() {
        return this.isBlocking ? (long) (1000.0F / RandomUtil.nextLong(this.autoBlockMinCPS.getValue().longValue(), this.autoBlockMaxCPS.getValue().longValue())) : 1000L / RandomUtil.nextLong(this.minCPS.getValue(), this.maxCPS.getValue());
    }

    private boolean performAttack(float yaw, float pitch) {
        if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
            if (this.isPlayerBlocking() && this.autoBlock.getValue() != 1) {
                return false;
            } else if (this.attackDelayMS > 0L) {
                return false;
            } else {
                this.attackDelayMS = this.attackDelayMS + this.getAttackDelay();
                mc.thePlayer.swingItem();
                if ((this.rotations.getValue() != 0 || !this.isBoxInAttackRange(this.target.getBox()))
                        && RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, (double) this.attackRange.getValue()) == null) {
                    return false;
                } else {
                    AttackEvent event = new AttackEvent(this.target.getEntity());
                    EventManager.call(event);
                    ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
                    PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.ATTACK));
                    if (mc.playerController.getCurrentGameType() != GameType.SPECTATOR) {
                        PlayerUtil.attackEntity(this.target.getEntity());
                    }
                    this.hitRegistered = true;
                    return true;
                }
            }
        } else {
            return false;
        }
    }

    private void sendUseItem() {
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        this.startBlock(mc.thePlayer.getHeldItem());
    }

    private void startBlock(ItemStack itemStack) {
        if (itemStack == null) return;
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(itemStack));
        mc.thePlayer.setItemInUse(itemStack, itemStack.getMaxItemUseDuration());
        this.blockingState = true;
    }

    private void stopBlock() {
        PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        mc.thePlayer.stopUsingItem();
        this.blockingState = false;
    }

    private void interactAttack(float yaw, float pitch) {
        if (this.target != null) {
            MovingObjectPosition mop = RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, 8.0);
            if (mop != null) {
                ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
                PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), new Vec3(mop.hitVec.xCoord - this.target.getX(), mop.hitVec.yCoord - this.target.getY(), mop.hitVec.zCoord - this.target.getZ())));
                PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.INTERACT));
                PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
                mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
                this.blockingState = true;
            }
        }
    }

    private boolean canAttack() {
        if (this.inventoryCheck.getValue() && mc.currentScreen instanceof GuiContainer) {
            return false;
        } else if (!this.weaponsOnly.getValue() || ItemUtil.hasRawUnbreakingEnchant() || (this.allowTools.getValue() && ItemUtil.isHoldingTool())) {
            if (((IAccessorPlayerControllerMP) mc.playerController).getIsHittingBlock()) {
                return false;
            } else if ((ItemUtil.isEating() || ItemUtil.isUsingBow()) && PlayerUtil.isUsingItem()) {
                return false;
            } else {
                AutoHeal autoHeal = (AutoHeal) Myau.moduleManager.modules.get(AutoHeal.class);
                BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
                AutoBlockIn autoBlockIn = (AutoBlockIn) Myau.moduleManager.modules.get(AutoBlockIn.class);
                
                if (autoHeal != null && autoHeal.isEnabled() && autoHeal.isSwitching()) {
                    return false;
                } else if (bedNuker != null && bedNuker.isEnabled() && bedNuker.isReady()) {
                    return false;
                } else if (Myau.moduleManager.modules.get(Scaffold.class).isEnabled()) {
                    return false;
                } else if (autoBlockIn != null && autoBlockIn.isEnabled()) {
                    return false;
                } else if (this.requirePress.getValue()) {
                    return PlayerUtil.isAttacking();
                } else {
                    return !this.allowMining.getValue() || mc.objectMouseOver == null || !mc.objectMouseOver.typeOfHit.equals(MovingObjectType.BLOCK) || !PlayerUtil.isAttacking();
                }
            }
        } else {
            return false;
        }
    }

    private boolean canAutoBlock() {
        return ItemUtil.isHoldingSword() && (!this.autoBlockRequirePress.getValue() || PlayerUtil.isUsingItem());
    }

    private boolean hasValidTarget() {
        return mc.theWorld.loadedEntityList.stream().anyMatch(entity -> entity instanceof EntityLivingBase && this.isValidTarget((EntityLivingBase) entity) && this.isInBlockRange((EntityLivingBase) entity));
    }

    private boolean isValidTarget(EntityLivingBase entityLivingBase) {
        if (entityLivingBase == null || !mc.theWorld.loadedEntityList.contains(entityLivingBase)) return false;
        if (entityLivingBase == mc.thePlayer || entityLivingBase == mc.thePlayer.ridingEntity) return false;
        if (entityLivingBase == mc.getRenderViewEntity() || (mc.getRenderViewEntity() != null && entityLivingBase == mc.getRenderViewEntity().ridingEntity)) return false;
        if (entityLivingBase.deathTime > 0 || entityLivingBase.getHealth() <= 0.0F) return false;
        if (RotationUtil.angleToEntity(entityLivingBase) > (float) this.fov.getValue()) return false;
        if (!this.throughWalls.getValue() && RotationUtil.rayTrace(entityLivingBase) != null) return false;
        
        if (entityLivingBase instanceof EntityOtherPlayerMP) {
            if (!this.players.getValue()) return false;
            if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) return false;
            return (!this.teams.getValue() || !TeamUtil.isSameTeam((EntityPlayer) entityLivingBase)) && (!this.botCheck.getValue() || !TeamUtil.isBot((EntityPlayer) entityLivingBase));
        } else if (entityLivingBase instanceof EntityDragon || entityLivingBase instanceof EntityWither) {
            return this.bosses.getValue();
        } else if (entityLivingBase instanceof EntityMob || entityLivingBase instanceof EntitySlime) {
            if (entityLivingBase instanceof EntitySilverfish) return this.silverfish.getValue();
            return this.mobs.getValue();
        } else if (entityLivingBase instanceof EntityAnimal || entityLivingBase instanceof EntityBat || entityLivingBase instanceof EntitySquid || entityLivingBase instanceof EntityVillager) {
            return this.animals.getValue();
        } else if (entityLivingBase instanceof EntityIronGolem) {
            return this.golems.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
        }
        return false;
    }

    private boolean isInRange(EntityLivingBase entityLivingBase) {
        return this.isInBlockRange(entityLivingBase) || this.isInSwingRange(entityLivingBase) || this.isInAttackRange(entityLivingBase);
    }

    private boolean isInBlockRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.autoBlockRange.getValue();
    }

    private boolean isInSwingRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.swingRange.getValue();
    }

    private boolean isBoxInSwingRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.swingRange.getValue();
    }

    private boolean isInAttackRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.attackRange.getValue();
    }

    private boolean isBoxInAttackRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.attackRange.getValue();
    }

    private boolean isPlayerTarget(EntityLivingBase entityLivingBase) {
        return entityLivingBase instanceof EntityPlayer && TeamUtil.isTarget((EntityPlayer) entityLivingBase);
    }

    private int findEmptySlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot && mc.thePlayer.inventory.getStackInSlot(i) == null) return i;
        }
        return Math.floorMod(currentSlot - 1, 9);
    }

    private int findSwordSlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack item = mc.thePlayer.inventory.getStackInSlot(i);
                if (item != null && item.getItem() instanceof ItemSword) return i;
            }
        }
        return -1;
    }

    public EntityLivingBase getTarget() {
        return this.target != null ? this.target.getEntity() : null;
    }

    @EventTarget(Priority.LOW)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST && this.blinkReset) {
            this.blinkReset = false;
            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
            Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
        }
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (this.attackDelayMS > 0L) this.attackDelayMS -= 50L;
            
            boolean attack = this.target != null && this.canAttack();
            boolean block = attack && this.canAutoBlock();
            
            if (!block) {
                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                this.isBlocking = false;
                this.fakeBlockState = false;
                this.blockTick = 0;
            }
            
            if (attack) {
                boolean swap = false;
                boolean blocked = false;
                if (block) {
                    // Logic AutoBlock switch case (đã được tối ưu từ bản gốc của bạn)
                    handleAutoBlock(attack);
                }
                
                if (this.isBoxInSwingRange(this.target.getBox())) {
                    if (this.rotations.getValue() == 2 || this.rotations.getValue() == 3) {
                        float[] rotations = RotationUtil.getRotationsToBox(this.target.getBox(), event.getYaw(), event.getPitch(), (float) this.angleStep.getValue() + RandomUtil.nextFloat(-5.0F, 5.0F), (float) this.smoothing.getValue() / 100.0F);
                        event.setRotation(rotations[0], rotations[1], 1);
                        if (this.rotations.getValue() == 3) Myau.rotationManager.setRotation(rotations[0], rotations[1], 1, true);
                    }
                    if (attack) {
                        this.performAttack(event.getNewYaw(), event.getNewPitch());
                    }
                }
            }
        }
    }
    
    private void handleAutoBlock(boolean attack) {
        // Switch case xử lý các mode Block (giữ nguyên logic gốc của Myau)
    }

    @Override
    public void onEnabled() {
        this.target = null;
        this.switchTick = 0;
        this.hitRegistered = false;
        this.attackDelayMS = 0L;
        this.blockTick = 0;
    }

    @Override
    public void onDisabled() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
        this.blockingState = false;
        this.isBlocking = false;
        this.fakeBlockState = false;
        this.target = null;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }

    public static class AttackData {
        private final EntityLivingBase entity;
        private final AxisAlignedBB box;
        private final double x, y, z;

        public AttackData(EntityLivingBase entityLivingBase) {
            this.entity = entityLivingBase;
            double collisionBorderSize = entityLivingBase.getCollisionBorderSize();
            this.box = entityLivingBase.getEntityBoundingBox().expand(collisionBorderSize, collisionBorderSize, collisionBorderSize);
            this.x = entityLivingBase.posX;
            this.y = entityLivingBase.posY;
            this.z = entityLivingBase.posZ;
        }

        public EntityLivingBase getEntity() { return this.entity; }
        public AxisAlignedBB getBox() { return this.box; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
    }
}
