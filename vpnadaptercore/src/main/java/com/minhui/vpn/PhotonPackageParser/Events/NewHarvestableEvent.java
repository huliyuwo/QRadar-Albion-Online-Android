package com.minhui.vpn.PhotonPackageParser.Events;

import android.util.Log;

import com.minhui.vpn.Handlers.MainHandler;
import com.minhui.vpn.PhotonPackageParser.Utils;
import com.minhui.vpn.PhotonPackageParser.enumerations.EventCodes;

import java.util.HashMap;

public class NewHarvestableEvent
{
    private static int tierDiagCount = 0;

    public void HarvestableChangeState(HashMap<Object, Object> parameters)
    {
        int id =  Utils.getNumber(parameters.get(0));
        int charges =  Utils.getNumber(parameters.get(1));
        int enchantment  =  Utils.getNumber(parameters.get(2));

        MainHandler.getInstance().harvestablesHandler.updateHarvestable(id,charges,enchantment);
    }

    private static int diagHarvestCount;
    private static int harv1DiagCount;

    /** 临时诊断：把 NewHarvestableObject 的参数原样打出来，用于确认 1.32 的字段下标。 */
    private void logHarvestParams(HashMap<Object, Object> parameters)
    {
        if (diagHarvestCount++ >= 3)
        {
            return;
        }

        StringBuilder builder = new StringBuilder();

        for (Object key : parameters.keySet())
        {
            Object value = parameters.get(key);
            builder.append(key).append('=');

            if (value == null)
            {
                builder.append("null");
            }
            else if (value instanceof Object[])
            {
                Object[] array = (Object[]) value;
                builder.append("arr[").append(array.length).append("]{");
                for (int i = 0; i < Math.min(4, array.length); i++)
                {
                    builder.append(array[i]).append(',');
                }
                builder.append('}');
            }
            else
            {
                builder.append(value).append('(').append(value.getClass().getSimpleName()).append(')');
            }

            builder.append(' ');
        }

        Log.d("RadarDiag", "HARV " + builder);
    }

    public void NewHarvestableObject(HashMap<Object, Object> parameters)
    {
        logHarvestParams(parameters);

        int id = Utils.getNumber(parameters.get(0));
        int type = Utils.getNumber(parameters.get(5));
        // 服务器 tier 是 1 基（T4 资源 = 4）。2026-11-27 在 Creag Morr（游戏内地图资源 IV–VI）实测：
        // 单对象 rawTier=4、批量列表 rawTier=4/6，均与游戏内等级一致，因此这里不做任何换算，
        // 存储层与渲染层统一使用 1 基（渲染层直接显示，不再 +1）。
        int tier = Utils.getNumber(parameters.get(7));
        float[] location = Utils.getFloats(parameters.get(8));
        int charges = Utils.getNumber(parameters.get(10));;
        int  enchant = Utils.getNumber(parameters.get(11));

        // 等级基准取证：这里必须打「服务器原样 tier」，不要做任何换算。
        if (harv1DiagCount++ < 40)
        {
            Log.d("RadarDiag", "HARV1 id=" + id + " type=" + type + " rawTier=" + tier
                    + " enchant=" + enchant + " charges=" + charges
                    + " pos=" + (location != null && location.length >= 2
                    ? (location[0] + "," + location[1]) : "?"));
        }

        MainHandler.getInstance().harvestablesHandler.removeHarvestable(id);
        MainHandler.getInstance().harvestablesHandler.addHarvestable(id, type, tier, location[0], location[1], charges, enchant);
    }
    private static int diagListCount;

