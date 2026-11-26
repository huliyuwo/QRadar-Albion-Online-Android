package com.minhui.vpn.PhotonPackageParser.classes;

import android.util.Log;

import com.minhui.vpn.Handlers.MainHandler;
import com.minhui.vpn.PhotonPackageParser.Events.NewAuctionEvent;
import com.minhui.vpn.PhotonPackageParser.Events.NewChestEvent;
import com.minhui.vpn.PhotonPackageParser.Events.NewFishingZoneEvent;
import com.minhui.vpn.PhotonPackageParser.Events.NewHarvestableEvent;
import com.minhui.vpn.PhotonPackageParser.Events.NewMobEvent;
import com.minhui.vpn.PhotonPackageParser.Events.NewOthersEvent;
import com.minhui.vpn.PhotonPackageParser.Events.NewPlayerEvent;
import com.minhui.vpn.PhotonPackageParser.ProtocolCodeLookup;
import com.minhui.vpn.PhotonPackageParser.Utils;
import com.minhui.vpn.PhotonPackageParser.enumerations.EventCodes;
import com.minhui.vpn.PhotonPackageParser.enumerations.OperationCodes;
import com.minhui.vpn.zone.ZoneState;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Photon UDP 数据包解析器。
 *
 * <p>帧结构 / 命令分发 / 分片重组对齐 PC 参考实现 {@code internal/photon/packet.go}：
 * <ul>
 *   <li>flags == 1 表示整包加密（需要 MITM 才能解密），直接跳过；</li>
 *   <li>命令 8 (SendFragment) 需要按 startSequence + fragmentOffset 重组，
 *       旧实现直接跳过，导致大包（Join 响应、批量采集物）永远收不到；</li>
 *   <li>单条消息解析失败只丢弃该消息，不再中断同一 UDP 包里后续消息；</li>
 *   <li>事件码 / 操作码优先取参数 252 / 253（Albion 把真实码放在参数里），
 *       取不到时回退到消息头字节。</li>
 * </ul>
 */
public class PhotonPacketParser
{
    private static final int PHOTON_HEADER_LENGTH = 12;
    private static final int COMMAND_HEADER_LENGTH = 12;
    private static final int FRAGMENT_HEADER_LENGTH = 20;

    private static final int CMD_DISCONNECT = 4;
    private static final int CMD_SEND_RELIABLE = 6;
    private static final int CMD_SEND_UNRELIABLE = 7;
    private static final int CMD_SEND_FRAGMENT = 8;

    private static final int MSG_REQUEST = 2;
    private static final int MSG_RESPONSE = 3;
    private static final int MSG_EVENT = 4;
    private static final int MSG_RESPONSE_ALT = 7;
    private static final int MSG_ENCRYPTED = 131;

    /** 分片重组缓存上限（PC maxPendingSegments）。 */
    private static final int MAX_PENDING_SEGMENTS = 64;

    /** 与 PC maxArraySize 一致：挡住损坏长度字段导致的巨量分配。 */
    private static final int MAX_ARRAY_SIZE = 65536;

    private static final int PARAM_EVENT_CODE = 252;
    private static final int PARAM_OPERATION_CODE = 253;

    private final NewPlayerEvent newPlayerEvent = new NewPlayerEvent();
    private final NewMobEvent newMobEvent = new NewMobEvent();
    private final NewHarvestableEvent newHarvestableEvent = new NewHarvestableEvent();
    private final NewChestEvent newChestEvent = new NewChestEvent();
    private final NewFishingZoneEvent newFishingZoneEvent = new NewFishingZoneEvent();
    private final NewOthersEvent newOthersEvent = new NewOthersEvent();
    private final NewAuctionEvent newAuctionEvent = new NewAuctionEvent();

    private final float[] position = new float[2];

    /** 未完成的分片：startSequenceNumber -> 组装状态。 */
    private final LinkedHashMap<Long, Segment> pendingSegments = new LinkedHashMap<>();

    // ---------------- 统计 / 诊断 ----------------

    private static int cntRequest = 0;
    private static int cntResponse = 0;
    private static int cntEvent = 0;
    private static int cntError = 0;

