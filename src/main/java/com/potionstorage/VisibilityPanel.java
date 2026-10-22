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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.EqualsAndHashCode;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The sidebar panel: every potion potion storage keeps, and where each is shown - the bank, tag tabs,
 * searches. Pick what to edit at the top - one place, or all of them - and click potions to show or hide them
 * there; the switches under each potion change one place directly. The plugin's own on/off settings for each
 * place still apply on top, and the panel says so when one is off.
 * <p>
 * Swing's thread only. What the game knows - the potions and their doses - comes in through {@link #update}.
 */
final class VisibilityPanel extends PluginPanel
{
	private static final int COLUMNS = 5;
	private static final int GAP = 3;
	/** Swing sizes an HTML label's width in its own units, a little wider than the pixels it fills. */
	private static final int TEXT_WIDTH = 165;

	/** One potion kind, as the panel draws it. */
	@EqualsAndHashCode
	static final class Potion
	{
		final int id;
		final int iconId;
		final String name;
		final int doses;
		final boolean unfinished;

		Potion(int id, int iconId, String name, int doses, boolean unfinished)
		{
			this.id = id;
			this.iconId = iconId;
			this.name = name;
			this.doses = doses;
			this.unfinished = unfinished;
		}
	}

	/** The plugin's settings that turn a place off whatever the panel says, as they stand. */
	@EqualsAndHashCode
	static final class Switches
	{
		final boolean inBank;
		final boolean inTagTabs;
		final boolean inSearch;
		final boolean unfinished;

		Switches(boolean inBank, boolean inTagTabs, boolean inSearch, boolean unfinished)
		{
			this.inBank = inBank;
			this.inTagTabs = inTagTabs;
			this.inSearch = inSearch;
			this.unfinished = unfinished;
		}
	}

	/** What a click on a potion's icon changes. */
	enum Mode
	{
		ALL("All", EnumSet.allOf(Where.class)),
		BANK("Bank", EnumSet.of(Where.BANK)),
		TAGS("Tags", EnumSet.of(Where.TAGS)),
		SEARCH("Search", EnumSet.of(Where.SEARCH));

		final String label;
		final Set<Where> places;

		Mode(String label, Set<Where> places)
		{
			this.label = label;
			this.places = places;
		}
	}

	/** An item's icon, by id. */
	private final IntFunction<AsyncBufferedImage> icons;
	private final PotionVisibility visibility;
	/** Asked when the panel is opened: send the potions again, as they are now. */
	private final Runnable onOpen;

	private final PotionTile.Owner tileOwner;
	private final JLabel hint = new JLabel();
	private final JLabel notice = new JLabel();
	private final IconTextField filter = new IconTextField();
	private final Section potionsSection = new Section("Potions");
	private final Section unfinishedSection = new Section("Unfinished potions");
	private final JLabel empty = new JLabel();
	private final JButton showAll = new JButton("Show all");
	private final JButton hideAll = new JButton("Hide all");
	private final List<PotionTile> tiles = new ArrayList<>();

	private Mode mode = Mode.ALL;
	private List<Potion> potions = new ArrayList<>();
	private Switches switches = new Switches(true, true, true, true);

