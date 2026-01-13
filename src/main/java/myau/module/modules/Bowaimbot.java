package myau.module.modules;

import myau.event.EventTarget;
import myau.events.UpdateEvent;
import myau.event.types.EventType;
import myau.module.Module;
import myau.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemBow;
import net.minecraft.util.Vec3;

public class BowAimbot extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public final BooleanProperty silent = new BooleanProperty("Silent", true);
    public final BooleanProperty predict = new BooleanProperty("Prediction", true);
    public final DoubleProperty predictSize = new DoubleProperty("Predict Size", 2.0, 0.1, 5.0, () -> predict.getValue());

    public BowAimbot() {
        super("BowAimbot", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.PRE) {
            if (isHoldingBow() && mc.thePlayer.isUsingItem()) {
                EntityLivingBase target = getBestTarget();
                if (target != null) {
                    Vec3 targetPos = getPredictedPos(target);
                    float[] rotations = calculateArc(targetPos);
                    
                    if (rotations != null) {
                        // Sửa lỗi: Sử dụng setRotation thay vì setYaw/setPitch
                        if (silent.getValue()) {
                            event.setRotation(rotations[0], rotations[1], 1);
                        } else {
                            mc.thePlayer.rotationYaw = rotations[0];
                            mc.thePlayer.rotationPitch = rotations[1];
                        }
                    }
                }
            }
        }
    }

    private Vec3 getPredictedPos(EntityLivingBase target) {
        double x = target.posX + (target.posX - target.lastTickPosX) * (predict.getValue() ? predictSize.getValue() : 0);
        double y = target.posY + target.getEyeHeight() / 2.0;
        double z = target.posZ + (target.posZ - target.lastTickPosZ) * (predict.getValue() ? predictSize.getValue() : 0);
        return new Vec3(x, y, z);
    }

    private float[] calculateArc(Vec3 pos) {
        double diffX = pos.xCoord - mc.thePlayer.posX;
        double diffZ = pos.zCoord - mc.thePlayer.posZ;
        double diffY = pos.yCoord - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
        float v = getBowVelocity();
        float g = 0.006f;
        double root = Math.pow(v, 4) - g * (g * Math.pow(dist, 2) + 2 * diffY * Math.pow(v, 2));
        if (root < 0) return null;
        float pitch = (float) -Math.toDegrees(Math.atan((Math.pow(v, 2) - Math.sqrt(root)) / (g * dist)));
        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0 / Math.PI) - 90.0f;
        return new float[]{yaw, pitch};
    }

    private float getBowVelocity() {
        int duration = mc.thePlayer.getItemInUseDuration();
        float v = (duration / 20.0f);
        v = (v * v + v * 2.0f) / 3.0f;
        return Math.min(v, 1.0f) * 3.0f;
    }

    private boolean isHoldingBow() {
        return mc.thePlayer.getCurrentEquippedItem() != null && mc.thePlayer.getCurrentEquippedItem().getItem() instanceof ItemBow;
    }

    private EntityLivingBase getBestTarget() {
        return mc.theWorld.loadedEntityList.stream()
                .filter(e -> e instanceof EntityLivingBase && e != mc.thePlayer)
                .map(e -> (EntityLivingBase) e)
                .filter(e -> e.isEntityAlive() && mc.thePlayer.canEntityBeSeen(e))
                .findFirst().orElse(null);
    }
}
