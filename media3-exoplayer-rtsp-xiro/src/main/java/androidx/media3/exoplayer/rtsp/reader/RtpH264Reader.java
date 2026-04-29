/*
 * Copyright 2020 The Android Open Source Project
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
package androidx.media3.exoplayer.rtsp.reader;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.castNonNull;
import static androidx.media3.exoplayer.rtsp.reader.RtpReaderUtils.toSampleTimeUs;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.exoplayer.rtsp.RtpPacket;
import androidx.media3.exoplayer.rtsp.RtpPayloadFormat;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** Parses an H264 byte stream carried on RTP packets, and extracts H264 Access Units. */
/* package */ final class RtpH264Reader implements RtpPayloadReader {
  private static final String TAG = "RtpH264Reader";

  private static final int MEDIA_CLOCK_FREQUENCY = 90_000;

  /** Offset of payload data within a FU type A payload. */
  private static final int FU_PAYLOAD_OFFSET = 2;

  /** Single Time Aggregation Packet type A. */
  private static final int RTP_PACKET_TYPE_STAP_A = 24;

  /** Fragmentation Unit type A. */
  private static final int RTP_PACKET_TYPE_FU_A = 28;

  /** IDR NAL unit type. */
  private static final int NAL_UNIT_TYPE_IDR = 5;

  /** SPS NAL unit type. */
  private static final int NAL_UNIT_TYPE_SPS = 7;

  /** PPS NAL unit type. */
  private static final int NAL_UNIT_TYPE_PPS = 8;

  private final ByteArrayOutputStream accessUnitBuffer;

  private final RtpPayloadFormat payloadFormat;
  private final List<byte[]> initializationData;

  private @MonotonicNonNull TrackOutput trackOutput;
  private @C.BufferFlags int bufferFlags;

  private long firstReceivedTimestamp;
  private int previousSequenceNumber;

  private long startTimeOffsetUs;
  private boolean waitingForKeyFrame;
  private boolean droppingAccessUnit;
  private boolean prependInitializationDataToKeyFrame;
  private boolean previousPacketWasMarker;

  /** Creates an instance. */
  public RtpH264Reader(RtpPayloadFormat payloadFormat) {
    this.payloadFormat = payloadFormat;
    initializationData = payloadFormat.format.initializationData;
    accessUnitBuffer = new ByteArrayOutputStream();
    firstReceivedTimestamp = C.TIME_UNSET;
    previousSequenceNumber = C.INDEX_UNSET;
    waitingForKeyFrame = true;
    prependInitializationDataToKeyFrame = !initializationData.isEmpty();
    previousPacketWasMarker = true;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, int trackId) {
    trackOutput = extractorOutput.track(trackId, C.TRACK_TYPE_VIDEO);

    castNonNull(trackOutput).format(payloadFormat.format);
  }

  @Override
  public void onReceivingFirstPacket(long timestamp, int sequenceNumber) {}

  @Override
  public void consume(ParsableByteArray data, long timestamp, int sequenceNumber, boolean rtpMarker)
      throws ParserException {

    int rtpH264PacketMode;
    try {
      // RFC6184 Section 5.6, 5.7 and 5.8.
      rtpH264PacketMode = data.getData()[0] & 0x1F;
    } catch (IndexOutOfBoundsException e) {
      throw ParserException.createForMalformedManifest(/* message= */ null, e);
    }

    checkStateNotNull(trackOutput);
    if (previousSequenceNumber != C.INDEX_UNSET) {
      int expectedSequenceNumber = RtpPacket.getNextSequenceNumber(previousSequenceNumber);
      if (sequenceNumber != expectedSequenceNumber) {
        boolean packetLossInsideAccessUnit = !previousPacketWasMarker || accessUnitBuffer.size() > 0;
        Log.w(
            TAG,
            Util.formatInvariant(
                "Detected RTP packet loss before H264 sample assembly. Expected: %d; received: %d."
                    + " Waiting for the next key frame%s.",
                expectedSequenceNumber,
                sequenceNumber,
                packetLossInsideAccessUnit ? " and dropping the in-flight access unit" : ""));
        waitingForKeyFrame = true;
        prependInitializationDataToKeyFrame = true;
        if (packetLossInsideAccessUnit) {
          accessUnitBuffer.reset();
          bufferFlags = 0;
          droppingAccessUnit = true;
        }
      }
    }
    if (rtpH264PacketMode > 0 && rtpH264PacketMode < 24) {
      processSingleNalUnitPacket(data);
    } else if (rtpH264PacketMode == RTP_PACKET_TYPE_STAP_A) {
      processSingleTimeAggregationPacket(data);
    } else if (rtpH264PacketMode == RTP_PACKET_TYPE_FU_A) {
      processFragmentationUnitPacket(data, sequenceNumber);
    } else {
      throw ParserException.createForMalformedManifest(
          String.format("RTP H264 packetization mode [%d] not supported.", rtpH264PacketMode),
          /* cause= */ null);
    }

    if (rtpMarker) {
      if (firstReceivedTimestamp == C.TIME_UNSET) {
        firstReceivedTimestamp = timestamp;
      }

      if (!droppingAccessUnit && accessUnitBuffer.size() > 0) {
        long timeUs =
            toSampleTimeUs(
                startTimeOffsetUs, timestamp, firstReceivedTimestamp, MEDIA_CLOCK_FREQUENCY);
        byte[] accessUnit = accessUnitBuffer.toByteArray();
        trackOutput.sampleData(new ParsableByteArray(accessUnit), accessUnit.length);
        trackOutput.sampleMetadata(
            timeUs, bufferFlags, accessUnit.length, /* offset= */ 0, /* cryptoData= */ null);
        if ((bufferFlags & C.BUFFER_FLAG_KEY_FRAME) != 0) {
          waitingForKeyFrame = false;
          prependInitializationDataToKeyFrame = false;
        }
      }
      resetAccessUnit();
    }

    previousSequenceNumber = sequenceNumber;
    previousPacketWasMarker = rtpMarker;
  }

  @Override
  public void seek(long nextRtpTimestamp, long timeUs) {
    firstReceivedTimestamp = nextRtpTimestamp;
    resetAccessUnit();
    startTimeOffsetUs = timeUs;
    waitingForKeyFrame = true;
    prependInitializationDataToKeyFrame = !initializationData.isEmpty();
    previousSequenceNumber = C.INDEX_UNSET;
    previousPacketWasMarker = true;
  }

  // Internal methods.

  /**
   * Processes Single NAL Unit packet (RFC6184 Section 5.6).
   *
   * <p>Outputs the single NAL Unit (with start code prepended) to {@link #trackOutput}. Sets {@link
   * #bufferFlags} and {@link #fragmentedSampleSizeBytes} accordingly.
   */
  @RequiresNonNull("trackOutput")
  private void processSingleNalUnitPacket(ParsableByteArray data) {
    // Example of a Single Nal Unit packet
    //    0                   1                   2                   3
    //    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |F|NRI|  Type   |                                               |
    //    +-+-+-+-+-+-+-+-+                                               |
    //    |                                                               |
    //    |               Bytes 2..n of a single NAL unit                 |
    //    |                                                               |
    //    |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                               :...OPTIONAL RTP padding        |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

    int nalUnitOffset = data.getPosition();
    int nalHeaderType = data.getData()[nalUnitOffset] & 0x1F;
    if (waitingForKeyFrame && nalHeaderType != NAL_UNIT_TYPE_IDR) {
      return;
    }

    maybeAppendInitializationDataBeforeKeyFrame(nalHeaderType);
    appendStartCode();
    appendBytes(data.getData(), nalUnitOffset, data.bytesLeft());
    bufferFlags = getBufferFlagsFromNalType(nalHeaderType);
  }

  /**
   * Processes STAP Type A packet (RFC6184 Section 5.7).
   *
   * <p>Outputs the received aggregation units (with start code prepended) to {@link #trackOutput}.
   * Sets {@link #bufferFlags} and {@link #fragmentedSampleSizeBytes} accordingly.
   */
  @RequiresNonNull("trackOutput")
  private void processSingleTimeAggregationPacket(ParsableByteArray data) {
    //  Example of an STAP-A packet.
    //      0                   1                   2                   3
    //     0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                          RTP Header                           |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |STAP-A NAL HDR |         NALU 1 Size           | NALU 1 HDR    |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                         NALU 1 Data                           |
    //    :                                                               :
    //    +               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |               | NALU 2 Size                   | NALU 2 HDR    |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                         NALU 2 Data                           |
    //    :                                                               :
    //    |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                               :...OPTIONAL RTP padding        |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

    byte[] packetData = data.getData();
    int offset = data.getPosition() + 1;
    int limit = data.limit();
    boolean containsIdr = false;
    boolean containsParameterSets = false;

    int scanOffset = offset;
    while (scanOffset + 2 <= limit) {
      int nalUnitLength = ((packetData[scanOffset] & 0xFF) << 8) | (packetData[scanOffset + 1] & 0xFF);
      scanOffset += 2;
      if (nalUnitLength <= 0 || scanOffset + nalUnitLength > limit) {
        break;
      }
      int nalType = packetData[scanOffset] & 0x1F;
      containsIdr |= nalType == NAL_UNIT_TYPE_IDR;
      containsParameterSets |= nalType == NAL_UNIT_TYPE_SPS || nalType == NAL_UNIT_TYPE_PPS;
      scanOffset += nalUnitLength;
    }

    if (waitingForKeyFrame && !containsIdr) {
      return;
    }

    if (containsIdr && prependInitializationDataToKeyFrame && !containsParameterSets) {
      maybeAppendInitializationDataBeforeKeyFrame(NAL_UNIT_TYPE_IDR);
    }

    while (offset + 2 <= limit) {
      int nalUnitLength = ((packetData[offset] & 0xFF) << 8) | (packetData[offset + 1] & 0xFF);
      offset += 2;
      if (nalUnitLength <= 0 || offset + nalUnitLength > limit) {
        break;
      }
      int nalType = packetData[offset] & 0x1F;
      if (waitingForKeyFrame
          && nalType != NAL_UNIT_TYPE_IDR
          && nalType != NAL_UNIT_TYPE_SPS
          && nalType != NAL_UNIT_TYPE_PPS) {
        offset += nalUnitLength;
        continue;
      }
      appendStartCode();
      appendBytes(packetData, offset, nalUnitLength);
      offset += nalUnitLength;
    }

    bufferFlags = containsIdr ? C.BUFFER_FLAG_KEY_FRAME : 0;
  }

  /**
   * Processes Fragmentation Unit Type A packet (RFC6184 Section 5.8).
   *
   * <p>This method will be invoked multiple times to receive a single frame that is broken down
   * into a series of fragmentation units in multiple RTP packets.
   *
   * <p>Outputs the received fragmentation units (with start code prepended) to {@link
   * #trackOutput}. Sets {@link #bufferFlags} and {@link #fragmentedSampleSizeBytes} accordingly.
   */
  @RequiresNonNull("trackOutput")
  private void processFragmentationUnitPacket(ParsableByteArray data, int packetSequenceNumber) {
    //  FU-A mode packet layout.
    //   0                   1                   2                   3
    //   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //  | FU indicator  |   FU header   |                               |
    //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               |
    //  |                                                               |
    //  |                         FU payload                            |
    //  |                                                               |
    //  |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //  |                               :...OPTIONAL RTP padding        |
    //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //
    //     FU Indicator     FU Header
    //   0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7
    //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //  |F|NRI|  Type   |S|E|R|  Type   |
    //  +---------------+---------------+
    //  Indicator: Upper 3 bits are the same as NALU header, Type = 28 (FU-A type).
    //  Header: Start/End/Reserved/Type. Type is same as NALU type.
    int fuIndicator = data.getData()[0];
    int fuHeader = data.getData()[1];
    int nalHeader = (fuIndicator & 0xE0) | (fuHeader & 0x1F);
    int nalType = nalHeader & 0x1F;
    boolean isFirstFuPacket = (fuHeader & 0x80) > 0;
    boolean isLastFuPacket = (fuHeader & 0x40) > 0;

    if (isFirstFuPacket) {
      if (waitingForKeyFrame && nalType != NAL_UNIT_TYPE_IDR) {
        droppingAccessUnit = true;
        return;
      }
      maybeAppendInitializationDataBeforeKeyFrame(nalType);
      appendStartCode();
      data.getData()[1] = (byte) nalHeader;
      appendBytes(data.getData(), /* offset= */ 1, data.limit() - 1);
    } else if (!droppingAccessUnit) {
      int expectedSequenceNumber = RtpPacket.getNextSequenceNumber(previousSequenceNumber);
      if (packetSequenceNumber != expectedSequenceNumber) {
        Log.w(
            TAG,
            Util.formatInvariant(
                "Received RTP packet with unexpected sequence number. Expected: %d; received: %d."
                    + " Dropping the rest of the access unit and waiting for the next key frame.",
                expectedSequenceNumber, packetSequenceNumber));
        droppingAccessUnit = true;
        waitingForKeyFrame = true;
        prependInitializationDataToKeyFrame = true;
        return;
      }
      appendBytes(data.getData(), FU_PAYLOAD_OFFSET, data.limit() - FU_PAYLOAD_OFFSET);
    }

    if (isLastFuPacket) {
      bufferFlags = getBufferFlagsFromNalType(nalType);
    }
  }

  private void maybeAppendInitializationDataBeforeKeyFrame(int nalType) {
    if (nalType != NAL_UNIT_TYPE_IDR || !prependInitializationDataToKeyFrame) {
      return;
    }
    for (byte[] nalUnit : initializationData) {
      appendInitializationNalUnit(nalUnit);
    }
  }

  private void appendInitializationNalUnit(byte[] nalUnit) {
    if (nalUnit.length == 0) {
      return;
    }
    if (hasNalStartCode(nalUnit)) {
      appendBytes(nalUnit, /* offset= */ 0, nalUnit.length);
    } else {
      appendStartCode();
      appendBytes(nalUnit, /* offset= */ 0, nalUnit.length);
    }
  }

  private static boolean hasNalStartCode(byte[] data) {
    return (data.length >= 4
            && data[0] == 0
            && data[1] == 0
            && data[2] == 0
            && data[3] == 1)
        || (data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1);
  }

  private void appendStartCode() {
    appendBytes(NalUnitUtil.NAL_START_CODE, /* offset= */ 0, NalUnitUtil.NAL_START_CODE.length);
  }

  private void appendBytes(byte[] data, int offset, int length) {
    if (droppingAccessUnit || length <= 0) {
      return;
    }
    accessUnitBuffer.write(data, offset, length);
  }

  private void resetAccessUnit() {
    accessUnitBuffer.reset();
    bufferFlags = 0;
    droppingAccessUnit = false;
  }

  private static @C.BufferFlags int getBufferFlagsFromNalType(int nalType) {
    return nalType == NAL_UNIT_TYPE_IDR ? C.BUFFER_FLAG_KEY_FRAME : 0;
  }
}
