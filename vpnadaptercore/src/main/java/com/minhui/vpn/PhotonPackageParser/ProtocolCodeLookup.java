package com.minhui.vpn.PhotonPackageParser;

import com.minhui.vpn.PhotonPackageParser.enumerations.EventCodes;
import com.minhui.vpn.PhotonPackageParser.enumerations.OperationCodes;

/**
 * 协议码表查询工具。
 *
 * <p>{@link EventCodes} / {@link OperationCodes} 的 ordinal 就是协议编码，
 * 表中已与 PC 参考实现 {@code internal/photon/eventcodes}、{@code operationcodes}
 * 逐条对齐（712 / 556 条）。这里统一做越界保护，避免收到脏码时
 * {@code values()[code]} 抛 ArrayIndexOutOfBounds 把整条消息打断。
 */
public final class ProtocolCodeLookup
{
    private static final EventCodes[] EVENTS = EventCodes.values();
    private static final OperationCodes[] OPERATIONS = OperationCodes.values();

    private ProtocolCodeLookup()
    {
    }

    public static EventCodes event(int code)
    {
        if (code < 0 || code >= EVENTS.length)
        {
            return null;
        }

        return EVENTS[code];
    }

    public static OperationCodes operation(int code)
    {
        if (code < 0 || code >= OPERATIONS.length)
        {
            return null;
        }

        return OPERATIONS[code];
    }

    public static String eventName(int code)
    {
        EventCodes value = event(code);
        return value == null ? "Unknown_" + code : value.name();
    }

    public static String operationName(int code)
    {
        OperationCodes value = operation(code);
        return value == null ? "Unknown_" + code : value.name();
    }

    public static int eventCount()
    {
        return EVENTS.length;
    }

    public static int operationCount()
    {
        return OPERATIONS.length;
    }
}
