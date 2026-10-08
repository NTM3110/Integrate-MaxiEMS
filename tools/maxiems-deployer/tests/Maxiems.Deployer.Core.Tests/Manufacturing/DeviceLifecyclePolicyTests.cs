using Maxiems.Deployer.Core.Manufacturing;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests.Manufacturing;

[TestClass]
[TestCategory("Manufacturing")]
public sealed class DeviceLifecyclePolicyTests
{
    [TestMethod]
    [DataRow(DeviceLifecycleState.Registered, DeviceLifecycleState.Inspecting)]
    [DataRow(DeviceLifecycleState.Inspecting, DeviceLifecycleState.ReadyForDeployment)]
    [DataRow(DeviceLifecycleState.ReadyForDeployment, DeviceLifecycleState.Deploying)]
    [DataRow(DeviceLifecycleState.Deploying, DeviceLifecycleState.DeploymentVerified)]
    [DataRow(DeviceLifecycleState.DeploymentVerified, DeviceLifecycleState.ReadyForWarehouse)]
    [DataRow(DeviceLifecycleState.Deploying, DeviceLifecycleState.ReworkRequired)]
    [DataRow(DeviceLifecycleState.ReworkRequired, DeviceLifecycleState.Inspecting)]
    [DataRow(DeviceLifecycleState.ReadyForWarehouse, DeviceLifecycleState.Inspecting)]
    public void AllowsManufacturingAndReworkTransitions(DeviceLifecycleState from, DeviceLifecycleState to)
    {
        Assert.IsTrue(DeviceLifecyclePolicy.CanTransition(from, to));
        DeviceLifecyclePolicy.EnsureTransition(from, to);
    }

    [TestMethod]
    [DataRow(DeviceLifecycleState.Registered, DeviceLifecycleState.Deploying)]
    [DataRow(DeviceLifecycleState.Inspecting, DeviceLifecycleState.ReadyForWarehouse)]
    [DataRow(DeviceLifecycleState.ReadyForDeployment, DeviceLifecycleState.DeploymentVerified)]
    [DataRow(DeviceLifecycleState.ReworkRequired, DeviceLifecycleState.ReadyForWarehouse)]
    [DataRow(DeviceLifecycleState.Archived, DeviceLifecycleState.Inspecting)]
    [DataRow(DeviceLifecycleState.ReadyForWarehouse, DeviceLifecycleState.ReadyForWarehouse)]
    public void RejectsSkippedTerminalOrNoOpTransitions(DeviceLifecycleState from, DeviceLifecycleState to)
    {
        Assert.IsFalse(DeviceLifecyclePolicy.CanTransition(from, to));
        Assert.ThrowsExactly<InvalidOperationException>(() => DeviceLifecyclePolicy.EnsureTransition(from, to));
    }
}
