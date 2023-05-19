package io.micronaut.oraclecloud.httpclient.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

class BufferFutureHandlerTest {
    @Test
    public void normal() throws Exception {
        EmbeddedChannel embeddedChannel = new EmbeddedChannel();
        BufferFutureHandler handler = new BufferFutureHandler(embeddedChannel.alloc());
        embeddedChannel.pipeline().addLast(handler.new HandlerImpl());

        Assertions.assertFalse(handler.future.isDone());
        embeddedChannel.writeInbound(new DefaultHttpContent(Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.UTF_8))));
        Assertions.assertFalse(handler.future.isDone());
        embeddedChannel.writeInbound(new DefaultHttpContent(Unpooled.wrappedBuffer("bar".getBytes(StandardCharsets.UTF_8))));
        Assertions.assertFalse(handler.future.isDone());
        embeddedChannel.writeInbound(new DefaultLastHttpContent());
        Assertions.assertTrue(handler.future.isDone());
        Assertions.assertEquals(Unpooled.wrappedBuffer("foobar".getBytes(StandardCharsets.UTF_8)), handler.future.get());
    }

    @Test
    public void exception() throws Exception {
        EmbeddedChannel embeddedChannel = new EmbeddedChannel();
        BufferFutureHandler handler = new BufferFutureHandler(embeddedChannel.alloc());
        embeddedChannel.pipeline().addLast(handler.new HandlerImpl());

        Assertions.assertFalse(handler.future.isDone());
        embeddedChannel.writeInbound(Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertFalse(handler.future.isDone());
        embeddedChannel.pipeline().fireExceptionCaught(new RuntimeException("test"));
        Assertions.assertTrue(handler.future.isDone());
        embeddedChannel.writeInbound(new DefaultLastHttpContent());
        Assertions.assertTrue(handler.future.isDone());

        try {
            handler.future.get();
            Assertions.fail();
        } catch (ExecutionException e) {
            Assertions.assertEquals("test", e.getCause().getMessage());
        }
    }

    @Test
    public void doubleException() throws Exception {
        EmbeddedChannel embeddedChannel = new EmbeddedChannel();
        BufferFutureHandler handler = new BufferFutureHandler(embeddedChannel.alloc());
        embeddedChannel.pipeline().addLast(handler.new HandlerImpl());

        Assertions.assertFalse(handler.future.isDone());
        embeddedChannel.pipeline().fireExceptionCaught(new RuntimeException("foo"));
        Assertions.assertTrue(handler.future.isDone());
        embeddedChannel.pipeline().fireExceptionCaught(new RuntimeException("bar"));

        try {
            handler.future.get();
            Assertions.fail();
        } catch (ExecutionException e) {
            Assertions.assertEquals("foo", e.getCause().getMessage());
        }

        try {
            embeddedChannel.checkException();
            Assertions.fail();
        } catch (RuntimeException e) {
            Assertions.assertEquals("bar", e.getMessage());
        }
    }

    @Test
    public void cancel() throws Exception {
        EmbeddedChannel embeddedChannel = new EmbeddedChannel();
        BufferFutureHandler handler = new BufferFutureHandler(embeddedChannel.alloc());
        DecidedBodyHandler.HandlerImpl handlerImpl = handler.new HandlerImpl();
        embeddedChannel.pipeline().addLast(handlerImpl);

        Assertions.assertFalse(handler.future.isDone());
        embeddedChannel.writeInbound(Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertFalse(handler.future.isDone());
        embeddedChannel.pipeline().remove(handlerImpl);
        Assertions.assertTrue(handler.future.isDone());

        try {
            handler.future.get();
            Assertions.fail();
        } catch (ExecutionException e) {
            // should happen
            Assertions.assertTrue(e.getCause() instanceof EOFException);
        }
    }
}