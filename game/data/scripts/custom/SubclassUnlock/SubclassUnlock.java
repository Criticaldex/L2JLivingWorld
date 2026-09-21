/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package custom.SubclassUnlock;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.instance.VillageMaster;

/**
 * VillageMaster hardcodes a handful of "subclass family" groups (e.g. Sorcerer/Spellsinger/
 * Spellhowler all count as the same slot) and permanently bans Overlord/Warsmith from ever
 * being added as a subclass. Both restrictions live in static collections built once in
 * VillageMaster's <clinit> ("subclassSetMap", "mainSubclassSet", "neverSubclassed") with no
 * config toggle, so they are cleared/restored here via reflection at server start instead of
 * patching the closed-source GameServer.jar.
 *
 * Note: this does NOT lift the separate Elf/Dark Elf mutual subclass ban, which is inline
 * logic in VillageMaster#getSubclasses rather than a data field.
 */
public class SubclassUnlock
{
	private static final Logger LOGGER = Logger.getLogger(SubclassUnlock.class.getName());

	private SubclassUnlock()
	{
		try
		{
			final Field subclassSetMapField = VillageMaster.class.getDeclaredField("subclassSetMap");
			subclassSetMapField.setAccessible(true);
			final EnumMap<?, ?> subclassSetMap = (EnumMap<?, ?>) subclassSetMapField.get(null);
			subclassSetMap.clear();

			final Field neverSubclassedField = VillageMaster.class.getDeclaredField("neverSubclassed");
			neverSubclassedField.setAccessible(true);
			@SuppressWarnings("unchecked")
			final Set<PlayerClass> neverSubclassed = (Set<PlayerClass>) neverSubclassedField.get(null);

			final Field mainSubclassSetField = VillageMaster.class.getDeclaredField("mainSubclassSet");
			mainSubclassSetField.setAccessible(true);
			@SuppressWarnings("unchecked")
			final Set<PlayerClass> mainSubclassSet = (Set<PlayerClass>) mainSubclassSetField.get(null);
			mainSubclassSet.addAll(neverSubclassed);

			LOGGER.info("SubclassUnlock: subclass family restriction cleared, " + mainSubclassSet.size() + " classes now available to add as a subclass.");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "SubclassUnlock: failed to patch VillageMaster subclass restrictions.", e);
		}
	}

	public static void main(String[] args)
	{
		new SubclassUnlock();
	}
}
