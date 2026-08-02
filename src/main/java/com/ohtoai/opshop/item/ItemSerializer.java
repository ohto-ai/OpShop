package com.ohtoai.opshop.item;

import org.bukkit.inventory.ItemStack;

import java.io.*;

/**
 * Serializes/deserializes ItemStacks using Paper's native byte serialization.
 * This is lossless — preserves all NBT, enchantments, custom model data, PDC tags, etc.
 */
public final class ItemSerializer {

    private ItemSerializer() {}

    /**
     * Serialize an ItemStack to a byte array using Paper's native method.
     */
    public static byte[] serialize(ItemStack item) {
        if (item == null || item.isEmpty()) return new byte[0];
        return item.serializeAsBytes();
    }

    /**
     * Deserialize an ItemStack from a byte array using Paper's native method.
     */
    public static ItemStack deserialize(byte[] data) {
        if (data == null || data.length == 0) return null;
        return ItemStack.deserializeBytes(data);
    }

    /**
     * Serialize an array of ItemStacks to bytes.
     * Uses length-prefixed format since Paper 1.20.6 doesn't have batch methods.
     */
    public static byte[] serializeItems(ItemStack[] items) {
        if (items == null || items.length == 0) return new byte[0];
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeInt(items.length);
            for (ItemStack item : items) {
                byte[] data = serialize(item);
                dos.writeInt(data.length);
                dos.write(data);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize items", e);
        }
    }

    /**
     * Deserialize an array of ItemStacks from bytes.
     */
    public static ItemStack[] deserializeItems(byte[] data) {
        if (data == null || data.length == 0) return new ItemStack[0];
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
            int count = dis.readInt();
            ItemStack[] items = new ItemStack[count];
            for (int i = 0; i < count; i++) {
                int len = dis.readInt();
                byte[] itemData = new byte[len];
                dis.readFully(itemData);
                items[i] = deserialize(itemData);
            }
            return items;
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize items", e);
        }
    }

    /**
     * Deep-clone an ItemStack using serialization round-trip.
     */
    public static ItemStack clone(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        return ItemStack.deserializeBytes(item.serializeAsBytes());
    }
}
