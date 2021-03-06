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
package io.aeron.agent;

import org.agrona.concurrent.UnsafeBuffer;

import static io.aeron.agent.CommonEventEncoder.encodeLogHeader;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.agrona.BitUtil.SIZE_OF_LONG;

final class ClusterEventEncoder
{
    static final String SEPARATOR = " -> ";

    private ClusterEventEncoder()
    {
    }

    static int encodeNewLeadershipTerm(
        final UnsafeBuffer encodingBuffer,
        final int offset,
        final int captureLength,
        final int length,
        final long logLeadershipTermId,
        final long leadershipTermId,
        final long logPosition,
        final long timestamp,
        final int leaderMemberId,
        final int logSessionId)
    {
        int relativeOffset = encodeLogHeader(encodingBuffer, offset, captureLength, length);

        encodingBuffer.putLong(offset + relativeOffset, logLeadershipTermId, LITTLE_ENDIAN);
        relativeOffset += SIZE_OF_LONG;

        encodingBuffer.putLong(offset + relativeOffset, leadershipTermId, LITTLE_ENDIAN);
        relativeOffset += SIZE_OF_LONG;

        encodingBuffer.putLong(offset + relativeOffset, logPosition, LITTLE_ENDIAN);
        relativeOffset += SIZE_OF_LONG;

        encodingBuffer.putLong(offset + relativeOffset, timestamp, LITTLE_ENDIAN);
        relativeOffset += SIZE_OF_LONG;

        encodingBuffer.putInt(offset + relativeOffset, leaderMemberId, LITTLE_ENDIAN);
        relativeOffset += SIZE_OF_INT;

        encodingBuffer.putInt(offset + relativeOffset, logSessionId, LITTLE_ENDIAN);
        relativeOffset += SIZE_OF_INT;

        return relativeOffset;
    }

    static int newLeaderShipTermLength()
    {
        return SIZE_OF_LONG * 4 + SIZE_OF_INT * 2;
    }

    static <T extends Enum<T>> int encodeStateChange(
        final UnsafeBuffer encodingBuffer,
        final int offset,
        final int captureLength,
        final int length,
        final T from,
        final T to,
        final int memberId)
    {
        int relativeOffset = encodeLogHeader(encodingBuffer, offset, captureLength, length);

        encodingBuffer.putInt(offset + relativeOffset, memberId, LITTLE_ENDIAN);
        relativeOffset += SIZE_OF_INT;

        encodingBuffer.putInt(offset + relativeOffset, captureLength - SIZE_OF_INT * 2, LITTLE_ENDIAN);
        relativeOffset += SIZE_OF_INT;

        relativeOffset += encodingBuffer.putStringWithoutLengthAscii(offset + relativeOffset, from.name());
        relativeOffset += encodingBuffer.putStringWithoutLengthAscii(offset + relativeOffset, SEPARATOR);
        relativeOffset += encodingBuffer.putStringWithoutLengthAscii(offset + relativeOffset, to.name());

        return relativeOffset;
    }

    static <T extends Enum<T>> int stateChangeLength(final T from, final T to)
    {
        return stateTransitionStringLength(from, to) + SIZE_OF_INT * 2;
    }

    private static <T extends Enum<T>> int stateTransitionStringLength(final T from, final T to)
    {
        return from.name().length() + SEPARATOR.length() + to.name().length();
    }
}
