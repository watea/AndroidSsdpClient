/*
 * Copyright (c) 2024. Stephane Treuchot
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to
 * do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package com.watea.androidssdpclient;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class SsdpClient {
  private static final String ALL_DEVICES = "ssdp:all";
  private static final String LOG_TAG = SsdpClient.class.getSimpleName();
  private static final String MULTICAST_ADDRESS = "239.255.255.250";
  private static final String WLAN = "wlan0";
  private static final int SSDP_PORT = 1900;
  private static final int SEARCH_DELAY = 2000; // ms
  private static final int SEARCH_REPEAT = 3;
  private static final int MX = 3; // s
  private static final int SEARCH_TTL = 2; // UPnP spec
  private static final int MARGIN = 100; // ms
  private static final int EXPIRY_CHECK_PERIOD = 30; // s — period between expiry sweeps
  private static final String S_CRLF = "\r\n";
  private static final String SEARCH_MESSAGE =
    "M-SEARCH * HTTP/1.1" + S_CRLF +
      "HOST: " + MULTICAST_ADDRESS + ":" + SSDP_PORT + S_CRLF +
      "MAN: \"ssdp:discover\"" + S_CRLF +
      "MX: " + MX + S_CRLF +
      "ST: ";

  @NonNull
  private final String device;
  @NonNull
  private final Listener listener;
  // Cache
  private final Set<SsdpService> ssdpServices = Collections.synchronizedSet(new HashSet<>());
  // volatile ensures visibility of isRunning across threads
  private volatile boolean isRunning;
  @Nullable
  private MulticastSocket searchSocket = null; // Multicast socket used here only for TTL, otherwise DatagramSocket could be enough
  @Nullable
  private MulticastSocket listenSocket = null;
  @Nullable
  private NetworkInterface networkInterface = null;
  @Nullable
  private ScheduledExecutorService expiryScheduler = null;

  public SsdpClient(@Nullable String device, @NonNull Listener listener) {
    this.device = (device == null) ? ALL_DEVICES : device;
    this.listener = listener;
  }

  public SsdpClient(@NonNull Listener listener) {
    this(null, listener);
  }

  // Network shall be available (implementation dependant)
  public void start() {
    Log.d(LOG_TAG, "start");
    try {
      // Search socket and timeout
      searchSocket = new MulticastSocket();
      searchSocket.setSoTimeout(MX * 1000 + SEARCH_DELAY * SEARCH_REPEAT + MARGIN);
      searchSocket.setTimeToLive(SEARCH_TTL);
      // Late binding in case port is already used
      listenSocket = new MulticastSocket(null);
      listenSocket.setReuseAddress(true);
      listenSocket.bind(new InetSocketAddress(SSDP_PORT));
      // Join the multicast group on the specified network interface (wlan0 for Wi-Fi)
      networkInterface = NetworkInterface.getByName(WLAN);
      listenSocket.joinGroup(new InetSocketAddress(MULTICAST_ADDRESS, SSDP_PORT), networkInterface);
      // Receive on both ports unicast and multicast responses
      isRunning = true;
      ssdpServices.clear();
      new Thread(() -> receive(searchSocket)).start();
      new Thread(() -> receive(listenSocket)).start();
      // Periodic sweep to detect silently disappeared services
      expiryScheduler = Executors.newScheduledThreadPool(1);
      expiryScheduler.scheduleWithFixedDelay(this::checkExpiredServices, EXPIRY_CHECK_PERIOD, EXPIRY_CHECK_PERIOD, TimeUnit.SECONDS);
      // Now we can search
      // noinspection resource
      final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
      for (int i = 0; i < SEARCH_REPEAT; i++) {
        scheduler.schedule(this::search, (long) i * SEARCH_DELAY, TimeUnit.MILLISECONDS);
      }
      scheduler.shutdown();
    } catch (Exception exception) {
      Log.e(LOG_TAG, "start: failed!", exception);
      // Close sockets only — onFatalError is the relevant callback here, not onStop
      closeSockets();
      listener.onFatalError();
    }
  }

  public void stop() {
    Log.d(LOG_TAG, "stop");
    closeSockets();
    listener.onStop();
  }

  public void search() {
    Log.d(LOG_TAG, "search");
    if ((searchSocket != null) && !searchSocket.isClosed()) {
      try {
        // Explicit UTF-8 charset to avoid platform-default encoding
        final byte[] sendData = (SEARCH_MESSAGE + device + S_CRLF + S_CRLF).getBytes(UTF_8);
        final DatagramPacket packet = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(MULTICAST_ADDRESS), SSDP_PORT);
        searchSocket.send(packet);
      } catch (IOException iOException) {
        Log.e(LOG_TAG, "SSDP search failed!", iOException);
      }
    }
  }

  public boolean isStarted() {
    return isRunning;
  }

  // Remove services that have not renewed their presence within their max-age window,
  // and notify the listener for each one with a synthetic EXPIRED announcement
  private void checkExpiredServices() {
    Log.d(LOG_TAG, "checkExpiredServices");
    final Set<SsdpService> expired;
    synchronized (ssdpServices) {
      expired = ssdpServices.stream()
        .filter(SsdpService::isExpired)
        .collect(Collectors.toCollection(HashSet::new));
      ssdpServices.removeAll(expired);
    }
    expired.forEach(service -> listener.onServiceAnnouncement(service.asExpired()));
  }

  private void receive(@NonNull DatagramSocket datagramSocket) {
    Log.d(LOG_TAG, "receive");
    final byte[] receiveData = new byte[1024];
    final DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
    while (isRunning) {
      try {
        datagramSocket.receive(receivePacket);
        final SsdpService ssdpService = new SsdpService(SsdpResponse.from(receivePacket));
        if (ssdpService.getType() == SsdpResponse.Type.DISCOVERY_RESPONSE) {
          // Handle cache
          final Optional<SsdpService> existing;
          synchronized (ssdpServices) {
            existing = ssdpServices.stream().filter(ssdpService::equals).findFirst();
          }
          if (existing.isEmpty()) {
            ssdpServices.add(ssdpService);
            listener.onServiceDiscovered(ssdpService);
          } else {
            final boolean wasExpired = existing.get().isExpired();
            // Always refresh expiry on re-discovery
            ssdpServices.remove(ssdpService);
            ssdpServices.add(ssdpService);
            if (wasExpired) {
              listener.onServiceDiscovered(ssdpService);
            }
          }
        } else {
          // Sync cache on ssdp:byebye / ssdp:alive announcements
          final SsdpService.Status status = ssdpService.getStatus();
          if (status == SsdpService.Status.ALIVE) {
            if (ssdpServices.contains(ssdpService)) {
              ssdpServices.remove(ssdpService);
              ssdpServices.add(ssdpService);
              listener.onServiceAnnouncement(ssdpService);
            } else {
              ssdpServices.add(ssdpService);
              listener.onServiceDiscovered(ssdpService);
            }
          } else {
            if (status == SsdpService.Status.BYEBYE) {
              ssdpServices.remove(ssdpService);
            }
            listener.onServiceAnnouncement(ssdpService);
          }
        }
      } catch (IllegalArgumentException illegalArgumentException) {
        Log.d(LOG_TAG, "receive: unable to parse response");
      } catch (IOException iOException) {
        if (iOException instanceof SocketTimeoutException) {
          Log.d(LOG_TAG, "receive: timeout");
        } else if (isRunning) {
          Log.e(LOG_TAG, "receive: failed!");
        }
      }
    }
    Log.d(LOG_TAG, "receive: exit");
  }

  // Close sockets and stop expiry scheduler without triggering any listener callback
  private void closeSockets() {
    isRunning = false;
    if (expiryScheduler != null) {
      expiryScheduler.shutdownNow();
      expiryScheduler = null;
    }
    if (searchSocket != null) {
      searchSocket.close();
    }
    if (listenSocket != null) {
      if (networkInterface != null) {
        try {
          listenSocket.leaveGroup(new InetSocketAddress(MULTICAST_ADDRESS, SSDP_PORT), networkInterface);
        } catch (IOException iOException) {
          Log.e(LOG_TAG, "closeSockets: unable to leave group!", iOException);
        }
      }
      listenSocket.close();
    }
  }

  public interface Listener {
    void onServiceDiscovered(@NonNull SsdpService service);

    void onServiceAnnouncement(@NonNull SsdpService service);

    void onFatalError();

    void onStop();
  }
}