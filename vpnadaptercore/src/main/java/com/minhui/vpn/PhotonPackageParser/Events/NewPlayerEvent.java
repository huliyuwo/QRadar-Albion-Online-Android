package com.minhui.vpn.PhotonPackageParser.Events;

import android.util.Log;

import com.google.gson.Gson;
import com.minhui.vpn.Handlers.MainHandler;
import com.minhui.vpn.PhotonPackageParser.Utils;

import java.util.HashMap;

public class NewPlayerEvent
{
    final Gson gson = new Gson();

    public  NewPlayerEvent()
    {

    }

    public void removePlayer(HashMap<Object, Object> Parameters)
    {
        MainHandler.getInstance().playersHandler.removePlayer(Utils.getNumber(Parameters.get(0)));
    }

    public void NewCharacter(HashMap<Object, Object> Parameters)
    {
        // 当前协议（1.32）的 NewCharacter 参数（与 PC 版 web/scripts/handlers/PlayersHandler.js 对齐）：
        // 0=ObjectId, 1=名字, 8=公会, 40=装备, 51=联盟, 53=阵营(0=被动 1-6=阵营城 255=敌对)。
        // 坐标不在这里（玩家的 Move 坐标是 XOR 加密的，见 PlayerMoveDecoder）。
        int id = Utils.getNumber(Parameters.get(0));
        String name = Utils.getString(Parameters.get(1));
        String guildName = Utils.getString(Parameters.get(8));
        String allianceName = Parameters.get(51) == null ? null : Utils.getString(Parameters.get(51));
        int faction = Utils.getNumber(Parameters.get(53));

        MainHandler.getInstance().playersHandler.addOrUpdatePlayer(id, name, guildName, allianceName,
                faction, 0, 0, false, 0, 0);

        if (diagNewCharacterCount++ < 12)
        {
            Log.d("RadarDiag", "NewCharacter id=" + id + " name=" + name + " guild=" + guildName
                    + " alliance=" + allianceName + " faction=" + faction
                    + " keys=" + Parameters.keySet());

            if (diagNewCharacterCount <= 2)
            {
                // 诊断：NewCharacter 里是否也带坐标（PC 版不用它，但移动端协议可能不同）
                dumpParameters(Parameters, "NEWCHARPARAM");
            }
        }
    }

    private static int diagNewCharacterCount;

    public void updatePlayerPosition(int id, float posX , float posY)
    {
        MainHandler.getInstance().playersHandler.updatePlayerPosition(id, posX, posY);
    }

    public void updateLocalPlayer(HashMap<Object, Object> parameters)
    {
        // 当前协议的 Join 响应（真机 2026-11 dump 确认）：
        //   0=ObjectId, 2=用户名, 8=地图 cluster, 9=入场坐标 [x,y]（Object[2] of Float）,
        //   58=公会, 79=联盟；旧代码用的 64 是别的东西（永远是 0,0）。
        int id = Utils.getNumber(parameters.get(0));
        String cName = Utils.getString(parameters.get(2));
        String cluster = Utils.getString(parameters.get(8));
        String guildName = Utils.getString(parameters.get(58));
        String allianceName = parameters.get(79) == null ? null : Utils.getString(parameters.get(79));

        float[] pos = safeFloats(parameters.get(9));

        if (pos == null || !isWorldPosition(pos[0], pos[1]))
        {
            // 兜底：在参数里找第一对“像坐标”的 float（防止下一个游戏版本再挪字段）
            pos = findPositionInParameters(parameters);
        }

        float posX = pos != null ? pos[0] : 0;
        float posY = pos != null ? pos[1] : 0;

        MainHandler.getInstance().playersHandler.updateLocalPlayer(id, null, cName, guildName,
                cluster, posX, posY, 0, 0, 0, 0, 0, 0);

        if (allianceName != null)
        {
            MainHandler.getInstance().playersHandler.updateLocalPlayerAlliance(allianceName);
        }

        if (diagJoinCount++ < 3)
        {
            Log.d("RadarDiag", "JOIN id=" + id + " name=" + cName + " guild=" + guildName
                    + " alliance=" + allianceName + " pos=" + posX + "," + posY
                    + " cluster=" + cluster + " keys=" + parameters.keySet());
        }

        if (diagJoinCount == 1)
        {
            dumpParameters(parameters);
        }
    }

    private static boolean isWorldPosition(float x, float y)
    {
        return Float.isFinite(x) && Float.isFinite(y)
                && Math.abs(x) < 5000f && Math.abs(y) < 5000f
                && !(x == 0f && y == 0f);
    }

