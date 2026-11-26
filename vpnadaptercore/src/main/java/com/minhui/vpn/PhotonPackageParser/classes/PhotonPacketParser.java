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
import com.minhui.vpn.PhotonPackageParser.Utils;
import com.minhui.vpn.PhotonPackageParser.enumerations.EventCodes;
import com.minhui.vpn.PhotonPackageParser.enumerations.OperationCodes;

import org.greenrobot.eventbus.EventBus;

import java.nio.ByteBuffer;
import java.util.HashMap;

public class PhotonPacketParser
{
    NewPlayerEvent newPlayerEvent = new NewPlayerEvent();
    NewMobEvent newMobEvent = new NewMobEvent();
    NewHarvestableEvent newHarvestableEvent = new NewHarvestableEvent();
    NewChestEvent newChestEvent = new NewChestEvent();
    NewFishingZoneEvent newFishingZoneEvent = new NewFishingZoneEvent();
    NewOthersEvent newOthersEvent = new NewOthersEvent();
    NewAuctionEvent newAuctionEvent = new NewAuctionEvent();

    float [] position = new float[2];

    public  void receive(ByteBuffer p)
    {
        try
        {
            int peerId = Short.toUnsignedInt(p.getShort());
            int crcEnabled = Byte.toUnsignedInt(p.get());
            int commandCount = Byte.toUnsignedInt(p.get());
            int timestamp = p.getInt();
            int challenge = p.getInt();

            int commandHeaderLength = 12;
            int signifierByteLength = 1;

            for (int commandIdx = 0; commandIdx < commandCount; commandIdx++)
            {
                int commandType = Byte.toUnsignedInt(p.get());
                int channelId = Byte.toUnsignedInt(p.get());
                int commandFlags = Byte.toUnsignedInt(p.get());
                int unkBytes = Byte.toUnsignedInt(p.get());
                int commandLength = p.getInt();
                int sequenceNumber = p.getInt();

                switch (commandType)
                {
                    case 4: // Disconnect
                        break;

                    case 7: // Send unreliable
                        if (p.remaining() < 4)
                        {
                            return;
                        }

                        p.position(p.position() + 4);
                        commandLength -= 4;
                        // fall through

                    case 6: // Send reliable
                        p.position(p.position() + signifierByteLength);
                        int messageType = Byte.toUnsignedInt(p.get());

                        int operationLength = commandLength - commandHeaderLength - 2;

                        // 命令长度来自网络数据：与缓冲区实际剩余量对不上就说明帧已经损坏，
                        // 继续解析只会得到错位的长度字段（曾因此申请 4GB 数组把进程 OOM 掉）
                        if (operationLength < 0 || operationLength > p.remaining())
                        {
                            return;
                        }

                        byte[] tempBytes = new byte[operationLength];
                        p.get(tempBytes);
                        ByteBuffer payload = ByteBuffer.wrap(tempBytes);

                        switch (messageType)
                        {
                            case 2:
                            {
                                cntRequest++;
                                byte operationCode = payload.get();
                                java.util.HashMap<Object, Object> params =
                                        Protocol18Deserializer.deserializeOperationRequest(payload);
                                probeHandshake(params);
                                onRequest(operationCode, params);
                                break;
                            }

                            case 3:
                            {
                                cntResponse++;
                                byte operationCode = payload.get();

                                try
                                {
                                    onResponse(operationCode, Protocol18Deserializer.deserializeOperationResponse(payload));
                                }
                                catch (Exception e)
                                {
                                    if (cntError <= 8)
                                    {
                                        StringBuilder hex = new StringBuilder();
                                        byte[] raw = tempBytes;

                                        for (int i = 0; i < Math.min(40, raw.length); i++)
                                        {
                                            hex.append(String.format("%02x", raw[i]));
                                        }

                                        Log.d("RadarDiag", "RESP fail op=" + Byte.toUnsignedInt(operationCode)
                                                + " len=" + raw.length + " : " + e + " hex=" + hex);
                                    }

                                    throw e;
                                }

                                break;
                            }

                            case 4:
                            {
                                byte eventCode = payload.get();
                                onEvent(eventCode, Protocol18Deserializer.deserializeEventData(payload));

                                if (++cntEvent % 300 == 0)
                                {
                                    Log.d("RadarDiag", "PKT req=" + cntRequest + " resp=" + cntResponse
                                            + " evt=" + cntEvent + " err=" + cntError);
                                }

                                break;
                            }

                            default:
                                byte eventCode2 =  payload.get();
                                System.out.println("eventCode2: " + eventCode2);

                                //onOther(eventCode2 , Protocol16Deserializer.deserialize(payload, 1));


                                //onResponse(eventCode2, Protocol16Deserializer.deserializeOperationResponse(payload));

                                break;
                        }
                        break;

                    default:
                        p.position(p.position() + commandLength - commandHeaderLength);
                        break;
                }
            }
        }
        catch (Exception e)
        {
            cntError++;

            if (cntError <= 6)
            {
                Log.d("RadarDiag", "PKT err#" + cntError + " req=" + cntRequest + " resp=" + cntResponse
                        + " evt=" + cntEvent + " : " + e);
            }
        }
    }

