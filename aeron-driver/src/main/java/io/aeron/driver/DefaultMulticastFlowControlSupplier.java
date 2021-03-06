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
import org.agrona.LangUtil;

public class DefaultMulticastFlowControlSupplier implements FlowControlSupplier
{
    public FlowControl newInstance(final UdpChannel udpChannel, final int streamId, final long registrationId)
    {
        final String fcStr = udpChannel.channelUri().get(CommonContext.FLOW_CONTROL_PARAM_NAME);
        FlowControl flowControl = null;

        if (null != fcStr)
        {
            final String[] args = fcStr.split(",");

            if ("max".equals(args[0]))
            {
                return new MaxMulticastFlowControl();
            }
            else if ("min".equals(args[0]))
            {
                for (final String arg : args)
                {
                    if (arg.startsWith("g:"))
                    {
                        return new PreferredMulticastFlowControl();
                    }
                }

                return new MinMulticastFlowControl();
            }
            else
            {
                throw new IllegalArgumentException("unsupported multicast flow control strategy : fc=" + fcStr);
            }
        }

        try
        {
            flowControl = (FlowControl)Class.forName(Configuration.MULTICAST_FLOW_CONTROL_STRATEGY)
                .getConstructor()
                .newInstance();
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return flowControl;
    }

    public String toString()
    {
        return "DefaultMulticastFlowControlSupplier{flowControlClass=" +
            Configuration.MULTICAST_FLOW_CONTROL_STRATEGY + "}";
    }
}
