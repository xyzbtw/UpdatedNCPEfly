package org.efly;

import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.rusherhack.client.api.events.client.EventTimerSpeed;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.events.player.EventMove;
import org.rusherhack.client.api.events.player.EventPlayerUpdate;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.client.api.utils.PlayerUtils;
import org.rusherhack.core.event.stage.Stage;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.utils.Timer;

public class NCPElytraFly extends ToggleableModule {

    public NCPElytraFly() {
        super("UNCPEfly", ModuleCategory.MOVEMENT);
        this.takeOff.addSubSettings(takeOffWaitForDescend, takeOffTimerSpeed);
        this.useTimer.addSubSettings(activeTimerSpeed);
        this.autoRedeploy.addSubSettings(redeployOnLag, autoRedeployWait, autoRedeployTimerSpeed, autoRedeployDelay);
        this.speedBoost.addSubSettings(boostValue, speedBoostDelay);
        this.registerSettings(
                takeOff,
                autoRedeploy,
                useTimer,
                pitch,
                rubberbandResetSpeed,
                rubberbandMotionCancel,
                speed,
                speedBoost
        );
    }

    /**
     * Settings
     */
    private final BooleanSetting takeOff = new BooleanSetting("TakeOff", true);
    private final BooleanSetting takeOffWaitForDescend = new BooleanSetting("Descending", true);
    private final NumberSetting<Float> takeOffTimerSpeed = new NumberSetting<>("Timer", 0.15f, 0.05f, 1f).incremental(0.05f);
    private final NumberSetting<Float> pitch = new NumberSetting<>("Pitch", -4f, -6f, 6f).incremental(0.05f);

    private final BooleanSetting autoRedeploy = new BooleanSetting("AutoRedeploy", true);
    private final BooleanSetting redeployOnLag = new BooleanSetting("DeployOnLag", true);
    private final BooleanSetting autoRedeployWait = new BooleanSetting("Wait", true);
    private final NumberSetting<Float> autoRedeployTimerSpeed = new NumberSetting<>("Timer", 0.1f, 0.05f, 1f).incremental(0.05f);
    private final NumberSetting<Double> autoRedeployDelay = new NumberSetting<>("Delay", 0.5d, 0.05d, 2d);

    private final BooleanSetting useTimer = new BooleanSetting("UseTimer", true);
    private final NumberSetting<Float> activeTimerSpeed = new NumberSetting<>("ActiveSpeed", 1f, 0.1f, 1f);
    private final BooleanSetting rubberbandResetSpeed = new BooleanSetting("ResetSpeedRubberband", true);
    private final BooleanSetting rubberbandMotionCancel = new BooleanSetting("CancelMotionRubberband", true);

    private final NumberSetting<Double> speed = new NumberSetting<>("Speed", 3d, 0.1d, 10d).incremental(0.1d);
    private final BooleanSetting speedBoost = new BooleanSetting("SpeedBoost", false);
    private final NumberSetting<Double> boostValue = new NumberSetting<>("BoostValue", 0.03d, 0.01d, 0.1d);
    private final NumberSetting<Double> speedBoostDelay = new NumberSetting<>("BoostDelay", 1.5d, 0.1d, 2d).incremental(0.1d);

    /**
     * Variables
     */
    private long lastLagBack = System.currentTimeMillis(); //last time the player was rubberbanded
    private final Timer flightTimer = new Timer(); //used to know how long the player has been flying in total (used for speed boost)
    private final Timer burstFlightTimer = new Timer(); //used to know how long the player has been flying in the current deployment
    private final Timer noFlightTimer = new Timer(); //used to know how long the player has not been flying
    private double lastSpeed = 0f; //used for acceleration
    private float timerSpeed = 1f; //controls game timer speed
    private boolean isElytraGliding = false;
    private boolean deployedThisTick = false;
    private boolean redeploying = false;

