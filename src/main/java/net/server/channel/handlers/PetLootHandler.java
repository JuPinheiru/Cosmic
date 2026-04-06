package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Pet;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.maps.MapItem;
import server.maps.MapObject;
import tools.PacketCreator;
import java.util.Arrays;
import java.util.List;
import server.maps.MapObjectType;

import java.util.Set;

public final class PetLootHandler extends AbstractPacketHandler {

    @Override
    public final void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();

        int petIndex = chr.getPetIndex(p.readInt());
        Pet pet = chr.getPet(petIndex);
        if (pet == null || !pet.isSummoned()) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        p.skip(13);
        int oid = p.readInt();

        MapObject ob = chr.getMap().getMapObject(oid);
        if (!(ob instanceof MapItem)) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        MapItem mapitem = (MapItem) ob;

        try {
            if (mapitem.getMeso() > 0) {
                if (!chr.isEquippedMesoMagnet()) {
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                if (chr.isEquippedPetItemIgnore()) {
                    final Set<Integer> petIgnore = chr.getExcludedItems();
                    if (!petIgnore.isEmpty() && petIgnore.contains(Integer.MAX_VALUE)) {
                        c.sendPacket(PacketCreator.enableActions());
                        return;
                    }
                }
            } else {
                if (!chr.isEquippedItemPouch()) {
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                if (chr.isEquippedPetItemIgnore()) {
                    final Set<Integer> petIgnore = chr.getExcludedItems();
                    if (!petIgnore.isEmpty() && petIgnore.contains(mapitem.getItem().getItemId())) {
                        c.sendPacket(PacketCreator.enableActions());
                        return;
                    }
                }
            }

            if (!mapitem.canBePickedBy(chr)) {
                c.sendPacket(PacketCreator.enableActions());
                return;
            }

            // Verifica se é bot - bots usam loot normal, players usam vac
            if (chr.isBot()) {
                if (!mapitem.canBePickedBy(chr)) {
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }
                chr.pickupItem(ob, petIndex);
            } else {
                List<MapObject> list = chr.getMap().getMapObjectsInRange(
                        chr.getPosition(), Double.POSITIVE_INFINITY,
                        Arrays.asList(MapObjectType.ITEM)
                );
                for (MapObject mapObj : list) {
                    if (!(mapObj instanceof MapItem)) continue;
                    MapItem mi = (MapItem) mapObj;
                    if (!mi.canBePickedBy(chr)) continue;
                    chr.pickupItem(mapObj, petIndex);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            c.sendPacket(PacketCreator.enableActions());
        }
    }
}