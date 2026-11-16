package com.simpledefence;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * Highlights each drained NPC (hull, tile, true tile or outline) in the same
 * red -> green colour as its Defence above the head: red = no Defence drained,
 * green = fully drained. Only NPCs whose Defence was drained (or a drain attempted)
 * are highlighted, so it costs nothing when nothing is drained.
 */
class SimpleDefenceHighlightOverlay extends Overlay
{
	private static final int OUTLINE_FEATHER = 4;

	private final Client client;
	private final SimpleDefencePlugin plugin;
	private final SimpleDefenceConfig config;
	private final ModelOutlineRenderer outlineRenderer;

	/** Rebuilt only when the width setting changes. */
	private BasicStroke stroke;
	private int strokeWidth = -1;

	@Inject
	SimpleDefenceHighlightOverlay(Client client, SimpleDefencePlugin plugin, SimpleDefenceConfig config, ModelOutlineRenderer outlineRenderer)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.outlineRenderer = outlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.highlightNpc())
		{
			return null;
		}

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

		HighlightStyle style = config.highlightStyle();
		int width = config.highlightWidth();
		if (width != strokeWidth)
		{
			stroke = new BasicStroke(width);
			strokeWidth = width;
		}
		int fillAlpha = config.highlightFillOpacity();

		for (Map.Entry<Integer, NpcDefenceState> e : tracked.entrySet())
		{
			NPC npc = wv.npcs().byIndex(e.getKey());
			if (npc == null || npc.isDead())
			{
				continue;
			}

			NpcDefenceState s = e.getValue();
			int total = s.getMaxDrainable();
			int remaining = s.getDrainable();
			if (total - remaining <= 0 && !s.wasAttempted(NpcDefenceState.ATTEMPT_DEFENCE))
			{
				continue; // Defence untouched (e.g. only Magic drained)
			}

			Color color = SimpleDefenceOverlay.gradient(remaining, total);
			switch (style)
			{
				case OUTLINE:
					outlineRenderer.drawOutline(npc, width, color, OUTLINE_FEATHER);
					break;
				case TILE:
					draw(graphics, npc.getCanvasTilePoly(), color, fillAlpha);
					break;
				case TRUE_TILE:
					draw(graphics, trueTile(wv, npc), color, fillAlpha);
					break;
				case HULL:
				default:
					draw(graphics, npc.getConvexHull(), color, fillAlpha);
					break;
			}
		}
		return null;
	}

	private void draw(Graphics2D graphics, Shape shape, Color color, int fillAlpha)
	{
		if (shape == null)
		{
			return;
		}
		Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), fillAlpha);
		OverlayUtil.renderPolygon(graphics, shape, color, fill, stroke);
	}

	/** The NPC's server-side tile area (where it really is, not where it's animating). */
	private Shape trueTile(WorldView wv, NPC npc)
	{
		NPCComposition comp = npc.getTransformedComposition();
		if (comp == null)
		{
			comp = npc.getComposition();
		}
		int size = comp == null ? 1 : comp.getSize();

		LocalPoint lp = LocalPoint.fromWorld(wv, npc.getWorldLocation());
		if (lp == null)
		{
			return null;
		}
		// fromWorld gives the south-west tile; the area poly is centred, so shift to the middle
		int shift = Perspective.LOCAL_TILE_SIZE * (size - 1) / 2;
		LocalPoint centre = new LocalPoint(lp.getX() + shift, lp.getY() + shift, wv);
		return Perspective.getCanvasTileAreaPoly(client, centre, size);
	}
}
