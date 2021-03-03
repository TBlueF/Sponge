/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.common.mixin.inventory.event.entity;

import org.spongepowered.api.data.Transaction;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.entity.ChangeEntityEquipmentEvent;
import org.spongepowered.api.item.inventory.Inventory;
import org.spongepowered.api.item.inventory.ItemStackSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.common.bridge.world.entity.player.InventoryBridge;
import org.spongepowered.common.event.inventory.InventoryEventFactory;
import org.spongepowered.common.inventory.adapter.InventoryAdapter;
import org.spongepowered.common.inventory.adapter.impl.slots.SlotAdapter;
import org.spongepowered.common.inventory.fabric.Fabric;
import org.spongepowered.common.inventory.lens.Lens;
import org.spongepowered.common.inventory.lens.impl.comp.HotbarLens;
import org.spongepowered.common.inventory.lens.impl.minecraft.PlayerInventoryLens;
import org.spongepowered.common.inventory.lens.impl.slot.BasicSlotLens;
import org.spongepowered.common.inventory.lens.slots.SlotLens;
import org.spongepowered.common.item.util.ItemStackUtil;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

@Mixin(value = LivingEntity.class)
public abstract class LivingEntityMixin_Inventory {

    // @formatter:off
    @Shadow public abstract void shadow$setItemSlot(EquipmentSlot slotIn, ItemStack stack);
    @Shadow protected abstract ItemStack shadow$getLastHandItem(EquipmentSlot p_241347_1_);
    @Shadow protected abstract ItemStack shadow$getLastArmorItem(EquipmentSlot p_241346_1_);
    // @formatter:on

    private final EnumMap<EquipmentSlot, SlotLens> impl$slotLens = new EnumMap<>(EquipmentSlot.class);

    @Inject(method = "handleHandSwap", cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;broadcast(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/protocol/Packet;)V"))
    private void inventory$onHandleHandSwap(Map<EquipmentSlot, ItemStack> map, CallbackInfo ci) {
        if (this instanceof ServerPlayer) {
            return; // For players ChangeInventoryEvent.SwapHand is called somewhere else
        }
        final SlotAdapter mainHand = this.impl$getSpongeSlot(EquipmentSlot.MAINHAND);
        final boolean customMainHand = this.impl$throwEquipmentEvent(EquipmentSlot.MAINHAND, mainHand, map.get(EquipmentSlot.MAINHAND), this.shadow$getLastHandItem(EquipmentSlot.MAINHAND));
        final SlotAdapter offHand = this.impl$getSpongeSlot(EquipmentSlot.OFFHAND);
        final boolean customOffHand = this.impl$throwEquipmentEvent(EquipmentSlot.OFFHAND, offHand, map.get(EquipmentSlot.OFFHAND), this.shadow$getLastHandItem(EquipmentSlot.OFFHAND));
        if (customMainHand || customOffHand) {
            ci.cancel(); // If canceled or customized let handleEquipmentChanges send packets instead
        }
    }

    @Inject(method = "handleEquipmentChanges",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private void inventory$onHandleEquipmentChanges(Map<EquipmentSlot, ItemStack> map, CallbackInfo ci) {
        if ((Object) this instanceof Player && ((Entity) (Object) this).tickCount == 1) {
            // Ignore Equipment on player spawn/respawn
            return;
        }
        map.entrySet().removeIf(entry -> {
            final SlotAdapter slotAdapter = this.impl$getSpongeSlot(entry.getKey());
            ItemStack oldStack = null;
            switch (entry.getKey().getType()) {
                case HAND:
                    oldStack = shadow$getLastHandItem(entry.getKey());
                    break;
                case ARMOR:
                    oldStack = shadow$getLastArmorItem(entry.getKey());
                    break;
            }
            return this.impl$throwEquipmentEvent(entry.getKey(), slotAdapter, entry.getValue(), oldStack);
        });
    }

    private boolean impl$throwEquipmentEvent(EquipmentSlot equipmentslottype, SlotAdapter slot, ItemStack newStack, ItemStack oldStack) {
        final ChangeEntityEquipmentEvent event = InventoryEventFactory.callChangeEntityEquipmentEvent((LivingEntity) (Object) this,
                ItemStackUtil.snapshotOf(oldStack), ItemStackUtil.snapshotOf(newStack), slot);
        if (event.isCancelled()) {
            this.shadow$setItemSlot(equipmentslottype, oldStack);
            return true;
        }
        final Transaction<ItemStackSnapshot> transaction = event.getTransaction();
        if (!transaction.isValid()) {
            this.shadow$setItemSlot(equipmentslottype, oldStack);
            return true;
        }
        final Optional<ItemStackSnapshot> optional = transaction.getCustom();
        if (optional.isPresent()) {
            final ItemStack custom = ItemStackUtil.fromSnapshotToNative(optional.get());
            this.shadow$setItemSlot(equipmentslottype, custom);
            return true;
        }
        return false;
    }

    private SlotAdapter impl$getSpongeSlot(EquipmentSlot equipmentslottype) {
        final Inventory slotAdapter;
        if (((Object) this) instanceof net.minecraft.server.level.ServerPlayer) {
            final SlotLens slotLens;
            final InventoryBridge inventory = (InventoryBridge) ((net.minecraft.server.level.ServerPlayer) (Object) this).getInventory();
            final Lens inventoryLens = ((InventoryAdapter) inventory).inventoryAdapter$getRootLens();
            final Fabric fabric = ((InventoryAdapter) inventory).inventoryAdapter$getFabric();
            if (inventoryLens instanceof PlayerInventoryLens) {
                switch (equipmentslottype) {
                    case OFFHAND:
                        slotLens = ((PlayerInventoryLens) inventoryLens).getOffhandLens();
                        break;
                    case MAINHAND:
                        final HotbarLens hotbarLens = ((PlayerInventoryLens) inventoryLens).getPrimaryInventoryLens().getHotbar();
                        slotLens = hotbarLens.getSlotLens(fabric, hotbarLens.getSelectedSlotIndex(fabric));
                        break;
                    default:
                        slotLens = ((PlayerInventoryLens) inventoryLens).getEquipmentLens().getSlotLens(fabric, equipmentslottype.getIndex());
                }
            } else {
                slotLens = inventoryLens.getSlotLens(fabric, equipmentslottype.getIndex());
            }

            slotAdapter = slotLens.getAdapter(fabric, (Inventory) inventory);
        } else {
            if (this.impl$slotLens.isEmpty()) {
                for (final EquipmentSlot slot : EquipmentSlot.values()) {
                    this.impl$slotLens.put(slot, new BasicSlotLens(slot.getIndex()));
                }
            }
            slotAdapter = this.impl$slotLens.get(equipmentslottype).getAdapter((Fabric) this, null);
        }
        return (SlotAdapter) slotAdapter;
    }

}
