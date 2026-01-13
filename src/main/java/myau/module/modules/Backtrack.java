package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraft.util.Vec3;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Backtrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"LEGACY", "MODERN"});
    public final IntProperty maxDelay = new IntProperty("MaxDelay", 200, 0, 1000);
    
    private final ConcurrentLinkedQueue<PacketData> packetQueue = new ConcurrentLinkedQueue<>();
    private final LinkedList<PositionData> serverPositions = new LinkedList<>();
    private EntityLivingBase target = null;

    public Backtrack() {
        super("Backtrack", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.PRE) {
            KillAura aura = (KillAura) myau.Myau.moduleManager.modules.get(KillAura.class);
            
            // Sửa lỗi: Truy cập target public và gọi getEntity()
            if (aura.isEnabled() && aura.target != null) {
                target = aura.target.getEntity();
            }

            if (target == null || mc.thePlayer.getDistanceToEntity(target) > 6.0f) {
                resetPackets();
                return;
            }

            if (mode.getValue() == 1) {
                packetQueue.removeIf(p -> {
                    if (System.currentTimeMillis() - p.time > maxDelay.getValue()) {
                        processPacket(p.packet);
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        Packet packet = event.getPacket();
        if (mode.getValue() == 1 && event.getType() == EventType.RECEIVE) {
            if (target != null && (packet instanceof S14PacketEntity || packet instanceof S18PacketEntityTeleport)) {
                packetQueue.add(new PacketData(packet, System.currentTimeMillis()));
                serverPositions.add(new PositionData(new Vec3(target.posX, target.posY, target.posZ), System.currentTimeMillis()));
                event.setCancelled(true);
            }
        }
    }

    private void processPacket(Packet packet) {
        if (mc.getNetHandler() != null && packet != null) {
            // Sửa lỗi INetHandler: Sử dụng phương thức processPacket chuẩn
            packet.processPacket(mc.getNetHandler());
        }
    }

    private void resetPackets() {
        packetQueue.forEach(p -> processPacket(p.packet));
        packetQueue.clear();
        serverPositions.clear();
    }

    @Override
    public void onDisabled() { resetPackets(); target = null; }
    
    private static class PacketData {
        Packet packet; long time;
        public PacketData(Packet p, long t) { this.packet = p; this.time = t; }
    }
    private static class PositionData {
        Vec3 pos; long time;
        public PositionData(Vec3 p, long t) { this.pos = p; this.time = t; }
    }
}
