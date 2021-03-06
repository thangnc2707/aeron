/*
 * Copyright 2014-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver;

import io.aeron.CommonContext;
import io.aeron.driver.media.UdpChannel;
import io.aeron.protocol.StatusMessageFlyweight;
import org.agrona.AsciiEncoding;
import org.agrona.BitUtil;
import org.agrona.SystemUtil;
import org.agrona.concurrent.UnsafeBuffer;

import java.net.InetSocketAddress;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

import static io.aeron.driver.MinMulticastFlowControl.EMPTY_RECEIVERS;
import static io.aeron.logbuffer.LogBufferDescriptor.computePosition;
import static java.lang.System.getProperty;
import static org.agrona.SystemUtil.getDurationInNanos;

/**
 * Minimum multicast sender flow control strategy only for preferred members identified by an ASF key.
 * <p>
 * Flow control is set to minimum of tracked preferred receivers.
 * <p>
 * Tracking of preferred receivers is done as long as they continue to send Status Messages. Once SMs stop, the receiver
 * tracking for that receiver will timeout after a given number of nanoseconds.
 */
public class PreferredMulticastFlowControl implements FlowControl
{
    /**
     * Property name to set timeout, in nanoseconds, for a receiver to be tracked.
     */
    public static final String RECEIVER_TIMEOUT_PROP_NAME = "aeron.PreferredMulticastFlowControl.receiverTimeout";

    /**
     * Default timeout, in nanoseconds, until a receiver is no longer tracked and considered for minimum.
     */
    public static final long RECEIVER_TIMEOUT_DEFAULT = TimeUnit.SECONDS.toNanos(2);

    public static final long RECEIVER_TIMEOUT = getDurationInNanos(
        RECEIVER_TIMEOUT_PROP_NAME, RECEIVER_TIMEOUT_DEFAULT);

    /**
     * Property name used to set Application Specific Feedback (ASF) in Status Messages to identify preferred receivers.
     */
    public static final String PREFERRED_ASF_PROP_NAME = "aeron.PreferredMulticastFlowControl.asf";

    /**
     * Default Application Specific Feedback (ASF) value
     */
    public static final String PREFERRED_ASF_DEFAULT = "FFFFFFFF";

    public static final String PREFERRED_ASF = getProperty(PREFERRED_ASF_PROP_NAME, PREFERRED_ASF_DEFAULT);
    public static final byte[] PREFERRED_ASF_BYTES = BitUtil.fromHex(PREFERRED_ASF);

    private MinMulticastFlowControl.Receiver[] receivers = EMPTY_RECEIVERS;
    private long receiverTimeoutNs = RECEIVER_TIMEOUT;
    private long rtag = new UnsafeBuffer(PREFERRED_ASF_BYTES).getInt(0, ByteOrder.LITTLE_ENDIAN);

    /**
     * {@inheritDoc}
     */
    public long onStatusMessage(
        final StatusMessageFlyweight flyweight,
        final InetSocketAddress receiverAddress,
        final long senderLimit,
        final int initialTermId,
        final int positionBitsToShift,
        final long timeNs)
    {
        final long position = computePosition(
            flyweight.consumptionTermId(),
            flyweight.consumptionTermOffset(),
            positionBitsToShift,
            initialTermId);

        final long windowLength = flyweight.receiverWindowLength();
        final long receiverId = flyweight.receiverId();
        final boolean isFromPreferred = isFromPreferred(flyweight);
        final long lastPositionPlusWindow = position + windowLength;
        boolean isExisting = false;
        long minPosition = Long.MAX_VALUE;

        for (final MinMulticastFlowControl.Receiver receiver : receivers)
        {
            if (isFromPreferred && receiverId == receiver.receiverId)
            {
                receiver.lastPosition = Math.max(position, receiver.lastPosition);
                receiver.lastPositionPlusWindow = lastPositionPlusWindow;
                receiver.timeOfLastStatusMessageNs = timeNs;
                isExisting = true;
            }

            minPosition = Math.min(minPosition, receiver.lastPositionPlusWindow);
        }

        if (isFromPreferred && !isExisting)
        {
            final MinMulticastFlowControl.Receiver receiver = new MinMulticastFlowControl.Receiver(
                position, lastPositionPlusWindow, timeNs, receiverId, receiverAddress);
            receivers = MinMulticastFlowControl.add(receivers, receiver);
            minPosition = Math.min(minPosition, lastPositionPlusWindow);
        }

        return receivers.length > 0 ?
            Math.max(senderLimit, minPosition) : Math.max(senderLimit, lastPositionPlusWindow);
    }

    /**
     * {@inheritDoc}
     */
    public void initialize(final UdpChannel udpChannel, final int initialTermId, final int termBufferLength)
    {
        final String fcStr = udpChannel.channelUri().get(CommonContext.FLOW_CONTROL_PARAM_NAME);

        if (null != fcStr)
        {
            for (final String arg : fcStr.split(","))
            {
                if (arg.startsWith("t:"))
                {
                    receiverTimeoutNs = SystemUtil.parseDuration("fc min timeout", arg.substring(2));
                }
                else if (arg.startsWith("g:"))
                {
                    rtag = AsciiEncoding.parseLongAscii(arg, 2, arg.length() - 2);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public long onIdle(final long timeNs, final long senderLimit, final long senderPosition, final boolean isEos)
    {
        long minLimitPosition = Long.MAX_VALUE;
        int removed = 0;

        for (int lastIndex = receivers.length - 1, i = lastIndex; i >= 0; i--)
        {
            final MinMulticastFlowControl.Receiver receiver = receivers[i];
            if ((receiver.timeOfLastStatusMessageNs + receiverTimeoutNs) - timeNs < 0)
            {
                if (i != lastIndex)
                {
                    receivers[i] = receivers[lastIndex--];
                }
                removed++;
            }
            else
            {
                minLimitPosition = Math.min(minLimitPosition, receiver.lastPositionPlusWindow);
            }
        }

        if (removed > 0)
        {
            receivers = MinMulticastFlowControl.truncateReceivers(receivers, removed);
        }

        return receivers.length > 0 ? minLimitPosition : senderLimit;
    }

    private boolean isFromPreferred(final StatusMessageFlyweight statusMessageFlyweight)
    {
        final int asfLength = statusMessageFlyweight.asfLength();
        boolean result = false;

        // default ASF is 4 bytes
        if (asfLength >= 4)
        {
            if (statusMessageFlyweight.receiverTag() == rtag)
            {
                result = true;
            }
        }

        return result;
    }
}