    private static int diagOps = 0;
    private static int diagResponses = 0;
    private static int diagMoves = 0;
    private static int diagMoveRequests = 0;
    private static int diagEncodedMoves = 0;
    private static final java.util.HashSet<Integer> diagEventCodesSeen = new java.util.HashSet<>();
    private static int diagEventErrors = 0;

    public void receive(ByteBuffer packet)
    {
        if (packet == null || packet.remaining() < PHOTON_HEADER_LENGTH)
        {
            return;
        }

        try
        {
            packet.getShort();                      // peerId
            int flags = packet.get() & 0xFF;        // flags / crcEnabled
            int commandCount = packet.get() & 0xFF;
            packet.getInt();                        // timestamp
            packet.getInt();                        // challenge

            // 加密包无法在本地解密（XOR 位置同理），跳过即可，别让后续包解析失败
            if (flags == 1)
            {
                return;
            }

            for (int i = 0; i < commandCount; i++)
            {
                if (!handleCommand(packet))
                {
                    break;
                }
            }
        }
        catch (RuntimeException e)
        {
            cntError++;
            logErrorRateLimited("PKT err#" + cntError + " req=" + cntRequest + " resp=" + cntResponse
                    + " evt=" + cntEvent + " : " + e);
        }
    }

    /** @return false 表示当前帧已损坏，停止解析该 UDP 包剩余命令。 */
    private boolean handleCommand(ByteBuffer packet)
    {
        if (packet.remaining() < COMMAND_HEADER_LENGTH)
        {
            return false;
        }

        int commandType = packet.get() & 0xFF;
        packet.get();                  // channelId
        packet.get();                  // commandFlags
        packet.get();                  // reserved
        int commandLength = packet.getInt();
        packet.getInt();               // reliableSequenceNumber

        int payloadLength = commandLength - COMMAND_HEADER_LENGTH;

        if (payloadLength < 0 || payloadLength > packet.remaining())
        {
            // 长度字段和实际剩余量对不上：帧已损坏，硬解会整包错位
            return false;
        }

        switch (commandType)
        {
            case CMD_DISCONNECT:
                skip(packet, payloadLength);
                return true;

            case CMD_SEND_UNRELIABLE:
                if (payloadLength < 4)
                {
                    skip(packet, payloadLength);
                    return true;
                }
                skip(packet, 4);       // unreliableSequenceNumber
                return dispatchMessage(packet, payloadLength - 4);

            case CMD_SEND_RELIABLE:
                return dispatchMessage(packet, payloadLength);

            case CMD_SEND_FRAGMENT:
                return handleFragment(packet, payloadLength);

            default:
                skip(packet, payloadLength);
                return true;
        }
    }

    /** 一条 reliable 消息：[signalByte][messageType][payload]。 */
    private boolean dispatchMessage(ByteBuffer packet, int length)
    {
        if (length < 2)
        {
            skip(packet, Math.max(0, length));
            return true;
        }

        skip(packet, 1);                       // signalByte
        int messageType = packet.get() & 0xFF;
        int payloadLength = length - 2;

        if (payloadLength < 0 || payloadLength > packet.remaining())
        {
            return false;
        }

        byte[] payload = new byte[payloadLength];
        packet.get(payload);

        if (messageType == MSG_ENCRYPTED)
        {
            return true;
        }

        dispatchPayload(messageType, payload);
        return true;
    }

    private void dispatchPayload(int messageType, byte[] payload)
    {
        if (payload.length < 1)
        {
            return;
        }

        ByteBuffer body = ByteBuffer.wrap(payload);
        byte code = (byte) (body.get() & 0xFF);

        switch (messageType)
        {
            case MSG_REQUEST:
                cntRequest++;
                handleSafely("request", code, () ->
                {
                    HashMap<Object, Object> params = Protocol18Deserializer.deserializeOperationRequest(body);
                    probeHandshake(params);
                    onRequest(code, params);
                });
                break;

            case MSG_RESPONSE:
            case MSG_RESPONSE_ALT:
                cntResponse++;
                handleSafely("response", code, () ->
                {
                    HashMap<Object, Object> params = Protocol18Deserializer.deserializeOperationResponse(body);
                    onResponse(code, params);
                });
                break;

            case MSG_EVENT:
                cntEvent++;
                handleSafely("event", code, () ->
                {
                    HashMap<Object, Object> params = Protocol18Deserializer.deserializeEventData(body);
                    onEvent(code, params);
                });

                if (cntEvent % 300 == 0)
                {
                    Log.d("RadarDiag", "PKT req=" + cntRequest + " resp=" + cntResponse
                            + " evt=" + cntEvent + " err=" + cntError);
                }
                break;

            default:
                break;
        }
    }

