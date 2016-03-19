package org.teamclutch.chat;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.jetbrains.annotations.NotNull;
import org.teamclutch.chat.protobuf.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.teamclutch.chat.DataMessage.encode;

class ChatClient extends AbstractClient {
    private final Chat instance;
    private final List<String> servers = Collections.synchronizedList(new ArrayList<>());

    ChatClient(Chat instance) {
        super(instance.config(), instance.host());
        this.instance = instance;
        servers.add(HOST);
    }

    @Override
    protected void run() throws Exception {
        boolean exit = false;
        while (isRunning() && !exit) {
            try {
                Bootstrap b = bootstrap();

                // Start the connection attempt.
                while (isRunning() && servers.isEmpty()) {
                    synchronized (servers) {
                        servers.wait();
                    }
                }
                final Channel ch;

                try {
                    synchronized (servers) {
                        ch = b.connect(servers.get(0), PORT).sync().channel();
                    }
                } catch (Exception ex) {
                    System.err.println("Attempt to connect failed to " + servers.get(0));
                    Thread.sleep(5000);
                    continue;
                }

                // Read commands from the stdin.
                ChannelFuture lastWriteFuture;
                final ClientConfig clientConfig = instance.config();
                ch.writeAndFlush(encode(DataMessage.createNewUserRequest(clientConfig)));
                while (isRunning()) {
                    String line = instance.getNextMessageToSend();
                    // Sends the received line to the server.
                    Message.Packet message = DataMessage.createMessage(clientConfig, line);
                    @NotNull String encode = encode(message);
                    lastWriteFuture = ch.writeAndFlush(encode);
                    if (!lastWriteFuture.await(1, TimeUnit.SECONDS) && !ch.closeFuture().await(1, TimeUnit.SECONDS)) {
                        ch.write(DataMessage.createMessage(clientConfig, "exit"));
                        continue;
                    }

                    // If user typed the 'exit' command, wait until the server closes
                    // the connection.
                    if ("exit".equals(line.toLowerCase())) {
                        System.out.print("\nWaiting last write...");
                        lastWriteFuture.await(1000);
                        System.out.print("\rWaiting client exit...");
                        ch.closeFuture().await(5000);
                        System.out.println("\rBye!");
                        exit = true;
                        break;
                    }
                }
            } finally {
                // The connection is closed automatically on shutdown.
                eventLoopGroup().shutdownGracefully();
                instance.gracefullyEnd();
                while (isRunning()) {
                    Thread.yield();
                }
            }
        }
    }

    @Override
    public void initChannel(@NotNull SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // Add SSL handler first to encrypt and decrypt everything.
        // In this example, we use a bogus certificate in the server side
        // and accept any invalid certificates in the client side.
        // You will need something more complicated to identify both
        // and server in the real world.
        if (sslContext() != null) {
            pipeline.addLast(sslContext().newHandler(ch.alloc(), HOST, PORT));
        }

        // On top of the SSL handler, add the text line codec.
        pipeline.addLast(new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));
        pipeline.addLast(new StringDecoder());
        pipeline.addLast(new StringEncoder());

        // and then business logic.
        pipeline.addLast(handler());
    }

    @Override
    protected void channelRead(ChannelHandlerContext ctx, String msg) {
        Message.Packet decode = DataMessage.decode(msg);
        if (decode == null) {
            return;
        }

        Message.Data data = decode.getData();
        if (data != null) {
            instance.newMessageReceived(new DataMessage(data));
        } else {
            // Make sure the server system obtains this
            if (decode.getServers() != null) {
                synchronized (servers) {
                    while (!servers.isEmpty()) {servers.get(servers.size() - 1);}
                    servers.addAll(decode.getServers().getServerList().stream().map(Message.Servers.Server::getLocation).collect(Collectors.toList()));
                    servers.notifyAll();
                }
            }
            instance.eventBus().post(decode);
        }
    }
}
