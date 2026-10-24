package com.atlas;

import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class HistoryGraphTest
{
    @Test
    public void recoveryIsPositiveInsteadOfCumulativeLoss()
    {
        List<Wealth.Point> points = Arrays.asList(
            new Wealth.Point(100, 100000000, 0, 1, 0, 0, false),
            new Wealth.Point(200, 50000000, 0, 1, -50000000, -50000000, true),
            new Wealth.Point(300, 50000000, 0, 1, -50000000, -50000000, true),
            new Wealth.Point(400, 53000000, 0, 1, -47000000, -47000000, true));
        for (int metric : new int[]{3, 4})
        {
            WealthPanel.Graph graph = new WealthPanel.Graph();
            graph.setSize(694, 300);
            graph.setData(points, metric);
            assertEquals(-50000000, graph.value(points.get(1)));
            assertEquals(0, graph.value(points.get(2)));
            assertEquals(3000000, graph.value(points.get(3)));
            assertTrue(graph.getToolTipText(new MouseEvent(graph, 0, 0, 0, 570, 150, 0, false)).contains("+3,000,000 GP"));
            BufferedImage image = new BufferedImage(694, 300, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            graph.paint(graphics);
            graphics.dispose();
            int red = 0, green = 0, redTop = 300, greenBottom = 0;
            for (int y = 28; y < 262; y++)
            {
                if (image.getRGB(170, y) == WealthPanel.RED.getRGB()) { red++; redTop = Math.min(redTop, y); }
                if (image.getRGB(570, y) == WealthPanel.GREEN.getRGB()) { green++; greenBottom = Math.max(greenBottom, y); }
                assertNotEquals("Stable interval has no gain bar", WealthPanel.GREEN.getRGB(), image.getRGB(370, y));
                assertNotEquals("Stable interval has no loss bar", WealthPanel.RED.getRGB(), image.getRGB(370, y));
            }
            assertTrue(red > 0);
            assertTrue(green > 0);
            assertTrue("Gains are above zero and losses below", greenBottom < redTop);
        }
    }
    private List<Wealth.Point> history()
    {
        return Arrays.asList(
            new Wealth.Point(100, 1000, 0, 1, 100, 200, false),
            new Wealth.Point(200, 1030, 0, 1, 110, 220, true),
            new Wealth.Point(300, 1930, 0, 1, 110, 1120, false),
            new Wealth.Point(400, 1980, 0, 1, 130, 1150, true));
    }

    @Test
    public void missingIntervalDoesNotBecomeAnItemGain()
    {
        List<Wealth.Point> points = history();
        assertEquals(30, WealthPanel.knownChange(points, true));
        assertEquals(50, WealthPanel.knownChange(points, false));
        WealthPanel.Graph graph = new WealthPanel.Graph();
        graph.setData(points, 4);
        assertEquals(0, graph.value(points.get(0)));
        assertEquals(20, graph.value(points.get(1)));
        assertEquals(0, graph.value(points.get(2)));
        assertEquals(30, graph.value(points.get(3)));
        graph.setData(points, 3);
        assertEquals(20, graph.value(points.get(3)));
    }

    @Test
    public void shortHistoryWorksInLongRangesAndFirstPointIsOnlyABaseline()
    {
        List<Wealth.Point> points = history().subList(2, 4);
        assertEquals(20, WealthPanel.knownChange(points, true));
        assertEquals(30, WealthPanel.knownChange(points, false));
        WealthPanel.Graph graph = new WealthPanel.Graph();
        graph.setData(points, 4);
        assertEquals(0, graph.value(points.get(0)));
        assertEquals(30, graph.value(points.get(1)));
        assertEquals(0, WealthPanel.knownChange(Collections.singletonList(points.get(0)), false));
        assertEquals(0, WealthPanel.knownChange(Collections.emptyList(), false));
    }

    @Test
    public void netWorthStillIncludesChangesAcrossTheGap()
    {
        WealthPanel.Graph graph = new WealthPanel.Graph();
        List<Wealth.Point> points = history();
        graph.setData(points, 0);
        assertEquals(1930, graph.value(points.get(2)));
        assertEquals(1980, graph.value(points.get(3)));
    }

    @Test
    public void tooltipIdentifiesOnlyTheMissingInterval()
    {
        WealthPanel.Graph graph = new WealthPanel.Graph();
        graph.setSize(694, 300); // plotting area x=70..670; gap x=270..470
        graph.setData(history(), 4);
        assertTrue(graph.getToolTipText(new MouseEvent(graph, 0, 0, 0, 370, 150, 0, false)).contains("Missing"));
        assertFalse(graph.getToolTipText(new MouseEvent(graph, 0, 0, 0, 570, 150, 0, false)).contains("Missing"));
    }

    @Test
    public void bothSplitChartsRenderBarsButDoNotBridgeTheGap()
    {
        for (int metric : new int[]{3, 4})
        {
            WealthPanel.Graph graph = new WealthPanel.Graph();
            graph.setSize(694, 300);
            graph.setData(history(), metric);
            BufferedImage image = new BufferedImage(694, 300, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            graph.paint(graphics); graphics.dispose();
            int color = WealthPanel.GREEN.getRGB();
            int before = 0, gap = 0, after = 0;
            for (int y = 28; y < 262; y++)
            {
                for (int x = 75; x < 665; x++)
                {
                    if (image.getRGB(x, y) != color) { continue; }
                    if (x < 265) { before++; }
                    else if (x > 475) { after++; }
                    else if (x > 280 && x < 460) { gap++; }
                }
            }
            assertTrue("Known changes before gap rendered", before > 20);
            assertTrue("Known changes after gap rendered", after > 20);
            assertEquals("No invented line through missing interval", 0, gap);
        }
    }
}