    private interface MessageParser
    {
        void parse();
    }

    /**
     * 单个消息级异常隔离：一条坏消息只丢自己，不中断同包内后续消息
     * （PC 端 handleSendReliable 对每条消息独立 deserialize + 忽略错误）。
     */
    private void handleSafely(String kind, int code, MessageParser parser)
    {
        try
        {
            parser.parse();
        }
        catch (RuntimeException e)
        {
            cntError++;

            if (diagEventErrors++ < 8)
            {
                Log.d("RadarDiag", "MSG " + kind + " code=" + code + " dropped: " + e);
            }
        }
    }

    // ---------------- 分片重组（PC handleSendFragment） ----------------

    private boolean handleFragment(ByteBuffer packet, int length)
    {
        if (length < FRAGMENT_HEADER_LENGTH || packet.remaining() < FRAGMENT_HEADER_LENGTH)
        {
            skip(packet, Math.max(0, length));
            return true;
        }

        long startSequence = packet.getInt() & 0xFFFFFFFFL;
        int fragmentCount = packet.getInt();
        int fragmentNumber = packet.getInt();
        int totalLength = packet.getInt();
        int fragmentOffset = packet.getInt();

        int fragmentLength = length - FRAGMENT_HEADER_LENGTH;

        if (fragmentLength < 0 || fragmentLength > packet.remaining()
                || totalLength < 0 || totalLength > MAX_ARRAY_SIZE * 16)
        {
            skip(packet, Math.max(0, fragmentLength));
            return false;
        }

        Segment segment = pendingSegments.get(startSequence);

        if (segment == null)
        {
            evictOldestSegmentIfFull();
            segment = new Segment(totalLength);
            pendingSegments.put(startSequence, segment);
        }

        byte[] fragment = new byte[fragmentLength];
        packet.get(fragment);
        segment.append(fragmentOffset, fragment);

        if (segment.isComplete())
        {
            pendingSegments.remove(startSequence);

            // 重组完成后的 payload 与 reliable 消息同格式：[signalByte][messageType][payload]
            ByteBuffer assembled = ByteBuffer.wrap(segment.payload);
            dispatchMessage(assembled, segment.totalLength);
        }

        return true;
    }

    private void evictOldestSegmentIfFull()
    {
        if (pendingSegments.size() < MAX_PENDING_SEGMENTS)
        {
            return;
        }

        Iterator<Map.Entry<Long, Segment>> iterator = pendingSegments.entrySet().iterator();

        if (iterator.hasNext())
        {
            iterator.next();
            iterator.remove();
        }
    }

    /** 单个分片包的组装状态；重复 offset 的分片会被忽略（UDP 丢包重传）。 */
    private static final class Segment
    {
        final int totalLength;
        final byte[] payload;
        final java.util.HashSet<Integer> seenOffsets = new java.util.HashSet<>();
        int bytesWritten;

        Segment(int totalLength)
        {
            this.totalLength = totalLength;
            this.payload = new byte[totalLength];
        }

        void append(int fragmentOffset, byte[] fragment)
        {
            int end = fragmentOffset + fragment.length;

            if (fragmentOffset < 0 || end > payload.length || seenOffsets.contains(fragmentOffset))
            {
                return;
            }

            System.arraycopy(fragment, 0, payload, fragmentOffset, fragment.length);
            seenOffsets.add(fragmentOffset);
            bytesWritten += fragment.length;
        }

        boolean isComplete()
        {
            return totalLength > 0 && bytesWritten >= totalLength;
        }
    }

