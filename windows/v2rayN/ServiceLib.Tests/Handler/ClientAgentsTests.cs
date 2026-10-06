namespace ServiceLib.Tests.Handler;

/// <summary>Same cases as Android ClientAgentsTest.</summary>
public class ClientAgentsTests
{
    [Test]
    public async Task RetryOnRefusalOrRedirect()
    {
        await ClientAgents.Retryable(403).Should().BeTrue();
        await ClientAgents.Retryable(400).Should().BeTrue();
        await ClientAgents.Retryable(503).Should().BeTrue();
        await ClientAgents.Retryable(302).Should().BeTrue();
    }

    [Test]
    public async Task NoRetryWhenNothingToGain()
    {
        await ClientAgents.Retryable(null).Should().BeFalse();
        await ClientAgents.Retryable(404).Should().BeFalse();
        await ClientAgents.Retryable(410).Should().BeFalse();
        await ClientAgents.Retryable(200).Should().BeFalse();
    }

    [Test]
    public async Task HappFirst()
    {
        await ClientAgents.Fallback[0].StartsWith("Happ/").Should().BeTrue();
    }
}
