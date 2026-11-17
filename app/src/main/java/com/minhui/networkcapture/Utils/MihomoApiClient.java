package com.minhui.networkcapture.Utils;

import android.os.Handler;
import android.os.Looper;

import com.minhui.vpn.proxy.UpstreamProxyConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * mihomo（Clash Meta 内核）REST API 客户端。
 *
 * 前提：内置 mihomo 子进程监听 external-controller 127.0.0.1:9090（不设 secret）。
 * 提供三个能力，全部在后台线程执行、主线程回调：
 * 1. fetchSelectorGroup  —— GET /proxies，自动找到第一个 Selector 分组（节点列表 + 当前选中）
 * 2. selectNode          —— PUT /proxies/{group}，切换节点
 * 3. refreshAllProviders —— GET /providers/proxies 后逐个 PUT，立即重新拉取所有订阅
 */
public class MihomoApiClient
{
    public interface Callback<T>
    {
        void onSuccess(T result);

        void onError(String message);
    }

    /** 一个 select 类型代理分组的快照。 */
    public static class NodeGroup
    {
        public final String groupName;
        public final String now;
        public final List<String> all;

        NodeGroup(String groupName, String now, List<String> all)
        {
            this.groupName = groupName;
            this.now = now;
            this.all = all;
        }
    }

    private static final int CONTROLLER_PORT = 9090;
    private static final int TIMEOUT_MS = 5000;

    /** 我们生成的配置里 Selector 分组的名字。 */
    private static final String PREFERRED_GROUP = "节点选择";
    /** mihomo 自动生成的全局组，只含 DIRECT/REJECT/各组名，不能当作节点列表。 */
    private static final String GLOBAL_GROUP = "GLOBAL";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MihomoApiClient()
    {
    }

    private static String baseUrl()
    {
        return "http://" + UpstreamProxyConfig.getHost() + ":" + CONTROLLER_PORT;
    }

    /** 拉取第一个 Selector 分组（含节点全列表与当前选中节点）。 */
    public static void fetchSelectorGroup(Callback<NodeGroup> callback)
    {
        executor.execute(() ->
        {
            try
            {
                String body = request("GET", "/proxies", null);
                JSONObject proxies = new JSONObject(body).optJSONObject("proxies");

                if (proxies == null)
                {
                    postError(callback, "empty /proxies response");
                    return;
                }

                NodeGroup fallback = null;
                NodeGroup preferred = null;
                Iterator<String> names = proxies.keys();

                while (names.hasNext())
                {
                    String name = names.next();
                    JSONObject proxy = proxies.optJSONObject(name);

                    if (proxy == null || !"Selector".equals(proxy.optString("type")))
                    {
                        continue;
                    }

                    JSONArray allArray = proxy.optJSONArray("all");
                    List<String> all = new ArrayList<>();

                    if (allArray != null)
                    {
                        for (int i = 0; i < allArray.length(); i++)
                        {
                            all.add(allArray.getString(i));
                        }
                    }

                    NodeGroup group = new NodeGroup(name, proxy.optString("now"), all);

                    if (PREFERRED_GROUP.equals(name) && !all.isEmpty())
                    {
                        preferred = group;
                    }
                    else if (!GLOBAL_GROUP.equals(name) && !all.isEmpty() && fallback == null)
                    {
                        fallback = group;
                    }
                }

                if (preferred != null)
                {
                    postSuccess(callback, preferred);
                }
                else if (fallback != null)
                {
                    postSuccess(callback, fallback);
                }
                else
                {
                    postError(callback, "no Selector group found");
                }
            }
            catch (Exception e)
            {
                postError(callback, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    /** 把指定 Selector 分组切换到目标节点。 */
    public static void selectNode(String groupName, String nodeName, Callback<String> callback)
    {
        executor.execute(() ->
        {
            try
            {
                JSONObject payload = new JSONObject();
                payload.put("name", nodeName);

                String path = "/proxies/" + URLEncoder.encode(groupName, "UTF-8");
                request("PUT", path, payload.toString());
                postSuccess(callback, nodeName);
            }
            catch (Exception e)
            {
                postError(callback, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    /** 立即刷新所有代理 provider（重新拉取订阅），返回成功刷新的个数。 */
    public static void refreshAllProviders(Callback<Integer> callback)
    {
        executor.execute(() ->
        {
            try
            {
                String body = request("GET", "/providers/proxies", null);
                JSONObject providers = new JSONObject(body).optJSONObject("providers");

                if (providers == null || providers.length() == 0)
                {
                    postError(callback, "no proxy providers found");
                    return;
                }

                int updated = 0;
                Iterator<String> names = providers.keys();
                String lastError = null;

                while (names.hasNext())
                {
                    String name = names.next();

                    try
                    {
                        request("PUT", "/providers/proxies/" + URLEncoder.encode(name, "UTF-8"), null);
                        updated++;
                    }
                    catch (IOException e)
                    {
                        lastError = name + ": " + e.getMessage();
                    }
                }

                if (updated > 0)
                {
                    postSuccess(callback, updated);
                }
                else
                {
                    postError(callback, lastError == null ? "update failed" : lastError);
                }
            }
            catch (Exception e)
            {
                postError(callback, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    /** 同步 HTTP 请求（仅在 executor 线程调用）。返回响应体；非 2xx 抛 IOException。 */
    private static String request(String method, String path, String jsonBody) throws IOException
    {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl() + path).openConnection();

        try
        {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);

            if (jsonBody != null)
            {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");

                try (OutputStream out = connection.getOutputStream())
                {
                    out.write(jsonBody.getBytes("UTF-8"));
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            String body = "";

            if (stream != null)
            {
                try (InputStream input = stream)
                {
                    body = readAll(input);
                }
            }

            if (status < 200 || status >= 300)
            {
                throw new IOException("HTTP " + status + (body.isEmpty() ? "" : ": " + body));
            }

            return body;
        }
        finally
        {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException
    {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;

        while ((read = stream.read(chunk)) != -1)
        {
            buffer.write(chunk, 0, read);
        }

        return new String(buffer.toByteArray(), "UTF-8");
    }

    private static <T> void postSuccess(Callback<T> callback, T result)
    {
        mainHandler.post(() -> callback.onSuccess(result));
    }

    private static <T> void postError(Callback<T> callback, String message)
    {
        mainHandler.post(() -> callback.onError(message));
    }
}