    private static void skip(ByteBuffer packet, int count)
    {
        if (count <= 0)
        {
            return;
        }

        int target = Math.min(packet.limit(), packet.position() + count);
        packet.position(target);
    }

    private static void logErrorRateLimited(String message)
    {
        if (cntError <= 8)
        {
            Log.d("RadarDiag", message);
        }
    }

    // ---------------- 消息处理 ----------------

    protected void onEvent(byte headerCode, HashMap<Object, Object> parameters)
    {
        int code = resolveCode(headerCode, parameters, PARAM_EVENT_CODE);
        EventCodes eventCode = ProtocolCodeLookup.event(code);

        if (eventCode == null)
        {
            return;
        }

        // 主城暂停：完全不解析。事件里没有切图/入场消息（那是 operation），全部丢弃。
        if (ZoneState.shouldSkipFully())
        {
            return;
        }

        if (eventCode == EventCodes.Move)
        {
            handleMoveEvent(parameters);
        }
        else
        {
            switch (eventCode)
            {
                case Leave:
                    newPlayerEvent.removePlayer(parameters);
                    newMobEvent.removeMob(parameters);
                    newHarvestableEvent.removeHarvestable(parameters);
                    newFishingZoneEvent.removeFishingZoneObject(parameters);
                    newChestEvent.removeChest(parameters);
                    break;

                case NewCharacter:
                    newPlayerEvent.NewCharacter(parameters);
                    break;

                case Mounted:
                    newPlayerEvent.Mounted(parameters);
                    break;

                case NewMountObject:
                    newPlayerEvent.NewMount(parameters);
                    break;

                case InCombatStateUpdate:
                    newPlayerEvent.InCombatStateUpdate(parameters);
                    break;

                case HealthUpdate:
                    newPlayerEvent.HealthUpdate(parameters);
                    break;

                case Attack:
                    newPlayerEvent.Attack(parameters);
                    break;

                case NewSimpleItem:
                case NewEquipmentItem:
                case NewFurnitureItem:
                case NewJournalItem:
                case NewLaborerItem:
                    newHarvestableEvent.NewSimpleItem(parameters, eventCode);
                    break;

                case UpdateMoney:
                    newPlayerEvent.UpdateMoney(parameters);
                    break;

                case MobChangeState:
                    newMobEvent.MobChangeState(parameters);
                    break;

                case NewMob:
                    newMobEvent.NewMob(parameters);
                    break;

                case NewSimpleHarvestableObjectList:
                    newHarvestableEvent.NewSimpleHarvestableObjectList(parameters);
                    break;

                case NewHarvestableObject:
                    newHarvestableEvent.NewHarvestableObject(parameters);
                    break;

                case HarvestableChangeState:
                    newHarvestableEvent.HarvestableChangeState(parameters);
                    break;

                case HarvestStart:
                    newHarvestableEvent.HarvestStart(parameters);
                    break;

                case HarvestFinished:
                    newHarvestableEvent.HarvestFinished(parameters);
                    break;

                case NewFloatObject:
                    newHarvestableEvent.NewFloatObject(parameters);
                    break;

                case NewTreasureChest:
                    newChestEvent.NewTreasureChest(parameters);
                    break;

                case NewFishingZoneObject:
                    newFishingZoneEvent.NewFishingZoneObject(parameters);
                    break;

                case FishingFinished:
                    newFishingZoneEvent.FishingFinished(parameters);
                    break;

                case NewBuilding:
                    newPlayerEvent.NewBuilding(parameters);
                    break;

                case NewLoot:
                    newOthersEvent.NewLoot(parameters);
                    break;

                case NewExit:
                    newOthersEvent.NewExit(parameters);
                    break;

                case NewRandomDungeonExit:
                    newOthersEvent.NewRandomDungeonExit(parameters);
                    break;

                default:
                    // 诊断：记录每个未处理事件码一次，便于发现“玩家坐标藏在哪个事件里”
                    if (diagEventCodesSeen.size() < 80 && diagEventCodesSeen.add(code))
                    {
                        StringBuilder builder = new StringBuilder("EVT code=").append(code)
                                .append(" name=").append(eventCode).append(" params=");

                        for (Map.Entry<Object, Object> entry : parameters.entrySet())
                        {
                            builder.append(entry.getKey()).append('=');

                            Object value = entry.getValue();

                            if (value instanceof Object[])
                            {
                                Object[] array = (Object[]) value;
                                builder.append("Object[").append(array.length).append("]{");

                                for (int i = 0; i < Math.min(4, array.length); i++)
                                {
                                    builder.append(array[i]).append(',');
                                }

                                builder.append('}');
                            }
                            else if (value instanceof byte[])
                            {
                                builder.append("byte[").append(((byte[]) value).length).append(']');
                            }
                            else
                            {
                                builder.append(value).append('(')
                                        .append(value == null ? "null" : value.getClass().getSimpleName())
                                        .append(')');
                            }

                            builder.append(' ');
                        }

                        Log.d("RadarDiag", builder.toString());
                    }
                    break;
            }
        }
    }