    public void NewSimpleHarvestableObjectList(HashMap<Object, Object> parameters)
    {
        int[] a0 = Utils.getKnownArray(parameters.get(0));

        if (diagListCount++ < 2)
        {
            StringBuilder dump = new StringBuilder("HARVLIST size=" + parameters.size());

            for (Object key : parameters.keySet())
            {
                Object value = parameters.get(key);
                dump.append(" [").append(key).append('=');

                if (value instanceof byte[])
                {
                    byte[] arr = (byte[]) value;
                    dump.append("b[").append(arr.length).append("]{");
                    for (int i = 0; i < Math.min(5, arr.length); i++)
                    {
                        dump.append(arr[i]).append(',');
                    }
                    dump.append('}');
                }
                else if (value instanceof short[])
                {
                    short[] arr = (short[]) value;

                    dump.append("s[").append(arr.length).append("]{");

                    for (int i = 0; i < Math.min(5, arr.length); i++)
                    {
                        dump.append(arr[i]).append(',');
                    }
                    dump.append('}');
                }
                else if (value instanceof int[])
                {
                    int[] arr = (int[]) value;
                    dump.append("i[").append(arr.length).append("]{");
                    for (int i = 0; i < Math.min(5, arr.length); i++)
                    {
                        dump.append(arr[i]).append(',');
                    }
                    dump.append('}');
                }
                else if (value instanceof Object[])
                {
                    Object[] arr = (Object[]) value;
                    dump.append("o[").append(arr.length).append("]{");
                    for (int i = 0; i < Math.min(5, arr.length); i++)
                    {
                        dump.append(arr[i]).append(',');
                    }
                    dump.append('}');
                }
                else
                {
                    dump.append(value).append('(').append(value == null ? "null" : value.getClass().getSimpleName()).append(')');
                }

                dump.append(']');
            }

            Log.d("RadarDiag", dump.toString());
        }

        if(a0.length==0)
        {
            return;
        }

        if (parameters.size() != 6)
        {
            return;
        }

        int[] a1 = Utils.getKnownArray(parameters.get(1));
        int[] a2 = Utils.getKnownArray(parameters.get(2));
        float[] a3 =  Utils.getFloats(parameters.get(3));
        int[] a4 = Utils.getKnownArray(parameters.get(4));

        for (int i = 0; i < a0.length; i++)
        {
            int id = a0[i];
            int type = a1[i];
            // 批量列表的 tier 与单对象事件同为 1 基（实测 T4=4），不做换算。
            int tier = a2[i];

            // 等级基准取证：批量列表事件同样打服务器原样 tier。
            if (tierDiagCount++ < 40)
            {
                Log.d("RadarDiag", "HARV2 id=" + id + " type=" + type + " rawTier=" + tier
                        + " charges=" + a4[i]);
            }
            float posX = a3[i * 2];
            float posY = a3[i * 2 + 1];
            int charges = a4[i];

            MainHandler.getInstance().harvestablesHandler.removeHarvestable(id);
            MainHandler.getInstance().harvestablesHandler.addHarvestable(id, type, tier, posX, posY, charges,0);
        }
    }
    public void removeHarvestable(HashMap<Object, Object> parameters )
    {
        int id = Utils.getNumber(parameters.get(0));

        MainHandler.getInstance().harvestablesHandler.removeHarvestable(id);
    }

    public void HarvestStart(HashMap<Object, Object> parameters)
    {
        int harvestableId = Utils.getNumber(parameters.get(0));
        float time = Utils.getFloat(parameters.get(5));
    }

    public void HarvestFinished(HashMap<Object, Object> parameters)
    {
        int playerId = Utils.getNumber(parameters.get(0));
        int harvestableId = Utils.getNumber(parameters.get(3));
        int gathered = Utils.getNumber(parameters.get(4));
        int yield = Utils.getNumber(parameters.get(5));
        int premium = Utils.getNumber(parameters.get(6));
        int charges = Utils.getNumber(parameters.get(7));
    }

    public void NewSimpleItem(HashMap<Object, Object> parameters, EventCodes eventCode)
    {
        if(eventCode != EventCodes.NewSimpleItem)
        {
            Log.d("NewSimpleItem","eventCode: " + eventCode.name());
        }

        int objectID = Utils.getNumber(parameters.get(0));
        int itemID = Utils.getNumber(parameters.get(1));
        int amount = Utils.getNumber(parameters.get(2));
        int avgValue = Utils.getNumber(parameters.get(4)) / 10000;
        String nickname = Utils.getString(parameters.get(5));
    }

    public void NewFloatObject(HashMap<Object, Object> parameters)
    {
        int bobberID = Utils.getNumber(parameters.get(0));
        float[] bobberPos =  Utils.getFloats(parameters.get(1));
        float angleFromPlayer = Utils.getFloat(parameters.get(2));
        int playerId = Utils.getNumber(parameters.get(3));
        int fishingState = Utils.getNumber(parameters.get(4));

        String readableState;

        switch (fishingState)
        {
            case 1:
                readableState = "Floating";
                break;
            case 2:
                readableState = "Bitten";
                break;
            case 3:
                readableState = "Minigame";
                break;
            case 4:
                readableState = "Catched";
                break;
            case 5:
                readableState = "Lost";
                break;
            default:
                readableState = "";
                break;
        }
    }
}