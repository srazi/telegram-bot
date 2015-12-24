package org.telegram.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.telegram.api.TLAbsInputFile;
import org.telegram.api.TLAbsInputPeer;
import org.telegram.api.TLAbsInputUser;
import org.telegram.api.TLAbsUpdates;
import org.telegram.api.TLConfig;
import org.telegram.api.TLInputFile;
import org.telegram.api.TLInputFileBig;
import org.telegram.api.TLInputMediaUploadedPhoto;
import org.telegram.api.TLInputPeerChat;
import org.telegram.api.TLInputPeerContact;
import org.telegram.api.TLInputUserContact;
import org.telegram.api.TLInputUserSelf;
import org.telegram.api.TLUpdateShortChatMessage;
import org.telegram.api.TLUpdateShortMessage;
import org.telegram.api.auth.TLAbsSentCode;
import org.telegram.api.auth.TLAuthorization;
import org.telegram.api.engine.ApiCallback;
import org.telegram.api.engine.AppInfo;
import org.telegram.api.engine.LoggerInterface;
import org.telegram.api.engine.RpcCallback;
import org.telegram.api.engine.RpcException;
import org.telegram.api.engine.TelegramApi;
import org.telegram.api.engine.file.Uploader;
import org.telegram.api.messages.TLAbsSentMessage;
import org.telegram.api.messages.TLAbsStatedMessage;
import org.telegram.api.requests.TLRequestAccountUpdateStatus;
import org.telegram.api.requests.TLRequestAuthSendCode;
import org.telegram.api.requests.TLRequestAuthSignIn;
import org.telegram.api.requests.TLRequestHelpGetConfig;
import org.telegram.api.requests.TLRequestMessagesCreateChat;
import org.telegram.api.requests.TLRequestMessagesSendMedia;
import org.telegram.api.requests.TLRequestMessagesSendMessage;
import org.telegram.api.requests.TLRequestUpdatesGetState;
import org.telegram.api.updates.TLState;
import org.telegram.bot.engine.MemoryApiState;
import org.telegram.mtproto.log.LogInterface;
import org.telegram.mtproto.log.Logger;
import org.telegram.tl.TLVector;

/**
 * Created by ex3ndr on 13.01.14.
 */
public class Application {

	private static final String COMMAND_PREFIX = "/";
	// Note! Change these values to your own api_id and api_hash.
	private static final int API_ID = 5;
    private static final String API_HASH = "1c5c96d5edd401b1ed40db3fb5633e2d";

	private static HashMap<Integer, PeerState> userStates = new HashMap<Integer, PeerState>();
    private static HashMap<Integer, PeerState> chatStates = new HashMap<Integer, PeerState>();
    private static MemoryApiState apiState;
    private static TelegramApi api;
    private static Random rnd = new Random();
    private static long lastOnline = System.currentTimeMillis();
    private static Executor mediaSender = Executors.newSingleThreadExecutor();

    public static void main(String[] args) throws IOException {
		setupLogging();
        createApi();
        login();
        workLoop();
    }

    private static synchronized PeerState getUserPeer(int uid) {
        if (!userStates.containsKey(uid)) {
            userStates.put(uid, new PeerState(uid, true));
        }
        return userStates.get(uid);
    }

    private static synchronized PeerState getChatPeer(int chatId) {
        if (!chatStates.containsKey(chatId)) {
            chatStates.put(chatId, new PeerState(chatId, false));
        }

        return chatStates.get(chatId);
    }