    /** Move 事件：参数 1 是字节数组，坐标在 [9..13) / [13..17)（PC events.go extractMovePositions）。 */
    private void handleMoveEvent(HashMap<Object, Object> parameters)
    {
        int id = Utils.getNumber(parameters.get(0));
        byte[] bytes = Utils.getByteArray(parameters.get(1));

        if (bytes == null || bytes.length < 17)
        {
            return;
        }

        float x = Float.intBitsToFloat(Utils.bytesToInt(bytes, 9));
        float y = Float.intBitsToFloat(Utils.bytesToInt(bytes, 13));

        final com.minhui.vpn.Handlers.PlayersHandler playersHandler =
                MainHandler.getInstance().playersHandler;
        final int localId = playersHandler.localPlayerId();
        final boolean knownPlayer = id != localId && playersHandler.containsPlayer(id);

        if (knownPlayer || !PlayerMoveDecoder.isPlausible(x, y))
        {
            // 玩家坐标：第二层 8 字节 XorCode 异或（明文解出来是 NaN/Inf）。
            // 自身事件用来反推 XorCode；拿到之后所有玩家坐标都能解。
            if (id == localId)
            {
                PlayerMoveDecoder.onSelfMove(bytes, 9,
                        playersHandler.localPlayerPosX(), playersHandler.localPlayerPosY(),
                        playersHandler.localPlayerPosAgeMs());

                // 自身坐标以客户端明文请求为准，事件解不出来也不影响雷达居中
                if (!PlayerMoveDecoder.isPlausible(x, y))
                {
                    return;
                }
            }
            else
            {
                float[] decoded = PlayerMoveDecoder.decode(bytes, 9);

                if (decoded == null)
                {
                    if (diagEncodedMoves++ < 12)
                    {
                        Log.d("RadarDiag", "MOVE-ENC id=" + id + " local=" + localId
                                + " len=" + bytes.length + " hex=" + PlayerMoveDecoder.hex(bytes));
                    }

                    return;
                }

                x = decoded[0];
                y = decoded[1];
            }
        }
        else if (id == localId)
        {
            // 自身事件既可能是明文也可能是 XOR：两种都喂给解码器，便于自恢复
            PlayerMoveDecoder.onSelfMove(bytes, 9,
                    playersHandler.localPlayerPosX(), playersHandler.localPlayerPosY(),
                    playersHandler.localPlayerPosAgeMs());
        }

        if (!Float.isFinite(x) || !Float.isFinite(y))
        {
            return;
        }

        position[0] = x;
        position[1] = y;

        if (diagMoves++ < 20)
        {
            Log.d("RadarDiag", "MOVE id=" + id + " local=" + localId + " len=" + bytes.length
                    + " pos=" + x + "," + y + " known=" + knownPlayer
                    + " xor=" + PlayerMoveDecoder.isReady()
                    + (diagMoves <= 8 ? " hex=" + PlayerMoveDecoder.hex(bytes) : ""));
        }

        newPlayerEvent.updatePlayerPosition(id, x, y);
        newMobEvent.updateMobPosition(id, x, y);
        MainHandler.getInstance().playersHandler.updateLocalPlayerPositionById(id, x, y);
    }

