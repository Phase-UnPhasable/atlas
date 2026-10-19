package com.atlas;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Locale;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Display the existing session result above the backpack tab; no valuation work during rendering. */
final class InventorySessionOverlay extends Overlay
{
    private static final Font BADGE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Color BADGE_BACKGROUND = new Color(12, 15, 20, 240);
    private static final int[] INVENTORY_TABS = {
        InterfaceID.Toplevel.STONE3,
        InterfaceID.ToplevelOsrsStretch.STONE3,
        InterfaceID.ToplevelPreEoc.STONE3
    };
    private final Client client;
    private volatile Value value;

    InventorySessionOverlay(Client client)
    {
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    void update(Wealth.Session session)
    {
        value = session == null ? null : new Value(session.change);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        Value current = value;
        if (current == null || client.getGameState() != GameState.LOGGED_IN) { return null; }
        for (int id : INVENTORY_TABS)
        {
            Widget tab = client.getWidget(id);
            if (tab == null || tab.isHidden()) { continue; }
            Rectangle bounds = tab.getBounds();
            if (bounds.width < 1 || bounds.height < 1 || bounds.x < 0 || bounds.y < 0) { continue; }
            paint(graphics, bounds, current);
            break;
        }
        return null;
    }

    static final class Value
    {
        final String text;
        final Color color;

        Value(long change)
        {
            text = WealthPanel.signed(change).toLowerCase(Locale.ROOT);
            color = change < 0 ? new Color(255, 70, 70) : change > 0 ? new Color(60, 255, 90) : Color.WHITE;
        }
    }

    /** Package-visible for an offline layout fixture; coordinates come from the visible tab. */
    static void paint(Graphics2D graphics, Rectangle bounds, Value value)
    {
        Graphics2D g = (Graphics2D) graphics.create();
        try
        {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            g.setFont(BADGE_FONT);
            FontMetrics metrics = g.getFontMetrics();
            int width = metrics.stringWidth(value.text);
            int badgeWidth = Math.max(bounds.width - 2, width + 10);
            int badgeHeight = metrics.getHeight() + 4;
            int x = bounds.x + (bounds.width - badgeWidth) / 2;
            int y = bounds.y - badgeHeight - 2;
            // Expand the badge rather than squeezing the digits into a narrow tab.
            Rectangle clip = g.getClipBounds();
            if (clip != null && badgeWidth <= clip.width)
            { x = Math.max(clip.x, Math.min(x, clip.x + clip.width - badgeWidth)); }
            g.setColor(BADGE_BACKGROUND);
            g.fillRoundRect(x, y, badgeWidth, badgeHeight, 7, 7);
            g.setColor(new Color(value.color.getRed(), value.color.getGreen(), value.color.getBlue(), 110));
            g.drawRoundRect(x, y, badgeWidth - 1, badgeHeight - 1, 7, 7);
            g.setColor(value.color);
            g.drawString(value.text, x + (badgeWidth - width) / 2, y + 2 + metrics.getAscent());
        }
        finally { g.dispose(); }
    }
}
