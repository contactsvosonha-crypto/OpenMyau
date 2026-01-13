package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FakeLag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // Settings
    public final IntProperty delay = new IntProperty("Delay", 550, 0, 1000);
    public final IntProperty recoilTime = new IntProperty("RecoilTime", 750, 0, 2000);
    public final BooleanProperty blinkOnAction = new BooleanProperty("BlinkOnAction", true);
    public final BooleanProperty pauseOnNoMove = new BooleanProperty("PauseOnNoMove", true);
    public final BooleanProperty renderLine = new BooleanProperty("RenderLine", true);

    // Logic Variables
    private final ConcurrentLinkedQueue<PacketData> packetQueue = new ConcurrentLinkedQueue<>();
    private final LinkedList<Vec3> historyPositions = new LinkedList<>();
    private long lastBlinkTime = 0;
    private boolean ignoreTick = false;

    public FakeLag() {
        super("FakeLag", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.PRE) {
            if (mc.thePlayer == null || mc.thePlayer.isDead) {
                blink();
                return;
            }

            // Tự động xả nếu đứng yên để tránh bị flag Balance
            if (pauseOnNoMove.getValue() && !isMoving()) {
                blink();
                return;
            }

            // Kiểm tra và xả packet dựa trên thời gian delay
            if (System.currentTimeMillis() - lastBlinkTime >= delay.getValue()) {
                handlePackets();
                ignoreTick = false;
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();

        // Xử lý các gói tin RECEIVE (Từ Server) - Tự động xả để đồng bộ
        if (event.getType() == EventType.RECEIVE) {
            if (packet instanceof S08PacketPlayerPosLook || packet instanceof S12PacketEntityVelocity || packet instanceof S27PacketExplosion) {
                blink(); // Server yêu cầu cập nhật vị trí hoặc knockback -> xả ngay
                return;
            }
        }

        // Xử lý các gói tin SEND (Gửi lên Server)
        if (event.getType() == EventType.SEND && !event.isCancelled()) {
            
            // Các hành động đặc biệt gây xả packet ngay lập tức để bypass
            if (blinkOnAction.getValue()) {
                if (packet instanceof C02PacketUseEntity || packet instanceof C08PacketPlayerBlockPlacement || 
                    packet instanceof C07PacketPlayerDigging || packet instanceof C0EPacketClickWindow) {
                    blink();
                    return;
                }
            }

            if (mc.currentScreen instanceof GuiContainer) {
                blink();
                return;
            }

            // Bắt đầu giữ packet (Blink)
            if (packet instanceof C03PacketPlayer) {
                event.setCancelled(true);
                packetQueue.add(new PacketData(packet, System.currentTimeMillis()));
                
                // Lưu lại lịch sử di chuyển để vẽ line
                C03PacketPlayer p = (C03PacketPlayer) packet;
                if (p.isMoving()) {
                    historyPositions.add(new Vec3(p.getPositionX(), p.getPositionY(), p.getPositionZ()));
                }
                
                if (historyPositions.size() > 20) historyPositions.removeFirst();
            }
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (renderLine.getValue() && historyPositions.size() > 1) {
            GL11.glPushMatrix();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glLineWidth(2.0F);

            GL11.glBegin(GL11.GL_LINE_STRIP);
            GL11.glColor4f(0.0f, 1.0f, 0.0f, 1.0f); // Màu xanh lá cho FakeLag line

            double renderX = mc.getRenderManager().viewerPosX;
            double renderY = mc.getRenderManager().viewerPosY;
            double renderZ = mc.getRenderManager().viewerPosZ;

            for (Vec3 pos : historyPositions) {
                GL11.glVertex3d(pos.xCoord - renderX, pos.yCoord - renderY, pos.zCoord - renderZ);
            }

            GL11.glEnd();
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glPopMatrix();
        }
    }

    private void handlePackets() {
        if (!packetQueue.isEmpty()) {
            for (PacketData data : packetQueue) {
                mc.getNetHandler().getNetworkManager().sendPacket(data.packet);
            }
            packetQueue.clear();
        }
        lastBlinkTime = System.currentTimeMillis();
    }

    private void blink() {
        handlePackets();
        historyPositions.clear();
        ignoreTick = true;
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0 || mc.thePlayer.moveStrafing != 0;
    }

    @Override
    public void onDisabled() {
        blink();
    }

    @Override
    public void onEnabled() {
        lastBlinkTime = System.currentTimeMillis();
        packetQueue.clear();
        historyPositions.clear();
    }

    private static class PacketData {
        Packet<?> packet;
        long time;
        public PacketData(Packet<?> packet, long time) {
            this.packet = packet;
            this.time = time;
        }
    }
}