    protected void onRequest(byte headerCode, HashMap<Object, Object> parameters)
    {
        int code = resolveCode(headerCode, parameters, PARAM_OPERATION_CODE);
        OperationCodes operationCode = ProtocolCodeLookup.operation(code);

        if (operationCode == null)
        {
            return;
        }

        // 主城暂停：完全不解析。只放行 ChangeCluster —— 它是“离开主城、恢复解析”的唯一入口，
        // 其余请求（Move / 交互等）全部丢弃。
        if (ZoneState.shouldSkipFully() && operationCode != OperationCodes.ChangeCluster)
        {
            return;
        }

        if (operationCode != OperationCodes.Move && diagOps++ < 25)
        {
            Log.d("RadarDiag", "OPREQ hdr=" + (headerCode & 0xFF) + " p253=" + parameters.get(PARAM_OPERATION_CODE)
                    + " ->" + operationCode + " keys=" + parameters.keySet());
        }

        switch (operationCode)
        {
            case Move:
            {
                float[] pos = Utils.getFloats(parameters.get(1));
                float angle = Utils.getFloat(parameters.get(2));
                float[] targetPos = Utils.getFloats(parameters.get(3));
                float speed = Utils.getFloat(parameters.get(4));

                if (pos != null && pos.length >= 2)
                {
                    MainHandler.getInstance().playersHandler.updateLocalPlayerPosition(
                            pos[0], pos[1], angle,
                            targetPos != null && targetPos.length >= 2 ? targetPos[0] : pos[0],
                            targetPos != null && targetPos.length >= 2 ? targetPos[1] : pos[1],
                            speed);
                }

                if (diagMoveRequests++ < 3)
                {
                    Log.d("RadarDiag", "MOVEREQ pos=" + (pos == null ? "null" : pos[0] + "," + pos[1])
                            + " target=" + (targetPos == null ? "null" : targetPos[0] + "," + targetPos[1])
                            + " angle=" + angle + " speed=" + speed);
                }
                break;
            }

            case ChangeCluster:
                // 参考 PC：换图请求到达时清空上一张图的实体；参数 0 = 目标地图 id
                Log.d("RadarDiag", "ChangeCluster REQUEST -> clearAll");
                MainHandler.getInstance().clearAll();
                updateZone(parameters.get(0));
                break;

            case AuctionGetOffers:
                newAuctionEvent.AuctionGetOffersRequest(parameters);
                break;

            default:
                break;
        }
    }

    protected void onResponse(byte headerCode, HashMap<Object, Object> parameters)
    {
        int code = resolveCode(headerCode, parameters, PARAM_OPERATION_CODE);
        OperationCodes operationCode = ProtocolCodeLookup.operation(code);

        if (diagResponses++ < 15)
        {
            Log.d("RadarDiag", "OPRESP hdr=" + (headerCode & 0xFF) + " p253=" + parameters.get(PARAM_OPERATION_CODE)
                    + " ->" + (operationCode == null ? "null" : operationCode)
                    + " size=" + parameters.size() + " keys=" + parameters.keySet());
        }

        // Join 响应按 shape 兜底：参数表里同时有 0/2/8 且数量很大，就是 Join。
        // 解析修好后正常走 opcode 分支，这里只防"操作码参数缺失"的极端情况。
        // 主城暂停时也要走这里：Join 带地图 id（参数 8）与本地玩家信息。
        if (operationCode != OperationCodes.Join && looksLikeJoinResponse(parameters))
        {
            Log.d("RadarDiag", "JOIN by shape: size=" + parameters.size() + " p253=" + parameters.get(PARAM_OPERATION_CODE));
            newPlayerEvent.updateLocalPlayer(parameters);
            updateZone(parameters.get(8));
            return;
        }

        if (operationCode == null)
        {
            return;
        }

        // 主城暂停：完全不解析。只放行 Join（本地玩家 + 地图 id）与 ChangeCluster（切图），
        // 否则无法检测“已经离开主城”，暂停会永远卡住。
        if (ZoneState.shouldSkipFully()
                && operationCode != OperationCodes.Join
                && operationCode != OperationCodes.ChangeCluster)
        {
            return;
        }

        switch (operationCode)
        {
            case Join:
                newPlayerEvent.updateLocalPlayer(parameters);
                updateZone(parameters.get(8));
                break;

            case ChangeCluster:
                // 参考 PC：换图响应到达时清空上一张图的实体；参数 0 = 目标地图 id
                Log.d("RadarDiag", "ChangeCluster RESPONSE -> clearAll");
                MainHandler.getInstance().clearAll();
                updateZone(parameters.get(0));
                break;

            case AuctionGetOffers:
                newAuctionEvent.AuctionGetOffersResponse(parameters);
                break;

            default:
                break;
        }
    }

