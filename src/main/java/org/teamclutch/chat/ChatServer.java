package org.teamclutch.chat;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.guava.CaffeinatedGuava;
import com.google.common.cache.Cache;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.net.InetAddresses;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.teamclutch.chat.protobuf.Message;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static org.teamclutch.chat.DataMessage.decode;
import static org.teamclutch.chat.DataMessage.encode;

class ChatServer extends AbstractExecutionThreadService {
    private final int PORT;
    private Thread executorThread;
    private final ClientConfig config;
    private final Chat chat;
    private final EventBus eventBus;

    ChatServer(Chat chat) {
        this.chat = chat;
        this.config = chat.config();
        this.eventBus = chat.eventBus();
        PORT = config.port();
    }

    @Override
    protected void run() throws Exception {
        executorThread = Thread.currentThread();

        SelfSignedCertificate ssc = new SelfSignedCertificate();
        SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                .build();

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    //.handler(new LoggingHandler(LogLevel.ERROR))
                    .childHandler(new SecureChatServerInitializer(sslCtx));

            ChannelFuture future = null;
            while (isRunning() && future == null) {
                try {
                    future = b.bind(PORT).sync().channel().closeFuture();
                } catch (Exception ex) {
                    chat.serverCantBind(ex);
                    Thread.sleep(5000);
                }
            }

            if (future != null) {
                chat.serverReady();
                future.sync();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            chat.gracefullyEnd();
        }
    }

    @Override
    public void triggerShutdown() {
        if (executorThread != null && executorThread.isAlive()) {
            executorThread.interrupt();
        }
    }


    /**
     * Handles a server-side channel.
     */
    private class SecureChatServerHandler extends SimpleChannelInboundHandler<String> {
        final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        @NotNull Cache<String, DataMessage> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
                .expireAfterAccess(1, TimeUnit.DAYS)
                .maximumSize(5000));
        ConcurrentHashMap<String, String> usernameClientMap = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, String> serverNodeMap = new ConcurrentHashMap<>();
        ClientConfig serverConfig;


        @Override
        public void channelActive(@NotNull final ChannelHandlerContext ctx) {
            // Once session is secured, send a greeting and register the channel to the global channel
            // list so the channel received the messages from others.
            if (serverConfig == null) {
                serverConfig = new ClientConfig();
                serverConfig.configureClient("SERVER", config.serverClientId().toString(), 0);
            }
            ctx.pipeline().get(SslHandler.class).handshakeFuture().addListener(
                    (GenericFutureListener<Future<Channel>>) future -> {
                        ctx.write(encode(DataMessage.createMessage(serverConfig,
                                "Welcome to " + InetAddress.getLocalHost().getHostName() + " secure chat!\n")
                        ));

                        ctx.write(encode(DataMessage.createMessage(serverConfig,
                                "Your session is protected by " +
                                        ctx.pipeline().get(SslHandler.class).engine().getSession().getCipherSuite() +
                                        " cipher suite.\n")));
                        ctx.flush();

                        channels.add(ctx.channel());
                        //config.connected(ctx.channel());
                    });
        }

        @Override
        public void channelRead0(@NotNull ChannelHandlerContext ctx, @NotNull String msg) throws Exception {
            // Send the received message to all channels but the current one.
            boolean close = false;

            Message.Packet decode = decode(msg);
            if (decode == null) {
                return;
            }

            Message.Data data = decode.getData();
            if (data != null) {
                if (data.getData().trim().toLowerCase().equals("exit")) {
                    close = true;
                }

                if (!close) {
                    if (cache.getIfPresent(getId(data)) == null) {
                        cache.put(getId(data),
                                new DataMessage(data));
                        sendMessageToAll(ctx, decode);
                    }
                }
            } else if (decode.getNew() != null) {
                Message.NewUser newUser = decode.getNew();
                String username = newUser.getUsername();
                if (newUser.getNode()) {
                    if (ctx.channel().remoteAddress() instanceof InetSocketAddress) {
                        InetSocketAddress address = (InetSocketAddress) ctx.channel().remoteAddress();
                        synchronized (serverNodeMap) {
                            serverNodeMap.put(newUser.getId(), InetAddresses.toAddrString(address.getAddress()));
                        }
                    }

                    Message.Servers.Builder servers = Message.Servers.newBuilder();
                    synchronized (serverNodeMap) {
                        for (Map.Entry<String, String> node : serverNodeMap.entrySet()) {
                            servers.addServer(Message.Servers.Server.newBuilder().setId(node.getKey())
                                    .setLocation(node.getValue()));
                        }
                    }
                    sendMessageToAll(ctx, Message.Packet.newBuilder().setServers(servers).build());
                }
                if (!usernameClientMap.containsKey(username)) {
                    usernameClientMap.put(username, newUser.getId());
                    String message = username + " joined the chat.";
                    sendMessageToAll(ctx, true, message);
                } else {
                    sendMessage((Channel) ctx, username + " already exists!", true);
                }

            } else if (decode.getRequest() != null) {
                Message.DataRequest request = decode.getRequest();
                String[] split = request.getId().split(":");
                if (split.length == 2) {
                    if (split[1].contains("-")) {
                        String[] boundaries = split[1].split("-");
                        if (boundaries.length == 2) {
                            long lower = Long.getLong(boundaries[0]);
                            long upper = Long.getLong(boundaries[1]);
                            List<DataMessage> messages = new LinkedList<>();
                            for (; lower <= upper; lower++) {
                                messages.add(cache.getIfPresent(split[0] + ":" + lower));
                            }
                            if (!messages.isEmpty()) {
                                ctx.writeAndFlush(
                                        encode(DataMessage.createDataPackage(
                                                messages.toArray(new DataMessage[messages.size()]))
                                        ));
                            }
                        }
                    }
                }
            } else if (decode.getPkg() != null) {
                Message.DataPackage pkg = decode.getPkg();
                for (Message.Data data1 : pkg.getMessagesList()) {
                    cache.put(getId(data1),
                            new DataMessage(
                                    data1.getUsername(), data1.getServerClientId(), data1.getId(), data1.getData()
                            )
                    );
                }
            } else if (decode.getPing() != null) {
                Message.Ping ping = decode.getPing();
                if (ping.getLoad().isEmpty()) {
                    ctx.writeAndFlush(encode(DataMessage.createPing()));
                }
            }

            ctx.flush();

            // Close the connection if the client has sent 'bye'.
            if (close) {
                final String username = data.getUsername();
                sendMessageToAll(ctx, true, username + " has decided to leave :(");
                if (usernameClientMap.containsKey(username)) {
                    usernameClientMap.remove(username);
                }
                ctx.close();
            }
        }

