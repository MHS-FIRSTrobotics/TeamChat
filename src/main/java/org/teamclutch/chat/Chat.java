package org.teamclutch.chat;

import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.ServiceManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.google.common.base.Preconditions.checkNotNull;

public class Chat extends AbstractIdleService {
    static final boolean DEBUG_VERSION = false;

    public static final int PORT = 8226;
    private final static List<Chat> instances = Collections.synchronizedList(new LinkedList<>());
    private final EventBus eventBus;
    private final ClientConfig clientConfig;
    private final ConcurrentLinkedQueue<DataMessage> messageQueue = new ConcurrentLinkedQueue<>();
    private final SynchronousQueue<String> messageSendQueue = new SynchronousQueue<>(true);
    private final ChatClient chatClient;
    private final ChatServer chatServer;
    private ServiceManager serviceManager;
    private volatile boolean serverUp = false;
    private final String host;

    public Chat(@NotNull String host, @NotNull String username, @Nullable String password, int port, @Nullable Object caller) {
        clientConfig = new ClientConfig();
        this.host = checkNotNull(host);
        while (password == null) {
            System.out.println("Welcome " + username + "!");
            System.out.print("Enter password: ");
            char[] data;
            if (System.console() != null) {
                data = System.console().readPassword();
            } else {
                List<Character> list = new LinkedList<>();
                char input;
                try {
                    while ((input = (char) System.in.read()) != '\n') {
                        list.add(input);
                        System.out.print('\b');
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                char[] chars = new char[list.size()];
                for (int i = 0; i < chars.length; i++) {
                    chars[i] = list.get(i);
                }

                data = chars;
            }
            if (data != null) {
                password = String.copyValueOf(data);
            }

            if (password != null && password.length() <= 8) {
                System.err.println("The entered password is too short!");
                password = null;
            }
        }
        clientConfig.configureClient(username, password, port);

        instances.add(this);
        eventBus = new EventBus(this.getClass().getSimpleName() + '#' + instances.size());

        chatClient = new ChatClient(this);
        chatServer = new ChatServer(this);
        serviceManager = new ServiceManager(Arrays.asList(chatClient, chatServer));

        if (caller != null) {
            eventBus.register(caller);
        }

        eventBus.register(this);
    }

    public Chat(@NotNull String host, @NotNull String username, @Nullable String password) {
        this(host, username, password, PORT, null);
    }

    public Chat(@NotNull String host, @NotNull String username, @Nullable String password, @NotNull Object caller) {
        this(host, username, password, PORT, caller);
    }

    @NotNull
    public Chat start() {
        super.startAsync();
        try {
            awaitRunning(5, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new RuntimeException("Failed to start chat session! Sorry :(", ex);
        }

        return this;
    }

    @Override
    protected void startUp() throws Exception {
        serviceManager.startAsync();
        serviceManager.awaitHealthy(5, TimeUnit.SECONDS);
        serviceManager.addListener(new ServiceManager.Listener() {
            @Override
            public void stopped() {
                super.stopped();
            }
        });
    }

    @Override
    protected void shutDown() throws Exception {
        serviceManager.stopAsync();
        serviceManager.awaitStopped(5, TimeUnit.SECONDS);
    }

    @NotNull
    public DataMessage getNextMessage() throws InterruptedException {
        while (messageQueue.isEmpty()) {
            synchronized (messageQueue) {
                messageQueue.wait();
            }
        }
        synchronized (messageQueue) {
            return messageQueue.poll();
        }
    }

    void newMessageReceived(@NotNull DataMessage message) {
        synchronized (messageQueue) {
            messageQueue.add(message);
            messageQueue.notifyAll();
        }

        eventBus.post(message);
    }

    public void newMessage(@NotNull String message) {
        try {
            if (this.isRunning() && chatClient.isRunning()) {
                messageSendQueue.put(message);
            } else {
                throw new IllegalStateException("Session disconnected :(");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @NotNull
    String getNextMessageToSend() throws InterruptedException {
        String poll = messageSendQueue.poll();
        while (poll == null) {
            Thread.yield();
            poll = messageSendQueue.poll();
        }
        return poll;
    }

    @NotNull
    public ClientConfig config() {
        return clientConfig;
    }

    void gracefullyEnd() {
        this.stopAsync();
    }

    void serverCantBind(Throwable ex) {
        checkNotNull(ex);
        serverUp = false;
    }

    void serverReady() {
        serverUp = true;
    }

    public boolean isServerUp() {
        return serverUp;
    }

    EventBus eventBus() {
        return eventBus;
    }

    public String host() {
        return host;
    }


    @Subscribe
    void deadEvents(DeadEvent dead) {
        if (DEBUG_VERSION) {
            System.err.println("\n[CLIENT]> [DEAD_EVENT] Event:\n" + dead.getEvent());
        }
    }

    @Subscribe
    void blackhole(DataMessage message) {

    }
}