    protected void onEvent(byte code , HashMap<Object, Object> Parameters)
    {
        // Albion 把真正的事件码放在参数 252（参考 Albion.Network 的 ParseEventCode），
        // 消息头里那个字节只在某些情况下有意义（3 = 移动同步）。
        int Code = Byte.toUnsignedInt(code);
        Object wireEventCode = Parameters.get(252);

        if (wireEventCode instanceof Number)
        {
            int eventCode = ((Number) wireEventCode).intValue();

            if (eventCode > 0 && eventCode < EventCodes.values().length)
            {
                Code = eventCode;
            }
        }

        try
        {
            if(Code == 3)
            {
                int id = Utils.getNumber(Parameters.get(0));
                byte[] bytes = Utils.getByteArray(Parameters.get(1));

                position[0]= Float.intBitsToFloat(Utils.bytesToInt(bytes, 9));
                position[1] = Float.intBitsToFloat(Utils.bytesToInt(bytes, 13));

                if (diagMoves++ < 5)
                {
                    StringBuilder hex = new StringBuilder();

                    for (int i = 0; i < Math.min(28, bytes == null ? 0 : bytes.length); i++)
                    {
                        hex.append(String.format("%02x", bytes[i]));
                    }

                    Log.d("RadarDiag", "MOVE id=" + id + " len=" + (bytes == null ? -1 : bytes.length)
                            + " pos=" + position[0] + "," + position[1] + " keys=" + Parameters.keySet()
                            + " hex=" + hex);
                }

                newPlayerEvent.updatePlayerPosition(id, position[0], position[1]);
                newMobEvent.updateMobPosition(id, position[0], position[1]);
                MainHandler.getInstance().playersHandler.updateLocalPlayerPositionById(id, position[0], position[1]);
            }
            else
            {
                EventCodes eventCode = EventCodes.values()[Code];

                switch (eventCode)
                {
                    case Leave:
                        newPlayerEvent.removePlayer(Parameters);
                        newMobEvent.removeMob(Parameters);
                        newHarvestableEvent.removeHarvestable(Parameters);
                        newFishingZoneEvent.removeFishingZoneObject(Parameters);
                        newChestEvent.removeChest(Parameters);
                        break;

                    case NewCharacter:
                        newPlayerEvent.NewCharacter(Parameters);
                        break;

                    case Mounted:
                        newPlayerEvent.Mounted(Parameters);
                        break;

                    case NewMountObject:
                        newPlayerEvent.NewMount(Parameters);
                        break;

                    case InCombatStateUpdate:
                        newPlayerEvent.InCombatStateUpdate(Parameters);
                        break;

                    case HealthUpdate:
                        newPlayerEvent.HealthUpdate(Parameters);
                        break;

                    case Attack:
                        newPlayerEvent.Attack(Parameters);
                        break;

                    case NewSimpleItem:
                    case NewEquipmentItem:
                    case NewFurnitureItem:
                    case NewJournalItem:
                    case NewLaborerItem:
                        newHarvestableEvent.NewSimpleItem(Parameters, eventCode);
                        break;

                    case UpdateMoney:
                        newPlayerEvent.UpdateMoney(Parameters);
                        break;

                    case MobChangeState:
                        newMobEvent.MobChangeState(Parameters);
                        break;

                    case NewMob:
                        newMobEvent.NewMob(Parameters);
                        break;

                    case NewSimpleHarvestableObjectList:
                        newHarvestableEvent.NewSimpleHarvestableObjectList(Parameters);
                        break;

                    case NewHarvestableObject:
                        newHarvestableEvent.NewHarvestableObject(Parameters);
                        break;

                    case HarvestableChangeState:
                        newHarvestableEvent.HarvestableChangeState(Parameters);
                        break;

                    case HarvestStart:
                        newHarvestableEvent.HarvestStart(Parameters);
                        break;

                    case HarvestFinished:
                        newHarvestableEvent.HarvestFinished(Parameters);
                        break;

                    case NewFloatObject:
                        newHarvestableEvent.NewFloatObject(Parameters);
                        break;

                    case NewTreasureChest:
                        newChestEvent.NewTreasureChest(Parameters);
                        break;

                    case NewFishingZoneObject:
                        newFishingZoneEvent.NewFishingZoneObject(Parameters);
                        break;

                    case FishingStart:
                        newFishingZoneEvent.FishingStart(Parameters);
                        break;

                    case FishingFinished:
                        newFishingZoneEvent.FishingFinished(Parameters);
                        break;

                    case FishingCatch:
                        newFishingZoneEvent.FishingCatch(Parameters);
                        break;

                    case FishingCast:
                        newFishingZoneEvent.FishingCast(Parameters);
                        break;

                    case FishingMiniGame:
                        newFishingZoneEvent.FishingMiniGame(Parameters);
                        break;

                    case InventoryPutItem:
                        newPlayerEvent.InventoryPutItem(Parameters);
                        break;

                    case NewBuilding:
                        newPlayerEvent.NewBuilding(Parameters);
                        break;

                    case NewLoot:
                        newOthersEvent.NewLoot(Parameters);
                        break;

                    case NewExit:
                        newOthersEvent.NewExit(Parameters);
                        break;

                    case NewRandomDungeonExit:
                        newOthersEvent.NewRandomDungeonExit(Parameters);
                        break;

                    default:
                        break;

                }
            }
        }
        catch (Exception ex )
        {
            Log.d("onEvent","Code: " + Code + " Error:" + ex.toString());
        }

        EventBus.getDefault().post("refresh");
    }

