package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.BooleanProperty;
import myau.util.RenderUtil; // Giả định bạn có class RenderUtil
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Backtrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // Settings
    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"LEGACY", "MODERN"});
    public final IntProperty maxDelay = new IntProperty("MaxDelay", 200, 0, 1000);
    public final BooleanProperty renderBox = new BooleanProperty("RenderBox", true);
    
    // Logic Variables
    private final ConcurrentLinkedQueue<PacketData> packetQueue = new ConcurrentLinkedQueue<>();
    private final LinkedList<PositionData> serverPositions = new LinkedList<>();
    private EntityLivingBase target = null;
    private boolean isBlinking = false;

    public Backtrack() {
        super("Backtrack", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.PRE) {
            // Kiểm tra mục tiêu từ KillAura (nếu có)
            KillAura aura = (KillAura) myau.Myau.moduleManager.modules.get(KillAura.class);
            if (aura.isEnabled() && aura.target != null) {
                target = aura.target;
            }

            if (target == null || mc.thePlayer.getDistanceToEntity(target) > 6.0f) {
                resetPackets();
                return;
            }

            // Xử lý mode Modern: Giải phóng packet cũ
            if (mode.getValue() == 1) {
                packetQueue.removeIf(p -> {
                    if (System.currentTimeMillis() - p.time > maxDelay.getValue()) {
                        handlePacketNoEvent(p.packet);
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null) return;
        
        Packet<?> packet = event.getPacket();

        if (mode.getValue() == 1) { // MODERN MODE
            if (event.getType() == EventType.RECEIVE) {
                // Chỉ trì hoãn các gói tin liên quan đến thực thể và vị trí khi có target
                if (target != null) {
                    if (packet instanceof S14PacketEntity || 
                        packet instanceof S18PacketEntityTeleport || 
                        packet instanceof S19PacketEntityStatus) {
                        
                        packetQueue.add(new PacketData(packet, System.currentTimeMillis()));
                        event.setCancelled(true);
                        
                        // Lưu vị trí server để render hoặc tính toán
                        if (packet instanceof S14PacketEntity || packet instanceof S18PacketEntityTeleport) {
                            serverPositions.add(new PositionData(new Vec3(target.posX, target.posY, target.posZ), System.currentTimeMillis()));
                        }
                    }
                }
            }
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (renderBox.getValue() && target != null && !serverPositions.isEmpty()) {
            PositionData lastPos = serverPositions.getLast();
            
            // Vẽ box tại vị trí cũ của đối thủ (vị trí mà backtrack đang giữ)
            double x = lastPos.pos.xCoord - mc.getRenderManager().viewerPosX;
            double y = lastPos.pos.yCoord - mc.getRenderManager().viewerPosY;
            double z = lastPos.pos.zCoord - mc.getRenderManager().viewerPosZ;
            
            AxisAlignedBB bb = new AxisAlignedBB(
                x - target.width/2, y, z - target.width/2,
                x + target.width/2, y + target.height, z + target.width/2
            );

            GL11.glPushMatrix();
            // RenderUtil.drawBlockESP(bb, Color.CYAN.getRGB(), 0.2f); // Sử dụng hàm vẽ của bạn ở đây
            GL11.glPopMatrix();
        }
        
        // Dọn dẹp vị trí cũ
        serverPositions.removeIf(p -> System.currentTimeMillis() - p.time > maxDelay.getValue());
    }

    private void resetPackets() {
        if (!packetQueue.isEmpty()) {
            packetQueue.forEach(p -> handlePacketNoEvent(p.packet));
            packetQueue.clear();
        }
        serverPositions.clear();
    }

    // Gửi packet thẳng vào game mà không kích hoạt lại event vòng lặp
    private void handlePacketNoEvent(Packet<?> packet) {
        if (mc.thePlayer != null) {
            ((net.minecraft.network.INetHandlerPlayClient) mc.getNetHandler()).handleEntityVelocity((S12PacketEntityVelocity) packet);
            // Ghi chú: Cần bổ sung instanceof cho các loại packet khác tương tự như code gốc
        }
    }

    @Override
    public void onDisabled() {
        resetPackets();
        target = null;
    }

    @Override
    public void onEnabled() {
        packetQueue.clear();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString() + " " + maxDelay.getValue() + "ms"};
    }

    // Helper Classes
    private static class PacketData {
        Packet<?> packet;
        long time;
        public PacketData(Packet<?> packet, long time) {
            this.packet = packet;
            this.time = time;
        }
    }

    private static class PositionData {
        Vec3 pos;
        long time;
        public PositionData(Vec3 pos, long time) {
            this.pos = pos;
            this.time = time;
        }
    }
}