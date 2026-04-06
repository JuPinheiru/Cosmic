/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package client.processor.npc;

import client.Character;
import client.Client;
import client.autoban.AutobanFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.inventory.manipulator.KarmaManipulator;
import config.YamlConfig;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.Storage;
import tools.PacketCreator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import tools.DatabaseConnection;

/**
 * @author Matze
 * @author Ronan - inventory concurrency protection on storing items
 */
public class StorageProcessor {
    private static final Logger log = LoggerFactory.getLogger(StorageProcessor.class);

    public static void storageAction(InPacket p, Client c) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Character chr = c.getPlayer();
        Storage storage = chr.getStorage();
        String gmBlockedStorageMessage = "You cannot use the storage as a GM of this level.";

        byte mode = p.readByte();

// Bloqueia store/takeout na collection virtual
        if (chr.hasVirtualStorage() && mode == 7) {
            chr.dropMessage(5, "[Collection] Use !collection withdraw <itemId> para retirar itens.");
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        if (chr.getLevel() < 15) {
            chr.dropMessage(1, "You may only use the storage once you have reached level 15.");
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        if (c.tryacquireClient()) {
            try {
                switch (mode) {
                    case 4: { // Take out
                        byte type = p.readByte();
                        byte slot = p.readByte();
                        if (slot < 0 || slot > storage.getSlots()) { // removal starts at zero
                            AutobanFactory.PACKET_EDIT.alert(c.getPlayer(), c.getPlayer().getName() + " tried to packet edit with storage.");
                            log.warn("Chr {} tried to work with storage slot {}", c.getPlayer().getName(), slot);
                            c.disconnect(true, false);
                            return;
                        }

                        slot = storage.getSlot(InventoryType.getByType(type), slot);
                        Item item = storage.getItem(slot);

                        // Collection virtual storage - cria cópia sem remover
                        if (storage.isVirtual()) {
                            if (item != null) {
                                Item copy = item.copy();
                                if (InventoryManipulator.checkSpace(c, copy.getItemId(), copy.getQuantity(), copy.getOwner())) {
                                    InventoryManipulator.addFromDrop(c, copy, false);
                                    String itemName = ii.getName(copy.getItemId());
                                    chr.dropMessage(5, "[Collection] '" + itemName + "' retirado (cópia criada).");
                                } else {
                                    c.sendPacket(PacketCreator.getStorageError((byte) 0x0A));
                                }
                            }
                            c.sendPacket(PacketCreator.enableActions());
                            break;
                        }

                        if (hasGMRestrictions(chr)) {
                            chr.dropMessage(1, gmBlockedStorageMessage);
                            log.info(String.format("GM %s blocked from using storage", chr.getName()));
                            chr.sendPacket(PacketCreator.enableActions());
                            return;
                        }

                        if (item != null) {
                            if (ii.isPickupRestricted(item.getItemId()) && chr.haveItemWithId(item.getItemId(), true)) {
                                c.sendPacket(PacketCreator.getStorageError((byte) 0x0C));
                                return;
                            }

                            int takeoutFee = storage.getTakeOutFee();
                            if (chr.getMeso() < takeoutFee) {
                                c.sendPacket(PacketCreator.getStorageError((byte) 0x0B));
                                return;
                            } else {
                                chr.gainMeso(-takeoutFee, false);
                            }

                            if (InventoryManipulator.checkSpace(c, item.getItemId(), item.getQuantity(), item.getOwner())) {
                                if (storage.takeOut(item)) {
                                    chr.setUsedStorage();

                                    KarmaManipulator.toggleKarmaFlagToUntradeable(item);
                                    InventoryManipulator.addFromDrop(c, item, false);

                                    String itemName = ii.getName(item.getItemId());
                                    log.debug("Chr {} took out {}x {} ({})", c.getPlayer().getName(), item.getQuantity(), itemName, item.getItemId());

                                    storage.sendTakenOut(c, item.getInventoryType());
                                } else {
                                    c.sendPacket(PacketCreator.enableActions());
                                    return;
                                }
                            } else {
                                c.sendPacket(PacketCreator.getStorageError((byte) 0x0A));
                            }
                        }
                        break;
                    }
                    case 5: { // Store
                        short slot = p.readShort();
                        int itemId = p.readInt();
                        short quantity = p.readShort();
                        InventoryType invType = ItemConstants.getInventoryType(itemId);
                        Inventory inv = chr.getInventory(invType);
                        if (slot < 1 || slot > inv.getSlotLimit()) { // player inv starts at one
                            AutobanFactory.PACKET_EDIT.alert(c.getPlayer(),
                                    c.getPlayer().getName() + " tried to packet edit with storage.");
                            log.warn("Chr {} tried to store item at slot {}", c.getPlayer().getName(), slot);
                            c.disconnect(true, false);
                            return;
                        }

                        // Collection virtual storage - deposita item
                        if (storage.isVirtual()) {
                            Item depositItem = inv.getItem(slot);

                            if (depositItem == null || depositItem.getItemId() != itemId) {
                                c.sendPacket(PacketCreator.enableActions());
                                break;
                            }

                            int accountId = chr.getAccountID();

                            try (Connection con = DatabaseConnection.getConnection()) {
                                try (PreparedStatement ps2 = con.prepareStatement("SELECT itemid FROM account_collection WHERE accountid = ? AND itemid = ?")) {
                                    ps2.setInt(1, accountId);
                                    ps2.setInt(2, itemId);
                                    try (ResultSet rs2 = ps2.executeQuery()) {
                                        if (rs2.next()) {
                                            chr.dropMessage(5, "[Collection] Este item já está na sua coleção!");
                                            c.sendPacket(PacketCreator.enableActions());
                                            break;
                                        }
                                    }
                                }

                                try (PreparedStatement ps2 = con.prepareStatement("INSERT INTO account_collection (accountid, itemid) VALUES (?, ?)")) {
                                    ps2.setInt(1, accountId);
                                    ps2.setInt(2, itemId);
                                    ps2.executeUpdate();
                                }

                                // Conta total de itens após inserção
                                int totalItems2;
                                try (PreparedStatement psCount = con.prepareStatement("SELECT COUNT(*) as total FROM account_collection WHERE accountid = ?")) {
                                    psCount.setInt(1, accountId);
                                    try (ResultSet rsCount = psCount.executeQuery()) {
                                        rsCount.next();
                                        totalItems2 = rsCount.getInt("total");
                                    }
                                }

                                // +3 stats a cada 10 itens
                                int newBonus = (totalItems2 / 10) * 3;
                                chr.setCollectionBonus(newBonus);

                                try (PreparedStatement ps2 = con.prepareStatement("UPDATE accounts SET collectionBonus = ? WHERE id = ?")) {
                                    ps2.setInt(1, newBonus);
                                    ps2.setInt(2, accountId);
                                    ps2.executeUpdate();
                                }

                                Item virtualItem = ii.getEquipById(itemId);
                                if (virtualItem != null) {
                                    storage.store(virtualItem);
                                }

                                chr.equipChanged();
                                String itemName2 = ii.getName(itemId);
                                String bonusMsg2 = totalItems2 % 10 == 0 ? " MILESTONE! +5 STR/DEX/INT/LUK!" : " (" + (10 - totalItems2 % 10) + " itens para o proximo bonus)";
                                chr.dropMessage(5, "[Collection] '" + itemName2 + "' adicionado!" + bonusMsg2 + " | Total: +" + newBonus + " STR/DEX/INT/LUK");
                                storage.sendStored(c, invType);

                            } catch (SQLException e) {
                                e.printStackTrace();
                                chr.dropMessage(5, "[Collection] Erro ao depositar item.");
                                c.sendPacket(PacketCreator.enableActions());
                            }
                            break;
                        }

                        if (hasGMRestrictions(chr)) {
                            chr.dropMessage(1, gmBlockedStorageMessage);
                            log.info(String.format("GM %s blocked from using storage", chr.getName()));
                            chr.sendPacket(PacketCreator.enableActions());
                            return;
                        }

                        if (quantity < 1) {
                            c.sendPacket(PacketCreator.enableActions());
                            return;
                        }
                        if (storage.isFull()) {
                            c.sendPacket(PacketCreator.getStorageError((byte) 0x11));
                            return;
                        }
                        int storeFee = 0;
                        if (chr.getMeso() < storeFee) {
                            c.sendPacket(PacketCreator.getStorageError((byte) 0x0B));
                        } else {
                            Item item;

                            inv.lockInventory(); // thanks imbee for pointing a dupe within storage
                            try {
                                item = inv.getItem(slot);
                                if (item != null && item.getItemId() == itemId
                                        && (item.getQuantity() >= quantity || ItemConstants.isRechargeable(itemId))) {
                                    if (ItemId.isWeddingRing(itemId) || ItemId.isWeddingToken(itemId)) {
                                        c.sendPacket(PacketCreator.enableActions());
                                        return;
                                    }

                                    if (ItemConstants.isRechargeable(itemId)) {
                                        quantity = item.getQuantity();
                                    }

                                    InventoryManipulator.removeFromSlot(c, invType, slot, quantity, false);
                                } else {
                                    c.sendPacket(PacketCreator.enableActions());
                                    return;
                                }

                                item = item.copy(); // thanks Robin Schulz & BHB88 for noticing a inventory glitch when storing items
                            } finally {
                                inv.unlockInventory();
                            }

                            chr.gainMeso(-storeFee, false, true, false);

                            KarmaManipulator.toggleKarmaFlagToUntradeable(item);
                            item.setQuantity(quantity);

                            storage.store(item); // inside a critical section, "!(storage.isFull())" is still in effect...
                            chr.setUsedStorage();

                            String itemName = ii.getName(item.getItemId());
                            log.debug("Chr {} stored {}x {} ({})", c.getPlayer().getName(), item.getQuantity(), itemName, item.getItemId());
                            storage.sendStored(c, ItemConstants.getInventoryType(itemId));
                        }
                        break;
                    }
                    case 6: // Arrange items
                        if (YamlConfig.config.server.USE_STORAGE_ITEM_SORT) {
                            storage.arrangeItems(c);
                        }
                        c.sendPacket(PacketCreator.enableActions());
                        break;
                    case 7: { // Mesos
                        int meso = p.readInt();
                        int storageMesos = storage.getMeso();
                        int playerMesos = chr.getMeso();

                        if (hasGMRestrictions(chr)) {
                            chr.dropMessage(1, gmBlockedStorageMessage);
                            log.info(String.format("GM %s blocked from using storage", chr.getName()));
                            chr.sendPacket(PacketCreator.enableActions());
                            return;
                        }

                        if ((meso > 0 && storageMesos >= meso) || (meso < 0 && playerMesos >= -meso)) {
                            if (meso < 0 && (storageMesos - meso) < 0) {
                                meso = Integer.MIN_VALUE + storageMesos;
                                if (meso < playerMesos) {
                                    c.sendPacket(PacketCreator.enableActions());
                                    return;
                                }
                            } else if (meso > 0 && (playerMesos + meso) < 0) {
                                meso = Integer.MAX_VALUE - playerMesos;
                                if (meso > storageMesos) {
                                    c.sendPacket(PacketCreator.enableActions());
                                    return;
                                }
                            }
                            storage.setMeso(storageMesos - meso);
                            chr.gainMeso(meso, false, true, false);
                            chr.setUsedStorage();
                            log.debug("Chr {} {} {} mesos", c.getPlayer().getName(), meso > 0 ? "took out" : "stored", Math.abs(meso));
                            storage.sendMeso(c);
                        } else {
                            c.sendPacket(PacketCreator.enableActions());
                            return;
                        }
                        break;
                    }
                    case 8:
                        if (chr.hasVirtualStorage()) {
                            chr.closeVirtualStorage();
                        } else {
                            storage.close();
                        }
                        break;
                }  // fecha o switch
            } finally {
                c.releaseClient();
            }
        }  // fecha o if (c.tryacquireClient())
    }  // fecha o storageAction

    private static boolean hasGMRestrictions(Character character) {
        return character.isGM() && character.gmLevel() < YamlConfig.config.server.MINIMUM_GM_LEVEL_TO_USE_STORAGE;
    }
}