    private static void sendMedia(PeerState peerState, String fileName) {
        TLAbsInputPeer inputPeer = peerState.isUser() ? new TLInputPeerContact(peerState.getId()) : new TLInputPeerChat(peerState.getId());

        int task = api.getUploader().requestTask(fileName, null);
        api.getUploader().waitForTask(task);
        int resultState = api.getUploader().getTaskState(task);
        Uploader.UploadResult result = api.getUploader().getUploadResult(task);
        TLAbsInputFile inputFile;
        if (result.isUsedBigFile()) {
            inputFile = new TLInputFileBig(result.getFileId(), result.getPartsCount(), "file.jpg");
        } else {
            inputFile = new TLInputFile(result.getFileId(), result.getPartsCount(), "file.jpg", result.getHash());
        }
        try {
            TLAbsStatedMessage res = api.doRpcCall(new TLRequestMessagesSendMedia(inputPeer, new TLInputMediaUploadedPhoto(inputFile), rnd.nextInt()), 30000);
            res.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void sendMessage(PeerState peerState, String message) {
        if (peerState.isUser()) {
            sendMessageUser(peerState.getId(), message);
        } else {
            sendMessageChat(peerState.getId(), message);
        }
    }

    private static void sendMessageChat(int chatId, String message) {
        api.doRpcCall(new TLRequestMessagesSendMessage(new TLInputPeerChat(chatId), message, rnd.nextInt()),
                new RpcCallback<TLAbsSentMessage>() {
                    @Override
                    public void onResult(TLAbsSentMessage result) {

                    }

                    @Override
                    public void onError(int errorCode, String message) {
                    }
                });
    }

    private static void sendMessageUser(int uid, String message) {
        api.doRpcCall(new TLRequestMessagesSendMessage(new TLInputPeerContact(uid), message, rnd.nextInt()),
                new RpcCallback<TLAbsSentMessage>() {
                    @Override
                    public void onResult(TLAbsSentMessage result) {

                    }

                    @Override
                    public void onError(int errorCode, String message) {

                    }
                });
    }

	private static void createGroup(int uid, final String title) {
        TLVector<TLAbsInputUser> users = new TLVector<>();
        users.add(new TLInputUserSelf());
        users.add(new TLInputUserContact(uid));
		api.doRpcCall(new TLRequestMessagesCreateChat(users, title),
                new RpcCallback<TLAbsStatedMessage>() {
                    @Override
                    public void onResult(TLAbsStatedMessage result) {
                    	System.out.println("Group created: " + title);
                    }

                    @Override
                    public void onError(int errorCode, String message) {
                    	System.out.println("Error creating group: " + title + ", ErrorCode: " + 
                    			errorCode + ", Message: " + message);
                    }
                });
    }
    
    private static void onIncomingMessageUser(int uid, String message) {
        System.out.println("Incoming message from user #" + uid + ": " + message);
		PeerState peerState = getUserPeer(uid);
		if (message.startsWith(COMMAND_PREFIX)) {
			processCommand(message.trim().substring(1), peerState);
		} else {
			processCommand("help", peerState);
        }
    }

    private static void onIncomingMessageChat(int chatId, String message) {
        System.out.println("Incoming message in chat #" + chatId + ": " + message);
		PeerState peerState = getChatPeer(chatId);
		if (message.startsWith(COMMAND_PREFIX)) {
			processCommand(message.trim().substring(1), peerState);
		} else {
			processCommand("help", peerState);
        }
    }

    private static void processCommand(String message, final PeerState peerState) {
        String[] args = message.split(" ");
        if (args.length == 0) {
            sendMessage(peerState, "Unknown command");
        }
        String command = args[0].trim().toLowerCase();
		String argument = message.substring(command.length()).trim();
		if (command.equals("ping")) {
			sendMessage(peerState, "pong ");
        } else if (command.equals("help")) {
            sendMessage(peerState, "Bot commands:\n" +
            		"/help - this help text\n" +
                    "/ping - pong\n" +
            		"/create-group [name]\n" +
                    "/img - sending sample image\n");
        } else if (command.equals("create-group")) {
			if (!peerState.isUser()) {
				sendMessage(peerState, "Not supported in group");
			}
			if (argument.length() > 0) {
				createGroup(peerState.getId(), argument);
			} else {
				createGroup(peerState.getId(), "New Group");
			}
        } else if (command.equals("img")) {
            mediaSender.execute(new Runnable() {
                @Override
                public void run() {
                    sendMedia(peerState, "demo.jpg");
                }
            });
        } else {
            sendMessage(peerState, "Unknown command '" + args[0] + "'");
        }
    }

    private static void workLoop() {
        while (true) {
            try {
                if (System.currentTimeMillis() - lastOnline > 60 * 1000) {
                    api.doRpcCallWeak(new TLRequestAccountUpdateStatus(false));
                    lastOnline = System.currentTimeMillis();
                }
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void setupLogging() {
        Logger.registerInterface(new LogInterface() {
            @Override
            public void w(String tag, String message) {
				// System.out.println(tag + ": " + message);
            }

            @Override
            public void d(String tag, String message) {

            }

            @Override
            public void e(String tag, Throwable t) {
				// t.printStackTrace();
            }
        });
        org.telegram.api.engine.Logger.registerInterface(new LoggerInterface() {
            @Override
            public void w(String tag, String message) {
				// System.out.println(tag + ": " + message);
            }

            @Override
            public void d(String tag, String message) {

            }

            @Override
            public void e(String tag, Throwable t) {
				// t.printStackTrace();
            }
        });
    }

    private static void createApi() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.print("Use test DC? (write test for test servers): ");
        String res = reader.readLine();
        boolean useTest = res.equals("test");
        if (!useTest) {
            System.out.println("Using production servers");
        } else {
            System.out.println("Using test servers");
        }
        apiState = new MemoryApiState(useTest);
        api = new TelegramApi(apiState, new AppInfo(API_ID, "console", "???", "???", "en"), new ApiCallback() {

            @Override
            public void onAuthCancelled(TelegramApi api) {

            }

            @Override
            public void onUpdatesInvalidated(TelegramApi api) {

            }

            @Override
            public void onUpdate(TLAbsUpdates updates) {
                if (updates instanceof TLUpdateShortMessage) {
                    onIncomingMessageUser(((TLUpdateShortMessage) updates).getFromId(), ((TLUpdateShortMessage) updates).getMessage());
                } else if (updates instanceof TLUpdateShortChatMessage) {
                    onIncomingMessageChat(((TLUpdateShortChatMessage) updates).getChatId(), ((TLUpdateShortChatMessage) updates).getMessage());
                }
            }
        });
    }

    private static void login() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.print("Loading fresh DC list...");
        TLConfig config = api.doRpcCallNonAuth(new TLRequestHelpGetConfig());
        apiState.updateSettings(config);
        System.out.println("completed.");
        System.out.print("Phone number for bot:");
        String phone = reader.readLine();
        System.out.print("Sending sms to phone " + phone + "...");
		TLAbsSentCode sentCode;
        try {
            sentCode = api.doRpcCallNonAuth(new TLRequestAuthSendCode(phone, 0, API_ID, API_HASH, "en"));
        } catch (RpcException e) {
            if (e.getErrorCode() == 303) {
                int destDC;
                if (e.getErrorTag().startsWith("NETWORK_MIGRATE_")) {
                    destDC = Integer.parseInt(e.getErrorTag().substring("NETWORK_MIGRATE_".length()));
                } else if (e.getErrorTag().startsWith("PHONE_MIGRATE_")) {
                    destDC = Integer.parseInt(e.getErrorTag().substring("PHONE_MIGRATE_".length()));
                } else if (e.getErrorTag().startsWith("USER_MIGRATE_")) {
                    destDC = Integer.parseInt(e.getErrorTag().substring("USER_MIGRATE_".length()));
                } else {
                    throw e;
                }
                api.switchToDc(destDC);
                sentCode = api.doRpcCallNonAuth(new TLRequestAuthSendCode(phone, 0, API_ID, API_HASH, "en"));
            } else {
                throw e;
            }
        }
        System.out.println("sent.");
        System.out.print("Activation code:");
        String code = reader.readLine();
        TLAuthorization auth = api.doRpcCallNonAuth(new TLRequestAuthSignIn(phone, sentCode.getPhoneCodeHash(), code));
        apiState.setAuthenticated(apiState.getPrimaryDc(), true);
        System.out.println("Activation complete.");
        System.out.print("Loading initial state...");
        TLState state = api.doRpcCall(new TLRequestUpdatesGetState());
        System.out.println("loaded.");
    }
}
