using NebulaUpdater.Core.Models;

namespace NebulaUpdater.Core.Services;

/// <summary>Ping / 连通性探测。</summary>
public static class PingService
{
    /// <summary>ICMP Ping 一个主机，返回毫秒数或错误</summary>
    public static async Task<PingResult> PingHostAsync(string host, CancellationToken ct = default)
    {
        try
        {
            using var ping = new System.Net.NetworkInformation.Ping();
            var reply = await ping.SendPingAsync(host, 5000).WaitAsync(ct).ConfigureAwait(false);
            return reply.Status == System.Net.NetworkInformation.IPStatus.Success
                ? new PingResult(true, $"{reply.RoundtripTime} ms")
                : new PingResult(false, reply.Status.ToString(), reply.Status.ToString());
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            return new PingResult(false, ex.Message, ex.Message);
        }
    }

    /// <summary>
    /// 查询 MC 服务器状态。
    ///
    /// 走扩展包的 mcstatus（嵌入式 Python + mcstatus 库），
    /// 与 Android 端输出格式保持一致。这部分沿用旧版实现，不改写。
    /// </summary>
    public static string QueryMcServer() => PyExtManager.QueryPing();
}