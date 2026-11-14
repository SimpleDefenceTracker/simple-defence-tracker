package com.simpledefence;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.ImageUtil;

/**
 * One line above each drained NPC's name, built from the stats enabled in config:
 *   [Att] 191/200  [Str] 191/200  [Def] 42/104  [Rng] 196/200  [Mag] 227/250  [Mag def] -30 mdef
 * Each stat is remaining/total, red -> green as it drains, and only appears once it has
 * been drained or a drain on it was attempted. Magic Defence stays -N (a bonus, no total).
 */
class SimpleDefenceOverlay extends Overlay
{
	private static final int ICON_GAP = 2;
	private static final int GROUP_GAP = 6;
	private static final int LINE_GAP = 2;
	private static final float HUE_GREEN = 1f / 3f;

	private static final Color MAGIC_DEFENCE_COLOR = new Color(190, 130, 255);

	private final Client client;
	private final SimpleDefencePlugin plugin;
	private final SimpleDefenceConfig config;
	private final Map<Skill, BufferedImage> icons = new EnumMap<>(Skill.class);
	/** Magic Defence (Eye of Ayak) icon, bundled in resources. Loaded once. */
	private final BufferedImage magicDefenceIcon;

	/** One piece of the line: icon + text. Reused list to avoid per-frame allocation churn. */
	private static final class Segment
	{
		BufferedImage icon;
		String text;
		Color color;
	}

	private final List<Segment> segments = new ArrayList<>();
	private final List<Segment> pool = new ArrayList<>();

	@Inject
	SimpleDefenceOverlay(Client client, SimpleDefencePlugin plugin, SimpleDefenceConfig config, SkillIconManager skillIconManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		for (Skill skill : new Skill[]{Skill.DEFENCE, Skill.MAGIC, Skill.ATTACK, Skill.STRENGTH, Skill.RANGED})
		{
			icons.put(skill, skillIconManager.getSkillImage(skill, true));
		}
		magicDefenceIcon = loadMagicDefenceIcon(icons.get(Skill.DEFENCE));
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/** Loads the bundled icon and scales it to the same height as the skill icons. */
	private static BufferedImage loadMagicDefenceIcon(BufferedImage reference)
	{
		BufferedImage img = ImageUtil.loadImageResource(SimpleDefenceOverlay.class, "magic_defence.png");
		if (img == null || reference == null || img.getHeight() == reference.getHeight())
		{
			return img;
		}
		int h = reference.getHeight();
		int w = Math.max(1, Math.round(img.getWidth() * (h / (float) img.getHeight())));
		return ImageUtil.resizeImage(img, w, h);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Map<Integer, NpcDefenceState> tracked = plugin.getTracked();
		if (tracked.isEmpty())
		{
			return null;
		}

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}

		FontMetrics fm = graphics.getFontMetrics();
		BufferedImage defIcon = icons.get(Skill.DEFENCE);
		int iconH = defIcon == null ? 0 : defIcon.getHeight();
		// Name labels (NPC Indicators etc.) sit at this height, so lift one line above them
		int lift = Math.max(fm.getHeight(), iconH) + LINE_GAP;
		int zOffset = config.heightOffset();

		for (Map.Entry<Integer, NpcDefenceState> e : tracked.entrySet())
		{
			NPC npc = wv.npcs().byIndex(e.getKey());
			if (npc == null || npc.isDead())
			{
				continue; // icon stays until this NPC dies
			}

			NpcDefenceState s = e.getValue();
			buildSegments(s);
			if (segments.isEmpty())
			{
				continue;
			}

			// Centre point above the head (empty string = zero width)
			Point anchor = npc.getCanvasTextLocation(graphics, "", npc.getLogicalHeight() + zOffset);
			if (anchor == null)
			{
				continue;
			}

			int rowW = -GROUP_GAP;
			for (Segment seg : segments)
			{
				rowW += GROUP_GAP + iconWidth(seg.icon) + fm.stringWidth(seg.text);
			}

			int x = anchor.getX() - rowW / 2;
			int y = anchor.getY() - lift;
			for (Segment seg : segments)
			{
				if (seg.icon != null)
				{
					OverlayUtil.renderImageLocation(graphics, new Point(x, y - seg.icon.getHeight() + 2), seg.icon);
				}
				x += iconWidth(seg.icon);
				OverlayUtil.renderTextLocation(graphics, new Point(x, y), seg.text, seg.color);
				x += fm.stringWidth(seg.text) + GROUP_GAP;
			}
		}
		return null;
	}

	private void buildSegments(NpcDefenceState s)
	{
		pool.addAll(segments);
		segments.clear();

		// Order: Attack, Strength, Defence, Ranged, Magic, then Magic Defence (Eye of Ayak).
		// Each stat reads remaining/total (red -> green) and only appears once it has been
		// drained or a drain on it was attempted (a miss, or a hit at its cap).
		if (config.headAttack() && shows(s, NpcDefenceState.ATTEMPT_ATTACK, s.getAttackDrained()))
		{
			addStat(Skill.ATTACK, s.getAttack(), s.getBaseAttack());
		}
		if (config.headStrength() && shows(s, NpcDefenceState.ATTEMPT_STRENGTH, s.getStrengthDrained()))
		{
			addStat(Skill.STRENGTH, s.getStrength(), s.getBaseStrength());
		}
		if (config.headDefence() && shows(s, NpcDefenceState.ATTEMPT_DEFENCE, s.getMaxDrainable() - s.getDrainable()))
		{
			addStat(Skill.DEFENCE, s.getDrainable(), s.getMaxDrainable());
		}
		if (config.headRanged() && shows(s, NpcDefenceState.ATTEMPT_RANGED, s.getRangedDrained()))
		{
			addStat(Skill.RANGED, s.getRanged(), s.getBaseRanged());
		}
		if (config.headMagic() && shows(s, NpcDefenceState.ATTEMPT_MAGIC, s.getMagicDrained()))
		{
			addStat(Skill.MAGIC, s.getMagic(), s.getBaseMagic());
		}
		if (config.headMagicDefence() && shows(s, NpcDefenceState.ATTEMPT_MAGIC_DEFENCE, s.getMagicDefenceDrained()))
		{
			add(magicDefenceIcon, "-" + s.getMagicDefenceDrained() + " mdef", MAGIC_DEFENCE_COLOR);
		}
	}

	private static boolean shows(NpcDefenceState s, int attemptFlag, int drained)
	{
		return drained > 0 || s.wasAttempted(attemptFlag);
	}

	/** remaining/total, coloured red (untouched) -> green (fully drained). */
	private void addStat(Skill skill, int remaining, int total)
	{
		add(skill, remaining + "/" + total, gradient(remaining, total));
	}

	private void add(Skill skill, String text, Color color)
	{
		add(icons.get(skill), text, color);
	}

	private void add(BufferedImage icon, String text, Color color)
	{
		Segment seg = pool.isEmpty() ? new Segment() : pool.remove(pool.size() - 1);
		seg.icon = icon;
		seg.text = text;
		seg.color = color;
		segments.add(seg);
	}

	private static int iconWidth(BufferedImage icon)
	{
		return icon == null ? 0 : icon.getWidth() + ICON_GAP;
	}

	/**
	 * Red at full defence, fading through orange and yellow to green once fully drained.
	 */
	private static Color gradient(int remaining, int total)
	{
		if (total <= 0 || remaining <= 0)
		{
			return Color.GREEN;
		}
		float drained = 1f - Math.min(1f, (float) remaining / total);
		return Color.getHSBColor(drained * HUE_GREEN, 1f, 1f);
	}
}
