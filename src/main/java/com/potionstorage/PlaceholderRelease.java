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

import java.util.function.BooleanSupplier;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.util.Text;

/**
 * The bank menu's "Release all placeholders", made to count and release this plugin's placeholders with the
 * bank's own: its count includes them, and confirming in the game's "Release all placeholders?" pop-up releases
 * them too, places and all. The game offers no pop-up for a bank without placeholders of its own, so with only
 * this plugin's to release, the chatbox asks instead. Client thread only.
 */
final class PlaceholderRelease
{
	private static final String BUTTON_TEXT = "Release all placeholders";
	private static final String POPUP_TITLE = "Release all placeholders?";
	private static final String CONFIRM = "Release them all";

	private final Client client;
	private final ClientThread clientThread;
	private final ChatboxPanelManager chatboxPanelManager;
	private final SavedOrder savedOrder;
	private final BooleanSupplier enabled;
	private final Runnable relayout;

	/** The bank's own placeholders, as last counted. */
	private int bankPlaceholders;
	/** The button's count is one this set, not the game's. */
	private boolean counted;

	PlaceholderRelease(Client client, ClientThread clientThread, ChatboxPanelManager chatboxPanelManager,
		SavedOrder savedOrder, BooleanSupplier enabled, Runnable relayout)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.chatboxPanelManager = chatboxPanelManager;
		this.savedOrder = savedOrder;
		this.enabled = enabled;
		this.relayout = relayout;
	}

	void bankChanged(ItemContainer bank)
	{
		int n = 0;
		for (Item item : bank.getItems())
		{
			if (item.getId() >= 0)
			{
				ItemComposition def = client.getItemDefinition(item.getId());
				if (def.getPlaceholderTemplateId() != -1 && def.getPlaceholderId() != -1)
				{
					n++;
				}
			}
		}
		bankPlaceholders = n;
	}

	private int ours()
	{
		return enabled.getAsBoolean() ? savedOrder.placeholders().size() : 0;
	}

	/** The button's count, with this plugin's placeholders in it. Each frame the bank is open: cheap. */
	void syncButton()
	{
		int ours = ours();
		Widget text = buttonText();
		if (text == null || (ours == 0 && !counted))
		{
			return;
		}
		// None of ours left: the game's own text again, which it does not redraw for a release of ours alone.
		int total = bankPlaceholders + ours;
		String want = total > 0 ? BUTTON_TEXT + " (" + total + ")" : BUTTON_TEXT;
		counted = ours > 0;
		if (!want.equals(text.getText()))
		{
			text.setText(want);
		}
	}

	/** The button's own text, the one text component among its parts. */
	private Widget buttonText()
	{
		Widget button = client.getWidget(InterfaceID.Bankmain.RELEASE_PLACEHOLDERS);
		Widget[] parts = button == null ? null : button.getDynamicChildren();
		if (parts != null)
		{
			for (Widget w : parts)
			{
				if (w != null && w.getType() == WidgetType.TEXT)
				{
					return w;
				}
			}
		}
		return null;
	}

	void clicked(MenuOptionClicked e)
	{
		if (e.getParam1() == InterfaceID.Bankmain.RELEASE_PLACEHOLDERS && bankPlaceholders == 0 && ours() > 0)
		{
			// Nothing of the bank's to release: the game would say so and stop. Ask here instead.
			e.consume();
			int n = ours();
			chatboxPanelManager.openTextMenuInput("Release all " + n + " placeholder" + (n == 1 ? "" : "s") + "?")
				.option(CONFIRM, () -> clientThread.invoke(this::releaseAll))
				.option("Cancel", () ->
				{
				})
				.build();
		}
		else if (e.getParam1() == InterfaceID.Popupoverlay.BUTTON_1 && e.getMenuOption() != null
			&& CONFIRM.equals(Text.removeTags(e.getMenuOption())) && popupIsRelease())
		{
			releaseAll();
		}
	}

	/** The game's pop-up is a general one: it is ours only under this title. */
	private boolean popupIsRelease()
	{
		Widget frame = client.getWidget(InterfaceID.Popupoverlay.FRAME);
		Widget[] parts = frame == null ? null : frame.getDynamicChildren();
		if (parts != null)
		{
			for (Widget w : parts)
			{
				if (w != null && POPUP_TITLE.equals(Text.removeTags(w.getText() == null ? "" : w.getText())))
				{
					return true;
				}
			}
		}
		return false;
	}

	private void releaseAll()
	{
		savedOrder.release(savedOrder.placeholders());
		relayout.run();
	}
}
