package com.ttsplugin.main;

import com.google.inject.Provides;
import com.ttsplugin.enums.Gender;
import com.ttsplugin.enums.Voice;
import com.ttsplugin.utils.Utils;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.*;

@PluginDescriptor(name = "Text to speech", description = "Text to speech for chat and dialog", tags = {"tts", "text to speech", "voice", "chat", "dialog"})
public class TTSPlugin extends Plugin {	
	public HashMap<String, ArrayList<Long>> spamHash = new HashMap<>();
	public boolean isPlaying;
	public long lastProcess;
	public Dialog lastDialog;
	public Clip currentClip;

	@Inject
	private Client client;
	
	@Inject
	private TTSConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private KeyManager keyManager;
	@Inject
	private KeyboardHandler keyboardHandler;

	@Inject
	private MouseManager mouseManager;
	@Inject
	private MouseHandler mouseHandler;

	@Getter
	@Setter
	private Point menuOpenPoint;

	private final HotkeyListener hotkeyListener = new HotkeyListener(() -> this.config.narrateHotkey()) {
		@Override
		public void hotkeyPressed() {
			keyboardHandler.handleHotkey(config.narrateHotkey());
		}
	};
	private final HotkeyListener quantityHotkeyListener = new HotkeyListener(() -> this.config.narrateQuantityHotkey()) {
		@Override
		public void hotkeyPressed() {
			keyboardHandler.handleHotkey(config.narrateQuantityHotkey());
		}
	};
	@Override
	protected void startUp() {
		// TODO: consolidate hotkey vs click message processing
		this.keyManager.registerKeyListener(this.hotkeyListener);
		this.keyManager.registerKeyListener(this.quantityHotkeyListener);
		this.mouseManager.registerMouseListener(this.mouseHandler);
	}

	@Override
	protected void shutDown() {
		this.keyManager.unregisterKeyListener(this.hotkeyListener);
		this.keyManager.unregisterKeyListener(this.quantityHotkeyListener);
		this.mouseManager.unregisterMouseListener(this.mouseHandler);
	}

