package com.simpledefence;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * Movable panel (Alt + drag):
 *
 *   Simple Defence Tracker
 *   Bork
 *   [Elder maul] : 2
 *   [DWH]        : 3
 *   The Smorks
 *   [Elder maul] : 4
 *   ...
 *
 * One section per drained NPC that's alive, in the order they were first drained.
 * An NPC's section is removed as soon as it dies.
 */
class SpecsPanelOverlay extends OverlayPanel
{
	private static final String TITLE = "Simple Defence Tracker";
	private static final int ICON_W = 18;
	private static final int ICON_H = 16;
	private static final Color NAME_COLOR = new Color(255, 152, 31);

	/** Top-to-bottom order. Elder maul always above DWH. */
	private static final DrainWeapon[] DISPLAY_ORDER = {
		DrainWeapon.ELDER_MAUL,
		DrainWeapon.DRAGON_WARHAMMER,
		DrainWeapon.TONALZTICS_OF_RALOS,
		DrainWeapon.BANDOS_GODSWORD,
		DrainWeapon.ARCLIGHT,
		DrainWeapon.EMBERLIGHT,
		DrainWeapon.BONE_DAGGER,
		DrainWeapon.BARRELCHEST_ANCHOR,
		DrainWeapon.ACCURSED_SCEPTRE,
		DrainWeapon.SEERCULL
	};

	private final Client client;
	private final SimpleDefencePlugin plugin;
	private final SimpleDefenceConfig config;
	private final ItemManager itemManager;

	private final Map<DrainWeapon, BufferedImage> icons = new ConcurrentHashMap<>();
	private boolean iconsRequested;

	@Inject
	SpecsPanelOverlay(Client client, SimpleDefencePlugin plugin, SimpleDefenceConfig config, ItemManager itemManager)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Map<Integer, NpcDefenceState> tracked = plugin.getTracked();
		if (tracked.isEmpty() && plugin.getHeldDrains().isEmpty())
		{
			return null;
		}

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}

		if (!iconsRequested)
		{
			requestIcons();
		}

		int width = Math.max(ComponentConstants.STANDARD_WIDTH, graphics.getFontMetrics().stringWidth(TITLE) + 10);
		boolean anySection = false;

		for (Map.Entry<Integer, NpcDefenceState> e : tracked.entrySet())
		{
			NPC npc = wv.npcs().byIndex(e.getKey());
			if (npc == null || npc.isDead())
			{
				continue; // dead (or not here) -> no section
			}
			width = addSection(graphics, e.getValue(), anySection, width);
			anySection = true;
		}

		// Out of sight but still alive: kept for the "Out of sight" setting (default 5 min)
		for (ArrayDeque<SimpleDefencePlugin.HeldDrain> queue : plugin.getHeldDrains().values())
		{
			for (SimpleDefencePlugin.HeldDrain held : queue)
			{
				if (!plugin.isHeldExpired(held))
				{
					width = addSection(graphics, held.getState(), anySection, width);
					anySection = true;
				}
			}
		}

		if (!anySection)
		{
			return null;
		}

		panelComponent.setPreferredSize(new Dimension(width, 0));
		return super.render(graphics);
	}

	/** Adds one NPC's section (name + weapon lines). Returns the updated panel width. */
	private int addSection(Graphics2D graphics, NpcDefenceState s, boolean titleAdded, int width)
	{
		String name = s.getNpcName() == null ? "Unknown" : s.getNpcName();

		if (!titleAdded)
		{
			panelComponent.getChildren().add(TitleComponent.builder().text(TITLE).build());
		}

		panelComponent.getChildren().add(LineComponent.builder().left(name).leftColor(NAME_COLOR).build());
		width = Math.max(width, graphics.getFontMetrics().stringWidth(name) + 10);

		if (s.getDrainable() <= 0)
		{
			panelComponent.getChildren().add(LineComponent.builder().left("Fully drained").leftColor(Color.GREEN).build());
			return width;
		}

		for (DrainWeapon w : DISPLAY_ORDER)
		{
			if (!isShown(w))
			{
				continue;
			}
			// Specs (Tonalztics: hitsplats; BGS / Bone dagger / Anchor: damage needed)
			int count = w.panelCount(s);
			if (count > 0)
			{
				addWeaponLine(w, count);
			}
		}
		return width;
	}

	private void addWeaponLine(DrainWeapon w, int count)
	{
		String text = w.panelShowsDamage() ? ": " + count + " dmg" : ": " + count;
		LineComponent line = LineComponent.builder().left(text).build();
		BufferedImage icon = icons.get(w);
		if (icon == null)
		{
			panelComponent.getChildren().add(line);
			return;
		}
		panelComponent.getChildren().add(SplitComponent.builder()
			.first(new ImageComponent(icon))
			.second(line)
			.orientation(ComponentOrientation.HORIZONTAL)
			.gap(new Point(4, 0))
			.build());
	}

	private boolean isShown(DrainWeapon w)
	{
		switch (w)
		{
			case ELDER_MAUL:
				return config.showElderMaul();
			case DRAGON_WARHAMMER:
				return config.showDwh();
			case TONALZTICS_OF_RALOS:
				return config.showTonalztics();
			case BANDOS_GODSWORD:
				return config.showBgs();
			case ARCLIGHT:
				return config.showArclight();
			case EMBERLIGHT:
				return config.showEmberlight();
			case BONE_DAGGER:
				return config.showBoneDagger();
			case BARRELCHEST_ANCHOR:
				return config.showAnchor();
			case ACCURSED_SCEPTRE:
				return config.showAccursed();
			case SEERCULL:
				return config.showSeercull();
			default:
				return false;
		}
	}

	/** Runs once, on the client thread. Icons are scaled once and cached. */
	private void requestIcons()
	{
		iconsRequested = true;
		for (DrainWeapon w : DrainWeapon.values())
		{
			AsyncBufferedImage img = itemManager.getImage(w.iconItemId());
			img.onLoaded(() -> icons.put(w, ImageUtil.resizeImage(img, ICON_W, ICON_H)));
		}
	}
}