    @Subscribe(stage = Stage.ALL)
    public void onMove(EventMove event) {
        //only listening to all for debug
        if(!event.getStage().equals(Stage.PRE)) {
            return;
        }

        if(!this.canElytraFly()) {
            this.timerSpeed = 1f;
            this.lastSpeed = 0;
            this.flightTimer.reset();
            this.burstFlightTimer.reset();
            return;
        }

        final long timeSinceLastRubberband = System.currentTimeMillis() - this.lastLagBack;
        deployedThisTick = false;

        if(redeploying){
            event.setY(0);
        }

        deploy:
        {
            if(!mc.player.isFallFlying()) {

                final boolean redeploy = this.autoRedeploy.getValue() && timeSinceLastRubberband < 2000L;
                final boolean attemptTakeoff = this.takeOff.getValue();

                if(redeploy) {
                    if(this.redeploy()) {
                        //  ChatUtils.print(System.currentTimeMillis() + " sent redeploy");
                        this.burstFlightTimer.reset();
                    }

                    event.setY(0);
                    //this.lastSpeed = 0;

                    //break because we should continue flying while we wait for server to redeploy us
                    deployedThisTick = true;
                    break deploy;

                } else if(attemptTakeoff) {
                    if(this.takeOff(event.getY())) {
                       // ChatUtils.print(System.currentTimeMillis() + " take off " + event.getY());
                        event.setY(0);

                        //break because we should start flying immediately after we send elytra packet
                        deployedThisTick = true;
                        break deploy;
                    } else {
                        this.flightTimer.reset();
                        //ChatUtils.print("motionY: " + event.getY());
                    }
                   // ChatUtils.print("reset speed 2");
                    this.lastSpeed = 0;
                }
//                else {
//                    this.timerSpeed = 1f;
//                }

                return;
            }
        }

        if(!deployedThisTick) {
            redeploying = false;
            //ChatUtils.print("Set active timer speed 2 to " + timerSpeed);
            //this.timerSpeed = this.activeTimerSpeed.getValue();
            this.noFlightTimer.reset();
        }

        //disables moving while redeploying / stops all motion and update packets while redeploying
        if(this.autoRedeployWait.getValue() && (!this.isElytraGliding || redeploying) && timeSinceLastRubberband < 2000L) {
           // ChatUtils.print("test1");
            event.setCancelled(true);
            return;
        }


        //by this point we are actively flying (or its the first tick after we sent fallfly packet

        //cancel vanilla motion
        event.setMotion(Vec3.ZERO);

        double startingSpeed = Math.min(this.speed.getValue(), 3.75f);

        if(this.rubberbandMotionCancel.getValue()) {
            if(mc.player.isFallFlying() && this.burstFlightTimer.passed(1000) && System.currentTimeMillis() - this.lastLagBack < 300L) {
                event.setCancelled(true);
                return;
            }
        }

        //this was used to reset speed when rubberbanded while eflying, but its no longer needed due to the above method
        if(this.rubberbandResetSpeed.getValue()) {
            if(mc.player.isFallFlying() && System.currentTimeMillis() - this.lastLagBack >= 75L && System.currentTimeMillis() - this.lastLagBack <= 500L) {
                this.lastSpeed = 0f;
               // ChatUtils.print("reset speed 1");
            }
        }

        double speed = startingSpeed;

        if(this.lastSpeed > speed) {
            speed = this.lastSpeed;
        }

        if(this.flightTimer.passed(this.speedBoostDelay.getValue() * 1000) && PlayerUtils.hasHorizontalInput() && speedBoost.getValue()) {
            speed += this.boostValue.getValue();
        }

        if(speed > this.speed.getValue()) {
            speed = this.speed.getValue();
        }

        //ChatUtils.print("speed " + speed);

        final double[] dirSpeed = PlayerUtils.getDirectionalSpeed(speed);
        event.setX(dirSpeed[0]);
        event.setZ(dirSpeed[1]);

        mc.player.setDeltaMovement(event.getMotion());

        //update last speed
        if(!deployedThisTick) {
            this.lastSpeed = speed;
        }
    }
    @Subscribe
    public void onPlayerUpdate(EventPlayerUpdate event) {
        //this method spoofs pitch to bypass anticheat

        if(!this.canElytraFly() || mc.player.onGround()) {
            return;
        }

        //disables moving while redeploying / stops all motion and update packets while redeploying
        if(this.autoRedeployWait.getValue() && (!this.isElytraGliding || redeploying) && System.currentTimeMillis() - this.lastLagBack < 2000L) {
            event.setCancelled(true);
            return;
        }

        //cancel packet to avoid sending extra when we just got rubberbanded, we are supposed to be redeploying soon
        if(this.rubberbandMotionCancel.getValue()) {
            if(this.burstFlightTimer.passed(1000) && System.currentTimeMillis() - this.lastLagBack < 300L) {
                event.setCancelled(true);
                return;
            }
        }

        float pitch = this.pitch.getValue();

//        if(this.lastSpeed >= 11) {
//            pitch = -1f;
//        } else if(this.lastSpeed >= 8.3) {
//            pitch = -1.3f;
//        } else if(this.lastSpeed >= 5.8) {
//            pitch = -1.5f;
//        }

        event.setPitch(pitch);
    }

