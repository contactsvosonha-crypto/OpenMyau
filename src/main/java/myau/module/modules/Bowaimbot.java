package myau.module.modules;

import myau.event.EventTarget;
import myau.events.UpdateEvent;
import myau.event.types.EventType;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemBow;
import net.minecraft.util.MathHelper;

public class BowAimbot extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public final BooleanProperty silent = new BooleanProperty("Silent", true);
    public final BooleanProperty autoRelease = new BooleanProperty("AutoRelease", false);
    public final ModeProperty priority = new ModeProperty("Priority", 0, new String[]{"DISTANCE", "HEALTH"});

    private EntityLivingBase target = null;

    public BowAimbot() {
        super("BowAimbot", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.PRE) {
            // Kiểm tra xem người chơi có đang cầm cung và gồng cung không
            if (mc.thePlayer.getCurrentEquippedItem() != null && mc.thePlayer.getCurrentEquippedItem().getItem() instanceof ItemBow && mc.thePlayer.isUsingItem()) {
                
                target = getBestTarget();
                
                if (target != null) {
                    float[] rotations = calculateArc(target);
                    
                    if (rotations != null) {
                        if (silent.getValue()) {
                            event.setYaw(rotations[0]);
                            event.setPitch(rotations[1]);
                        } else {
                            mc.thePlayer.rotationYaw = rotations[0];
                            mc.thePlayer.rotationPitch = rotations[1];
                        }
                        
                        // Tự động bắn khi gồng đủ lực (20 ticks = lực mạnh nhất)
                        if (autoRelease.getValue() && mc.thePlayer.getItemInUseDuration() >= 20) {
                            mc.playerController.onStoppedUsingItem(mc.thePlayer);
                        }
                    }
                }
            } else {
                target = null;
            }
        }
    }

    private float[] calculateArc(EntityLivingBase target) {
        double x = target.posX - mc.thePlayer.posX;
        double y = target.posY + (target.getEyeHeight() / 2.0) - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double z = target.posZ - mc.thePlayer.posZ;
        double dist = MathHelper.sqrt_double(x * x + z * z);

        // Tính toán vận tốc mũi tên dựa trên thời gian gồng cung
        int useCount = mc.thePlayer.getItemInUseDuration();
        float velocity = useCount / 20.0f;
        velocity = (velocity * velocity + velocity * 2.0f) / 3.0f;
        if (velocity > 1.0f) velocity = 1.0f;
        
        // Vận tốc thực tế (max là 3.0)
        float v = velocity * 3.0f;
        float g = 0.05f; // Gravity

        // Công thức tính góc bắn (Pitch) để trúng mục tiêu ở khoảng cách dist:
        // theta = atan((v^2 +- sqrt(v^4 - g(gx^2 + 2yv^2))) / gx)
        float root = (float) (Math.pow(v, 4) - g * (g * Math.pow(dist, 2) + 2 * y * Math.pow(v, 2)));

        if (root < 0) return null; // Không thể bắn tới mục tiêu

        float theta = (float) Math.atan((Math.pow(v, 2) - Math.sqrt(root)) / (g * dist));
        
        float yaw = (float) (Math.atan2(z, x) * 180.0D / Math.PI) - 90.0F;
        float pitch = (float) -(theta * 180.0D / Math.PI);

        return new float[]{yaw, pitch};
    }

    private EntityLivingBase getBestTarget() {
        EntityLivingBase best = null;
        double minVal = Double.MAX_VALUE;

        for (Object obj : mc.theWorld.loadedEntityList) {
            if (obj instanceof EntityLivingBase) {
                EntityLivingBase entity = (EntityLivingBase) obj;
                if (entity != mc.thePlayer && entity.isEntityAlive() && mc.thePlayer.canEntityBeSeen(entity)) {
                    double val = priority.getValue() == 0 ? mc.thePlayer.getDistanceToEntity(entity) : entity.getHealth();
                    if (val < minVal) {
                        minVal = val;
                        best = entity;
                    }
                }
            }
        }
        return best;
    }
}