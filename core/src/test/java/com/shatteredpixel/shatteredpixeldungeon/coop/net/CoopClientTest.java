/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * Logic Link PD — Copyright (C) 2026 Logic Link PD contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.coop.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

//CoopClient against a real websocket server on localhost (Java-WebSocket's own server)
class CoopClientTest {

	private static final long WAIT_S = 10;

	private EchoServer server;
	private CoopClient client;

	@AfterEach
	void tearDown() throws Exception {
		if (client != null) client.stop();
		if (server != null) server.stop( 1000 );
	}

	@Test
	void framesSentBeforeOpenArriveInOrder() throws Exception {
		server = startServer( 0 );
		RecordingListener events = new RecordingListener();
		//a client that can't reach anything yet: everything sent now must be queued
		client = newClient( server.port, events, 50 );
		for (int i = 0; i < 5; i++) {
			assertTrue( client.send( "early-" + i ) );
		}
		assertEquals( 5, client.queued() );
		client.start();

		WebSocket conn = server.connections.poll( WAIT_S, TimeUnit.SECONDS );
		assertNotNull( conn, "client never connected" );
		for (int i = 0; i < 5; i++) {
			assertEquals( "early-" + i, server.received.poll( WAIT_S, TimeUnit.SECONDS ) );
		}
		assertTrue( client.send( "late" ) );
		assertEquals( "late", server.received.poll( WAIT_S, TimeUnit.SECONDS ) );
		assertEquals( 0, client.queued() );
	}

	@Test
	void callbacksRunOnlyOnTheMainThread() throws Exception {
		server = startServer( 0 );
		RecordingListener events = new RecordingListener();
		client = newClient( server.port, events, 50 );
		client.start();

		WebSocket conn = server.connections.poll( WAIT_S, TimeUnit.SECONDS );
		assertNotNull( conn );
		conn.send( "hello-from-server" );

		assertEquals( "connected", events.next() );
		assertEquals( "message:hello-from-server", events.next() );
		assertEquals( Collections.singletonList( events.mainThread.thread.getName() ),
				events.distinctThreadNames() );
	}

	@Test
	void reconnectsAfterServerDropAndFlushesQueue() throws Exception {
		server = startServer( 0 );
		RecordingListener events = new RecordingListener();
		client = newClient( server.port, events, 50 );
		client.start();

		WebSocket first = server.connections.poll( WAIT_S, TimeUnit.SECONDS );
		assertNotNull( first );
		assertEquals( "connected", events.next() );

		first.close( 1011, "server restart" );
		assertEquals( "disconnected:1011:true", events.next() );
		//sent while reconnecting: must be queued and delivered on the new connection
		assertTrue( client.send( "while-down" ) );

		WebSocket second = server.connections.poll( WAIT_S, TimeUnit.SECONDS );
		assertNotNull( second, "client never reconnected" );
		assertEquals( "connected", events.next() );
		assertEquals( "while-down", server.received.poll( WAIT_S, TimeUnit.SECONDS ) );
	}

	@Test
	void stopNeverReconnects() throws Exception {
		server = startServer( 0 );
		RecordingListener events = new RecordingListener();
		client = newClient( server.port, events, 50 );
		client.start();

		assertNotNull( server.connections.poll( WAIT_S, TimeUnit.SECONDS ) );
		assertEquals( "connected", events.next() );

		client.stop();
		assertEquals( CoopClient.State.STOPPED, client.state() );
		assertFalse( client.send( "after-stop" ) );
		//well past several backoff periods (50ms base)
		assertNull( server.connections.poll( 1, TimeUnit.SECONDS ), "client reconnected after stop()" );
	}

	@Test
	void fullQueueRejectsInsteadOfDropping() {
		RecordingListener events = new RecordingListener();
		//never started: nothing can be sent, everything queues
		client = newClient( 1, events, 50 );
		for (int i = 0; i < CoopClient.MAX_QUEUED; i++) {
			assertTrue( client.send( "m" + i ) );
		}
		assertFalse( client.send( "one-too-many" ) );
		assertEquals( CoopClient.MAX_QUEUED, client.queued() );
	}

