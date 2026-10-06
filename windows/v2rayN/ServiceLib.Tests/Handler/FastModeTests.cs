namespace ServiceLib.Tests.Handler;

/// <summary>Same cases as Android FastModeTest.</summary>
public class FastModeTests
{
    [Test]
    public async Task LogLevel()
    {
        await FastMode.LogLevel(true, "debug").Should().BeEqualTo("warning");
        await FastMode.LogLevel(true, "info").Should().BeEqualTo("warning");
        await FastMode.LogLevel(true, "error").Should().BeEqualTo("error");
        await FastMode.LogLevel(false, "debug").Should().BeEqualTo("debug");
        await FastMode.LogLevel(false, null).Should().BeEqualTo("warning");
        await FastMode.LogLevel(true, "").Should().BeEqualTo("warning");
    }

    [Test]
    public async Task CheckInterval()
    {
        await FastMode.CheckInterval(TimeSpan.FromSeconds(20), true).Should().BeEqualTo(TimeSpan.FromSeconds(40));
        await FastMode.CheckInterval(TimeSpan.FromSeconds(20), false).Should().BeEqualTo(TimeSpan.FromSeconds(20));
    }
}