    /** 在参数表里找第一对合理的坐标（Object[] of Float）。 */
    private static float[] findPositionInParameters(HashMap<Object, Object> parameters)
    {
        for (Object value : parameters.values())
        {
            if (!(value instanceof Object[]))
            {
                continue;
            }

            Object[] array = (Object[]) value;

            if (array.length < 2 || !(array[0] instanceof Float) || !(array[1] instanceof Float))
            {
                continue;
            }

            float x = (Float) array[0];
            float y = (Float) array[1];

            if (isWorldPosition(x, y))
            {
                return new float[]{x, y};
            }
        }

        return null;
    }

    /** 诊断：把 Join 响应的参数类型/取值打出来，用于确认坐标（以及可能存在的 XorCode）位置。 */
    private void dumpParameters(HashMap<Object, Object> parameters)
    {
        dumpParameters(parameters, "JOINPARAM");
    }

    private void dumpParameters(HashMap<Object, Object> parameters, String tag)
    {
        for (Object key : parameters.keySet())
        {
            Object value = parameters.get(key);
            StringBuilder builder = new StringBuilder(tag).append(' ').append(key).append('=');

            if (value == null)
            {
                builder.append("null");
            }
            else if (value instanceof Object[])
            {
                Object[] array = (Object[]) value;
                builder.append("Object[").append(array.length).append("]{");

                for (int i = 0; i < Math.min(6, array.length); i++)
                {
                    builder.append(array[i]);
                    if (i < Math.min(6, array.length) - 1)
                    {
                        builder.append(',');
                    }
                }

                builder.append('}');
            }
            else if (value instanceof byte[])
            {
                builder.append("byte[").append(((byte[]) value).length).append(']');
            }
            else
            {
                builder.append(value).append('(').append(value.getClass().getSimpleName()).append(')');
            }

            Log.d("RadarDiag", builder.toString());
        }
    }

    private static int diagJoinCount;

    /** 安全取 float[2]：字段缺失或类型不对时返回 null，不再让整个 Join 处理失败。 */
    private static float[] safeFloats(Object value)
    {
        try
        {
            float[] result = Utils.getFloats(value);

            return result != null && result.length >= 2 ? result : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public void Mounted(HashMap<Object, Object> parameters)
    {
        int id = Utils.getNumber(parameters.get(0));
        String ten = Utils.getString(parameters.get(8));
        boolean mounted = Utils.getBoolean(parameters.get(11));

        if(mounted)
        {
            MainHandler.getInstance().playersHandler.updatePlayerMounted(id, true);
        }
        else if (ten != null && ten.equals("-1"))
        {
            MainHandler.getInstance().playersHandler.updatePlayerMounted(id, true);
        }
        else
        {
            MainHandler.getInstance().playersHandler.updatePlayerMounted(id, false);
        }
    }

    public void InCombatStateUpdate(HashMap<Object, Object> parameters)
    {
        int playerId = Utils.getNumber(parameters.get(0));
        boolean playerAttacking =  Utils.getBoolean(parameters.get(1));
        boolean enemyAttacking  =  Utils.getBoolean(parameters.get(2));
    }

    public void InventoryPutItem(HashMap<Object, Object> parameters)
    {
        int id = Utils.getNumber(parameters.get(0));
    }

    public void NewMount(HashMap<Object, Object> parameters)
    {
        int id =  Utils.getNumber(parameters.get(0));
        float[] pos =  Utils.getFloats(parameters.get(3));
        byte[] items = Utils.getByteArray(parameters.get(5));
    }

    public void UpdateMoney(HashMap<Object, Object> parameters)
    {
        int id =  Utils.getNumber(parameters.get(0));
        String silver =  Utils.getString(parameters.get(1));

        int len = silver.length() - 4;
        long currentSilver = Long.parseLong(silver.substring(0, len));
    }

    public void HealthUpdate(HashMap<Object, Object> parameters)
    {
        // 与 PC 版 PlayersHandler.UpdatePlayerHealth 对齐：0=玩家 id, 2=当前血量, 3=最大血量。
        // 少部分包把目标 id 放在 6，这里两个位置都尝试。
        int playerId = Utils.getNumber(parameters.get(0));
        float currentHealth = Utils.getFloat(parameters.get(2));
        float maxHealth = Utils.getFloat(parameters.get(3));

        if (currentHealth <= 0)
        {
            currentHealth = Utils.getNumber(parameters.get(3));
        }

        MainHandler.getInstance().playersHandler.updatePlayerHealth(playerId, currentHealth);

        if (maxHealth > 0)
        {
            MainHandler.getInstance().playersHandler.updatePlayerMaxHealth(playerId, maxHealth);
        }
    }

    public void Attack(HashMap<Object, Object> parameters)
    {
        int attackerID = Utils.getNumber(parameters.get(0));
        int targetID = Utils.getNumber(parameters.get(2));
    }

    public void NewBuilding(HashMap<Object, Object> parameters)
    {
        int packetID =  Utils.getNumber(parameters.get(0));
        String name =  Utils.getString(parameters.get(3));
        float[] pos =  Utils.getFloats(parameters.get(4));
    }
}