	VisibilityPanel(IntFunction<AsyncBufferedImage> icons, PotionVisibility visibility, Runnable onOpen)
	{
		this.icons = icons;
		this.visibility = visibility;
		this.onOpen = onOpen;
		this.tileOwner = new PotionTile.Owner()
		{
			@Override
			public Set<Where> placesShown(int potionId)
			{
				return visibility.placesShown(potionId);
			}

			@Override
			public Set<Where> editing()
			{
				return mode.places;
			}

			@Override
			public void set(int potionId, Set<Where> places, boolean show)
			{
				visibility.set(List.of(potionId), places, show);
				repaintTiles();
			}
		};

		JLabel title = new JLabel("Potion Storage");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setBorder(new EmptyBorder(2, 0, 4, 0));
		add(title);

		add(new ModeBar(m ->
		{
			mode = m;
			refreshText();
			repaintTiles();
		}));

		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add(hint);

		notice.setFont(FontManager.getRunescapeSmallFont());
		notice.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
		add(notice);

		filter.setIcon(IconTextField.Icon.SEARCH);
		filter.setPreferredSize(new Dimension(PANEL_WIDTH - 2 * BORDER_OFFSET, 30));
		filter.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		filter.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		filter.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuild();
			}
		});
		filter.addClearListener(this::rebuild);
		add(filter);

		add(potionsSection);
		add(unfinishedSection);

		empty.setFont(FontManager.getRunescapeSmallFont());
		empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		empty.setBorder(new EmptyBorder(8, 0, 8, 0));
		add(empty);

		JPanel buttons = new JPanel(new GridLayout(1, 2, GAP, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (JButton b : new JButton[]{showAll, hideAll})
		{
			b.setFont(FontManager.getRunescapeSmallFont());
			b.setFocusPainted(false);
			buttons.add(b);
		}
		showAll.addActionListener(e -> setShown(true));
		hideAll.addActionListener(e -> setShown(false));
		add(buttons);

		refreshText();
		rebuild();
	}

	@Override
	public void onActivate()
	{
		onOpen.run();
	}

	/** The potions and the plugin's settings, as they stand. */
	void update(List<Potion> potions, Switches switches)
	{
		if (potions.equals(this.potions) && switches.equals(this.switches))
		{
			// Nothing to lay out again: keep the tiles, and whatever the mouse is over.
			repaintTiles();
			return;
		}
		this.potions = potions;
		this.switches = switches;
		refreshText();
		rebuild();
	}

	/** The settings changed somewhere else - a profile switch, a reset. */
	void settingsChanged()
	{
		repaintTiles();
	}

	/** Show or hide every potion the filter leaves, in the places being edited. */
	private void setShown(boolean show)
	{
		List<Integer> ids = tiles.stream().map(t -> t.potion.id).collect(Collectors.toList());
		if (!ids.isEmpty())
		{
			visibility.set(ids, mode.places, show);
			repaintTiles();
		}
	}

	private void repaintTiles()
	{
		tiles.forEach(PotionTile::repaint);
	}

	private void refreshText()
	{
		String where = mode == Mode.ALL ? "everywhere" : "in " + mode.places.iterator().next().phrase;
		hint.setText(html("Click a potion to show or hide it " + where + ". The switches under each are the "
			+ "<b>B</b>ank, <b>T</b>ag tabs and <b>S</b>earch: click one to change that place alone. "
			+ "Right-click a potion for more."));

		List<String> off = new ArrayList<>();
		if (mode.places.contains(Where.BANK) && !switches.inBank)
		{
			off.add("\"Show in the bank\" is off in the plugin's settings, so the bank shows no stored potions.");
		}
		if (mode.places.contains(Where.TAGS) && !switches.inTagTabs)
		{
			off.add("\"Show in tag tabs\" is off, so only tag tabs with a layout show stored potions.");
		}
		if (mode.places.contains(Where.SEARCH) && !switches.inSearch)
		{
			off.add("\"Show in searches\" is off, so searches show no stored potions.");
		}
		notice.setText(off.isEmpty() ? "" : html(String.join("<br>", off)));
		notice.setVisible(!off.isEmpty());
		showAll.setToolTipText("Show every potion listed " + where);
		hideAll.setToolTipText("Hide every potion listed " + where);
	}

	/** Lay the tiles out again: new potions, new doses, or a new filter. */
	private void rebuild()
	{
		String q = filter.getText() == null ? "" : filter.getText().trim().toLowerCase();
		// Only the potions there are some of, in potion storage's own order.
		List<Potion> stored = potions.stream().filter(p -> p.doses > 0).collect(Collectors.toList());
		List<Potion> shown = stored.stream()
			.filter(p -> q.isEmpty() || p.name.toLowerCase().contains(q))
			.collect(Collectors.toList());

		tiles.clear();
		List<Potion> finished = shown.stream().filter(p -> !p.unfinished).collect(Collectors.toList());
		List<Potion> unfinished = shown.stream().filter(p -> p.unfinished).collect(Collectors.toList());
		potionsSection.fill(finished, null);
		unfinishedSection.fill(unfinished, switches.unfinished ? null
			: "\"Unfinished potions\" is off in the plugin's settings, so none of these are shown anywhere.");

		if (potions.isEmpty())
		{
			empty.setText(html("Log in to see your potions."));
		}
		else if (stored.isEmpty())
		{
			empty.setText(html("Potion storage is empty."));
		}
		else if (shown.isEmpty())
		{
			empty.setText(html("No potion's name has \"" + q + "\" in it."));
		}
		empty.setVisible(shown.isEmpty());
		showAll.setEnabled(!shown.isEmpty());
		hideAll.setEnabled(!shown.isEmpty());
		revalidate();
		repaint();
	}

	private static String html(String text)
	{
		return "<html><body style='width:" + TEXT_WIDTH + "px'>" + text + "</body></html>";
	}

	/** A heading, an optional note, and a grid of potions; hidden when it has none. */
	private final class Section extends JPanel
	{
		private final String name;
		private final JLabel heading = new JLabel();
		private final JLabel note = new JLabel();
		private final JPanel grid = new JPanel(new GridLayout(0, COLUMNS, GAP, GAP));

		Section(String name)
		{
			this.name = name;
			setLayout(new BorderLayout(0, 4));
			setBackground(ColorScheme.DARK_GRAY_COLOR);
			setBorder(new EmptyBorder(6, 0, 2, 0));

			JPanel top = new JPanel(new BorderLayout(0, 2));
			top.setBackground(ColorScheme.DARK_GRAY_COLOR);
			heading.setFont(FontManager.getRunescapeBoldFont());
			heading.setForeground(ColorScheme.TEXT_COLOR);
			heading.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
				new EmptyBorder(0, 0, 3, 0)));
			note.setFont(FontManager.getRunescapeSmallFont());
			note.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
			top.add(heading, BorderLayout.NORTH);
			top.add(note, BorderLayout.SOUTH);
			add(top, BorderLayout.NORTH);

			grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
			add(grid, BorderLayout.CENTER);
		}

		void fill(List<Potion> list, String warning)
		{
			heading.setText(name + "  (" + list.size() + ")");
			note.setText(warning == null ? "" : html(warning));
			note.setVisible(warning != null);
			grid.removeAll();
			for (Potion p : list)
			{
				PotionTile t = new PotionTile(p, icons.apply(p.iconId), tileOwner);
				tiles.add(t);
				grid.add(t);
			}
			setVisible(!list.isEmpty());
		}
	}

	/** The row of what a click on a potion edits: everywhere, or one place. */
	private static final class ModeBar extends JComponent
	{
		private static final int HEIGHT = 26;
		private final Consumer<Mode> onPick;
		private Mode picked = Mode.ALL;
		private Mode hot;

		ModeBar(Consumer<Mode> onPick)
		{
			this.onPick = onPick;
			setPreferredSize(new Dimension(PANEL_WIDTH - 2 * BORDER_OFFSET, HEIGHT));
			setToolTipText("What a click on a potion shows or hides it in");
			MouseAdapter mouse = new MouseAdapter()
			{
				@Override
				public void mouseMoved(MouseEvent e)
				{
					hot = at(e.getX());
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hot = null;
					repaint();
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					Mode m = at(e.getX());
					if (m != null && m != picked && contains(e.getPoint()))
					{
						picked = m;
						onPick.accept(m);
						repaint();
					}
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
		}

		private Mode at(int x)
		{
			Mode[] all = Mode.values();
			int i = x * all.length / Math.max(getWidth(), 1);
			return i >= 0 && i < all.length ? all[i] : null;
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			Graphics2D g = (Graphics2D) g0.create();
			try
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				g.setColor(ColorScheme.DARKER_GRAY_COLOR);
				g.fillRoundRect(0, 0, w, h, 6, 6);
				g.setFont(FontManager.getRunescapeSmallFont());
				FontMetrics fm = g.getFontMetrics();
				Mode[] all = Mode.values();
				for (int i = 0; i < all.length; i++)
				{
					Mode m = all[i];
					int x0 = i * w / all.length;
					int x1 = (i + 1) * w / all.length;
					if (m == picked)
					{
						g.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
						g.fillRoundRect(x0 + 2, 2, x1 - x0 - 4, h - 4, 5, 5);
						g.setColor(ColorScheme.BRAND_ORANGE);
						g.fillRect(x0 + 6, h - 4, x1 - x0 - 12, 2);
					}
					else if (m == hot)
					{
						g.setColor(ColorScheme.DARKER_GRAY_HOVER_COLOR);
						g.fillRoundRect(x0 + 2, 2, x1 - x0 - 4, h - 4, 5, 5);
					}
					g.setColor(m == picked ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
					g.drawString(m.label, x0 + (x1 - x0 - fm.stringWidth(m.label)) / 2, (h + fm.getAscent()) / 2 - 2);
				}
			}
			finally
			{
				g.dispose();
			}
		}
	}
}