	@Test
	void retriesUntilTheServerAppears() throws Exception {
		//reserve a free port, then start the client before anything listens on it
		EchoServer probe = startServer( 0 );
		int port = probe.port;
		probe.stop( 1000 );

		RecordingListener events = new RecordingListener();
		client = newClient( port, events, 50 );
		assertTrue( client.send( "queued-while-no-server" ) );
		client.start();
		assertTrue( events.next().startsWith( "disconnected:" ), "first attempt should fail" );

		server = startServer( port );
		assertNotNull( server.connections.poll( WAIT_S, TimeUnit.SECONDS ), "client never connected" );
		assertEquals( "queued-while-no-server", server.received.poll( WAIT_S, TimeUnit.SECONDS ) );
	}

	private CoopClient newClient( int port, RecordingListener events, long baseMs ) {
		return new CoopClient( URI.create( "ws://127.0.0.1:" + port + "/ws" ),
				new CoopTransport.Factory() {
					@Override
					public CoopTransport create() {
						return new JavaWebSocketTransport();
					}
				},
				events.mainThread, new Backoff( baseMs, baseMs * 4, new Random( 1 ) ), events );
	}

	private static EchoServer startServer( int port ) throws InterruptedException {
		EchoServer s = new EchoServer( port );
		s.setReuseAddr( true );
		s.start();
		assertTrue( s.started.await( WAIT_S, TimeUnit.SECONDS ), "test server did not start" );
		s.port = s.getPort();
		return s;
	}

	private static class EchoServer extends WebSocketServer {
		final CountDownLatch started = new CountDownLatch( 1 );
		final BlockingQueue<WebSocket> connections = new LinkedBlockingQueue<>();
		final BlockingQueue<String> received = new LinkedBlockingQueue<>();
		volatile int port;

		EchoServer( int port ) {
			super( new InetSocketAddress( "127.0.0.1", port ) );
		}

		@Override
		public void onStart() {
			started.countDown();
		}

		@Override
		public void onOpen( WebSocket conn, ClientHandshake handshake ) {
			connections.add( conn );
		}

		@Override
		public void onMessage( WebSocket conn, String message ) {
			received.add( message );
		}

		@Override
		public void onClose( WebSocket conn, int code, String reason, boolean remote ) {
		}

		@Override
		public void onError( WebSocket conn, Exception ex ) {
		}
	}

	//a single "GL thread" stand-in that runs posted tasks in order
	private static class FakeMainThread implements MainThread {
		final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
		final Thread thread = new Thread( () -> {
			try {
				while (true) tasks.take().run();
			} catch (InterruptedException e) {
				//test over
			}
		}, "fake-gl-thread" );

		FakeMainThread() {
			thread.setDaemon( true );
			thread.start();
		}

		@Override
		public void post( Runnable task ) {
			tasks.add( task );
		}
	}

	private static class RecordingListener implements CoopClient.Listener {
		final FakeMainThread mainThread = new FakeMainThread();
		final BlockingQueue<String> events = new LinkedBlockingQueue<>();
		final List<String> threads = Collections.synchronizedList( new ArrayList<>() );

		@Override
		public void onConnected() {
			record( "connected" );
		}

		@Override
		public void onMessage( String text ) {
			record( "message:" + text );
		}

		@Override
		public void onDisconnected( int code, String reason, boolean willRetry ) {
			record( "disconnected:" + code + ":" + willRetry );
		}

		private void record( String event ) {
			threads.add( Thread.currentThread().getName() );
			events.add( event );
		}

		String next() throws InterruptedException {
			String e = events.poll( WAIT_S, TimeUnit.SECONDS );
			assertNotNull( e, "no listener event within " + WAIT_S + "s" );
			return e;
		}

		List<String> distinctThreadNames() {
			synchronized (threads) {
				List<String> out = new ArrayList<>();
				for (String t : threads) if (!out.contains( t )) out.add( t );
				return out;
			}
		}
	}
}
