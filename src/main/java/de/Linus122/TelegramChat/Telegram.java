package de.Linus122.TelegramChat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import de.Linus122.TelegramComponents.ChatMessageToTelegram;
import de.Linus122.TelegramComponents.Chat;
import de.Linus122.TelegramComponents.ChatMessageToMc;
import de.Linus122.TelegramComponents.Update;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Bukkit;

public class Telegram {

	public static FileConfiguration cfg;
	public JsonObject authJson;
	public boolean connected = false;

	static int lastUpdate = 0;
	public String token;

	private List<TelegramActionListener> listeners = new ArrayList<TelegramActionListener>();

	private final String API_URL_GETME = "https://api.telegram.org/bot%s/getMe";
	private final String API_URL_GETUPDATES = "https://api.telegram.org/bot%s/getUpdates?offset=%d";
	private final String API_URL_GENERAL = "https://api.telegram.org/bot%s/%s";

	private Gson gson = new Gson();

	public void addListener(TelegramActionListener actionListener) {
		listeners.add(actionListener);
	}

	public boolean auth(String token) {
		this.token = token;
		return reconnect();
	}

	public boolean reconnect() {
		try {
			JsonObject obj = sendGet(String.format(API_URL_GETME, token));
			authJson = obj;
			System.out.print("[Telegram] Established a connection with the telegram servers.");
			connected = true;
			return true;
		} catch (Exception e) {
			connected = false;
			System.out.print("[Telegram] Sorry, but could not connect to Telegram servers. The token could be wrong.");
			return false;
		}
	}

	public boolean getUpdate() {
		Integer groupid =  cfg.getInt("group-id");
		JsonObject up = null;
		try {
			up = sendGet(String.format(API_URL_GETUPDATES, Main.getBackend().getToken(), lastUpdate + 1));
		} catch (IOException e) {
			return false;
		}
		if (up == null) {
			return false;
		}
		if (up.has("result")) {
			for (JsonElement ob : up.getAsJsonArray("result")) {
				if (ob.isJsonObject()) {
					Update update = gson.fromJson(ob, Update.class);

					if(lastUpdate == update.getUpdate_id()) return true;
					lastUpdate = update.getUpdate_id();


					if (update.getMessage() != null) {
						Chat chat = update.getMessage().getChat();
						System.out.print(chat.getId());
						if (!Main.getBackend().ids.contains(chat.getId()))
							Main.getBackend().ids.add(chat.getId());

						if (update.getMessage().getText() != null) {
							String text = update.getMessage().getText();
							if (text.length() == 0)
								return true;
							if (text.startsWith("/")) {
								switch (text) {
									case "/start":
										if (Main.getBackend().isFirstUse()) {
											Main.getBackend().setFirstUse(false);
											ChatMessageToTelegram chat2 = new ChatMessageToTelegram();
											chat2.chat_id = chat.getId();
											chat2.parse_mode = "Markdown";
											chat2.text = Utils.formatMSG("setup-msg")[0];
											this.sendMsg(chat2);
										}
										this.sendMsg(chat.getId(), Utils.formatMSG("can-see-but-not-chat")[0]);
										break;
									case "/getChatId":
//											this.sendMsg(chat.getId(), Integer.toString(chat.getId()));
										this.sendMsg(chat.getId(), Utils.formatMSG("get-chat-id", Integer.toString(chat.getId()))[0]);
										break;
								}
							} else if (Main.getBackend().getLinkCodes().containsKey(text) && update.getMessage().getChat().getId() == groupid) {
								// LINK
								Main.link(Main.getBackend().getUUIDFromLinkCode(text), chat.getId());
								Main.groupLink(Main.getBackend().getUUIDFromLinkCode(text), update.getMessage().getFrom().getId(), chat.getId());

								Main.getBackend().removeLinkCode(text);
							} else if (Main.getBackend().getGroupLinkedChats().containsKey(update.getMessage().getFrom().getId()) && update.getMessage().getChat().getId() == groupid) {
								ChatMessageToMc chatMsg = new ChatMessageToMc(Main.getBackend().getUUIDFromUserID(update.getMessage().getFrom().getId()), text, groupid);
								for (TelegramActionListener actionListener : listeners) {
									actionListener.onSendToMinecraft(chatMsg);
								}
								if(!chatMsg.isCancelled()){
//										Bukkit.getServer().broadcastMessage("[telegram] 4");
									Main.sendToMC(chatMsg);
								}
							} else {
								this.sendMsg(chat.getId(), Utils.formatMSG("need-to-link")[0]);
							}
						}
					}

				}
			}
		}
		return true;
	}

	public void sendMsg(int id, String msg) {
		ChatMessageToTelegram chat = new ChatMessageToTelegram();
		chat.chat_id = id;
		chat.text = msg;
		sendMsg(chat);
	}

	public void sendMsg(ChatMessageToTelegram chat) {
		for (TelegramActionListener actionListener : listeners) {
			actionListener.onSendToTelegram(chat);
		}
		Gson gson = new Gson();
		if(!chat.isCancelled()){
			post("sendMessage", gson.toJson(chat, ChatMessageToTelegram.class));
		}
	}

	public void sendAll(final ChatMessageToTelegram chat) {
		new Thread(new Runnable() {
			public void run() {
//				for (int id : Main.getBackend().ids) {
				chat.chat_id = cfg.getInt("group-id");
				// post("sendMessage", gson.toJson(chat, Chat.class));
				sendMsg(chat);
//				}
			}
		}).start();
	}

	public void post(String method, String json) {
		try {
			Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 8001));

			String body = json;
			URL url = new URL(String.format(API_URL_GENERAL, Main.getBackend().getToken(), method));
			HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
			connection.setRequestMethod("POST");
			connection.setDoInput(true);
			connection.setDoOutput(true);
			connection.setUseCaches(false);
			connection.setRequestProperty("Content-Type", "application/json; ; Charset=UTF-8");
			connection.setRequestProperty("Content-Length", String.valueOf(body.length()));

			DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(wr, "UTF-8"));
			writer.write(body);
			writer.close();
			wr.close();

			BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

			writer.close();
			reader.close();
		} catch (Exception e) {
			reconnect();
			System.out.print("[Telegram] Disconnected from Telegram, reconnect...");
		}

	}

	public JsonObject sendGet(String url) throws IOException {
		Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 8001));
		String a = url;
		URL url2 = new URL(a);
		URLConnection conn = url2.openConnection(proxy);

		BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));

		String all = "";
		String inputLine;
		while ((inputLine = br.readLine()) != null) {
			all += inputLine;
		}

		br.close();
		JsonParser parser = new JsonParser();
		return parser.parse(all).getAsJsonObject();

	}

}
