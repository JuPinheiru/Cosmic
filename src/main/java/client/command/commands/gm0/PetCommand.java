package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;

import java.util.concurrent.TimeUnit;

public class PetCommand extends Command {
    {
        setDescription("Cria itens de pet no inventário");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();

        // Pet precisa de criação especial
        int petId = Pet.createPet(5000000);
        long expiration = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365);
        InventoryManipulator.addById(c, 5000000, (short) 1, player.getName(), petId, expiration);

        // Demais itens normais
        int[] items = {1812002, 1812003, 1812001, 1812000, 1812006, 1812004, 1812005};
        for (int itemId : items) {
            InventoryManipulator.addById(c, itemId, (short) 1);
        }
        player.dropMessage(5, "Itens de pet adicionados!");
    }
}