    private static int diagOps = 0;

    protected void onRequest(byte code , HashMap<Object, Object> Parameters)
    {
        // Albion 的 OperationRequest 里，消息头字节恒为 1，
        // 真正的操作码在参数 253。
        int Code = Byte.toUnsignedInt(code);
        Object wireOperationCode = Parameters.get(253);

        if (wireOperationCode instanceof Number)
        {
            int operationCode = ((Number) wireOperationCode).intValue();

            if (operationCode > 0 && operationCode < OperationCodes.values().length)
            {
                Code = operationCode;
            }
        }

        try
        {
            OperationCodes operationCode = OperationCodes.values()[Code];

            if (diagOps++ < 25)
            {
                Log.d("RadarDiag", "OPREQ hdr=" + Byte.toUnsignedInt(code) + " p253=" + wireOperationCode
                        + " ->" + operationCode + " keys=" + Parameters.keySet());
            }

            switch (operationCode)
            {
                case Move:
                    float[] pos = Utils.getFloats(Parameters.get(1));
                    float angle = Utils.getFloat(Parameters.get(2));
                    float[] targetpos = Utils.getFloats(Parameters.get(3));
                    float speed = Utils.getFloat(Parameters.get(4));

                    MainHandler.getInstance().playersHandler.updateLocalPlayerPosition(pos[0], pos[1], angle, targetpos[0], targetpos[1], speed);
                    // 不再按 80 码裁剪：服务端只会下发视野内的实体，主动删除只会让雷达"走一路丢一路"。
                    // 实体离开视野时服务端会发 Leave/Remove，换图时 clearAll() 也会清空。
                    break;

                case ChangeCluster :
                    MainHandler.getInstance().clearAll();
                    break;

                case AuctionGetOffers:
                    newAuctionEvent.AuctionGetOffersRequest(Parameters);
                    break;
            }
        }
        catch (Exception ex)
        {
            Log.d("onRequest","Code: " + Code);
        }

        EventBus.getDefault().post("refresh");
    }

    private static void probeHandshake(java.util.HashMap<Object, Object> params)
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

    private static int diagResponses = 0;

    private static int diagMoves = 0;

    private static int cntRequest = 0;
    private static int cntResponse = 0;
    private static int cntEvent = 0;
    private static int cntError = 0;

    protected void onResponse(byte code , HashMap<Object, Object> Parameters)
    {
        int Code = Byte.toUnsignedInt(code);
        Object wireOperationCode = Parameters.get(253);

        if (wireOperationCode instanceof Number)
        {
            int operationCode = ((Number) wireOperationCode).intValue();

            if (operationCode > 0 && operationCode < OperationCodes.values().length)
            {
                Code = operationCode;
            }
        }

        try
        {
            OperationCodes operationCode = OperationCodes.values()[Code];

            if (diagResponses++ < 15)
            {
                Log.d("RadarDiag", "OPRESP hdr=" + Byte.toUnsignedInt(code) + " p253=" + wireOperationCode
                        + " ->" + operationCode + " size=" + Parameters.size() + " keys=" + Parameters.keySet());
            }

            // Join 响应在 1.32 里不一定带 253，只靠操作码分派会漏掉，导致本地玩家 id 一直是 0
            if (Parameters.size() > 40 && Parameters.containsKey(0) && Parameters.containsKey(2)
                    && Parameters.containsKey(8))
            {
                Log.d("RadarDiag", "JOIN by shape: size=" + Parameters.size() + " p253=" + wireOperationCode);
                newPlayerEvent.updateLocalPlayer(Parameters);
                MainHandler.getInstance().playersHandler.localPlayerId();
            }

            switch (operationCode)
            {
                case Join:
                    newPlayerEvent.updateLocalPlayer(Parameters);
                    break;

                case AuctionGetOffers:
                    newAuctionEvent.AuctionGetOffersResponse(Parameters);
                    break;

                default:
                    //Log.d("onResponse operationCode","Code: " + operationCode);

                    break;
            }
        }
        catch (Exception ex)
        {
            Log.d("onResponse","Code: " + Code);
        }

        EventBus.getDefault().post("refresh");
    }

    protected void onOther(byte code , HashMap<Object, Object> Parameters)
    {
        short Code = (short)Parameters.get(253);

    }
}
