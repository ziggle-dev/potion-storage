/*
 * Copyright (c) 2026, ziggle-dev
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.potionstorage;

import com.potionstorage.PotionVisibility.Where;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * One potion in the sidebar panel: its icon, the doses stored, and a strip of three switches under it - the
 * bank, tag tabs, search - lit where it is shown. A click on the icon shows or hides it in whatever places the
 * panel is editing; a click on a switch, in that place alone; a right-click offers every place by name.
 */
final class PotionTile extends JComponent
{
	static final int WIDTH = 40;
	static final int HEIGHT = 52;
	private static final int ICON_H = 38;
	private static final int STRIP_Y = ICON_H + 2;
	private static final int STRIP_H = HEIGHT - STRIP_Y - 2;
	private static final int ARC = 6;

	/** How a potion shown in none of the places being edited is drawn. */
	private static final float HIDDEN_ALPHA = 0.22f;
	/** How a potion shown in some of them is. */
	private static final float PARTLY_ALPHA = 0.6f;

	private static final Color STACK_YELLOW = new Color(255, 255, 0);
	private static final Color SWITCH_ON = ColorScheme.BRAND_ORANGE;
	private static final Color SWITCH_ON_HOVER = new Color(240, 165, 40);
	private static final Color SWITCH_OFF = ColorScheme.DARK_GRAY_COLOR;
	private static final Color SWITCH_OFF_HOVER = ColorScheme.MEDIUM_GRAY_COLOR;

	/** What the tile asks of whoever keeps the settings. */
	interface Owner
	{
		Set<Where> placesShown(int potionId);

		/** The places a click on the icon changes. */
		Set<Where> editing();

		void set(int potionId, Set<Where> places, boolean show);
	}

	final VisibilityPanel.Potion potion;
	private final Owner owner;
	private final BufferedImage icon;
	/** The icon in grey, made when first wanted; the image loading may clear it from the client thread. */
	private volatile BufferedImage greyIcon;
	private boolean hover;
	/** The switch under the mouse, or -1. */
	private int hoverSwitch = -1;

	PotionTile(VisibilityPanel.Potion potion, AsyncBufferedImage icon, Owner owner)
	{
		this.potion = potion;
		this.owner = owner;
		this.icon = icon;
		icon.onLoaded(() ->
		{
			greyIcon = null;
			repaint();
		});
		setPreferredSize(new Dimension(WIDTH, HEIGHT));
		setToolTipText("");
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hover = true;
				track(e);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover = false;
				hoverSwitch = -1;
				repaint();
			}

			@Override
			public void mouseMoved(MouseEvent e)
			{
				track(e);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showMenu(e);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showMenu(e);
				}
				else if (SwingUtilities.isLeftMouseButton(e) && contains(e.getPoint()))
				{
					click(switchAt(e.getX(), e.getY()));
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	private void track(MouseEvent e)
	{
		hoverSwitch = switchAt(e.getX(), e.getY());
		repaint();
	}

	/** Which of the three switches is at a point: -1 for the icon. */
	private int switchAt(int x, int y)
	{
		if (y < STRIP_Y)
		{
			return -1;
		}
		int n = Where.values().length;
		return Math.min(Math.max(x * n / Math.max(getWidth(), 1), 0), n - 1);
	}

	private void click(int switchIndex)
	{
		Set<Where> shown = owner.placesShown(potion.id);
		if (switchIndex >= 0)
		{
			Where w = Where.values()[switchIndex];
			owner.set(potion.id, EnumSet.of(w), !shown.contains(w));
		}
		else
		{
			// Hidden anywhere it is being edited: show it there. Shown in all of them: hide it.
			Set<Where> editing = owner.editing();
			owner.set(potion.id, editing, !shown.containsAll(editing));
		}
		repaint();
	}

	private void showMenu(MouseEvent e)
	{
		Set<Where> shown = owner.placesShown(potion.id);
		JPopupMenu menu = new JPopupMenu();
		for (Where w : Where.values())
		{
			JCheckBoxMenuItem item = new JCheckBoxMenuItem("Show in " + w.phrase, shown.contains(w));
			item.addActionListener(ev -> owner.set(potion.id, EnumSet.of(w), item.isSelected()));
			menu.add(item);
		}
		menu.addSeparator();
		JMenuItem all = new JMenuItem("Show everywhere");
		all.addActionListener(ev -> owner.set(potion.id, EnumSet.allOf(Where.class), true));
		JMenuItem none = new JMenuItem("Hide everywhere");
		none.addActionListener(ev -> owner.set(potion.id, EnumSet.allOf(Where.class), false));
		menu.add(all);
		menu.add(none);
		menu.show(this, e.getX(), e.getY());
	}

	@Override
	public String getToolTipText(MouseEvent e)
	{
		Set<Where> shown = owner.placesShown(potion.id);
		int s = switchAt(e.getX(), e.getY());
		if (s >= 0)
		{
			Where w = Where.values()[s];
			return (shown.contains(w) ? "Shown in " : "Hidden in ") + w.phrase + ": click to "
				+ (shown.contains(w) ? "hide" : "show");
		}
		StringBuilder b = new StringBuilder("<html><b>").append(potion.name).append("</b><br>");
		b.append(potion.doses > 0 ? potion.doses + (potion.doses == 1 ? " dose" : " doses") + " stored" : "None stored");
		List<Where> all = Arrays.asList(Where.values());
		List<Where> in = all.stream().filter(shown::contains).collect(Collectors.toList());
		List<Where> out = all.stream().filter(w -> !shown.contains(w)).collect(Collectors.toList());
		if (out.isEmpty())
		{
			b.append("<br>Shown everywhere");
		}
		else if (in.isEmpty())
		{
			b.append("<br>Hidden everywhere");
		}
		else
		{
			b.append("<br>Shown in ").append(phrases(in)).append("<br>Hidden in ").append(phrases(out));
		}
		return b.append("</html>").toString();
	}

	private static String phrases(List<Where> places)
	{
		return places.stream().map(w -> w.phrase).collect(Collectors.joining(", "));
	}

	@Override
	protected void paintComponent(Graphics g0)
	{
		Graphics2D g = (Graphics2D) g0.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Set<Where> shown = owner.placesShown(potion.id);
			Set<Where> editing = owner.editing();
			long shownOfEditing = editing.stream().filter(shown::contains).count();

			g.setColor(hover && hoverSwitch < 0 ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
			g.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);

			paintIcon(g, shownOfEditing == editing.size() ? 1f : shownOfEditing == 0 ? HIDDEN_ALPHA : PARTLY_ALPHA);
			if (potion.doses > 0)
			{
				paintCount(g);
			}
			paintSwitches(g, shown, editing);
		}
		finally
		{
			g.dispose();
		}
	}