    @Subscribe
    public void getTimerSpeed(EventTimerSpeed event) {
        if(this.useTimer.getValue()) {
            event.setSpeed(this.timerSpeed);
        }
    }
    @Subscribe(stage = Stage.ALL)
    public void onPacketReceive(EventPacket.Receive event) {

        if(event.getStage().equals(Stage.POST) && event.getPacket() instanceof ClientboundSetEntityDataPacket pck) {
            final Entity entity = mc.level.getEntity(pck.id());
            if(entity != null && entity.equals(mc.player)) {

                for(SynchedEntityData.DataValue<?> packedItem : pck.packedItems()) {
                    if(packedItem.id() == 0 && packedItem.value() instanceof Byte byteValue) {
                        this.updatePlayerState((byteValue & 1 << 7) != 0);
                        break;
                    }
                }
            }
        }

        if(!(event.getPacket() instanceof ClientboundPlayerPositionPacket packet) || !event.getStage().equals(Stage.PRE)) {
            return;
        }


        if(mc.player.isFallFlying() || (System.currentTimeMillis() - this.lastLagBack) <= 1000L) {
            this.lastLagBack = System.currentTimeMillis();
        }

        if (this.redeployOnLag.getValue() && !deployedThisTick) {

            if (this.redeploy()) {
                this.burstFlightTimer.reset();
            }

            deployedThisTick = true;
        }


        //this.lastSpeed = 0f;
    }

    @Override
    public void onEnable() {
        this.flightTimer.reset();
        this.noFlightTimer.reset();
        this.burstFlightTimer.reset();
        this.timerSpeed = 1f;
        this.isElytraGliding = mc.player.isFallFlying();
    }

    /**
     * This is called when we the server changed our elytra state
     */
    private void updatePlayerState(boolean fallFlying) {
        if(fallFlying) {
            //set active timer speed immediately after server tells us we are in elytra flight
            this.timerSpeed = this.activeTimerSpeed.getValue();
        } else {
            this.burstFlightTimer.reset();
            this.flightTimer.reset();
        }
        this.isElytraGliding = fallFlying;
    }

    private boolean canElytraFly() {
        //dont take off if we are already flying in creative
        if(mc.player.getAbilities().flying) {
            return false;
        }

        if(mc.player.onGround()) {
            return false;
        }

        final ItemStack chestSlot = mc.player.getItemBySlot(EquipmentSlot.CHEST);

        final boolean playerStateCheck = !mc.player.onGround() && !mc.player.isPassenger() && !mc.player.onClimbable() && !mc.player.isInWater() && !mc.player.isInLava();
        final boolean itemCheck = chestSlot.is(Items.ELYTRA) && ElytraItem.isFlyEnabled(chestSlot);

        return playerStateCheck && itemCheck;
    }
    /**
     * This method is called when first trying to take off from the ground
     *
     * @return true if we are attempting takeoff
     */
    private boolean takeOff(double motionY) {
        //only attempt while holding jump key
        //ignore if redeploy is true because we are redeploying automatically
        if(!mc.options.keyJump.isDown() && System.currentTimeMillis() - this.lastLagBack > 300L) {
            return false;
        }

        //we must be descending
        //0.08 is used here because this method is called before the descending motion is send, so we have to wait for the next tick
        if(this.takeOffWaitForDescend.getValue() && motionY >= -0.08) {
            //apply timer speed 1 tick early when we are about to descend
            if(motionY < 0.1) {
                this.timerSpeed = this.takeOffTimerSpeed.getValue();
            }
            return false;
        }

        //apply timer speed
        this.timerSpeed = this.takeOffTimerSpeed.getValue();

        if(mc.player.tryToStartFallFlying()) {
            ChatUtils.print("TAKING OFF");
            mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            return true;
        }

        return false;
    }

    /**
     * This method is called when the server stops your elytrafly state and we need to redeploy
     */
    private boolean redeploy() {
        //apply timer speed
        redeploying = true;
        this.timerSpeed = this.autoRedeployTimerSpeed.getValue();
        reElytra();
        if(!isElytraGliding || !mc.player.isFallFlying()) {
            if (this.noFlightTimer.passed(this.autoRedeployDelay.getValue() * 1000) /*&& mc.player.tryToStartFallFlying() */) {
                ChatUtils.print("REDEPLOYING");
                mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                return true;
            }
        }

        return false;
    }
    private void reElytra(){
        if(!isElytraGliding || !mc.player.isFallFlying()) return;

        if(isElytraGliding || mc.player.isFallFlying()) {
            //mc.player.stopFallFlying();
            mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }
    public static void clickSlot(int slotId, boolean shiftClick) {
        if(mc.player == null || mc.gameMode == null) {
            return;
        }

        mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, slotId, 0, shiftClick ? ClickType.QUICK_MOVE : ClickType.PICKUP, mc.player);
    }

}
