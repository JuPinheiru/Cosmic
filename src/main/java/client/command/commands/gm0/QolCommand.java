package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.manipulator.InventoryManipulator;

public class QolCommand extends Command {
    {
        setDescription("Cria itens de QoL no inventário");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int[] items = {5041000, 5520000, 5450000};
        for (int itemId : items) {
            InventoryManipulator.addById(c, itemId, (short) 1);
        }
        player.dropMessage(5, "Itens de QoL adicionados!");
    }
}