	private void paintIcon(Graphics2D g, float alpha)
	{
		BufferedImage img = icon;
		if (potion.doses == 0)
		{
			// Never stored, or none left: the grey of an empty slot, to tell it from one that is hidden.
			img = greyIcon;
			if (img == null)
			{
				img = ImageUtil.grayscaleImage(icon);
				greyIcon = img;
			}
			alpha *= 0.7f;
		}
		Composite was = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		g.drawImage(img, (getWidth() - img.getWidth()) / 2, (ICON_H - img.getHeight()) / 2 + 2, null);
		g.setComposite(was);
	}

	/** The doses, as the game writes a stack's count: yellow, top left, with a shadow. */
	private void paintCount(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setFont(FontManager.getRunescapeSmallFont());
		String text = potion.doses >= 100_000 ? potion.doses / 1000 + "K" : String.valueOf(potion.doses);
		int y = g.getFontMetrics().getAscent();
		g.setColor(Color.BLACK);
		g.drawString(text, 3, y + 1);
		g.setColor(STACK_YELLOW);
		g.drawString(text, 2, y);
	}

	private void paintSwitches(Graphics2D g, Set<Where> shown, Set<Where> editing)
	{
		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = g.getFontMetrics();
		Where[] places = Where.values();
		int n = places.length;
		for (int i = 0; i < n; i++)
		{
			Where w = places[i];
			Rectangle r = switchBounds(i, n, getWidth());
			boolean on = shown.contains(w);
			boolean hot = hover && hoverSwitch == i;
			g.setColor(on ? (hot ? SWITCH_ON_HOVER : SWITCH_ON) : (hot ? SWITCH_OFF_HOVER : SWITCH_OFF));
			g.fillRoundRect(r.x, r.y, r.width, r.height, 4, 4);
			if (editing.contains(w) && editing.size() < n)
			{
				// The place the panel is editing, picked out.
				g.setColor(Color.WHITE);
				g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 4, 4);
			}
			String letter = letter(w);
			g.setColor(on ? Color.BLACK : ColorScheme.LIGHT_GRAY_COLOR);
			g.drawString(letter, r.x + (r.width - fm.stringWidth(letter)) / 2 + 1, r.y + (r.height + fm.getAscent()) / 2 - 1);
		}
	}

	private static Rectangle switchBounds(int i, int n, int width)
	{
		int gap = 2;
		int w = (width - 4 - gap * (n - 1)) / n;
		return new Rectangle(2 + i * (w + gap), STRIP_Y, w, STRIP_H);
	}

	static String letter(Where w)
	{
		switch (w)
		{
			case BANK:
				return "B";
			case TAGS:
				return "T";
			default:
				return "S";
		}
	}
}