        private void sendMessage(@NotNull Channel ctx, @NotNull String message, boolean serverConfig) {
            if (serverConfig) {
                sendMessage(ctx, DataMessage.createMessage(this.serverConfig, message));
            } else {
                sendMessage(ctx, DataMessage.createMessage(config, message));
            }
        }

        private void sendMessage(@NotNull Channel ctx, @NotNull Message.Packet packet) {
            ctx.writeAndFlush(encode(packet));
        }

        private void sendMessageToAll(@Nullable ChannelHandlerContext ctx, boolean serverConfig, @NotNull String... message) {
            boolean includeMe = message.length > 1;
            checkArgument(!includeMe || ctx != null, "You must specify a ChannelHandlerContext if you want to exclude yourself!");
            for (Channel c : channels) {
                if (includeMe && c == ctx.channel()) {
                    sendMessage(c, message[1], serverConfig);
                } else {
                    sendMessage(c, message[0], serverConfig);
                }
            }
        }

        private void sendMessageToAll(@Nullable ChannelHandlerContext ctx, Message.@NotNull Packet... message) {
            boolean includeMe = message.length > 1;
            checkArgument(!includeMe || ctx != null, "You must specify a ChannelHandlerContext if you want to exclude yourself!");
            for (Channel c : channels) {
                if (includeMe && c == ctx.channel()) {
                    sendMessage(c, message[1]);
                } else {
                    sendMessage(c, message[0]);
                }
            }
        }

        @Override
        public void exceptionCaught(@NotNull ChannelHandlerContext ctx, @NotNull Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }

        @Subscribe
        public void receiveMessageToSend(Message.Packet data) {
            try {
                if (data.getData() != null) {
                    String key = getId(data.getData());
                    if (cache.getIfPresent(key) == null) {
                        sendMessageToAll(null, wrap((
                                cache.get(key, () -> new DataMessage(data.getData()))).createMessage()));
                    }
                }
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
            if (data.getData() != null) {
                sendMessageToAll(null, false, data.getData().getData());
            }
        }

        private Message.Packet wrap(Message.Data message) {
            return Message.Packet.newBuilder().setData(message).build();
        }
    }

    @NotNull
    private String getId(Message.Data data) {
        return data.getServerClientId() + ":" + data.getId();
    }

    /**
     * Creates a newly configured {@link ChannelPipeline} for a new channel.
     */
    private class SecureChatServerInitializer extends ChannelInitializer<SocketChannel> {

        private final SslContext sslCtx;

        SecureChatServerInitializer(SslContext sslCtx) {
            this.sslCtx = sslCtx;
        }

        @Override
        public void initChannel(@NotNull SocketChannel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            pipeline.addLast(sslCtx.newHandler(ch.alloc()));

            // On top of the SSL handler, add the text line codec.
            pipeline.addLast(new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));
            pipeline.addLast(new StringDecoder());
            pipeline.addLast(new StringEncoder());

            // and then business logic.
            final SecureChatServerHandler secureChatServerHandler = new SecureChatServerHandler();
            eventBus.register(secureChatServerHandler);
            pipeline.addLast(secureChatServerHandler);
        }
    }
}