	@Provides
	TTSConfig getConfig(ConfigManager configManager) {
		return configManager.getConfig(TTSConfig.class);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event) {
		for (String line : config.blacklistedWords().split("\\r?\\n")) {
			if (!line.isEmpty() && line.length() > 1 && event.getMessage().contains(line)) {
				return;
			}
		}

		processMessage(event.getMessage(), event.getName(), event.getType(), false);
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (config.dialogs()) {
			Dialog dialog = Dialog.getCurrentDialog(client);

			if (dialog != null && !dialog.equals(lastDialog)) {
				if (currentClip != null) currentClip.stop();
				processMessage(dialog.message, dialog.sender, null, true);
			}
			
			lastDialog = dialog;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked menuOptionClicked) {
		if (!this.config.enableOnClick())
			return;

		boolean blacklist = menuOptionClicked.getMenuAction() != MenuAction.WALK &&
			menuOptionClicked.getMenuAction() != MenuAction.CANCEL &&
			menuOptionClicked.getMenuAction() != MenuAction.WIDGET_TYPE_6;

		// If the menu is open, and you click on a menu option, say it
		// If the menu is not open (clicking on something), only say it if it is not Walk, Cancel, or a dialog option
		if (this.client.isMenuOpen() || blacklist) {
			this.sayMenuOptionClicked(menuOptionClicked);
		}
	}

	public void processGameMessage(String message) {
		processMessage(message, "", null, false);
	}

	public void processMessage(String message, String sender, ChatMessageType type, boolean dialog) {
		if (Math.abs(System.currentTimeMillis() - lastProcess) < 50) return;
		lastProcess = System.currentTimeMillis();
		
		int voice = 0;
		int distance = 1;
		if (!dialog) {
			if (type != ChatMessageType.PUBLICCHAT && type != ChatMessageType.AUTOTYPER && !config.gameMessages()) return;
			if (type == ChatMessageType.AUTOTYPER && !config.autoChat()) return;
			if (!sender.isEmpty() && ignoreSpam(message, sender) && config.ignoreSpam()) return;
			if (!config.chatMessages()) return;
			
			Player player = getPlayerFromUsername(sender);
			voice = getVoice(sender, player == null ? Gender.UNKNOWN : Gender.get(player.getPlayerComposition().isFemale())).id;
			distance = player == null ? 0 : client.getLocalPlayer().getWorldLocation().distanceTo(player.getWorldLocation());
		} else {
			if (sender.equals(client.getLocalPlayer().getName())) {
				voice = getVoice(sender, Gender.get(client.getLocalPlayer().getPlayerComposition().isFemale())).id;
			} else {
				if (config.randomVoice() && !config.useDialogVoiceWithRandom()) {
					voice = getVoice(sender, Gender.UNKNOWN).id;
				} else {
					voice = config.dialogVoice().id;
				}
			}
		}
		
		final int voice2 = voice;
		final int distance2 = distance;
		new Thread(() -> {
			try {
				if (isPlaying) {
					long start = System.currentTimeMillis();
					int random = new Random().nextInt(300) + 50;
					while(true) {
						if (!isPlaying) {
							break;
						} else if (Math.abs(start - System.currentTimeMillis()) > config.queueMs()) {
							return;
						}
						
						Utils.sleep(random);
					}
				}
				
				isPlaying = true;
				play(ConvertMessage.convert(message), voice2, distance2);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}).start();
	}
	
	public void play(String text, int voice, int distance) {	
		new Thread(() -> {
			try {
				String request = "https://ttsplugin.com?m=" + URLEncoder.encode(text, "UTF-8") + "&r=" + config.rate() + "&v=" + voice;

			    URLConnection conn = new URL(request).openConnection();
			    byte[] bytes = new byte[conn.getContentLength()];
			    InputStream stream = conn.getInputStream();
			    for (int i = 0; i < conn.getContentLength(); i++) {
			    	bytes[i] = (byte)stream.read();
			    }
			    
				AudioInputStream inputStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes));
				
				Clip clip = AudioSystem.getClip();
		        clip.open(inputStream);
		        currentClip = clip;
		        
		        if (config.distanceVolume()) {
		        	Utils.setClipVolume((config.volume() / (float)10) - ((float)distance / (float)config.distanceVolumeEffect()), clip);
		        } else {
		        	Utils.setClipVolume(config.volume() / (float)10, clip);
		        }
		        
		        clip.start();
				Utils.sleep(50);
				
				while(clip.isRunning())  {
					Utils.sleep(50);
					if (client.getGameState() == GameState.LOGIN_SCREEN) {
						clip.stop();
						break;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			isPlaying = false;
		}).start();
	}
	
	public Voice getVoice(String sender, Gender gender) {
		if (config.useVoiceForSelfWithRandom() && sender.equals(client.getLocalPlayer().getName())) {
			return config.voice();
		}
		
		if (config.gameMessages() && sender.isEmpty()) {
			return config.gameMessageVoice();
		}
		
		if (config.randomVoice()) {
			List<Voice> voices = new ArrayList<>();
			for (Voice voice : Voice.values()) {
				if (voice.language.equals(config.randomVoiceLanguage()) && (voice.gender == gender || gender == Gender.UNKNOWN)) {
					voices.add(voice);
				}
			}
			
			if (voices.isEmpty()) {
				return getVoice(sender, gender.reverse());
			}
			
			return voices.get(Math.abs(sender.hashCode()) % voices.size());
		}
		
		return config.voice();
	}
	
	public boolean ignoreSpam(String message, String sender) {
		long ms = System.currentTimeMillis();
		
		Set<String> keySet = new HashSet<>();
		keySet.addAll(spamHash.keySet());
		for (String key : keySet) {
			ArrayList<Long> values = spamHash.get(key);
			if (values.isEmpty()) {
				spamHash.remove(key);
			}
			
			for (int i = 0; i < values.size(); i++) {
				if (Math.abs(ms - values.get(i)) > 30000) {
					spamHash.get(key).remove(values.get(i));
				}
			}
		}
		
		String key = message + sender;
		if (spamHash.containsKey(key)) {
			spamHash.get(key).add(ms);
		} else {
			ArrayList<Long> array = new ArrayList<>();
			array.add(ms);
			spamHash.put(key, array);
		}
		
		if (spamHash.get(key).size() > config.spamMessages()) {
			return true;
		}
		
		return false;
	}
	
	public Player getPlayerFromUsername(String username) {
		for (Player player : client.getCachedPlayers()) {
			if (player != null && player.getName() != null && Text.sanitize(player.getName()).equals(Text.sanitize(username))) {
				return player;
			}
		}
		
		return null;
	}

	private void sayMenuOptionClicked(MenuOptionClicked menuOptionClicked) {
		String actionName = menuOptionClicked.getMenuOption();
		String itemName = menuOptionClicked.getMenuTarget();
		final Widget widget = this.client.getWidget(menuOptionClicked.getParam1());
		if (widget != null) {
			if (widget.getChildren() != null) {
				if (widget.getParent().getId() == WidgetInfo.BANK_PIN_CONTAINER.getId()) {
					actionName = widget.getChild(1).getText();
				} else if (widget.getId() == WidgetInfo.PACK(553, 14)) { // Report reason
					actionName = widget.getChild(menuOptionClicked.getParam0() + 1).getText() + " " +
						widget.getChild(menuOptionClicked.getParam0() + 2).getText();
					// In bank ui (maybe other things too like deposit boxes or things like that?)
				} else {
					Widget child = widget.getChild(menuOptionClicked.getParam0());
					if (child != null && child.getItemId() > -1) {
						itemName = this.itemManager.getItemComposition(child.getItemId()).getName();
					}
				}
			} else if (widget.getParent().getId() == WidgetInfo.PACK(553, 7)) { // Report add to ignore
				actionName = this.client.getWidget(553, 8).getText();
				// normal inventory
			} else if (widget.getId() == WidgetInfo.INVENTORY.getId()) {
				WidgetItem itemWidget = widget.getWidgetItem(menuOptionClicked.getParam0());
				int itemID = itemWidget.getId();
				ItemComposition item = this.itemManager.getItemComposition(itemID);
				itemName = item.getName();
				// Fallback
			} else if (menuOptionClicked.getParam0() > -1) {
				itemName = widget.getChild(menuOptionClicked.getParam0()).getText();
			}
		}
		processGameMessage(actionName + " " + itemName);
	}

}
