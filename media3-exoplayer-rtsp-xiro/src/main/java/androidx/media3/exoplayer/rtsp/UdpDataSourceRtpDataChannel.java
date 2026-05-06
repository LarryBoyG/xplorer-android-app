/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.rtsp;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkState;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.UdpDataSource;
import com.google.common.primitives.Ints;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

/** An {@link RtpDataChannel} for UDP transport. */
/* package */ final class UdpDataSourceRtpDataChannel implements RtpDataChannel {

  private static final String TAG = "UdpRtpDataChannel";
  private static final String DEFAULT_UDP_TRANSPORT_FORMAT = "RTP/AVP;unicast;client_port=%d-%d";
  private static final int XIRO_MAX_UDP_PACKET_SIZE_BYTES = 65_507;
  private static final int XIRO_TARGET_SOCKET_RECEIVE_BUFFER_SIZE_BYTES = 1_048_576;
  private static final byte[] XIRO_LEGACY_UDP_PUNCH_PACKET =
      new byte[] {(byte) 0xCE, (byte) 0xFA, (byte) 0xED, (byte) 0xFE};
  private static final long XIRO_LEGACY_UDP_PUNCH_GAP_MS = 95L;

  private final UdpDataSource dataSource;

  /** The associated RTCP channel; {@code null} if the current channel is an RTCP channel. */
  @Nullable private UdpDataSourceRtpDataChannel rtcpChannel;
  private volatile boolean closed;

  /**
   * Creates a new instance.
   *
   * @param socketTimeoutMs The timeout for {@link #read} in milliseconds.
   */
  public UdpDataSourceRtpDataChannel(long socketTimeoutMs) {
    dataSource =
        new UdpDataSource(XIRO_MAX_UDP_PACKET_SIZE_BYTES, Ints.checkedCast(socketTimeoutMs));
    closed = false;
  }

  @Override
  public String getTransport() {
    int dataPortNumber = getLocalPort();
    checkState(dataPortNumber != C.INDEX_UNSET); // Assert open() is called.
    return Util.formatInvariant(DEFAULT_UDP_TRANSPORT_FORMAT, dataPortNumber, dataPortNumber + 1);
  }

  @Override
  public int getLocalPort() {
    int port = dataSource.getLocalPort();
    return port == UdpDataSource.UDP_PORT_UNSET ? C.INDEX_UNSET : port;
  }

  @Override
  public boolean needsClosingOnLoadCompletion() {
    return true;
  }

  @Nullable
  @Override
  public RtspMessageChannel.InterleavedBinaryDataListener getInterleavedBinaryDataListener() {
    return null;
  }

  @Override
  public void addTransferListener(TransferListener transferListener) {
    dataSource.addTransferListener(transferListener);
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    closed = false;
    long openedLength = dataSource.open(dataSpec);
    configureSocketReceiveBuffer();
    return openedLength;
  }

  @Nullable
  @Override
  public Uri getUri() {
    return dataSource.getUri();
  }

  @Override
  public void close() {
    closed = true;
    dataSource.close();

    if (rtcpChannel != null) {
      rtcpChannel.close();
    }
  }

  @Override
  public void primeTransportPath(String host, int port) {
    if (closed || host == null || host.isEmpty() || port <= 0) {
      return;
    }

    DatagramSocket socket = getOpenSocket();
    if (socket == null || socket.isClosed()) {
      return;
    }

    try {
      InetAddress address = InetAddress.getByName(host);
      sendPunchPacket(socket, address, port);
      Thread followUpPunch =
          new Thread(
              () -> {
                try {
                  Thread.sleep(XIRO_LEGACY_UDP_PUNCH_GAP_MS);
                  if (!closed && !socket.isClosed()) {
                    sendPunchPacket(socket, address, port);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } catch (IOException e) {
                  Log.w(TAG, "Legacy UDP punch retry failed", e);
                }
              },
              "xiro-udp-punch");
      followUpPunch.setDaemon(true);
      followUpPunch.start();
    } catch (IOException e) {
      Log.w(TAG, "Legacy UDP punch failed", e);
    }
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    while (!closed) {
      try {
        int bytesRead = dataSource.read(buffer, offset, length);
        if (bytesRead > 0 && rtcpChannel != null) {
          RtspDebugStats.recordUdpRtpPacket(bytesRead);
        }
        return bytesRead;
      } catch (UdpDataSource.UdpDataSourceException e) {
        if (closed) {
          return C.RESULT_END_OF_INPUT;
        }
        if (e.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
          return 0;
        } else {
          throw e;
        }
      }
    }
    return C.RESULT_END_OF_INPUT;
  }

  public void setRtcpChannel(UdpDataSourceRtpDataChannel rtcpChannel) {
    checkArgument(this != rtcpChannel);
    this.rtcpChannel = rtcpChannel;
  }

  @Nullable
  private DatagramSocket getOpenSocket() {
    try {
      Field socketField = UdpDataSource.class.getDeclaredField("socket");
      socketField.setAccessible(true);
      return (DatagramSocket) socketField.get(dataSource);
    } catch (ReflectiveOperationException e) {
      Log.w(TAG, "Unable to access UDP socket for legacy punch", e);
      return null;
    }
  }

  private void configureSocketReceiveBuffer() {
    DatagramSocket socket = getOpenSocket();
    if (socket == null || socket.isClosed()) {
      return;
    }

    try {
      int previousReceiveBufferSize = socket.getReceiveBufferSize();
      if (previousReceiveBufferSize < XIRO_TARGET_SOCKET_RECEIVE_BUFFER_SIZE_BYTES) {
        socket.setReceiveBufferSize(XIRO_TARGET_SOCKET_RECEIVE_BUFFER_SIZE_BYTES);
      }
      int configuredReceiveBufferSize = socket.getReceiveBufferSize();
      Log.d(
          TAG,
          Util.formatInvariant(
              "UDP socket receive buffer configured: %d -> %d bytes",
              previousReceiveBufferSize,
              configuredReceiveBufferSize));
    } catch (SocketException e) {
      Log.w(TAG, "Unable to increase UDP socket receive buffer", e);
    }
  }

  private void sendPunchPacket(DatagramSocket socket, InetAddress address, int port)
      throws IOException {
    DatagramPacket packet =
        new DatagramPacket(
            XIRO_LEGACY_UDP_PUNCH_PACKET,
            XIRO_LEGACY_UDP_PUNCH_PACKET.length,
            address,
            port);
    socket.send(packet);
  }
}
