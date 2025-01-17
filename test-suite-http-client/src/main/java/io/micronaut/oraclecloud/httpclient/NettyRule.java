package io.micronaut.oraclecloud.httpclient;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDomainSocketChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class NettyRule implements BeforeEachCallback, AfterEachCallback {
    private Queue<ExpectedRequestHandler> handlers;

    boolean handleContinue;
    boolean aggregate;
    boolean timeout;
    Consumer<Channel> channelCustomizer;
    private List<Throwable> errors;
    Channel serverChannel;
    private NioEventLoopGroup group;

    public void handleOneRequest(ExpectedRequestHandler handler) {
        handlers.add(handler);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        NettyTest test = (NettyTest) context.getTestInstance().get();
        test.netty = this;

        Thread testThread = Thread.currentThread();

        channelCustomizer = c -> {};
        handleContinue = false;
        aggregate = true;
        timeout = true;
        handlers = new ArrayDeque<>();

        errors = new CopyOnWriteArrayList<>();

        group = new NioEventLoopGroup(1);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group, group)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        if (timeout) {
                            ch.pipeline().addLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS));
                        }
                        ch.pipeline()
                                .addLast(new LoggingHandler(LogLevel.INFO))
                                .addLast(new HttpServerCodec());
                        if (aggregate) {
                            ch.pipeline().addLast(new HttpObjectAggregator(4096) {
                                @Override
                                protected Object newContinueResponse(HttpMessage start, int maxContentLength, ChannelPipeline pipeline) {
                                    if (!handleContinue) {
                                        return super.newContinueResponse(start, maxContentLength, pipeline);
                                    }

                                    ExpectedRequestHandler handler = handlers.poll();
                                    if (handler == null) {
                                        throw new AssertionError("Unexpected message: " + start);
                                    }
                                    ChannelHandlerContext ctx = pipeline.context(this);
                                    try {
                                        handler.handle(ctx, (HttpRequest) start);
                                    } catch (Exception e) {
                                        try {
                                            exceptionCaught(ctx, e);
                                        } catch (Exception ex) {
                                            throw new RuntimeException(ex);
                                        }
                                    }
                                    return null;
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    if (handleContinue) {
                                        // don't throw an error when we shut down before the request is done
                                        ctx.fireChannelInactive();
                                    } else {
                                        super.channelInactive(ctx);
                                    }
                                }
                            });
                        }
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                        if (msg instanceof HttpContent && !aggregate) {
                                            ((HttpContent) msg).release();
                                            return;
                                        }
                                        if (((HttpMessage) msg).decoderResult().isFailure()) {
                                            ((HttpMessage) msg).decoderResult().cause().printStackTrace();
                                        }
                                        ExpectedRequestHandler handler = handlers.poll();
                                        if (handler == null) {
                                            throw new AssertionError("Unexpected message: " + msg);
                                        }
                                        handler.handle(ctx, (HttpRequest) msg);
                                    }

                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                        if (cause instanceof ReadTimeoutException ||
                                                (cause instanceof IOException && cause.getMessage().equals("Connection reset by peer"))) {
                                            // not fatal
                                            ctx.close();
                                            return;
                                        }

                                        errors.add(cause);
                                        testThread.interrupt();
                                        ctx.close();
                                        if (!(ctx.channel() instanceof NioDomainSocketChannel)) { // https://github.com/netty/netty/pull/14409
                                            ctx.channel().parent().close(); // close the server too
                                        }
                                    }
                                });
                        channelCustomizer.accept(ch);
                    }
                });
        test.setupBootstrap(bootstrap);
        serverChannel = bootstrap.bind().syncUninterruptibly().channel();
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        try {
            serverChannel.close();
            group.shutdownGracefully();
        } catch (Throwable t) {
            errors.add(t);
        }
        if (!errors.isEmpty()) {
            Throwable main = errors.get(0);
            for (int i = 1; i < errors.size(); i++) {
                main.addSuppressed(errors.get(i));
            }
            throw main instanceof Exception e ? e : new Exception(main);
        }
    }
}
