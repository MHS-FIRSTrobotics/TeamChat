package org.teamclutch.chat;

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLException;

abstract class AbstractClient extends AbstractExecutionThreadService {
    private final ClientHandler clientHandler;
    final String HOST;
    final int PORT;

    @Nullable
    private SslContext sslContext;
    private final EventLoopGroup group = new NioEventLoopGroup();

    AbstractClient(ClientConfig config, String host) {
        HOST = host;
        clientHandler = new ClientHandler();
        try {
            sslContext = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        } catch (SSLException e) {
            System.err.println("Configuring the SSL context failed. " + e.getLocalizedMessage());
            sslContext = null;
        }
        PORT = config.port();
    }

    @NotNull
    EventLoopGroup eventLoopGroup() {
        return group;
    }

    @Nullable SslContext sslContext() {
        return sslContext;
    }

    @NotNull Bootstrap bootstrap() {
        Bootstrap b = new Bootstrap();
        b.group(eventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(new ClientInitializer());
        return b;
    }

    public abstract void initChannel(SocketChannel socketChannel);

    @Contract(value = "_, _ -> false", pure = true)
    private boolean exceptionCaught(ChannelHandlerContext ctx, Throwable throwable) {
        return false;
    }

    protected abstract void channelRead(ChannelHandlerContext ctx, String msg);

    private final class ClientInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        protected void initChannel(SocketChannel ch) throws Exception {
            AbstractClient.this.initChannel(ch);
        }
    }

    @Contract(pure = true)
    final SimpleChannelInboundHandler<String> handler() {
        return clientHandler;
    }

    private final class ClientHandler extends SimpleChannelInboundHandler<String> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
            AbstractClient.this.channelRead(ctx, msg);
        }

        @Override
        public void exceptionCaught(@NotNull ChannelHandlerContext ctx, @NotNull Throwable throwable) {
            try {
                if (!AbstractClient.this.exceptionCaught(ctx, throwable)) {
                    if (Chat.DEBUG_VERSION) {
                        System.err.println("The client encountered a error! Description: " + throwable.getLocalizedMessage());
                        Throwable rootCause = Throwables.getRootCause(throwable);
                        System.err.println(rootCause.getLocalizedMessage() + "\n"
                                + Throwables.getStackTraceAsString(rootCause));
                    }
                }
            } catch (Exception ex) {
                System.err.println("The client encountered a fatal error while handling a \"" + throwable.getClass().getSimpleName() + "\"!");
                ctx.close();
                ex.addSuppressed(throwable);
                if (Chat.DEBUG_VERSION) {
                    ex.printStackTrace();
                }
            }
        }
    }
}