    private static boolean looksLikeJoinResponse(HashMap<Object, Object> parameters)
    {
        return parameters.size() > 40
                && parameters.containsKey(0)
                && parameters.containsKey(2)
                && parameters.containsKey(8);
    }

    /**
     * 主城暂停的状态机：解析到新地图 id 后更新 {@link ZoneState}，
     * 并在“进入主城 / 离开主城”的那一刻做清理与日志。
     *
     * <p>ChangeCluster（op 41，参数 0 = 目标地图 id）与 Join（参数 8 = 当前 cluster）
     * 都只在切图/入场时出现，所以即使处于暂停状态也必须调用：
     * <ul>
     *   <li>进入主城且 pauseMode=1：立刻 clearAll 释放上一张图的内存，后续包在入口处全部丢弃；</li>
     *   <li>进入主城且 pauseMode=2：只暂停渲染，解析继续；</li>
     *   <li>离开主城：打 {@code CITY PAUSE: resumed}，下一次解析立刻恢复正常。</li>
     * </ul>
     */
    private static void updateZone(Object rawZoneId)
    {
        if (rawZoneId == null)
        {
            return;
        }

        String zoneId = rawZoneId instanceof Number
                ? String.valueOf(((Number) rawZoneId).intValue())
                : String.valueOf(rawZoneId);

        boolean wasCity = ZoneState.isCity;
        int mode = ZoneState.pauseMode;

        ZoneState.setZone(zoneId);

        boolean nowCity = ZoneState.isCity;

        if (!wasCity && nowCity)
        {
            if (mode == ZoneState.MODE_SKIP_ALL)
            {
                MainHandler.getInstance().clearAll();
                Log.d("RadarDiag", "CITY PAUSE: parsing suspended (mode=1 zone="
                        + ZoneState.zoneId + " name=" + ZoneState.zoneName + ")");
            }
            else if (mode == ZoneState.MODE_RENDER_ONLY)
            {
                Log.d("RadarDiag", "CITY PAUSE: render suspended (mode=2 zone="
                        + ZoneState.zoneId + " name=" + ZoneState.zoneName + ")");
            }
        }
        else if (wasCity && !nowCity)
        {
            Log.d("RadarDiag", "CITY PAUSE: resumed (zone="
                    + ZoneState.zoneId + " name=" + ZoneState.zoneName + ")");
        }
    }

    /**
     * 真实事件码 / 操作码在参数 252 / 253；取不到时回退消息头字节。
     */
    private static int resolveCode(byte headerCode, HashMap<Object, Object> parameters, int paramId)
    {
        int header = headerCode & 0xFF;
        Object wireCode = parameters == null ? null : parameters.get(paramId);

        if (wireCode instanceof Number)
        {
            int value = ((Number) wireCode).intValue();

            if (value >= 0)
            {
                return value;
            }
        }

        return header;
    }

    private static void probeHandshake(HashMap<Object, Object> params)
    {
        if (params == null || params.isEmpty())
        {
            return;
        }

        for (Object value : params.values())
        {
            if (value instanceof byte[])
            {
                byte[] bytes = (byte[]) value;

                // DH 公钥长度 96（768-bit）或 128（补零版本），交回 PhotonCrypto 判定
                if (bytes.length == 96 || bytes.length == 128)
                {
                    PhotonCrypto.get().offerPublicKey(bytes);
                }
            }
        }
    }
}
